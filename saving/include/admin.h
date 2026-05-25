#pragma once
#include "db.h"
#include <stdint.h>

typedef struct SessionTable SessionTable;

/* Start the HTTP admin panel on a background thread.
 * Shares the existing DB connection (protected by DB.mu).
 * Port defaults to 7475; override with ADMIN_PORT env var.
 * Password defaults to "saving_admin_dev"; override with ADMIN_PASSWORD.
 * Returns 0 on success, -1 on failure. */
int admin_start(DB *db, SessionTable *st, uint16_t port, const char *password);

/* Close the admin listen socket so the accept thread exits. Safe to call
 * from a signal handler. */
void admin_request_stop(void);
