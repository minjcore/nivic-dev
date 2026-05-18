#include "iso8583.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <pthread.h>

/* ── Mock card ledger ───────────────────────────────────────────── */

typedef struct {
    const char *pan;
    long long   balance;  /* minor units (VND, no decimals) */
    int         blocked;
} Card;

static Card cards[] = {
    { "4111111111111111", 50000000LL,  0 },  /* VISA test — 50M VND */
    { "4000000000000002",  1000000LL,  0 },  /* VISA low balance    */
    { "5500000000000004", 100000000LL, 0 },  /* MC test — 100M VND  */
    { "4000000000000119",         0LL, 1 },  /* blocked             */
};
#define N_CARDS ((int)(sizeof(cards)/sizeof(cards[0])))

static pthread_mutex_t mu    = PTHREAD_MUTEX_INITIALIZER;
static int             stan  = 0;

static Card *find_card(const char *pan) {
    for (int i = 0; i < N_CARDS; i++)
        if (strcmp(cards[i].pan, pan) == 0) return &cards[i];
    return NULL;
}

/* ── Helpers ────────────────────────────────────────────────────── */

static void gen_auth(char out[7]) {
    static const char alpha[] = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789";
    for (int i = 0; i < 6; i++)
        out[i] = alpha[rand() % (int)(sizeof(alpha) - 1)];
    out[6] = '\0';
}

static void fill_datetime(Iso8583 *msg) {
    time_t t = time(NULL);
    struct tm *tm = gmtime(&t);
    char buf[16];
    /* DE7: MMDDhhmmss */
    snprintf(buf, sizeof(buf), "%02d%02d%02d%02d%02d",
             tm->tm_mon+1, tm->tm_mday, tm->tm_hour, tm->tm_min, tm->tm_sec);
    iso8583_set(msg, 7, buf);
    /* DE12: hhmmss */
    snprintf(buf, sizeof(buf), "%02d%02d%02d", tm->tm_hour, tm->tm_min, tm->tm_sec);
    iso8583_set(msg, 12, buf);
    /* DE13: MMDD */
    snprintf(buf, sizeof(buf), "%02d%02d", tm->tm_mon+1, tm->tm_mday);
    iso8583_set(msg, 13, buf);
}

/* ── Message handlers ───────────────────────────────────────────── */

static void handle_0800(const Iso8583 *req, Iso8583 *resp) {
    iso8583_make_response(req, resp);
    fill_datetime(resp);
    iso8583_set(resp, 39, RC_APPROVED);
    printf("  [0800] network mgmt echo → %s\n", RC_APPROVED);
}

static void handle_auth_or_financial(const Iso8583 *req, Iso8583 *resp) {
    iso8583_make_response(req, resp);
    fill_datetime(resp);

    const char *pan   = iso8583_get(req, 2);
    long long   amount = atoll(iso8583_get(req, 4));

    pthread_mutex_lock(&mu);
    Card *card = find_card(pan);

    if (!card) {
        pthread_mutex_unlock(&mu);
        iso8583_set(resp, 39, RC_INVALID_CARD);
        printf("  [%s] PAN=%.6s... → %s (invalid card)\n", req->mti, pan, RC_INVALID_CARD);
        return;
    }
    if (card->blocked) {
        pthread_mutex_unlock(&mu);
        iso8583_set(resp, 39, RC_DO_NOT_HONOR);
        printf("  [%s] PAN=%.6s... → %s (blocked)\n", req->mti, pan, RC_DO_NOT_HONOR);
        return;
    }
    if (amount > card->balance) {
        long long bal = card->balance;
        pthread_mutex_unlock(&mu);
        iso8583_set(resp, 39, RC_INSUF_FUNDS);
        printf("  [%s] PAN=%.6s... amt=%lld bal=%lld → %s\n",
               req->mti, pan, amount, bal, RC_INSUF_FUNDS);
        return;
    }

    /* Debit only on 0200 (financial), not 0100 (auth-only) */
    int is_financial = (req->mti[1] == '2');
    if (is_financial) card->balance -= amount;
    long long new_bal = card->balance;
    pthread_mutex_unlock(&mu);

    char auth[7]; gen_auth(auth);
    iso8583_set(resp, 38, auth);
    iso8583_set(resp, 39, RC_APPROVED);

    /* DE54: balance info  "C" + 3-digit currency + "D" + 12-digit amount */
    char bal_str[21];
    snprintf(bal_str, sizeof(bal_str), "C704D%012lld", new_bal);
    iso8583_set(resp, 54, bal_str);

    printf("  [%s] PAN=%.6s... amt=%lld auth=%s bal=%lld → %s%s\n",
           req->mti, pan, amount, auth, new_bal, RC_APPROVED,
           is_financial ? "" : " (auth only)");
}

/* ── Public entry point ─────────────────────────────────────────── */

void process_message(Iso8583 *req, Iso8583 *resp) {
    /* Assign STAN if missing */
    if (!req->has[11]) {
        pthread_mutex_lock(&mu);
        int s = ++stan % 1000000;
        pthread_mutex_unlock(&mu);
        char buf[7]; snprintf(buf, sizeof(buf), "%06d", s);
        iso8583_set(req, 11, buf);
    }

    if      (strcmp(req->mti, "0800") == 0) handle_0800(req, resp);
    else if (strcmp(req->mti, "0100") == 0) handle_auth_or_financial(req, resp);
    else if (strcmp(req->mti, "0200") == 0) handle_auth_or_financial(req, resp);
    else {
        iso8583_make_response(req, resp);
        iso8583_set(resp, 39, RC_INVALID_TRANS);
        printf("  [%s] unknown MTI → %s\n", req->mti, RC_INVALID_TRANS);
    }
}
