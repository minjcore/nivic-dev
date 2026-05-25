#pragma once
#include <stdint.h>
#include <stddef.h>
#include <stdatomic.h>
#include "wire.h"

/*
 * ══════════════════════════════════════════════════════════════════════════
 *  LMAX Disruptor-style lock-free ring buffers
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  FrameRing (MPSC)  — IO threads   → single event-processor thread
 *  PushRing  (SPSC)  — event-proc   → single push-writer thread
 *
 *  Slot lifecycle:
 *    ready == -1     slot is free (consumer has finished with it)
 *    ready == N ≥ 0  slot holds data for sequence N
 *
 *  Memory ordering:
 *    producer: store(ready, N, release)  — makes slot data visible
 *    consumer: load(ready,    acquire)   — synchronises with producer
 * ══════════════════════════════════════════════════════════════════════════
 */

/* Internal frame type: connection closed — handled by event processor,
 * not dispatched to handle_frame(). Value chosen to avoid all Wire types. */
#define FRAME_CLOSE  0xFE

/* ─── Cache-line-padded sequence counter (avoids false sharing) ──────────── */
#define WIRE_CACHE_LINE 64

typedef struct {
    _Atomic uint64_t val;
    char _pad[WIRE_CACHE_LINE - sizeof(_Atomic uint64_t)];
} WireSeq;

/* ═══════════════════════════════════════════════════════════════════════════
 *  FrameRing  (Multi-Producer Single-Consumer)
 * ═══════════════════════════════════════════════════════════════════════════ */

#define FRAME_RING_SIZE  1024u
#define FRAME_RING_MASK  (FRAME_RING_SIZE - 1u)

typedef struct {
    _Atomic int64_t  ready;   /* -1=free; N=published at sequence N */
    int              fd;
    void            *db;      /* DB * — void* avoids circular header dep */
    void            *st;      /* SessionTable * */
    WireFrame        frame;
    uint8_t          raw[WIRE_MAX_FRAME]; /* original verified wire bytes */
    uint32_t         raw_len;             /* 0 for synthetic FRAME_CLOSE */
} FrameSlot;

typedef struct {
    FrameSlot        slots[FRAME_RING_SIZE];
    WireSeq          cursor;             /* next sequence to claim (producers) */
    _Atomic uint64_t total_published;   /* cumulative frames published (all producers) */
    _Atomic uint64_t total_consumed;    /* cumulative frames consumed (event processor) */
    char             _pad[WIRE_CACHE_LINE];
} FrameRing;

/* ═══════════════════════════════════════════════════════════════════════════
 *  PushRing  (Single-Producer Single-Consumer)
 * ═══════════════════════════════════════════════════════════════════════════ */

#define PUSH_RING_SIZE  512u
#define PUSH_RING_MASK  (PUSH_RING_SIZE - 1u)

typedef struct {
    _Atomic int64_t  ready;
    int              fd;
    uint32_t         _pad;
    size_t           len;
    uint8_t          buf[WIRE_MAX_FRAME];
} PushSlot;

typedef struct {
    PushSlot         slots[PUSH_RING_SIZE];
    uint64_t         prod_seq;          /* only ever written by producer thread */
    uint64_t         cons_seq;          /* only ever written by consumer thread */
    _Atomic uint64_t total_pushed;      /* cumulative outbound push frames enqueued */
} PushRing;

/* ─── FrameRing API ─────────────────────────────────────────────────────── */

void frame_ring_init(FrameRing *r);

/* Publish a parsed frame from an IO thread.
 * raw/raw_len: original verified wire bytes (pass NULL/0 for synthetic frames).
 * Spins (with back-off) if the ring is full — provides natural back-pressure. */
void frame_ring_publish(FrameRing *r, int fd, void *db, void *st,
                        const WireFrame *f,
                        const uint8_t *raw, uint32_t raw_len);

/* Publish a synthetic FRAME_CLOSE slot — event processor will call
 * registry_remove(fd) + close(fd), keeping fd lifetime on one thread. */
void frame_ring_publish_close(FrameRing *r, int fd, void *db, void *st);

/* Consume the next frame (blocking spin until available).
 * Call from exactly one consumer thread. Fills *out.
 * Pass the consumer's local cursor as `seq`; caller must call
 * frame_ring_release(r, seq) and then increment seq after processing. */
void frame_ring_consume(FrameRing *r, uint64_t seq, FrameSlot *out);

/* Release the slot at seq so producers can reuse it. */
void frame_ring_release(FrameRing *r, uint64_t seq);

/* ─── PushRing API ──────────────────────────────────────────────────────── */

void push_ring_init(PushRing *r);

/* Enqueue a push frame. Called only from the event-processor thread.
 * Spins if ring is full. */
void push_ring_enqueue(PushRing *r, int fd, const uint8_t *buf, size_t len);

/* Non-blocking dequeue. Returns 1 and fills *fd_out / *buf_out / *len_out,
 * or returns 0 if the ring is empty. Called only from push-writer thread. */
int  push_ring_try_dequeue(PushRing *r, int *fd_out,
                            uint8_t *buf_out, size_t *len_out);
