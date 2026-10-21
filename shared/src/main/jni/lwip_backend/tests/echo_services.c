/* Test-only raw API consumers. These are not compiled into the Android library. */
#include "echo_services.h"
#include "lwip/tcp.h"
#include "lwip/udp.h"
#include <stdio.h>
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

int echo_services_start(void *unused) {
    (void)unused;
    struct tcp_pcb *tcp = tcp_new_ip_type(IPADDR_TYPE_V6);
    if (!tcp) {
        fprintf(stderr, "tcp_new failed\n");
        return -1;
    }
    err_t bind_result = tcp_bind(tcp, IP6_ADDR_ANY, 7000);
    if (bind_result != ERR_OK) {
        fprintf(stderr, "tcp_bind failed %d\n", bind_result);
        tcp_abort(tcp);
        return -1;
    }
    struct tcp_pcb *listener = tcp_listen_with_backlog(tcp, 4);
    if (!listener) {
        fprintf(stderr, "tcp_listen failed\n");
        tcp_abort(tcp);
        return -1;
    }
    tcp_accept(listener, accepted);
    struct udp_pcb *udp = udp_new_ip_type(IPADDR_TYPE_V6);
    if (!udp) {
        fprintf(stderr, "udp_new failed\n");
        return -1;
    }
    bind_result = udp_bind(udp, IP6_ADDR_ANY, 6000);
    if (bind_result != ERR_OK) {
        fprintf(stderr, "udp_bind failed %d\n", bind_result);
        udp_remove(udp);
        return -1;
    }
    udp_recv(udp, echo_udp, NULL);
    return 0;
}
