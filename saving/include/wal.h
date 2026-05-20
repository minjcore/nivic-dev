#pragma once
#include "wire.h"
#include <stdint.h>

/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Wire Write-Ahead Log
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  Append-only binary log.  Each entry is written by the event-processor
 *  thread *before* handle_frame() executes, so every verified frame has a
 *  durable record regardless of whether the handler crashes mid-way.
 *
 *  Entry format (big-endian):
 *
 *    ┌──────────┬──────────┬────────┬──────────┬──────────────────────┐
 *    │ magic 2B │  len  4B │ fd  4B │  ts_ns 8B│  raw frame (len B)   │
 *    └──────────┴──────────┴────────┴──────────┴──────────────────────┘
 *
 *    magic  = 0xWA1E (sanity check on replay)
 *    len    = byte count of raw frame
 *    fd     = client descriptor at time of receipt (informational)
 *    ts_ns  = CLOCK_MONOTONIC nanoseconds since server start
 *    frame  = the raw encoded frame (including HMAC — already verified)
 *
 *  Thread safety: WAL functions must only be called from one thread
 *  (the event processor).  No internal locking.
 * ══════════════════════════════════════════════════════════════════════════
 */

#define WAL_MAGIC   0xA1E0u    /* two bytes on wire: 0xA1, 0xE0 */

typedef struct {
    int      fd;        /* open file descriptor, -1 = disabled */
    uint64_t t0_ns;     /* CLOCK_MONOTONIC at wal_open() */
} WAL;

/* Open (or create) the WAL file at path.  Returns 0 on success, -1 on error.
 * Pass path=NULL to disable WAL (writes become no-ops). */
int  wal_open(WAL *w, const char *path);

/* Append one entry.  Performs an fdatasync after the write for durability.
 * The frame is the *raw encoded* bytes (already HMAC-verified).
 * raw_buf / raw_len come from the re-encoded frame; if not available,
 * pass NULL / 0 and only the header is written (useful for FRAME_CLOSE). */
void wal_append(WAL *w, int fd, const uint8_t *raw_buf, uint32_t raw_len);

void wal_close(WAL *w);
