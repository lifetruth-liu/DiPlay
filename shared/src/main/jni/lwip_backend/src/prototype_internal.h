#ifndef DP_PROTOTYPE_INTERNAL_H
#define DP_PROTOTYPE_INTERNAL_H
#include "diplay_prototype.h"
/* C-only temporary copy area. Owner-thread/context validation precedes access;
 * no pointer from this helper may escape through JNI or survive a JNI call. */
dp_result dp_proto_udp_buffer(uint64_t context, uint8_t **bytes);
#endif
