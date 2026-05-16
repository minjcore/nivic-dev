#pragma once
#include <stdint.h>
#include <stddef.h>

/* Thread-safe map: mid → open client fd.
 * Used to push EVT_* frames to connected clients. */

void registry_init(void);
void registry_add(uint32_t mid, int fd);
void registry_remove(int fd);

/* Send buf to mid's socket if connected. Returns 0 = sent, -1 = offline. */
int  registry_push(uint32_t mid, const uint8_t *buf, size_t len);
