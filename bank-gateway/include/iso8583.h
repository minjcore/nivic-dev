#pragma once
#include <stdint.h>

#define ISO_FIELD_MAX  256
#define ISO_MAX_DE     128

/* Response codes */
#define RC_APPROVED        "00"
#define RC_DO_NOT_HONOR    "05"
#define RC_INVALID_TRANS   "12"
#define RC_INVALID_AMOUNT  "13"
#define RC_INVALID_CARD    "14"
#define RC_INSUF_FUNDS     "51"
#define RC_EXPIRED_CARD    "54"
#define RC_SYSTEM_ERROR    "96"

typedef struct {
    char    mti[5];                          /* e.g. "0200" */
    uint8_t bitmap[16];                      /* primary (8B) + optional secondary (8B) */
    char    f[ISO_MAX_DE + 1][ISO_FIELD_MAX];
    int     has[ISO_MAX_DE + 1];
} Iso8583;

int         iso8583_unpack      (const uint8_t *buf, int len, Iso8583 *out);
int         iso8583_pack        (const Iso8583 *msg, uint8_t *buf, int maxlen);
void        iso8583_set         (Iso8583 *msg, int de, const char *val);
const char *iso8583_get         (const Iso8583 *msg, int de);
void        iso8583_make_response(const Iso8583 *req, Iso8583 *resp);
