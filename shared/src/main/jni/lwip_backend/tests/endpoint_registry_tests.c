#include "endpoint_registry.h"
#include "internal.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define CHECK(c) do { if (!(c)) { fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #c); exit(1); } } while (0)
static void release(void *value) { ++*(unsigned *)value; }
struct context_test { dp_core *core; unsigned released; uint64_t handle; };
static uint64_t clock_ms(void *arg) { (void)arg; return 0; }
static uint32_t random32(void *arg) { (void)arg; return 1234567; }
static dp_tx_result output(void *arg, const uint8_t *data, size_t length) {
    (void)arg; (void)data; (void)length; return DP_TX_NOT_READY;
}
static int attach(void *arg) {
    struct context_test *test = arg;
    return dp_endpoint_register(&test->core->endpoints, DP_ENDPOINT_TCP, &test->released, release, &test->handle);
}
int main(void) {
    dp_endpoint_registry registry = {0}, other = {0};
    unsigned releases[DP_ENDPOINT_CAPACITY] = {0};
    uint64_t handles[DP_ENDPOINT_CAPACITY];
    void *value = NULL;
    CHECK(dp_endpoint_register(&registry, DP_ENDPOINT_TCP, &releases[0], release, &handles[0]) == DP_OK);
    CHECK(handles[0] > 0 && handles[0] <= INT64_MAX);
    CHECK(dp_endpoint_lookup(&registry, handles[0], DP_ENDPOINT_TCP, &value) == DP_OK);
    CHECK(value == &releases[0]);
    CHECK(dp_endpoint_lookup(&other, handles[0], DP_ENDPOINT_TCP, &value) == DP_INVALID_ARGUMENT);
    CHECK(value == NULL);
    unsigned other_released = 0;
    uint64_t other_handle = 0;
    CHECK(dp_endpoint_register(&other, DP_ENDPOINT_TCP, &other_released, release, &other_handle) == DP_OK);
    CHECK(other_handle != handles[0]);
    CHECK(dp_endpoint_lookup(&other, handles[0], DP_ENDPOINT_TCP, &value) == DP_INVALID_ARGUMENT);
    CHECK(dp_endpoint_lookup(&registry, other_handle, DP_ENDPOINT_TCP, &value) == DP_INVALID_ARGUMENT);
    dp_endpoint_clear(&other);
    CHECK(other_released == 1);
    CHECK(dp_endpoint_lookup(&registry, handles[0], DP_ENDPOINT_UDP, &value) == DP_INVALID_ARGUMENT);
    CHECK(dp_endpoint_remove(&registry, handles[0], DP_ENDPOINT_TCP) == DP_OK);
    CHECK(releases[0] == 1 && registry.used == 0);
    CHECK(dp_endpoint_remove(&registry, handles[0], DP_ENDPOINT_TCP) == DP_INVALID_ARGUMENT);
    uint64_t stale = handles[0];
    for (unsigned i = 0; i < DP_ENDPOINT_CAPACITY; ++i) {
        CHECK(dp_endpoint_register(&registry, DP_ENDPOINT_UDP, &releases[i], release, &handles[i]) == DP_OK);
        CHECK(handles[i] != stale);
    }
    CHECK(dp_endpoint_lookup(&registry, stale, DP_ENDPOINT_TCP, &value) == DP_INVALID_ARGUMENT);
    uint64_t overflow = 99;
    CHECK(dp_endpoint_register(&registry, DP_ENDPOINT_UDP, &releases[0], release, &overflow) == DP_RESOURCE_LIMIT);
    CHECK(overflow == 0 && registry.used == DP_ENDPOINT_CAPACITY);
    dp_endpoint_clear(&registry);
    CHECK(registry.used == 0);
    dp_endpoint_clear(&registry);
    for (unsigned i = 0; i < DP_ENDPOINT_CAPACITY; ++i) {
        CHECK(releases[i] == (i ? 1u : 2u));
        CHECK(dp_endpoint_lookup(&registry, handles[i], DP_ENDPOINT_UDP, &value) == DP_INVALID_ARGUMENT);
    }
    CHECK(dp_endpoint_register(&registry, DP_ENDPOINT_TCP, &releases[0], release, &overflow) == DP_OK);
    for (unsigned i = 0; i < DP_ENDPOINT_CAPACITY; ++i) CHECK(overflow != handles[i]);
    dp_endpoint_clear(&registry);
    struct context_test test = {0};
    dp_config config = {0};
    config.mac[0] = 2; config.mac[5] = 1;
    config.ipv6[0] = 0xfe; config.ipv6[1] = 0x80; config.ipv6[15] = 1;
    config.mtu = 1500; config.clock_ms = clock_ms;
    config.random32 = random32; config.output = output;
    CHECK(dp_core_create(&config, &test.core) == DP_OK);
    CHECK(dp_core_execute(test.core, attach, &test) == DP_OK);
    CHECK(dp_core_destroy(&test.core) == DP_OK);
    CHECK(test.core == NULL && test.released == 1);
    puts("Bounded endpoint handles, stale generations and release ownership passed");
    return 0;
}
