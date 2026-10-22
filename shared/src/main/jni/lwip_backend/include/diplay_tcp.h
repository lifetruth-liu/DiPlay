#ifndef DIPLAY_TCP_H
#define DIPLAY_TCP_H
#include "diplay_lwip.h"
#ifdef __cplusplus
extern "C" {
#endif
#define DP_TCP_MAX_BACKLOG 32u
#define DP_TCP_RX_BYTES (4u * 1024u * 1024u)
#define DP_TCP_TX_BYTES (2u * 1024u * 1024u)
typedef struct {
    uint8_t local[16], remote[16];
    uint16_t local_port, remote_port;
} dp_tcp_info;
/* Serialized owner-thread API. Queued accepts are owned by the listener until
 * publication. Read restores receive credit only for bytes actually consumed.
 * dp_tcp_close invalidates the generation and aborts locally without waiting for FIN. */
DP_API dp_result dp_tcp_listen(dp_core *, uint16_t, unsigned, uint64_t *, uint16_t *);
DP_API dp_result dp_tcp_accept(dp_core *, uint64_t, uint64_t *, dp_tcp_info *);
/* begin returns OK with an owned candidate token once a SYN is queued; poll that
 * token with connect_status instead of beginning again. Unready begin returns
 * WOULD_BLOCK with no token. Close cancels either pending or connected tokens. */
DP_API dp_result dp_tcp_connect(dp_core *, const uint8_t[16], uint16_t, uint64_t *);
DP_API dp_result dp_tcp_connect_status(dp_core *, uint64_t, dp_tcp_info *);
DP_API dp_result dp_tcp_read(dp_core *, uint64_t, uint8_t *, size_t, size_t *);
/* write reports a copied prefix; WOULD_BLOCK means no bytes were queued.
 * A successful write queues owned data, not necessarily a completed wire send. */
DP_API dp_result dp_tcp_write(dp_core *, uint64_t, const uint8_t *, size_t, size_t *);
DP_API dp_result dp_tcp_shutdown_output(dp_core *, uint64_t);
DP_API dp_result dp_tcp_configure_liveness(dp_core *, uint64_t);
DP_API dp_result dp_tcp_close(dp_core *, uint64_t);
/* Consume the application handle and hand closing PCB ownership to lwIP. */
DP_API dp_result dp_tcp_close_graceful(dp_core *, uint64_t);
DP_API dp_result dp_tcp_listener_close(dp_core *, uint64_t);
#ifdef __cplusplus
}
#endif
#endif
