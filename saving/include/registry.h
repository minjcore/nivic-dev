#pragma once
#include <stdint.h>
#include <stddef.h>
#include "disruptor.h"

/* Thread-safe map: mid → open client fd.
 * Used to push EVT_* frames to connected clients via the PushRing. */

void      registry_init(void);
void      registry_add(uint32_t mid, int fd);
void      registry_remove(int fd);

/* Enqueue a push frame to mid's socket via the PushRing (non-blocking). */
int       registry_push(uint32_t mid, const uint8_t *buf, size_t len);

/* Returns the shared PushRing — hand this to the push-writer thread. */
PushRing *registry_get_push_ring(void);
