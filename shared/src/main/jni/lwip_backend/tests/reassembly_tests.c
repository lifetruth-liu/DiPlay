/* Exercise the actual reassembly allocator and timer, including private queue
 * invariants, without adding hooks to product code. */
#include "../third_party/lwip/src/core/ipv6/ip6_frag.c"
#include "diplay_lwip.h"
#include "lwip/stats.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define CHECK(c) do { if (!(c)) { fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #c); exit(1); } } while (0)
static uint64_t clock_ms(void *arg) { (void)arg; return 0; }
static uint32_t random32(void *arg) { (void)arg; return 1234567; }
static dp_tx_result output(void *arg, const uint8_t *data, size_t length) {
    (void)arg; (void)data; (void)length; return DP_TX_NOT_READY;
}
static void reject_over_quota(unsigned id) {
    struct pbuf *p = pbuf_alloc(PBUF_RAW, IP6_HLEN + IP6_FRAG_HLEN + 8, PBUF_POOL);
    CHECK(p != NULL);
    memset(p->payload, 0, p->len);
    for (unsigned i = 0; i < IP_REASS_MAX_PBUFS; ++i) {
        struct pbuf *tail = pbuf_alloc(PBUF_RAW, 8, PBUF_POOL);
        CHECK(tail != NULL);
        memset(tail->payload, 0, tail->len);
        pbuf_cat(p, tail);
    }
    CHECK(pbuf_clen(p) == IP_REASS_MAX_PBUFS + 1);
    struct ip6_hdr *header = p->payload;
    header->_plen = lwip_htons(p->tot_len - IP6_HLEN);
    ip_data.current_ip6_header = header;
    CHECK(pbuf_remove_header(p, IP6_HLEN) == 0);
    struct ip6_frag_hdr *fragment = p->payload;
    fragment->_nexth = IP6_NEXTH_UDP;
    fragment->_fragment_offset = lwip_htons(1);
    fragment->_identification = lwip_htonl(id);
    CHECK(ip6_reass(p) == NULL); /* Reassembly owns and frees the rejected chain. */
    ip_data.current_ip6_header = NULL;
    CHECK(reassdatagrams != NULL && reassdatagrams->p == NULL);
    CHECK(ip6_reass_pbufcount == 0);
    CHECK(lwip_stats.memp[MEMP_PBUF_POOL]->used == 0);
}
static int exercise(void *arg) {
    (void)arg;
    for (unsigned repeat = 0; repeat < 8; ++repeat) {
        reject_over_quota(repeat + 1);
        for (unsigned tick = 0; tick <= IPV6_REASS_MAXAGE; ++tick) ip6_reass_tmr();
        CHECK(reassdatagrams == NULL);
        CHECK(lwip_stats.memp[MEMP_IP6_REASSDATA]->used == 0);
    }
    return 0;
}
int main(void) {
    dp_core *core = NULL;
    dp_config config = {0};
    config.mac[0] = 2; config.mac[5] = 1;
    config.ipv6[0] = 0xfe; config.ipv6[1] = 0x80; config.ipv6[15] = 1;
    config.mtu = 1500; config.clock_ms = clock_ms;
    config.random32 = random32; config.output = output;
    CHECK(dp_core_create(&config, &core) == DP_OK);
    CHECK(dp_core_execute(core, exercise, NULL) == DP_OK);
    CHECK(dp_core_destroy(&core) == DP_OK);
    puts("Empty IPv6 reassembly contexts expire and release their pool entries");
    return 0;
}
