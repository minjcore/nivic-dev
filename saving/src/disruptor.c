#include "disruptor.h"
#include <string.h>
#include <time.h>
#include <sched.h>

/* Adaptive backoff: hot spin first for ultra-low latency on the uncontested
 * path, then escalate the sleep the longer the ring stays empty/full so a quiet
 * server burns ~no CPU. The caller's `count` is local to one wait episode and
 * starts at 0, so as soon as a frame arrives the loop exits and the next wait
 * begins hot again — under load we never leave the PAUSE tier.
 *
 *   count <  128   : PAUSE/ISB        (busy path — sub-µs latency, no syscall)
 *   count < 1024   : nanosleep 1 µs   (warm — still sub-ms latency)
 *   else           : nanosleep 1 ms   (deep idle — negligible CPU) */
static inline void spin_once(int *count) {
    int c = ++(*count);
    if (c < 128) {
        /* On x86 this becomes PAUSE; on ARM it's ISB or NOP — keeps the
         * pipeline sane without burning memory bandwidth. */
#if defined(__x86_64__) || defined(__i386__)
        __asm__ volatile("pause" ::: "memory");
#elif defined(__aarch64__) || defined(__arm__)
        __asm__ volatile("isb" ::: "memory");
#else
        atomic_thread_fence(memory_order_seq_cst);
#endif
    } else if (c < 1024) {
        struct timespec ts = {0, 1000};      /* 1 µs */
        nanosleep(&ts, NULL);
    } else {
        struct timespec ts = {0, 1000000};   /* 1 ms */
        nanosleep(&ts, NULL);
        if (c > (1 << 20)) *count = 1024;    /* clamp: stay in deep-idle tier, avoid overflow */
    }
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  FrameRing  (MPSC)
 * ═══════════════════════════════════════════════════════════════════════════ */

void frame_ring_init(FrameRing *r) {
    atomic_store_explicit(&r->cursor.val, 0, memory_order_relaxed);
    atomic_store_explicit(&r->total_published, 0, memory_order_relaxed);
    atomic_store_explicit(&r->total_consumed,  0, memory_order_relaxed);
    for (unsigned i = 0; i < FRAME_RING_SIZE; i++)
        atomic_store_explicit(&r->slots[i].ready, -1LL, memory_order_relaxed);
    atomic_thread_fence(memory_order_seq_cst);
}

void frame_ring_publish(FrameRing *r, int fd, void *db, void *st,
                        const WireFrame *f,
                        const uint8_t *raw, uint32_t raw_len) {
    /* Atomically claim a unique sequence number (multiple IO threads safe). */
    uint64_t seq = atomic_fetch_add_explicit(&r->cursor.val, 1,
                                              memory_order_acq_rel);
    FrameSlot *slot = &r->slots[seq & FRAME_RING_MASK];

    /* Back-pressure: spin until consumer has released this slot from the
     * previous lap around the ring. */
    int spins = 0;
    while (atomic_load_explicit(&slot->ready, memory_order_acquire) >= 0)
        spin_once(&spins);

    slot->fd      = fd;
    slot->db      = db;
    slot->st      = st;
    slot->raw_len = raw_len;
    if (raw_len > 0 && raw) memcpy(slot->raw, raw, raw_len);
    memcpy(&slot->frame, f, sizeof(WireFrame));

    /* Release-store: makes all slot writes visible to the consumer. */
    atomic_store_explicit(&slot->ready, (int64_t)seq, memory_order_release);
    atomic_fetch_add_explicit(&r->total_published, 1, memory_order_relaxed);
}

void frame_ring_publish_close(FrameRing *r, int fd, void *db, void *st) {
    WireFrame f;
    memset(&f, 0, sizeof(f));
    f.type = FRAME_CLOSE;
    frame_ring_publish(r, fd, db, st, &f, NULL, 0);
}

void frame_ring_consume(FrameRing *r, uint64_t seq, FrameSlot *out) {
    FrameSlot *slot = &r->slots[seq & FRAME_RING_MASK];
    int spins = 0;
    while (atomic_load_explicit(&slot->ready, memory_order_acquire) != (int64_t)seq)
        spin_once(&spins);

    out->fd      = slot->fd;
    out->db      = slot->db;
    out->st      = slot->st;
    out->raw_len = slot->raw_len;
    if (slot->raw_len > 0) memcpy(out->raw, slot->raw, slot->raw_len);
    memcpy(&out->frame, &slot->frame, sizeof(WireFrame));
}

void frame_ring_release(FrameRing *r, uint64_t seq) {
    FrameSlot *slot = &r->slots[seq & FRAME_RING_MASK];
    atomic_store_explicit(&slot->ready, -1LL, memory_order_release);
    atomic_fetch_add_explicit(&r->total_consumed, 1, memory_order_relaxed);
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  PushRing  (SPSC)
 * ═══════════════════════════════════════════════════════════════════════════ */

void push_ring_init(PushRing *r) {
    r->prod_seq = 0;
    r->cons_seq = 0;
    atomic_store_explicit(&r->total_pushed, 0, memory_order_relaxed);
    for (unsigned i = 0; i < PUSH_RING_SIZE; i++)
        atomic_store_explicit(&r->slots[i].ready, -1LL, memory_order_relaxed);
    atomic_thread_fence(memory_order_seq_cst);
}

void push_ring_enqueue(PushRing *r, int fd, const uint8_t *buf, size_t len) {
    uint64_t seq  = r->prod_seq;
    PushSlot *slot = &r->slots[seq & PUSH_RING_MASK];

    int spins = 0;
    while (atomic_load_explicit(&slot->ready, memory_order_acquire) >= 0)
        spin_once(&spins);

    slot->fd  = fd;
    slot->len = len;
    if (len > 0 && len <= WIRE_MAX_FRAME)
        memcpy(slot->buf, buf, len);

    atomic_store_explicit(&slot->ready, (int64_t)seq, memory_order_release);
    r->prod_seq = seq + 1;
    atomic_fetch_add_explicit(&r->total_pushed, 1, memory_order_relaxed);
}

int push_ring_try_dequeue(PushRing *r, int *fd_out,
                           uint8_t *buf_out, size_t *len_out) {
    uint64_t seq  = r->cons_seq;
    PushSlot *slot = &r->slots[seq & PUSH_RING_MASK];

    if (atomic_load_explicit(&slot->ready, memory_order_acquire) != (int64_t)seq)
        return 0;

    *fd_out  = slot->fd;
    *len_out = slot->len;
    if (slot->len > 0 && slot->len <= WIRE_MAX_FRAME)
        memcpy(buf_out, slot->buf, slot->len);

    atomic_store_explicit(&slot->ready, -1LL, memory_order_release);
    r->cons_seq = seq + 1;
    return 1;
}
