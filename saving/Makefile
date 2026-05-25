UNAME := $(shell uname)

ifeq ($(UNAME), Darwin)
  CC      = clang
  CFLAGS  = -std=c11 -O2 -Wall -Wextra -Iinclude \
            -I/opt/homebrew/include/postgresql@17 \
            -I/opt/homebrew/opt/curl/include \
            -I/opt/homebrew/opt/openssl@3/include
  LDFLAGS = -L/opt/homebrew/lib/postgresql@17 -lpq -lpthread \
            -L/opt/homebrew/opt/curl/lib -lcurl \
            -L/opt/homebrew/opt/openssl@3/lib -lssl -lcrypto
else
  CC      = gcc
  CFLAGS  = -std=c11 -D_GNU_SOURCE -O2 -Wall -Wextra -Iinclude -I/usr/include/postgresql
  LDFLAGS = -lpq -lpthread -lcurl -lssl -lcrypto
endif

SRC = src/main.c src/wire_parser.c src/db.c src/handlers.c src/server.c src/registry.c \
      src/disruptor.c src/wal.c src/admin.c src/apns.c
BIN = saving

.PHONY: all clean

all: $(BIN)

$(BIN): $(SRC)
	$(CC) $(CFLAGS) $^ -o $@ $(LDFLAGS)

clean:
	rm -f $(BIN)
