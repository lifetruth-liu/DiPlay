#define _POSIX_C_SOURCE 200809L
#define _DEFAULT_SOURCE
#include "diplay_lwip.h"
#include "echo_services.h"
#include "lwip/priv/tcp_priv.h"
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/if_tun.h>
#include <net/ethernet.h>
#include <net/if.h>
#include <netpacket/packet.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

/* Test tool only: 256 * 1514-byte frames, never more than the 512 KiB TX budget.
 * Raw callbacks only copy into this queue. File I/O, logging and waiting stay outside lwIP. */
#define TX_CAPACITY 256
struct frame {
    size_t length;
    uint8_t bytes[1514];
};
struct port {
    int fd, control, random_fd, raw, paused;
    struct ifreq interface;
    size_t head, count, random_remaining;
    unsigned max_refused, sndbuf_at_max_refused, zero_window_samples;
    uint32_t random[512];
    struct frame tx[TX_CAPACITY];
};
static int inspect_pressure(void *arg) {
    struct port *port = arg;
    for (struct tcp_pcb *pcb = tcp_active_pcbs; pcb; pcb = pcb->next) {
        if (!pcb->snd_wnd)
            port->zero_window_samples++;
        if (pcb->refused_data && pcb->refused_data->tot_len > port->max_refused) {
            port->max_refused = pcb->refused_data->tot_len;
            port->sndbuf_at_max_refused = tcp_sndbuf(pcb);
        }
    }
    return 0;
}
static volatile sig_atomic_t stopping;
static void stop(int signal_number) {
    (void)signal_number;
    stopping = 1;
}
static uint64_t clock_ms(void *unused) {
    (void)unused;
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value))
        abort();
    return (uint64_t)value.tv_sec * 1000 + (uint64_t)value.tv_nsec / 1000000;
}
static int refill_random(struct port *port) {
    if (port->random_remaining >= 128)
        return 0;
    uint8_t *bytes = (uint8_t *)port->random;
    size_t filled = 0;
    while (filled < sizeof(port->random)) {
        ssize_t n = read(port->random_fd, bytes + filled, sizeof(port->random) - filled);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0)
            return -1;
        filled += (size_t)n;
    }
    port->random_remaining = 512;
    return 0;
}
static uint32_t random32(void *arg) {
    struct port *port = arg;
    if (!port->random_remaining)
        abort(); /* Never replace failed entropy with a predictable seed. */
    return port->random[--port->random_remaining];
}
static dp_tx_result enqueue(void *arg, const uint8_t *bytes, size_t length) {
    struct port *port = arg;
    if (length > 1514)
        return DP_TX_FAILED;
    if (port->count == TX_CAPACITY) {
        port->paused = 1;
        return DP_TX_NOT_READY;
    }
    struct frame *frame = &port->tx[(port->head + port->count) % TX_CAPACITY];
    frame->length = length;
    memcpy(frame->bytes, bytes, length);
    port->count++;
    return DP_TX_ACCEPTED;
}
static int open_port(struct port *port, const char *name, int raw) {
    if (strlen(name) >= IFNAMSIZ) {
        errno = EINVAL;
        return -1;
    }
    memset(&port->interface, 0, sizeof(port->interface));
    strcpy(port->interface.ifr_name, name);
    port->raw = raw;
    if (raw) {
        unsigned index = if_nametoindex(name);
        if (!index)
            return -1;
        port->fd = socket(AF_PACKET, SOCK_RAW | SOCK_NONBLOCK | SOCK_CLOEXEC, htons(ETH_P_IPV6));
        if (port->fd < 0)
            return -1;
        struct sockaddr_ll address = {0};
        address.sll_family = AF_PACKET;
        address.sll_protocol = htons(ETH_P_IPV6);
        address.sll_ifindex = (int)index;
        if (bind(port->fd, (struct sockaddr *)&address, sizeof(address)))
            return -1;
    } else {
        port->fd = open("/dev/net/tun", O_RDWR | O_NONBLOCK | O_CLOEXEC);
        /* Android exposes the same TUN driver at /dev/tun. Test harness only. */
        if (port->fd < 0 && errno == ENOENT)
            port->fd = open("/dev/tun", O_RDWR | O_NONBLOCK | O_CLOEXEC);
        if (port->fd < 0)
            return -1;
        port->interface.ifr_flags = IFF_TAP | IFF_NO_PI;
        if (ioctl(port->fd, TUNSETIFF, &port->interface))
            return -1;
    }
    port->control = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    port->random_fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    return port->control < 0 || port->random_fd < 0 ? -1 : refill_random(port);
}
static int interface_up(struct port *port) {
    if (ioctl(port->control, SIOCGIFFLAGS, &port->interface))
        return 0;
    return !!(port->interface.ifr_flags & IFF_UP);
}
static int flush(struct port *port) {
    for (int batch = 0; batch < 32 && port->count; batch++) {
        struct frame *frame = &port->tx[port->head];
        ssize_t n = write(port->fd, frame->bytes, frame->length);
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR))
            break;
        if (n != (ssize_t)frame->length)
            return -1;
        port->head = (port->head + 1) % TX_CAPACITY;
        port->count--;
    }
    return 0;
}
static ssize_t receive(struct port *port, uint8_t *bytes, size_t length) {
    if (!port->raw)
        return read(port->fd, bytes, length);
    struct sockaddr_ll source;
    socklen_t size = sizeof(source);
    ssize_t n = recvfrom(port->fd, bytes, length, 0, (struct sockaddr *)&source, &size);
    if (n > 0 && source.sll_pkttype == PACKET_OUTGOING)
        return 0;
    return n;
}
int main(int argc, char **argv) {
    if (argc != 3 || (strcmp(argv[1], "--tap") && strcmp(argv[1], "--veth"))) {
        fprintf(stderr, "Usage: %s --tap|--veth interface\n", argv[0]);
        return 2;
    }
    struct port *port = calloc(1, sizeof(*port));
    if (!port)
        return 1;
    port->fd = port->control = port->random_fd = -1;
    dp_core *core = NULL;
    int result = 1;
    if (open_port(port, argv[2], !strcmp(argv[1], "--veth"))) {
        perror("open Ethernet port");
        goto cleanup;
    }
    dp_config config = {0};
    uint8_t mac[6] = {2, 0, 0, 0, 0, 1};
    memcpy(config.mac, mac, 6);
    inet_pton(AF_INET6, "fe80::1", config.ipv6);
    config.mtu = 1500;
    config.opaque = port;
    config.clock_ms = clock_ms;
    config.random32 = random32;
    config.output = enqueue;
    if (dp_core_create(&config, &core) != DP_OK ||
        dp_core_execute(core, echo_services_start, NULL) != DP_OK)
        goto cleanup;
    signal(SIGINT, stop);
    signal(SIGTERM, stop);
    uint64_t start = clock_ms(NULL);
    int ready = 0;
    while (!stopping) {
        if (refill_random(port)) {
            perror("entropy");
            goto cleanup;
        }
        if (!ready && interface_up(port)) {
            if (dp_core_set_link_ready(core, 1) != DP_OK)
                goto cleanup;
            ready = 1;
        }
        if (!ready && clock_ms(NULL) - start > 5000) {
            fprintf(stderr, "interface did not become ready\n");
            goto cleanup;
        }
        if (flush(port)) {
            perror("Ethernet TX (failed or short frame)");
            goto cleanup;
        }
        if (port->paused && port->count < TX_CAPACITY / 2) {
            port->paused = 0;
            if (dp_core_set_link_ready(core, 1) != DP_OK)
                goto cleanup;
        }
        uint32_t timeout;
        if (dp_core_poll(core) != DP_OK || dp_core_next_timeout(core, &timeout) != DP_OK)
            goto cleanup;
        if (dp_core_execute(core, inspect_pressure, port) != DP_OK)
            goto cleanup;
        if (!ready && timeout > 100)
            timeout = 100;
        struct pollfd event = {port->fd, (short)(POLLIN | (port->count ? POLLOUT : 0)), 0};
        int events = poll(&event, 1, timeout > 10000 ? 10000 : (int)timeout);
        if (events < 0 && errno == EINTR)
            continue;
        if (events < 0 || (event.revents & (POLLERR | POLLHUP | POLLNVAL))) {
            perror("Ethernet poll");
            goto cleanup;
        }
        if (event.revents & POLLIN) {
            for (int batch = 0; batch < 32; batch++) {
                uint8_t bytes[1518];
                ssize_t n = receive(port, bytes, sizeof(bytes));
                if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR))
                    break;
                if (n < 0) {
                    perror("Ethernet RX");
                    goto cleanup;
                }
                if (!n)
                    break;
                dp_result status = dp_core_input(core, bytes, (size_t)n);
                if (status != DP_OK && status != DP_UNSUPPORTED && status != DP_INVALID_ARGUMENT &&
                    status != DP_RESOURCE_LIMIT)
                    goto cleanup;
            }
        }
    }
    result = 0;
cleanup:
    fprintf(stderr, "max_refused=%u sndbuf_at_max_refused=%u zero_window_samples=%u\n",
            port->max_refused, port->sndbuf_at_max_refused, port->zero_window_samples);
    if (core) {
        dp_stats stats;
        if (dp_core_get_stats(core, &stats) == DP_OK)
            fprintf(stderr, "rx=%" PRIu64 " tx=%" PRIu64 " not_ready=%" PRIu64 " heap_peak=%u\n",
                    stats.rx_frames, stats.tx_accepted, stats.tx_not_ready, stats.heap_peak);
        if (dp_core_destroy(&core) != DP_OK)
            result = 1;
    }
    if (port->fd >= 0)
        close(port->fd);
    if (port->control >= 0)
        close(port->control);
    if (port->random_fd >= 0)
        close(port->random_fd);
    free(port);
    return result;
}
