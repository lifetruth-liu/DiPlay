/* Compile the real parser in this translation unit to exercise its private byte
 * reader with exact-size buffers. The static library does not pull tcp_in.o
 * because this translation unit already supplies its public definitions. No
 * test hooks or replacement parser are added to the product build. */
#include "../third_party/lwip/src/core/tcp_in.c"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(condition) do { if (!(condition)) { \
    fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #condition); exit(1); \
} } while (0)

static void truncated(unsigned split, u8_t kind) {
    const unsigned length = 4;
    unsigned first = split ? split : length;
    u8_t *head = malloc(TCP_HLEN + first);
    u8_t *tail = split ? malloc(length - first) : NULL;
    struct tcp_pcb pcb;
    CHECK(head != NULL && (!split || tail != NULL));
    memset(head, 0, TCP_HLEN + first);
    memset(&pcb, 0, sizeof(pcb));
    memset(head + TCP_HLEN, LWIP_TCP_OPT_NOP, first);
    if (tail != NULL) memset(tail, LWIP_TCP_OPT_NOP, length - first);
    if (tail != NULL) tail[length - first - 1] = kind;
    else head[TCP_HLEN + length - 1] = kind;
    tcphdr = (struct tcp_hdr *)head;
    tcphdr_optlen = length;
    tcphdr_opt1len = first;
    tcphdr_opt2 = tail;
    pcb.mss = 1000;
    tcp_parseopt(&pcb);
    CHECK(pcb.mss == 1000);
    CHECK(tcp_optidx == length + 1);
    free(tail);
    free(head);
}

static void valid(unsigned split) {
    const u8_t option[] = {LWIP_TCP_OPT_MSS, LWIP_TCP_OPT_LEN_MSS, 0x02, 0x00};
    unsigned first = split ? split : sizeof(option);
    u8_t *head = malloc(TCP_HLEN + first);
    u8_t *tail = split ? malloc(sizeof(option) - first) : NULL;
    struct tcp_pcb pcb;
    CHECK(head != NULL && (!split || tail != NULL));
    memset(head, 0, TCP_HLEN + first);
    memset(&pcb, 0, sizeof(pcb));
    memcpy(head + TCP_HLEN, option, first);
    if (tail != NULL) memcpy(tail, option + first, sizeof(option) - first);
    tcphdr = (struct tcp_hdr *)head;
    tcphdr_optlen = sizeof(option);
    tcphdr_opt1len = first;
    tcphdr_opt2 = tail;
    tcp_parseopt(&pcb);
    CHECK(pcb.mss == 512);
    CHECK(tcp_optidx == sizeof(option));
    free(tail);
    free(head);
}

int main(void) {
    for (unsigned split = 0; split < 4; ++split) {
        valid(split);
        truncated(split, LWIP_TCP_OPT_MSS);
        truncated(split, 254); /* Unknown option also needs a length byte. */
    }
    puts("TCP exact-buffer and split-option tests passed");
    return 0;
}
