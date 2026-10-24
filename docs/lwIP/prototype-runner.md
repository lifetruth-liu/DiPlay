# P2 transport runner

`LwipPrototype` owns an `EthernetFramePort` and a factory for `LwipPrototypeCore`.
Controlled JVM tests inject a core; `LwipPrototype.nativeEcho` explicitly creates
the real native echo adapter on the core worker. It copies configuration at
construction and owns the supplied port. No user-visible backend selection is
installed. This runner does not establish actual NCM/iPhone interoperability or
pass G2.

## Threads and ownership

- The core thread creates, prepares, polls and destroys its core. Input arrays
  are borrowed for a call; output arrays transfer ownership out of the core.
  No native callback into Java is required by this pull boundary.
- One USB IN worker calls the port's existing asynchronous-request-backed recv.
  One USB OUT worker performs blocking sends. Both exchange frames through
  bounded queues. Native operations and USB transfers never share a thread.
- A close worker cancels/closes USB independently of a stalled core or writer.
  Stop rejects further queue publication and clears queued frame references.
  The owner closes the core after its current call returns. An expired join
  leaves that ownership intact; it does not force-free a live native object.
- Worker allocation and startup failures enter the same stop path. If the USB
  close worker cannot be created or started, the runner records that failure
  and closes the port inline; when stop originates on the core worker, inline
  close waits until the core has been destroyed. A failed inline USB close is
  retained as a suppressed failure. `awaitStopped` waits for this close to
  finish before publishing STOPPED.
- `awaitStopped` rechecks the current worker set, including a close worker
  created after waiting began. Only a completed join publishes STOPPED.
  Failures remain in snapshots, with at most four additional suppressed causes.

The loop consumes at most 32 frames per batch and waits for input, readiness,
queue space or the next core timer. Closing defaults to a two-second total join
budget; a caller can choose a shorter budget. These are bounds, not measured
Android scheduling guarantees.

## Readiness

Preparing listeners does not wait for USB to accept a frame. LISTENING means
prepare completed; LINK_ACTIVE means a whole USB block was reported transferred,
not that DAD, TCP or CarPlay has completed.

Initial probes may be queued while USB is still NAKing. After handing off
unconfirmed startup output, the runner pauses the core link so a queued probe
does not count as a delivered DAD probe. NotReady drops and counts the unsent
frame; the queue does not replay it. The core is allowed to probe again after
100 ms, and protocol timers govern actual retransmission. A NotReady transition
is latched even when a later successful send arrives before the core wakes.
The native adapter preserves netif/DAD semantics. Emulator tests exercise real
timer output through JNI and the runner; USB readiness and actual device DAD
still require NCM device coverage.

## Resource accounting still to finish

| Owner | Current limit/status |
| --- | --- |
| Runner RX queue | 128 KiB payload and 128 frames |
| Runner TX queue | 192 KiB payload and 128 frames |
| Native output owner | Fixed 128 x 1514-byte ring; complete adapter context below 256 KiB |
| In-flight frames | One per RX, TX and core call, each at most 1514 bytes |
| NcmFramePort | Configures bridge to 64 KiB/256 frames, with 14–1514-byte input; legacy VPN defaults remain 1 MiB/256 frames |
| NCM read/reassembly | Two 32 KiB read buffers; reassembly grows through 32/64/128 KiB capacities |
| Worker stacks | Up to four owned workers; Android/ARM stack cost not measured |

The parser checks frame lengths and cumulative quotas before copying payloads,
rejects cyclic NDP chains and caps table/entry traversal. Prototype receive
parses one productive NTB then lets its consumer drain, so coalesced blocks do
not spuriously exhaust the smaller queue. Oversized frames are counted/dropped;
an admitted-frame quota violation terminates with RESOURCE_LIMIT.

Conservative ingress payload/allocation ceilings total 480 KiB (runner 128,
bridge queue 64, read buffers 64, all reassembly growth allocations 224), leaving
32 KiB for frame/collection metadata and in-flight copies. Egress combines the
192 KiB runner queue with the adapter's below-256-KiB allocation and small copy
buffers. These estimates are **not measured budget acceptance**: managed heap
retention, lwIP pools, USB machinery and worker stacks still belong in total
16 MiB accounting. None of
the Android memory, throughput or shutdown performance gates have passed.
Thread allocation/start failure and failed inline USB close now have explicit
fault injection. Quarantine/reuse behavior still needs separate tests before
production integration.

## Checks

```powershell
./gradlew.bat :shared:testDebugUnitTest --tests '*LwipPrototypeTest' --max-workers=2
```

Twenty controlled tests cover preparation before USB readiness, thread
ownership and allocation failure, NotReady/drop accounting, send failure, RX
overflow, blocked TX backpressure, close during an active core call, preparation
failure, coalesced readiness, late-created close workers, close failure,
repeated close timeouts and long explicit waits. Some cases cover multiple
invariants. Full Android CI and run evidence are in
[execution-status.md](execution-status.md).

The Android instrumentation suite also loads the actual library, verifies JNI
array bounds, old/wrong-thread handles, repeated recreation and timer output,
and runs the explicit factory through its real core/I/O workers. Native secure
entropy is supplied from `SecureRandom` outside raw callbacks. No raw callback
logs, blocks on USB or calls Java.
