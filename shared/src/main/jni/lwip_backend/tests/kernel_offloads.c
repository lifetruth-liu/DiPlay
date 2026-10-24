/* Test interface setup only: AF_PACKET must receive wire-valid checksums/MTU frames. */
#define _DEFAULT_SOURCE
#include <errno.h>
#include <linux/ethtool.h>
#include <linux/sockios.h>
#include <net/if.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>

static int disable(int fd, struct ifreq *request, unsigned get, unsigned set) {
    struct ethtool_value feature = {get, 0};
    request->ifr_data = (void *)&feature;
    if (ioctl(fd, SIOCETHTOOL, request))
        return errno == EOPNOTSUPP ? 0 : -1;
    if (!feature.data)
        return 0;
    feature.cmd = set;
    feature.data = 0;
    if (ioctl(fd, SIOCETHTOOL, request))
        return -1;
    feature.cmd = get;
    if (ioctl(fd, SIOCETHTOOL, request))
        return -1;
    if (feature.data) {
        errno = EIO;
        return -1;
    }
    return 0;
}
int main(int argc, char **argv) {
    if (argc != 2 || strlen(argv[1]) >= IFNAMSIZ) {
        fprintf(stderr, "Usage: %s isolated-test-interface\n", argv[0]);
        return 2;
    }
    int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        perror("offload socket");
        return 1;
    }
    struct ifreq request = {0};
    strcpy(request.ifr_name, argv[1]);
    int failed = disable(fd, &request, ETHTOOL_GTSO, ETHTOOL_STSO) ||
                 disable(fd, &request, ETHTOOL_GGSO, ETHTOOL_SGSO) ||
                 disable(fd, &request, ETHTOOL_GGRO, ETHTOOL_SGRO) ||
                 disable(fd, &request, ETHTOOL_GTXCSUM, ETHTOOL_STXCSUM);
    if (failed)
        perror("disable test interface offloads");
    close(fd);
    return failed ? 1 : 0;
}
