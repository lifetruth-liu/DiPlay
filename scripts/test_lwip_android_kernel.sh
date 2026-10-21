#!/system/bin/sh
# Device-side test helper. Run only in a disposable rooted Android emulator.
set -eu
root="$(cd "$(dirname "$0")" && pwd)"
if [ "${1:-}" != --child ]; then
    [ "$(getprop ro.kernel.qemu)" = 1 ] || { echo 'Requires a disposable Android emulator' >&2; exit 1; }
    [ "$(id -u)" = 0 ] || { echo 'Requires emulator adb root' >&2; exit 1; }
    case "${1:-}" in tap|veth) ;; *) echo 'Expected tap or veth' >&2; exit 2 ;; esac
    [ "$#" -le 2 ] || exit 2
    case "${2:-basic}" in basic|slow) ;; *) echo 'Expected basic or slow scenario' >&2; exit 2 ;; esac
    exec "$root/lwip_namespace_exec" sh "$0" --child "$1" "$(readlink /proc/self/ns/net)" "${2:-basic}"
fi
[ "$#" = 4 ] || exit 2
[ "$(readlink /proc/self/ns/net)" != "$3" ] || { echo 'Refusing parent namespace' >&2; exit 1; }
mode="$2"
scenario="$4"
case "$scenario" in basic|slow) ;; *) exit 2 ;; esac
case "$mode" in tap|veth) ;; *) exit 2 ;; esac
pid=''
cleanup() {
    if [ -n "$pid" ]; then kill "$pid" 2>/dev/null || true; wait "$pid" || true; fi
    [ ! -f "$root/kernel-$mode.log" ] || cat "$root/kernel-$mode.log"
}
trap cleanup EXIT
trap 'exit 1' INT TERM HUP
ip link set lo up
if [ "$mode" = veth ]; then
    ip link add dp-lwip0 type veth peer name dp-peer0
    "$root/lwip_kernel_offloads" dp-lwip0
    "$root/lwip_kernel_offloads" dp-peer0
    echo 1 > /proc/sys/net/ipv6/conf/dp-lwip0/disable_ipv6
    ip link set dp-lwip0 address 02:00:00:00:00:01 up
    peer=dp-peer0
else
    peer=dp-lwip0
fi
"$root/lwip_kernel_echo" "--$mode" dp-lwip0 > "$root/kernel-$mode.log" 2>&1 &
pid=$!
attempt=0
while ! ip link show dev "$peer" >/dev/null 2>&1; do
    kill -0 "$pid" 2>/dev/null || exit 1
    attempt=$((attempt + 1))
    [ "$attempt" -lt 100 ] || { echo 'Interface creation timed out' >&2; exit 1; }
    sleep 0.05
done
ip link set "$peer" address 02:00:00:00:00:02 mtu 1500 up
ip -6 addr add fe80::2/64 dev "$peer" nodad
if [ "$scenario" = slow ]; then
    "$root/lwip_kernel_peer" "$peer" --slow
else
    "$root/lwip_kernel_peer" "$peer"
fi
kill "$pid"
wait "$pid"
pid=''
echo "PASS Android Linux kernel $scenario via $mode (isolated namespace)"
