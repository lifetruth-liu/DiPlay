#ifndef DIPLAY_LWIP_CC_H
#define DIPLAY_LWIP_CC_H
#include <stdint.h>
uint32_t dp_platform_random32(void);
void dp_platform_assert(const char *message, const char *file, int line);
void dp_platform_assert_core(void);
#define LWIP_RAND() dp_platform_random32()
#define LWIP_PLATFORM_DIAG(x)                                                                      \
    do {                                                                                           \
    } while (0)
#define LWIP_PLATFORM_ASSERT(x) dp_platform_assert((x), __FILE__, __LINE__)
#define LWIP_ASSERT_CORE_LOCKED() dp_platform_assert_core()
#endif
