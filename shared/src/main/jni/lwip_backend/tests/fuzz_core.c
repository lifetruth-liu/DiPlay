#include "diplay_endpoints.h"
#include "diplay_tcp.h"
#define LWIP_DONT_PROVIDE_BYTEORDER_FUNCTIONS
#include "lwip/netif.h"
#include "lwip/stats.h"
#include "lwip/memp.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define VERIFY(x) do { if (!(x)) { fprintf(stderr, "fuzz invariant: %s:%d: %s\n", __FILE__, __LINE__, #x); abort(); } } while (0)
static const uint8_t mac[6] = {2,0,0,0,0,1}, peer_mac[6] = {2,0,0,0,0,2};
static const uint8_t ip[16] = {0xfe,0x80,0,0,0,0,0,0,0,0,0,0,0,0,0,1};
static const uint8_t peer[16] = {0xfe,0x80,0,0,0,0,0,0,0,0,0,0,0,0,0,2};
struct slot { uint64_t token, stale; unsigned kind; };
struct fixture { dp_core *core; uint64_t now; uint32_t random, syn; struct slot slots[16]; };
static uint16_t read16(const uint8_t *p) { return (uint16_t)((uint16_t)p[0] * 256u + p[1]); }
static uint32_t read32(const uint8_t *p) { return (uint32_t)read16(p) * 65536u + read16(p + 2); }
static void write16(uint8_t *p, uint16_t n) { p[0] = (uint8_t)(n >> 8); p[1] = (uint8_t)n; }
#if !defined(FUZZ_ethernet)
static void write32(uint8_t *p, uint32_t n) { write16(p, (uint16_t)(n >> 16)); write16(p + 2, (uint16_t)n); }
#endif
static uint16_t checksum(const uint8_t *frame, uint8_t *payload, size_t size, uint8_t protocol) {
    uint32_t sum = (uint32_t)size + protocol;
    for (unsigned i = 22; i < 54; i += 2) sum += read16(frame + i);
    for (size_t i = 0; i + 1 < size; i += 2) sum += read16(payload + i);
    if (size & 1) sum += (uint32_t)payload[size - 1] * 256u;
    while (sum >> 16) sum = (sum & 65535u) + (sum >> 16);
    uint16_t result = (uint16_t)~sum;
    return result ? result : 65535u;
}
static uint8_t *packet(uint8_t *frame, uint8_t protocol, size_t size) {
    memset(frame, 0, 54 + size);
    memcpy(frame, mac, 6); memcpy(frame + 6, peer_mac, 6); write16(frame + 12, 0x86dd);
    frame[14] = 0x60; write16(frame + 18, (uint16_t)size); frame[20] = protocol; frame[21] = 64;
    memcpy(frame + 22, peer, 16); memcpy(frame + 38, ip, 16);
    return frame + 54;
}
static uint64_t clock_ms(void *arg) { return ((struct fixture *)arg)->now; }
static uint32_t random32(void *arg) {
    struct fixture *f = arg; f->random = f->random * 1664525u + 1013904223u; return f->random;
}
static dp_tx_result output(void *arg, const uint8_t *frame, size_t size) {
    struct fixture *f = arg;
    VERIFY(size >= 14 && size <= 1514);
    if (size >= 74 && frame[20] == 6 && (frame[67] & 18) == 18) f->syn = read32(frame + 58);
    return DP_TX_ACCEPTED;
}
#if !defined(FUZZ_ethernet)
static void tcp_input(struct fixture *f, uint8_t flags, uint32_t sequence, uint32_t ack) {
    uint8_t frame[74]; uint8_t *p = packet(frame, 6, 20);
    write16(p, 50000); write16(p + 2, 7000); write32(p + 4, sequence); write32(p + 8, ack);
    p[12] = 0x50; p[13] = flags; write16(p + 14, 32768);
    write16(p + 16, checksum(frame, p, 20, 6));
    VERIFY(dp_core_input(f->core, frame, sizeof(frame)) == DP_OK);
}
#endif
static void create_ready(struct fixture *f) {
    dp_config config = {0};
    memcpy(config.mac, mac, 6); memcpy(config.ipv6, ip, 16); config.mtu = 1500;
    config.opaque = f; config.clock_ms = clock_ms; config.random32 = random32; config.output = output;
    f->random = 1;
    VERIFY(dp_core_create(&config, &f->core) == DP_OK);
    VERIFY(dp_core_set_link_ready(f->core, 1) == DP_OK);
    for (unsigned i = 0; i < 16; ++i) { f->now += 250; VERIFY(dp_core_poll(f->core) == DP_OK); }
    uint8_t frame[86]; uint8_t *p = packet(frame, 58, 32);
    frame[21] = 255; p[0] = 135; memcpy(p + 8, ip, 16); p[24] = 1; p[25] = 1;
    memcpy(p + 26, peer_mac, 6); write16(p + 2, checksum(frame, p, 32, 58));
    VERIFY(dp_core_input(f->core, frame, sizeof(frame)) == DP_OK);
}
#if !defined(FUZZ_ethernet)
static void close_slot(struct fixture *f, struct slot *slot) {
    if (!slot->token) return;
    if (slot->kind == 1) (void)dp_udp_close(f->core, slot->token);
    else if (slot->kind == 2) (void)dp_tcp_listener_close(f->core, slot->token);
    else (void)dp_tcp_close(f->core, slot->token);
    slot->stale = slot->token; slot->token = 0; slot->kind = 0;
}
#endif
static void prepare_endpoints(struct fixture *f) {
    uint16_t port;
    VERIFY(dp_udp_bind(f->core, 47000, &f->slots[0].token, &port) == DP_OK); f->slots[0].kind = 1;
    VERIFY(dp_tcp_listen(f->core, 7000, 4, &f->slots[1].token, &port) == DP_OK); f->slots[1].kind = 2;
#if !defined(FUZZ_ethernet)
    tcp_input(f, 2, 1000, 0); tcp_input(f, 16, 1001, f->syn + 1);
    dp_tcp_info info;
    VERIFY(dp_tcp_accept(f->core, f->slots[1].token, &f->slots[2].token, &info) == DP_OK);
    f->slots[2].kind = 3;
#endif
}
static void frame_input(struct fixture *f, const uint8_t *data, size_t size) {
    if (!size) return;
    uint8_t flags = *data++; size--;
    uint8_t frame[1514];
    if (size > sizeof(frame)) size = sizeof(frame);
    memcpy(frame, data, size);
    if (size >= 54) {
        if (flags & 1) {
            memcpy(frame, mac, 6); memcpy(frame + 6, peer_mac, 6); write16(frame + 12, 0x86dd);
            frame[14] = 0x60; write16(frame + 18, (uint16_t)(size - 54));
        }
        if (flags & 2) { memcpy(frame + 22, peer, 16); memcpy(frame + 38, ip, 16); }
        size_t payload = size - 54;
        unsigned offset = frame[20] == 17 ? 6 : (frame[20] == 6 ? 16 : 2);
        if ((flags & 4) && (frame[20] == 6 || frame[20] == 17 || frame[20] == 58) && payload >= offset + 2u) {
            write16(frame + 54 + offset, 0);
            write16(frame + 54 + offset, checksum(frame, frame + 54, payload, frame[20]));
        }
    }
    (void)dp_core_input(f->core, frame, size);
}
#if !defined(FUZZ_ethernet)
static void commands(struct fixture *f, const uint8_t *data, size_t size) {
    for (unsigned step = 0; size >= 4 && step < 128; ++step) {
        unsigned op = data[0] % 20, index = data[1] % 16, arg = data[2];
        size_t length = data[3];
        int stale = data[1] & 128;
        data += 4; size -= 4; if (length > size) length = size;
#if defined(FUZZ_lifecycle)
        if (op == 0) {
            VERIFY(dp_core_destroy(&f->core) == DP_OK);
            for (unsigned i = 0; i < 16; ++i) {
                if (f->slots[i].token) f->slots[i].stale = f->slots[i].token;
                f->slots[i].token = 0; f->slots[i].kind = 0;
            }
            create_ready(f);
        }
#endif
        struct slot *slot = &f->slots[index];
        uint64_t token = stale ? slot->stale : slot->token;
        uint8_t bytes[256]; size_t copied = 0; uint16_t port; dp_udp_datagram datagram; dp_tcp_info info;
        switch (op) {
        case 0: break;
        case 1:
            close_slot(f, slot);
            if (dp_udp_bind(f->core, (uint16_t)(arg ? 47000u + arg : 0), &slot->token, &port) == DP_OK) slot->kind = 1;
            break;
        case 2: (void)dp_udp_send(f->core, token, peer, (uint16_t)(47000u + arg), data, length); break;
        case 3: (void)dp_udp_receive(f->core, token, bytes, arg, &datagram); break;
        case 4: (void)dp_udp_set_queue_max_age(f->core, token, arg); break;
        case 5: { uint64_t expired; (void)dp_udp_get_expired_count(f->core, token, &expired); break; }
        case 6:
            close_slot(f, slot);
            if (dp_tcp_listen(f->core, (uint16_t)(arg ? 7000u + arg : 0), 4, &slot->token, &port) == DP_OK) slot->kind = 2;
            break;
        case 7: {
            uint64_t child = 0;
            if (dp_tcp_accept(f->core, token, &child, &info) == DP_OK) {
                struct slot *destination = &f->slots[(index + 1) % 16]; close_slot(f, destination);
                destination->token = child; destination->kind = 3;
            }
            break;
        }
        case 8: (void)dp_tcp_read(f->core, token, bytes, arg, &copied); break;
        case 9: (void)dp_tcp_write(f->core, token, data, length, &copied); break;
        case 10: if (!stale) close_slot(f, slot); else (void)dp_udp_close(f->core, token); break;
        case 11:
            close_slot(f, slot);
            if (dp_tcp_connect(f->core, peer, (uint16_t)(9000u + arg), &slot->token) == DP_OK) slot->kind = 3;
            break;
        case 12: (void)dp_tcp_connect_status(f->core, token, &info); break;
        case 13: (void)dp_tcp_shutdown_output(f->core, token); break;
        case 14: (void)dp_core_set_link_ready(f->core, arg & 1); break;
        case 15: f->now += arg * 250u; (void)dp_core_poll(f->core); break;
        case 16: frame_input(f, data, length); break;
        case 17: (void)dp_tcp_configure_liveness(f->core, token); break;
        case 18: { dp_udp_queue_stats stats; (void)dp_udp_get_queue_stats(f->core, token, &stats); break; }
        case 19:
            if (dp_tcp_close_graceful(f->core, token) == DP_OK && !stale) {
                slot->stale = slot->token; slot->token = 0; slot->kind = 0;
            }
            break;
        }
        data += length; size -= length;
    }
}
#endif
int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    static int initialized;
    static uint32_t heap, pools[MEMP_MAX];
    struct fixture f = {0};
    if (!initialized) {
        create_ready(&f); VERIFY(dp_core_destroy(&f.core) == DP_OK);
        heap = lwip_stats.mem.used;
        for (unsigned i = 0; i < MEMP_MAX; ++i) pools[i] = lwip_stats.memp[i]->used;
        initialized = 1;
    }
    create_ready(&f); prepare_endpoints(&f);
#if defined(FUZZ_ethernet)
    frame_input(&f, data, size);
#else
    commands(&f, data, size);
#endif
    dp_stats stats;
    VERIFY(dp_core_get_stats(f.core, &stats) == DP_OK);
    VERIFY(stats.configured_bytes < 16u * 1024u * 1024u && stats.tcp_active <= 32 && stats.udp_pcbs <= 32);
    VERIFY(dp_core_destroy(&f.core) == DP_OK && f.core == NULL);
    VERIFY(dp_core_destroy(&f.core) == DP_OK);
    VERIFY(netif_list == NULL && netif_default == NULL && lwip_stats.mem.used == heap);
    for (unsigned i = 0; i < MEMP_MAX; ++i) {
        uint32_t used = lwip_stats.memp[i]->used;
        VERIFY(used >= pools[i] && used <= pools[i] + (i == MEMP_SYS_TIMEOUT ? 1u : 0u));
    }
    return 0;
}
