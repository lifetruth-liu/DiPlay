/* P2 virtual-netif echo service, started only by the explicit prototype API. */

#include "lwip/tcp.h"
#include "lwip/udp.h"

#include <stdlib.h>

static err_t echo_tcp(void *arg, struct tcp_pcb *pcb, struct pbuf *p, err_t error) {
    (void)arg;
    (void)error;
    if (!p) {
        /* Prototype echo has no half-close API; production endpoints are implemented separately. */
        tcp_abort(pcb);
        return ERR_ABRT;
    }
    if (tcp_sndbuf(pcb) < p->tot_len)
        return ERR_MEM;
    void *bytes = malloc(p->tot_len);
    if (!bytes)
        return ERR_MEM;
    pbuf_copy_partial(p, bytes, p->tot_len, 0);
    err_t result = tcp_write(pcb, bytes, p->tot_len, TCP_WRITE_FLAG_COPY);
    free(bytes);
    if (result != ERR_OK)
        return result;
    tcp_recved(pcb, p->tot_len);
    pbuf_free(p);
    tcp_output(pcb);
    return ERR_OK;
}

static err_t accepted(void *arg, struct tcp_pcb *pcb, err_t error) {
    (void)arg;
    if (error != ERR_OK)
        return error;
    tcp_backlog_accepted(pcb);
    tcp_recv(pcb, echo_tcp);
    return ERR_OK;
}

static void echo_udp(void *arg, struct udp_pcb *pcb, struct pbuf *p, const ip_addr_t *address,
                     u16_t port) {
    (void)arg;
    if (p) {
        udp_sendto(pcb, p, address, port);
        pbuf_free(p);
    }
}

int dp_prototype_echo_start(void *unused) {
    (void)unused;
    struct tcp_pcb *tcp = tcp_new_ip_type(IPADDR_TYPE_V6);
    if (!tcp) {
        return -1;
    }
    err_t bind_result = tcp_bind(tcp, IP6_ADDR_ANY, 7000);
    if (bind_result != ERR_OK) {
        tcp_abort(tcp);
        return -1;
    }
    struct tcp_pcb *listener = tcp_listen_with_backlog(tcp, 4);
    if (!listener) {
        tcp_abort(tcp);
        return -1;
    }
    tcp_accept(listener, accepted);
    struct udp_pcb *udp = udp_new_ip_type(IPADDR_TYPE_V6);
    if (!udp) {
        return -1;
    }
    bind_result = udp_bind(udp, IP6_ADDR_ANY, 6000);
    if (bind_result != ERR_OK) {
        udp_remove(udp);
        return -1;
    }
    udp_recv(udp, echo_udp, NULL);
    return 0;
}
