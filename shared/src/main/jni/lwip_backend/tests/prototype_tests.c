#include "diplay_prototype.h"
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

static uint8_t entropy[DP_PROTO_ENTROPY_BYTES];
static uint64_t create(void) {
    const uint8_t mac[6] = {2, 0, 0, 0, 0, 1};
    const uint8_t ip[16] = {0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
    uint64_t handle = 0;
    CHECK(dp_proto_create(mac, ip, 1500, 100, entropy, sizeof(entropy), &handle) == DP_OK);
    CHECK(handle && handle < INT64_MAX);
    return handle;
}
static void udp_lifecycle(void) {
    uint64_t context = create(), endpoint = 0;
    uint16_t port = 0;
    CHECK(dp_proto_udp_bind(context, 0, &endpoint, &port) == DP_OK);
    CHECK(endpoint && port);
    CHECK(dp_proto_udp_set_queue_max_age(context, endpoint, 100) == DP_OK);
    uint64_t expired = UINT64_MAX;
    CHECK(dp_proto_udp_expired_count(context, endpoint, &expired) == DP_OK && expired == 0);
    CHECK(dp_proto_udp_expired_count(context, endpoint, NULL) == DP_INVALID_ARGUMENT);
    CHECK(dp_proto_udp_set_queue_max_age(context, context, 100) == DP_INVALID_ARGUMENT);
    dp_udp_datagram info;
    CHECK(dp_proto_udp_receive(context, endpoint, NULL, 0, &info) == DP_WOULD_BLOCK);
    CHECK(dp_proto_udp_close(context, context) == DP_INVALID_ARGUMENT);
    CHECK(dp_proto_udp_close(context, endpoint) == DP_OK);
    expired = UINT64_MAX;
    CHECK(dp_proto_udp_expired_count(context, endpoint, &expired) == DP_INVALID_ARGUMENT && expired == 0);
    CHECK(dp_proto_udp_set_queue_max_age(context, endpoint, 100) == DP_INVALID_ARGUMENT);
    uint64_t stale_endpoint = endpoint, stale_context = context;
    CHECK(dp_proto_udp_bind(context, port, &endpoint, &port) == DP_OK && endpoint != stale_endpoint);
    CHECK(dp_proto_udp_close(context, stale_endpoint) == DP_INVALID_ARGUMENT);
    CHECK(dp_proto_destroy(&context) == DP_OK);
    context = create();
    CHECK(dp_proto_udp_close(context, endpoint) == DP_INVALID_ARGUMENT);
    CHECK(dp_proto_udp_bind(stale_context, 0, &endpoint, &port) == DP_INVALID_ARGUMENT);
    CHECK(endpoint == 0 && port == 0);
    CHECK(dp_proto_udp_bind(context, 0, &endpoint, &port) == DP_OK);
    CHECK(dp_proto_supply(context, 99, NULL, 0) == DP_CLOCK_REVERSED);
    CHECK(dp_proto_udp_close(context, endpoint) == DP_OK); /* Cleanup after context failure. */
    CHECK(dp_proto_destroy(&context) == DP_OK);
}
static void tcp_lifecycle(void) {
    uint64_t context = create(), listener = 0, stream = 99;
    uint16_t port = 0;
    dp_tcp_info info;
    const uint8_t peer[16] = {0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2};
    CHECK(dp_proto_tcp_connect(context, peer, 9000, &stream) == DP_WOULD_BLOCK && stream == 0);
    CHECK(dp_proto_tcp_listen_with_options(context, 0, 2, 1, &listener, &port) == DP_OK && listener && port);
    CHECK(dp_proto_tcp_accept(context, listener, &stream, &info) == DP_WOULD_BLOCK && stream == 0);
    CHECK(dp_proto_tcp_close(context, listener) == DP_INVALID_ARGUMENT);
    CHECK(dp_proto_supply(context, 99, NULL, 0) == DP_CLOCK_REVERSED);
    CHECK(dp_proto_tcp_listener_close(context, listener) == DP_OK);
    CHECK(dp_proto_destroy(&context) == DP_OK);
    context = create();
    CHECK(dp_proto_tcp_listener_close(context, listener) == DP_INVALID_ARGUMENT);
    CHECK(dp_proto_tcp_listen(context, 0, 2, &listener, &port) == DP_OK);
    CHECK(dp_proto_destroy(&context) == DP_OK); /* Owned, unclosed listener must be reclaimed. */
}
static void lifecycle(void) {
    uint64_t previous = 0;
    for (int i = 0; i < 100; i++) {
        uint64_t h = create();
        CHECK(h != previous);
        if (previous)
            CHECK(dp_proto_poll(previous) == DP_INVALID_ARGUMENT);
        CHECK(dp_proto_prepare_echo(h) == DP_OK);
        CHECK(dp_proto_prepare_echo(h) == DP_INVALID_ARGUMENT);
        dp_proto_stats stats;
        CHECK(dp_proto_get_stats(h, &stats) == DP_OK);
        CHECK(stats.core.tcp_listeners == 1 && stats.core.udp_pcbs == 1);
        CHECK(stats.configured_bytes <= 256 * 1024 && stats.entropy_remaining > 0);
        previous = h;
        CHECK(dp_proto_destroy(&h) == DP_OK && h == 0);
        CHECK(dp_proto_destroy(&h) == DP_OK);
    }
    udp_lifecycle();
    tcp_lifecycle();
}
static void queue(void) {
    uint64_t h = create();
    uint8_t frame[1514] = {0}, copied[1514];
    for (int i = 0; i < DP_PROTO_TX_FRAMES; i++) {
        frame[13] = (uint8_t)i;
        CHECK(dp_proto_test_emit(h, frame, sizeof(frame)) == DP_TX_ACCEPTED);
    }
    memset(frame, 0xff, sizeof(frame));
    CHECK(dp_proto_test_emit(h, frame, sizeof(frame)) == DP_TX_NOT_READY);
    dp_proto_stats stats;
    CHECK(dp_proto_get_stats(h, &stats) == DP_OK);
    CHECK(stats.tx_frames == DP_PROTO_TX_FRAMES && stats.tx_rejected == 1);
    size_t length = 0;
    CHECK(dp_proto_take_output(h, copied, 1, &length) == DP_RESOURCE_LIMIT && length == 1514);
    for (int i = 0; i < DP_PROTO_TX_FRAMES; i++) {
        CHECK(dp_proto_take_output(h, copied, sizeof(copied), &length) == DP_OK);
        CHECK(length == 1514 && copied[13] == (uint8_t)i && copied[0] == 0);
    }
    CHECK(dp_proto_take_output(h, copied, sizeof(copied), &length) == DP_OK && length == 0);
    CHECK(dp_proto_destroy(&h) == DP_OK);
}
static void real_stack(void) {
    uint64_t h = create();
    CHECK(dp_proto_prepare_echo(h) == DP_OK);
    CHECK(dp_proto_set_link_ready(h, 1) == DP_OK);
    CHECK(dp_proto_supply(h, 2100, NULL, 0) == DP_OK);
    CHECK(dp_proto_poll(h) == DP_OK);
    uint8_t frame[1514];
    size_t length;
    CHECK(dp_proto_take_output(h, frame, sizeof(frame), &length) == DP_OK && length >= 14);
    CHECK(frame[12] == 0x86 && frame[13] == 0xdd);
    uint32_t timeout;
    CHECK(dp_proto_next_timeout(h, &timeout) == DP_OK);
    CHECK(dp_proto_supply(h, 2099, NULL, 0) == DP_CLOCK_REVERSED);
    CHECK(dp_proto_poll(h) == DP_CLOCK_REVERSED);
    CHECK(dp_proto_destroy(&h) == DP_OK);
}
static void depleted(void) {
    uint64_t h = create();
    dp_proto_stats stats;
    CHECK(dp_proto_get_stats(h, &stats) == DP_OK);
    for (uint32_t i = 0; i < stats.entropy_remaining; i++)
        dp_proto_test_random(h);
    CHECK(dp_proto_get_stats(h, &stats) == DP_OK && stats.entropy_remaining == 0);
    dp_proto_test_random(h);
    CHECK(dp_proto_poll(h) == DP_INTERNAL);
    uint8_t frame[14] = {0};
    CHECK(dp_proto_test_emit(h, frame, sizeof(frame)) == DP_TX_FAILED);
    CHECK(dp_proto_get_stats(h, &stats) == DP_OK && stats.failure == DP_INTERNAL);
    CHECK(dp_proto_destroy(&h) == DP_OK);
}
static uint64_t other_handle;
#ifdef _WIN32
static DWORD WINAPI other_thread(LPVOID unused) {
#else
static void *other_thread(void *unused) {
#endif
    (void)unused;
    uint64_t copy = other_handle;
    CHECK(dp_proto_poll(copy) == DP_WRONG_THREAD);
    CHECK(dp_proto_destroy(&copy) == DP_WRONG_THREAD && copy == other_handle);
    return 0;
}
static void threads(void) {
    other_handle = create();
#ifdef _WIN32
    HANDLE t = CreateThread(NULL, 0, other_thread, NULL, 0, NULL);
    CHECK(t && WaitForSingleObject(t, 2000) == WAIT_OBJECT_0);
    CloseHandle(t);
#else
    pthread_t t;
    CHECK(pthread_create(&t, NULL, other_thread, NULL) == 0);
    CHECK(pthread_join(t, NULL) == 0);
#endif
    CHECK(dp_proto_poll(other_handle) == DP_OK);
    CHECK(dp_proto_destroy(&other_handle) == DP_OK);
}
static void generation(void) {
    dp_proto_test_generation(UINT32_MAX - 1);
    uint64_t h = create(), stale = h;
    CHECK(dp_proto_destroy(&h) == DP_OK);
    const uint8_t mac[6] = {2, 0, 0, 0, 0, 1}, ip[16] = {0xfe, 0x80};
    CHECK(dp_proto_create(mac, ip, 1500, 100, entropy, sizeof(entropy), &h) == DP_RESOURCE_LIMIT);
    CHECK(h == 0 && dp_proto_poll(stale) != DP_OK);
}
int main(int argc, char **argv) {
    for (size_t i = 0; i < sizeof(entropy); i++)
        entropy[i] = (uint8_t)(i * 7 + 1);
    CHECK(argc == 2);
    if (!strcmp(argv[1], "lifecycle"))
        lifecycle();
    else if (!strcmp(argv[1], "queue"))
        queue();
    else if (!strcmp(argv[1], "stack"))
        real_stack();
    else if (!strcmp(argv[1], "entropy"))
        depleted();
    else if (!strcmp(argv[1], "thread"))
        threads();
    else if (!strcmp(argv[1], "generation"))
        generation();
    else
        CHECK(0);
    return 0;
}
