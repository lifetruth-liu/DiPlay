#include "diplay_prototype.h"
#include "prototype_internal.h"
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

#define TOKEN_TAG UINT64_C(0x4c57000000000000)
#define TOKEN_MASK UINT64_C(0xffffffff00000000)
struct frame {
    uint16_t length;
    uint8_t bytes[1514];
};
struct prototype {
    dp_core *core;
    uint64_t handle, now, rejected;
    uint32_t entropy[DP_PROTO_ENTROPY_BYTES / 4], entropy_at, entropy_count;
    struct frame frames[DP_PROTO_TX_FRAMES];
    uint8_t copy_scratch[DP_PROTO_COPY_BYTES];
    uint32_t head, count;
    int echo_started;
    dp_result failure;
};
_Static_assert(sizeof(struct prototype) <= 256u * 1024u, "prototype context budget exceeded");
static _Thread_local struct prototype *owned;
static atomic_uint_least32_t generation;
int dp_prototype_echo_start(void *unused);

static void erase(void *memory, size_t length) {
    volatile unsigned char *p = memory;
    while (length--)
        *p++ = 0;
}
static dp_result lookup(uint64_t handle, struct prototype **result, int failed_ok) {
    if ((handle & TOKEN_MASK) != TOKEN_TAG || !(uint32_t)handle)
        return DP_INVALID_ARGUMENT;
    if (!owned)
        return DP_WRONG_THREAD;
    if (owned->handle != handle)
        return DP_INVALID_ARGUMENT;
    *result = owned;
    return failed_ok ? DP_OK : owned->failure;
}
static uint64_t clock_ms(void *opaque) {
    return ((struct prototype *)opaque)->now;
}
static uint32_t random32(void *opaque) {
    struct prototype *p = opaque;
    if (p->entropy_at >= p->entropy_count) {
        p->failure = DP_INTERNAL;
        return 0;
    }
    uint32_t value = p->entropy[p->entropy_at];
    p->entropy[p->entropy_at++] = 0;
    return value;
}
static dp_tx_result output(void *opaque, const uint8_t *bytes, size_t length) {
    struct prototype *p = opaque;
    if (p->failure != DP_OK || !bytes || length < 14 || length > 1514)
        return DP_TX_FAILED;
    if (p->count == DP_PROTO_TX_FRAMES) {
        p->rejected++;
        return DP_TX_NOT_READY;
    }
    struct frame *f = &p->frames[(p->head + p->count) % DP_PROTO_TX_FRAMES];
    memcpy(f->bytes, bytes, length);
    f->length = (uint16_t)length;
    p->count++;
    return DP_TX_ACCEPTED;
}
static int entropy_valid(const uint8_t *bytes, size_t length) {
    return bytes && length && length <= DP_PROTO_ENTROPY_BYTES && length % 4 == 0;
}
static void refill(struct prototype *p, const uint8_t *bytes, size_t length) {
    erase(p->entropy, sizeof(p->entropy));
    for (size_t i = 0; i < length / 4; i++) {
        const uint8_t *b = bytes + i * 4;
        p->entropy[i] =
            (uint32_t)b[0] | (uint32_t)b[1] << 8 | (uint32_t)b[2] << 16 | (uint32_t)b[3] << 24;
    }
    p->entropy_at = 0;
    p->entropy_count = (uint32_t)(length / 4);
}
static dp_result finish(struct prototype *p, dp_result result) {
    if (p->failure != DP_OK)
        return p->failure;
    if (result == DP_INTERNAL || result == DP_ADDRESS_CONFLICT || result == DP_CLOCK_REVERSED ||
        result == DP_LINK_IO)
        p->failure = result;
    return result;
}
dp_result dp_proto_create(const uint8_t mac[6], const uint8_t ip[16], uint16_t mtu, uint64_t now,
                          const uint8_t *entropy, size_t length, uint64_t *handle) {
    if (!handle)
        return DP_INVALID_ARGUMENT;
    *handle = 0;
    if (!mac || !ip || !entropy_valid(entropy, length))
        return DP_INVALID_ARGUMENT;
    if (owned)
        return DP_RESOURCE_LIMIT;
    uint_least32_t previous = atomic_load(&generation);
    do {
        if (previous == UINT32_MAX)
            return DP_RESOURCE_LIMIT;
    } while (!atomic_compare_exchange_weak(&generation, &previous, previous + 1));
    struct prototype *p = calloc(1, sizeof(*p));
    if (!p)
        return DP_RESOURCE_LIMIT;
    p->handle = TOKEN_TAG | (previous + 1);
    p->now = now;
    refill(p, entropy, length);
    dp_config config = {0};
    memcpy(config.mac, mac, 6);
    memcpy(config.ipv6, ip, 16);
    config.mtu = mtu;
    config.opaque = p;
    config.clock_ms = clock_ms;
    config.random32 = random32;
    config.output = output;
    dp_result status = dp_core_create(&config, &p->core);
    if (status == DP_OK && p->failure != DP_OK)
        status = p->failure;
    if (status != DP_OK) {
        if (p->core)
            dp_core_destroy(&p->core);
        erase(p, sizeof(*p));
        free(p);
        return status;
    }
    owned = p;
    *handle = p->handle;
    return DP_OK;
}
dp_result dp_proto_supply(uint64_t h, uint64_t now, const uint8_t *entropy, size_t length) {
    struct prototype *p;
    dp_result s = lookup(h, &p, 0);
    if (s != DP_OK)
        return s;
    if (length && !entropy_valid(entropy, length))
        return DP_INVALID_ARGUMENT;
    if (now < p->now)
        return p->failure = DP_CLOCK_REVERSED;
    p->now = now;
    if (length)
        refill(p, entropy, length);
    return DP_OK;
}
dp_result dp_proto_prepare_echo(uint64_t h) {
    struct prototype *p;
    dp_result s = lookup(h, &p, 0);
    if (s != DP_OK)
        return s;
    if (p->echo_started)
        return DP_INVALID_ARGUMENT;
    p->echo_started = 1;
    return finish(p, dp_core_execute(p->core, dp_prototype_echo_start, NULL));
}
dp_result dp_proto_set_link_ready(uint64_t h, int ready) {
    struct prototype *p;
    dp_result s = lookup(h, &p, 0);
    if (s != DP_OK)
        return s;
    return finish(p, dp_core_set_link_ready(p->core, ready));
}
dp_result dp_proto_input(uint64_t h, const uint8_t *frame, size_t length) {
    struct prototype *p;
    dp_result s = lookup(h, &p, 0);
    if (s != DP_OK)
        return s;
    return finish(p, dp_core_input(p->core, frame, length));
}
dp_result dp_proto_poll(uint64_t h) {
    struct prototype *p;
    dp_result s = lookup(h, &p, 0);
    if (s != DP_OK)
        return s;
    return finish(p, dp_core_poll(p->core));
}
dp_result dp_proto_next_timeout(uint64_t h, uint32_t *timeout) {
    struct prototype *p;
    dp_result s = lookup(h, &p, 0);
    if (s != DP_OK)
        return s;
    return finish(p, dp_core_next_timeout(p->core, timeout));
}
dp_result dp_proto_take_output(uint64_t h, uint8_t *target, size_t capacity, size_t *length) {
    if (!length)
        return DP_INVALID_ARGUMENT;
    *length = 0;
    struct prototype *p;
    dp_result s = lookup(h, &p, 0);
    if (s != DP_OK)
        return s;
    if (!p->count)
        return DP_OK;
    struct frame *f = &p->frames[p->head];
    *length = f->length;
    if (capacity < f->length)
        return DP_RESOURCE_LIMIT;
    if (!target)
        return DP_INVALID_ARGUMENT;
    memcpy(target, f->bytes, f->length);
    p->head = (p->head + 1) % DP_PROTO_TX_FRAMES;
    p->count--;
    return DP_OK;
}
dp_result dp_proto_get_stats(uint64_t h, dp_proto_stats *stats) {
    if (!stats)
        return DP_INVALID_ARGUMENT;
    struct prototype *p;
    dp_result s = lookup(h, &p, 1);
    if (s != DP_OK)
        return s;
    s = dp_core_get_stats(p->core, &stats->core);
    if (s != DP_OK)
        return s;
    stats->tx_frames = p->count;
    stats->tx_rejected = p->rejected;
    stats->entropy_remaining = p->entropy_count - p->entropy_at;
    stats->configured_bytes = (uint32_t)sizeof(*p);
    stats->failure = p->failure != DP_OK ? p->failure : stats->core.failure;
    return DP_OK;
}
dp_result dp_proto_destroy(uint64_t *handle) {
    if (!handle)
        return DP_INVALID_ARGUMENT;
    if (!*handle)
        return DP_OK;
    struct prototype *p;
    dp_result s = lookup(*handle, &p, 1);
    if (s != DP_OK)
        return s;
    s = dp_core_destroy(&p->core);
    if (s != DP_OK)
        return s;
    owned = NULL;
    *handle = 0;
    erase(p, sizeof(*p));
    free(p);
    return DP_OK;
}
dp_result dp_proto_copy_buffer(uint64_t h, uint8_t **bytes) {
    if (!bytes) return DP_INVALID_ARGUMENT;
    *bytes = NULL;
    struct prototype *p;
    dp_result s = lookup(h, &p, 0);
    if (s == DP_OK) *bytes = p->copy_scratch;
    return s;
}
dp_result dp_proto_udp_bind(uint64_t h, uint16_t port, uint64_t *endpoint, uint16_t *actual) {
    if (endpoint) *endpoint = 0;
    if (actual) *actual = 0;
    struct prototype *p;
    dp_result s = lookup(h, &p, 0);
    return s == DP_OK ? finish(p, dp_udp_bind(p->core, port, endpoint, actual)) : s;
}
dp_result dp_proto_udp_send(uint64_t h, uint64_t endpoint, const uint8_t destination[16], uint16_t port,
                            const uint8_t *payload, size_t length) {
    struct prototype *p;
    dp_result s = lookup(h, &p, 0);
    return s == DP_OK ? finish(p, dp_udp_send(p->core, endpoint, destination, port, payload, length)) : s;
}
dp_result dp_proto_udp_receive(uint64_t h, uint64_t endpoint, uint8_t *target, size_t capacity, dp_udp_datagram *info) {
    struct prototype *p;
    dp_result s = lookup(h, &p, 0);
    return s == DP_OK ? finish(p, dp_udp_receive(p->core, endpoint, target, capacity, info)) : s;
}
dp_result dp_proto_udp_close(uint64_t h, uint64_t endpoint) {
    struct prototype *p;
    dp_result s = lookup(h, &p, 1);
    return s == DP_OK ? dp_udp_close(p->core, endpoint) : s;
}
int32_t dp_proto_entropy_remaining(uint64_t h) {
    struct prototype *p;
    dp_result s = lookup(h, &p, 0);
    return s == DP_OK ? (int32_t)(p->entropy_count - p->entropy_at) : s;
}
dp_result dp_proto_tcp_listen(uint64_t h, uint16_t port, unsigned backlog, uint64_t *endpoint, uint16_t *actual) {
    if (endpoint) *endpoint = 0;
    if (actual) *actual = 0;
    struct prototype *p; dp_result s = lookup(h, &p, 0);
    return s == DP_OK ? finish(p, dp_tcp_listen(p->core, port, backlog, endpoint, actual)) : s;
}
dp_result dp_proto_tcp_accept(uint64_t h, uint64_t listener, uint64_t *endpoint, dp_tcp_info *info) {
    if (endpoint) *endpoint = 0;
    struct prototype *p; dp_result s = lookup(h, &p, 0);
    return s == DP_OK ? finish(p, dp_tcp_accept(p->core, listener, endpoint, info)) : s;
}
dp_result dp_proto_tcp_connect(uint64_t h, const uint8_t address[16], uint16_t port, uint64_t *endpoint) {
    if (endpoint) *endpoint = 0;
    struct prototype *p; dp_result s = lookup(h, &p, 0);
    return s == DP_OK ? finish(p, dp_tcp_connect(p->core, address, port, endpoint)) : s;
}
dp_result dp_proto_tcp_connect_status(uint64_t h, uint64_t endpoint, dp_tcp_info *info) {
    struct prototype *p; dp_result s = lookup(h, &p, 0);
    return s == DP_OK ? finish(p, dp_tcp_connect_status(p->core, endpoint, info)) : s;
}
dp_result dp_proto_tcp_read(uint64_t h, uint64_t endpoint, uint8_t *bytes, size_t capacity, size_t *copied) {
    if (copied) *copied = 0;
    struct prototype *p; dp_result s = lookup(h, &p, 0);
    return s == DP_OK ? finish(p, dp_tcp_read(p->core, endpoint, bytes, capacity, copied)) : s;
}
dp_result dp_proto_tcp_write(uint64_t h, uint64_t endpoint, const uint8_t *bytes, size_t length, size_t *copied) {
    if (copied) *copied = 0;
    struct prototype *p; dp_result s = lookup(h, &p, 0);
    return s == DP_OK ? finish(p, dp_tcp_write(p->core, endpoint, bytes, length, copied)) : s;
}
dp_result dp_proto_tcp_shutdown_output(uint64_t h, uint64_t endpoint) {
    struct prototype *p; dp_result s = lookup(h, &p, 0);
    return s == DP_OK ? finish(p, dp_tcp_shutdown_output(p->core, endpoint)) : s;
}
dp_result dp_proto_tcp_close(uint64_t h, uint64_t endpoint) {
    struct prototype *p; dp_result s = lookup(h, &p, 1);
    if (s == DP_OK && p->failure != DP_OK) dp_core_set_link_ready(p->core, 0);
    return s == DP_OK ? dp_tcp_close(p->core, endpoint) : s;
}
dp_result dp_proto_tcp_listener_close(uint64_t h, uint64_t endpoint) {
    struct prototype *p; dp_result s = lookup(h, &p, 1);
    if (s == DP_OK && p->failure != DP_OK) dp_core_set_link_ready(p->core, 0);
    return s == DP_OK ? dp_tcp_listener_close(p->core, endpoint) : s;
}
#ifdef DP_PROTO_TESTING
dp_tx_result dp_proto_test_emit(uint64_t h, const uint8_t *bytes, size_t length) {
    struct prototype *p;
    return lookup(h, &p, 1) == DP_OK ? output(p, bytes, length) : DP_TX_FAILED;
}
uint32_t dp_proto_test_random(uint64_t h) {
    struct prototype *p;
    return lookup(h, &p, 1) == DP_OK ? random32(p) : 0;
}
void dp_proto_test_generation(uint32_t value) {
    atomic_store(&generation, value);
}
#endif
