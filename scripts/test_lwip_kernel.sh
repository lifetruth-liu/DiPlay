#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build="$(cd "${1:?expected native build directory}" && pwd)"
mode="${2:-tap}"
backend="${3:-echo}"
benchmark_seconds="${4:-}"
benchmark_report="${5:-}"
[[ "$mode" == tap || "$mode" == veth ]] || { echo 'Expected tap or veth' >&2; exit 2; }
[[ "$backend" == echo || "$backend" == endpoints ]] || { echo 'Expected echo or endpoints' >&2; exit 2; }
if [[ -n "$benchmark_seconds" || -n "$benchmark_report" ]]; then
  [[ -n "$benchmark_seconds" && -n "$benchmark_report" && "$backend" == echo ]] || {
    echo 'Benchmark mode requires duration, report path and echo backend' >&2; exit 2;
  }
fi
[[ "$(uname -s)" == Linux && "$(id -u)" == 0 ]] || { echo 'Kernel interoperability requires Linux root with isolated network namespace/interface privileges' >&2; exit 1; }
[[ -x "$build/lwip_kernel_echo" ]] || { echo 'Build lwip_kernel_echo first' >&2; exit 1; }
namespace="$(readlink /proc/self/ns/net)"
if [[ -z "${DIPLAY_LWIP_PARENT_NS:-}" ]]; then
  args=("$build" "$mode" "$backend")
  if [[ -n "$benchmark_seconds" ]]; then args+=("$benchmark_seconds" "$benchmark_report"); fi
  exec unshare --net env DIPLAY_LWIP_PARENT_NS="$namespace" "$0" "${args[@]}"
fi
[[ "$namespace" != "$DIPLAY_LWIP_PARENT_NS" ]] || { echo 'Refusing to modify the parent network namespace' >&2; exit 1; }
pid=""
cleanup() { if [[ -n "$pid" ]]; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi; }
trap cleanup EXIT
ip link set lo up
if [[ "$mode" == veth ]]; then
  ip link add dp-lwip0 type veth peer name dp-peer0
  "$build/lwip_kernel_offloads" dp-lwip0
  "$build/lwip_kernel_offloads" dp-peer0
  sysctl -q -w net.ipv6.conf.dp-lwip0.disable_ipv6=1
  ip link set dp-lwip0 address 02:00:00:00:00:01 up
  peer=dp-peer0
else
  [[ -c /dev/net/tun ]] || { echo 'Linux TAP device is unavailable' >&2; exit 1; }
  peer=dp-lwip0
fi
options=("--$mode" dp-lwip0)
if [[ "$backend" == endpoints ]]; then options+=(--endpoints); fi
"$build/lwip_kernel_echo" "${options[@]}" > "$build/kernel-$mode.log" 2>&1 &
pid=$!
for ((attempt=0;attempt<100;attempt++)); do
  ip link show dev "$peer" >/dev/null 2>&1 && break
  kill -0 "$pid" 2>/dev/null || { cat "$build/kernel-$mode.log" >&2; exit 1; }
  sleep 0.05
done
ip link set "$peer" address 02:00:00:00:00:02 mtu 1500 up
ip -6 addr add fe80::2/64 dev "$peer" nodad
if [[ -n "$benchmark_seconds" ]]; then
  python3 "$root/scripts/lwip_kernel_benchmark.py" --interface "$peer" \
    --duration "$benchmark_seconds" --report "$benchmark_report"
elif [[ "$backend" == endpoints ]]; then
  "$build/lwip_kernel_peer" "$peer" --endpoints
  for ((attempt=0;attempt<100;attempt++)); do
    grep -q '^endpoint-active: verified$' "$build/kernel-$mode.log" && break
    kill -0 "$pid" 2>/dev/null || { cat "$build/kernel-$mode.log" >&2; exit 1; }
    sleep 0.05
  done
  grep -q '^endpoint-active: verified$' "$build/kernel-$mode.log"
else
  python3 "$root/scripts/lwip_kernel_client.py" "$peer"
fi
kill "$pid"
wait "$pid"
pid=""
cat "$build/kernel-$mode.log"
echo "PASS Linux kernel $backend IPv6 TCP/UDP via $mode (isolated namespace)"
