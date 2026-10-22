#include "diplay_lwip.h"
#include "diplay_endpoints.h"
#include "diplay_tcp.h"
#include "echo_services.h"
/* Keep lwIP byte-order aliases out of the Windows SDK declarations below. */
#define LWIP_DONT_PROVIDE_BYTEORDER_FUNCTIONS
#include "lwip/nd6.h"
#include "lwip/netif.h"
#include "lwip/memp.h"
#include "lwip/mem.h"
#include "lwip/stats.h"
#include "lwip/timeouts.h"
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
#ifdef DP_WRAP_ALLOCATION
static struct { size_t calls, fail_at, calloc_calls, rejected; int armed, persistent;
                struct sys_timeo *last_timeout; } allocation_fault;
void *__real_mem_malloc(mem_size_t size);
void *__real_calloc(size_t count, size_t size);
#if MEMP_OVERFLOW_CHECK
void *__real_memp_malloc_fn(memp_t type, const char *file, int line);
#else
void *__real_memp_malloc(memp_t type);
#endif
static int reject_allocation(void) {
    if (!allocation_fault.armed) return 0;
    ++allocation_fault.calls;
    if (allocation_fault.fail_at && (allocation_fault.calls == allocation_fault.fail_at ||
        (allocation_fault.persistent && allocation_fault.calls > allocation_fault.fail_at))) {
        allocation_fault.rejected++;
        return 1;
    }
    return 0;
}
void *__wrap_mem_malloc(mem_size_t size) {
    if (reject_allocation()) { MEM_STATS_INC(err); return NULL; }
    return __real_mem_malloc(size);
}
void *__wrap_calloc(size_t count, size_t size) {
    if (allocation_fault.armed) allocation_fault.calloc_calls++;
    return reject_allocation() ? NULL : __real_calloc(count, size);
}
#if MEMP_OVERFLOW_CHECK
void *__wrap_memp_malloc_fn(memp_t type, const char *file, int line) {
    if (reject_allocation()) { STATS_INC(memp[type]->err); return NULL; }
    void *result = __real_memp_malloc_fn(type, file, line);
    if (type == MEMP_SYS_TIMEOUT && result) allocation_fault.last_timeout = result;
    return result;
}
#else
void *__wrap_memp_malloc(memp_t type) {
    if (reject_allocation()) { STATS_INC(memp[type]->err); return NULL; }
    void *result = __real_memp_malloc(type);
    if (type == MEMP_SYS_TIMEOUT && result) allocation_fault.last_timeout = result;
    return result;
}
#endif
#endif
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
    size_t fail_after;
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
    if (f->ready < 0 || (f->fail_after && f->count >= f->fail_after))
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
static void send_tcp_pair(struct fixture *f, uint16_t port, uint16_t destination, uint8_t flags, uint32_t sequence, uint32_t ack,
                           const void *data, size_t n) {
    uint8_t frame[1514];
    CHECK(n <= 1440);
    ethernet(frame, local_mac);
    uint8_t *p = ipv6(frame, local_ip, 6, (uint16_t)(20 + n));
    w16(p, port);
    w16(p + 2, destination);
    w32(p + 4, sequence);
    w32(p + 8, ack);
    p[12] = 0x50;
    p[13] = flags;
    w16(p + 14, 32768);
    memcpy(p + 20, data, n);
    w16(p + 16, checksum(frame + 14, p, 20 + n, 6));
    CHECK(dp_core_input(f->core, frame, 74 + n) == DP_OK);
}
static void send_tcp_port(struct fixture *f, uint16_t port, uint8_t flags, uint32_t sequence, uint32_t ack,
                          const void *data, size_t n) {
    send_tcp_pair(f, port, 7000, flags, sequence, ack, data, n);
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
static void test_tcp_write(void) {
    struct fixture f; ready_core(&f);
    uint64_t listener; uint16_t port;
    CHECK(dp_tcp_listen(f.core, 7000, 2, &listener, &port) == DP_OK);
    uint32_t server = establish(&f, 50000);
    uint64_t stream = accept_stream(&f, listener);
    enum { TOTAL = 40320 };
    uint8_t source[TOTAL], expected[TOTAL], seen[TOTAL];
    for (size_t i = 0; i < TOTAL; ++i) source[i] = expected[i] = (uint8_t)(i ^ (i >> 8));
    memset(seen, 0, sizeof(seen));
    size_t copied = 99;
    CHECK(dp_tcp_write(f.core, stream, NULL, 0, &copied) == DP_OK && copied == 0);
    CHECK(dp_tcp_write(f.core, listener, source, 1, &copied) == DP_INVALID_ARGUMENT && copied == 0);
    CHECK(dp_tcp_write(f.core, stream, NULL, 1, &copied) == DP_INVALID_ARGUMENT && copied == 0);
    CHECK(dp_tcp_write(f.core, stream, source, TOTAL, &copied) == DP_OK && copied == 32768);
    size_t accepted_bytes = copied;
    memset(source, 0, copied); /* Already accepted bytes must no longer borrow this buffer. */
    CHECK(dp_tcp_write(f.core, stream, source, 1, &copied) == DP_WOULD_BLOCK && copied == 0);
    size_t received_bytes = 0;
    for (unsigned attempt = 0; received_bytes < TOTAL && attempt < 64; ++attempt) {
        collect_echo(&f, server, expected, seen, TOTAL);
        while (received_bytes < TOTAL && seen[received_bytes]) received_bytes++;
        send_tcp(&f, 16, 1001, server + 1 + (uint32_t)received_bytes, "");
        if (accepted_bytes < TOTAL) {
            dp_result result = dp_tcp_write(f.core, stream, source + accepted_bytes, TOTAL - accepted_bytes, &copied);
            CHECK(result == DP_OK || (result == DP_WOULD_BLOCK && copied == 0));
            accepted_bytes += copied;
        }
        advance(&f, 250);
    }
    CHECK(accepted_bytes == TOTAL && received_bytes == TOTAL);
    f.count = 0;
    CHECK(dp_tcp_close(f.core, stream) == DP_OK);
    CHECK(dp_tcp_listener_close(f.core, listener) == DP_OK);
    dp_stats stats; CHECK(dp_core_get_stats(f.core, &stats) == DP_OK && stats.heap_used == 0 && stats.pbuf_used == 0);
    close_core(&f);
}
static void test_tcp_half_close(void) {
    for (unsigned mode = 0; mode < 4; ++mode) {
        struct fixture f; ready_core(&f);
        uint64_t listener; uint16_t port;
        CHECK(dp_tcp_listen(f.core, 7000, 2, &listener, &port) == DP_OK);
        uint32_t server = establish(&f, 50000);
        uint64_t stream = accept_stream(&f, listener);
        uint8_t target[8]; size_t copied;
        uint32_t fin_ack = server + 2;
        if (mode == 1) {
            send_tcp(&f, 25, 1001, server + 1, "after");
            CHECK(dp_tcp_write(f.core, stream, (const uint8_t *)"R", 1, &copied) == DP_OK && copied == 1);
            fin_ack++;
        }
        f.count = 0;
        CHECK(dp_tcp_shutdown_output(f.core, stream) == DP_OK);
        size_t frames = f.count;
        CHECK(dp_tcp_shutdown_output(f.core, stream) == DP_OK && f.count == frames);
        int fin = 0;
        for (size_t i = 0; i < f.count; ++i) if (f.frames[i].bytes[20] == 6 && (f.frames[i].bytes[67] & 1)) {
            CHECK(u32(f.frames[i].bytes + 58) == fin_ack - 1); fin = 1;
        }
        CHECK(fin);
        CHECK(dp_tcp_write(f.core, stream, (const uint8_t *)"x", 1, &copied) == DP_PEER_CLOSED && copied == 0);
        if (mode == 3) {
            send_tcp(&f, 20, 1001, fin_ack, "");
            CHECK(dp_tcp_read(f.core, stream, target, 1, &copied) == DP_PEER_RESET);
            CHECK(dp_tcp_write(f.core, stream, (const uint8_t *)"x", 1, &copied) == DP_PEER_RESET && copied == 0);
            CHECK(dp_tcp_close(f.core, stream) == DP_OK);
            CHECK(dp_tcp_listener_close(f.core, listener) == DP_OK);
            close_core(&f);
            continue;
        }
        if (mode == 0) {
            send_tcp(&f, 16, 1001, fin_ack, "");
            send_tcp(&f, 25, 1001, fin_ack, "after");
        } else if (mode == 2) {
            send_tcp(&f, 25, 1001, server + 1, "after"); /* FIN arrives before acknowledgment of our FIN. */
            send_tcp(&f, 16, 1007, fin_ack, "");
        } else send_tcp(&f, 16, 1007, fin_ack, "");
        if (mode != 1) {
            f.count = 0;
            send_tcp(&f, 17, 1006, fin_ack, ""); /* TIME_WAIT still acknowledges a duplicate FIN. */
            struct frame *ack = find(&f, 6, -1, 0);
            CHECK(ack && (ack->bytes[67] & 0x14) == 0x10 && u32(ack->bytes + 62) == 1007);
        }
        f.count = 0;
        advance(&f, 121000); /* Expire TIME_WAIT without dereferencing its former PCB. */
        establish(&f, 50001);
        uint64_t fresh = accept_stream(&f, listener);
        CHECK(dp_tcp_read(f.core, stream, target, sizeof(target), &copied) == DP_OK && copied == 5 && !memcmp(target, "after", 5));
        CHECK(dp_tcp_read(f.core, stream, target, 1, &copied) == DP_PEER_CLOSED && copied == 0);
        CHECK(dp_tcp_shutdown_output(f.core, stream) == DP_OK);
        CHECK(dp_tcp_close(f.core, stream) == DP_OK);
        CHECK(dp_tcp_read(f.core, fresh, target, 1, &copied) == DP_WOULD_BLOCK);
        f.count = 0;
        CHECK(dp_tcp_close(f.core, fresh) == DP_OK);
        CHECK(dp_tcp_listener_close(f.core, listener) == DP_OK);
        dp_stats stats; CHECK(dp_core_get_stats(f.core, &stats) == DP_OK && stats.heap_used == 0 && stats.pbuf_used == 0);
        close_core(&f);
    }
}
static void test_tcp_write_io(void) {
    struct fixture f; ready_core(&f);
    uint64_t listener; uint16_t port;
    CHECK(dp_tcp_listen(f.core, 7000, 2, &listener, &port) == DP_OK);
    uint32_t server = establish(&f, 50000);
    uint64_t stream = accept_stream(&f, listener);
    size_t copied;
    CHECK(dp_core_set_link_ready(f.core, 0) == DP_OK);
    CHECK(dp_tcp_write(f.core, stream, (const uint8_t *)"x", 1, &copied) == DP_WOULD_BLOCK && copied == 0);
    CHECK(dp_core_set_link_ready(f.core, 1) == DP_OK);
    f.count = 0; f.ready = 0;
    uint8_t source[4] = {'h', 'e', 'l', 'd'}, seen[4] = {0};
    CHECK(dp_tcp_write(f.core, stream, source, sizeof(source), &copied) == DP_OK && copied == sizeof(source));
    memset(source, 0, sizeof(source));
    f.ready = 1;
    CHECK(dp_core_set_link_ready(f.core, 1) == DP_OK);
    advance(&f, 5000);
    collect_echo(&f, server, (const uint8_t *)"held", seen, sizeof(seen));
    CHECK(seen[0] && seen[1] && seen[2] && seen[3]);
    send_tcp(&f, 16, 1001, server + 5, "");
    f.ready = -1;
    CHECK(dp_tcp_write(f.core, stream, (const uint8_t *)"bad", 3, &copied) == DP_LINK_IO && copied == 3);
    close_core(&f); /* Fatal output failure does not strand copied data or endpoint records. */
}
struct segment_pressure { void *blocks[512]; size_t count; int release; };
static int pressure_segments(void *arg) {
    struct segment_pressure *pressure = arg;
    if (pressure->release) {
        while (pressure->count) memp_free(MEMP_TCP_SEG, pressure->blocks[--pressure->count]);
    } else {
        void *block;
        while ((block = memp_malloc(MEMP_TCP_SEG)) != NULL) {
            CHECK(pressure->count < 512);
            pressure->blocks[pressure->count++] = block;
        }
    }
    return 0;
}
static void test_tcp_shutdown_pressure(void) {
    for (unsigned cancel = 0; cancel < 2; ++cancel) {
        struct fixture f; ready_core(&f);
        uint64_t listener; uint16_t port;
        CHECK(dp_tcp_listen(f.core, 7000, 2, &listener, &port) == DP_OK);
        uint32_t server = establish(&f, 50000);
        uint64_t stream = accept_stream(&f, listener);
        struct segment_pressure pressure = {0};
        CHECK(dp_core_execute(f.core, pressure_segments, &pressure) == DP_OK && pressure.count > 0);
        f.count = 0;
        CHECK(dp_tcp_shutdown_output(f.core, stream) == DP_OK && f.count == 0);
        size_t copied;
        CHECK(dp_tcp_write(f.core, stream, (const uint8_t *)"x", 1, &copied) == DP_PEER_CLOSED && copied == 0);
        if (cancel) CHECK(dp_tcp_close(f.core, stream) == DP_OK);
        pressure.release = 1;
        CHECK(dp_core_execute(f.core, pressure_segments, &pressure) == DP_OK && pressure.count == 0);
        if (!cancel) {
            advance(&f, 500);
            struct frame *fin = find(&f, 6, -1, 0);
            CHECK(fin && (fin->bytes[67] & 1));
            send_tcp(&f, 17, 1001, server + 2, "");
            uint8_t byte;
            CHECK(dp_tcp_read(f.core, stream, &byte, 1, &copied) == DP_PEER_CLOSED);
            CHECK(dp_tcp_close(f.core, stream) == DP_OK);
        }
        f.count = 0;
        CHECK(dp_tcp_listener_close(f.core, listener) == DP_OK);
        close_core(&f);
    }
}
static void test_tcp_graceful_close(void) {
    for (unsigned mode = 0; mode < 3; ++mode) {
        struct fixture f; ready_core(&f);
        uint64_t listener; uint16_t port;
        CHECK(dp_tcp_listen(f.core, 7000, 2, &listener, &port) == DP_OK);
        uint32_t server = establish(&f, 50000);
        uint64_t stream = accept_stream(&f, listener);
        size_t copied;
        uint8_t source[4] = {'t', 'a', 'i', 'l'}, seen[4] = {0};
        if (mode == 0) {
            CHECK(dp_tcp_write(f.core, stream, source, 4, &copied) == DP_OK && copied == 4);
            memset(source, 0, sizeof(source));
        } else send_tcp(&f, mode == 1 ? 17 : 24, 1001, server + 1, mode == 1 ? "" : "unread");
        f.count = 0;
        CHECK(dp_tcp_close_graceful(f.core, listener) == DP_INVALID_ARGUMENT);
        CHECK(dp_tcp_close_graceful(f.core, stream) == DP_OK);
        CHECK(dp_tcp_close(f.core, stream) == DP_INVALID_ARGUMENT);
        CHECK(dp_tcp_read(f.core, stream, source, 1, &copied) == DP_INVALID_ARGUMENT);
        struct frame *reply = find(&f, 6, -1, 0);
        CHECK(reply && (reply->bytes[67] & (mode == 2 ? 4 : 1)));
        if (mode != 2) {
            for (size_t i = 0; i < f.count; ++i) CHECK(f.frames[i].bytes[20] != 6 || !(f.frames[i].bytes[67] & 4));
            if (mode == 0) {
                f.count = 0; advance(&f, 5000);
                collect_echo(&f, server, (const uint8_t *)"tail", seen, 4);
                CHECK(seen[0] && seen[1] && seen[2] && seen[3]);
                send_tcp(&f, 17, 1001, server + 6, "");
                f.count = 0;
                send_tcp(&f, 17, 1001, server + 6, ""); /* Detached TIME_WAIT still answers duplicate FIN. */
                reply = find(&f, 6, -1, 0);
                CHECK(reply && !(reply->bytes[67] & 4) && u32(reply->bytes + 62) == 1002);
                advance(&f, 121000);
            } else send_tcp(&f, 16, 1002, server + 2, "");
        }
        CHECK(dp_tcp_listener_close(f.core, listener) == DP_OK);
        dp_stats stats; CHECK(dp_core_get_stats(f.core, &stats) == DP_OK);
        CHECK(stats.tcp_active == 0 && stats.heap_used == 0 && stats.pbuf_used == 0);
        CHECK(dp_tcp_listen(f.core, 7000, 2, &listener, &port) == DP_OK);
        establish(&f, 50001);
        uint64_t fresh = accept_stream(&f, listener);
        CHECK(fresh != stream && dp_tcp_close_graceful(f.core, stream) == DP_INVALID_ARGUMENT);
        CHECK(dp_tcp_read(f.core, fresh, source, 1, &copied) == DP_WOULD_BLOCK);
        CHECK(dp_tcp_close(f.core, fresh) == DP_OK);
        CHECK(dp_tcp_listener_close(f.core, listener) == DP_OK);
        close_core(&f);
    }
}
static void test_tcp_graceful_pressure(void) {
    for (unsigned complete = 0; complete < 3; ++complete) {
        struct fixture f; ready_core(&f);
        uint64_t listener; uint16_t port;
        CHECK(dp_tcp_listen(f.core, 7000, 2, &listener, &port) == DP_OK);
        uint32_t server = establish(&f, 50000);
        uint64_t stream = accept_stream(&f, listener);
        struct segment_pressure pressure = {0};
        if (complete != 2)
            CHECK(dp_core_execute(f.core, pressure_segments, &pressure) == DP_OK && pressure.count > 0);
        else f.ready = 0; /* Output backpressure, rather than allocation pressure. */
        f.count = 0;
        CHECK(dp_tcp_close_graceful(f.core, stream) == DP_OK && f.count == 0);
        CHECK(dp_tcp_close_graceful(f.core, stream) == DP_INVALID_ARGUMENT);
        pressure.release = 1;
        CHECK(dp_core_execute(f.core, pressure_segments, &pressure) == DP_OK && pressure.count == 0);
        f.ready = 1;
        if (complete == 2) CHECK(dp_core_set_link_ready(f.core, 1) == DP_OK);
        if (complete) {
            advance(&f, complete == 2 ? 5000 : 500);
            struct frame *fin = find(&f, 6, -1, 0);
            CHECK(fin && (fin->bytes[67] & 1) && !(fin->bytes[67] & 4));
            send_tcp(&f, 17, 1001, server + 2, "");
            advance(&f, 121000);
        }
        CHECK(dp_tcp_listener_close(f.core, listener) == DP_OK);
        close_core(&f); /* Also frees detached FIN-pending PCBs before any retry. */
    }
}
static void test_tcp_graceful_failure(void) {
    for (unsigned mode = 0; mode < 3; ++mode) {
        struct fixture f; ready_core(&f);
        uint64_t stream, listener = 0; uint16_t port;
        if (!mode) CHECK(dp_tcp_connect(f.core, peer_ip, 9000, &stream) == DP_OK);
        else {
            CHECK(dp_tcp_listen(f.core, 7000, 2, &listener, &port) == DP_OK);
            establish(&f, 50000); stream = accept_stream(&f, listener);
        }
        f.count = 0;
        if (mode == 1) { f.now--; CHECK(dp_core_poll(f.core) == DP_CLOCK_REVERSED); }
        if (mode == 2) f.ready = -1;
        dp_result expected = mode == 1 ? DP_CLOCK_REVERSED : (mode == 2 ? DP_LINK_IO : DP_OK);
        CHECK(dp_tcp_close_graceful(f.core, stream) == expected);
        if (mode == 1) CHECK(f.count == 0); /* Failed cores suppress cleanup output. */
        if (!mode) {
            struct frame *reset = find(&f, 6, -1, 0);
            CHECK(reset && (reset->bytes[67] & 4)); /* A pending candidate is cancelled by abort. */
        }
        dp_stats stats; CHECK(dp_core_get_stats(f.core, &stats) == DP_OK);
        CHECK(stats.failure == expected);
        if (mode != 2) CHECK(stats.tcp_active == 0 && stats.pbuf_used == 0);
        if (listener) CHECK(dp_tcp_listener_close(f.core, listener) == expected);
        close_core(&f);
        ready_core(&f);
        CHECK(dp_core_get_stats(f.core, &stats) == DP_OK);
        CHECK(stats.heap_used == 0 && stats.pbuf_used == 0 && stats.tcp_active == 0);
        close_core(&f);
    }
}
static void test_tcp_liveness(void) {
    struct fixture f; ready_core(&f);
    uint64_t listener; uint16_t port;
    CHECK(dp_tcp_listen(f.core, 7000, 2, &listener, &port) == DP_OK);
    uint32_t server = establish(&f, 50000);
    uint64_t stream = accept_stream(&f, listener);
    CHECK(dp_tcp_configure_liveness(f.core, stream) == DP_OK);
    uint8_t target; size_t copied;
    for (unsigned round = 0; round < 3; ++round) {
        f.count = 0; advance(&f, 11000);
        struct frame *probe = find(&f, 6, -1, 0);
        CHECK(probe && probe->length == 74 && probe->bytes[67] == 16 && u32(probe->bytes + 58) == server);
        send_tcp(&f, 16, 1001, server + 1, ""); /* A healthy idle peer stays connected. */
        CHECK(dp_tcp_read(f.core, stream, &target, 1, &copied) == DP_WOULD_BLOCK);
    }
    CHECK(dp_tcp_configure_liveness(f.core, listener) == DP_INVALID_ARGUMENT);
    f.count = 0; advance(&f, 21000); /* Drop all probe replies. */
    CHECK(dp_tcp_read(f.core, stream, &target, 1, &copied) == DP_PEER_RESET);
    CHECK(dp_tcp_close(f.core, stream) == DP_OK);
    CHECK(dp_tcp_listener_close(f.core, listener) == DP_OK);
    close_core(&f);
}
static void test_tcp_reuse(void) {
    for (int old_reuse = 0; old_reuse < 2; ++old_reuse) for (int new_reuse = 0; new_reuse < 2; ++new_reuse) {
        struct fixture f; ready_core(&f);
        uint64_t listener, extra; uint16_t port;
        CHECK(dp_tcp_listen_with_options(f.core, 7000, 2, 2, &listener, &port) == DP_INVALID_ARGUMENT && !listener && !port);
        CHECK(dp_tcp_listen_with_options(f.core, 7000, 2, old_reuse, &listener, &port) == DP_OK);
        establish(&f, 50000); uint64_t stream = accept_stream(&f, listener);
        CHECK(dp_tcp_listener_close(f.core, listener) == DP_OK);
        dp_result expected = old_reuse && new_reuse ? DP_OK : DP_ADDRESS_IN_USE;
        CHECK(dp_tcp_listen_with_options(f.core, 7000, 2, new_reuse, &listener, &port) == expected);
        if (expected == DP_OK) {
            CHECK(dp_tcp_listen_with_options(f.core, 7000, 2, 1, &extra, &port) == DP_ADDRESS_IN_USE && !extra);
            uint8_t byte; size_t copied;
            CHECK(dp_tcp_read(f.core, stream, &byte, 1, &copied) == DP_WOULD_BLOCK);
            uint32_t fresh_server = establish(&f, 50001);
            uint64_t fresh = accept_stream(&f, listener);
            CHECK(fresh != stream);
            send_tcp_port(&f, 50001, 24, 1001, fresh_server + 1, "N", 1);
            CHECK(dp_tcp_read(f.core, fresh, &byte, 1, &copied) == DP_OK && copied == 1 && byte == 'N');
            CHECK(dp_tcp_read(f.core, stream, &byte, 1, &copied) == DP_WOULD_BLOCK);
            CHECK(dp_tcp_close(f.core, fresh) == DP_OK);
            CHECK(dp_tcp_listener_close(f.core, listener) == DP_OK);
        }
        CHECK(dp_tcp_close(f.core, stream) == DP_OK);
        close_core(&f);
    }
    for (int reuse = 0; reuse < 2; ++reuse) {
        struct fixture f; ready_core(&f);
        uint64_t listener; uint16_t port;
        CHECK(dp_tcp_listen_with_options(f.core, 7000, 2, 1, &listener, &port) == DP_OK);
        uint32_t server = establish(&f, 50000); uint64_t stream = accept_stream(&f, listener);
        CHECK(dp_tcp_close_graceful(f.core, stream) == DP_OK);
        send_tcp(&f, 17, 1001, server + 2, "");
        CHECK(dp_tcp_listener_close(f.core, listener) == DP_OK);
        CHECK(dp_tcp_listen_with_options(f.core, 7000, 2, reuse, &listener, &port) == (reuse ? DP_OK : DP_ADDRESS_IN_USE));
        if (reuse) CHECK(dp_tcp_listener_close(f.core, listener) == DP_OK);
        close_core(&f);
    }
}
static uint32_t connect_begin(struct fixture *f, uint64_t *stream, uint16_t *local_port) {
    f->count = 0;
    CHECK(dp_tcp_connect(f->core, peer_ip, 9000, stream) == DP_OK && *stream);
    struct frame *syn = find(f, 6, -1, 0);
    CHECK(syn && (syn->bytes[67] & 0x12) == 2);
    CHECK(!memcmp(syn->bytes + 22, local_ip, 16) && !memcmp(syn->bytes + 38, peer_ip, 16));
    CHECK(u16(syn->bytes + 56) == 9000);
    *local_port = u16(syn->bytes + 54);
    CHECK(*local_port != 0);
    return u32(syn->bytes + 58);
}
static void test_tcp_connect(void) {
    struct fixture f; open_core(&f, 0);
    uint64_t stream = 99; uint16_t local_port;
    CHECK(dp_tcp_connect(f.core, peer_ip, 9000, &stream) == DP_WOULD_BLOCK && stream == 0);
    CHECK(dp_core_set_link_ready(f.core, 1) == DP_OK);
    CHECK(dp_tcp_connect(f.core, peer_ip, 9000, &stream) == DP_WOULD_BLOCK && stream == 0);
    dp_stats stats; CHECK(dp_core_get_stats(f.core, &stats) == DP_OK && stats.heap_used == 0 && stats.tcp_active == 0);
    close_core(&f); ready_core(&f);
    uint32_t sequence = connect_begin(&f, &stream, &local_port);
    dp_tcp_info info;
    memset(&info, 99, sizeof(info));
    CHECK(dp_tcp_connect_status(f.core, stream, &info) == DP_WOULD_BLOCK && info.local_port == 0 && info.remote_port == 0);
    uint8_t bytes[8]; size_t copied;
    CHECK(dp_tcp_write(f.core, stream, (const uint8_t *)"early", 5, &copied) == DP_WOULD_BLOCK && copied == 0);
    CHECK(dp_tcp_shutdown_output(f.core, stream) == DP_WOULD_BLOCK);
    f.count = 0;
    send_tcp_pair(&f, 9000, local_port, 18, 7000, sequence + 1, "", 0);
    CHECK(dp_tcp_connect_status(f.core, stream, &info) == DP_OK);
    CHECK(info.local_port == local_port && info.remote_port == 9000);
    CHECK(!memcmp(info.local, local_ip, 16) && !memcmp(info.remote, peer_ip, 16));
    f.count = 0;
    CHECK(dp_tcp_write(f.core, stream, (const uint8_t *)"hi", 2, &copied) == DP_OK && copied == 2);
    uint8_t seen[2] = {0};
    collect_echo(&f, sequence, (const uint8_t *)"hi", seen, 2);
    CHECK(seen[0] && seen[1]);
    send_tcp_pair(&f, 9000, local_port, 25, 7001, sequence + 3, "reply", 5);
    CHECK(dp_tcp_connect_status(f.core, stream, &info) == DP_OK); /* Completed before peer EOF. */
    CHECK(dp_tcp_read(f.core, stream, bytes, sizeof(bytes), &copied) == DP_OK && copied == 5 && !memcmp(bytes, "reply", 5));
    CHECK(dp_tcp_read(f.core, stream, bytes, 1, &copied) == DP_PEER_CLOSED);
    CHECK(dp_tcp_close(f.core, stream) == DP_OK);
    CHECK(dp_core_get_stats(f.core, &stats) == DP_OK && stats.heap_used == 0 && stats.pbuf_used == 0);
    close_core(&f);
}
static void test_tcp_connect_cancel(void) {
    struct fixture f; ready_core(&f);
    uint64_t stream; uint16_t port; dp_tcp_info info;
    uint32_t sequence = connect_begin(&f, &stream, &port);
    send_tcp_pair(&f, 9000, port, 20, 0, sequence + 1, "", 0);
    CHECK(dp_tcp_connect_status(f.core, stream, &info) == DP_PEER_RESET);
    CHECK(dp_tcp_close(f.core, stream) == DP_OK);
    sequence = connect_begin(&f, &stream, &port);
    uint64_t stale = stream;
    uint16_t cancelled_port = port;
    uint32_t cancelled_sequence = sequence;
    CHECK(dp_tcp_close(f.core, stream) == DP_OK);
    send_tcp_pair(&f, 9000, port, 18, 7000, sequence + 1, "", 0);
    CHECK(dp_tcp_connect_status(f.core, stale, &info) == DP_INVALID_ARGUMENT);
    sequence = connect_begin(&f, &stream, &port);
    CHECK(stream != stale && dp_tcp_connect_status(f.core, stream, &info) == DP_WOULD_BLOCK);
    send_tcp_pair(&f, 9000, cancelled_port, 18, 7000, cancelled_sequence + 1, "", 0);
    CHECK(dp_tcp_connect_status(f.core, stream, &info) == DP_WOULD_BLOCK);
    send_tcp_pair(&f, 9000, port, 18, 7000, sequence + 1, "", 0);
    CHECK(dp_tcp_connect_status(f.core, stream, &info) == DP_OK);
    CHECK(dp_tcp_close(f.core, stream) == DP_OK);
    connect_begin(&f, &stale, &port);
    close_core(&f); /* Pending SYN belongs to the context when never explicitly cancelled. */
    ready_core(&f);
    connect_begin(&f, &stream, &port);
    CHECK(dp_tcp_connect_status(f.core, stale, &info) == DP_INVALID_ARGUMENT);
    CHECK(dp_tcp_close(f.core, stream) == DP_OK);
    close_core(&f);
}
static void test_tcp_connect_limits(void) {
    struct fixture f; ready_core(&f);
    uint64_t streams[32], extra = 99; uint16_t port; dp_tcp_info info;
    uint8_t invalid[16] = {0};
    CHECK(dp_tcp_connect(f.core, invalid, 9000, &extra) == DP_INVALID_ARGUMENT && extra == 0);
    invalid[0] = 255;
    CHECK(dp_tcp_connect(f.core, invalid, 9000, &extra) == DP_INVALID_ARGUMENT && extra == 0);
    CHECK(dp_tcp_connect(f.core, peer_ip, 0, &extra) == DP_INVALID_ARGUMENT && extra == 0);
    struct segment_pressure pressure = {0};
    CHECK(dp_core_execute(f.core, pressure_segments, &pressure) == DP_OK && pressure.count > 0);
    CHECK(dp_tcp_connect(f.core, peer_ip, 9000, &extra) == DP_RESOURCE_LIMIT && extra == 0);
    dp_stats stats;
    CHECK(dp_core_get_stats(f.core, &stats) == DP_OK && stats.tcp_active == 0 && stats.heap_used == 0 && stats.pbuf_used == 0);
    pressure.release = 1;
    CHECK(dp_core_execute(f.core, pressure_segments, &pressure) == DP_OK);
    for (unsigned i = 0; i < 32; ++i) connect_begin(&f, &streams[i], &port);
    CHECK(dp_tcp_connect(f.core, peer_ip, 9000, &extra) == DP_RESOURCE_LIMIT && extra == 0);
    for (unsigned i = 0; i < 32; ++i) CHECK(dp_tcp_connect_status(f.core, streams[i], &info) == DP_WOULD_BLOCK);
    f.count = 0;
    for (unsigned i = 0; i < 32; ++i) CHECK(dp_tcp_close(f.core, streams[i]) == DP_OK);
    f.count = 0;
    CHECK(dp_core_get_stats(f.core, &stats) == DP_OK && stats.tcp_active == 0 && stats.heap_used == 0 && stats.pbuf_used == 0);
    f.ready = -1;
    CHECK(dp_tcp_connect(f.core, peer_ip, 9000, &extra) == DP_LINK_IO && extra == 0);
    CHECK(dp_core_get_stats(f.core, &stats) == DP_OK && stats.tcp_active == 0 && stats.heap_used == 0 && stats.pbuf_used == 0);
    close_core(&f);
}
static void test_tcp_connect_retry(void) {
    struct fixture f; ready_core(&f);
    uint64_t stream; dp_tcp_info info;
    f.ready = 0;
    CHECK(dp_tcp_connect(f.core, peer_ip, 9000, &stream) == DP_OK && stream);
    CHECK(dp_tcp_connect_status(f.core, stream, &info) == DP_WOULD_BLOCK);
    f.count = 0; f.ready = 1;
    CHECK(dp_core_set_link_ready(f.core, 1) == DP_OK);
    advance(&f, 5000);
    struct frame *syn = find(&f, 6, -1, 0);
    CHECK(syn && (syn->bytes[67] & 0x12) == 2);
    uint16_t port = u16(syn->bytes + 54);
    uint32_t sequence = u32(syn->bytes + 58);
    for (size_t i = 0; i < f.count; ++i) if (f.frames[i].bytes[20] == 6) {
        CHECK(u16(f.frames[i].bytes + 54) == port && u32(f.frames[i].bytes + 58) == sequence);
    }
    send_tcp_pair(&f, 9000, port, 18, 7000, sequence + 1, "", 0);
    CHECK(dp_tcp_connect_status(f.core, stream, &info) == DP_OK);
    CHECK(dp_tcp_close(f.core, stream) == DP_OK);
    connect_begin(&f, &stream, &port);
    dp_result state = DP_WOULD_BLOCK;
    for (unsigned i = 0; i < 120 && state == DP_WOULD_BLOCK; ++i) {
        f.count = 0;
        advance(&f, 2000);
        state = dp_tcp_connect_status(f.core, stream, &info);
    }
    CHECK(state == DP_PEER_RESET); /* Protocol retry exhaustion, not a Java operation deadline. */
    CHECK(dp_tcp_close(f.core, stream) == DP_OK);
    f.count = 0; advance(&f, 4000);
    dp_stats stats;
    CHECK(dp_core_get_stats(f.core, &stats) == DP_OK && stats.tcp_active == 0 && stats.heap_used == 0 && stats.pbuf_used == 0);
    close_core(&f);
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
static void test_udp_endpoint_fragments_case(size_t payload_length, int ordered) {
    struct fixture f;
    ready_core(&f);
    uint64_t handle;
    uint16_t port;
    CHECK(dp_udp_bind(f.core, 0, &handle, &port) == DP_OK);
    uint8_t datagram[65535] = {0}, received[65527], reply[65535] = {0};
    const size_t datagram_length = payload_length + 8;
    w16(datagram, 50000); w16(datagram + 2, port); w16(datagram + 4, (uint16_t)datagram_length);
    for (size_t i = 8; i < datagram_length; ++i) datagram[i] = (uint8_t)(i ^ (i >> 8));
    uint8_t pseudo[40] = {0};
    memcpy(pseudo + 8, peer_ip, 16); memcpy(pseudo + 24, local_ip, 16);
    w16(datagram + 6, checksum(pseudo, datagram, datagram_length, 17));
    const size_t tail = ((datagram_length - 1) / 1200) * 1200;
    if (!ordered) fragment(&f, datagram, tail, datagram_length - tail, 0, 345);
    for (size_t offset = 0; offset < (ordered ? datagram_length : tail); offset += 1200) {
        size_t length = datagram_length - offset < 1200 ? datagram_length - offset : 1200;
        fragment(&f, datagram, offset, length, offset + length < datagram_length, 345);
    }
    dp_udp_datagram info;
    CHECK(dp_udp_receive(f.core, handle, received, sizeof(received), &info) == DP_OK);
    CHECK(info.original_length == payload_length && info.copied_length == payload_length);
    CHECK(!memcmp(received, datagram + 8, payload_length));
    CHECK(info.source_port == 50000 && !memcmp(info.source, peer_ip, 16));
    f.count = 0;
    CHECK(dp_udp_send(f.core, handle, peer_ip, 50000, received, payload_length) == DP_OK);
    size_t total = 0, pieces = 0;
    for (size_t i = 0; i < f.count; ++i) {
        const uint8_t *ip = f.frames[i].bytes + 14;
        if (ip[6] != 44) continue;
        const uint8_t *frag = ip + 40;
        size_t offset = u16(frag + 2) & 0xfff8, length = u16(ip + 4) - 8;
        CHECK(frag[0] == 17 && offset + length <= datagram_length);
        memcpy(reply + offset, frag + 8, length);
        if (!(u16(frag + 2) & 1)) total = offset + length;
        pieces++;
    }
    CHECK(pieces > 1 && total == datagram_length);
    CHECK(u16(reply) == port && u16(reply + 2) == 50000);
    CHECK(!memcmp(reply + 8, received, payload_length));
    memcpy(pseudo + 8, local_ip, 16); memcpy(pseudo + 24, peer_ip, 16);
    CHECK(checksum(pseudo, reply, datagram_length, 17) == 0);
    close_core(&f);

    if (payload_length != 65527 || ordered) return;

    const size_t failures[] = {0, 1, 45};
    for (size_t attempt = 0; attempt < sizeof(failures) / sizeof(failures[0]); ++attempt) {
        ready_core(&f);
        CHECK(dp_udp_bind(f.core, 0, &handle, &port) == DP_OK);
        f.fail_after = failures[attempt];
        if (!f.fail_after) f.ready = -1;
        CHECK(dp_udp_send(f.core, handle, peer_ip, 50000, received, sizeof(received)) == DP_LINK_IO);
        CHECK(f.count == failures[attempt]);
        CHECK(dp_udp_close(f.core, handle) == DP_LINK_IO);
        dp_stats failure_stats;
        CHECK(dp_core_get_stats(f.core, &failure_stats) == DP_OK);
        CHECK(failure_stats.failure == DP_LINK_IO && failure_stats.udp_pcbs == 0 && failure_stats.pbuf_used == 0);
        close_core(&f);
    }
}
static void test_udp_endpoint_fragments(void) {
    const size_t sizes[] = {32768, 65487, 65488, 65527};
    for (size_t i = 0; i < sizeof(sizes) / sizeof(sizes[0]); ++i) {
        test_udp_endpoint_fragments_case(sizes[i], 0);
        test_udp_endpoint_fragments_case(sizes[i], 1);
    }
}
static void extension_fragment(struct fixture *f, const uint8_t *datagram, size_t offset,
                               size_t length, int more, unsigned extensions, uint8_t first,
                               uint8_t fragment_next) {
    uint8_t frame[1514];
    CHECK(extensions <= 2 && length <= 1200);
    ethernet(frame, local_mac);
    uint8_t *p = ipv6(frame, local_ip, first, (uint16_t)(extensions * 8 + 8 + length));
    for (unsigned i = 0; i < extensions; ++i) p[i * 8] = i + 1 < extensions ? 60 : 44;
    p += extensions * 8;
    p[0] = fragment_next; w16(p + 2, (uint16_t)(offset | (more ? 1 : 0))); w32(p + 4, 731);
    memcpy(p + 8, datagram + offset, length);
    CHECK(dp_core_input(f->core, frame, 62 + extensions * 8 + length) == DP_OK);
}
static void test_udp_extension_fragments(void) {
    for (unsigned extensions = 1; extensions <= 2; ++extensions) {
        const size_t sizes[] = {64, 65487 - extensions * 8, 65488 - extensions * 8,
                                65527 - extensions * 8, 65528 - extensions * 8};
        for (unsigned first_kind = 0; first_kind < 2; ++first_kind) {
            for (unsigned ordered = 0; ordered < 2; ++ordered) {
                for (size_t vector = 0; vector < sizeof(sizes) / sizeof(sizes[0]); ++vector) {
                    struct fixture f;
                    ready_core(&f);
                    uint64_t handle; uint16_t port;
                    CHECK(dp_udp_bind(f.core, 0, &handle, &port) == DP_OK);
                    uint8_t datagram[65535] = {0}, received[65527], pseudo[40] = {0};
                    const size_t length = sizes[vector] + 8;
                    w16(datagram, 50000); w16(datagram + 2, port); w16(datagram + 4, (uint16_t)length);
                    for (size_t n = 8; n < length; ++n) datagram[n] = (uint8_t)(n ^ (n >> 8));
                    memcpy(pseudo + 8, peer_ip, 16); memcpy(pseudo + 24, local_ip, 16);
                    w16(datagram + 6, checksum(pseudo, datagram, length, 17));
                    const size_t pieces = (length + 1199) / 1200;
                    for (size_t n = 0; n < pieces; ++n) {
                        size_t at = (ordered ? n : pieces - n - 1) * 1200;
                        size_t count = length - at < 1200 ? length - at : 1200;
                        extension_fragment(&f, datagram, at, count, at + count < length,
                                           extensions, first_kind ? 60 : 0, 17);
                    }
                    dp_udp_datagram info;
                    int valid = sizes[vector] + extensions * 8 <= 65527;
                    dp_result status = dp_udp_receive(f.core, handle, received, sizeof(received), &info);
                    if (status != (valid ? DP_OK : DP_WOULD_BLOCK))
                        fprintf(stderr, "extensions=%u first=%u ordered=%u payload=%lu status=%d\n",
                                extensions, first_kind ? 60u : 0u, ordered, (unsigned long)sizes[vector], status);
                    CHECK(status == (valid ? DP_OK : DP_WOULD_BLOCK));
                    if (valid) {
                        CHECK(info.original_length == sizes[vector] && info.copied_length == sizes[vector]);
                        CHECK(info.source_port == 50000 && !memcmp(info.source, peer_ip, 16));
                        CHECK(!memcmp(received, datagram + 8, sizes[vector]));
                    }
                    /* Accepted or dropped, the context must stay usable and release all fragments. */
                    w16(datagram + 4, 9); w16(datagram + 6, 0); datagram[8] = 42;
                    w16(datagram + 6, checksum(pseudo, datagram, 9, 17));
                    extension_fragment(&f, datagram, 0, 9, 0, extensions, first_kind ? 60 : 0, 17);
                    CHECK(dp_udp_receive(f.core, handle, received, sizeof(received), &info) == DP_OK);
                    CHECK(info.original_length == 1 && received[0] == 42);
                    dp_stats stats;
                    CHECK(dp_core_get_stats(f.core, &stats) == DP_OK);
                    CHECK(stats.failure == DP_OK && stats.pbuf_used == 0);
                    CHECK(dp_udp_close(f.core, handle) == DP_OK);
                    close_core(&f);
                }
            }
        }
    }
    for (unsigned atomic = 0; atomic < 2; ++atomic) {
        for (unsigned ordered = 0; ordered < 2; ++ordered) {
            struct fixture f;
            ready_core(&f);
            uint64_t handle; uint16_t port;
            CHECK(dp_udp_bind(f.core, 0, &handle, &port) == DP_OK);
            uint8_t datagram[17] = {17}, pseudo[40] = {0}, target[8];
            w16(datagram + 8, 50000); w16(datagram + 10, port); w16(datagram + 12, 9);
            datagram[16] = 42;
            memcpy(pseudo + 8, peer_ip, 16); memcpy(pseudo + 24, local_ip, 16);
            w16(datagram + 14, checksum(pseudo, datagram + 8, 9, 17));
            if (atomic) extension_fragment(&f, datagram, 0, 17, 0, 0, 44, 0);
            else {
                for (unsigned n = 0; n < 2; ++n) {
                    unsigned part = ordered ? n : 1 - n;
                    extension_fragment(&f, datagram, part * 16, part ? 1 : 16, !part, 0, 44, 0);
                }
            }
            dp_udp_datagram info;
            CHECK(dp_udp_receive(f.core, handle, target, sizeof(target), &info) == DP_WOULD_BLOCK);
            dp_stats stats;
            CHECK(dp_core_get_stats(f.core, &stats) == DP_OK);
            CHECK(stats.failure == DP_OK && stats.pbuf_used == 0);
            CHECK(dp_udp_close(f.core, handle) == DP_OK);
            close_core(&f);
        }
    }
}
#ifdef DP_WRAP_ALLOCATION
static void check_complete_udp_output(struct fixture *f, uint16_t port,
                                      const uint8_t *payload, size_t length) {
    uint8_t reply[65535] = {0}, seen[65535] = {0}, pseudo[40] = {0};
    size_t copied = 0;
    int last = 0;
    uint32_t id = 0;
    for (size_t n = 0; n < f->count; ++n) {
        const uint8_t *ip = f->frames[n].bytes + 14;
        CHECK(!memcmp(ip + 8, local_ip, 16) && !memcmp(ip + 24, peer_ip, 16));
        CHECK(u16(ip + 4) + 54u == f->frames[n].length);
        if (ip[6] == 17) {
            CHECK(f->count == 1 && u16(ip + 4) == length + 8);
            memcpy(reply, ip + 40, length + 8); copied = length + 8; last = 1;
            break;
        }
        CHECK(ip[6] == 44);
        const uint8_t *fragment = ip + 40;
        size_t at = u16(fragment + 2) & 0xfff8, count = u16(ip + 4) - 8;
        CHECK(fragment[0] == 17 && at + count <= length + 8);
        if (n == 0) id = u32(fragment + 4);
        else CHECK(id == u32(fragment + 4));
        if (u16(fragment + 2) & 1) CHECK(count % 8 == 0);
        else { CHECK(!last && at + count == length + 8); last = 1; }
        for (size_t byte = at; byte < at + count; ++byte) { CHECK(!seen[byte]); seen[byte] = 1; }
        memcpy(reply + at, fragment + 8, count); copied += count;
    }
    CHECK(last && copied == length + 8);
    CHECK(u16(reply) == port && u16(reply + 2) == 50000 && u16(reply + 4) == length + 8);
    CHECK(!memcmp(reply + 8, payload, length));
    memcpy(pseudo + 8, local_ip, 16); memcpy(pseudo + 24, peer_ip, 16);
    CHECK(checksum(pseudo, reply, length + 8, 17) == 0);
}
static void test_udp_allocation_failures(void) {
    const size_t lengths[] = {1200, 65487, 65488, 65527};
    uint8_t payload[65527];
    for (size_t byte = 0; byte < sizeof(payload); ++byte) payload[byte] = (uint8_t)(byte ^ (byte >> 8));
    for (size_t vector = 0; vector < sizeof(lengths) / sizeof(lengths[0]); ++vector) {
        size_t allocations = 0;
        for (size_t fail_at = 0; fail_at <= allocations; ++fail_at) {
            struct fixture f;
            ready_core(&f);
            uint64_t handle; uint16_t port;
            CHECK(dp_udp_bind(f.core, 0, &handle, &port) == DP_OK);
            dp_stats before, after;
            CHECK(dp_core_get_stats(f.core, &before) == DP_OK);
            uint32_t pool_usage[MEMP_MAX];
            for (unsigned pool = 0; pool < MEMP_MAX; ++pool) pool_usage[pool] = lwip_stats.memp[pool]->used;
            allocation_fault.calls = 0; allocation_fault.fail_at = fail_at; allocation_fault.armed = 1;
            dp_result result = dp_udp_send(f.core, handle, peer_ip, 50000, payload, lengths[vector]);
            allocation_fault.armed = 0;
            if (!fail_at) {
                CHECK(result == DP_OK);
                allocations = allocation_fault.calls;
                CHECK(allocations > 0 && allocations <= 256);
                check_complete_udp_output(&f, port, payload, lengths[vector]);
            } else {
                if (result != DP_RESOURCE_LIMIT)
                    fprintf(stderr, "UDP allocation failure index=%lu calls=%lu length=%lu status=%d\n",
                            (unsigned long)fail_at, (unsigned long)allocation_fault.calls,
                            (unsigned long)lengths[vector], result);
                CHECK(result == DP_RESOURCE_LIMIT && allocation_fault.calls == fail_at);
                /* No final fragment: a failed call must not publish a whole datagram. */
                for (size_t n = 0; n < f.count; ++n) {
                    const uint8_t *ip = f.frames[n].bytes + 14;
                    CHECK(ip[6] == 44 && (u16(ip + 42) & 1));
                }
            }
            CHECK(dp_core_get_stats(f.core, &after) == DP_OK);
            CHECK(after.failure == DP_OK && after.heap_used == before.heap_used && after.pbuf_used == before.pbuf_used);
            for (unsigned pool = 0; pool < MEMP_MAX; ++pool) CHECK(lwip_stats.memp[pool]->used == pool_usage[pool]);
            f.count = 0;
            CHECK(dp_udp_send(f.core, handle, peer_ip, 50000, payload, lengths[vector]) == DP_OK);
            check_complete_udp_output(&f, port, payload, lengths[vector]);
            CHECK(dp_core_get_stats(f.core, &after) == DP_OK);
            CHECK(after.heap_used == before.heap_used && after.pbuf_used == before.pbuf_used);
            for (unsigned pool = 0; pool < MEMP_MAX; ++pool) CHECK(lwip_stats.memp[pool]->used == pool_usage[pool]);
            CHECK(dp_udp_close(f.core, handle) == DP_OK);
            close_core(&f);
        }
        printf("UDP length=%lu traversed %lu allocation failure points\n",
               (unsigned long)lengths[vector], (unsigned long)allocations);
    }
}
static void test_udp_bind_allocation_failures(void) {
    const uint8_t payload[] = {1, 2, 3};
    for (unsigned shared_pool = 0; shared_pool < 2; ++shared_pool) {
        size_t allocations = 0;
        for (size_t fail_at = 0; fail_at <= allocations; ++fail_at) {
            struct fixture f;
            ready_core(&f);
            uint64_t seed = 0; uint16_t seed_port = 0;
            if (shared_pool) {
                CHECK(dp_udp_bind(f.core, 47001, &seed, &seed_port) == DP_OK);
                uint8_t frame[65];
                ethernet(frame, local_mac);
                uint8_t *udp = ipv6(frame, local_ip, 17, 11);
                w16(udp, 50000); w16(udp + 2, seed_port); w16(udp + 4, 11);
                memcpy(udp + 8, payload, sizeof(payload));
                w16(udp + 6, checksum(frame + 14, udp, 11, 17));
                CHECK(dp_core_input(f.core, frame, sizeof(frame)) == DP_OK);
            }
            dp_stats before, after;
            CHECK(dp_core_get_stats(f.core, &before) == DP_OK);
            uint32_t pool_usage[MEMP_MAX];
            for (unsigned pool = 0; pool < MEMP_MAX; ++pool) pool_usage[pool] = lwip_stats.memp[pool]->used;
            uint64_t handle = UINT64_MAX; uint16_t port = UINT16_MAX;
            allocation_fault.calloc_calls = 0;
            allocation_fault.calls = 0; allocation_fault.fail_at = fail_at; allocation_fault.armed = 1;
            dp_result result = dp_udp_bind(f.core, 47000, &handle, &port);
            allocation_fault.armed = 0;
            if (!fail_at) {
                CHECK(result == DP_OK && handle && port == 47000);
                /* Detect a toolchain that bypasses the libc wrapper rather than silently losing coverage. */
                CHECK(allocation_fault.calloc_calls == (shared_pool ? 1u : 2u));
                allocations = allocation_fault.calls;
                CHECK(allocations > 0 && allocations <= 16);
                CHECK(dp_udp_close(f.core, handle) == DP_OK);
            } else {
                CHECK(result == DP_RESOURCE_LIMIT && allocation_fault.calls == fail_at);
                CHECK(handle == 0 && port == 0);
            }
            CHECK(dp_core_get_stats(f.core, &after) == DP_OK);
            CHECK(after.failure == DP_OK && after.heap_used == before.heap_used && after.udp_pcbs == before.udp_pcbs);
            for (unsigned pool = 0; pool < MEMP_MAX; ++pool) CHECK(lwip_stats.memp[pool]->used == pool_usage[pool]);
            if (shared_pool) {
                uint8_t received[3]; dp_udp_datagram info;
                CHECK(dp_udp_receive(f.core, seed, received, sizeof(received), &info) == DP_OK);
                CHECK(info.original_length == sizeof(payload) && info.copied_length == sizeof(payload));
                CHECK(info.source_port == 50000 && !memcmp(info.source, peer_ip, 16));
                CHECK(!memcmp(received, payload, sizeof(payload)));
            }
            /* Same fixed port remains available after every failed allocation. */
            CHECK(dp_udp_bind(f.core, 47000, &handle, &port) == DP_OK && handle && port == 47000);
            f.count = 0;
            CHECK(dp_udp_send(f.core, handle, peer_ip, 50000, payload, sizeof(payload)) == DP_OK);
            check_complete_udp_output(&f, port, payload, sizeof(payload));
            CHECK(dp_udp_close(f.core, handle) == DP_OK);
            if (shared_pool) {
                f.count = 0;
                CHECK(dp_udp_send(f.core, seed, peer_ip, 50000, payload, sizeof(payload)) == DP_OK);
                check_complete_udp_output(&f, seed_port, payload, sizeof(payload));
            }
            CHECK(dp_core_get_stats(f.core, &after) == DP_OK);
            CHECK(after.heap_used == before.heap_used && after.udp_pcbs == before.udp_pcbs);
            for (unsigned pool = 0; pool < MEMP_MAX; ++pool) CHECK(lwip_stats.memp[pool]->used == pool_usage[pool]);
            if (shared_pool) CHECK(dp_udp_close(f.core, seed) == DP_OK);
            close_core(&f);
        }
        printf("UDP bind shared_pool=%u traversed %lu allocation failure points\n",
               shared_pool, (unsigned long)allocations);
    }
}
static dp_config allocation_core_config(struct fixture *f) {
    memset(f, 0, sizeof(*f)); f->random = 1234567; f->ready = 1;
    dp_config config = {0};
    memcpy(config.mac, local_mac, 6); memcpy(config.ipv6, local_ip, 16);
    config.mtu = 1500; config.opaque = f;
    config.clock_ms = clock_ms; config.random32 = random32; config.output = output;
    return config;
}
static void test_core_allocation_failures(void) {
    struct fixture f;
    dp_config config = allocation_core_config(&f);
    f.core = (dp_core *)(uintptr_t)UINTPTR_MAX;
    /* Fail the first process's context calloc before global lwIP initialization. */
    allocation_fault.calls = 0; allocation_fault.calloc_calls = 0;
    allocation_fault.fail_at = 1; allocation_fault.armed = 1;
    dp_result result = dp_core_create(&config, &f.core);
    allocation_fault.armed = 0;
    CHECK(result == DP_RESOURCE_LIMIT && f.core == NULL && allocation_fault.calloc_calls == 1);
    ready_core(&f); close_core(&f); /* The global claim must have been released. */

    const uint32_t heap_before = lwip_stats.mem.used;
    uint32_t pools_before[MEMP_MAX];
    for (unsigned pool = 0; pool < MEMP_MAX; ++pool) pools_before[pool] = lwip_stats.memp[pool]->used;
    size_t allocations = 0;
    for (size_t fail_at = 0; fail_at <= allocations; ++fail_at) {
        config = allocation_core_config(&f);
        f.core = (dp_core *)(uintptr_t)UINTPTR_MAX;
        allocation_fault.calls = 0; allocation_fault.calloc_calls = 0;
        allocation_fault.fail_at = fail_at; allocation_fault.armed = 1;
        result = dp_core_create(&config, &f.core);
        allocation_fault.armed = 0;
        if (!fail_at) {
            CHECK(result == DP_OK && f.core && allocation_fault.calloc_calls == 1);
            allocations = allocation_fault.calls;
            CHECK(allocations > 0 && allocations <= 32);
            close_core(&f);
        } else {
            int published = f.core != NULL;
            if (result == DP_OK) close_core(&f);
            if (result != DP_RESOURCE_LIMIT || published)
                fprintf(stderr, "Core create failure index=%lu calls=%lu status=%d published=%d\n",
                        (unsigned long)fail_at, (unsigned long)allocation_fault.calls, result, published);
            CHECK(result == DP_RESOURCE_LIMIT && !published);
        }
        CHECK(netif_list == NULL && netif_default == NULL && lwip_stats.mem.used == heap_before);
        for (unsigned pool = 0; pool < MEMP_MAX; ++pool) CHECK(lwip_stats.memp[pool]->used == pools_before[pool]);
        ready_core(&f);
        uint64_t handle; uint16_t port;
        CHECK(dp_udp_bind(f.core, 47000, &handle, &port) == DP_OK);
        const uint8_t payload[] = {1, 2, 3};
        CHECK(dp_udp_send(f.core, handle, peer_ip, 50000, payload, sizeof(payload)) == DP_OK);
        check_complete_udp_output(&f, port, payload, sizeof(payload));
        CHECK(dp_udp_close(f.core, handle) == DP_OK);
        close_core(&f);
        CHECK(lwip_stats.mem.used == heap_before);
        for (unsigned pool = 0; pool < MEMP_MAX; ++pool) CHECK(lwip_stats.memp[pool]->used == pools_before[pool]);
    }
    printf("Core create traversed 1 cold calloc and %lu warm allocation failure points\n",
           (unsigned long)allocations);
}
static void exercise_and_close_listener(struct fixture *f, uint64_t listener) {
    uint32_t server = establish(f, 50000);
    uint64_t stream = accept_stream(f, listener);
    send_tcp(f, 24, 1001, server + 1, "abc");
    uint8_t received[3]; size_t copied = 0;
    CHECK(dp_tcp_read(f->core, stream, received, sizeof(received), &copied) == DP_OK);
    CHECK(copied == 3 && !memcmp(received, "abc", 3));
    CHECK(dp_tcp_close(f->core, stream) == DP_OK);
    CHECK(dp_tcp_listener_close(f->core, listener) == DP_OK);
    advance(f, 500); /* Let the on-demand TCP timer retire after abort. */
}
static void test_tcp_listen_allocation_failures(void) {
    for (int profile = 0; profile < 4; ++profile) {
        int reuse = profile % 2, persistent = profile / 2;
        size_t allocations = 0;
        unsigned recovered = 0, rejected = 0;
        for (size_t fail_at = 0; fail_at <= allocations; ++fail_at) {
            struct fixture f;
            ready_core(&f);
            dp_stats before, after;
            CHECK(dp_core_get_stats(f.core, &before) == DP_OK);
            uint32_t pools_before[MEMP_MAX];
            for (unsigned pool = 0; pool < MEMP_MAX; ++pool) pools_before[pool] = lwip_stats.memp[pool]->used;
            uint64_t listener = UINT64_MAX; uint16_t port = UINT16_MAX;
            allocation_fault.calls = 0; allocation_fault.calloc_calls = 0;
            allocation_fault.rejected = 0; allocation_fault.persistent = persistent;
            allocation_fault.fail_at = fail_at; allocation_fault.armed = 1;
            dp_result result = dp_tcp_listen_with_options(f.core, 7000, 2, reuse, &listener, &port);
            allocation_fault.armed = 0;
            if (!fail_at) {
                CHECK(result == DP_OK && listener && port == 7000 && allocation_fault.calloc_calls == 1);
                allocations = allocation_fault.calls;
                CHECK(allocations > 0 && allocations <= 16);
            } else {
                CHECK(allocation_fault.rejected > 0 && allocation_fault.calls >= fail_at);
                if (!persistent) CHECK(allocation_fault.rejected == 1);
            }
            if (result == DP_OK) {
                CHECK(!fail_at || !persistent);
                CHECK(listener && port == 7000);
                if (fail_at) recovered++;
                exercise_and_close_listener(&f, listener);
            } else {
                CHECK(fail_at && result == DP_RESOURCE_LIMIT);
                CHECK(listener == 0 && port == 0);
                rejected++;
            }
            CHECK(dp_core_get_stats(f.core, &after) == DP_OK);
            CHECK(after.failure == DP_OK && after.heap_used == before.heap_used &&
                  after.tcp_active == before.tcp_active && after.tcp_listeners == before.tcp_listeners);
            for (unsigned pool = 0; pool < MEMP_MAX; ++pool) CHECK(lwip_stats.memp[pool]->used == pools_before[pool]);
            CHECK(dp_tcp_listen_with_options(f.core, 7000, 2, reuse, &listener, &port) == DP_OK && port == 7000);
            exercise_and_close_listener(&f, listener);
            CHECK(dp_core_get_stats(f.core, &after) == DP_OK);
            CHECK(after.failure == DP_OK && after.heap_used == before.heap_used &&
                  after.tcp_active == before.tcp_active && after.tcp_listeners == before.tcp_listeners);
            for (unsigned pool = 0; pool < MEMP_MAX; ++pool) CHECK(lwip_stats.memp[pool]->used == pools_before[pool]);
            close_core(&f);
        }
        CHECK(recovered + rejected == allocations);
        if (persistent) CHECK(recovered == 0 && rejected == allocations);
        printf("TCP listen reuse=%d persistent=%d points=%lu recovered=%u resource_limit=%u\n",
               reuse, persistent, (unsigned long)allocations, recovered, rejected);
    }
}
static void test_tcp_accept_allocation_failures(void) {
    for (int persistent = 0; persistent < 2; ++persistent) {
        size_t allocations = 0;
        unsigned delivered = 0, input_failed = 0, unpublished = 0;
        for (size_t fail_at = 0; fail_at <= allocations; ++fail_at) {
            struct fixture f;
            ready_core(&f);
            dp_stats before, after;
            CHECK(dp_core_get_stats(f.core, &before) == DP_OK);
            uint32_t pools_before[MEMP_MAX];
            for (unsigned pool = 0; pool < MEMP_MAX; ++pool) pools_before[pool] = lwip_stats.memp[pool]->used;
            uint64_t listener; uint16_t port;
            CHECK(dp_tcp_listen(f.core, 7000, 2, &listener, &port) == DP_OK);
            send_tcp(&f, 2, 1000, 0, "");
            struct frame *syn = find(&f, 6, -1, 0);
            CHECK(syn && (syn->bytes[67] & 18) == 18);
            uint32_t server = u32(syn->bytes + 58);
            uint8_t frame[74];
            ethernet(frame, local_mac);
            uint8_t *tcp = ipv6(frame, local_ip, 6, 20);
            w16(tcp, 50000); w16(tcp + 2, 7000); w32(tcp + 4, 1001); w32(tcp + 8, server + 1);
            tcp[12] = 0x50; tcp[13] = 16; w16(tcp + 14, 32768);
            w16(tcp + 16, checksum(frame + 14, tcp, 20, 6));
            allocation_fault.calls = 0; allocation_fault.calloc_calls = 0;
            allocation_fault.rejected = 0; allocation_fault.persistent = persistent;
            allocation_fault.fail_at = fail_at; allocation_fault.armed = 1;
            dp_result input = dp_core_input(f.core, frame, sizeof(frame));
            allocation_fault.armed = 0;
            CHECK(input == DP_OK || input == DP_RESOURCE_LIMIT);
            if (!fail_at) {
                CHECK(input == DP_OK && allocation_fault.calloc_calls == 1);
                allocations = allocation_fault.calls;
                CHECK(allocations > 0 && allocations <= 16);
            } else {
                CHECK(allocation_fault.rejected > 0 && allocation_fault.calls >= fail_at);
                if (!persistent) CHECK(allocation_fault.rejected == 1);
            }
            uint64_t stream = UINT64_MAX; dp_tcp_info info;
            dp_result accepted = dp_tcp_accept(f.core, listener, &stream, &info);
            if (accepted == DP_OK) {
                CHECK(stream && info.local_port == 7000 && info.remote_port == 50000);
                CHECK(!fail_at);
                delivered++;
                send_tcp(&f, 24, 1001, server + 1, "abc");
                uint8_t bytes[3]; size_t copied;
                CHECK(dp_tcp_read(f.core, stream, bytes, sizeof(bytes), &copied) == DP_OK);
                CHECK(copied == 3 && !memcmp(bytes, "abc", 3));
                CHECK(dp_tcp_close(f.core, stream) == DP_OK);
            } else {
                CHECK(accepted == DP_WOULD_BLOCK && stream == 0 && fail_at);
                if (input == DP_RESOURCE_LIMIT) input_failed++;
                else unpublished++;
            }
            /* The original listener must still accept another client before it is closed. */
            server = establish(&f, 50001);
            CHECK(dp_tcp_accept(f.core, listener, &stream, &info) == DP_OK && stream);
            CHECK(info.local_port == 7000 && info.remote_port == 50001);
            send_tcp_port(&f, 50001, 24, 1001, server + 1, "xyz", 3);
            uint8_t recovered_bytes[3]; size_t recovered_count;
            CHECK(dp_tcp_read(f.core, stream, recovered_bytes, sizeof(recovered_bytes), &recovered_count) == DP_OK);
            CHECK(recovered_count == 3 && !memcmp(recovered_bytes, "xyz", 3));
            CHECK(dp_tcp_close(f.core, stream) == DP_OK);
            CHECK(dp_tcp_listener_close(f.core, listener) == DP_OK);
            advance(&f, 500);
            CHECK(dp_core_get_stats(f.core, &after) == DP_OK);
            CHECK(after.failure == DP_OK && after.heap_used == before.heap_used &&
                  after.tcp_active == before.tcp_active && after.tcp_listeners == before.tcp_listeners);
            for (unsigned pool = 0; pool < MEMP_MAX; ++pool) CHECK(lwip_stats.memp[pool]->used == pools_before[pool]);
            CHECK(dp_tcp_listen(f.core, 7000, 2, &listener, &port) == DP_OK);
            exercise_and_close_listener(&f, listener);
            close_core(&f);
        }
        printf("TCP accept persistent=%d points=%lu delivered=%u input_failed=%u unpublished=%u\n",
               persistent, (unsigned long)allocations, delivered, input_failed, unpublished);
    }
}
static void test_tcp_syn_allocation_failures(void) {
    struct fixture prime; ready_core(&prime); close_core(&prime);
    uint32_t global_heap = lwip_stats.mem.used, global_pools[MEMP_MAX];
    for (unsigned pool = 0; pool < MEMP_MAX; ++pool) global_pools[pool] = lwip_stats.memp[pool]->used;
    for (int persistent = 0; persistent < 2; ++persistent) {
        size_t allocations = 0;
        unsigned fatal_contexts = 0;
        for (size_t fail_at = 0; fail_at <= allocations; ++fail_at) {
            struct fixture f; ready_core(&f);
            dp_stats before, after;
            CHECK(dp_core_get_stats(f.core, &before) == DP_OK);
            uint32_t pools_before[MEMP_MAX];
            for (unsigned pool = 0; pool < MEMP_MAX; ++pool) pools_before[pool] = lwip_stats.memp[pool]->used;
            uint64_t listener; uint16_t port;
            CHECK(dp_tcp_listen(f.core, 7000, 2, &listener, &port) == DP_OK);
            uint8_t frame[74]; ethernet(frame, local_mac);
            uint8_t *tcp = ipv6(frame, local_ip, 6, 20);
            w16(tcp, 50000); w16(tcp + 2, 7000); w32(tcp + 4, 1000);
            tcp[12] = 0x50; tcp[13] = 2; w16(tcp + 14, 32768);
            w16(tcp + 16, checksum(frame + 14, tcp, 20, 6));
            fprintf(stderr, "SYN persistent=%d fail_at=%lu\n", persistent, (unsigned long)fail_at);
            allocation_fault.calls = 0; allocation_fault.calloc_calls = 0;
            allocation_fault.rejected = 0; allocation_fault.persistent = persistent;
            allocation_fault.fail_at = fail_at; allocation_fault.armed = 1;
            dp_result input = dp_core_input(f.core, frame, sizeof(frame));
            allocation_fault.armed = 0;
            CHECK(input == DP_OK || input == DP_RESOURCE_LIMIT);
            if (!fail_at) { allocations = allocation_fault.calls; CHECK(allocations > 0 && allocations <= 32); }
            else CHECK(allocation_fault.rejected > 0 && allocation_fault.calls >= fail_at);
            CHECK(dp_core_get_stats(f.core, &after) == DP_OK);
            if (after.failure == DP_OK) {
                uint64_t stream = UINT64_MAX; dp_tcp_info info;
                CHECK(dp_tcp_accept(f.core, listener, &stream, &info) == DP_WOULD_BLOCK && stream == 0);
                /* A different client must remain serviceable even if the first SYN was dropped. */
                uint32_t server = establish(&f, 50001);
                CHECK(dp_tcp_accept(f.core, listener, &stream, &info) == DP_OK && stream);
                send_tcp_port(&f, 50001, 24, 1001, server + 1, "abc", 3);
                uint8_t bytes[3]; size_t copied;
                CHECK(dp_tcp_read(f.core, stream, bytes, sizeof(bytes), &copied) == DP_OK);
                CHECK(copied == 3 && !memcmp(bytes, "abc", 3));
                CHECK(dp_tcp_close(f.core, stream) == DP_OK);
                CHECK(dp_tcp_listener_close(f.core, listener) == DP_OK);
                advance(&f, 500);
                CHECK(dp_core_get_stats(f.core, &after) == DP_OK);
                CHECK(after.heap_used == before.heap_used && after.tcp_active == before.tcp_active &&
                      after.tcp_listeners == before.tcp_listeners);
                for (unsigned pool = 0; pool < MEMP_MAX; ++pool) CHECK(lwip_stats.memp[pool]->used == pools_before[pool]);
            } else {
                CHECK(fail_at && input == DP_RESOURCE_LIMIT && after.failure == DP_RESOURCE_LIMIT);
                fatal_contexts++;
            }
            close_core(&f);
            if (after.failure == DP_RESOURCE_LIMIT) {
                ready_core(&f);
                CHECK(dp_tcp_listen(f.core, 7000, 2, &listener, &port) == DP_OK);
                exercise_and_close_listener(&f, listener);
                close_core(&f);
            }
            CHECK(netif_list == NULL && netif_default == NULL && lwip_stats.mem.used == global_heap);
            for (unsigned pool = 0; pool < MEMP_MAX; ++pool) CHECK(lwip_stats.memp[pool]->used == global_pools[pool]);
        }
        if (!persistent) CHECK(fatal_contexts == 0);
        printf("TCP SYN persistent=%d traversed %lu allocation points, fatal_contexts=%u\n",
               persistent, (unsigned long)allocations, fatal_contexts);
    }
}
static int replay_tcp_timer(void *unused) {
    (void)unused;
    struct sys_timeo *timer = allocation_fault.last_timeout;
    CHECK(timer && timer->h && timer->arg == NULL);
    sys_timeout_handler handler = timer->h;
    void *arg = timer->arg;
    sys_untimeout(handler, arg);
    allocation_fault.last_timeout = NULL;
    handler(arg);
    return 0;
}
static void test_tcp_timer_allocation_failures(void) {
    struct fixture f; ready_core(&f); close_core(&f);
    uint32_t heap = lwip_stats.mem.used, pools[MEMP_MAX];
    for (unsigned pool = 0; pool < MEMP_MAX; ++pool) pools[pool] = lwip_stats.memp[pool]->used;
    for (int persistent = 0; persistent < 2; ++persistent) {
        ready_core(&f);
        uint64_t listener; uint16_t port;
        CHECK(dp_tcp_listen(f.core, 7000, 2, &listener, &port) == DP_OK);
        allocation_fault.last_timeout = NULL;
        establish(&f, 50000);
        uint64_t stream = accept_stream(&f, listener);
        CHECK(allocation_fault.last_timeout && allocation_fault.last_timeout->arg == NULL);
        allocation_fault.calls = 0; allocation_fault.rejected = 0;
        allocation_fault.fail_at = 1; allocation_fault.persistent = persistent; allocation_fault.armed = 1;
        dp_result result = dp_core_execute(f.core, replay_tcp_timer, NULL);
        allocation_fault.armed = 0;
        CHECK(allocation_fault.rejected > 0);
        dp_stats stats;
        CHECK(dp_core_get_stats(f.core, &stats) == DP_OK);
        if (persistent) {
            CHECK(result == DP_RESOURCE_LIMIT && stats.failure == DP_RESOURCE_LIMIT);
            uint8_t bytes[3] = {9, 9, 9}; size_t copied = 99;
            CHECK(dp_tcp_read(f.core, stream, bytes, sizeof(bytes), &copied) == DP_RESOURCE_LIMIT);
            CHECK(copied == 0 && bytes[0] == 9 && bytes[1] == 9 && bytes[2] == 9);
        } else {
            CHECK(result == DP_OK && stats.failure == DP_OK);
            CHECK(allocation_fault.rejected == 1 && allocation_fault.last_timeout);
            CHECK(dp_tcp_close(f.core, stream) == DP_OK);
            CHECK(dp_tcp_listener_close(f.core, listener) == DP_OK);
            advance(&f, 500);
        }
        close_core(&f);
        ready_core(&f);
        CHECK(dp_tcp_listen(f.core, 7000, 2, &listener, &port) == DP_OK);
        exercise_and_close_listener(&f, listener);
        close_core(&f);
        CHECK(netif_list == NULL && netif_default == NULL && lwip_stats.mem.used == heap);
        for (unsigned pool = 0; pool < MEMP_MAX; ++pool) CHECK(lwip_stats.memp[pool]->used == pools[pool]);
        printf("TCP timer reschedule persistent=%d recovered and released\n", persistent);
    }
}
#endif
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
    else if (!strcmp(argv[1], "tcp_write"))
        test_tcp_write();
    else if (!strcmp(argv[1], "tcp_half_close"))
        test_tcp_half_close();
    else if (!strcmp(argv[1], "tcp_write_io"))
        test_tcp_write_io();
    else if (!strcmp(argv[1], "tcp_shutdown_pressure"))
        test_tcp_shutdown_pressure();
    else if (!strcmp(argv[1], "tcp_connect"))
        test_tcp_connect();
    else if (!strcmp(argv[1], "tcp_graceful_close"))
        test_tcp_graceful_close();
    else if (!strcmp(argv[1], "tcp_graceful_pressure"))
        test_tcp_graceful_pressure();
    else if (!strcmp(argv[1], "tcp_graceful_failure"))
        test_tcp_graceful_failure();
    else if (!strcmp(argv[1], "tcp_liveness"))
        test_tcp_liveness();
    else if (!strcmp(argv[1], "tcp_reuse"))
        test_tcp_reuse();
    else if (!strcmp(argv[1], "tcp_connect_cancel"))
        test_tcp_connect_cancel();
    else if (!strcmp(argv[1], "tcp_connect_limits"))
        test_tcp_connect_limits();
    else if (!strcmp(argv[1], "tcp_connect_retry"))
        test_tcp_connect_retry();
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
    else if (!strcmp(argv[1], "udp_extension_fragments"))
        test_udp_extension_fragments();
#ifdef DP_WRAP_ALLOCATION
    else if (!strcmp(argv[1], "udp_allocation_failures"))
        test_udp_allocation_failures();
    else if (!strcmp(argv[1], "udp_bind_allocation_failures"))
        test_udp_bind_allocation_failures();
    else if (!strcmp(argv[1], "core_allocation_failures"))
        test_core_allocation_failures();
    else if (!strcmp(argv[1], "tcp_listen_allocation_failures"))
        test_tcp_listen_allocation_failures();
    else if (!strcmp(argv[1], "tcp_accept_allocation_failures"))
        test_tcp_accept_allocation_failures();
    else if (!strcmp(argv[1], "tcp_syn_allocation_failures"))
        test_tcp_syn_allocation_failures();
    else if (!strcmp(argv[1], "tcp_timer_allocation_failures"))
        test_tcp_timer_allocation_failures();
#endif
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
