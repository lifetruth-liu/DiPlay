"""Independent Linux kernel peer; this file contains no lwIP or frame-code dependency."""
import socket
import sys


def main(interface):
    scope = socket.if_nametoindex(interface)
    payload = bytes(range(256)) * 256
    for _ in range(3):
        with socket.socket(socket.AF_INET6, socket.SOCK_STREAM) as peer:
            peer.settimeout(10)
            peer.bind(("fe80::2", 0, 0, scope))
            peer.connect(("fe80::1", 7000, 0, scope))
            peer.sendall(payload)
            reply = bytearray()
            while len(reply) < len(payload):
                data = peer.recv(len(payload) - len(reply))
                if not data:
                    raise AssertionError("TCP ended before echo was complete")
                reply.extend(data)
            if reply != payload:
                raise AssertionError("TCP byte order/content mismatch")
    with socket.socket(socket.AF_INET6, socket.SOCK_DGRAM) as peer:
        peer.settimeout(3)
        peer.bind(("fe80::2", 0, 0, scope))
        for size in (0, 1, 1200, 4096, 8192):
            request = payload[:size]
            peer.sendto(request, ("fe80::1", 6000, 0, scope))
            reply, source = peer.recvfrom(65535)
            if reply != request or source[0] != "fe80::1" or source[1] != 6000:
                raise AssertionError("UDP data/source mismatch")
    print("PASS independent kernel TCP (3 x 64 KiB), UDP (empty/MTU/fragmented)")


if __name__ == "__main__":
    main(sys.argv[1])
