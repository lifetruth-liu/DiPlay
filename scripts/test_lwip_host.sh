#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build="${LWIP_BUILD_DIR:-$root/build/lwip-host-linux}"
sanitizers=OFF
kernel_mode=""
while (($#)); do
  case "$1" in
    --sanitize) sanitizers=ON; shift ;;
    --kernel) kernel_mode="${2:?expected tap or veth}"; shift 2 ;;
    --build-dir) build="${2:?expected build directory}"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done
cmake_bin="${CMAKE:-cmake}"
ctest_bin="${CTEST:-ctest}"
"$cmake_bin" --version
"${CC:-cc}" --version
printf 'Pinned lwIP: 2.2.1 / 77dcd25a72509eb83f72b033d219b1d40cd8eb95\n'
python3 "$root/scripts/check_lwip_native.py"
"$cmake_bin" -S "$root/shared/src/main/jni/lwip_backend" -B "$build" \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo -DDIPLAY_SANITIZERS="$sanitizers"
"$cmake_bin" --build "$build" --parallel "${LWIP_JOBS:-2}"
cd "$build"
"$ctest_bin" -N > tests.list
cat tests.list
grep -Eq 'Total Tests: [1-9][0-9]*' tests.list || { echo 'No native tests registered' >&2; exit 1; }
export ASAN_OPTIONS="${ASAN_OPTIONS:-detect_leaks=1:halt_on_error=1}"
export UBSAN_OPTIONS="${UBSAN_OPTIONS:-halt_on_error=1:print_stacktrace=1}"
"$ctest_bin" --output-on-failure --output-log ctest.log
if [[ -n "$kernel_mode" ]]; then
  "$root/scripts/test_lwip_kernel.sh" "$build" "$kernel_mode"
else
  echo 'Deterministic Ethernet tests only; Linux kernel interoperability was not requested.'
fi
