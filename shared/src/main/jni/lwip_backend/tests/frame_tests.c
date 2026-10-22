#include "diplay_lwip.h"
#include "diplay_endpoints.h"
#include "diplay_tcp.h"
#include "echo_services.h"
/* Keep lwIP byte-order aliases out of the Windows SDK declarations below. */
#define LWIP_DONT_PROVIDE_BYTEORDER_FUNCTIONS
#include "lwip/nd6.h"
#include "lwip/netif.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef _WIN32
#include <windows.h>
#else
#include <pthread.h>
#endif

#define CHECK(x)                                                                                   \
    do {                                                                                           \
        if (!(x)) {                                                                                \
            fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #x);                                \
            exit(1);                                                                               \
        }                                                                                          \
    } while (0)
#define FRAME_MAX 1514
struct frame {
    size_t length;
    uint8_t bytes[FRAME_MAX];
};
struct fixture {
    dp_core *core;
    uint64_t now;
    uint32_t random;
    int ready;
    size_t count;
    struct frame frames[64];
};
static const uint8_t local_mac[6] = {2, 0, 0, 0, 0, 1};
static const uint8_t peer_mac[6] = {2, 0, 0, 0, 0, 2};
static const uint8_t local_ip[16] = {0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
static const uint8_t peer_ip[16] = {0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2};
static const uint8_t solicited[16] = {0xff, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0xff, 0, 0, 1};
static const uint8_t all_nodes[16] = {0xff, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
static uint16_t u16(const uint8_t *p) {
    return (uint16_t)((p[0] << 8) | p[1]);
}
static uint32_t u32(const uint8_t *p) {
    return ((uint32_t)u16(p) << 16) | u16(p + 2);
}
static void w16(uint8_t *p, uint16_t n) {
    p[0] = (uint8_t)(n >> 8);
    p[1] = (uint8_t)n;
}
static void w32(uint8_t *p, uint32_t n) {
    w16(p, (uint16_t)(n >> 16));
    w16(p + 2, (uint16_t)n);
}
static uint32_t sum(const uint8_t *p, size_t n) {
    uint32_t s = 0;
    for (; n >= 2; n -= 2, p += 2)
        s += u16(p);
    if (n)
        s += (uint32_t)p[0] << 8;
    return s;
}
static uint16_t checksum(const uint8_t *ip, const uint8_t *payload, size_t n, uint8_t protocol) {
    uint32_t s = sum(ip + 8, 32) + sum(payload, n) + (uint32_t)n + protocol;
    while (s >> 16)
        s = (s & 65535) + (s >> 16);
    return (uint16_t)~s;
}
static uint64_t clock_ms(void *arg) {
    return ((struct fixture *)arg)->now;
}
static uint32_t random32(void *arg) {
    struct fixture *f = arg;
    f->random ^= f->random << 13;
    f->random ^= f->random >> 17;
    f->random ^= f->random << 5;
    return f->random;
}
static dp_tx_result output(void *arg, const uint8_t *bytes, size_t length) {
    struct fixture *f = arg;
    if (f->ready < 0)
        return DP_TX_FAILED;
    if (!f->ready)
        return DP_TX_NOT_READY;
    CHECK(length <= FRAME_MAX && f->count < 64);
    f->frames[f->count].length = length;
    memcpy(f->frames[f->count++].bytes, bytes, length);
    return DP_TX_ACCEPTED;
}
static void open_core(struct fixture *f, int ready) {
    memset(f, 0, sizeof(*f));
    f->random = 1234567;
    f->ready = ready;
    dp_config config = {0};
    memcpy(config.mac, local_mac, 6);
    memcpy(config.ipv6, local_ip, 16);
    config.mtu = 1500;
    config.opaque = f;
    config.clock_ms = clock_ms;
    config.random32 = random32;
    config.output = output;
    CHECK(dp_core_create(&config, &f->core) == DP_OK);
    CHECK(dp_core_set_link_ready(f->core, ready) == DP_OK);
}
static void advance(struct fixture *f, uint64_t millis) {
    uint64_t end = f->now + millis;
    while (f->now < end) {
        f->now += 250;
        CHECK(dp_core_poll(f->core) == DP_OK);
    }
}
static dp_address_state address_state(struct fixture *f) {
    dp_address_state state;
    CHECK(dp_core_address_state(f->core, &state) == DP_OK);
    return state;
}
static void close_core(struct fixture *f) {
    CHECK(dp_core_destroy(&f->core) == DP_OK);
    CHECK(!f->core);
}
static void ethernet(uint8_t *frame, const uint8_t *destination) {
    memcpy(frame, destination, 6);
    memcpy(frame + 6, peer_mac, 6);
    w16(frame + 12, 0x86dd);
}
static uint8_t *ipv6(uint8_t *frame, const uint8_t *destination, uint8_t proto, uint16_t size) {
    uint8_t *ip = frame + 14;
    memset(ip, 0, 40 + size);
    ip[0] = 0x60;
    w16(ip + 4, size);
    ip[6] = proto;
    ip[7] = proto == 58 ? 255 : 64;
    memcpy(ip + 8, peer_ip, 16);
    memcpy(ip + 24, destination, 16);
    return ip + 40;
}
static void neighbor(struct fixture *f, int advertisement) {
    uint8_t frame[86] = {0};
    uint8_t multicast[6] = {0x33, 0x33, 0xff, 0, 0, 1};
    if (advertisement) {
        multicast[2] = 0;
        multicast[5] = 1;
    }
    ethernet(frame, multicast);
    uint8_t *p = ipv6(frame, advertisement ? all_nodes : solicited, 58, 32);
    p[0] = advertisement ? 136 : 135;
    p[4] = advertisement ? 0x20 : 0;
    memcpy(p + 8, local_ip, 16);
    p[24] = advertisement ? 2 : 1;
    p[25] = 1;
    memcpy(p + 26, peer_mac, 6);
    w16(p + 2, checksum(frame + 14, p, 32, 58));
    dp_result result = dp_core_input(f->core, frame, sizeof(frame));
    CHECK(result == (advertisement ? DP_ADDRESS_CONFLICT : DP_OK));
}
static struct frame *find(struct fixture *f, uint8_t proto, int type, int data) {
    for (size_t i = 0; i < f->count; i++) {
        struct frame *frame = &f->frames[i];
        const uint8_t *ip = frame->bytes + 14;
        if (frame->length < 54 || ip[6] != proto)
            continue;
        if (type >= 0 && ip[40] != (uint8_t)type)
            continue;
        if (data && (proto != 6 || u16(ip + 4) <= (ip[52] >> 4) * 4))
            continue;
        CHECK(frame->length >= (size_t)54 + u16(ip + 4));
        CHECK(checksum(ip, ip + 40, u16(ip + 4), proto) == 0);
        return frame;
    }
    return NULL;
}
static void ready_core(struct fixture *f) {
    open_core(f, 1);
    advance(f, 3000);
    CHECK(address_state(f) == DP_ADDRESS_PREFERRED);
    f->count = 0;
    neighbor(f, 0);
    CHECK(find(f, 58, 136, 0));
    f->count = 0;
}
static void test_ndp(void) {
    struct fixture f;
    ready_core(&f);
    neighbor(&f, 0);
    struct frame *reply = find(&f, 58, 136, 0);
    CHECK(reply);
    CHECK(!memcmp(reply->bytes, peer_mac, 6));
    CHECK(!memcmp(reply->bytes + 6, local_mac, 6));
    CHECK(!memcmp(reply->bytes + 54 + 8, local_ip, 16));
    close_core(&f);
}
static void test_ndp_options(void) {
    /* Do not pre-populate the peer cache: the response must use the address
     * learned from the source-LL option, skipping the preceding unknown one. */
    for (int malformed = 0; malformed < 3; ++malformed) {
        struct fixture f;
        open_core(&f, 1);
        advance(&f, 3000);
        f.count = 0;
        uint8_t frame[94] = {0};
        const uint8_t multicast[6] = {0x33, 0x33, 0xff, 0, 0, 1};
        ethernet(frame, multicast);
        uint8_t *p = ipv6(frame, solicited, 58, 40);
        p[0] = 135;
        memcpy(p + 8, local_ip, 16);
        p[24] = 254; /* Unknown option is valid and must be skipped. */
        p[25] = malformed == 1 ? 0 : 1;
        memset(p + 26, 0xa5, 6);
        p[32] = 1;
        p[33] = malformed == 2 ? 2 : 1; /* Advertised length exceeds packet. */
        memcpy(p + 34, peer_mac, 6);
        w16(p + 2, checksum(frame + 14, p, 40, 58));
        CHECK(dp_core_input(f.core, frame, sizeof(frame)) == DP_OK);
        struct frame *reply = find(&f, 58, 136, 0);
        if (malformed) CHECK(reply == NULL);
        else {
            CHECK(reply != NULL);
            CHECK(!memcmp(reply->bytes, peer_mac, 6));
        }
        close_core(&f);
    }
}
static void test_dad(void) {
    struct fixture f;
    open_core(&f, 1);
    advance(&f, 1000);
    CHECK(address_state(&f) == DP_ADDRESS_TENTATIVE);
    struct frame *probe = find(&f, 58, 135, 0);
    CHECK(probe);
    uint8_t zero[16] = {0};
    CHECK(!memcmp(probe->bytes + 22, zero, 16));
    neighbor(&f, 1);
    CHECK(address_state(&f) == DP_ADDRESS_DUPLICATE);
    close_core(&f);
}
static void send_tcp_port(struct fixture *f, uint16_t port, uint8_t flags, uint32_t sequence, uint32_t ack,
                           const void *data, size_t n) {
    uint8_t frame[1514];
    CHECK(n <= 1440);
    ethernet(frame, local_mac);
    uint8_t *p = ipv6(frame, local_ip, 6, (uint16_t)(20 + n));
    w16(p, port);
    w16(p + 2, 7000);
    w32(p + 4, sequence);
    w32(p + 8, ack);
    p[12] = 0x50;
    p[13] = flags;
    w16(p + 14, 32768);
    memcpy(p + 20, data, n);
    w16(p + 16, checksum(frame + 14, p, 20 + n, 6));
    CHECK(dp_core_input(f->core, frame, 74 + n) == DP_OK);
}
static void send_tcp_bytes(struct fixture *f, uint8_t flags, uint32_t sequence, uint32_t ack,
                           const void *data, size_t n) {
    send_tcp_port(f, 50000, flags, sequence, ack, data, n);
}
static void send_tcp(struct fixture *f, uint8_t flags, uint32_t sequence, uint32_t ack,
                     const char *data) {
    send_tcp_bytes(f, flags, sequence, ack, data, strlen(data));
}
static void collect_echo(struct fixture *f, uint32_t server, const uint8_t *expected, uint8_t *seen,
                         size_t total) {
    for (size_t i = 0; i < f->count; i++) {
        const uint8_t *p = f->frames[i].bytes;
        if (f->frames[i].length < 74 || p[20] != 6)
            continue;
        size_t header = (p[66] >> 4) * 4;
        size_t length = u16(p + 18) - header;
        if (!length)
            continue;
        uint32_t offset = u32(p + 58) - (server + 1);
        CHECK(offset <= total && length <= total - offset);
        CHECK(checksum(p + 14, p + 54, u16(p + 18), 6) == 0);
        CHECK(!memcmp(p + 54 + header, expected + offset, length));
        memset(seen + offset, 1, length);
    }
    f->count = 0;
}
static uint32_t establish(struct fixture *f, uint16_t port) {
    f->count = 0;
    send_tcp_port(f, port, 2, 1000, 0, "", 0);
    struct frame *syn = find(f, 6, -1, 0);
    CHECK(syn && (syn->bytes[67] & 0x12) == 0x12);
    uint32_t sequence = u32(syn->bytes + 58);
    f->count = 0;
    send_tcp_port(f, port, 16, 1001, sequence + 1, "", 0);
    return sequence;
}
static uint64_t accept_stream(struct fixture *f, uint64_t listener) {
    uint64_t stream = 0;
    dp_tcp_info info;
    CHECK(dp_tcp_accept(f->core, listener, &stream, &info) == DP_OK && stream);
    CHECK(!memcmp(info.local, local_ip, 16) && !memcmp(info.remote, peer_ip, 16));
    CHECK(info.local_port == 7000 && info.remote_port >= 50000);
    return stream;
}
static void test_tcp_endpoint(void) {
    struct fixture f;
    ready_core(&f);
    uint64_t listener, stream = 99;
    uint16_t port;
    dp_tcp_info info;
    CHECK(dp_tcp_listen(f.core, 7000, 2, &listener, &port) == DP_OK && port == 7000);
    CHECK(dp_tcp_accept(f.core, listener, &stream, &info) == DP_WOULD_BLOCK && stream == 0);
    uint32_t server = establish(&f, 50000);
    send_tcp(&f, 24, 1001, server + 1, "hello");
    stream = accept_stream(&f, listener);
    uint8_t bytes[8]; size_t count = 99;
    CHECK(dp_tcp_read(f.core, stream, NULL, 0, &count) == DP_OK && count == 0);
    CHECK(dp_tcp_read(f.core, stream, bytes, 2, &count) == DP_OK && count == 2 && !memcmp(bytes, "he", 2));
    send_tcp(&f, 17, 1006, server + 1, "");
    CHECK(dp_tcp_read(f.core, stream, bytes, sizeof(bytes), &count) == DP_OK && count == 3 && !memcmp(bytes, "llo", 3));
    CHECK(dp_tcp_read(f.core, stream, bytes, sizeof(bytes), &count) == DP_PEER_CLOSED && count == 0);
    CHECK(dp_tcp_listener_close(f.core, listener) == DP_OK);
    CHECK(dp_tcp_read(f.core, stream, bytes, 1, &count) == DP_PEER_CLOSED);
    CHECK(dp_tcp_close(f.core, stream) == DP_OK);
    CHECK(dp_tcp_close(f.core, stream) == DP_INVALID_ARGUMENT);
    CHECK(dp_tcp_read(f.core, stream, bytes, 1, &count) == DP_INVALID_ARGUMENT);
    CHECK(dp_tcp_listen(f.core, 7000, 1, &listener, &port) == DP_OK);
    server = establish(&f, 50001);
    stream = accept_stream(&f, listener);
    send_tcp_port(&f, 50001, 20, 1001, server + 1, "", 0);
    CHECK(dp_tcp_read(f.core, stream, bytes, 1, &count) == DP_PEER_RESET);
    CHECK(dp_tcp_close(f.core, stream) == DP_OK);
    server = establish(&f, 50002); /* An unclaimed, data-bearing connection belongs to the listener. */
    send_tcp_port(&f, 50002, 24, 1001, server + 1, "held", 4);
    CHECK(dp_tcp_listener_close(f.core, listener) == DP_OK);
    dp_stats stats;
    CHECK(dp_core_get_stats(f.core, &stats) == DP_OK && stats.tcp_active == 0 && stats.heap_used == 0 && stats.pbuf_used == 0);
    close_core(&f);
}
static uint16_t last_window(struct fixture *f) {
    uint16_t window = 65535;
    int found = 0;
    for (size_t i = 0; i < f->count; ++i) if (f->frames[i].bytes[20] == 6 && f->frames[i].length >= 74) {
        window = u16(f->frames[i].bytes + 68); found = 1;
    }
    CHECK(found);
    f->count = 0;
    return window;
}
static void test_tcp_backpressure(void) {
    struct fixture f;
    ready_core(&f);
    uint64_t listener; uint16_t port;
    CHECK(dp_tcp_listen(f.core, 7000, 2, &listener, &port) == DP_OK);
    uint32_t server = establish(&f, 50000);
    uint64_t stream = accept_stream(&f, listener);
    uint8_t payload[65535], target[65535];
    for (size_t i = 0; i < sizeof(payload); ++i) payload[i] = (uint8_t)(i ^ (i >> 8));
    for (size_t i = 0; i < sizeof(payload); i += 1440) {
        size_t length = sizeof(payload) - i < 1440 ? sizeof(payload) - i : 1440;
        send_tcp_bytes(&f, 24, 1001 + (uint32_t)i, server + 1, payload + i, length);
    }
    advance(&f, 250);
    CHECK(last_window(&f) == 0);
    advance(&f, 10000); /* No receive credit is released merely by polling timers. */
    f.count = 0;
    size_t copied;
    CHECK(dp_tcp_read(f.core, stream, target, 32768, &copied) == DP_OK && copied == 32768);
    CHECK(last_window(&f) >= 32768);
    CHECK(dp_tcp_read(f.core, stream, target + 32768, sizeof(target) - 32768, &copied) == DP_OK && copied == sizeof(target) - 32768);
    CHECK(!memcmp(target, payload, sizeof(payload)));
    CHECK(dp_tcp_read(f.core, stream, target, 1, &copied) == DP_WOULD_BLOCK && copied == 0);
    f.count = 0;
    close_core(&f); /* Held and already consumed chains must both leave zero pool usage. */
    open_core(&f, 0);
    dp_stats stats; CHECK(dp_core_get_stats(f.core, &stats) == DP_OK && stats.pbuf_used == 0 && stats.heap_used == 0);
    close_core(&f);
}
static void test_tcp_endpoint_limits(void) {
    struct fixture f;
    open_core(&f, 0); /* Listeners must be preparable before USB readiness and DAD. */
    uint64_t listeners[16], stream = 99, extra = 99; uint16_t port = 99;
    CHECK(dp_tcp_listen(f.core, 0, 0, &extra, &port) == DP_INVALID_ARGUMENT && extra == 0 && port == 0);
    for (unsigned i = 0; i < 16; ++i) CHECK(dp_tcp_listen(f.core, 0, 1, &listeners[i], &port) == DP_OK && port);
    CHECK(dp_tcp_listen(f.core, 0, 1, &extra, &port) == DP_RESOURCE_LIMIT && extra == 0 && port == 0);
    CHECK(dp_tcp_close(f.core, listeners[0]) == DP_INVALID_ARGUMENT);
    for (unsigned i = 0; i < 16; ++i) CHECK(dp_tcp_listener_close(f.core, listeners[i]) == DP_OK);
    close_core(&f);
    ready_core(&f);
    CHECK(dp_tcp_listen(f.core, 7000, 32, &listeners[0], &port) == DP_OK);
    for (unsigned i = 0; i < 32; ++i) establish(&f, (uint16_t)(50000 + i));
    f.count = 0;
    send_tcp_port(&f, 50032, 2, 1000, 0, "", 0);
    uint64_t streams[32];
    for (unsigned i = 0; i < 32; ++i) streams[i] = accept_stream(&f, listeners[0]);
    dp_tcp_info info;
    CHECK(dp_tcp_accept(f.core, listeners[0], &stream, &info) == DP_WOULD_BLOCK && stream == 0);
    uint8_t byte; size_t count;
    for (unsigned i = 0; i < 32; ++i) CHECK(dp_tcp_read(f.core, streams[i], &byte, 1, &count) == DP_WOULD_BLOCK);
    f.count = 0;
    for (unsigned i = 0; i < 32; ++i) CHECK(dp_tcp_close(f.core, streams[i]) == DP_OK);
    CHECK(dp_tcp_listener_close(f.core, listeners[0]) == DP_OK);
    CHECK(dp_tcp_listen(f.core, 7000, 1, &extra, &port) == DP_OK && extra != listeners[0]);
    f.count = 0; send_tcp(&f, 2, 1000, 0, ""); /* Close during SYN_RCVD, before raw accept. */
    CHECK(dp_tcp_listener_close(f.core, extra) == DP_OK);
    dp_stats stats; CHECK(dp_core_get_stats(f.core, &stats) == DP_OK && stats.tcp_active == 0 && stats.tcp_listeners == 0 && stats.heap_used == 0);
    close_core(&f);
}
static void test_tcp_endpoint_lifecycle(void) {
    uint64_t previous = 0;
    for (unsigned cycle = 0; cycle < 8; ++cycle) {
        struct fixture f;
        ready_core(&f);
        uint64_t listener, stream = 0; uint16_t port;
        dp_tcp_info info; size_t copied; uint8_t bytes[3];
        CHECK(dp_tcp_listen(f.core, 7000, 1, &listener, &port) == DP_OK);
        if (previous) CHECK(dp_tcp_read(f.core, previous, bytes, 1, &copied) == DP_INVALID_ARGUMENT);
        dp_stats baseline, stats;
        CHECK(dp_core_get_stats(f.core, &baseline) == DP_OK);
        uint32_t server = establish(&f, 50000);
        send_tcp_port(&f, 50000, 24, 1001, server + 1, "abc", 3);
        send_tcp_port(&f, 50000, 20, 1004, server + 1, "", 0);
        CHECK(dp_tcp_accept(f.core, listener, &stream, &info) == DP_WOULD_BLOCK && stream == 0);
        CHECK(dp_core_get_stats(f.core, &stats) == DP_OK && stats.heap_used == baseline.heap_used && stats.pbuf_used == 0 && stats.tcp_active == 0);
        server = establish(&f, 50001);
        stream = accept_stream(&f, listener);
        send_tcp_port(&f, 50001, 24, 1001, server + 1, "xyz", 3);
        send_tcp_port(&f, 50001, 20, 1004, server + 1, "", 0);
        CHECK(dp_tcp_read(f.core, stream, bytes, 2, &copied) == DP_OK && copied == 2 && !memcmp(bytes, "xy", 2));
        CHECK(dp_tcp_read(f.core, stream, bytes, 3, &copied) == DP_OK && copied == 1 && bytes[0] == 'z');
        CHECK(dp_tcp_read(f.core, stream, bytes, 3, &copied) == DP_PEER_RESET);
        previous = stream;
        CHECK(dp_tcp_close(f.core, stream) == DP_OK);
        establish(&f, 50002);
        f.count = 0;
        send_tcp_port(&f, 50003, 2, 1000, 0, "", 0);
        CHECK(find(&f, 6, -1, 0) == NULL); /* Full delayed-accept backlog rejects a new SYN. */
        stream = accept_stream(&f, listener);
        establish(&f, 50003); /* Application accept restored the backlog slot. */
        CHECK(dp_tcp_listener_close(f.core, listener) == DP_OK);
        CHECK(dp_tcp_read(f.core, stream, bytes, 1, &copied) == DP_WOULD_BLOCK);
        CHECK(dp_tcp_close(f.core, stream) == DP_OK);
        CHECK(dp_core_get_stats(f.core, &stats) == DP_OK && stats.heap_used == 0 && stats.pbuf_used == 0 && stats.tcp_active == 0);
        close_core(&f);
    }
}
static void test_tcp_large_refused(void) {
    struct fixture f;
    ready_core(&f);
    CHECK(dp_core_execute(f.core, echo_services_start, NULL) == DP_OK);
    send_tcp(&f, 2, 1000, 0, "");
    struct frame *syn = find(&f, 6, -1, 0);
    CHECK(syn);
    uint32_t server = u32(syn->bytes + 58);
    f.count = 0;
    send_tcp(&f, 16, 1001, server + 1, "");
    enum { TOTAL = 28 * 1440 };
    uint8_t payload[TOTAL], seen[TOTAL];
    for (size_t i = 0; i < TOTAL; i++)
        payload[i] = (uint8_t)(i ^ (i >> 8));
    memset(seen, 0, sizeof(seen));
    /* Close a 1440-byte hole after 27 out-of-order segments: one callback then
     * receives 40320 bytes, larger than the unchanged 32768-byte send budget. */
    for (size_t i = 1440; i < TOTAL; i += 1440)
        send_tcp_bytes(&f, 24, 1001 + (uint32_t)i, server + 1, payload + i, 1440);
    send_tcp_bytes(&f, 24, 1001, server + 1, payload, 1440);
    collect_echo(&f, server, payload, seen, TOTAL);
    CHECK(seen[0] && !seen[32768]);
    size_t received = 0;
    for (int attempt = 0; received < TOTAL && attempt < 16; attempt++) {
        size_t before = received;
        while (received < TOTAL && seen[received])
            received++;
        CHECK(received > before);
        send_tcp(&f, 16, 1001 + TOTAL, server + 1 + (uint32_t)received, "");
        advance(&f, 250);
        collect_echo(&f, server, payload, seen, TOTAL);
    }
    CHECK(received == TOTAL);
    for (size_t i = 0; i < TOTAL; i++)
        CHECK(seen[i]);
    send_tcp(&f, 16, 1001 + TOTAL, server + 1 + TOTAL, "");
    close_core(&f);
}
static void test_tcp(void) {
    struct fixture f;
    ready_core(&f);
    CHECK(dp_core_execute(f.core, echo_services_start, NULL) == DP_OK);
    send_tcp(&f, 2, 1000, 0, "");
    struct frame *syn = find(&f, 6, -1, 0);
    CHECK(syn);
    CHECK((syn->bytes[67] & 0x12) == 0x12);
    uint32_t server = u32(syn->bytes + 58);
    CHECK(u32(syn->bytes + 62) == 1001);
    f.count = 0;
    send_tcp(&f, 16, 1001, server + 1, "");
    send_tcp(&f, 24, 1001, server + 1, "hello");
    struct frame *echo = find(&f, 6, -1, 1);
    CHECK(echo);
    size_t header = (echo->bytes[66] >> 4) * 4;
    CHECK(u16(echo->bytes + 18) == header + 5);
    CHECK(!memcmp(echo->bytes + 54 + header, "hello", 5));
    f.count = 0;
    send_tcp(&f, 16, 1006, server + 6, "");
    close_core(&f);
}
static void send_udp_bytes(struct fixture *f, uint16_t port, const uint8_t *data, size_t n) {
    uint8_t frame[1514];
    CHECK(n <= 1452);
    ethernet(frame, local_mac);
    uint8_t *p = ipv6(frame, local_ip, 17, (uint16_t)(8 + n));
    w16(p, 50000);
    w16(p + 2, port);
    w16(p + 4, (uint16_t)(8 + n));
    memcpy(p + 8, data, n);
    uint16_t c = checksum(frame + 14, p, 8 + n, 17);
    w16(p + 6, c ? c : 65535);
    CHECK(dp_core_input(f->core, frame, 62 + n) == DP_OK);
}
static void send_udp(struct fixture *f, uint16_t port, const char *data) {
    send_udp_bytes(f, port, (const uint8_t *)data, strlen(data));
}
static void test_udp(void) {
    struct fixture f;
    ready_core(&f);
    CHECK(dp_core_execute(f.core, echo_services_start, NULL) == DP_OK);
    send_udp(&f, 6000, "hello");
    struct frame *echo = find(&f, 17, -1, 0);
    CHECK(echo);
    CHECK(u16(echo->bytes + 54) == 6000 && u16(echo->bytes + 56) == 50000);
    CHECK(!memcmp(echo->bytes + 62, "hello", 5));
    f.count = 0;
    send_udp(&f, 6000, "");
    echo = find(&f, 17, -1, 0);
    CHECK(echo && u16(echo->bytes + 18) == 8);
    f.count = 0;
    send_udp(&f, 6001, "x");
    CHECK(find(&f, 58, 1, 0));
    close_core(&f);
}
static void test_udp_endpoint(void) {
    struct fixture f;
    open_core(&f, 0);
    uint64_t prepared;
    uint16_t prepared_port;
    CHECK(dp_udp_bind(f.core, 0, &prepared, &prepared_port) == DP_OK);
    CHECK(dp_udp_send(f.core, prepared, peer_ip, 50000, NULL, 0) == DP_WOULD_BLOCK);
    close_core(&f);
    ready_core(&f);
    uint64_t handle = 0, conflict = 9;
    uint16_t port = 0, conflict_port = 9;
    CHECK(dp_udp_bind(f.core, 0, &handle, &port) == DP_OK);
    CHECK(handle > 0 && port != 0);
    CHECK(dp_udp_bind(f.core, port, &conflict, &conflict_port) == DP_ADDRESS_IN_USE);
    CHECK(conflict == 0 && conflict_port == 0);
    dp_udp_datagram datagram;
    uint8_t data[16] = {0};
    CHECK(dp_udp_receive(f.core, handle, data, sizeof(data), &datagram) == DP_WOULD_BLOCK);
    send_udp(&f, port, "");
    CHECK(dp_udp_receive(f.core, handle, NULL, 0, &datagram) == DP_OK);
    CHECK(datagram.original_length == 0 && datagram.copied_length == 0);
    CHECK(datagram.source_port == 50000 && !memcmp(datagram.source, peer_ip, 16));
    send_udp(&f, port, "hello");
    CHECK(dp_udp_receive(f.core, handle, data, 2, &datagram) == DP_OK);
    CHECK(datagram.original_length == 5 && datagram.copied_length == 2 && !memcmp(data, "he", 2));
    CHECK(dp_udp_receive(f.core, handle, data, sizeof(data), &datagram) == DP_WOULD_BLOCK);
    f.count = 0;
    CHECK(dp_udp_send(f.core, handle, peer_ip, 50000, (const uint8_t *)"reply", 5) == DP_OK);
    struct frame *reply = find(&f, 17, -1, 0);
    CHECK(reply && u16(reply->bytes + 54) == port && !memcmp(reply->bytes + 62, "reply", 5));
    f.count = 0;
    CHECK(dp_udp_send(f.core, handle, peer_ip, 50000, NULL, 0) == DP_OK);
    reply = find(&f, 17, -1, 0);
    CHECK(reply && u16(reply->bytes + 18) == 8 && u16(reply->bytes + 54) == port);
    f.count = 0;
    f.ready = 0; /* The queue can stop accepting after the initial ready check. */
    CHECK(dp_udp_send(f.core, handle, peer_ip, 50000, (const uint8_t *)"x", 1) == DP_WOULD_BLOCK);
    f.ready = 1;
    CHECK(dp_core_set_link_ready(f.core, 1) == DP_OK);
    for (unsigned i = 0; i <= DP_UDP_QUEUE_PACKETS; ++i) send_udp(&f, port, "");
    dp_udp_queue_stats queued;
    CHECK(dp_udp_get_queue_stats(f.core, handle, &queued) == DP_OK);
    CHECK(queued.packets == DP_UDP_QUEUE_PACKETS && queued.bytes == 0 && queued.dropped == 1);
    for (unsigned i = 0; i < DP_UDP_QUEUE_PACKETS; ++i)
        CHECK(dp_udp_receive(f.core, handle, NULL, 0, &datagram) == DP_OK);
    uint8_t large[1000], received[1000];
    for (size_t i = 0; i < sizeof(large); ++i) large[i] = (uint8_t)i;
    /* Exercise the byte ceiling independently of the packet ceiling. */
    for (unsigned i = 0; i < 66; ++i) {
        w16(large, (uint16_t)i);
        send_udp_bytes(&f, port, large, sizeof(large));
    }
    CHECK(dp_udp_get_queue_stats(f.core, handle, &queued) == DP_OK);
    CHECK(queued.packets == 65 && queued.bytes == 65000 && queued.dropped == 2);
    /* Pop, then wrap both descriptor and payload rings and verify each byte. */
    for (unsigned i = 0; i < 130; ++i) {
        CHECK(dp_udp_receive(f.core, handle, received, sizeof(received), &datagram) == DP_OK);
        CHECK(datagram.original_length == sizeof(large) && u16(received) == i);
        CHECK(!memcmp(large + 2, received + 2, sizeof(large) - 2));
        if (i < 65) {
            w16(large, (uint16_t)(65 + i));
            send_udp_bytes(&f, port, large, sizeof(large));
        }
    }
    CHECK(dp_udp_get_queue_stats(f.core, handle, &queued) == DP_OK);
    CHECK(queued.packets == 0 && queued.bytes == 0);
    CHECK(dp_udp_close(f.core, handle) == DP_OK);
    CHECK(dp_udp_receive(f.core, handle, data, sizeof(data), &datagram) == DP_INVALID_ARGUMENT);
    uint64_t stale = handle;
    CHECK(dp_udp_bind(f.core, port, &handle, &port) == DP_OK && handle != stale);
    send_udp(&f, port, "queued at close");
    dp_stats stats;
    CHECK(dp_core_get_stats(f.core, &stats) == DP_OK);
    CHECK(stats.pbuf_used == 0 && stats.heap_used >= DP_UDP_QUEUE_BYTES);
    uint16_t ports[32];
    uint64_t endpoint_handles[32];
    ports[0] = port;
    endpoint_handles[0] = handle;
    for (unsigned i = 1; i < 32; ++i) {
        uint64_t another;
        uint16_t another_port;
        CHECK(dp_udp_bind(f.core, 0, &another, &another_port) == DP_OK);
        ports[i] = another_port;
        endpoint_handles[i] = another;
    }
    CHECK(dp_core_get_stats(f.core, &stats) == DP_OK);
    CHECK(stats.udp_pcbs == 32 && stats.configured_bytes < 16 * 1024 * 1024);
    CHECK(stats.heap_used <= DP_UDP_GLOBAL_QUEUE_BYTES);
    CHECK(dp_udp_bind(f.core, 0, &conflict, &conflict_port) == DP_RESOURCE_LIMIT);
    CHECK(conflict == 0 && conflict_port == 0);
    uint64_t total_bytes = 0, total_dropped = 0;
    /* The plan's 512 KiB UDP receive ceiling applies across all endpoints. */
    for (unsigned i = 0; i < 32; ++i)
        for (unsigned packet = 0; packet < 17; ++packet)
            send_udp_bytes(&f, ports[i], large, sizeof(large));
    for (unsigned i = 0; i < 32; ++i) {
        CHECK(dp_udp_get_queue_stats(f.core, endpoint_handles[i], &queued) == DP_OK);
        total_bytes += queued.bytes;
        total_dropped += queued.dropped;
    }
    CHECK(total_bytes <= 512 * 1024 && total_dropped > 0);
    close_core(&f); /* Queued data and PCB must be reclaimed by registry teardown. */
    open_core(&f, 0);
    CHECK(dp_udp_close(f.core, stale) == DP_INVALID_ARGUMENT);
    CHECK(dp_core_get_stats(f.core, &stats) == DP_OK);
    CHECK(stats.udp_pcbs == 0 && stats.pbuf_used == 0 && stats.heap_used == 0);
    close_core(&f);
}
static int read_peer_mtu(void *arg) {
    ip6_addr_t address;
    IP6_ADDR(&address, PP_HTONL(0xfe800000), 0, 0, PP_HTONL(2));
    ip6_addr_assign_zone(&address, IP6_UNICAST, netif_list);
    *(uint16_t *)arg = nd6_get_destination_mtu(&address, netif_list);
    return 0;
}
static void test_pmtu(void) {
    const uint32_t advertised[] = {1, 0, 1279, 1280, 1400, 65535, 65536, 0xffffffff};
    for (size_t i = 0; i < sizeof(advertised) / sizeof(advertised[0]); ++i) {
        struct fixture f;
        ready_core(&f);
        CHECK(dp_core_execute(f.core, echo_services_start, NULL) == DP_OK);
        send_udp(&f, 6000, "before");
        struct frame *echo = find(&f, 17, -1, 0);
        CHECK(echo != NULL);
        uint8_t quote[40];
        memcpy(quote, echo->bytes + 14, sizeof(quote));
        f.count = 0;
        uint8_t frame[102];
        ethernet(frame, local_mac);
        uint8_t *icmp = ipv6(frame, local_ip, 58, 48);
        icmp[0] = 2;
        w32(icmp + 4, advertised[i]);
        memcpy(icmp + 8, quote, sizeof(quote));
        w16(icmp + 2, checksum(frame + 14, icmp, 48, 58));
        CHECK(dp_core_input(f.core, frame, sizeof(frame)) == DP_OK);
        uint16_t mtu = 0;
        CHECK(dp_core_execute(f.core, read_peer_mtu, &mtu) == DP_OK);
        uint16_t expected = advertised[i] < 1280 ? 1500 :
            (advertised[i] > 65535 ? 65535 : (uint16_t)advertised[i]);
        CHECK(mtu == expected);
        send_udp(&f, 6000, "after");
        echo = find(&f, 17, -1, 0);
        CHECK(echo && !memcmp(echo->bytes + 62, "after", 5));
        close_core(&f);
    }
}
static void test_lifecycle(void) {
    for (int i = 0; i < 100; i++) {
        struct fixture f;
        open_core(&f, 0);
        CHECK(dp_core_execute(f.core, echo_services_start, NULL) == DP_OK);
        dp_core *other = NULL;
        dp_config config = {0};
        memcpy(config.mac, local_mac, 6);
        memcpy(config.ipv6, local_ip, 16);
        config.mtu = 1500;
        config.opaque = &f;
        config.clock_ms = clock_ms;
        config.random32 = random32;
        config.output = output;
        CHECK(dp_core_create(&config, &other) == DP_RESOURCE_LIMIT && !other);
        dp_stats stats;
        CHECK(dp_core_get_stats(f.core, &stats) == DP_OK);
        CHECK(stats.tcp_listeners == 1 && stats.udp_pcbs == 1 && stats.tcp_active == 0);
        CHECK(stats.configured_bytes < 16 * 1024 * 1024 && stats.pbuf_used == 0);
        close_core(&f);
    }
}
static void test_not_ready(void) {
    struct fixture f;
    open_core(&f, 0);
    CHECK(dp_core_execute(f.core, echo_services_start, NULL) == DP_OK);
    advance(&f, 5000);
    CHECK(f.count == 0);
    CHECK(address_state(&f) == DP_ADDRESS_TENTATIVE);
    f.ready = 1;
    CHECK(dp_core_set_link_ready(f.core, 1) == DP_OK);
    advance(&f, 3000);
    CHECK(address_state(&f) == DP_ADDRESS_PREFERRED);
    close_core(&f);
}
static void test_tx_not_ready(void) {
    struct fixture f;
    open_core(&f, 1);
    f.ready = 0;
    advance(&f, 5000);
    CHECK(f.count == 0);
    CHECK(address_state(&f) == DP_ADDRESS_TENTATIVE);
    f.ready = 1;
    CHECK(dp_core_set_link_ready(f.core, 1) == DP_OK);
    advance(&f, 3000);
    CHECK(address_state(&f) == DP_ADDRESS_PREFERRED);
    close_core(&f);
}
static void test_malformed(void) {
    struct fixture f;
    ready_core(&f);
    uint8_t frame[1515] = {0};
    CHECK(dp_core_input(f.core, frame, 13) == DP_INVALID_ARGUMENT);
    CHECK(dp_core_input(f.core, frame, sizeof(frame)) == DP_INVALID_ARGUMENT);
    CHECK(dp_core_input(f.core, frame, 64) == DP_UNSUPPORTED);
    for (int i = 0; i < 100; i++) {
        ethernet(frame, local_mac);
        CHECK(dp_core_input(f.core, frame, 64) == DP_OK);
    }
    dp_stats stats;
    CHECK(dp_core_get_stats(f.core, &stats) == DP_OK);
    CHECK(stats.pbuf_used == 0);
    close_core(&f);
}
static void fragment(struct fixture *f, const uint8_t *datagram, size_t offset, size_t length,
                     int more, uint32_t id) {
    uint8_t frame[1514];
    CHECK(length <= 1448 && offset % 8 == 0);
    ethernet(frame, local_mac);
    uint8_t *p = ipv6(frame, local_ip, 44, (uint16_t)(8 + length));
    p[0] = 17;
    w16(p + 2, (uint16_t)(offset | (more ? 1 : 0)));
    w32(p + 4, id);
    memcpy(p + 8, datagram + offset, length);
    CHECK(dp_core_input(f->core, frame, 62 + length) == DP_OK);
}
static void test_fragments(void) {
    struct fixture f;
    ready_core(&f);
    CHECK(dp_core_execute(f.core, echo_services_start, NULL) == DP_OK);
    uint8_t datagram[2056] = {0};
    w16(datagram, 50000);
    w16(datagram + 2, 6000);
    w16(datagram + 4, sizeof(datagram));
    for (size_t i = 8; i < sizeof(datagram); i++)
        datagram[i] = (uint8_t)i;
    uint8_t pseudo[40] = {0};
    memcpy(pseudo + 8, peer_ip, 16);
    memcpy(pseudo + 24, local_ip, 16);
    w16(datagram + 6, checksum(pseudo, datagram, sizeof(datagram), 17));
    /* Out-of-order input must reassemble without losing UDP boundaries. */
    fragment(&f, datagram, 1200, 856, 0, 42);
    fragment(&f, datagram, 0, 1200, 1, 42);
    uint8_t reply[2056] = {0};
    size_t total = 0, pieces = 0;
    for (size_t i = 0; i < f.count; i++) {
        const uint8_t *ip = f.frames[i].bytes + 14;
        if (ip[6] != 44)
            continue;
        const uint8_t *p = ip + 40;
        CHECK(p[0] == 17 && u16(ip + 4) >= 8);
        size_t offset = u16(p + 2) & 0xfff8;
        size_t length = u16(ip + 4) - 8;
        CHECK(offset + length <= sizeof(reply));
        memcpy(reply + offset, p + 8, length);
        pieces++;
        if (!(u16(p + 2) & 1))
            total = offset + length;
    }
    CHECK(pieces >= 2 && total == sizeof(reply));
    CHECK(u16(reply) == 6000 && u16(reply + 2) == 50000);
    CHECK(!memcmp(reply + 8, datagram + 8, sizeof(reply) - 8));
    memcpy(pseudo + 8, local_ip, 16);
    memcpy(pseudo + 24, peer_ip, 16);
    CHECK(checksum(pseudo, reply, sizeof(reply), 17) == 0);
    /* Incomplete reassembly must not survive a context generation. */
    fragment(&f, datagram, 0, 1200, 1, 43);
    dp_stats stats;
    CHECK(dp_core_get_stats(f.core, &stats) == DP_OK);
    CHECK(stats.pbuf_used > 0);
    close_core(&f);
    open_core(&f, 0);
    CHECK(dp_core_get_stats(f.core, &stats) == DP_OK);
    CHECK(stats.pbuf_used == 0 && stats.heap_used == 0);
    close_core(&f);
}
static void test_udp_endpoint_fragments(void) {
    struct fixture f;
    ready_core(&f);
    uint64_t handle;
    uint16_t port;
    CHECK(dp_udp_bind(f.core, 0, &handle, &port) == DP_OK);
    uint8_t datagram[32776] = {0}, received[32768], reply[32776] = {0};
    w16(datagram, 50000); w16(datagram + 2, port); w16(datagram + 4, sizeof(datagram));
    for (size_t i = 8; i < sizeof(datagram); ++i) datagram[i] = (uint8_t)(i ^ (i >> 8));
    uint8_t pseudo[40] = {0};
    memcpy(pseudo + 8, peer_ip, 16); memcpy(pseudo + 24, local_ip, 16);
    w16(datagram + 6, checksum(pseudo, datagram, sizeof(datagram), 17));
    fragment(&f, datagram, 32400, sizeof(datagram) - 32400, 0, 345);
    for (size_t offset = 0; offset < 32400; offset += 1200)
        fragment(&f, datagram, offset, 1200, 1, 345);
    dp_udp_datagram info;
    CHECK(dp_udp_receive(f.core, handle, received, sizeof(received), &info) == DP_OK);
    CHECK(info.original_length == sizeof(received) && info.copied_length == sizeof(received));
    CHECK(!memcmp(received, datagram + 8, sizeof(received)));
    CHECK(info.source_port == 50000 && !memcmp(info.source, peer_ip, 16));
    f.count = 0;
    CHECK(dp_udp_send(f.core, handle, peer_ip, 50000, received, sizeof(received)) == DP_OK);
    size_t total = 0, pieces = 0;
    for (size_t i = 0; i < f.count; ++i) {
        const uint8_t *ip = f.frames[i].bytes + 14;
        if (ip[6] != 44) continue;
        const uint8_t *frag = ip + 40;
        size_t offset = u16(frag + 2) & 0xfff8, length = u16(ip + 4) - 8;
        CHECK(frag[0] == 17 && offset + length <= sizeof(reply));
        memcpy(reply + offset, frag + 8, length);
        if (!(u16(frag + 2) & 1)) total = offset + length;
        pieces++;
    }
    CHECK(pieces > 1 && total == sizeof(reply));
    CHECK(u16(reply) == port && u16(reply + 2) == 50000);
    CHECK(!memcmp(reply + 8, received, sizeof(received)));
    memcpy(pseudo + 8, local_ip, 16); memcpy(pseudo + 24, peer_ip, 16);
    CHECK(checksum(pseudo, reply, sizeof(reply), 17) == 0);
    close_core(&f);
}
static void test_tx_failed(void) {
    struct fixture f;
    open_core(&f, 1);
    f.ready = -1;
    f.now = 1000;
    CHECK(dp_core_poll(f.core) == DP_LINK_IO);
    dp_stats stats;
    CHECK(dp_core_get_stats(f.core, &stats) == DP_OK);
    CHECK(stats.tx_failed > 0 && stats.tx_accepted == 0 && stats.failure == DP_LINK_IO);
    close_core(&f);
}
static int reenter(void *arg) {
    struct fixture *f = arg;
    CHECK(dp_core_poll(f->core) == DP_REENTRANT);
    return 0;
}
static void test_api(void) {
    struct fixture f;
    open_core(&f, 0);
    CHECK(dp_core_abi_version() == 1);
    CHECK(dp_core_execute(f.core, reenter, &f) == DP_OK);
    CHECK(dp_core_next_timeout(f.core, NULL) == DP_INVALID_ARGUMENT);
    CHECK(dp_core_address_state(f.core, NULL) == DP_INVALID_ARGUMENT);
    f.now = 100;
    CHECK(dp_core_poll(f.core) == DP_OK);
    f.now = 50;
    CHECK(dp_core_poll(f.core) == DP_CLOCK_REVERSED);
    dp_stats stats;
    CHECK(dp_core_get_stats(f.core, &stats) == DP_OK);
    CHECK(stats.failure == DP_CLOCK_REVERSED);
    close_core(&f);
}
#ifdef _WIN32
static DWORD WINAPI wrong_thread(void *arg) {
#else
static void *wrong_thread(void *arg) {
#endif
    dp_core *core = arg;
    CHECK(dp_core_poll(core) == DP_WRONG_THREAD);
    CHECK(dp_core_destroy(&core) == DP_WRONG_THREAD && core == arg);
    return 0;
}
static void test_thread(void) {
    struct fixture f;
    open_core(&f, 0);
#ifdef _WIN32
    HANDLE thread = CreateThread(NULL, 0, wrong_thread, f.core, 0, NULL);
    CHECK(thread && WaitForSingleObject(thread, 3000) == WAIT_OBJECT_0);
    CloseHandle(thread);
#else
    pthread_t thread;
    CHECK(pthread_create(&thread, NULL, wrong_thread, f.core) == 0);
    CHECK(pthread_join(thread, NULL) == 0);
#endif
    close_core(&f);
}
int main(int argc, char **argv) {
    CHECK(argc == 2);
    if (!strcmp(argv[1], "ndp"))
        test_ndp();
    else if (!strcmp(argv[1], "dad"))
        test_dad();
    else if (!strcmp(argv[1], "tcp"))
        test_tcp();
    else if (!strcmp(argv[1], "tcp_large_refused"))
        test_tcp_large_refused();
    else if (!strcmp(argv[1], "tcp_endpoint"))
        test_tcp_endpoint();
    else if (!strcmp(argv[1], "tcp_backpressure"))
        test_tcp_backpressure();
    else if (!strcmp(argv[1], "tcp_endpoint_limits"))
        test_tcp_endpoint_limits();
    else if (!strcmp(argv[1], "tcp_endpoint_lifecycle"))
        test_tcp_endpoint_lifecycle();
    else if (!strcmp(argv[1], "udp"))
        test_udp();
    else if (!strcmp(argv[1], "lifecycle"))
        test_lifecycle();
    else if (!strcmp(argv[1], "not_ready"))
        test_not_ready();
    else if (!strcmp(argv[1], "tx_not_ready"))
        test_tx_not_ready();
    else if (!strcmp(argv[1], "tx_failed"))
        test_tx_failed();
    else if (!strcmp(argv[1], "fragments"))
        test_fragments();
    else if (!strcmp(argv[1], "ndp_options"))
        test_ndp_options();
    else if (!strcmp(argv[1], "pmtu"))
        test_pmtu();
    else if (!strcmp(argv[1], "udp_endpoint"))
        test_udp_endpoint();
    else if (!strcmp(argv[1], "udp_endpoint_fragments"))
        test_udp_endpoint_fragments();
    else if (!strcmp(argv[1], "api"))
        test_api();
    else if (!strcmp(argv[1], "thread"))
        test_thread();
    else if (!strcmp(argv[1], "malformed"))
        test_malformed();
    else
        return 2;
    printf("PASS %s\n", argv[1]);
    return 0;
}
