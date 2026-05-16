#include "db.h"
#include "wire.h"
#include <stdio.h>
#include <stdlib.h>

void server_run(DB *db);

int main(int argc, char *argv[]) {
    const char *conninfo = argc > 1 ? argv[1]
                                    : "dbname=saving host=localhost";

    DB db;
    if (db_open(&db, conninfo) != 0) {
        fprintf(stderr, "failed to open db: %s\n", conninfo);
        return 1;
    }
    printf("[saving] database: %s\n", conninfo);

    server_run(&db);

    db_close(&db);
    return 0;
}
