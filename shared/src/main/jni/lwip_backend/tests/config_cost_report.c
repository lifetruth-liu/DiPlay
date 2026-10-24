#include "diplay_endpoints.h"
#include "diplay_tcp.h"
#include "diplay_lwip.h"
#include "internal.h"
#include "lwip/init.h"
#include "lwip/memp.h"
#include "lwip/opt.h"
#include "lwip/priv/memp_priv.h"
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define UDP_ENDPOINT_LIMIT MEMP_NUM_UDP_PCB
#define LISTENER_LIMIT MEMP_NUM_TCP_PCB_LISTEN
#define FRAME_WORKSPACE_BYTES 1536u
#define PROTOCOL_METADATA_RESERVE_BYTES 65536u
#define NATIVE_CORE_BUDGET_BYTES (16u * 1024u * 1024u)

struct report_environment {
    uint64_t now;
    uint32_t random;
};

static uint64_t report_clock(void *opaque) {
    return ((struct report_environment *)opaque)->now;
}

static uint32_t report_random(void *opaque) {
    struct report_environment *environment = opaque;
    environment->random ^= environment->random << 13;
    environment->random ^= environment->random >> 17;
    environment->random ^= environment->random << 5;
    return environment->random;
}

static dp_tx_result report_output(void *opaque, const uint8_t *frame, size_t length) {
    (void)opaque;
    return frame && length >= 14 ? DP_TX_ACCEPTED : DP_TX_FAILED;
}

static int fail(const char *message) {
    fprintf(stderr, "lwip_config_cost_report: %s\n", message);
    return 1;
}

int main(void) {
    const uint8_t mac[6] = {0x02, 0x44, 0x69, 0x50, 0x6c, 0x61};
    const uint8_t ipv6[16] = {0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
    struct report_environment environment = {0, 0x6d2b79f5u};
    dp_config config = {0};
    memcpy(config.mac, mac, sizeof(mac));
    memcpy(config.ipv6, ipv6, sizeof(ipv6));
    config.mtu = 1500;
    config.opaque = &environment;
    config.clock_ms = report_clock;
    config.random32 = report_random;
    config.output = report_output;

    dp_core *core = NULL;
    if (dp_core_create(&config, &core) != DP_OK || !core) return fail("core creation failed");

    uint32_t memp_pool_bytes = 0;
    for (unsigned i = 0; i < MEMP_MAX; ++i) {
        memp_pool_bytes += (uint32_t)memp_pools[i]->num *
                           (MEMP_SIZE + MEMP_ALIGN_SIZE(memp_pools[i]->size));
    }
    const uint32_t fixed_core_bytes = (uint32_t)(MEM_SIZE + sizeof(*core) + FRAME_WORKSPACE_BYTES +
                                                  PROTOCOL_METADATA_RESERVE_BYTES + memp_pool_bytes);

    dp_stats baseline;
    if (dp_core_get_stats(core, &baseline) != DP_OK) {
        dp_core_destroy(&core);
        return fail("baseline stats unavailable");
    }
    if (baseline.configured_bytes != fixed_core_bytes) {
        dp_core_destroy(&core);
        return fail("configured byte accounting differs from its source components");
    }

    uint64_t udp_handles[UDP_ENDPOINT_LIMIT] = {0};
    uint64_t listener_handles[LISTENER_LIMIT] = {0};
    for (unsigned i = 0; i < UDP_ENDPOINT_LIMIT; ++i) {
        uint16_t actual_port = 0;
        if (dp_udp_bind(core, 0, &udp_handles[i], &actual_port) != DP_OK ||
            !udp_handles[i] || !actual_port) {
            dp_core_destroy(&core);
            return fail("could not fill the configured UDP endpoint limit");
        }
    }
    for (unsigned i = 0; i < LISTENER_LIMIT; ++i) {
        uint16_t actual_port = 0;
        if (dp_tcp_listen(core, 0, 1, &listener_handles[i], &actual_port) != DP_OK ||
            !listener_handles[i] || !actual_port) {
            dp_core_destroy(&core);
            return fail("could not fill the configured TCP listener limit");
        }
    }

    dp_stats saturated;
    if (dp_core_get_stats(core, &saturated) != DP_OK ||
        saturated.udp_pcbs != UDP_ENDPOINT_LIMIT || saturated.tcp_listeners != LISTENER_LIMIT ||
        saturated.configured_bytes < baseline.configured_bytes ||
        saturated.configured_bytes > NATIVE_CORE_BUDGET_BYTES) {
        dp_core_destroy(&core);
        return fail("saturated endpoint configuration violated accounting or the 16 MiB native-core budget");
    }

    printf("{\n");
    printf("  \"schema_version\": 1,\n");
    printf("  \"scope\": \"lwIP native core capacity accounting; excludes Kotlin/ART, USB, process-wide memory, and active TCP streams\",\n");
    printf("  \"lwip_version\": \"%s\",\n", LWIP_VERSION_STRING);
    printf("  \"pointer_bits\": %u,\n", (unsigned)(sizeof(void *) * 8u));
    printf("  \"components_bytes\": {\n");
    printf("    \"lwip_heap_capacity\": %u,\n", (unsigned)MEM_SIZE);
    printf("    \"memp_static_pools\": %u,\n", (unsigned)memp_pool_bytes);
    printf("    \"dp_core_context\": %u,\n", (unsigned)sizeof(*core));
    printf("    \"ethernet_frame_workspace\": %u,\n", FRAME_WORKSPACE_BYTES);
    printf("    \"protocol_metadata_reserve\": %u,\n", PROTOCOL_METADATA_RESERVE_BYTES);
    printf("    \"udp_endpoint_limit\": %u,\n", (unsigned)UDP_ENDPOINT_LIMIT);
    printf("    \"tcp_listener_limit\": %u,\n", (unsigned)LISTENER_LIMIT);
    printf("    \"tcp_active_stream_limit\": %u,\n", (unsigned)MEMP_NUM_TCP_PCB);
    printf("    \"udp_receive_queue_cap\": %u,\n", DP_UDP_GLOBAL_QUEUE_BYTES);
    printf("    \"tcp_receive_queue_cap\": %u,\n", DP_TCP_RX_BYTES);
    printf("    \"tcp_send_queue_cap\": %u\n", DP_TCP_TX_BYTES);
    printf("  },\n");
    printf("  \"startup\": {\"configured_bytes\": %u, \"heap_used_bytes\": %u, \"heap_peak_bytes\": %u},\n",
           baseline.configured_bytes, baseline.heap_used, baseline.heap_peak);
    printf("  \"max_udp_and_listener_endpoints\": {\"udp_endpoints\": %u, \"tcp_listeners\": %u, ",
           saturated.udp_pcbs, saturated.tcp_listeners);
    printf("\"endpoint_allocated_bytes\": %u, \"configured_bytes\": %u, ",
           saturated.configured_bytes - fixed_core_bytes, saturated.configured_bytes);
    printf("\"heap_used_bytes\": %u, \"heap_peak_bytes\": %u},\n",
           saturated.heap_used, saturated.heap_peak);
    printf("  \"native_core_budget_reference_bytes\": %u,\n", NATIVE_CORE_BUDGET_BYTES);
    printf("  \"tested_configuration_below_native_core_budget\": %s\n",
           saturated.configured_bytes <= NATIVE_CORE_BUDGET_BYTES ? "true" : "false");
    printf("}\n");

    for (unsigned i = 0; i < LISTENER_LIMIT; ++i) {
        if (dp_tcp_listener_close(core, listener_handles[i]) != DP_OK) {
            dp_core_destroy(&core);
            return fail("listener cleanup failed");
        }
    }
    for (unsigned i = 0; i < UDP_ENDPOINT_LIMIT; ++i) {
        if (dp_udp_close(core, udp_handles[i]) != DP_OK) {
            dp_core_destroy(&core);
            return fail("UDP cleanup failed");
        }
    }
    dp_stats released;
    if (dp_core_get_stats(core, &released) != DP_OK ||
        released.configured_bytes != baseline.configured_bytes ||
        released.heap_used != baseline.heap_used || released.udp_pcbs || released.tcp_listeners) {
        dp_core_destroy(&core);
        return fail("endpoint memory did not return to the startup baseline");
    }
    if (dp_core_destroy(&core) != DP_OK || core) return fail("core cleanup failed");
    return 0;
}
