#!/usr/bin/env python3
"""Assemble reproducible metadata around a Linux lwIP host benchmark run."""
from datetime import datetime, timezone
import hashlib
import json
import platform
from pathlib import Path
import re
import subprocess


def file_sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_sha256(root):
    paths = list((root / 'shared/src/main/jni/lwip_backend').rglob('*'))
    paths += [root / 'scripts/benchmark_lwip.sh', root / 'scripts/lwip_kernel_benchmark.py',
              root / 'scripts/lwip_benchmark_report.py', root / 'scripts/test_lwip_kernel.sh',
              root / 'scripts/check_lwip_native.py']
    digest = hashlib.sha256()
    for path in sorted(item for item in paths if item.is_file()):
        digest.update(path.relative_to(root).as_posix().encode('utf-8') + b'\0')
        digest.update(bytes.fromhex(file_sha256(path)))
    return digest.hexdigest()


def command_version(command):
    return subprocess.check_output(command, text=True).splitlines()[0]


def build_report(root, output, peer_report_path, kernel_log, compiler, duration,
                 kernel_returncode, network, started_utc):
    root = Path(root).resolve()
    output = Path(output).resolve()
    peer_report_path = Path(peer_report_path)
    kernel_log = Path(kernel_log)
    build_dir = output.parent / 'build'
    cmake_args = ['-S', str(root / 'shared/src/main/jni/lwip_backend'), '-B', str(build_dir),
                  '-DCMAKE_C_COMPILER=' + compiler, '-DCMAKE_BUILD_TYPE=Release',
                  '-DDIPLAY_SANITIZERS=OFF']
    build_args = ['--build', str(build_dir), '--target', 'lwip_kernel_echo',
                  'lwip_kernel_offloads', '--parallel', '2']
    report = {
        'schema_version': 1,
        'status': 'failed',
        'test_type': 'Linux host TCP/UDP echo microbenchmark',
        'device_performance_acceptance': False,
        'media_headroom_gate_evaluable': False,
        'media_peak_mbps': None,
        'requested_duration_seconds': float(duration),
        'kernel_test_returncode': int(kernel_returncode),
        'started_utc': started_utc,
        'finished_utc': datetime.now(timezone.utc).isoformat(),
        'environment': {
            'platform': platform.platform(),
            'machine': platform.machine(),
            'kernel': platform.release(),
            'python': __import__('sys').version,
            'compiler': compiler,
            'compiler_version': command_version([compiler, '--version']),
            'cmake_version': command_version(['cmake', '--version']),
            'build_type': 'Release',
            'network_mode': network,
        },
        'build': {
            'configure_command': ['cmake', *cmake_args],
            'build_command': ['cmake', *build_args],
            'configure_log': str(output.parent / 'configure.log'),
            'build_log': str(output.parent / 'build.log'),
            'native_source_check': str(output.parent / 'native-source-check.json'),
        },
        'source_sha256': source_sha256(root),
        'binary_sha256': file_sha256(build_dir / 'lwip_kernel_echo'),
        'kernel_log': str(kernel_log),
        'peer_report': None,
        'core_summary': None,
        'commit': None,
        'dirty': None,
    }

    def git(*args):
        return subprocess.check_output(['git', '-C', str(root), *args], text=True,
                                       stderr=subprocess.PIPE).strip()

    try:
        report['commit'] = git('rev-parse', 'HEAD')
        report['dirty'] = bool(git('status', '--porcelain'))
    except (OSError, subprocess.CalledProcessError) as error:
        report['git_error'] = str(error)

    if peer_report_path.is_file():
        try:
            report['peer_report'] = json.loads(peer_report_path.read_text(encoding='utf-8'))
        except (OSError, ValueError) as error:
            report['peer_report_error'] = str(error)
    log = kernel_log.read_text(encoding='utf-8', errors='replace') if kernel_log.is_file() else ''
    match = re.search(r'rx=(\d+) tx=(\d+) not_ready=(\d+) heap_peak=(\d+)', log)
    if match:
        report['core_summary'] = dict(zip(('rx_frames', 'tx_accepted', 'tx_not_ready', 'lwip_heap_peak_bytes'),
                                          map(int, match.groups())))

    peer = report['peer_report']
    if kernel_returncode == 0 and peer and peer.get('status') == 'complete':
        report['status'] = 'complete'
    report['duration_gate_passed'] = bool(report['status'] == 'complete' and peer and
                                          peer.get('duration_gate_passed'))
    temporary = output.with_suffix(output.suffix + '.tmp')
    temporary.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    temporary.replace(output)
    return report


def main(argv=None):
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--peer-report', required=True, type=Path)
    parser.add_argument('--kernel-log', required=True, type=Path)
    parser.add_argument('--compiler', required=True)
    parser.add_argument('--duration', required=True, type=float)
    parser.add_argument('--kernel-returncode', required=True, type=int)
    parser.add_argument('--network', required=True, choices=['veth', 'tap'])
    parser.add_argument('--started-utc', required=True)
    args = parser.parse_args(argv)
    report = build_report(args.root, args.output, args.peer_report, args.kernel_log,
                          args.compiler, args.duration, args.kernel_returncode,
                          args.network, args.started_utc)
    print(args.output.resolve())
    return 0 if report['status'] == 'complete' and args.kernel_returncode == 0 else 1


if __name__ == '__main__':
    import sys
    sys.exit(main())
