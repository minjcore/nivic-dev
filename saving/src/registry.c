#include "registry.h"
#include "wire.h"
#include <string.h>
#include <pthread.h>
#include <sys/socket.h>

#define REGISTRY_MAX 512

typedef struct {
    uint32_t mid;
    int      fd;   /* -1 = empty */
} RegEntry;

static RegEntry        g_reg[REGISTRY_MAX];
static pthread_mutex_t g_mu;

void registry_init(void) {
    pthread_mutex_init(&g_mu, NULL);
    for (int i = 0; i < REGISTRY_MAX; i++) {
        g_reg[i].mid = 0;
        g_reg[i].fd  = -1;
    }
}

void registry_add(uint32_t mid, int fd) {
    pthread_mutex_lock(&g_mu);
    /* Replace existing entry for same mid, or find free slot */
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

int registry_push(uint32_t mid, const uint8_t *buf, size_t len) {
    pthread_mutex_lock(&g_mu);
    int fd = -1;
    for (int i = 0; i < REGISTRY_MAX; i++) {
        if (g_reg[i].mid == mid) { fd = g_reg[i].fd; break; }
    }
    pthread_mutex_unlock(&g_mu);

    if (fd < 0) return -1;
    return wire_send_raw(fd, buf, len);
}
