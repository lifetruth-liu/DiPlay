#include "internal.h"
#include "lwip/init.h"
#include "lwip/ip6_frag.h"
#include "lwip/priv/memp_priv.h"
#include "lwip/priv/tcp_priv.h"
#include "lwip/stats.h"
#include "lwip/tcp.h"
#include "lwip/timeouts.h"
#include "lwip/udp.h"
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static atomic_int claimed;
static _Thread_local dp_core *owner;
static int initialized;
static uint32_t last_time;

uint32_t dp_core_abi_version(void) {
    return 1;
}
u32_t sys_now(void) {
    return owner ? owner->now : last_time;
}
uint32_t dp_platform_random32(void) {
    dp_platform_assert_core();
    return owner->config.random32(owner->config.opaque);
}
void dp_platform_assert(const char *message, const char *file, int line) {
    fprintf(stderr, "lwIP assertion: %s (%s:%d)\n", message, file, line);
    abort();
}
void dp_platform_assert_core(void) {
    if (!owner || !owner->entered)
        dp_platform_assert("outside core thread", __FILE__, __LINE__);
}
dp_result dp_enter(dp_core *core, int allow_failed) {
    if (!core)
        return DP_INVALID_ARGUMENT;
    if (owner != core)
        return DP_WRONG_THREAD;
    if (core->entered)
        return DP_REENTRANT;
    if (core->failure != DP_OK && !allow_failed)
        return core->failure;
    core->entered = 1;
    uint64_t now = core->config.clock_ms(core->config.opaque);
    if (now < core->last_clock)
        core->failure = DP_CLOCK_REVERSED;
    else {
        core->last_clock = now;
        core->now = core->origin + (uint32_t)(now - core->initial_clock);
    }
    if (core->failure != DP_OK && !allow_failed) {
        core->entered = 0;
        return core->failure;
    }
    return DP_OK;
}
dp_result dp_leave(dp_core *core) {
    dp_tcp_reconcile(core);
    /* A TCP PCB must never outlive a failed essential timer allocation unnoticed.
     * Retry a transient failure once at the owner boundary; persistent failure
     * terminates this context through the normal destruction barrier. */
    if (core->failure == DP_OK && tcp_timer_ensure() != ERR_OK)
        core->failure = DP_RESOURCE_LIMIT;
    if (netif_ip6_addr_state(&core->netif, 0) == IP6_ADDR_DUPLICATED)
        core->failure = DP_ADDRESS_CONFLICT;
    if (core->pending_link_down) {
        core->pending_link_down = 0;
        netif_set_link_down(&core->netif);
        if (!core->address_validated && core->failure != DP_ADDRESS_CONFLICT)
            netif_ip6_addr_set_state(&core->netif, 0, IP6_ADDR_TENTATIVE);
    }
    if (ip6_addr_isvalid(netif_ip6_addr_state(&core->netif, 0)))
        core->address_validated = 1;
    last_time = core->now;
    core->entered = 0;
    return core->failure;
}
dp_result dp_core_create(const dp_config *config, dp_core **result) {
    if (!result)
        return DP_INVALID_ARGUMENT;
    *result = NULL;
    if (!config || !config->clock_ms || !config->random32 || !config->output ||
        config->mtu < 1280 || config->mtu > 1500 || (config->mac[0] & 1) ||
        !memcmp(config->mac, "\0\0\0\0\0\0", 6) || config->ipv6[0] != 0xfe ||
        (config->ipv6[1] & 0xc0) != 0x80)
        return DP_INVALID_ARGUMENT;
    int expected = 0;
    if (!atomic_compare_exchange_strong(&claimed, &expected, 1))
        return DP_RESOURCE_LIMIT;
    dp_core *core = calloc(1, sizeof(*core));
    if (!core) {
        atomic_store(&claimed, 0);
        return DP_RESOURCE_LIMIT;
    }
    core->config = *config;
    core->initial_clock = core->last_clock = config->clock_ms(config->opaque);
    core->origin = core->now = last_time;
    core->entered = 1;
    owner = core;
    if (!initialized) {
        lwip_init();
        initialized = 1;
    }
    dp_result status = dp_netif_init(core);
    core->entered = 0;
    if (status != DP_OK) {
        owner = NULL;
        free(core);
        atomic_store(&claimed, 0);
        return status;
    }
    *result = core;
    return DP_OK;
}
dp_result dp_core_destroy(dp_core **pointer) {
    if (!pointer)
        return DP_INVALID_ARGUMENT;
    if (!*pointer)
        return DP_OK;
    dp_core *core = *pointer;
    dp_result status = dp_enter(core, 1);
    if (status != DP_OK)
        return status;
    core->link_ready = 0;
    netif_set_link_down(&core->netif);
    dp_endpoint_clear(&core->endpoints);
    LWIP_ASSERT("TCP endpoint ownership remains at destroy", core->tcp_rx_bytes == 0 &&
                core->tcp_stream_count == 0 && core->tcp_listener_count == 0 && core->tcp_streams == NULL);
    /* One context owns every PCB. Remove callback reachability before abort/free. */
    while (tcp_active_pcbs || tcp_tw_pcbs || tcp_bound_pcbs) {
        struct tcp_pcb *pcb =
            tcp_active_pcbs ? tcp_active_pcbs : (tcp_tw_pcbs ? tcp_tw_pcbs : tcp_bound_pcbs);
        tcp_arg(pcb, NULL);
        tcp_err(pcb, NULL);
        tcp_recv(pcb, NULL);
        tcp_sent(pcb, NULL);
        tcp_poll(pcb, NULL, 0);
        tcp_abort(pcb);
    }
    while (tcp_listen_pcbs.listen_pcbs) {
        struct tcp_pcb_listen *pcb = tcp_listen_pcbs.listen_pcbs;
        tcp_arg((struct tcp_pcb *)pcb, NULL);
        tcp_accept((struct tcp_pcb *)pcb, NULL);
        tcp_close((struct tcp_pcb *)pcb);
    }
    while (udp_pcbs) {
        udp_recv(udp_pcbs, NULL, NULL);
        udp_remove(udp_pcbs);
    }
    /* Reassembly is global in upstream lwIP. Expire it before a link/address can be reused. */
    for (int i = 0; i <= IPV6_REASS_MAXAGE; i++)
        ip6_reass_tmr();
    netif_set_down(&core->netif);
    netif_remove(&core->netif);
    last_time = core->now;
    core->entered = 0;
    owner = NULL;
    *pointer = NULL;
    free(core);
    atomic_store(&claimed, 0);
    return DP_OK;
}
dp_result dp_core_set_link_ready(dp_core *core, int ready) {
    dp_result status = dp_enter(core, 0);
    if (status != DP_OK)
        return status;
    core->link_ready = !!ready;
    if (ready)
        netif_set_link_up(&core->netif);
    else
        core->pending_link_down = 1;
    return dp_leave(core);
}
dp_result dp_core_input(dp_core *core, const uint8_t *frame, size_t length) {
    if (!frame || length < 14 || length > 1514)
        return DP_INVALID_ARGUMENT;
    dp_result status = dp_enter(core, 0);
    if (status != DP_OK)
        return status;
    if (length > (size_t)core->config.mtu + 14) {
        core->entered = 0;
        return DP_INVALID_ARGUMENT;
    }
    if (frame[12] != 0x86 || frame[13] != 0xdd) {
        core->stats.rx_rejected++;
        core->entered = 0;
        return DP_UNSUPPORTED;
    }
    struct pbuf *p = pbuf_alloc(PBUF_RAW, (u16_t)length, PBUF_POOL);
    if (!p) {
        core->stats.rx_rejected++;
        core->entered = 0;
        return DP_RESOURCE_LIMIT;
    }
    if (pbuf_take(p, frame, (u16_t)length) != ERR_OK) {
        pbuf_free(p);
        core->entered = 0;
        return DP_INTERNAL;
    }
    core->stats.rx_frames++;
    err_t error = core->netif.input(p, &core->netif);
    if (error != ERR_OK)
        pbuf_free(p);
    status = dp_leave(core);
    return status != DP_OK ? status : (error == ERR_OK ? DP_OK : DP_INTERNAL);
}
dp_result dp_core_poll(dp_core *core) {
    dp_result status = dp_enter(core, 0);
    if (status != DP_OK)
        return status;
    sys_check_timeouts();
    return dp_leave(core);
}
dp_result dp_core_next_timeout(dp_core *core, uint32_t *millis) {
    if (!millis)
        return DP_INVALID_ARGUMENT;
    dp_result status = dp_enter(core, 0);
    if (status != DP_OK)
        return status;
    *millis = sys_timeouts_sleeptime();
    return dp_leave(core);
}
dp_result dp_core_address_state(dp_core *core, dp_address_state *result) {
    if (!result)
        return DP_INVALID_ARGUMENT;
    dp_result status = dp_enter(core, 1);
    if (status != DP_OK)
        return status;
    uint8_t state = netif_ip6_addr_state(&core->netif, 0);
    dp_leave(core);
    *result = state == IP6_ADDR_DUPLICATED
                  ? DP_ADDRESS_DUPLICATE
                  : (ip6_addr_isvalid(state) ? DP_ADDRESS_PREFERRED : DP_ADDRESS_TENTATIVE);
    return DP_OK;
}
dp_result dp_core_execute(dp_core *core, int (*command)(void *), void *arg) {
    if (!command)
        return DP_INVALID_ARGUMENT;
    dp_result status = dp_enter(core, 0);
    if (status != DP_OK)
        return status;
    int result = command(arg);
    status = dp_leave(core);
    return status != DP_OK ? status : (result == 0 ? DP_OK : DP_INTERNAL);
}
dp_result dp_core_get_stats(dp_core *core, dp_stats *stats) {
    if (!stats)
        return DP_INVALID_ARGUMENT;
    dp_result status = dp_enter(core, 1);
    if (status != DP_OK)
        return status;
    *stats = core->stats;
    stats->heap_used = (uint32_t)lwip_stats.mem.used + core->endpoint_allocated;
    stats->heap_peak = (uint32_t)lwip_stats.mem.max + core->endpoint_peak;
    stats->pbuf_used =
        (uint32_t)(lwip_stats.memp[MEMP_PBUF_POOL]->used + lwip_stats.memp[MEMP_PBUF]->used);
    /* Reserve 64 KiB for descriptors, counters and global protocol metadata in addition
     * to the actual heap/pools/context. ELF data+BSS is checked separately per ABI. */
    stats->configured_bytes = MEM_SIZE + sizeof(*core) + 1536 + 65536 + core->endpoint_allocated;
    stats->failure = core->failure;
    for (int i = 0; i < MEMP_MAX; i++)
        stats->configured_bytes +=
            (uint32_t)memp_pools[i]->num * (MEMP_SIZE + MEMP_ALIGN_SIZE(memp_pools[i]->size));
    for (struct tcp_pcb *p = tcp_active_pcbs; p; p = p->next)
        stats->tcp_active++;
    for (struct tcp_pcb_listen *p = tcp_listen_pcbs.listen_pcbs; p; p = p->next)
        stats->tcp_listeners++;
    for (struct udp_pcb *p = udp_pcbs; p; p = p->next)
        stats->udp_pcbs++;
    dp_leave(core);
    return DP_OK;
}
