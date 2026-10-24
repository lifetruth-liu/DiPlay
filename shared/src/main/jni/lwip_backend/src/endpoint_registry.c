#include "endpoint_registry.h"
#include <stdatomic.h>
#include <string.h>
#define ENDPOINT_TAG UINT64_C(0x4550000000000000)
static atomic_uint_least32_t generation;
_Static_assert(DP_ENDPOINT_CAPACITY < 256, "endpoint slot must fit token");
static int valid_kind(dp_endpoint_kind kind) {
    return kind == DP_ENDPOINT_TCP || kind == DP_ENDPOINT_LISTENER || kind == DP_ENDPOINT_UDP;
}
dp_result dp_endpoint_register(dp_endpoint_registry *registry, dp_endpoint_kind kind,
                               void *value, void (*release)(void *), uint64_t *handle) {
    if (!handle) return DP_INVALID_ARGUMENT;
    *handle = 0;
    if (!registry || !valid_kind(kind) || !value || !release) return DP_INVALID_ARGUMENT;
    unsigned index;
    for (index = 0; index < DP_ENDPOINT_CAPACITY; ++index)
        if (!registry->slots[index].token) break;
    if (index == DP_ENDPOINT_CAPACITY) return DP_RESOURCE_LIMIT;
    uint_least32_t previous = atomic_load(&generation);
    do {
        if (previous == UINT32_MAX) return DP_RESOURCE_LIMIT;
    } while (!atomic_compare_exchange_weak(&generation, &previous, previous + 1));
    uint64_t token = ENDPOINT_TAG | ((uint64_t)(previous + 1) << 16) |
                     ((uint64_t)kind << 8) | (index + 1);
    registry->slots[index] = (dp_endpoint_slot){token, value, release};
    registry->used++;
    *handle = token;
    return DP_OK;
}
static dp_endpoint_slot *find(dp_endpoint_registry *registry, uint64_t handle, dp_endpoint_kind kind) {
    unsigned index = (unsigned)(handle & 255);
    if (!registry || !valid_kind(kind) || (handle & UINT64_C(0xffff000000000000)) != ENDPOINT_TAG ||
        ((handle >> 8) & 255) != (unsigned)kind || !index || index > DP_ENDPOINT_CAPACITY)
        return NULL;
    dp_endpoint_slot *slot = &registry->slots[index - 1];
    return slot->token == handle ? slot : NULL;
}
dp_result dp_endpoint_lookup(dp_endpoint_registry *registry, uint64_t handle, dp_endpoint_kind kind, void **value) {
    if (!value) return DP_INVALID_ARGUMENT;
    *value = NULL;
    dp_endpoint_slot *slot = find(registry, handle, kind);
    if (!slot) return DP_INVALID_ARGUMENT;
    *value = slot->value;
    return DP_OK;
}
dp_result dp_endpoint_remove(dp_endpoint_registry *registry, uint64_t handle, dp_endpoint_kind kind) {
    dp_endpoint_slot *slot = find(registry, handle, kind);
    if (!slot) return DP_INVALID_ARGUMENT;
    dp_endpoint_slot removed = *slot;
    memset(slot, 0, sizeof(*slot));
    registry->used--;
    removed.release(removed.value);
    return DP_OK;
}
void dp_endpoint_clear(dp_endpoint_registry *registry) {
    if (!registry) return;
    for (unsigned i = 0; i < DP_ENDPOINT_CAPACITY; ++i) {
        dp_endpoint_slot *slot = &registry->slots[i];
        if (slot->token) {
            dp_endpoint_slot removed = *slot;
            memset(slot, 0, sizeof(*slot));
            registry->used--;
            removed.release(removed.value);
        }
    }
}
