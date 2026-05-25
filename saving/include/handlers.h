#pragma once
#include "wire.h"
#include "db.h"
#include <stdint.h>

/* In-memory session table (opaque). Created once in server_run(). */
typedef struct SessionTable SessionTable;

SessionTable *session_table_new(void);
void          session_table_free(SessionTable *st);

/* Handle one parsed WireFrame, writing the response back to fd. */
void handle_frame(DB *db, SessionTable *st, int fd, const WireFrame *f);

/* ─── Admin helpers (used by admin.c) ───────────────────────────────────── */

typedef struct {
    uint32_t mid;
    int32_t  expires_in_s;
} SessionInfo;

/* Fill out[0..max-1] with active sessions. Returns count. */
int  st_list_sessions(SessionTable *st, SessionInfo *out, int max);

/* Kill all sessions for the given mid. */
void st_kill_mid(SessionTable *st, uint32_t mid);

/* Maintenance mode — when on, all Wire commands except PING/LOGIN/LOGOUT/
 * RENEW_SESSION return ERR_SYSTEM_OFFLINE. */
void maintenance_set(int on);
int  maintenance_get(void);
