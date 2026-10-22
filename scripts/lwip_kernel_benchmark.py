#!/usr/bin/env python3
"""Measure TCP and UDP echo throughput/round-trip latency against the isolated lwIP peer."""
import argparse
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import platform
import socket
import sys
import time

try:
    import resource
except ImportError:  # Keep report helpers importable from Windows unit tests.
    resource = None


UDP_SIZES = (64, 1200, 4096, 8192)
TCP_PAYLOAD_SIZE = 16 * 1024
MINIMUM_BENCHMARK_SECONDS = 600


def positive_seconds(value):
    try:
        number = float(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError('must be a number') from error
    if not math.isfinite(number) or number <= 0:
        raise argparse.ArgumentTypeError('must be finite and positive')
    return number


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, int(math.ceil(fraction * len(ordered))))
    return ordered[min(rank, len(ordered)) - 1]


def latency_summary(samples_ns):
    return {
        'rtt_p50_ms': percentile(samples_ns, 0.50) / 1_000_000 if samples_ns else None,
        'rtt_p95_ms': percentile(samples_ns, 0.95) / 1_000_000 if samples_ns else None,
        'rtt_p99_ms': percentile(samples_ns, 0.99) / 1_000_000 if samples_ns else None,
        'rtt_max_ms': max(samples_ns) / 1_000_000 if samples_ns else None,
        'sample_count': len(samples_ns),
    }


def receive_exact(connection, count):
    result = bytearray()
    while len(result) < count:
        data = connection.recv(count - len(result))
        if not data:
            raise ConnectionError('TCP echo peer closed before returning the whole payload')
        result.extend(data)
    return bytes(result)


def throughput_metrics(payload_bytes, elapsed):
    seconds = max(elapsed, 1e-9)
    return {
        'payload_bytes': payload_bytes,
        'payload_mib_per_second': payload_bytes / seconds / (1024 * 1024),
        'roundtrip_mib_per_second': 2 * payload_bytes / seconds / (1024 * 1024),
    }


def measure_tcp_echo(host, port, duration, scope_id=0, payload_size=TCP_PAYLOAD_SIZE):
    if duration <= 0 or payload_size <= 0:
        raise ValueError('duration and payload_size must be positive')
    payload = bytes((index * 37 + 11) & 255 for index in range(payload_size))
    samples = []
    byte_count = 0
    address = (host, port, 0, scope_id)
    with socket.socket(socket.AF_INET6, socket.SOCK_STREAM) as connection:
        connection.settimeout(5)
        connection.connect(address)
        started = time.monotonic()
        deadline = started + duration
        while time.monotonic() < deadline:
            sample_start = time.perf_counter_ns()
            connection.sendall(payload)
            echoed = receive_exact(connection, payload_size)
            sample_ns = time.perf_counter_ns() - sample_start
            if echoed != payload:
                raise AssertionError('TCP echo payload mismatch')
            samples.append(sample_ns)
            byte_count += payload_size
        elapsed = time.monotonic() - started
    return {
        'protocol': 'tcp_echo',
        'requested_seconds': duration,
        'elapsed_seconds': elapsed,
        **throughput_metrics(byte_count, elapsed),
        'echo_bytes': byte_count,
        'payload_size_bytes': payload_size,
        **latency_summary(samples),
    }


def measure_udp_echo(host, port, duration, scope_id=0, payload_size=1200):
    if duration <= 0 or payload_size < 0 or payload_size > 65527:
        raise ValueError('UDP duration must be positive and payload size must be 0..65527')
    payload = bytes((index * 53 + 7) & 255 for index in range(payload_size))
    address = (host, port, 0, scope_id)
    samples = []
    sent = received = byte_count = dropped = 0
    with socket.socket(socket.AF_INET6, socket.SOCK_DGRAM) as connection:
        connection.settimeout(min(1.0, max(0.05, duration)))
        started = time.monotonic()
        deadline = started + duration
        while time.monotonic() < deadline:
            sample_start = time.perf_counter_ns()
            connection.sendto(payload, address)
            sent += 1
            try:
                echoed, source = connection.recvfrom(65535)
            except socket.timeout:
                dropped += 1
                continue
            if source[0].split('%', 1)[0] != host or source[1] != port:
                raise AssertionError('UDP echo source mismatch')
            if echoed != payload:
                raise AssertionError('UDP echo payload mismatch')
            samples.append(time.perf_counter_ns() - sample_start)
            received += 1
            byte_count += payload_size
        elapsed = time.monotonic() - started
    return {
        'protocol': 'udp_echo',
        'requested_seconds': duration,
        'elapsed_seconds': elapsed,
        **throughput_metrics(byte_count, elapsed),
        'payload_size_bytes': payload_size,
        'sent_datagrams': sent,
        'received_datagrams': received,
        'dropped_datagrams': dropped,
        'loss_percent': (100.0 * dropped / sent) if sent else None,
        **latency_summary(samples),
    }


def write_report(path, report):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    temporary.replace(path)


def execute_benchmark(interface, duration, report_path):
    if resource is None or not sys.platform.startswith('linux'):
        raise OSError('the kernel-peer benchmark requires Linux resource accounting')
    started_wall = time.monotonic()
    started_cpu = resource.getrusage(resource.RUSAGE_SELF)
    scope_id = socket.if_nametoindex(interface)
    report = {
        'schema_version': 1,
        'status': 'running',
        'test_type': 'Linux host TCP/UDP echo microbenchmark; not device performance acceptance',
        'started_utc': datetime.now(timezone.utc).isoformat(),
        'interface': interface,
        'interface_index': scope_id,
        'peer': 'fe80::1',
        'requested_seconds': duration,
        'minimum_acceptance_seconds': MINIMUM_BENCHMARK_SECONDS,
        'benchmarks': [],
        'environment': {
            'platform': platform.platform(),
            'machine': platform.machine(),
            'python': sys.version,
            'network_namespace': os.readlink('/proc/self/ns/net'),
        },
    }
    write_report(report_path, report)
    try:
        tcp_seconds = duration / 2
        udp_seconds = (duration - tcp_seconds) / len(UDP_SIZES)
        report['benchmarks'].append(measure_tcp_echo('fe80::1', 7000, tcp_seconds, scope_id))
        write_report(report_path, report)
        for size in UDP_SIZES:
            report['benchmarks'].append(measure_udp_echo('fe80::1', 6000, udp_seconds, scope_id, size))
            write_report(report_path, report)
        dropped = sum(result['dropped_datagrams'] for result in report['benchmarks']
                      if result['protocol'] == 'udp_echo')
        report['status'] = 'complete' if dropped == 0 else 'failed'
        if dropped:
            report['error'] = 'UDP echo lost %d datagram(s)' % dropped
        return 0 if dropped == 0 else 1
    except Exception as error:
        report['status'] = 'failed'
        report['error'] = type(error).__name__ + ': ' + str(error)
        return_code = 1
        return return_code
    finally:
        report['finished_utc'] = datetime.now(timezone.utc).isoformat()
        report['elapsed_wall_seconds'] = time.monotonic() - started_wall
        ended_cpu = resource.getrusage(resource.RUSAGE_SELF)
        report['client_process_cpu_seconds'] = ((ended_cpu.ru_utime - started_cpu.ru_utime) +
                                                (ended_cpu.ru_stime - started_cpu.ru_stime))
        report['client_process_max_rss_kib'] = ended_cpu.ru_maxrss
        report['duration_gate_passed'] = (report['status'] == 'complete' and
                                          report['elapsed_wall_seconds'] >= MINIMUM_BENCHMARK_SECONDS and
                                          duration >= MINIMUM_BENCHMARK_SECONDS)
        write_report(report_path, report)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--interface', required=True, help='isolated TAP/veth peer interface')
    parser.add_argument('--duration', type=positive_seconds, default=MINIMUM_BENCHMARK_SECONDS,
                        help='total measured seconds (default: 600)')
    parser.add_argument('--report', required=True, type=Path)
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    try:
        result = execute_benchmark(args.interface, args.duration, args.report.resolve())
    except OSError as error:
        print('benchmark failed: %s' % error, file=sys.stderr)
        return 1
    print(args.report.resolve())
    return result


if __name__ == '__main__':
    sys.exit(main())
