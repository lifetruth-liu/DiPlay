# USB recovery D/E execution plan — 2026-10-25

Approved scope follows phases D/E in wired-fallback-and-recovery-plan-2026-10-24.md.

1. Audit control/media TCP, service lease and native close semantics. Accepted control/event/IAP tunnel streams use reset-on-close. Native graceful retirement can queue a FIN but cannot prove peer delivery; do not label local drain as FIN/ACK completion.
2. Add failing tests for retained network ownership, in-flight NCM sends, NotReady, cancellation and a hard deadline with a paused command clock. Implement a split session close/network release and local egress observation without native protocol changes.
3. Integrate a debug-only GRACEFUL close candidate for voluntary active USERSPACE sessions on the current USB enumeration. Keep service binding, NCM and iAP2 until session close/local drain completes or the whole 750 ms deadline expires. Failures and detach abort. Default LEGACY and VPN remain unchanged pending independent hardware validation.
4. Preserve production Start quiet=2000 ms, poll=500 ms and one resend/rearm. Permit debug-only launch quiet=1000/500 ms. Add exact-boundary, TCP-before-Start, active and cancellation tests.
5. Show localized changing-USB, authentication, waiting-network, establishing-session and VPN-to-userspace stages. Keep stages monotonic using actual link/TCP evidence. No settings or layout change.
6. Run targeted tests, full AGENTS Android checks, public-tree and four-ABI APK checks. Review implementation and fix findings.
7. Reinstall preserving app data at 192.168.0.103:5555. Collect baseline and candidate logs with each experiment isolated. Do not promote defaults without the planned 30-cycle comparison; record failures and unavailable matrix coverage.

No speed improvement or iPhone stale-session root cause is assumed. A local egress result only describes host output; FIN may remain deferred within the native stack.
## Close-semantics audit

`AirPlaySessionHost.acceptLoop`, `AirPlaySession` event connection and `IapTunnel` explicitly request reset-on-close. The candidate clears that option for the old session's control/event/tunnel TCP streams; screen/audio TCP uses ordinary close already. The existing default remains unchanged.

`LwipTcpStream.close` retires a resource through the core command queue. Native `dp_tcp_close_graceful` may queue a FIN, defer it behind DAD or allocation pressure, or reset unread receive data. A drained Java TX queue alone therefore proves neither FIN creation nor delivery. The local observation waits for close-resource commands, native output exhaustion, Java queue and in-flight USB send completion. Historical/current NotReady prevents an affirmative result, but must not bypass the barrier wait.

USBMUX channel close submits FIN|ACK synchronously to the USB pipe; USBMUX host close itself only closes its local channels/pipe. No peer acknowledgement barrier or undocumented CarPlay stop opcode is introduced. The VPN transport has no equivalent observable NCM egress barrier and remains on its existing path.

The retained lease releases only its own old network. The session service stays bound through the experiment. A dedicated daemon worker closes old sessions then waits for output; the owning teardown thread enforces one 750 ms monotonic deadline across both operations. Detach cancels observation. The old network can be aborted independently of a blocked media callback, and a timeout is not certified as full resource release.

## Delivery result

Full Android CI passed: 3542 unit tests, three lint checks and three debug APK builds; four-ABI packaging and public-tree checks passed. Final APK SHA-256: d8b96045cb91b888e0dd5b68b12f73f2bb2a5c1103b2df7204a2d294160e5e02. The device was updated preserving app data. Seventeen successful voluntary reconnects were observed, including warmups and collector-recovery samples; one separate post-install startup attempt failed before automatic recovery. Four three-sample groups did not establish a close-order or quiet-window performance benefit. Production defaults remain LEGACY and 2000 ms. The final active device session uses those defaults. See reconnect-latency-2026-10-25-de-report.md and its sanitized JSON for evidence and limitations.
