#include "iso8583.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>

/*
 * DE table: { max_len, var_prefix (0=fixed, 2=LLVAR, 3=LLLVAR), is_numeric }
 * Encoding: ASCII MTI + binary bitmap + ASCII field data.
 * Numeric fixed fields: zero-padded left.  AN fixed fields: space-padded right.
 */
typedef struct { int len; int var; int numeric; } DeDef;

static const DeDef defs[129] = {
    [2]  = { 19, 2, 1 },  /* PAN                         LLVAR n..19  */
    [3]  = {  6, 0, 1 },  /* Processing Code             n6           */
    [4]  = { 12, 0, 1 },  /* Amount, Transaction         n12          */
    [7]  = { 10, 0, 1 },  /* Transmission Date/Time      n10 MMDDhhmmss */
    [11] = {  6, 0, 1 },  /* STAN                        n6           */
    [12] = {  6, 0, 1 },  /* Local Transaction Time      n6 hhmmss    */
    [13] = {  4, 0, 1 },  /* Local Transaction Date      n4 MMDD      */
    [37] = { 12, 0, 0 },  /* Retrieval Reference Number  an12         */
    [38] = {  6, 0, 0 },  /* Authorization ID Response   an6          */
    [39] = {  2, 0, 0 },  /* Response Code               an2          */
    [41] = {  8, 0, 0 },  /* Card Acceptor Terminal ID   ans8         */
    [42] = { 15, 0, 0 },  /* Card Acceptor ID (MID)      ans15        */
    [49] = {  3, 0, 1 },  /* Currency Code               n3           */
    [54] = { 20, 0, 0 },  /* Additional Amounts (balance) an20        */
};

/* ── Bitmap helpers ─────────────────────────────────────────────── */

static int bm_test(const uint8_t *bm, int de) {
    int bit = de - 1;
    return (bm[bit / 8] >> (7 - bit % 8)) & 1;
}

static void bm_set(uint8_t *bm, int de) {
    int bit = de - 1;
    bm[bit / 8] |= (uint8_t)(1 << (7 - bit % 8));
}

/* ── Unpack ─────────────────────────────────────────────────────── */

int iso8583_unpack(const uint8_t *buf, int len, Iso8583 *out) {
    if (len < 4 + 8) return -1;
    memset(out, 0, sizeof(*out));

    memcpy(out->mti, buf, 4);
    out->mti[4] = '\0';
    int pos = 4;

    memcpy(out->bitmap, buf + pos, 8);
    pos += 8;

    if (bm_test(out->bitmap, 1)) {
        if (pos + 8 > len) return -1;
        memcpy(out->bitmap + 8, buf + pos, 8);
        pos += 8;
    }

    for (int de = 2; de <= ISO_MAX_DE; de++) {
        if (!bm_test(out->bitmap, de)) continue;
        const DeDef *d = &defs[de];
        if (d->len == 0) return -1; /* unknown DE */

        int flen = d->len;
        if (d->var == 2) {
            if (pos + 2 > len) return -1;
            char tmp[3]; memcpy(tmp, buf + pos, 2); tmp[2] = '\0';
            flen = atoi(tmp);
            pos += 2;
        } else if (d->var == 3) {
            if (pos + 3 > len) return -1;
            char tmp[4]; memcpy(tmp, buf + pos, 3); tmp[3] = '\0';
            flen = atoi(tmp);
            pos += 3;
        }

        if (flen < 0 || flen >= ISO_FIELD_MAX || pos + flen > len) return -1;
        memcpy(out->f[de], buf + pos, flen);
        out->f[de][flen] = '\0';
        out->has[de] = 1;
        pos += flen;
    }
    return pos;
}

/* ── Pack ───────────────────────────────────────────────────────── */

int iso8583_pack(const Iso8583 *msg, uint8_t *buf, int maxlen) {
    if (maxlen < 4 + 8) return -1;
    int pos = 0;

    memcpy(buf + pos, msg->mti, 4);
    pos += 4;

    int bm_pos = pos;
    uint8_t bm[8] = {0};
    pos += 8;

    for (int de = 2; de <= ISO_MAX_DE; de++) {
        if (!msg->has[de]) continue;
        const DeDef *d = &defs[de];
        if (d->len == 0) continue;

        bm_set(bm, de);

        int vlen = (int)strlen(msg->f[de]);

        if (d->var == 2) {
            if (pos + 2 + vlen > maxlen) return -1;
            snprintf((char *)buf + pos, 3, "%02d", vlen);
            pos += 2;
            memcpy(buf + pos, msg->f[de], vlen);
            pos += vlen;
        } else if (d->var == 3) {
            if (pos + 3 + vlen > maxlen) return -1;
            snprintf((char *)buf + pos, 4, "%03d", vlen);
            pos += 3;
            memcpy(buf + pos, msg->f[de], vlen);
            pos += vlen;
        } else {
            /* Fixed length — pad to d->len */
            if (pos + d->len > maxlen) return -1;
            char padded[ISO_FIELD_MAX];
            if (d->numeric) {
                /* zero-pad left */
                memset(padded, '0', d->len);
                int copy = vlen < d->len ? vlen : d->len;
                memcpy(padded + d->len - copy,
                       msg->f[de] + (vlen > d->len ? vlen - d->len : 0), copy);
            } else {
                /* space-pad right */
                memset(padded, ' ', d->len);
                int copy = vlen < d->len ? vlen : d->len;
                memcpy(padded, msg->f[de], copy);
            }
            memcpy(buf + pos, padded, d->len);
            pos += d->len;
        }
    }

    memcpy(buf + bm_pos, bm, 8);
    return pos;
}

/* ── Helpers ────────────────────────────────────────────────────── */

void iso8583_set(Iso8583 *msg, int de, const char *val) {
    if (de < 1 || de > ISO_MAX_DE) return;
    snprintf(msg->f[de], ISO_FIELD_MAX, "%s", val);
    msg->has[de] = 1;
}

const char *iso8583_get(const Iso8583 *msg, int de) {
    if (de < 1 || de > ISO_MAX_DE || !msg->has[de]) return "";
    return msg->f[de];
}

/*
 * Build response MTI: second digit 0→1  (0200→0210, 0100→0110, 0800→0810)
 * Echo back the "mirror" fields.
 */
void iso8583_make_response(const Iso8583 *req, Iso8583 *resp) {
    memset(resp, 0, sizeof(*resp));
    memcpy(resp->mti, req->mti, 4);
    resp->mti[2] = '1';
    resp->mti[4] = '\0';

    static const int echo[] = { 2, 3, 4, 7, 11, 12, 13, 37, 41, 42, 49, 0 };
    for (int i = 0; echo[i]; i++)
        if (req->has[echo[i]])
            iso8583_set(resp, echo[i], req->f[echo[i]]);
}
