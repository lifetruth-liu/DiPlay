#include "endpoint_services.h"
#include "diplay_tcp.h"
#include "diplay_endpoints.h"
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define STREAMS 8u
struct stream { uint64_t token; size_t length, offset; uint8_t bytes[4096]; };
struct endpoint_services {
    uint64_t listener, udp, control, active;
    struct stream streams[STREAMS];
    uint8_t datagram[DP_UDP_MAX_PAYLOAD], reply[16];
    dp_udp_datagram source;
    dp_udp_datagram control_peer;
    int udp_pending, control_reply, requested, connected, half_closed, verified;
    size_t sent, received;
    uint64_t accepted, read_bytes, written_bytes, udp_received, udp_sent;
};
_Static_assert(sizeof(endpoint_services) <= 128 * 1024, "endpoint test application budget");
static const uint8_t peer[16] = {0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2};
static const uint8_t local[16] = {0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
static const uint8_t challenge[] = "DiPlay-active-v1";
static int error(const char *operation, dp_result status) {
    fprintf(stderr, "endpoint-api %s: status=%d\n", operation, status); return -1;
}
endpoint_services *endpoint_services_create(dp_core *core) {
    endpoint_services *s = calloc(1, sizeof(*s));
    if (!s) return NULL;
    uint16_t port;
    dp_result status = dp_tcp_listen_with_options(core, 7000, STREAMS, 1, &s->listener, &port);
    if (status == DP_OK && port == 7000) status = dp_udp_bind(core, 6000, &s->udp, &port);
    if (status == DP_OK && port == 6000) status = dp_udp_bind(core, 6002, &s->control, &port);
    if (status == DP_OK && port == 6002) return s;
    error("create", status); endpoint_services_destroy(core, s); return NULL;
}
static int active(dp_core *core, endpoint_services *s) {
    if (!s->requested || s->verified) return 0;
    int progress = 0; dp_result status;
    if (!s->active) {
        status = dp_tcp_connect(core, peer, 9000, &s->active);
        if (status == DP_WOULD_BLOCK) return 0;
        if (status != DP_OK) return error("connect", status);
        progress = 1;
    }
    if (!s->connected) {
        dp_tcp_info info;
        status = dp_tcp_connect_status(core, s->active, &info);
        if (status == DP_WOULD_BLOCK) return progress;
        if (status != DP_OK || !info.local_port || memcmp(info.local, local, 16) ||
            info.remote_port != 9000 || memcmp(info.remote, peer, 16)) return error("connect status", status);
        s->connected = 1; progress = 1;
    }
    size_t count = 0;
    if (s->sent < sizeof(challenge) - 1) {
        status = dp_tcp_write(core, s->active, challenge + s->sent, sizeof(challenge) - 1 - s->sent, &count);
        if (status == DP_OK && count && count <= sizeof(challenge) - 1 - s->sent) { s->sent += count; progress = 1; }
        else if (status != DP_WOULD_BLOCK || count) return error("active write", status);
    }
    if (s->sent == sizeof(challenge) - 1 && !s->half_closed) {
        status = dp_tcp_shutdown_output(core, s->active);
        if (status == DP_OK) { s->half_closed = 1; progress = 1; }
        else if (status != DP_WOULD_BLOCK) return error("active half close", status);
    }
    uint8_t extra;
    size_t remaining = sizeof(s->reply) - s->received;
    status = dp_tcp_read(core, s->active, remaining ? s->reply + s->received : &extra, remaining ? remaining : 1, &count);
    if (status == DP_OK) {
        if (!count || count > remaining) return error("active reply length", status);
        s->received += count; progress = 1;
    } else if (status == DP_PEER_CLOSED) {
        if (s->received != sizeof(challenge) - 1 || memcmp(s->reply, challenge, sizeof(challenge) - 1)) return error("active reply content", status);
        status = dp_tcp_close_graceful(core, s->active); s->active = 0;
        if (status != DP_OK) return error("active close", status);
        s->verified = 1; fprintf(stderr, "endpoint-active: verified\n"); progress = 1;
    } else if (status != DP_WOULD_BLOCK) return error("active read", status);
    return progress;
}
int endpoint_services_pump(dp_core *core, endpoint_services *s) {
    int progress = 0;
    for (unsigned i = 0; i < STREAMS; ++i) {
        struct stream *stream = &s->streams[i]; size_t count = 0;
        if (!stream->token) {
            dp_tcp_info info; dp_result status = dp_tcp_accept(core, s->listener, &stream->token, &info);
            if (status == DP_WOULD_BLOCK) continue;
            if (status != DP_OK) return error("accept", status);
            if (info.local_port != 7000 || memcmp(info.local, local, 16) || !info.remote_port || memcmp(info.remote, peer, 16)) return error("accepted peer", status);
            s->accepted++; progress = 1;
        }
        dp_result status;
        if (stream->offset < stream->length) {
            status = dp_tcp_write(core, stream->token, stream->bytes + stream->offset, stream->length - stream->offset, &count);
            if (status == DP_OK && count && count <= stream->length - stream->offset) {
                stream->offset += count; s->written_bytes += count; progress = 1;
            } else if (status == DP_PEER_RESET || status == DP_PEER_CLOSED) {
                dp_result closed = dp_tcp_close(core, stream->token); memset(stream, 0, sizeof(*stream));
                if (closed != DP_OK) return error("reset close", closed);
                progress = 1; continue;
            } else if (status != DP_WOULD_BLOCK || count) return error("write", status);
        }
        if (stream->offset != stream->length) continue;
        status = dp_tcp_read(core, stream->token, stream->bytes, sizeof(stream->bytes), &count);
        if (status == DP_OK && count && count <= sizeof(stream->bytes)) {
            stream->length = count; stream->offset = 0; s->read_bytes += count; progress = 1;
        } else if (status == DP_PEER_CLOSED || status == DP_PEER_RESET) {
            dp_result closed = status == DP_PEER_CLOSED ? dp_tcp_close_graceful(core, stream->token) : dp_tcp_close(core, stream->token);
            memset(stream, 0, sizeof(*stream));
            if (closed != DP_OK) return error("close", closed);
            progress = 1;
        } else if (status != DP_WOULD_BLOCK || count) return error("read", status);
    }
    dp_result status;
    if (s->udp_pending) {
        status = dp_udp_send(core, s->udp, s->source.source, s->source.source_port, s->datagram, s->source.copied_length);
        if (status == DP_OK) { s->udp_pending = 0; s->udp_sent++; progress = 1; }
        else if (status != DP_WOULD_BLOCK) return error("UDP send", status);
    }
    if (!s->udp_pending) {
        status = dp_udp_receive(core, s->udp, s->datagram, sizeof(s->datagram), &s->source);
        if (status == DP_OK) {
            if (s->source.original_length != s->source.copied_length || memcmp(s->source.source, peer, 16)) return error("UDP metadata", status);
            s->udp_pending = 1; s->udp_received++; progress = 1;
        } else if (status != DP_WOULD_BLOCK) return error("UDP receive", status);
    }
    uint8_t command[16]; dp_udp_datagram meta;
    status = dp_udp_receive(core, s->control, command, sizeof(command), &meta);
    if (status == DP_OK) {
        if (meta.original_length != 11 || meta.copied_length != 11 || memcmp(command, "CONNECT9000", 11) || memcmp(meta.source, peer, 16)) return error("control", status);
        s->requested = 1; progress = 1;
        s->control_peer = meta; s->control_reply = 1;
    } else if (status != DP_WOULD_BLOCK) return error("control receive", status);
    if (s->control_reply) {
        status = dp_udp_send(core, s->control, s->control_peer.source, s->control_peer.source_port, (const uint8_t *)"READY", 5);
        if (status == DP_OK) { s->control_reply = 0; progress = 1; }
        else if (status != DP_WOULD_BLOCK) return error("control ACK", status);
    }
    int connected = active(core, s);
    return connected < 0 ? -1 : (progress || connected);
}
int endpoint_services_verified(const endpoint_services *s) { return s->verified; }
int endpoint_services_destroy(dp_core *core, endpoint_services *s) {
    int failed = 0;
    fprintf(stderr, "endpoint-active-state: requested=%d connected=%d sent=%zu received=%zu\n", s->requested, s->connected, s->sent, s->received);
    for (unsigned i = 0; i < STREAMS; ++i) if (s->streams[i].token && dp_tcp_close(core, s->streams[i].token) != DP_OK) failed = 1;
    if (s->active && dp_tcp_close(core, s->active) != DP_OK) failed = 1;
    if (s->listener && dp_tcp_listener_close(core, s->listener) != DP_OK) failed = 1;
    if (s->udp && dp_udp_close(core, s->udp) != DP_OK) failed = 1;
    if (s->control && dp_udp_close(core, s->control) != DP_OK) failed = 1;
    fprintf(stderr, "endpoint-api: accepted=%" PRIu64 " read=%" PRIu64 " written=%" PRIu64
            " udp_received=%" PRIu64 " udp_sent=%" PRIu64 " app_bytes=%zu\n",
            s->accepted, s->read_bytes, s->written_bytes, s->udp_received, s->udp_sent, sizeof(*s));
    free(s); return failed ? -1 : 0;
}
