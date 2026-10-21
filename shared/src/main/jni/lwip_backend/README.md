# Native lwIP prototype

This is the P2 core/harness, not an enabled CarPlay backend. The Android build
packages `libdiplay_lwip.so` for armeabi-v7a, arm64-v8a, x86 and x86_64.
`LwipPrototype.nativeEcho` explicitly loads the P2 echo adapter on its core worker;
production sessions still select the system backend. Production endpoint JNI
and actual iPhone/NCM interoperability remain unimplemented or unverified.
Provenance is in [UPSTREAM.md](UPSTREAM.md).

## Host checks

From the repository root on Linux, with CMake, a C11 compiler, Python 3 and make:

```sh
./scripts/test_lwip_host.sh --sanitize
python3 -m unittest discover -s scripts/tests -p 'test_check_lwip_native.py'
```

On Windows with MinGW, pass the real CMake executable (not a Python launcher):

```powershell
./scripts/test_lwip_host.ps1 -Cmake 'C:/path/to/cmake.exe' -Compiler 'C:/path/to/gcc.exe' -Make 'C:/path/to/mingw32-make.exe'
```

Both runners reject an empty CTest inventory. Thirteen independent frame cases
cover NDP, DAD/conflict, TCP handshake/data, UDP/ICMP, repeated teardown,
readiness/failure, malformed frames, fragment reassembly, thread/reentry and
clock contracts. Sanitizer builds also enable lwIP heap/pool guard and sanity
checks; fixed pools otherwise hide some invalid accesses from ASan.
Six additional adapter cases cover generation exhaustion/stale tokens, wrong
threads, entropy depletion, bounded output, real core timers and teardown.

For Linux kernel interoperability, run on a disposable Linux host with root,
network namespaces and TAP or veth support:

```sh
sudo ./scripts/test_lwip_host.sh --kernel tap
sudo ./scripts/test_lwip_host.sh --kernel veth
```

The helper enters a fresh network namespace before configuring interfaces. Its
independent Python client uses kernel TCP/UDP sockets. WSL1 on the execution
host rejects `unshare --net`; these two modes have **not** passed there.
The test echo service aborts on EOF and is not a production half-close endpoint.

### Disposable Android emulator kernel peer

An Android emulator with `adb root`, network namespaces, TAP/veth and `ip` can
also supply an independent Linux kernel peer. This is test infrastructure;
the product backend requires none of these permissions. Check
`adb -s SERIAL shell getprop ro.kernel.qemu` is `1` before rooting the task-owned
emulator. Build the standalone tools with the installed NDK (use an appropriate
CMake generator for the host):

```sh
cmake -S shared/src/main/jni/lwip_backend -B build/lwip-android-kernel \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=x86_64 -DANDROID_PLATFORM=android-24
cmake --build build/lwip-android-kernel --parallel 2 --target \
  lwip_kernel_echo lwip_kernel_peer lwip_namespace_exec lwip_kernel_offloads
adb -s SERIAL root
adb -s SERIAL wait-for-device
adb -s SERIAL shell mkdir -p /data/local/tmp/diplay-lwip-kernel
adb -s SERIAL push build/lwip-android-kernel/lwip_kernel_echo \
  build/lwip-android-kernel/lwip_kernel_peer \
  build/lwip-android-kernel/lwip_namespace_exec \
  build/lwip-android-kernel/lwip_kernel_offloads \
  scripts/test_lwip_android_kernel.sh /data/local/tmp/diplay-lwip-kernel/
adb -s SERIAL shell 'chmod 700 /data/local/tmp/diplay-lwip-kernel/lwip_*'
adb -s SERIAL shell sh /data/local/tmp/diplay-lwip-kernel/test_lwip_android_kernel.sh tap
adb -s SERIAL shell sh /data/local/tmp/diplay-lwip-kernel/test_lwip_android_kernel.sh veth
```

The launcher verifies a changed network namespace before executing interface
setup. Interface readiness uses `ip link`, since inherited sysfs can show the
parent namespace. Android TAP uses `/dev/tun`. The veth setup disables checksum
and segmentation/aggregation offloads on both isolated interfaces: AF_PACKET
otherwise sees unfinished checksums or oversized offload packets, rather than
wire-valid frames. This does not disable lwIP checksum validation.

The standalone client links only libc and kernel sockets. It checks three TCP
64-KiB echoes and UDP sizes 0/1/1200/4096/8192, including reply address/port and
byte content. TAP and veth passed on the API28 x86_64 task AVD; this is functional
kernel interoperability, not actual USB/NCM, JNI-path, ARM performance or G2
acceptance. Exact evidence is in the execution record.

The optional `slow` scenario uses 4-KiB requested socket buffers, forks a
continuous 1-MiB sender and pauses the reader for ten seconds after the first
successful send. It requires the sender to remain backpressured, then verifies
every recovered byte against its absolute stream position:

```sh
adb -s SERIAL shell sh /data/local/tmp/diplay-lwip-kernel/test_lwip_android_kernel.sh tap slow
adb -s SERIAL shell sh /data/local/tmp/diplay-lwip-kernel/test_lwip_android_kernel.sh veth slow
```

The echo callback consumes large coalesced input in send-buffer-sized pieces;
it retains its offset across refused-pbuf retries without crediting bytes twice.
The deterministic `tcp_large_refused` case covers a 40320-byte callback with an
unchanged 32768-byte send buffer. Aggregate test-tool pressure counters are
sampled outside raw callbacks, not logged per packet. These checks do not prove
production endpoint consumption/cancellation contracts or total memory budgets.

## Ownership and limits

`include/diplay_lwip.h` describes the prototype C ABI. A process has at most one
active context, all calls occur on its creating thread, and callbacks cannot
reenter. Clock and entropy are injected; production entropy must be secure.
The context accepts untagged Ethernet IPv6 only, link-local addressing, MTU
1280–1500. It does not implement IPv4, VLAN, DNS, DHCP or socket APIs.
The lwIP interface index belongs to this context and is not an Android scope ID.

Creating listeners is independent of link readiness. An accepted output means
the caller copied the frame to a bounded queue, not that USB delivered it.
NOT_READY pauses the link; the caller must restore readiness after draining.
Output failure is terminal. DAD remains tentative when its probe was not sent.
Destruction clears TCP/UDP state, reassembly and neighbor queues on the owner
thread; old pointers must never be reused.

`include/diplay_prototype.h` adds opaque generation tokens, not pointer-valued
Java handles. Tokens never wrap; exhausted generations reject new contexts.
The owning thread supplies monotonic time and secure entropy before core calls.
The native output callback only copies into a fixed 128 x 1514-byte ring and
never enters Java. Entropy exhaustion fails closed. JNI uses bounded region
copies, retains no Java references and creates no native threads. The explicit
echo service exposes TCP 7000 and UDP 6000 only on the virtual netif; EOF aborts
TCP and is unsuitable for production endpoint semantics.

Configuration caps include 1 MiB lwIP heap, 256 x 1536-byte pbuf pool, 32 TCP
PCBs, 16 listeners, 32 UDP PCBs, 4 reassemblies and 128 retained fragment pbufs.
The test kernel tool has a separate 256-frame TX ring and bounded RX/TX batches.
These are configuration ceilings, not measured total resident memory or ARM
performance. JNI, USB, thread stacks and media queues need separate budgets.

## Android/package checks

Build through the repository Gradle CI command. Then run:

```sh
python3 scripts/check_lwip_native.py --apk mobile/build/outputs/apk/debug/mobile-debug.apk
```

This verifies the pinned 314-file source inventory, four ELF identities,
16 KiB LOAD/ZIP alignment, non-executable stack, static data budget and packaged
license. It does not prove Android loading, kernel/iPhone interoperability,
throughput or low-end ARM stability. G0/G1/G2 device gates remain unmet.

Run Android runtime checks separately on a task-owned emulator/device:

```sh
./gradlew :shared:connectedDebugAndroidTest --max-workers=2
```

Enable `debug.checkjni=1` before the test process starts. See
[execution evidence](../../../../../docs/lwIP/execution-status.md) for the tested
ABI/API, test results and limits; a successful APK build alone is not JNI evidence.
