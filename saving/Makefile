CC      = clang
CFLAGS  = -std=c11 -O2 -Wall -Wextra -Iinclude \
          -I/opt/homebrew/include/postgresql@17
LDFLAGS = -L/opt/homebrew/lib/postgresql@17 -lpq -lpthread

# macOS: CommonCrypto is part of the SDK (no extra flag needed)
# Linux: add -lcrypto and swap CommonCrypto headers for OpenSSL

SRC = src/main.c src/wire_parser.c src/db.c src/handlers.c src/server.c src/registry.c
BIN = saving

.PHONY: all clean

all: $(BIN)

$(BIN): $(SRC)
	$(CC) $(CFLAGS) $^ -o $@ $(LDFLAGS)

clean:
	rm -f $(BIN)
