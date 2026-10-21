#include "internal.h"
#include "lwip/ethip6.h"
#include "netif/ethernet.h"
#include <string.h>

static err_t output(struct netif *netif, struct pbuf *p) {
    dp_core *core = netif->state;
    uint8_t frame[1514];
    if (p->tot_len > sizeof(frame)) {
        core->stats.tx_failed++;
        return ERR_IF;
    }
    if (!core->link_ready) {
        core->stats.tx_not_ready++;
        return ERR_IF;
    }
    pbuf_copy_partial(p, frame, p->tot_len, 0);
    dp_tx_result result = core->config.output(core->config.opaque, frame, p->tot_len);
    if (result == DP_TX_ACCEPTED) {
        core->stats.tx_accepted++;
        return ERR_OK;
    }
    if (result == DP_TX_NOT_READY) {
        core->stats.tx_not_ready++;
        /* Apply after the stack callback unwinds; an unsent DAD probe must not validate us. */
        core->link_ready = 0;
        core->pending_link_down = 1;
    } else {
        core->stats.tx_failed++;
        core->failure = DP_LINK_IO;
    }
    return ERR_IF;
}
static err_t initialize(struct netif *netif) {
    dp_core *core = netif->state;
    netif->name[0] = 'd';
    netif->name[1] = 'p';
    netif->hwaddr_len = 6;
    memcpy(netif->hwaddr, core->config.mac, 6);
    netif->mtu = core->config.mtu;
    netif->flags = NETIF_FLAG_ETHERNET | NETIF_FLAG_MLD6;
    netif->output_ip6 = ethip6_output;
    netif->linkoutput = output;
    return ERR_OK;
}
dp_result dp_netif_init(dp_core *core) {
    if (!netif_add_noaddr(&core->netif, core, initialize, ethernet_input))
        return DP_INTERNAL;
    ip6_addr_t address;
    ip6_addr_set_zero(&address);
    memcpy(address.addr, core->config.ipv6, 16);
    ip6_addr_assign_zone(&address, IP6_UNICAST, &core->netif);
    netif_ip6_addr_set(&core->netif, 0, &address);
    netif_ip6_addr_set_state(&core->netif, 0, IP6_ADDR_TENTATIVE);
    netif_set_default(&core->netif);
    netif_set_up(&core->netif);
    return DP_OK;
}
