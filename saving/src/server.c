#include "wire.h"
#include "db.h"
#include "handlers.h"
#include "registry.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <signal.h>

#define SAVING_PORT  7474
#define BACKLOG      128

typedef struct {
    int           client_fd;
    DB           *db;
    SessionTable *st;
} WorkerCtx;

static void *worker(void *arg) {
    WorkerCtx *ctx = (WorkerCtx *)arg;
    int fd          = ctx->client_fd;
    DB           *db = ctx->db;
    SessionTable *st = ctx->st;
    free(ctx);

    WireFrame f;
    int rc;
    while ((rc = wire_recv_frame(fd, &f)) != -1) {
        if (rc != WIRE_OK) {
            /* Bad frame: reply with error, keep connection alive */
            uint8_t buf[WIRE_MAX_FRAME];
            size_t n = wire_ack(0, (uint8_t)rc, NULL, 0, buf, sizeof(buf));
            if (n > 0) wire_send_raw(fd, buf, n);
            continue;
        }
        handle_frame(db, st, fd, &f);
    }

    registry_remove(fd);
    close(fd);
    return NULL;
}

void server_run(DB *db) {
    signal(SIGPIPE, SIG_IGN);
    registry_init();

    SessionTable *st = session_table_new();

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

    printf("[saving] Wire server listening on :%d\n", SAVING_PORT);

    while (1) {
        struct sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);
        int client_fd = accept(server_fd, (struct sockaddr *)&client_addr, &client_len);
        if (client_fd < 0) { perror("accept"); continue; }

        WorkerCtx *ctx = malloc(sizeof(WorkerCtx));
        ctx->client_fd = client_fd;
        ctx->db        = db;
        ctx->st        = st;

        pthread_t tid;
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
        pthread_create(&tid, &attr, worker, ctx);
        pthread_attr_destroy(&attr);
    }

    session_table_free(st);
}
