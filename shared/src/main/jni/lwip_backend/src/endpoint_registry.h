#ifndef DP_ENDPOINT_REGISTRY_H
#define DP_ENDPOINT_REGISTRY_H
#include "diplay_lwip.h"
#define DP_ENDPOINT_CAPACITY 80
typedef enum { DP_ENDPOINT_TCP = 1, DP_ENDPOINT_LISTENER = 2, DP_ENDPOINT_UDP = 3 } dp_endpoint_kind;
typedef struct {
    uint64_t token;
    void *value;
    void (*release)(void *);
} dp_endpoint_slot;
typedef struct {
    dp_endpoint_slot slots[DP_ENDPOINT_CAPACITY];
    unsigned used;
} dp_endpoint_registry;
/* Native private API: zero-initialize; serialize on the owning core thread.
 * No raw pointer crosses the JNI boundary. A successful registration transfers
 * ownership; failure retains caller ownership. Release is nonblocking and must
 * not reenter this registry. Removed tokens are invalid, including repeat remove. */
dp_result dp_endpoint_register(dp_endpoint_registry *, dp_endpoint_kind, void *, void (*)(void *), uint64_t *);
dp_result dp_endpoint_lookup(dp_endpoint_registry *, uint64_t, dp_endpoint_kind, void **);
dp_result dp_endpoint_remove(dp_endpoint_registry *, uint64_t, dp_endpoint_kind);
void dp_endpoint_clear(dp_endpoint_registry *);
#endif
