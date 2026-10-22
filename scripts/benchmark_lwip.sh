#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Build a release-like lwIP host and measure TCP/UDP echo throughput and RTT over an isolated Linux peer.

Usage:
  bash scripts/benchmark_lwip.sh --output DIR [--duration SECONDS] [--network veth|tap]

The default 600-second run is a host microbenchmark. It does not measure USB,
Android, iPhone media load, PSS, or the device performance acceptance gates.
Linux root plus network-namespace and CAP_NET_ADMIN privileges are required.
EOF
}

die() {
  printf 'benchmark_lwip: %s\n' "$1" >&2
  exit 2
}

output=
duration=600
network=veth
while (($#)); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --output|--duration|--network)
      (($# >= 2)) || die "$1 requires a value"
      option=$1
      value=$2
      shift 2
      case "$option" in
        --output) [[ -z "$output" ]] || die '--output may be provided once'; output=$value ;;
        --duration) duration=$value ;;
        --network) network=$value ;;
      esac
      ;;
    *) die "unknown argument: $1" ;;
  esac
done
[[ -n "$output" ]] || die '--output is required'
[[ "$network" == veth || "$network" == tap ]] || die '--network must be veth or tap'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
benchmark_client="$script_dir/lwip_kernel_benchmark.py"
command -v python3 >/dev/null 2>&1 || die 'python3 not found'
# Validate duration before checking host privileges, building binaries or creating output.
python3 -c 'import runpy,sys; runpy.run_path(sys.argv[1])["parse_args"](["--interface","dp-peer0","--duration",sys.argv[2],"--report","report.json"])' \
  "$benchmark_client" "$duration"

compiler="${CC:-cc}"
command -v "$compiler" >/dev/null 2>&1 || die "host compiler not found: $compiler"
command -v cmake >/dev/null 2>&1 || die 'cmake not found'
[[ "$(uname -s)" == Linux ]] || die 'benchmark requires Linux'
[[ "$(id -u)" == 0 ]] || die 'run with root (sudo) to create the isolated network peer'
command -v unshare >/dev/null 2>&1 || die 'unshare is required for network isolation'
command -v ip >/dev/null 2>&1 || die 'iproute2 is required for the isolated peer'
unshare --net true >/dev/null 2>&1 || die 'root lacks permission to create an isolated network namespace'
if [[ "$network" == tap ]]; then
  [[ -c /dev/net/tun ]] || die 'TAP mode requires /dev/net/tun'
fi

mkdir -p -- "$output"
output="$(cd -- "$output" && pwd)"
started_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
build_dir="$output/build"
compiler_version="$output/compiler-version.txt"
cmake_version="$output/cmake-version.txt"
"$compiler" --version > "$compiler_version"
cmake --version > "$cmake_version"
python3 "$repo_root/scripts/check_lwip_native.py" > "$output/native-source-check.json"

cmake_args=(-S "$repo_root/shared/src/main/jni/lwip_backend" -B "$build_dir"
  -DCMAKE_C_COMPILER="$compiler" -DCMAKE_BUILD_TYPE=Release -DDIPLAY_SANITIZERS=OFF)
if ! cmake "${cmake_args[@]}" > "$output/configure.log" 2>&1; then
  cat "$output/configure.log" >&2
  exit 1
fi
build_args=(--build "$build_dir" --target lwip_kernel_echo lwip_kernel_offloads --parallel 2)
if ! cmake "${build_args[@]}" > "$output/build.log" 2>&1; then
  cat "$output/build.log" >&2
  exit 1
fi

peer_report="$output/peer-report.json"
kernel_log="$output/kernel.log"
kernel_status=0
if bash "$script_dir/test_lwip_kernel.sh" "$build_dir" "$network" echo "$duration" "$peer_report" > "$kernel_log" 2>&1; then
  kernel_status=0
else
  kernel_status=$?
  cat "$kernel_log" >&2
fi

python3 "$script_dir/lwip_benchmark_report.py" --root "$repo_root" --output "$output/report.json" \
  --peer-report "$peer_report" --kernel-log "$kernel_log" --compiler "$compiler" \
  --duration "$duration" --kernel-returncode "$kernel_status" --network "$network" \
  --started-utc "$started_utc"

[[ "$kernel_status" -eq 0 ]] || exit "$kernel_status"
