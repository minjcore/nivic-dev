#include "db.h"
#include "wire.h"
#include "apns.h"
#include <stdio.h>
#include <stdlib.h>

void server_run(DB *db, const char *wal_path);

int main(int argc, char *argv[]) {
    const char *conninfo = argc > 1            ? argv[1]
                         : getenv("SAVING_DB") ? getenv("SAVING_DB")
                         :                       "dbname=saving host=localhost";

    const char *wal_path = getenv("SAVING_WAL");   /* NULL = WAL disabled */

    DB db;
    if (db_open(&db, conninfo) != 0) {
        fprintf(stderr, "failed to open db: %s\n", conninfo);
        return 1;
    }
    printf("[saving] database: %s\n", conninfo);
    apns_init();   /* no-op if env vars missing */

    server_run(&db, wal_path);

    db_close(&db);
    return 0;
}
