#include "diplay_endpoints.h"
#include "internal.h"
#include "lwip/udp.h"
#include <stdlib.h>
#include <string.h>
#define UDP_BLOCK_BYTES 1024u
struct udp_block { uint8_t bytes[UDP_BLOCK_BYTES]; uint16_t next; };
struct queued_datagram { dp_udp_datagram info; uint16_t first; };
struct udp_endpoint {
    dp_core *owner;
    struct udp_pcb *pcb;
    struct queued_datagram packets[DP_UDP_QUEUE_PACKETS];
    uint32_t head, count, used;
    uint64_t dropped;
};
/* Reserve all endpoint records and block metadata inside the global RX budget,
 * not just the payload bytes. Allocation is outside receive callbacks. */
#define UDP_BLOCKS ((DP_UDP_GLOBAL_QUEUE_BYTES - MEMP_NUM_UDP_PCB * sizeof(struct udp_endpoint) - 8u) / sizeof(struct udp_block))
struct dp_udp_pool {
    struct udp_block blocks[UDP_BLOCKS];
    uint16_t free_head, free_count, owners;
};
_Static_assert(UDP_BLOCKS >= 64 && UDP_BLOCKS < UINT16_MAX, "UDP block capacity invalid");
_Static_assert(sizeof(struct dp_udp_pool) + MEMP_NUM_UDP_PCB * sizeof(struct udp_endpoint) <= DP_UDP_GLOBAL_QUEUE_BYTES,
               "UDP RX payload and metadata exceed global budget");
static dp_result map_error(err_t error) {
    if (error == ERR_OK) return DP_OK;
    if (error == ERR_MEM || error == ERR_BUF) return DP_RESOURCE_LIMIT;
    if (error == ERR_USE) return DP_ADDRESS_IN_USE;
    if (error == ERR_VAL || error == ERR_ARG) return DP_INVALID_ARGUMENT;
    if (error == ERR_RTE || error == ERR_IF) return DP_LINK_IO;
    return DP_INTERNAL;
}
static dp_result leave(dp_core *core, dp_result result) {
    dp_result status = dp_leave(core);
    return status == DP_OK ? result : status;
}
static dp_result lookup(dp_core *core, uint64_t handle, struct udp_endpoint **endpoint) {
    void *value = NULL;
    dp_result status = dp_endpoint_lookup(&core->endpoints, handle, DP_ENDPOINT_UDP, &value);
    *endpoint = value;
    return status;
}
static void free_blocks(struct dp_udp_pool *pool, uint16_t first) {
    while (first) {
        struct udp_block *block = &pool->blocks[first - 1];
        uint16_t next = block->next;
        block->next = pool->free_head;
        pool->free_head = first;
        pool->free_count++;
        first = next;
    }
}
static void release(void *value) {
    struct udp_endpoint *endpoint = value;
    dp_core *core = endpoint->owner;
    udp_recv(endpoint->pcb, NULL, NULL);
    udp_remove(endpoint->pcb);
    struct dp_udp_pool *pool = core->udp_pool;
    for (uint32_t i = 0; i < endpoint->count; ++i)
        free_blocks(pool, endpoint->packets[(endpoint->head + i) % DP_UDP_QUEUE_PACKETS].first);
    core->endpoint_allocated -= sizeof(*endpoint);
    if (--pool->owners == 0) {
        LWIP_ASSERT("UDP pool blocks remain at last close", pool->free_count == UDP_BLOCKS);
        core->endpoint_allocated -= sizeof(*pool);
        free(pool);
        core->udp_pool = NULL;
    }
    free(endpoint);
}
static void received(void *arg, struct udp_pcb *pcb, struct pbuf *p, const ip_addr_t *source, u16_t port) {
    struct udp_endpoint *endpoint = arg;
    (void)pcb;
    if (!p) return;
    uint32_t length = p->tot_len;
    struct dp_udp_pool *pool = endpoint->owner->udp_pool;
    uint32_t needed = (length + UDP_BLOCK_BYTES - 1) / UDP_BLOCK_BYTES;
    if (!IP_IS_V6(source) || length > DP_UDP_MAX_PAYLOAD ||
        endpoint->count == DP_UDP_QUEUE_PACKETS || length > DP_UDP_QUEUE_BYTES - endpoint->used ||
        needed > pool->free_count) {
        endpoint->dropped++;
        pbuf_free(p);
        return;
    }
    uint16_t first = 0, last = 0;
    uint32_t copied = 0;
    while (copied < length) {
        uint16_t index = pool->free_head;
        struct udp_block *block = &pool->blocks[index - 1];
        pool->free_head = block->next;
        pool->free_count--;
        block->next = 0;
        if (last) pool->blocks[last - 1].next = index;
        else first = index;
        last = index;
        uint16_t count = (uint16_t)LWIP_MIN(length - copied, UDP_BLOCK_BYTES);
        if (pbuf_copy_partial(p, block->bytes, count, (uint16_t)copied) != count) {
            free_blocks(pool, first);
            endpoint->dropped++;
            pbuf_free(p);
            return;
        }
        copied += count;
    }
    struct queued_datagram *packet = &endpoint->packets[(endpoint->head + endpoint->count) % DP_UDP_QUEUE_PACKETS];
    memset(packet, 0, sizeof(*packet));
    memcpy(packet->info.source, ip_2_ip6(source)->addr, 16);
    packet->info.source_port = port;
    packet->info.original_length = (uint16_t)length;
    packet->first = first;
    endpoint->count++;
    endpoint->used += length;
    pbuf_free(p);
}
dp_result dp_udp_bind(dp_core *core, uint16_t port, uint64_t *handle, uint16_t *actual_port) {
    if (handle) *handle = 0;
    if (actual_port) *actual_port = 0;
    if (!handle || !actual_port) return DP_INVALID_ARGUMENT;
    dp_result status = dp_enter(core, 0);
    if (status != DP_OK) return status;
    struct udp_endpoint *endpoint = calloc(1, sizeof(*endpoint));
    if (!endpoint) return leave(core, DP_RESOURCE_LIMIT);
    endpoint->owner = core;
    endpoint->pcb = udp_new_ip_type(IPADDR_TYPE_V6);
    if (!endpoint->pcb) { free(endpoint); return leave(core, DP_RESOURCE_LIMIT); }
    ip_addr_t local;
    ip_addr_copy_from_ip6(local, *netif_ip6_addr(&core->netif, 0));
    status = map_error(udp_bind(endpoint->pcb, &local, port));
    int new_pool = 0;
    if (status == DP_OK && !core->udp_pool) {
        core->udp_pool = calloc(1, sizeof(*core->udp_pool));
        if (!core->udp_pool) status = DP_RESOURCE_LIMIT;
        else {
            new_pool = 1;
            core->udp_pool->free_head = 1;
            core->udp_pool->free_count = UDP_BLOCKS;
            for (unsigned i = 0; i < UDP_BLOCKS - 1; ++i) core->udp_pool->blocks[i].next = (uint16_t)(i + 2);
        }
    }
    if (status == DP_OK) status = dp_endpoint_register(&core->endpoints, DP_ENDPOINT_UDP, endpoint, release, handle);
    if (status != DP_OK) {
        if (new_pool) { free(core->udp_pool); core->udp_pool = NULL; }
        udp_remove(endpoint->pcb); free(endpoint); return leave(core, status);
    }
    udp_bind_netif(endpoint->pcb, &core->netif);
    udp_recv(endpoint->pcb, received, endpoint);
    core->endpoint_allocated += sizeof(*endpoint);
    if (new_pool) core->endpoint_allocated += sizeof(*core->udp_pool);
    core->udp_pool->owners++;
    if (core->endpoint_allocated > core->endpoint_peak) core->endpoint_peak = core->endpoint_allocated;
    *actual_port = endpoint->pcb->local_port;
    return leave(core, DP_OK);
}
dp_result dp_udp_send(dp_core *core, uint64_t handle, const uint8_t destination[16], uint16_t port,
                       const uint8_t *payload, size_t length) {
    if (!destination || !port || (!payload && length) || length > DP_UDP_MAX_PAYLOAD) return DP_INVALID_ARGUMENT;
    dp_result status = dp_enter(core, 0);
    if (status != DP_OK) return status;
    struct udp_endpoint *endpoint = NULL;
    status = lookup(core, handle, &endpoint);
    if (status != DP_OK) return leave(core, status);
    if (!core->link_ready) return leave(core, DP_WOULD_BLOCK);
    ip_addr_t address;
    ip_addr_set_zero_ip6(&address);
    memcpy(ip_2_ip6(&address)->addr, destination, 16);
    ip6_addr_assign_zone(ip_2_ip6(&address), IP6_UNKNOWN, &core->netif);
    struct pbuf *p = pbuf_alloc(PBUF_TRANSPORT, (uint16_t)length, PBUF_RAM);
    if (!p) return leave(core, DP_RESOURCE_LIMIT);
    err_t error = length ? pbuf_take(p, payload, (uint16_t)length) : ERR_OK;
    if (error == ERR_OK) error = udp_sendto_if(endpoint->pcb, p, &address, port, &core->netif);
    pbuf_free(p);
    if (error == ERR_IF && core->pending_link_down && core->failure == DP_OK)
        return leave(core, DP_WOULD_BLOCK);
    return leave(core, map_error(error));
}
dp_result dp_udp_receive(dp_core *core, uint64_t handle, uint8_t *target, size_t capacity, dp_udp_datagram *info) {
    if (!info || (!target && capacity)) return DP_INVALID_ARGUMENT;
    memset(info, 0, sizeof(*info));
    dp_result status = dp_enter(core, 0);
    if (status != DP_OK) return status;
    struct udp_endpoint *endpoint = NULL;
    status = lookup(core, handle, &endpoint);
    if (status != DP_OK) return leave(core, status);
    if (!endpoint->count) return leave(core, DP_WOULD_BLOCK);
    struct queued_datagram *packet = &endpoint->packets[endpoint->head];
    *info = packet->info;
    info->copied_length = (uint16_t)LWIP_MIN(capacity, info->original_length);
    uint16_t index = packet->first;
    size_t copied = 0;
    while (index && copied < info->copied_length) {
        struct udp_block *block = &core->udp_pool->blocks[index - 1];
        size_t count = LWIP_MIN(info->copied_length - copied, UDP_BLOCK_BYTES);
        memcpy(target + copied, block->bytes, count);
        copied += count;
        index = block->next;
    }
    free_blocks(core->udp_pool, packet->first);
    endpoint->used -= packet->info.original_length;
    endpoint->head = (endpoint->head + 1) % DP_UDP_QUEUE_PACKETS;
    endpoint->count--;
    return leave(core, DP_OK);
}
dp_result dp_udp_get_queue_stats(dp_core *core, uint64_t handle, dp_udp_queue_stats *stats) {
    if (!stats) return DP_INVALID_ARGUMENT;
    memset(stats, 0, sizeof(*stats));
    dp_result status = dp_enter(core, 1);
    if (status != DP_OK) return status;
    struct udp_endpoint *endpoint = NULL;
    status = lookup(core, handle, &endpoint);
    if (status == DP_OK) *stats = (dp_udp_queue_stats){endpoint->count, endpoint->used, endpoint->dropped};
    return leave(core, status);
}
dp_result dp_udp_close(dp_core *core, uint64_t handle) {
    dp_result status = dp_enter(core, 1);
    if (status != DP_OK) return status;
    status = dp_endpoint_remove(&core->endpoints, handle, DP_ENDPOINT_UDP);
    return leave(core, status);
}
