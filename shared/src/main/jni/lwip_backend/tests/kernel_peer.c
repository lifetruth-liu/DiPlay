/* Independent kernel socket client. Deliberately does not include/link lwIP or frame helpers. */
#define _POSIX_C_SOURCE 200809L
#define _DEFAULT_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <net/if.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static int open_peer(int type, unsigned scope, unsigned short port, int small_buffers) {
    int fd = socket(AF_INET6, type | SOCK_CLOEXEC, 0);
    if (fd < 0)
        return -1;
    struct timeval timeout = {10, 0};
    if (small_buffers) {
        int size = 4096;
        if (setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &size, sizeof(size)) ||
            setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &size, sizeof(size)))
            goto fail;
    }
    struct sockaddr_in6 local = {0}, remote = {0};
    local.sin6_family = remote.sin6_family = AF_INET6;
    local.sin6_scope_id = remote.sin6_scope_id = scope;
    remote.sin6_port = htons(port);
    inet_pton(AF_INET6, "fe80::2", &local.sin6_addr);
    inet_pton(AF_INET6, "fe80::1", &remote.sin6_addr);
    if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) ||
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) ||
        bind(fd, (struct sockaddr *)&local, sizeof(local)))
        goto fail;
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK))
        goto fail;
    if (connect(fd, (struct sockaddr *)&remote, sizeof(remote))) {
        if (errno != EINPROGRESS)
            goto fail;
        struct pollfd event = {fd, POLLOUT, 0};
        int result;
        do {
            result = poll(&event, 1, 10000);
        } while (result < 0 && errno == EINTR);
        if (result <= 0) {
            if (!result)
                errno = ETIMEDOUT;
            goto fail;
        }
        int error = 0;
        socklen_t size = sizeof(error);
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &size))
            goto fail;
        if (error) {
            errno = error;
            goto fail;
        }
    }
    if (fcntl(fd, F_SETFL, flags))
        goto fail;
    return fd;
fail : {
    int error = errno;
    close(fd);
    errno = error;
    return -1;
}
}
static int tcp_case(unsigned scope, const unsigned char *payload, size_t length) {
    int fd = open_peer(SOCK_STREAM, scope, 7000, 0);
    if (fd < 0) {
        perror("TCP connect");
        return 1;
    }
    int result = 1;
    size_t sent = 0, received = 0;
    while (sent < length) {
        ssize_t n = send(fd, payload + sent, length - sent, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0) {
            perror("TCP send");
            goto done;
        }
        sent += (size_t)n;
    }
    while (received < length) {
        unsigned char bytes[4096];
        ssize_t n = recv(fd, bytes, sizeof(bytes), 0);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0) {
            fprintf(stderr, "TCP ended early: %zd errno=%d\n", n, errno);
            goto done;
        }
        if ((size_t)n > length - received || memcmp(payload + received, bytes, (size_t)n)) {
            fprintf(stderr, "TCP byte order/content mismatch\n");
            goto done;
        }
        received += (size_t)n;
    }
    result = 0;
done:
    close(fd);
    return result;
}
static int udp_cases(unsigned scope, const unsigned char *payload, int hop_options) {
    int fd = open_peer(SOCK_DGRAM, scope, 6000, 0);
    if (fd < 0) {
        perror("UDP connect");
        return 1;
    }
    if (hop_options) {
        const unsigned char options[8] = {0}; /* Eight-byte HBH header containing Pad1 options. */
        if (setsockopt(fd, IPPROTO_IPV6, IPV6_HOPOPTS, options, sizeof(options))) {
            perror("IPV6_HOPOPTS"); close(fd); return 1;
        }
    }
    const size_t sizes[] = {0, 1, 1200, 4096, 8192, 65487, 65488, hop_options ? 65519u : 65527u};
    int result = 1;
    for (size_t i = 0; i < sizeof(sizes) / sizeof(sizes[0]); i++) {
        ssize_t n;
        do {
            n = send(fd, payload, sizes[i], MSG_NOSIGNAL);
        } while (n < 0 && errno == EINTR);
        if (n != (ssize_t)sizes[i]) {
            perror("UDP send");
            goto done;
        }
        unsigned char bytes[65528];
        struct sockaddr_in6 source = {0};
        socklen_t size = sizeof(source);
        do {
            n = recvfrom(fd, bytes, sizeof(bytes), 0, (struct sockaddr *)&source, &size);
        } while (n < 0 && errno == EINTR);
        struct in6_addr expected;
        inet_pton(AF_INET6, "fe80::1", &expected);
        if (n != (ssize_t)sizes[i] || memcmp(bytes, payload, sizes[i]) ||
            source.sin6_family != AF_INET6 || source.sin6_port != htons(6000) ||
            memcmp(&source.sin6_addr, &expected, sizeof(expected))) {
            fprintf(stderr, "UDP data/source mismatch: expected=%zu actual=%zd errno=%d\n",
                    sizes[i], n, errno);
            goto done;
        }
    }
    result = 0;
done:
    close(fd);
    return result;
}
static unsigned char stream_byte(size_t offset) {
    uint32_t value = (uint32_t)offset + UINT32_C(0x9e3779b9);
    value = (value ^ (value >> 16)) * UINT32_C(0x85ebca6b);
    value = (value ^ (value >> 13)) * UINT32_C(0xc2b2ae35);
    return (unsigned char)((value ^ (value >> 16)) >> 24);
}
static int slow_reader(unsigned scope) {
    const size_t total = 1024 * 1024;
    int fd = open_peer(SOCK_STREAM, scope, 7000, 1);
    if (fd < 0) {
        perror("slow TCP connect");
        return 1;
    }
    struct timeval timeout = {30, 0};
    int pipefd[2];
    if (setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) || pipe(pipefd)) {
        perror("slow TCP setup");
        close(fd);
        return 1;
    }
    pid_t writer = fork();
    if (writer < 0) {
        perror("fork sender");
        close(pipefd[0]);
        close(pipefd[1]);
        close(fd);
        return 1;
    }
    if (!writer) {
        close(pipefd[0]);
        size_t sent = 0;
        while (sent < total) {
            unsigned char bytes[4096];
            size_t count = total - sent < sizeof(bytes) ? total - sent : sizeof(bytes);
            for (size_t i = 0; i < count; i++)
                bytes[i] = stream_byte(sent + i);
            ssize_t n = send(fd, bytes, count, MSG_NOSIGNAL);
            if (n < 0 && errno == EINTR)
                continue;
            if (n <= 0) {
                perror("slow sender");
                _exit(1);
            }
            if (!sent && write(pipefd[1], "R", 1) != 1)
                _exit(1);
            sent += (size_t)n;
        }
        close(pipefd[1]);
        close(fd);
        _exit(0);
    }
    close(pipefd[1]);
    int result = 1, status;
    struct pollfd event = {pipefd[0], POLLIN, 0};
    char started;
    if (poll(&event, 1, 2000) <= 0 || read(pipefd[0], &started, 1) != 1) {
        fprintf(stderr, "slow sender did not start\n");
        goto done;
    }
    struct timespec pause = {10, 0};
    while (nanosleep(&pause, &pause) && errno == EINTR) {
    }
    pid_t reaped = waitpid(writer, &status, WNOHANG);
    if (reaped != 0) {
        if (reaped == writer)
            writer = -1;
        fprintf(stderr, "sender did not remain backpressured during ten-second receive pause\n");
        goto done;
    }
    size_t received = 0;
    while (received < total) {
        unsigned char bytes[4096];
        ssize_t n = recv(fd, bytes, sizeof(bytes), 0);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0 || (size_t)n > total - received) {
            fprintf(stderr, "slow TCP ended early: received=%zu n=%zd errno=%d\n", received, n,
                    errno);
            goto done;
        }
        for (ssize_t i = 0; i < n; i++)
            if (bytes[i] != stream_byte(received + (size_t)i)) {
                fprintf(stderr, "slow TCP content/order mismatch at %zu\n", received + (size_t)i);
                goto done;
            }
        received += (size_t)n;
    }
    for (int attempt = 0; attempt < 200; attempt++) {
        reaped = waitpid(writer, &status, WNOHANG);
        if (reaped == writer) {
            writer = -1;
            if (WIFEXITED(status) && WEXITSTATUS(status) == 0)
                result = 0;
            break;
        }
        if (reaped < 0 && errno != EINTR)
            break;
        struct timespec tick = {0, 10000000};
        nanosleep(&tick, NULL);
    }
    if (!result)
        puts("PASS independent TCP 1 MiB: sender backpressured during 10-second pause, complete "
             "ordered recovery");
done:
    if (writer > 0) {
        kill(writer, SIGKILL);
        while (waitpid(writer, NULL, 0) < 0 && errno == EINTR) {
        }
    }
    close(pipefd[0]);
    close(fd);
    return result;
}
static int run_cases(unsigned scope, const char *scenario) {
    if (!strcmp(scenario, "--slow"))
        return slow_reader(scope);
    unsigned char payload[65536];
    for (size_t i = 0; i < sizeof(payload); i++)
        payload[i] = stream_byte(i);
    for (int repeat = 0; repeat < 3; repeat++)
        if (tcp_case(scope, payload, sizeof(payload)))
            return 1;
    if (!strcmp(scenario, "--tcp-only")) {
        puts("PASS independent kernel TCP (3 x 64 KiB)");
        return 0;
    }
    if (udp_cases(scope, payload, 0) || udp_cases(scope, payload, 1))
        return 1;
    puts("PASS independent kernel TCP (3 x 64 KiB), UDP (empty/MTU/fragmented/HBH)");
    return 0;
}
static int active_server(unsigned scope, int ready) {
    const char *stage = "listen";
    int server = socket(AF_INET6, SOCK_STREAM | SOCK_CLOEXEC, 0), fd = -1, result = 1;
    if (server < 0) return 1;
    struct timeval timeout = {10, 0};
    struct sockaddr_in6 address = {0};
    address.sin6_family = AF_INET6; address.sin6_scope_id = scope; address.sin6_port = htons(9000);
    inet_pton(AF_INET6, "fe80::2", &address.sin6_addr);
    if (setsockopt(server, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) ||
        setsockopt(server, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) ||
        bind(server, (struct sockaddr *)&address, sizeof(address)) || listen(server, 1)) goto done;
    if (write(ready, "R", 1) != 1) goto done;
    close(ready); ready = -1;
    stage = "accept";
    socklen_t length = sizeof(address);
    do { fd = accept(server, (struct sockaddr *)&address, &length); } while (fd < 0 && errno == EINTR);
    struct in6_addr expected_address; inet_pton(AF_INET6, "fe80::1", &expected_address);
    if (fd < 0 || address.sin6_family != AF_INET6 || !address.sin6_port ||
        memcmp(&address.sin6_addr, &expected_address, sizeof(expected_address))) goto done;
    const char expected[] = "DiPlay-active-v1"; char bytes[sizeof(expected) - 1], extra;
    stage = "read challenge";
    size_t received = 0, sent = 0;
    while (received < sizeof(bytes)) {
        ssize_t n = recv(fd, bytes + received, sizeof(bytes) - received, 0);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) goto done;
        received += (size_t)n;
    }
    if (memcmp(bytes, expected, sizeof(bytes))) goto done;
    ssize_t eof;
    stage = "read FIN";
    do { eof = recv(fd, &extra, 1, 0); } while (eof < 0 && errno == EINTR);
    if (eof != 0) goto done; /* Native output half-close leaves its receive side open. */
    stage = "write reply";
    while (sent < sizeof(bytes)) {
        ssize_t n = send(fd, bytes + sent, sizeof(bytes) - sent, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) goto done;
        sent += (size_t)n;
    }
    if (shutdown(fd, SHUT_WR)) goto done;
    result = 0;
done:
    if (result) fprintf(stderr, "independent active TCP server failed stage=%s errno=%d\n", stage, errno);
    if (ready >= 0) close(ready);
    if (fd >= 0) close(fd);
    close(server); return result;
}
static int endpoint_cases(unsigned scope, const char *scenario) {
    int pipefd[2]; if (pipe(pipefd)) return 1;
    fflush(NULL);
    pid_t child = fork();
    if (child < 0) { close(pipefd[0]); close(pipefd[1]); return 1; }
    if (!child) { close(pipefd[0]); _exit(active_server(scope, pipefd[1])); }
    close(pipefd[1]);
    struct pollfd ready = {pipefd[0], POLLIN, 0}; char marker;
    int result = 1, status;
    if (poll(&ready, 1, 10000) <= 0 || read(pipefd[0], &marker, 1) != 1 || marker != 'R') goto done;
    int control = open_peer(SOCK_DGRAM, scope, 6002, 0);
    if (control < 0) goto done;
    struct timeval retry = {0, 250000}; int acknowledged = 0;
    if (setsockopt(control, SOL_SOCKET, SO_RCVTIMEO, &retry, sizeof(retry))) { close(control); goto done; }
    for (int i = 0; i < 32; ++i) {
        ssize_t sent;
        do { sent = send(control, "CONNECT9000", 11, MSG_NOSIGNAL); } while (sent < 0 && errno == EINTR);
        if (sent != 11) break;
        char reply[6]; ssize_t received;
        do { received = recv(control, reply, sizeof(reply), 0); } while (received < 0 && errno == EINTR);
        if (received == 5 && !memcmp(reply, "READY", 5)) { acknowledged = 1; break; }
        if (received >= 0 || (errno != EAGAIN && errno != EWOULDBLOCK)) break;
    }
    close(control);
    if (!acknowledged) { fprintf(stderr, "endpoint control handshake failed\n"); goto done; }
    if (run_cases(scope, scenario)) goto done;
    for (int i = 0; i < 2000; ++i) {
        pid_t reaped = waitpid(child, &status, WNOHANG);
        if (reaped == child) { child = -1; result = WIFEXITED(status) && WEXITSTATUS(status) == 0 ? 0 : 1; break; }
        if (reaped < 0 && errno != EINTR) break;
        struct timespec tick = {0, 10000000}; nanosleep(&tick, NULL);
    }
    if (!result) puts("PASS independent kernel accepted native active connect, data and half-close");
done:
    close(pipefd[0]);
    if (child > 0) { kill(child, SIGKILL); while (waitpid(child, NULL, 0) < 0 && errno == EINTR) {} }
    return result;
}
int main(int argc, char **argv) {
    if (argc < 2 || argc > 3) return 2;
    const char *scenario = argc == 3 ? argv[2] : "basic";
    int endpoints = !strncmp(scenario, "--endpoints", 11);
    if (endpoints) {
        scenario += 11;
        if (!*scenario) scenario = "basic";
        else if (!strcmp(scenario, "-slow")) scenario = "--slow";
        else if (!strcmp(scenario, "-tcp-only")) scenario = "--tcp-only";
        else return 2;
    } else if (strcmp(scenario, "basic") && strcmp(scenario, "--slow") && strcmp(scenario, "--tcp-only")) return 2;
    unsigned scope = if_nametoindex(argv[1]);
    if (!scope) { perror("interface index"); return 1; }
    return endpoints ? endpoint_cases(scope, scenario) : run_cases(scope, scenario);
}
