import importlib.util
import json
from pathlib import Path
import socket
import sys
import subprocess
import tempfile
import threading
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'lwip_kernel_benchmark.py'
ENTRY_SCRIPT = Path(__file__).resolve().parents[1] / 'benchmark_lwip.sh'
REPORT_SCRIPT = Path(__file__).resolve().parents[1] / 'lwip_benchmark_report.py'
sys.path.insert(0, str(SCRIPT.parent))
SPEC = importlib.util.spec_from_file_location('lwip_kernel_benchmark', SCRIPT)
BENCHMARK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BENCHMARK)
REPORT_SPEC = importlib.util.spec_from_file_location('lwip_benchmark_report', REPORT_SCRIPT)
REPORT = importlib.util.module_from_spec(REPORT_SPEC)
REPORT_SPEC.loader.exec_module(REPORT)


class LocalEchoPeer:
    def __init__(self):
        self.stopping = threading.Event()
        self.tcp = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
        self.tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.tcp.bind(('::1', 0))
        self.port = self.tcp.getsockname()[1]
        self.tcp.listen(1)
        self.tcp.settimeout(0.1)
        self.udp = socket.socket(socket.AF_INET6, socket.SOCK_DGRAM)
        self.udp.bind(('::1', self.port))
        self.udp.settimeout(0.1)
        self.threads = [threading.Thread(target=self.serve_tcp, daemon=True),
                        threading.Thread(target=self.serve_udp, daemon=True)]
        for thread in self.threads:
            thread.start()

    def serve_tcp(self):
        while not self.stopping.is_set():
            try:
                connection, _ = self.tcp.accept()
            except socket.timeout:
                continue
            except OSError:
                if self.stopping.is_set():
                    return
                raise
            with connection:
                while not self.stopping.is_set():
                    try:
                        data = connection.recv(16384)
                    except socket.timeout:
                        continue
                    if not data:
                        break
                    connection.sendall(data)

    def serve_udp(self):
        while not self.stopping.is_set():
            try:
                data, source = self.udp.recvfrom(65535)
            except socket.timeout:
                continue
            except OSError:
                if self.stopping.is_set():
                    return
                raise
            self.udp.sendto(data, source)

    def close(self):
        self.stopping.set()
        self.tcp.close()
        self.udp.close()
        for thread in self.threads:
            thread.join(timeout=1)


class KernelBenchmarkTest(unittest.TestCase):
    @unittest.skipUnless(sys.platform == 'linux', 'benchmark entry point requires Bash/Linux')
    def test_benchmark_entry_help_is_available_without_root(self):
        result = subprocess.run(['bash', str(ENTRY_SCRIPT), '--help'], text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('--duration SECONDS', result.stdout)
        invalid = subprocess.run(['bash', str(ENTRY_SCRIPT), '--output', 'build/unused',
                                  '--duration', 'nan'], text=True,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(2, invalid.returncode)
        self.assertIn('finite and positive', invalid.stderr)

    @unittest.skipUnless(sys.platform == 'linux', 'IPv6 microbenchmark peer requires Linux')
    def test_percentile_uses_nearest_rank_and_handles_empty_samples(self):
        self.assertEqual(2, BENCHMARK.percentile([3, 1, 2], 0.5))
        self.assertEqual(3, BENCHMARK.percentile([3, 1, 2], 0.95))
        self.assertIsNone(BENCHMARK.percentile([], 0.5))

    @unittest.skipUnless(sys.platform == 'linux', 'IPv6 microbenchmark peer requires Linux')
    def test_tcp_and_udp_echo_metrics_include_integrity_and_latency(self):
        peer = LocalEchoPeer()
        try:
            tcp = BENCHMARK.measure_tcp_echo('::1', peer.port, 0.05, payload_size=1024)
            udp = BENCHMARK.measure_udp_echo('::1', peer.port, 0.05, 0, payload_size=64)
        finally:
            peer.close()
        self.assertGreater(tcp['payload_bytes'], 0)
        self.assertEqual(tcp['payload_bytes'], tcp['echo_bytes'])
        self.assertGreater(tcp['rtt_p50_ms'], 0)
        self.assertEqual(0, udp['dropped_datagrams'])
        self.assertEqual(udp['sent_datagrams'], udp['received_datagrams'])
        self.assertGreater(udp['rtt_p99_ms'], 0)
        self.assertIn('payload_mib_per_second', tcp)
        self.assertIn('payload_mib_per_second', udp)

    @unittest.skipUnless(sys.platform == 'linux', 'benchmark report tool requires Linux build metadata')
    def test_report_does_not_promote_short_host_run_to_acceptance(self):
        root = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as folder:
            output_dir = Path(folder)
            build = output_dir / 'build'
            build.mkdir()
            (build / 'lwip_kernel_echo').write_bytes(b'release-like host binary')
            peer_path = output_dir / 'peer-report.json'
            peer_path.write_text(json.dumps({'status': 'complete', 'duration_gate_passed': False,
                                             'benchmarks': [{'protocol': 'tcp_echo'}]}))
            log = output_dir / 'kernel.log'
            log.write_text('rx=9 tx=7 not_ready=0 heap_peak=1234\n')
            path = output_dir / 'report.json'
            report = REPORT.build_report(root, path, peer_path, log, 'cc', 10, 0, 'veth',
                                         '2026-10-23T00:00:00Z')
            saved = json.loads(path.read_text())
        self.assertEqual('complete', report['status'])
        self.assertFalse(report['duration_gate_passed'])
        self.assertFalse(report['device_performance_acceptance'])
        self.assertFalse(report['media_headroom_gate_evaluable'])
        self.assertEqual(1234, saved['core_summary']['lwip_heap_peak_bytes'])
        self.assertEqual(report['binary_sha256'], saved['binary_sha256'])

    @unittest.skipUnless(sys.platform == 'linux', 'benchmark report tool requires Linux build metadata')
    def test_failed_kernel_run_remains_failed_even_with_complete_peer_json(self):
        root = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as folder:
            output_dir = Path(folder)
            build = output_dir / 'build'
            build.mkdir()
            (build / 'lwip_kernel_echo').write_bytes(b'release-like host binary')
            peer_path = output_dir / 'peer-report.json'
            peer_path.write_text(json.dumps({'status': 'complete', 'duration_gate_passed': True}))
            log = output_dir / 'kernel.log'
            log.write_text('')
            report = REPORT.build_report(root, output_dir / 'failed.json', peer_path, log,
                                         'cc', 600, 1, 'tap', '2026-10-23T00:00:00Z')
        self.assertEqual('failed', report['status'])
        self.assertFalse(report['duration_gate_passed'])


if __name__ == '__main__':
    unittest.main()
