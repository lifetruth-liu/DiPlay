#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>

#include "lwip/err.h"
#include "lwip/init.h"
#include "lwip/ip_addr.h"
#include "lwip/opt.h"
#include "lwip/tcp.h"

#if DIPLAY_TEST_TCP_PORT_COUNT <= 0
#error "DIPLAY_TEST_TCP_PORT_COUNT must be positive"
#endif

static int core_active;
static uint32_t random_state = UINT32_C(0x9e3779b9);

u32_t sys_now(void) {
    return 0;
}

void dp_platform_assert(const char *message, const char *file, int line) {
    fprintf(stderr, "lwIP assertion: %s (%s:%d)\n", message, file, line);
    abort();
}

void dp_platform_assert_core(void) {
    if (!core_active) dp_platform_assert("outside single-thread test core", __FILE__, __LINE__);
}

uint32_t dp_platform_random32(void) {
    random_state ^= random_state << 13;
    random_state ^= random_state >> 17;
    random_state ^= random_state << 5;
    return random_state;
}

static int fail(const char *message, int line) {
    fprintf(stderr, "tcp port exhaustion test failed at line %d: %s\n", line, message);
    return 1;
}

#define CHECK(condition, message) \
    do { \
        if (!(condition)) return fail((message), __LINE__); \
    } while (0)

int main(void) {
    struct tcp_pcb *bound[DIPLAY_TEST_TCP_PORT_COUNT] = {0};
    struct tcp_pcb *probe = NULL;
    ip_addr_t any;
    unsigned char used[DIPLAY_TEST_TCP_PORT_COUNT] = {0};
    u16_t first_port = 0;

    memset(&any, 0, sizeof(any));
    ip_addr_set_any(IPADDR_TYPE_V6, &any);
    core_active = 1;
    lwip_init();

    for (unsigned i = 0; i < DIPLAY_TEST_TCP_PORT_COUNT; ++i) {
        bound[i] = tcp_new_ip_type(IPADDR_TYPE_V6);
        CHECK(bound[i] != NULL, "TCP PCB allocation failed before the port interval filled");
        CHECK(tcp_bind(bound[i], &any, 0) == ERR_OK, "ephemeral bind failed before interval exhaustion");
        const u16_t port = bound[i]->local_port;
        CHECK(port >= DIPLAY_TEST_TCP_PORT_START &&
              port < DIPLAY_TEST_TCP_PORT_START + DIPLAY_TEST_TCP_PORT_COUNT,
          "ephemeral port escaped the full dynamic range");
        const unsigned slot = (unsigned)(port - DIPLAY_TEST_TCP_PORT_START);
        CHECK(used[slot] == 0, "ephemeral allocator issued a duplicate port");
        used[slot] = 1;
        if (i == 0) first_port = port;
    }

    probe = tcp_new_ip_type(IPADDR_TYPE_V6);
    CHECK(probe != NULL, "PCB allocation failed before probing exhausted interval");
    CHECK(tcp_bind(probe, &any, 0) == ERR_BUF,
          "full ephemeral interval did not report the documented exhaustion error");

    CHECK(tcp_close(bound[0]) == ERR_OK, "closing a bound PCB failed");
    bound[0] = NULL;
    CHECK(tcp_bind(probe, &any, 0) == ERR_OK,
          "ephemeral allocation did not recover after a bound PCB closed");
    CHECK(probe->local_port == first_port,
          "recovered allocation did not reuse the only released port");

    CHECK(tcp_close(probe) == ERR_OK, "closing the recovered PCB failed");
    for (unsigned i = 0; i < DIPLAY_TEST_TCP_PORT_COUNT; ++i) {
        if (bound[i] != NULL) {
            CHECK(tcp_close(bound[i]) == ERR_OK, "closing a remaining bound PCB failed");
        }
    }

    puts("PASS full TCP ephemeral port range exhaustion and recovery");
    return 0;
}
