#ifndef DIPLAY_LWIP_H
#define DIPLAY_LWIP_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
#if defined(__GNUC__)
#define DP_API __attribute__((visibility("default")))
#else
#define DP_API
#endif
typedef struct dp_core dp_core;
typedef enum {
    DP_OK = 0,
    DP_INVALID_ARGUMENT = -1,
    DP_RESOURCE_LIMIT = -2,
    DP_WRONG_THREAD = -3,
    DP_REENTRANT = -4,
    DP_ADDRESS_CONFLICT = -5,
    DP_INTERNAL = -6,
    DP_UNSUPPORTED = -7,
    DP_CLOCK_REVERSED = -8,
    DP_LINK_IO = -9,
    DP_ADDRESS_IN_USE = -10,
    DP_WOULD_BLOCK = -11,
    DP_PEER_CLOSED = -12,
    DP_PEER_RESET = -13
} dp_result;
typedef enum { DP_TX_FAILED = -1, DP_TX_NOT_READY = 0, DP_TX_ACCEPTED = 1 } dp_tx_result;
typedef enum {
    DP_ADDRESS_TENTATIVE = 0,
    DP_ADDRESS_PREFERRED = 1,
    DP_ADDRESS_DUPLICATE = 2
} dp_address_state;
typedef struct {
    uint8_t mac[6];
    uint8_t ipv6[16];
    uint16_t mtu; /* 1280..1500; this prototype accepts untagged Ethernet IPv6 only. */
    void *opaque;
    uint64_t (*clock_ms)(void *opaque); /* Monotonic, nonblocking. */
    uint32_t (*random32)(void *opaque); /* Production caller must provide secure randomness. */
    dp_tx_result (*output)(void *opaque, const uint8_t *frame, size_t length);
} dp_config;
typedef struct {
    uint64_t rx_frames, rx_rejected, tx_accepted, tx_not_ready, tx_failed;
    uint32_t heap_used, heap_peak, pbuf_used, configured_bytes;
    uint32_t tcp_active, tcp_listeners, udp_pcbs;
    dp_result failure;
} dp_stats;
/* Prototype C ABI, not a Java handle ABI. No pointer is exposed to Kotlin. One live context
 * per process; every operation including destroy must run on the creating core thread.
 * Callbacks must not block or reenter. output borrows bytes only until it returns; ACCEPTED
 * means copied into a bounded TX queue, not delivered over USB. create/listen do not wait
 * for link readiness. Link-down keeps DAD tentative until the transport becomes writable. */
DP_API uint32_t dp_core_abi_version(void);
DP_API dp_result dp_core_create(const dp_config *config, dp_core **result);
DP_API dp_result dp_core_destroy(dp_core **core);
DP_API dp_result dp_core_set_link_ready(dp_core *core, int ready);
DP_API dp_result dp_core_input(dp_core *core, const uint8_t *frame, size_t length);
DP_API dp_result dp_core_poll(dp_core *core);
DP_API dp_result dp_core_next_timeout(dp_core *core, uint32_t *millis);
DP_API dp_result dp_core_address_state(dp_core *core, dp_address_state *state);
DP_API dp_result dp_core_get_stats(dp_core *core, dp_stats *stats);
/* Native-only short command boundary. No Java callback or blocking USB work is allowed here. */
DP_API dp_result dp_core_execute(dp_core *core, int (*command)(void *), void *arg);
#ifdef __cplusplus
}
#endif
#endif
