#include "wire.h"
#include "db.h"
#include "handlers.h"
#include "registry.h"
#include "disruptor.h"
#include "wal.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <signal.h>
#include <time.h>

/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Thread model (LMAX Disruptor pattern)
 * ══════════════════════════════════════════════════════════════════════════
 *
 *   accept()
 *     │
 *     ▼
 *   IO thread (one per connection)
 *     wire_recv_frame()           — blocks on network I/O
 *     HMAC already verified       — wire_frame_parse does this
 *     frame_ring_publish()        — lock-free MPSC publish
 *     │
 *     ▼  (no more shared-state access from IO thread)
 *   FrameRing (MPSC, 1024 slots)
 *     │
 *     ▼
 *   event_processor (single thread)
 *     wal_append()               — fdatasync before business logic
 *     handle_frame()             — zero mutex contention: only thread
 *                                  touching SessionTable + DB
 *     registry_push() calls      — enqueue to PushRing (non-blocking)
 *     │
 *     ▼
 *   PushRing (SPSC, 512 slots)
 *     │
 *     ▼
 *   push_writer (single thread)
 *     wire_send_raw()            — blocking sends isolated here
 *
 * ══════════════════════════════════════════════════════════════════════════
 */

#define SAVING_PORT  7474
#define BACKLOG      128

/* Global ring — shared between all IO threads and the event processor. */
static FrameRing g_frame_ring;

typedef struct {
    int           client_fd;
    DB           *db;
    SessionTable *st;
} WorkerCtx;

/* ─── IO thread ─────────────────────────────────────────────────────────── */

static void *io_worker(void *arg) {
    WorkerCtx *ctx = (WorkerCtx *)arg;
    int           fd = ctx->client_fd;
    DB           *db = ctx->db;
    SessionTable *st = ctx->st;
    free(ctx);

    WireFrame f;
    uint8_t   raw[WIRE_MAX_FRAME];
    uint32_t  raw_len;
    int rc;
    while ((rc = wire_recv_frame_raw(fd, &f, raw, &raw_len)) != -1) {
        if (rc != WIRE_OK) {
            /* Bad frame (HMAC fail, malformed): reply inline from IO thread.
             * This does NOT touch any shared mutable state. */
            uint8_t buf[WIRE_MAX_FRAME];
            size_t n = wire_ack(0, (uint8_t)rc, NULL, 0, buf, sizeof(buf));
            if (n > 0) wire_send_raw(fd, buf, n);
            continue;
        }
        /* Good frame: publish verified raw bytes + parsed frame to ring. */
        frame_ring_publish(&g_frame_ring, fd, db, st, &f, raw, raw_len);
    }

    /* Connection closed — publish a CLOSE sentinel so the event processor
     * handles registry_remove + close(fd) in the correct thread order.
     * We must NOT close(fd) here: there may still be queued frames for
     * this fd in the ring that the event processor hasn't processed yet. */
    frame_ring_publish_close(&g_frame_ring, fd, db, st);
    return NULL;
}

/* ─── Event processor (single thread) ──────────────────────────────────── */

typedef struct {
    FrameRing *ring;
    WAL       *wal;
} EventProcCtx;

static void *event_processor(void *arg) {
    EventProcCtx *ctx  = (EventProcCtx *)arg;
    FrameRing    *ring = ctx->ring;
    WAL          *wal  = ctx->wal;
    /* ctx is stack-allocated in server_run — do NOT free it */

    uint64_t  next = 0;
    FrameSlot slot;

    while (1) {
        frame_ring_consume(ring, next, &slot);

        if (slot.frame.type == FRAME_CLOSE) {
            /* Tidy up: remove from registry so no more push events are
             * enqueued for this fd, then close the socket. */
            registry_remove(slot.fd);
            close(slot.fd);
            frame_ring_release(ring, next);
            next++;
            continue;
        }

        /* WAL: original verified wire bytes, stored in the slot by the IO
         * thread immediately after HMAC check passed — no re-encode needed. */
        wal_append(wal, slot.fd, slot.raw_len ? slot.raw : NULL, slot.raw_len);

        /* Business logic — runs on exactly ONE thread: no mutex contention
         * on SessionTable, no concurrent DB access. */
        handle_frame((DB *)slot.db, (SessionTable *)slot.st,
                     slot.fd, &slot.frame);

        frame_ring_release(ring, next);
        next++;
    }

    return NULL;
}

/* ─── Push writer (single thread) ───────────────────────────────────────── */

static void *push_writer(void *arg) {
    PushRing *ring = (PushRing *)arg;
    int      fd;
    uint8_t  buf[WIRE_MAX_FRAME];
    size_t   len;
    struct timespec ts = {0, 50000};  /* 50 µs idle sleep */

    while (1) {
        int drained = 0;
        while (push_ring_try_dequeue(ring, &fd, buf, &len)) {
            wire_send_raw(fd, buf, len);
            drained = 1;
        }
        if (!drained) nanosleep(&ts, NULL);
    }

    return NULL;
}

/* ─── Main server loop ──────────────────────────────────────────────────── */

void server_run(DB *db, const char *wal_path) {
    signal(SIGPIPE, SIG_IGN);

    registry_init();
    frame_ring_init(&g_frame_ring);

    WAL wal;
    wal_open(&wal, wal_path);

    SessionTable *st = session_table_new();

    /* Start event processor thread */
    EventProcCtx ep_ctx = { &g_frame_ring, &wal };
    pthread_t ep_tid;
    pthread_attr_t ep_attr;
    pthread_attr_init(&ep_attr);
    pthread_attr_setdetachstate(&ep_attr, PTHREAD_CREATE_DETACHED);
    pthread_create(&ep_tid, &ep_attr, event_processor, &ep_ctx);
    pthread_attr_destroy(&ep_attr);

    /* Start push writer thread */
    pthread_t pw_tid;
    pthread_attr_t pw_attr;
    pthread_attr_init(&pw_attr);
    pthread_attr_setdetachstate(&pw_attr, PTHREAD_CREATE_DETACHED);
    pthread_create(&pw_tid, &pw_attr, push_writer, registry_get_push_ring());
    pthread_attr_destroy(&pw_attr);

    /* Accept socket */
    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd < 0) { perror("socket"); exit(1); }

    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr = {
        .sin_family      = AF_INET,
        .sin_addr.s_addr = INADDR_ANY,
        .sin_port        = htons(SAVING_PORT)
    };
    if (bind(server_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("bind"); exit(1);
    }
    if (listen(server_fd, BACKLOG) < 0) { perror("listen"); exit(1); }

    printf("[saving] Wire server listening on :%d  |  WAL: %s\n",
           SAVING_PORT, wal_path ? wal_path : "disabled");
    printf("[saving] Disruptor: FrameRing[%u]  PushRing[%u]\n",
           FRAME_RING_SIZE, PUSH_RING_SIZE);

    while (1) {
        struct sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);
        int client_fd = accept(server_fd, (struct sockaddr *)&client_addr,
                                &client_len);
        if (client_fd < 0) { perror("accept"); continue; }

        WorkerCtx *ctx = malloc(sizeof(WorkerCtx));
        ctx->client_fd = client_fd;
        ctx->db        = db;
        ctx->st        = st;

        pthread_t tid;
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
        pthread_create(&tid, &attr, io_worker, ctx);
        pthread_attr_destroy(&attr);
    }

    /* unreachable — but clean up if we ever add graceful shutdown */
    wal_close(&wal);
    session_table_free(st);
}
