#include "iso8583.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <time.h>

#define PORT    8095
#define BUF_MAX 8192

void process_message(Iso8583 *req, Iso8583 *resp);

static void *handle_client(void *arg) {
    int fd = *(int *)arg;
    free(arg);
    pthread_detach(pthread_self());

    struct sockaddr_in peer;
    socklen_t plen = sizeof(peer);
    getpeername(fd, (struct sockaddr *)&peer, &plen);
    printf("[conn] %s:%d connected\n", inet_ntoa(peer.sin_addr), ntohs(peer.sin_port));

    uint8_t buf[BUF_MAX], out[BUF_MAX];

    for (;;) {
        /* 2-byte big-endian length prefix */
        uint8_t hdr[2];
        if (recv(fd, hdr, 2, MSG_WAITALL) != 2) break;
        int mlen = (hdr[0] << 8) | hdr[1];
        if (mlen <= 0 || mlen > (int)(sizeof(buf) - 1)) break;

        if (recv(fd, buf, mlen, MSG_WAITALL) != mlen) break;

        Iso8583 req, resp;
        if (iso8583_unpack(buf, mlen, &req) < 0) {
            fprintf(stderr, "[iso] unpack error (len=%d)\n", mlen);
            break;
        }

        printf("[msg] MTI=%s PAN=%.6s... AMT=%-12s MID=%.15s\n",
               req.mti,
               iso8583_get(&req, 2),
               iso8583_get(&req, 4),
               iso8583_get(&req, 42));

        process_message(&req, &resp);

        int olen = iso8583_pack(&resp, out, sizeof(out));
        if (olen < 0) { fprintf(stderr, "[iso] pack error\n"); break; }

        uint8_t rhdr[2] = { (uint8_t)((olen >> 8) & 0xFF), (uint8_t)(olen & 0xFF) };
        send(fd, rhdr, 2, 0);
        send(fd, out,  olen, 0);
    }

    printf("[conn] %s:%d disconnected\n", inet_ntoa(peer.sin_addr), ntohs(peer.sin_port));
    close(fd);
    return NULL;
}

int main(void) {
    srand((unsigned)time(NULL));

    int srv = socket(AF_INET, SOCK_STREAM, 0);
    int opt = 1;
    setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr = {
        .sin_family      = AF_INET,
        .sin_port        = htons(PORT),
        .sin_addr.s_addr = INADDR_ANY,
    };
    if (bind(srv, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("bind"); return 1;
    }
    listen(srv, 16);

    printf("╔══════════════════════════════════╗\n");
    printf("║  Mock Bank Gateway  ISO 8583      ║\n");
    printf("║  TCP :%d                          ║\n", PORT);
    printf("╚══════════════════════════════════╝\n");
    printf("Test cards:\n");
    printf("  4111111111111111  bal=50,000,000 VND\n");
    printf("  4000000000000002  bal= 1,000,000 VND\n");
    printf("  5500000000000004  bal=100,000,000 VND\n");
    printf("  4000000000000119  blocked\n\n");

    for (;;) {
        struct sockaddr_in cli;
        socklen_t clen = sizeof(cli);
        int cfd = accept(srv, (struct sockaddr *)&cli, &clen);
        if (cfd < 0) continue;
        int *p = malloc(sizeof(int));
        *p = cfd;
        pthread_t t;
        pthread_create(&t, NULL, handle_client, p);
    }
}
