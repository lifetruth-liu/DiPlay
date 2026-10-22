#ifndef DIPLAY_LWIP_INTERNAL_H
#define DIPLAY_LWIP_INTERNAL_H
#include "diplay_lwip.h"
#include "lwip/netif.h"
#include "endpoint_registry.h"
struct dp_core {
    dp_endpoint_registry endpoints;
    uint32_t endpoint_allocated, endpoint_peak;
    struct dp_udp_pool *udp_pool;
    struct tcp_stream *tcp_streams;
    uint32_t tcp_rx_bytes;
    uint16_t tcp_stream_count, tcp_listener_count;
    dp_config config;
    struct netif netif;
    uint64_t initial_clock, last_clock;
    uint32_t origin, now;
    int entered, link_ready, pending_link_down, address_validated;
    dp_result failure;
    dp_stats stats;
};
dp_result dp_netif_init(dp_core *core);
dp_result dp_enter(dp_core *core, int allow_failed);
dp_result dp_leave(dp_core *core);
/* Drop application references to TIME_WAIT PCBs before timers/allocators can reclaim them. */
void dp_tcp_reconcile(dp_core *core);
#endif
