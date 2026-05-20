#include "registry.h"
#include "disruptor.h"
#include "wire.h"
#include <string.h>
#include <pthread.h>

#define REGISTRY_MAX 512

typedef struct {
    uint32_t mid;
    int      fd;    /* -1 = empty */
} RegEntry;

static RegEntry        g_reg[REGISTRY_MAX];
static pthread_mutex_t g_mu;
static PushRing        g_push_ring;

void registry_init(void) {
    pthread_mutex_init(&g_mu, NULL);
    for (int i = 0; i < REGISTRY_MAX; i++) {
        g_reg[i].mid = 0;
        g_reg[i].fd  = -1;
    }
    push_ring_init(&g_push_ring);
}

PushRing *registry_get_push_ring(void) {
    return &g_push_ring;
}

void registry_add(uint32_t mid, int fd) {
    pthread_mutex_lock(&g_mu);
    int free_slot = -1;
    for (int i = 0; i < REGISTRY_MAX; i++) {
        if (g_reg[i].mid == mid) { free_slot = i; break; }
        if (g_reg[i].fd == -1 && free_slot == -1) free_slot = i;
    }
    if (free_slot >= 0) {
        g_reg[free_slot].mid = mid;
        g_reg[free_slot].fd  = fd;
    }
    pthread_mutex_unlock(&g_mu);
}

void registry_remove(int fd) {
    pthread_mutex_lock(&g_mu);
    for (int i = 0; i < REGISTRY_MAX; i++) {
        if (g_reg[i].fd == fd) {
            g_reg[i].mid = 0;
            g_reg[i].fd  = -1;
            break;
        }
    }
    pthread_mutex_unlock(&g_mu);
}

/* Enqueues push frame onto PushRing instead of calling wire_send_raw()
 * directly.  The push-writer thread drains the ring asynchronously,
 * so this call never blocks the event-processor thread on a slow socket. */
int registry_push(uint32_t mid, const uint8_t *buf, size_t len) {
    pthread_mutex_lock(&g_mu);
    int fd = -1;
    for (int i = 0; i < REGISTRY_MAX; i++) {
        if (g_reg[i].mid == mid) { fd = g_reg[i].fd; break; }
    }
    pthread_mutex_unlock(&g_mu);

    if (fd < 0) return -1;
    push_ring_enqueue(&g_push_ring, fd, buf, len);
    return 0;
}
