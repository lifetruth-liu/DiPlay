/* Actual private NDP helpers; no product hooks or network traffic. */
#include "../third_party/lwip/src/core/ipv6/nd6.c"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define CHECK(c) do { if (!(c)) { fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #c); exit(1); } } while (0)
int main(void) {
    u8_t *bytes = calloc(1, 65535);
    CHECK(bytes != NULL);
    struct pbuf p;
    memset(&p, 0, sizeof(p));
    p.payload = bytes; p.len = p.tot_len = 65535;
    /* A truncated option near the end must not wrap the cursor and return an
     * unrelated option before the requested start position. */
    bytes[2] = ND6_OPTION_TYPE_SOURCE_LLADDR; bytes[3] = 1;
    bytes[65530] = 254; bytes[65531] = 1;
    CHECK(nd6_find_option(&p, 65530, ND6_OPTION_TYPE_SOURCE_LLADDR) == NULL);
    free(bytes);

    struct lladdr_option *opt = malloc(8);
    struct nd6_neighbor_cache_entry entry;
    struct netif netif;
    CHECK(opt != NULL);
    memset(opt, 0x42, 8); opt->length = 1;
    memset(&entry, 0xa5, sizeof(entry));
    memset(&netif, 0, sizeof(netif)); netif.hwaddr_len = 8;
    nd6_store_neighbor_lladdr(&entry, opt, &netif);
    for (unsigned i = 0; i < 6; ++i) CHECK(entry.lladdr[i] == 0x42);
    for (unsigned i = 6; i < NETIF_MAX_HWADDR_LEN; ++i) CHECK(entry.lladdr[i] == 0xa5);
    free(opt);
    puts("NDP option cursor and address-copy bounds passed");
    return 0;
}
