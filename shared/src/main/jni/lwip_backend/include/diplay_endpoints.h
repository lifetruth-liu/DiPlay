#ifndef DIPLAY_ENDPOINTS_H
#define DIPLAY_ENDPOINTS_H
#include "diplay_lwip.h"
#ifdef __cplusplus
extern "C" {
#endif
#define DP_UDP_QUEUE_BYTES 65536u
#define DP_UDP_GLOBAL_QUEUE_BYTES (512u * 1024u)
#define DP_UDP_QUEUE_PACKETS 128u
#define DP_UDP_MAX_PAYLOAD 65527u
typedef struct {
    uint8_t source[16];
    uint16_t source_port, original_length, copied_length;
} dp_udp_datagram;
typedef struct { uint32_t packets, bytes; uint64_t dropped; } dp_udp_queue_stats;
/* Native core-thread API. Calls are nonblocking; empty receive returns WOULD_BLOCK.
 * All payload/address buffers are borrowed only during the call; queues own copies.
 * Handles belong to this core generation. No native pointer is a JNI token. */
DP_API dp_result dp_udp_bind(dp_core *, uint16_t port, uint64_t *handle, uint16_t *actual_port);
DP_API dp_result dp_udp_send(dp_core *, uint64_t, const uint8_t destination[16], uint16_t port,
                             const uint8_t *payload, size_t length);
DP_API dp_result dp_udp_receive(dp_core *, uint64_t, uint8_t *target, size_t capacity, dp_udp_datagram *);
DP_API dp_result dp_udp_get_queue_stats(dp_core *, uint64_t, dp_udp_queue_stats *);
/* Opt-in per-channel age budget; zero preserves datagrams until receive/capacity pressure.
 * Age uses the core's 64-bit monotonic clock. Setting a budget also prunes existing packets. */
DP_API dp_result dp_udp_set_queue_max_age(dp_core *, uint64_t, uint32_t max_age_millis);
DP_API dp_result dp_udp_get_expired_count(dp_core *, uint64_t, uint64_t *expired);
DP_API dp_result dp_udp_close(dp_core *, uint64_t);
#ifdef __cplusplus
}
#endif
#endif
