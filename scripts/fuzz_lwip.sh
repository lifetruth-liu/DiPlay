#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Build and run one sanitized libFuzzer target for the pinned lwIP backend.

Usage:
  bash scripts/fuzz_lwip.sh --target {ethernet,commands,lifecycle} --output DIR \
    (--cpu-hours HOURS | --runs COUNT | --replay FILE) [--seed INTEGER]

CPU-hour mode accounts for the child process's measured user and system CPU time.
--runs is a smoke run and never satisfies the 24 CPU-hour acceptance gate.
EOF
}

die() {
  printf 'fuzz_lwip: %s\n' "$1" >&2
  exit 2
}

target=
output=
seed=1
mode=
mode_value=
while (($#)); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --target|--output|--seed|--cpu-hours|--runs|--replay)
      (($# >= 2)) || die "$1 requires a value"
      option=$1
      value=$2
      shift 2
      case "$option" in
        --target) [[ -z "$target" ]] || die '--target may be provided once'; target=$value ;;
        --output) [[ -z "$output" ]] || die '--output may be provided once'; output=$value ;;
        --seed) seed=$value ;;
        *) [[ -z "$mode" ]] || die 'choose exactly one of --cpu-hours, --runs or --replay'; mode=$option; mode_value=$value ;;
      esac
      ;;
    *) die "unknown argument: $1" ;;
  esac
done

case "$target" in
  ethernet|commands|lifecycle) ;;
  *) die '--target must be ethernet, commands or lifecycle' ;;
esac
[[ -n "$output" ]] || die '--output is required'
[[ -n "$mode" ]] || die 'choose one of --cpu-hours, --runs or --replay'
if [[ "$mode" == --replay ]]; then
  [[ -f "$mode_value" ]] || die "replay input is not a file: $mode_value"
  mode_value="$(cd -- "$(dirname -- "$mode_value")" && pwd)/$(basename -- "$mode_value")"
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
driver="$script_dir/lwip_fuzz_driver.py"
generator="$script_dir/generate_lwip_fuzz_seeds.py"
compiler="${CC:-clang}"
command -v "$compiler" >/dev/null 2>&1 || die "Clang compiler not found: $compiler"
command -v cmake >/dev/null 2>&1 || die 'cmake not found'
command -v python3 >/dev/null 2>&1 || die 'python3 not found'

mkdir -p -- "$output"
output="$(cd -- "$output" && pwd)"
build_dir="$output/build"
binary="$build_dir/lwip_fuzz_$target"
driver_args=(--target "$target" --output "$output" --binary "$binary" --seed "$seed" "$mode" "$mode_value")
# Parse and validate the complete driver contract before invoking CMake.
python3 -c 'import os,runpy,sys; sys.path.insert(0, os.path.dirname(os.path.abspath(sys.argv[1]))); runpy.run_path(sys.argv[1])["parse_args"](sys.argv[2:])' "$driver" "${driver_args[@]}"

"$compiler" --version > "$output/compiler-version.txt"
cmake --version > "$output/cmake-version.txt"
python3 "$generator" --target "$target" --output "$output/corpus"

cmake_args=(-S "$repo_root/shared/src/main/jni/lwip_backend" -B "$build_dir"
  -DCMAKE_C_COMPILER="$compiler" -DCMAKE_BUILD_TYPE=RelWithDebInfo
  -DDIPLAY_SANITIZERS=ON -DDIPLAY_FUZZ=ON)
if ! cmake "${cmake_args[@]}" > "$output/configure.log" 2>&1; then
  cat "$output/configure.log" >&2
  exit 1
fi
build_args=(--build "$build_dir" --target "lwip_fuzz_$target" --parallel 2)
if ! cmake "${build_args[@]}" > "$output/build.log" 2>&1; then
  cat "$output/build.log" >&2
  exit 1
fi

python3 - "$output/build-metadata.json" "$target" "$compiler" "${cmake_args[@]}" -- "${build_args[@]}" <<'PY'
import json
from pathlib import Path
import subprocess
import sys

destination = Path(sys.argv[1])
target = sys.argv[2]
compiler = sys.argv[3]
separator = sys.argv.index('--')
cmake_args = sys.argv[4:separator]
build_args = sys.argv[separator + 1:]
metadata = {
    'target': target,
    'compiler': compiler,
    'compiler_version': subprocess.check_output([compiler, '--version'], text=True).splitlines()[0],
    'cmake_version': subprocess.check_output(['cmake', '--version'], text=True).splitlines()[0],
    'configure_command': ['cmake', *cmake_args],
    'build_command': ['cmake', *build_args],
    'sanitizers': ['address', 'undefined', 'lwip-pool-guards'],
    'coverage': 'libFuzzer/LLVM SanitizerCoverage',
}
temporary = destination.with_suffix('.tmp')
temporary.write_text(json.dumps(metadata, indent=2, sort_keys=True) + '\n', encoding='utf-8')
temporary.replace(destination)
PY

cd -- "$repo_root"
exec python3 "$driver" "${driver_args[@]}"
