#define _POSIX_C_SOURCE 200809L
#define _DEFAULT_SOURCE
#include "diplay_lwip.h"
#include "echo_services.h"
#include "endpoint_services.h"
#include "lwip/priv/tcp_priv.h"
#include "wire_faults.h"
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
 * Raw callbacks do bounded in-memory queue/diagnostic work. I/O, logging and waiting
 * stay outside lwIP. */
struct port {
    int fd, control, random_fd, raw, paused;
    struct ifreq interface;
    size_t random_remaining;
    uint64_t now;
    unsigned max_refused, sndbuf_at_max_refused, zero_window_samples;
    uint32_t random[512];
    wf_queue tx;
    struct {
        uint32_t sequence, hash;
        uint16_t peer, length;
    } seen[1024];
    size_t seen_count, seen_next;
    uint64_t repeated_tcp_admissions;
};
_Static_assert(sizeof(struct port) <= 512 * 1024, "test transport/diagnostics budget exceeded");
static void inspect_output(struct port *port, const uint8_t *bytes, size_t length) {
    if (length < 74 || bytes[20] != 6 || bytes[54] != (7000 >> 8) || bytes[55] != (7000 & 255))
        return;
    size_t header = (bytes[66] >> 4) * 4;
    if (header < 20 || 54 + header >= length)
        return;
    uint32_t sequence = (uint32_t)bytes[58] << 24 | (uint32_t)bytes[59] << 16 |
                        (uint32_t)bytes[60] << 8 | bytes[61];
    uint16_t peer = (uint16_t)((bytes[56] << 8) | bytes[57]);
    uint16_t size = (uint16_t)(length - 54 - header);
    uint32_t hash = UINT32_C(2166136261);
    for (size_t i = 54 + header; i < length; i++)
        hash = (hash ^ bytes[i]) * UINT32_C(16777619);
    for (size_t i = 0; i < port->seen_count; i++) {
        if (port->seen[i].sequence == sequence && port->seen[i].peer == peer &&
            port->seen[i].length == size && port->seen[i].hash == hash) {
            port->repeated_tcp_admissions++;
            return;
        }
    }
    size_t index = port->seen_next;
    port->seen[index].sequence = sequence;
    port->seen[index].peer = peer;
    port->seen[index].length = size;
    port->seen[index].hash = hash;
    port->seen_next = (index + 1) % 1024;
    if (port->seen_count < 1024)
        port->seen_count++;
}
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
    int admitted = wf_push(&port->tx, bytes, length, clock_ms(NULL));
    if (admitted < 0)
        return DP_TX_FAILED;
    if (!admitted) {
        port->paused = 1;
        return DP_TX_NOT_READY;
    }
    inspect_output(port, bytes, length);
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
    for (int batch = 0; batch < 32; batch++) {
        const unsigned char *bytes;
        size_t length;
        if (!wf_peek(&port->tx, port->now, &bytes, &length))
            break;
        ssize_t n = write(port->fd, bytes, length);
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR))
            break;
        if (n != (ssize_t)length)
            return -1;
        wf_sent(&port->tx);
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
    if (argc < 3 || argc > 5 || (strcmp(argv[1], "--tap") && strcmp(argv[1], "--veth"))) {
        fprintf(stderr, "Usage: %s --tap|--veth interface [--endpoints] [--faults-1|--faults-5]\n", argv[0]);
        return 2;
    }
    int endpoints = 0, faults = 0;
    for (int i = 3; i < argc; ++i) {
        if (!strcmp(argv[i], "--endpoints") && !endpoints) endpoints = 1;
        else if (!strcmp(argv[i], "--faults-1") && !faults) faults = 1;
        else if (!strcmp(argv[i], "--faults-5") && !faults) faults = 5;
        else return 2;
    }
    struct port *port = calloc(1, sizeof(*port));
    if (!port)
        return 1;
    if (faults)
        port->tx.config = (wf_config){faults == 1 ? 100 : 20, 5, 10, 100};
    port->fd = port->control = port->random_fd = -1;
    dp_core *core = NULL;
    endpoint_services *service = NULL;
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
    if (dp_core_create(&config, &core) != DP_OK)
        goto cleanup;
    if (endpoints) {
        service = endpoint_services_create(core);
        if (!service) goto cleanup;
    } else if (dp_core_execute(core, echo_services_start, NULL) != DP_OK) goto cleanup;
    signal(SIGINT, stop);
    signal(SIGTERM, stop);
    uint64_t start = clock_ms(NULL);
    int ready = 0;
    while (!stopping) {
        port->now = clock_ms(NULL);
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
        if (port->paused && port->tx.count < WF_CAPACITY / 2) {
            port->paused = 0;
            if (dp_core_set_link_ready(core, 1) != DP_OK)
                goto cleanup;
        }
        uint32_t timeout;
        if (dp_core_poll(core) != DP_OK || dp_core_next_timeout(core, &timeout) != DP_OK)
            goto cleanup;
        if (service) {
            int progress = endpoint_services_pump(core, service);
            if (progress < 0) goto cleanup;
            if (progress) timeout = 0;
        }
        if (dp_core_execute(core, inspect_pressure, port) != DP_OK)
            goto cleanup;
        if (!ready && timeout > 100)
            timeout = 100;
        uint32_t wait = wf_wait(&port->tx, clock_ms(NULL));
        if (wait && wait < timeout)
            timeout = wait;
        struct pollfd event = {port->fd, (short)(POLLIN | (!wait ? POLLOUT : 0)), 0};
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
    fprintf(stderr,
            "faults: admitted=%" PRIu64 " dropped=%" PRIu64 " delayed=%" PRIu64
            " reordered=%" PRIu64 " duplicated=%" PRIu64 "\n",
            port->tx.admitted, port->tx.dropped, port->tx.delayed, port->tx.reordered,
            port->tx.duplicates_sent);
    fprintf(stderr, "repeated_tcp_admissions=%" PRIu64 "\n", port->repeated_tcp_admissions);
    if (faults && (!port->tx.dropped || !port->tx.delayed || !port->tx.reordered ||
                      !port->tx.duplicates_sent))
        result = 1;
    fprintf(stderr, "max_refused=%u sndbuf_at_max_refused=%u zero_window_samples=%u\n",
            port->max_refused, port->sndbuf_at_max_refused, port->zero_window_samples);
    if (core) {
        if (service) {
            fprintf(stderr, "endpoint-active: %s\n", endpoint_services_verified(service) ? "verified" : "incomplete");
            if (!endpoint_services_verified(service)) result = 1;
            if (endpoint_services_destroy(core, service)) result = 1;
        }
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
