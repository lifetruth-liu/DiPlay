#!/usr/bin/env python3
"""Linux libFuzzer driver; CPU gates use wait4 usage, never wall-clock estimates."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import subprocess
import sys
import time


def positive_float(value):
    number = float(value)
    if not math.isfinite(number) or number <= 0:
        raise argparse.ArgumentTypeError('must be finite and positive')
    return number


def positive_int(value):
    number = int(value)
    if number <= 0:
        raise argparse.ArgumentTypeError('must be positive')
    return number


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', required=True, choices=['ethernet', 'commands', 'lifecycle'])
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--binary', type=Path, help='instrumented executable; defaults to OUTPUT/build/lwip_fuzz_TARGET')
    parser.add_argument('--seed', type=positive_int, default=1)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--cpu-hours', type=positive_float)
    mode.add_argument('--runs', type=positive_int, help='smoke only; never satisfies the CPU-time gate')
    mode.add_argument('--replay', type=Path, help='execute one saved input without mutation')
    args = parser.parse_args(argv)
    if args.seed > 2147483647:
        parser.error('--seed must be at most 2147483647')
    if args.cpu_hours is not None and not math.isfinite(args.cpu_hours * 3600):
        parser.error('--cpu-hours is too large')
    return args


def fuzzer_command(binary, args, corpus, crashes, batch):
    seed = (args.seed - 1 + batch) % 2147483647 + 1
    command = [str(binary), '-seed=' + str(seed), '-max_len=8192', '-timeout=10',
               '-artifact_prefix=' + str(crashes) + '/', '-print_final_stats=1']
    if args.replay is not None:
        return command + [str(args.replay)]
    if args.runs is not None:
        command.append('-runs=' + str(args.runs))
    else:
        command.append('-max_total_time=10')
    return command + [str(corpus)]


def budget_complete(requested_seconds, measured_seconds, returncode):
    return requested_seconds is not None and measured_seconds >= requested_seconds and returncode == 0


def run_child(command, log, environment):
    if sys.platform != 'linux' or not hasattr(os, 'wait4'):
        raise RuntimeError('Linux wait4 is required for actual child CPU accounting')
    started = time.monotonic()
    with open(log, 'wb') as stream:
        process = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT, env=environment)
        try:
            _, status, usage = os.wait4(process.pid, 0)
        except BaseException:
            process.terminate()
            _, status, _ = os.wait4(process.pid, 0)
            process.returncode = os.WEXITSTATUS(status) if os.WIFEXITED(status) else -os.WTERMSIG(status)
            raise
        code = os.WEXITSTATUS(status) if os.WIFEXITED(status) else -os.WTERMSIG(status)
        process.returncode = code
    return {'returncode': code, 'user_seconds': usage.ru_utime, 'system_seconds': usage.ru_stime,
            'wall_seconds': time.monotonic() - started, 'max_rss_kib': usage.ru_maxrss,
            'command': command, 'log': str(log)}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def metadata(binary):
    root = Path(__file__).resolve().parents[1]
    def git(*args):
        return subprocess.check_output(['git', '-C', str(root), *args], text=True, stderr=subprocess.PIPE).strip()
    result = {'binary_sha256': digest(binary), 'platform': platform.platform(), 'python': sys.version,
              'machine': platform.machine(), 'commit': None, 'dirty': None}
    try:
        result.update(commit=git('rev-parse', 'HEAD'), dirty=bool(git('status', '--porcelain')))
    except (OSError, subprocess.CalledProcessError) as error:
        result['git_error'] = str(error)
    # Source archives and Windows-managed worktrees mounted in WSL may have no usable
    # Linux Git metadata. Always identify the actual source bytes, including dirty edits.
    source = hashlib.sha256()
    paths = list((root / 'shared/src/main/jni/lwip_backend').rglob('*'))
    paths += [Path(__file__).resolve()]
    for path in sorted(p for p in paths if p.is_file()):
        source.update(path.relative_to(root).as_posix().encode('utf-8') + b'\0')
        source.update(bytes.fromhex(digest(path)))
    result['source_sha256'] = source.hexdigest()
    return result


def main(argv=None):
    args = parse_args(argv)
    if sys.platform != 'linux':
        raise RuntimeError('Run the fuzz campaign on Linux; Windows must use a Linux host/WSL')
    binary = (args.binary or args.output / 'build' / ('lwip_fuzz_' + args.target)).resolve(strict=True)
    if args.replay is not None:
        args.replay = args.replay.resolve(strict=True)
        if not args.replay.is_file():
            raise ValueError('--replay must name one file')
    output = args.output.resolve()
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
    campaign = output / ('campaign-' + stamp)
    campaign.mkdir(parents=True, exist_ok=False)
    corpus, crashes = output / 'corpus', campaign / 'crashes'
    corpus.mkdir(exist_ok=True)
    crashes.mkdir()
    environment = dict(os.environ)
    environment.setdefault('ASAN_OPTIONS', 'detect_leaks=1:halt_on_error=1')
    environment.setdefault('UBSAN_OPTIONS', 'halt_on_error=1:print_stacktrace=1')
    requested = args.cpu_hours * 3600 if args.cpu_hours is not None else None
    report = dict(metadata(binary), target=args.target, seed=args.seed, requested_cpu_seconds=requested,
                  mode='replay' if args.replay else ('smoke' if args.runs else 'cpu-budget'),
                  started_utc=stamp, batches=[], measured_cpu_seconds=0.0, cpu_budget_complete=False,
                  sanitizer_options={key: environment[key] for key in ('ASAN_OPTIONS', 'UBSAN_OPTIONS')})
    report_file = campaign / 'report.json'
    def save():
        temporary = report_file.with_suffix('.tmp')
        temporary.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n', encoding='utf-8')
        temporary.replace(report_file)
    save()
    code = 0
    try:
        while True:
            index = len(report['batches'])
            command = fuzzer_command(binary, args, corpus, crashes, index)
            result = run_child(command, campaign / ('batch-%06d.log' % index), environment)
            report['batches'].append(result)
            report['measured_cpu_seconds'] += result['user_seconds'] + result['system_seconds']
            code = result['returncode']
            report['cpu_budget_complete'] = budget_complete(requested, report['measured_cpu_seconds'], code)
            save()
            if code != 0 or requested is None or report['cpu_budget_complete']:
                break
    except BaseException as error:
        code = 130 if isinstance(error, KeyboardInterrupt) else 1
        report['error'] = type(error).__name__ + ': ' + str(error)
        raise
    finally:
        report['returncode'] = code
        report['finished_utc'] = datetime.now(timezone.utc).isoformat()
        report['corpus'] = {p.name: digest(p) for p in sorted(corpus.iterdir()) if p.is_file()}
        save()
        print(report_file)
    return 0 if code == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
