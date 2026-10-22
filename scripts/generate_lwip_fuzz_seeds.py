#!/usr/bin/env python3
"""Create deterministic protocol and API seeds for the native lwIP fuzz targets."""
import argparse
from pathlib import Path
import struct


LOCAL_MAC = bytes.fromhex('020000000001')
PEER_MAC = bytes.fromhex('020000000002')
LOCAL_IP = bytes.fromhex('fe800000000000000000000000000001')
PEER_IP = bytes.fromhex('fe800000000000000000000000000002')


def internet_checksum(data):
    if len(data) & 1:
        data += b'\0'
    total = sum(struct.unpack('!%dH' % (len(data) // 2), data))
    while total >> 16:
        total = (total & 0xffff) + (total >> 16)
    return (~total) & 0xffff


def transport_checksum(payload, protocol):
    pseudo = PEER_IP + LOCAL_IP + struct.pack('!I3xB', len(payload), protocol)
    result = internet_checksum(pseudo + payload)
    return result or 0xffff


def has_valid_transport_checksum(frame):
    protocol = frame[20]
    segment = frame[54:]
    pseudo = frame[22:54] + struct.pack('!I3xB', len(segment), protocol)
    return internet_checksum(pseudo + segment) == 0


def ipv6_frame(protocol, payload):
    ethernet = LOCAL_MAC + PEER_MAC + b'\x86\xdd'
    ipv6 = (b'\x60\0\0\0' + struct.pack('!HBB', len(payload), protocol, 64) +
            PEER_IP + LOCAL_IP)
    return ethernet + ipv6 + payload


def udp_frame(payload=b'lwip-seed'):
    header = struct.pack('!HHHH', 50000, 47000, 8 + len(payload), 0)
    segment = header + payload
    checksum = transport_checksum(segment, 17)
    return ipv6_frame(17, segment[:6] + struct.pack('!H', checksum) + payload)


def tcp_syn_frame():
    header = struct.pack('!HHII', 50000, 7000, 1000, 0)
    header += bytes((0x50, 0x02)) + struct.pack('!HHH', 32768, 0, 0)
    checksum = transport_checksum(header, 6)
    return ipv6_frame(6, header[:16] + struct.pack('!H', checksum) + header[18:])


def icmp_echo_frame():
    payload = b'\x80\0\0\0' + struct.pack('!HH', 0x1234, 1) + b'dplay'
    checksum = transport_checksum(payload, 58)
    return ipv6_frame(58, payload[:2] + struct.pack('!H', checksum) + payload[4:])


def command(op, index=0, arg=0, payload=b'', stale=False):
    if len(payload) > 255:
        raise ValueError('one fuzz command payload cannot exceed 255 bytes')
    encoded_index = (index & 15) | (0x80 if stale else 0)
    return bytes((op, encoded_index, arg & 255, len(payload))) + payload


def seed_sets():
    udp = b'\x07' + udp_frame()
    tcp = b'\x07' + tcp_syn_frame()
    echo = b'\x07' + icmp_echo_frame()
    nested_udp = b'\x07' + udp_frame(b'cmd')
    return {
        'ethernet': {
            '00-udp-v6': udp,
            '01-tcp-syn-v6': tcp,
            '02-icmpv6-echo': echo,
            '03-truncated': b'\0\x86\xdd\x60',
        },
        'commands': {
            '00-udp-tcp-io': (
                command(2, arg=53, payload=b'udp-seed') +
                command(18) + command(4, arg=100) +
                command(9, index=2, payload=b'tcp-seed') +
                command(8, index=2, arg=32) + command(17, index=2) +
                command(15, arg=4)),
            '01-framed-udp': command(16, payload=nested_udp),
            '02-stale-token': (command(1) + command(2, arg=53, stale=True) +
                               command(10) + command(2, arg=53, stale=True)),
        },
        'lifecycle': {
            '00-recreate-and-stale': (
                command(0) + command(1) + command(2, arg=53, stale=True) +
                command(0) + command(2, arg=53, stale=True)),
            '01-repeated-recreate': command(0) + command(0) + command(14, arg=1) +
                                    command(15, arg=8),
            '02-close-and-rebind': command(10, index=2) + command(6, arg=1) +
                                   command(1, arg=1),
        },
    }


def write_seeds(output, target):
    written = []
    folder = output
    folder.mkdir(parents=True, exist_ok=True)
    for name, contents in seed_sets()[target].items():
        path = folder / name
        if not path.exists():
            path.write_bytes(contents)
        written.append(path)
    return written


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path,
                        help='corpus directory for the selected target')
    parser.add_argument('--target', required=True, choices=sorted(seed_sets()))
    args = parser.parse_args(argv)
    for path in write_seeds(args.output.resolve(), args.target):
        print(path)


if __name__ == '__main__':
    main()
