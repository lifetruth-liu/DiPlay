#include "diplay_tcp.h"
#include "internal.h"
#include "lwip/tcp.h"
#include "lwip/priv/tcp_priv.h"
#include <stdlib.h>
#include <string.h>
struct tcp_listener;
struct tcp_stream {
    struct tcp_stream *next;
    dp_core *owner;
    struct tcp_pcb *pcb;
    struct tcp_listener *listener;
    struct pbuf *rx;
    dp_tcp_info info;
    dp_result error;
    int eof, output_closed, connected;
};
struct tcp_listener {
    dp_core *owner;
    struct tcp_pcb *pcb;
    struct tcp_stream *pending[DP_TCP_MAX_BACKLOG];
    unsigned count, backlog;
};
_Static_assert(MEMP_NUM_TCP_PCB * (uint32_t)TCP_WND <= DP_TCP_RX_BYTES, "TCP receive windows exceed RX budget");
_Static_assert(MEMP_NUM_TCP_PCB * (uint32_t)TCP_SND_BUF <= DP_TCP_TX_BYTES, "TCP send buffers exceed TX budget");
static void detach(struct tcp_pcb *pcb) {
    tcp_arg(pcb, NULL); tcp_recv(pcb, NULL); tcp_err(pcb, NULL);
    tcp_sent(pcb, NULL); tcp_poll(pcb, NULL, 0);
}
void dp_tcp_reconcile(dp_core *core) {
    for (struct tcp_stream *stream = core->tcp_streams; stream; stream = stream->next) {
        if (stream->pcb && stream->pcb->state == TIME_WAIT) {
            /* Keep protocol TIME_WAIT behavior, but it must not retain an application pointer.
             * lwIP frees/reuses these PCBs without calling the err callback. */
            detach(stream->pcb);
            stream->pcb = NULL;
            stream->eof = 1;
        }
    }
}
static dp_result leave(dp_core *core, dp_result result) {
    dp_result status = dp_leave(core);
    return status == DP_OK ? result : status;
}
static void allocated(dp_core *core, size_t size) {
    core->endpoint_allocated += (uint32_t)size;
    if (core->endpoint_allocated > core->endpoint_peak) core->endpoint_peak = core->endpoint_allocated;
}
static void release_stream(void *value) {
    struct tcp_stream *stream = value;
    dp_core *core = stream->owner;
    struct tcp_stream **slot = &core->tcp_streams;
    while (*slot && *slot != stream) slot = &(*slot)->next;
    LWIP_ASSERT("TCP stream missing from owner list", *slot == stream);
    *slot = stream->next;
    if (stream->pcb) {
        detach(stream->pcb); tcp_abort(stream->pcb);
    }
    if (stream->rx) { core->tcp_rx_bytes -= stream->rx->tot_len; pbuf_free(stream->rx); }
    core->tcp_stream_count--;
    core->endpoint_allocated -= sizeof(*stream);
    free(stream);
}
static void failed(void *arg, err_t error) {
    struct tcp_stream *stream = arg;
    stream->pcb = NULL; /* Error/closed callbacks must not retain a retiring PCB. */
    if (error == ERR_CLSD) { stream->error = DP_OK; stream->eof = 1; }
    else stream->error = error == ERR_RST || error == ERR_ABRT ? DP_PEER_RESET : DP_INTERNAL;
    if (stream->listener) {
        struct tcp_listener *listener = stream->listener;
        for (unsigned i = 0; i < listener->count; ++i) if (listener->pending[i] == stream) {
            memmove(listener->pending + i, listener->pending + i + 1,
                    (listener->count - i - 1) * sizeof(listener->pending[0]));
            listener->pending[--listener->count] = NULL;
            release_stream(stream);
            break;
        }
    }
}
static err_t received(void *arg, struct tcp_pcb *pcb, struct pbuf *p, err_t error) {
    struct tcp_stream *stream = arg;
    (void)pcb;
    if (error != ERR_OK) return error;
    if (!p) { stream->eof = 1; return ERR_OK; }
    if (!p->tot_len) { pbuf_free(p); return ERR_OK; }
    uint32_t used = stream->rx ? stream->rx->tot_len : 0;
    if (p->tot_len > (uint32_t)TCP_WND - used || p->tot_len > DP_TCP_RX_BYTES - stream->owner->tcp_rx_bytes)
        return ERR_MEM; /* lwIP retains refused_data; do not copy, free or acknowledge it. */
    stream->owner->tcp_rx_bytes += p->tot_len;
    if (stream->rx) pbuf_cat(stream->rx, p);
    else stream->rx = p;
    return ERR_OK; /* Receive credit is deliberately not restored until an application read. */
}
static void address_info(struct tcp_stream *stream) {
    struct tcp_pcb *pcb = stream->pcb;
    memcpy(stream->info.local, ip_2_ip6(&pcb->local_ip)->addr, 16);
    memcpy(stream->info.remote, ip_2_ip6(&pcb->remote_ip)->addr, 16);
    stream->info.local_port = pcb->local_port; stream->info.remote_port = pcb->remote_port;
}
static err_t connected(void *arg, struct tcp_pcb *pcb, err_t error) {
    struct tcp_stream *stream = arg;
    LWIP_ASSERT("connected PCB changed ownership", pcb == stream->pcb);
    if (error != ERR_OK) { stream->error = DP_INTERNAL; return ERR_OK; }
    address_info(stream);
    stream->connected = 1;
    return ERR_OK;
}
static err_t accepted(void *arg, struct tcp_pcb *pcb, err_t error) {
    struct tcp_listener *listener = arg;
    dp_core *core = listener->owner;
    if (!pcb) return ERR_OK;
    if (error != ERR_OK || listener->count >= listener->backlog || core->tcp_stream_count >= MEMP_NUM_TCP_PCB) {
        tcp_abort(pcb); return ERR_ABRT;
    }
    struct tcp_stream *stream = calloc(1, sizeof(*stream));
    if (!stream) { tcp_abort(pcb); return ERR_ABRT; }
    stream->owner = core; stream->pcb = pcb; stream->listener = listener;
    stream->next = core->tcp_streams; core->tcp_streams = stream;
    address_info(stream);
    stream->connected = 1;
    core->tcp_stream_count++; allocated(core, sizeof(*stream));
    tcp_arg(pcb, stream); tcp_recv(pcb, received); tcp_err(pcb, failed);
    tcp_nagle_disable(pcb); ip_set_option(pcb, SOF_KEEPALIVE);
    tcp_backlog_delayed(pcb);
    listener->pending[listener->count++] = stream;
    return ERR_OK;
}
static void release_listener(void *value) {
    struct tcp_listener *listener = value;
    dp_core *core = listener->owner;
    /* Unpublished children are not in the handle registry: release never reenters it. */
    while (listener->count) release_stream(listener->pending[--listener->count]);
    if (listener->pcb) {
        if (listener->pcb->state == LISTEN) {
            struct tcp_pcb *pcb = tcp_active_pcbs;
            while (pcb) {
                struct tcp_pcb *next = pcb->next;
                if (pcb->state == SYN_RCVD && pcb->listener == (struct tcp_pcb_listen *)listener->pcb) {
                    tcp_arg(pcb, NULL); tcp_err(pcb, NULL); tcp_abort(pcb);
                }
                pcb = next;
            }
            tcp_arg(listener->pcb, NULL); tcp_accept(listener->pcb, NULL);
            err_t status = tcp_close(listener->pcb);
            LWIP_ASSERT("listener close must not wait for memory", status == ERR_OK);
            (void)status;
        } else { tcp_arg(listener->pcb, NULL); tcp_err(listener->pcb, NULL); tcp_abort(listener->pcb); }
    }
    core->tcp_listener_count--;
    core->endpoint_allocated -= sizeof(*listener);
    free(listener);
}
dp_result dp_tcp_listen_with_options(dp_core *core, uint16_t port, unsigned backlog, int reuse, uint64_t *handle, uint16_t *actual) {
    if (handle) *handle = 0;
    if (actual) *actual = 0;
    if (!handle || !actual || !backlog || backlog > DP_TCP_MAX_BACKLOG || (reuse != 0 && reuse != 1)) return DP_INVALID_ARGUMENT;
    dp_result status = dp_enter(core, 0);
    if (status != DP_OK) return status;
    if (core->tcp_listener_count >= MEMP_NUM_TCP_PCB_LISTEN) return leave(core, DP_RESOURCE_LIMIT);
    struct tcp_listener *listener = calloc(1, sizeof(*listener));
    if (!listener) return leave(core, DP_RESOURCE_LIMIT);
    listener->owner = core; listener->backlog = backlog;
    core->tcp_listener_count++; allocated(core, sizeof(*listener));
    listener->pcb = tcp_new_ip_type(IPADDR_TYPE_V6);
    if (!listener->pcb) { release_listener(listener); return leave(core, DP_RESOURCE_LIMIT); }
    if (reuse) ip_set_option(listener->pcb, SOF_REUSEADDR);
    ip_addr_t local;
    ip_addr_copy_from_ip6(local, *netif_ip6_addr(&core->netif, 0));
    err_t error = tcp_bind(listener->pcb, &local, port);
    if (error != ERR_OK) {
        release_listener(listener);
        return leave(core, error == ERR_USE ? DP_ADDRESS_IN_USE : DP_RESOURCE_LIMIT);
    }
    tcp_bind_netif(listener->pcb, &core->netif);
    struct tcp_pcb *pcb = tcp_listen_with_backlog_and_err(listener->pcb, (uint8_t)backlog, &error);
    if (!pcb) { release_listener(listener); return leave(core, error == ERR_USE ? DP_ADDRESS_IN_USE : DP_RESOURCE_LIMIT); }
    listener->pcb = pcb;
    status = dp_endpoint_register(&core->endpoints, DP_ENDPOINT_LISTENER, listener, release_listener, handle);
    if (status != DP_OK) { release_listener(listener); return leave(core, status); }
    tcp_arg(pcb, listener); tcp_accept(pcb, accepted);
    *actual = pcb->local_port;
    return leave(core, DP_OK);
}
dp_result dp_tcp_listen(dp_core *core, uint16_t port, unsigned backlog, uint64_t *handle, uint16_t *actual) {
    return dp_tcp_listen_with_options(core, port, backlog, 0, handle, actual);
}
dp_result dp_tcp_accept(dp_core *core, uint64_t listener_handle, uint64_t *handle, dp_tcp_info *info) {
    if (handle) *handle = 0;
    if (info) memset(info, 0, sizeof(*info));
    if (!handle || !info) return DP_INVALID_ARGUMENT;
    dp_result status = dp_enter(core, 0);
    if (status != DP_OK) return status;
    void *value = NULL;
    status = dp_endpoint_lookup(&core->endpoints, listener_handle, DP_ENDPOINT_LISTENER, &value);
    if (status != DP_OK) return leave(core, status);
    struct tcp_listener *listener = value;
    if (!listener->count) return leave(core, DP_WOULD_BLOCK);
    struct tcp_stream *stream = listener->pending[0];
    status = dp_endpoint_register(&core->endpoints, DP_ENDPOINT_TCP, stream, release_stream, handle);
    if (status != DP_OK) return leave(core, status);
    memmove(listener->pending, listener->pending + 1, (--listener->count) * sizeof(listener->pending[0]));
    listener->pending[listener->count] = NULL;
    stream->listener = NULL;
    tcp_backlog_accepted(stream->pcb);
    *info = stream->info;
    return leave(core, DP_OK);
}
dp_result dp_tcp_read(dp_core *core, uint64_t handle, uint8_t *bytes, size_t capacity, size_t *copied) {
    if (copied) *copied = 0;
    if (!copied || (!bytes && capacity)) return DP_INVALID_ARGUMENT;
    dp_result status = dp_enter(core, 0);
    if (status != DP_OK) return status;
    void *value = NULL;
    status = dp_endpoint_lookup(&core->endpoints, handle, DP_ENDPOINT_TCP, &value);
    if (status != DP_OK) return leave(core, status);
    struct tcp_stream *stream = value;
    if (!capacity) return leave(core, DP_OK);
    if (stream->rx) {
        uint16_t count = (uint16_t)LWIP_MIN(capacity, stream->rx->tot_len);
        *copied = pbuf_copy_partial(stream->rx, bytes, count, 0);
        LWIP_ASSERT("TCP retained chain copy must be complete", *copied == count);
        stream->rx = pbuf_free_header(stream->rx, count);
        core->tcp_rx_bytes -= count;
        if (stream->pcb) tcp_recved(stream->pcb, count);
        return leave(core, DP_OK);
    }
    if (stream->error != DP_OK) return leave(core, stream->error);
    return leave(core, stream->eof ? DP_PEER_CLOSED : DP_WOULD_BLOCK);
}
static dp_result close_handle(dp_core *core, uint64_t handle, dp_endpoint_kind kind) {
    dp_result status = dp_enter(core, 1);
    if (status != DP_OK) return status;
    if (core->failure != DP_OK) core->link_ready = 0; /* Cleanup must not create a new output failure. */
    status = dp_endpoint_remove(&core->endpoints, handle, kind);
    return leave(core, status);
}
dp_result dp_tcp_connect(dp_core *core, const uint8_t address[16], uint16_t port, uint64_t *handle) {
    if (handle) *handle = 0;
    if (!address || !port || !handle) return DP_INVALID_ARGUMENT;
    uint8_t nonzero = 0;
    for (unsigned i = 0; i < 16; ++i) nonzero |= address[i];
    if (!nonzero || address[0] == 255) return DP_INVALID_ARGUMENT;
    dp_result status = dp_enter(core, 0);
    if (status != DP_OK) return status;
    if (!core->link_ready || !ip6_addr_isvalid(netif_ip6_addr_state(&core->netif, 0)))
        return leave(core, DP_WOULD_BLOCK);
    if (core->tcp_stream_count >= MEMP_NUM_TCP_PCB) return leave(core, DP_RESOURCE_LIMIT);
    struct tcp_stream *stream = calloc(1, sizeof(*stream));
    if (!stream) return leave(core, DP_RESOURCE_LIMIT);
    stream->owner = core;
    stream->next = core->tcp_streams; core->tcp_streams = stream;
    core->tcp_stream_count++; allocated(core, sizeof(*stream));
    stream->pcb = tcp_new_ip_type(IPADDR_TYPE_V6);
    if (!stream->pcb) { release_stream(stream); return leave(core, DP_RESOURCE_LIMIT); }
    tcp_arg(stream->pcb, stream); tcp_recv(stream->pcb, received); tcp_err(stream->pcb, failed);
    tcp_nagle_disable(stream->pcb); ip_set_option(stream->pcb, SOF_KEEPALIVE);
    tcp_bind_netif(stream->pcb, &core->netif);
    ip_addr_t local, remote;
    ip_addr_copy_from_ip6(local, *netif_ip6_addr(&core->netif, 0));
    ip_addr_set_zero_ip6(&remote);
    memcpy(ip_2_ip6(&remote)->addr, address, 16);
    ip6_addr_assign_zone(ip_2_ip6(&remote), IP6_UNICAST, &core->netif);
    err_t error = tcp_bind(stream->pcb, &local, 0);
    if (error != ERR_OK) {
        release_stream(stream);
        return leave(core, error == ERR_USE ? DP_ADDRESS_IN_USE : DP_RESOURCE_LIMIT);
    }
    uint64_t candidate = 0;
    status = dp_endpoint_register(&core->endpoints, DP_ENDPOINT_TCP, stream, release_stream, &candidate);
    if (status != DP_OK) { release_stream(stream); return leave(core, status); }
    error = tcp_connect(stream->pcb, &remote, port, connected);
    if (error != ERR_OK || core->failure != DP_OK) {
        status = core->failure != DP_OK ? core->failure :
                 (error == ERR_MEM || error == ERR_BUF ? DP_RESOURCE_LIMIT :
                  (error == ERR_USE ? DP_ADDRESS_IN_USE : DP_LINK_IO));
        dp_endpoint_remove(&core->endpoints, candidate, DP_ENDPOINT_TCP);
        return leave(core, status);
    }
    status = leave(core, DP_OK);
    if (status != DP_OK) { dp_tcp_close(core, candidate); return status; }
    *handle = candidate;
    return DP_OK;
}
dp_result dp_tcp_connect_status(dp_core *core, uint64_t handle, dp_tcp_info *info) {
    if (!info) return DP_INVALID_ARGUMENT;
    memset(info, 0, sizeof(*info));
    dp_result status = dp_enter(core, 0);
    if (status != DP_OK) return status;
    void *value = NULL;
    status = dp_endpoint_lookup(&core->endpoints, handle, DP_ENDPOINT_TCP, &value);
    if (status != DP_OK) return leave(core, status);
    struct tcp_stream *stream = value;
    if (stream->error != DP_OK) return leave(core, stream->error);
    if (!stream->connected) return leave(core, DP_WOULD_BLOCK);
    *info = stream->info;
    return leave(core, DP_OK);
}
dp_result dp_tcp_write(dp_core *core, uint64_t handle, const uint8_t *bytes, size_t length, size_t *copied) {
    if (copied) *copied = 0;
    if (!copied || (!bytes && length)) return DP_INVALID_ARGUMENT;
    dp_result status = dp_enter(core, 0);
    if (status != DP_OK) return status;
    void *value = NULL;
    status = dp_endpoint_lookup(&core->endpoints, handle, DP_ENDPOINT_TCP, &value);
    if (status != DP_OK) return leave(core, status);
    struct tcp_stream *stream = value;
    if (stream->error != DP_OK) return leave(core, stream->error);
    if (!stream->connected) return leave(core, DP_WOULD_BLOCK);
    if (stream->output_closed || !stream->pcb) return leave(core, DP_PEER_CLOSED);
    if (!length) return leave(core, DP_OK);
    if (!core->link_ready) return leave(core, DP_WOULD_BLOCK);
    uint16_t count = (uint16_t)LWIP_MIN(length, tcp_sndbuf(stream->pcb));
    if (!count) { tcp_output(stream->pcb); return leave(core, DP_WOULD_BLOCK); }
    err_t error = tcp_write(stream->pcb, bytes, count, TCP_WRITE_FLAG_COPY);
    if (error == ERR_MEM || error == ERR_BUF) {
        tcp_output(stream->pcb); return leave(core, DP_WOULD_BLOCK);
    }
    if (error != ERR_OK) return leave(core, error == ERR_CONN ? DP_PEER_CLOSED : DP_INTERNAL);
    *copied = count;
    /* Once copied, output backpressure cannot turn this prefix into a retryable write.
     * lwIP owns it and retries transmission. Fatal core output failures still propagate. */
    tcp_output(stream->pcb);
    return leave(core, DP_OK);
}
dp_result dp_tcp_shutdown_output(dp_core *core, uint64_t handle) {
    dp_result status = dp_enter(core, 0);
    if (status != DP_OK) return status;
    void *value = NULL;
    status = dp_endpoint_lookup(&core->endpoints, handle, DP_ENDPOINT_TCP, &value);
    if (status != DP_OK) return leave(core, status);
    struct tcp_stream *stream = value;
    if (stream->output_closed) return leave(core, DP_OK);
    if (stream->error != DP_OK) return leave(core, stream->error);
    if (!stream->connected) return leave(core, DP_WOULD_BLOCK);
    if (!stream->pcb) return leave(core, DP_PEER_CLOSED);
    err_t error = tcp_shutdown(stream->pcb, 0, 1);
    if (error != ERR_OK) return leave(core, error == ERR_MEM ? DP_WOULD_BLOCK : DP_PEER_CLOSED);
    stream->output_closed = 1;
    return leave(core, DP_OK);
}
dp_result dp_tcp_close(dp_core *core, uint64_t handle) { return close_handle(core, handle, DP_ENDPOINT_TCP); }
dp_result dp_tcp_configure_liveness(dp_core *core, uint64_t handle) {
    dp_result status = dp_enter(core, 0);
    if (status != DP_OK) return status;
    void *value = NULL;
    status = dp_endpoint_lookup(&core->endpoints, handle, DP_ENDPOINT_TCP, &value);
    if (status != DP_OK) return leave(core, status);
    struct tcp_stream *stream = value;
    if (stream->error != DP_OK) return leave(core, stream->error);
    if (!stream->pcb) return leave(core, DP_PEER_CLOSED);
    ip_set_option(stream->pcb, SOF_KEEPALIVE);
    stream->pcb->keep_idle = 10000;
    stream->pcb->keep_intvl = 3000;
    stream->pcb->keep_cnt = 3;
    stream->pcb->keep_cnt_sent = 0;
    return leave(core, DP_OK);
}
dp_result dp_tcp_close_graceful(dp_core *core, uint64_t handle) {
    dp_result status = dp_enter(core, 1);
    if (status != DP_OK) return status;
    if (core->failure != DP_OK) core->link_ready = 0;
    void *value = NULL;
    status = dp_endpoint_lookup(&core->endpoints, handle, DP_ENDPOINT_TCP, &value);
    if (status != DP_OK) return leave(core, status);
    struct tcp_stream *stream = value;
    err_t error = ERR_OK;
    if (core->failure == DP_OK && stream->connected && stream->pcb) {
        struct tcp_pcb *pcb = stream->pcb;
        /* tcp_close may free immediately, defer FIN under pressure, or leave a closing PCB.
         * In every successful case it owns the PCB. No application callback may survive. */
        detach(pcb);
        error = tcp_close(pcb);
        if (error != ERR_OK) tcp_abort(pcb); /* Failed close retains a valid PCB. */
        stream->pcb = NULL;
    }
    status = dp_endpoint_remove(&core->endpoints, handle, DP_ENDPOINT_TCP);
    return leave(core, error == ERR_OK ? status : DP_INTERNAL);
}
dp_result dp_tcp_listener_close(dp_core *core, uint64_t handle) { return close_handle(core, handle, DP_ENDPOINT_LISTENER); }
