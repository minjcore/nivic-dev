#include "wal.h"
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <stdio.h>
#include <time.h>
#include <arpa/inet.h>

/* Write all bytes to fd, retrying on EINTR. */
static int write_all(int fd, const void *buf, size_t len) {
    const uint8_t *p = buf;
    while (len > 0) {
        ssize_t n = write(fd, p, len);
        if (n <= 0) return -1;
        p   += n;
        len -= (size_t)n;
    }
    return 0;
}

/* CRC-32 (IEEE 802.3 / Ethernet polynomial 0xEDB88320, reflected).
 * Used to detect WAL corruption on replay.  Not a security primitive. */
static uint32_t crc32_update(uint32_t crc, const uint8_t *data, size_t len) {
    crc = ~crc;
    for (size_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (int b = 0; b < 8; b++)
            crc = (crc >> 1) ^ (0xEDB88320u & (uint32_t)(-(int32_t)(crc & 1u)));
    }
    return ~crc;
}

static inline uint64_t now_ns(uint64_t t0) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec - t0;
}

int wal_open(WAL *w, const char *path) {
    w->fd = -1;
    w->t0_ns = 0;
    if (!path) return 0;   /* WAL disabled */

    int fd = open(path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0640);
    if (fd < 0) { perror("wal_open"); return -1; }

    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    w->t0_ns = (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
    w->fd    = fd;

    fprintf(stderr, "[wal] opened %s\n", path);
    return 0;
}

/*
 * Entry layout (all big-endian):
 *   magic  2B  = WAL_MAGIC
 *   len    4B  = raw_len (0 if no frame bytes — e.g. FRAME_CLOSE)
 *   fd_num 4B  = client fd (informational)
 *   ts_ns  8B  = nanoseconds since server start
 *   data   lenB
 */
void wal_append(WAL *w, int client_fd, const uint8_t *raw_buf, uint32_t raw_len) {
    if (w->fd < 0) return;

    uint8_t hdr[18];
    uint64_t ts = now_ns(w->t0_ns);

    /* magic */
    hdr[0] = (WAL_MAGIC >> 8) & 0xFF;
    hdr[1] =  WAL_MAGIC       & 0xFF;
    /* len */
    hdr[2] = (raw_len >> 24) & 0xFF;
    hdr[3] = (raw_len >> 16) & 0xFF;
    hdr[4] = (raw_len >>  8) & 0xFF;
    hdr[5] =  raw_len        & 0xFF;
    /* fd */
    uint32_t fdu = (uint32_t)client_fd;
    hdr[6]  = (fdu >> 24) & 0xFF;
    hdr[7]  = (fdu >> 16) & 0xFF;
    hdr[8]  = (fdu >>  8) & 0xFF;
    hdr[9]  =  fdu        & 0xFF;
    /* ts_ns */
    hdr[10] = (ts >> 56) & 0xFF;
    hdr[11] = (ts >> 48) & 0xFF;
    hdr[12] = (ts >> 40) & 0xFF;
    hdr[13] = (ts >> 32) & 0xFF;
    hdr[14] = (ts >> 24) & 0xFF;
    hdr[15] = (ts >> 16) & 0xFF;
    hdr[16] = (ts >>  8) & 0xFF;
    hdr[17] =  ts        & 0xFF;

    /* CRC-32 over header + frame data, appended as 4 big-endian bytes */
    uint32_t crc = crc32_update(0, hdr, sizeof(hdr));
    if (raw_len > 0 && raw_buf)
        crc = crc32_update(crc, raw_buf, raw_len);
    uint8_t crc_bytes[4];
    crc_bytes[0] = (crc >> 24) & 0xFF;
    crc_bytes[1] = (crc >> 16) & 0xFF;
    crc_bytes[2] = (crc >>  8) & 0xFF;
    crc_bytes[3] =  crc        & 0xFF;

    write_all(w->fd, hdr, sizeof(hdr));
    if (raw_len > 0 && raw_buf)
        write_all(w->fd, raw_buf, raw_len);
    write_all(w->fd, crc_bytes, sizeof(crc_bytes));

    /* fdatasync every entry — durability guarantee before handle_frame runs */
    fdatasync(w->fd);
}

void wal_close(WAL *w) {
    if (w->fd >= 0) { close(w->fd); w->fd = -1; }
}
