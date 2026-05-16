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
