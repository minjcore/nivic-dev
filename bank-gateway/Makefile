CC      = gcc
CFLAGS  = -Wall -Wextra -O2 -Iinclude
LDFLAGS = -lpthread

SRCS    = src/main.c src/iso8583.c src/processor.c
TARGET  = bank-gateway

$(TARGET): $(SRCS) include/iso8583.h
	$(CC) $(CFLAGS) $(SRCS) $(LDFLAGS) -o $(TARGET)

clean:
	rm -f $(TARGET)
