# P2 transport runner

`LwipPrototype` owns an `EthernetFramePort` and a factory for `LwipPrototypeCore`.
The factory is required: no JNI factory or user-visible backend selection is
installed yet. The tests inject a controlled core, not the compiled lwIP library.
This runner alone does not establish NCM/lwIP interoperability or pass G2.

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
The real native adapter must preserve the tested netif/DAD semantics; that
cross-boundary behavior still needs JNI integration tests.

## Resource accounting still to finish

| Owner | Current limit/status |
| --- | --- |
| Runner RX queue | 384 KiB payload and 256 frames |
| Runner TX queue | 192 KiB payload and 128 frames |
| Native output owner | Adapter contract requires at most 256 KiB including metadata; adapter not implemented |
| In-flight frames | One per RX, TX and core call, each at most 1514 bytes |
| Existing NcmUsbBridge | Retains its legacy 1 MiB/256-frame receive queue and separate read/reassembly buffers |
| Worker stacks | Up to four owned workers; Android/ARM stack cost not measured |

The runner limits do **not** prove the plan's combined 512 KiB ingress/egress
budget. Before enabling the prototype, the NCM adapter must configure a smaller
receive allowance without changing legacy VPN defaults, and the native/Java
handoff must account for all pools, metadata and temporary copies. Existing NCM
buffers and worker stacks also belong in the total 16 MiB accounting. None of
the Android memory, throughput or shutdown performance gates have passed.
Thread-creation/allocation failures and failed USB close still need explicit
fault injection and quarantine/reuse tests before production integration.

## Checks

```powershell
./gradlew.bat :shared:testDebugUnitTest --tests '*LwipPrototypeTest' --max-workers=2
```

Eleven controlled tests cover preparation before USB readiness, thread ownership,
NotReady/drop accounting, send failure, RX overflow, blocked TX backpressure,
close during an active core call, preparation failure, coalesced readiness,
late-created close workers, repeated close timeouts and long explicit waits.
Some cases cover multiple invariants. Full Android CI and run evidence are in
[execution-status.md](execution-status.md).
