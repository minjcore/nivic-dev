#include "wire.h"
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <CommonCrypto/CommonHMAC.h>

static const char WIRE_SECRET[] = "saving_wire_secret_changeme";

/* ─── Byte-order helpers ─────────────────────────────────────────────────── */

static inline uint32_t rd32(const uint8_t *p) {
    return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3];
}

static inline void wr32(uint8_t *p, uint32_t v) {
    p[0]=v>>24; p[1]=v>>16; p[2]=v>>8; p[3]=v;
}

/* ─── HMAC-SHA256 ────────────────────────────────────────────────────────── */

static void hmac_sha256(const uint8_t *data, size_t len, uint8_t out[WIRE_SIG_SIZE]) {
    CCHmac(kCCHmacAlgSHA256,
           WIRE_SECRET, sizeof(WIRE_SECRET) - 1,
           data, len,
           out);
}

/* ─── wire_frame_parse ───────────────────────────────────────────────────── */

int wire_frame_parse(const uint8_t *buf, size_t len, WireFrame *f) {
    if (len < WIRE_FRAME_OVERHEAD) return WIRE_ERR_BAD_FRAME;

    uint32_t frame_len = rd32(buf);
    if (frame_len != (uint32_t)len)  return WIRE_ERR_BAD_FRAME;
    if (frame_len > WIRE_MAX_FRAME)  return WIRE_ERR_BAD_FRAME;

    /* Verify HMAC over everything except the trailing sig */
    size_t sig_offset = len - WIRE_SIG_SIZE;
    uint8_t expected[WIRE_SIG_SIZE];
    hmac_sha256(buf, sig_offset, expected);
    if (memcmp(buf + sig_offset, expected, WIRE_SIG_SIZE) != 0)
        return WIRE_ERR_BAD_SIG;

    f->len      = frame_len;
    f->type     = buf[4];
    f->seq      = rd32(buf + 5);
    f->body_len = (uint16_t)(sig_offset - 9);  /* 9 = 4(len)+1(type)+4(seq) */
    memcpy(f->body, buf + 9, f->body_len);
    return WIRE_OK;
}

/* ─── wire_frame_encode ──────────────────────────────────────────────────── */

size_t wire_frame_encode(uint8_t type, uint32_t seq,
                         const uint8_t *body, uint16_t body_len,
                         uint8_t *buf, size_t buf_size) {
    size_t total = WIRE_FRAME_OVERHEAD + body_len;
    if (total > buf_size) return 0;

    wr32(buf, (uint32_t)total);
    buf[4] = type;
    wr32(buf + 5, seq);
    if (body_len > 0 && body) memcpy(buf + 9, body, body_len);

    uint8_t sig[WIRE_SIG_SIZE];
    hmac_sha256(buf, 9 + body_len, sig);
    memcpy(buf + 9 + body_len, sig, WIRE_SIG_SIZE);
    return total;
}

/* ─── wire_ack ───────────────────────────────────────────────────────────── */

size_t wire_ack(uint32_t seq, uint8_t code,
                const uint8_t *extra, uint16_t extra_len,
                uint8_t *buf, size_t buf_size) {
    uint8_t body[1 + WIRE_MAX_BODY];
    body[0] = code;
    if (extra && extra_len > 0) memcpy(body + 1, extra, extra_len);
    return wire_frame_encode(WIRE_ACK, seq, body, (uint16_t)(1 + extra_len),
                             buf, buf_size);
}

/* ─── wire_recv_frame ────────────────────────────────────────────────────── */

int wire_recv_frame(int fd, WireFrame *f) {
    uint8_t hdr[4];
    ssize_t n = recv(fd, hdr, 4, MSG_WAITALL);
    if (n != 4) return -1;

    uint32_t total = rd32(hdr);
    if (total < WIRE_FRAME_OVERHEAD || total > WIRE_MAX_FRAME)
        return WIRE_ERR_BAD_FRAME;

    uint8_t buf[WIRE_MAX_FRAME];
    memcpy(buf, hdr, 4);
    n = recv(fd, buf + 4, total - 4, MSG_WAITALL);
    if (n != (ssize_t)(total - 4)) return -1;

    return wire_frame_parse(buf, total, f);
}

/* ─── wire_send_raw ──────────────────────────────────────────────────────── */

int wire_send_raw(int fd, const uint8_t *buf, size_t len) {
    while (len > 0) {
        ssize_t n = send(fd, buf, len, 0);
        if (n <= 0) return -1;
        buf += n;
        len -= (size_t)n;
    }
    return 0;
}
