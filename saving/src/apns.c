#include "apns.h"
#include <curl/curl.h>
#include <openssl/evp.h>
#include <openssl/ecdsa.h>
#include <openssl/pem.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* ─── Config ─────────────────────────────────────────────────────────────── */
static char       g_key_id[32];
static char       g_team_id[32];
static char       g_bundle_id[128];
static int        g_sandbox;
static EVP_PKEY  *g_pkey;
static int        g_enabled;

/* ─── JWT cache (refreshed every 45 min) ─────────────────────────────────── */
static char            g_jwt[1280];
static time_t          g_jwt_time;
static pthread_mutex_t g_jwt_mu = PTHREAD_MUTEX_INITIALIZER;

/* ─── Async job queue ─────────────────────────────────────────────────────── */
typedef struct ApnsJob {
    char device_token[128];
    char title[256];
    char body[256];
    struct ApnsJob *next;
} ApnsJob;

static ApnsJob        *g_head, *g_tail;
static pthread_mutex_t g_qmu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_qcv = PTHREAD_COND_INITIALIZER;

/* ─── Base64url ───────────────────────────────────────────────────────────── */
static const char kB64[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

static void b64url(const unsigned char *in, size_t len, char *out) {
    size_t i = 0, o = 0;
    while (i < len) {
        unsigned int b = (unsigned int)in[i++] << 16;
        if (i < len) b |= (unsigned int)in[i++] << 8;
        if (i < len) b |= in[i++];
        out[o++] = kB64[(b >> 18) & 63];
        out[o++] = kB64[(b >> 12) & 63];
        out[o++] = kB64[(b >>  6) & 63];
        out[o++] = kB64[b & 63];
    }
    /* strip base64 padding to get base64url */
    size_t real = (len * 4 + 2) / 3;
    out[real] = '\0';
}

/* ─── JWT generation ─────────────────────────────────────────────────────── */
static int make_jwt(char *out, size_t outlen) {
    char hdr[64], pay[96];
    snprintf(hdr, sizeof(hdr), "{\"alg\":\"ES256\",\"kid\":\"%s\"}", g_key_id);
    snprintf(pay, sizeof(pay), "{\"iss\":\"%s\",\"iat\":%ld}",
             g_team_id, (long)time(NULL));

    /* base64url encode header and payload */
    char hb[128], pb[128];
    b64url((unsigned char *)hdr, strlen(hdr), hb);
    b64url((unsigned char *)pay, strlen(pay),  pb);

    /* signing input: "header.payload" */
    char sign_in[256];
    snprintf(sign_in, sizeof(sign_in), "%s.%s", hb, pb);

    /* ECDSA-SHA256 sign */
    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    if (!ctx) return -1;
    if (EVP_DigestSignInit(ctx, NULL, EVP_sha256(), NULL, g_pkey) != 1 ||
        EVP_DigestSignUpdate(ctx, sign_in, strlen(sign_in)) != 1) {
        EVP_MD_CTX_free(ctx); return -1;
    }
    size_t derlen = 0;
    EVP_DigestSignFinal(ctx, NULL, &derlen);
    unsigned char *der = malloc(derlen);
    if (!der) { EVP_MD_CTX_free(ctx); return -1; }
    EVP_DigestSignFinal(ctx, der, &derlen);
    EVP_MD_CTX_free(ctx);

    /* convert DER → raw r||s (32+32 bytes) for JWT */
    const unsigned char *p = der;
    ECDSA_SIG *sig = d2i_ECDSA_SIG(NULL, &p, (long)derlen);
    free(der);
    if (!sig) return -1;
    const BIGNUM *r, *s;
    ECDSA_SIG_get0(sig, &r, &s);
    unsigned char raw[64] = {0};
    BN_bn2binpad(r, raw,      32);
    BN_bn2binpad(s, raw + 32, 32);
    ECDSA_SIG_free(sig);

    char sb[128];
    b64url(raw, 64, sb);

    snprintf(out, outlen, "%s.%s.%s", hb, pb, sb);
    return 0;
}

static const char *get_cached_jwt(void) {
    pthread_mutex_lock(&g_jwt_mu);
    time_t now = time(NULL);
    if (!g_jwt[0] || now - g_jwt_time > 45 * 60) {
        if (make_jwt(g_jwt, sizeof(g_jwt)) == 0)
            g_jwt_time = now;
        else
            g_jwt[0] = '\0';
    }
    pthread_mutex_unlock(&g_jwt_mu);
    return g_jwt[0] ? g_jwt : NULL;
}

/* ─── JSON string escape (minimal: backslash-escape " and \) ─────────────── */
static void json_esc(const char *in, char *out, size_t outlen) {
    size_t o = 0;
    for (const char *p = in; *p && o + 3 < outlen; p++) {
        if (*p == '"' || *p == '\\') out[o++] = '\\';
        out[o++] = *p;
    }
    out[o] = '\0';
}

/* ─── Actual HTTP/2 APNs call ────────────────────────────────────────────── */
static size_t discard_write(void *p, size_t sz, size_t n, void *u) {
    (void)p; (void)u; return sz * n;
}

static void do_push(const char *device_token, const char *title, const char *body) {
    const char *jwt = get_cached_jwt();
    if (!jwt) return;

    char url[256];
    snprintf(url, sizeof(url), "https://%s/3/device/%s",
             g_sandbox ? "api.sandbox.push.apple.com" : "api.push.apple.com",
             device_token);

    char auth_hdr[1300];
    snprintf(auth_hdr, sizeof(auth_hdr), "authorization: bearer %s", jwt);

    char topic_hdr[192];
    snprintf(topic_hdr, sizeof(topic_hdr), "apns-topic: %s", g_bundle_id);

    char jtitle[256], jbody[256];
    json_esc(title, jtitle, sizeof(jtitle));
    json_esc(body,  jbody,  sizeof(jbody));

    char json[1024];
    snprintf(json, sizeof(json),
             "{\"aps\":{\"alert\":{\"title\":\"%s\",\"body\":\"%s\"},"
             "\"sound\":\"default\"}}",
             jtitle, jbody);

    CURL *curl = curl_easy_init();
    if (!curl) return;

    struct curl_slist *hdrs = NULL;
    hdrs = curl_slist_append(hdrs, auth_hdr);
    hdrs = curl_slist_append(hdrs, topic_hdr);
    hdrs = curl_slist_append(hdrs, "content-type: application/json");
    hdrs = curl_slist_append(hdrs, "apns-push-type: alert");
    hdrs = curl_slist_append(hdrs, "apns-priority: 10");

    curl_easy_setopt(curl, CURLOPT_URL,           url);
    curl_easy_setopt(curl, CURLOPT_HTTP_VERSION,  CURL_HTTP_VERSION_2_0);
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER,    hdrs);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS,    json);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, (long)strlen(json));
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, discard_write);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT,       10L);

    CURLcode res = curl_easy_perform(curl);
    if (res != CURLE_OK)
        fprintf(stderr, "[apns] curl error: %s\n", curl_easy_strerror(res));

    curl_slist_free_all(hdrs);
    curl_easy_cleanup(curl);
}

/* ─── Worker thread ──────────────────────────────────────────────────────── */
static void *apns_worker(void *arg) {
    (void)arg;
    for (;;) {
        pthread_mutex_lock(&g_qmu);
        while (!g_head)
            pthread_cond_wait(&g_qcv, &g_qmu);
        ApnsJob *job = g_head;
        g_head = job->next;
        if (!g_head) g_tail = NULL;
        pthread_mutex_unlock(&g_qmu);

        do_push(job->device_token, job->title, job->body);
        free(job);
    }
    return NULL;
}

/* ─── Public API ─────────────────────────────────────────────────────────── */
int apns_init(void) {
    const char *kp = getenv("APNS_KEY_PATH");
    const char *ki = getenv("APNS_KEY_ID");
    const char *ti = getenv("APNS_TEAM_ID");
    const char *bi = getenv("APNS_BUNDLE_ID");
    if (!kp || !ki || !ti || !bi) {
        fprintf(stderr, "[apns] disabled (set APNS_KEY_PATH/KEY_ID/TEAM_ID/BUNDLE_ID)\n");
        return -1;
    }

    FILE *f = fopen(kp, "r");
    if (!f) { fprintf(stderr, "[apns] cannot open %s\n", kp); return -1; }
    g_pkey = PEM_read_PrivateKey(f, NULL, NULL, NULL);
    fclose(f);
    if (!g_pkey) { fprintf(stderr, "[apns] failed to parse key\n"); return -1; }

    strncpy(g_key_id,    ki, sizeof(g_key_id)    - 1);
    strncpy(g_team_id,   ti, sizeof(g_team_id)   - 1);
    strncpy(g_bundle_id, bi, sizeof(g_bundle_id) - 1);
    const char *sb = getenv("APNS_SANDBOX");
    g_sandbox = sb ? atoi(sb) : 0;

    curl_global_init(CURL_GLOBAL_DEFAULT);

    pthread_t t;
    pthread_create(&t, NULL, apns_worker, NULL);
    pthread_detach(t);

    g_enabled = 1;
    fprintf(stderr, "[apns] key=%s team=%s bundle=%s %s\n",
            g_key_id, g_team_id, g_bundle_id,
            g_sandbox ? "(sandbox)" : "(production)");
    return 0;
}

void apns_notify_async(const char *device_token, const char *title, const char *body) {
    if (!g_enabled || !device_token || !device_token[0]) return;

    ApnsJob *job = calloc(1, sizeof(ApnsJob));
    if (!job) return;
    strncpy(job->device_token, device_token, sizeof(job->device_token) - 1);
    strncpy(job->title,        title,        sizeof(job->title)        - 1);
    strncpy(job->body,         body,         sizeof(job->body)         - 1);

    pthread_mutex_lock(&g_qmu);
    if (g_tail) g_tail->next = job;
    else        g_head = job;
    g_tail = job;
    pthread_cond_signal(&g_qcv);
    pthread_mutex_unlock(&g_qmu);
}
