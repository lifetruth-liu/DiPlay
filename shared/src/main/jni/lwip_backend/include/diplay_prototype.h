#ifndef DIPLAY_PROTOTYPE_H
#define DIPLAY_PROTOTYPE_H
#include "diplay_lwip.h"
#include "diplay_endpoints.h"
#include "diplay_tcp.h"
#define DP_PROTO_COPY_BYTES DP_UDP_MAX_PAYLOAD
#define DP_PROTO_ENTROPY_BYTES 1024
#define DP_PROTO_TX_FRAMES 128
typedef struct {
    dp_stats core;
    uint32_t tx_frames, entropy_remaining, configured_bytes;
    uint64_t tx_rejected;
    dp_result failure;
} dp_proto_stats;
/* Generation token, never a pointer. All calls belong to the creating thread.
 * Entropy must be securely generated outside raw callbacks. Exhaustion is terminal.
 * Output is copied into a fixed queue, then transferred by take_output; no Java callback. */
DP_API dp_result dp_proto_create(const uint8_t mac[6], const uint8_t ip[16], uint16_t mtu,
                                 uint64_t now_ms, const uint8_t *entropy, size_t entropy_length,
                                 uint64_t *handle);
DP_API dp_result dp_proto_supply(uint64_t handle, uint64_t now_ms, const uint8_t *entropy,
                                 size_t entropy_length);
DP_API dp_result dp_proto_prepare_echo(uint64_t handle);
DP_API dp_result dp_proto_set_link_ready(uint64_t handle, int ready);
DP_API dp_result dp_proto_input(uint64_t handle, const uint8_t *frame, size_t length);
DP_API dp_result dp_proto_poll(uint64_t handle);
DP_API dp_result dp_proto_next_timeout(uint64_t handle, uint32_t *timeout);
DP_API dp_result dp_proto_take_output(uint64_t handle, uint8_t *target, size_t capacity,
                                      size_t *length);
DP_API dp_result dp_proto_get_stats(uint64_t handle, dp_proto_stats *stats);
DP_API int32_t dp_proto_entropy_remaining(uint64_t handle);
DP_API dp_result dp_proto_destroy(uint64_t *handle);
DP_API dp_result dp_proto_udp_bind(uint64_t context, uint16_t port, uint64_t *endpoint, uint16_t *actual);
DP_API dp_result dp_proto_udp_send(uint64_t context, uint64_t endpoint, const uint8_t destination[16], uint16_t port,
                                   const uint8_t *payload, size_t length);
DP_API dp_result dp_proto_udp_receive(uint64_t context, uint64_t endpoint, uint8_t *target, size_t capacity, dp_udp_datagram *info);
DP_API dp_result dp_proto_udp_close(uint64_t context, uint64_t endpoint);
DP_API dp_result dp_proto_tcp_listen(uint64_t, uint16_t, unsigned, uint64_t *, uint16_t *);
DP_API dp_result dp_proto_tcp_accept(uint64_t, uint64_t, uint64_t *, dp_tcp_info *);
DP_API dp_result dp_proto_tcp_connect(uint64_t, const uint8_t[16], uint16_t, uint64_t *);
DP_API dp_result dp_proto_tcp_connect_status(uint64_t, uint64_t, dp_tcp_info *);
DP_API dp_result dp_proto_tcp_read(uint64_t, uint64_t, uint8_t *, size_t, size_t *);
DP_API dp_result dp_proto_tcp_write(uint64_t, uint64_t, const uint8_t *, size_t, size_t *);
DP_API dp_result dp_proto_tcp_shutdown_output(uint64_t, uint64_t);
DP_API dp_result dp_proto_tcp_close(uint64_t, uint64_t);
DP_API dp_result dp_proto_tcp_close_graceful(uint64_t, uint64_t);
DP_API dp_result dp_proto_tcp_listener_close(uint64_t, uint64_t);
#ifdef DP_PROTO_TESTING
dp_tx_result dp_proto_test_emit(uint64_t handle, const uint8_t *bytes, size_t length);
uint32_t dp_proto_test_random(uint64_t handle);
void dp_proto_test_generation(uint32_t value);
#endif
#endif
