# Reviewed local backports and corrections

Base: lwIP 2.2.1, commit `77dcd25a72509eb83f72b033d219b1d40cd8eb95`.
`UPSTREAM-SHA256.txt` remains the original release inventory. The verifier pins
its digest and requires the original digest below to match that inventory before
accepting exactly the reviewed post-image. No other vendor modification is allowed.

## Reassembly extension-header restart (local correction, 2026-10-22)

- File: `src/core/ipv6/ip6.c`, cumulative with the large-payload correction below.
- Previous post-image: `2c9d7c76fd3d5c990d2967cd2ae8f9e240054e115892085e974c0ce8f191ecd6`.
- Current post-image: `38bf7badfed81fdd58543f18c344966214aaa4235fed1d8d72a0f34019d8ce90`.
- After successful reassembly, restart parsing at the base header. A leading
  Hop-by-Hop header remains first and must not be rejected by the mid-chain HBH
  check. Explicitly reject Fragment Header -> HBH before reassembly, including
  atomic fragments; restarting must not make this illegal order acceptable.
- Regression: one/two pre-fragment extension headers (HBH/Destination Options),
  ordered/reversed fragments, normal/separate-base-header/max/overflow lengths,
  continued delivery and zero retained pbufs after acceptance/drop. Four negative
  Fragment -> HBH vectors keep the complete UDP header in the first fragment so
  rejection cannot be credited to an unrelated first-pbuf header-size check.
- This is local, not an upstream commit/CVE claim or independent security review.

## Full IPv6 UDP payload fragmentation/reassembly (local correction, 2026-10-22)

- Local implementation, not an upstream commit or CVE fix. It retains the pbuf
  u16 overflow guard and the 65527-byte public UDP validation limit.
- `src/core/ipv6/ip6.c`: original
  `b46f5bcc0d086552502bd0db36198a9a804f22efcb97683bc968e20099025dec`, post-image before
  the extension-header correction above:
  `2c9d7c76fd3d5c990d2967cd2ae8f9e240054e115892085e974c0ce8f191ecd6`.
- `src/core/ipv6/ip6_frag.c`: cumulative with the existing fragment backport;
  previous post-image `6d3783d2fcbe1187eac34a6b732436447288b9740b6ec9a8d69002acb08680f3`,
  current `23a4c2b37ee48527afb642bf1568a84b6e0ece67f19a9cda93371e3a674dd655`.
- `src/include/lwip/ip6_frag.h`: original
  `0dcea4a7600b856877e7fb4943b20690aa9e2b69414b4b2273c7e9e0a25f74a3`, current
  `b38cc1cc2df8b90d77671326a24b58178f2183cf2b623103eb5b7ac318efb178`.
- TX: when a legal payload plus the base IPv6 header cannot fit the u16 pbuf,
  retain the base header separately and pass the payload through the existing
  fragment loop, sharing its ID generator and netif output. Enable this path
  only with fragmentation enabled and loopback disabled, as in this port.
  The new path propagates output errors, including after partial output; legacy
  fragment output error behavior is unchanged.
- RX: strip fragment/helper metadata before concatenation, avoiding temporary
  u16 wrap. Reassembled payloads exceeding the full-header pbuf limit keep the
  base header in first-pbuf headroom; ip6_input consumes the payload separately.
  Reject extension-adjusted payload lengths above65535. The compatibility
  ip6_reass entry drops oversized results when it cannot return a separate header.
- Header pointers are borrowed only while the owning pbuf is alive. Operations
  remain outside assertion expressions, so disabling assertions does not skip
  header removal. No heap/pool/queue budget or public C/JNI ABI is increased.
- Evidence includes ordered/reordered32768/65487/65488/65527-byte native vectors,
  output failures at fragment0/1/45, actual JNI and independent kernel peer tests.
  Broader extension-header/fault matrices and independent security review remain
  open; this is not full upstream security approval.

## TCP timestamp unsigned shift (local correction, 2026-10-22)

- File: `src/core/tcp_in.c`, cumulative with the TCP option reader backport below.
- Previous post-image: `bb0062732bf843e8b19b55aa9ee8e17c35f36dd6bf35a1321307ce53a0fff717`.
- Current post-image: `b121300a5e5a765903d2ff19a39cf54267ce0f11a07a701694f665ff3e406243`.
- Promote the final timestamp byte to `u32_t` before shifting it by 24. Integer
  promotion previously made this a signed `int` shift; values >= 128 overflowed
  that type. No timestamp byte order, option length or buffer ownership changes.
- This is a local correction, not attributed to an upstream commit or CVE. The
  verifier reports it separately while retaining the upstream backport identity,
  immutable original release manifest and exact cumulative post-image check.
- Evidence: Android kernel-peer ASan/UBSan ingress failed at tcp_in.c:2003 with
  byte 143; the real-parser test reproduces it on Linux UBSan. Values 0x0102038f,
  0x80000000 and 0xffffffff cover every 12-byte option split, SYN and established
  ACK updates. This does not constitute complete timestamp/PAWS security coverage.

## TCP option byte bounds

- Official upstream commit: `3d896ba0a37ff3ce73270ca5e230707fe47f60e3`.
- File: `src/core/tcp_in.c`.
- Original SHA-256: `b4d1f0bf55dd782aa75f94069284bf99019aa8c42ee040c4e9e966ae8172b443`.
- Reader-only post-image (before the local correction above): `bb0062732bf843e8b19b55aa9ee8e17c35f36dd6bf35a1321307ce53a0fff717`.
- Change: the upstream four-line guard in `tcp_get_next_optbyte` returns EOL
  before accessing an option byte at or beyond the declared option length.
- Regression: `tcp_option_tests.c` compiles the real `tcp_in.c` in a test-only
  translation unit, with no product test hooks. Exact-size allocations put ASan
  redzones at the end of contiguous options and every split position (1, 2, 3).
  Truncated MSS and unknown options preserve the PCB MSS; valid MSS still parses.
  These exercise the parser's two-buffer representation, not the full ingress
  pbuf header-removal path, which remains covered separately by frame tests.
- Red: unmodified release produces ASan heap-buffer-overflow in the reader from
  `tcp_parseopt`, one byte beyond a 24-byte allocation. Green: all 26 host tests
  pass with ASan/UBSan/pool guards, and all 26 pass on Windows MinGW.

## IPv6 empty reassembly cleanup

- Official upstream commit: `56b29f8bcfaefe2974dca67bde16cc7c391feaeb`, IPv6
  portion only (IPv4 is disabled and its vendor source remains unchanged).
- File: `src/core/ipv6/ip6_frag.c`.
- Original SHA-256: `c770139ab8d31da076fbf9b74a260511b647c377241bf1344547d02f6e0cc99d`.
- Backport-only post-image SHA-256: `6d3783d2fcbe1187eac34a6b732436447288b9740b6ec9a8d69002acb08680f3`;
  the current cumulative post-image is recorded in the large-payload correction above.
- Change: wrap first-fragment ICMP generation in the upstream non-null pbuf guard.
- Regression: `reassembly_tests.c` compiles real `ip6_frag.c`, calls `ip6_reass`
  with a valid 129-pbuf chain, exceeds the configured 128-pbuf quota and confirms
  the rejected chain is freed while an empty context remains. Real timer expiry
  then frees the context. Eight cycles confirm zero pool use after each cleanup.
- Red: UBSan null pbuf member access in `ip6_reass_free_complete_datagram`,
  reached from `ip6_reass_tmr`. Green: 27/27 host tests on Linux with sanitizers
  and pool guards, and on Windows MinGW; integrity verifier tests 7/7.
- Scope: this is a stack-internal quota failure regression. The current bounded
  Ethernet ingress copies each frame into one pool pbuf; this test does not
  demonstrate remote reachability through that ingress or exercise allocation
  failure injection. Existing real-frame tests still cover successful reassembly
  and teardown of incomplete fragments.

## NDP option dependency chain

- Official upstream commits, in order:
  `0a65e654943c7e91c8f1129513572e99072ef142` (option search),
  `db4e4c9d0e19255f908d4eec17a52407fa5b44c5` (casts),
  `8e75a40acfea6b05ee643099e41f3b2e11ee464d` (bounded address copy),
  `7d1327e51407440350c5687d65dfa78de0f8f961` (remaining cast).
- File: `src/core/ipv6/nd6.c`.
- Original SHA-256: `c415888732a6b68e9276a6f50276bf1de524b0aa2403121b18c15b1bec3fe0b4`.
- Upstream-chain post-image SHA-256: `ae81f842253339b37a749f4683b5a5be3d3d41eed27065e2fad4893b8d060b0c`.
- Post-image with local cursor guard (before PMTU change below): `73aef9287f9afbc1a40ed08094b0c7b925733c1fc16a623f47bbb924d07f56fe`.
- Provenance: the original file matches the pinned tag after newline normalization.
  The official file at the chain's final commit differs from that tag by exactly
  these four commits. Original CRLF representation is preserved. The verifier
  reports the chain's final commit as its identifier, and pins the entire post-image,
  including the local cursor guard described below.
- Regression: real Ethernet/IPv6 Neighbor Solicitation with a valid unknown
  option before Source LL Address. Original release responds to the unknown
  option's `a5` bytes as a MAC; the backport responds to the actual peer MAC.
  Zero-length preceding options and truncated following LL options produce no NA.
- Scope: the initial directed frame test covered NS. NA, Redirect and RA entry
  checks were added on 2026-10-23; remaining coverage limits are listed below.
- Additional local guard: reject an option length exceeding the remaining total
  pbuf bytes before advancing the 16-bit offset. A direct real-parser regression
  at the 65535-byte boundary fails on the upstream chain: the cursor wraps and
  returns an unrelated earlier option. The guard makes it return NULL. This is
  an in-memory parser boundary test, not a remote exploitability claim or CVE.
  `ndp_option_tests.c` also verifies the real address-copy helper reads only the
  six bytes available in an eight-byte option when hardware length is eight.
  The guard is local; it must not be attributed to the upstream commits above.

## IPv6 minimum PMTU validation

- Based on upstream `d08f4773edd0182b7910fc8f046eed82ffcd67c9`.
- Same file: `src/core/ipv6/nd6.c`; post-image before the NDP entry validation below:
  `0117ecded27bf8382a1c977ec259b3a8cc8dc34aa07fa6ae452e58dcad4aa8ee`.
- Adaptation: apply the upstream lower-bound rejection at 1280, while preserving
  the original full 32-bit decoded MTU and saturation at 65535. Do not apply the
  upstream low-16-bit mask, which would interpret larger values modulo 65536.
  The verifier uses this latest upstream commit to identify the cumulative
  nd6.c post-image; the local deviations remain explicitly recorded here.
- Regression: real Ethernet/IPv6 Packet Too Big quoting a packet actually emitted
  by the UDP echo service. Assert destination-cache MTU and subsequent echo for
  advertisements 1, 0, 1279, 1280, 1400, 65535, 65536 and UINT32_MAX. Original
  release fails the lower-bound expectation; adapted fix passes.
- This tests value handling and continued UDP delivery, not authentication of
  every quoted packet, large TCP transfer recovery or all RFC PMTU behaviors.

## NDP message entry validation (local correction, 2026-10-23)

- File: `src/core/ipv6/nd6.c`, cumulative with the upstream NDP dependency chain,
  local cursor guard and PMTU adaptation above.
- Previous post-image: `0117ecded27bf8382a1c977ec259b3a8cc8dc34aa07fa6ae452e58dcad4aa8ee`.
- Current post-image: `77dcd0cf78909203632185ca43f5fc5c3b6262022e1ff05f2f45751b86621eaf`.
- Before an ND message can change neighbor, router or destination-cache state,
  validate the complete option chain for nonzero lengths and in-bounds extents.
  Enforce NA source/destination flag constraints; enforce the unspecified-source
  NS solicited-node destination and absence of Source LL options; require a
  Redirect source to match the cached first-hop router and its target to be
  link-local or equal to the redirected destination.
- Real Ethernet/IPv6 input regressions cover NA solicited-to-multicast, unspecified
  NA source and malformed trailing options; NS malformed trailing options and
  both unspecified-source restrictions; RA malformed options before route state
  changes; Redirect wrong source, invalid target and malformed trailing options.
  The valid RA route setup and existing NDP/DAD tests also remain green.
- This closes these specific TODO-backed input checks only. It is not full RFC
  4861 conformance, all option semantics/address-length coverage, a complete
  security audit, remote exploitability finding or device-gate evidence.

## Local TCP and cold-start cyclic timer allocation recovery

This is a local port correction, not an upstream backport or security approval.
SYN allocation injection reproduced the `MEMP_SYS_TIMEOUT` exhaustion assertion.
TCP timer scheduling now returns allocation failure internally and marks the timer
active only after successful insertion. The owner boundary retries scheduling;
persistent failure becomes sticky `DP_RESOURCE_LIMIT`, using normal owner teardown.
The timer callback uses the same path when rescheduling.

Cold initialization previously called the asserting timeout API for every cyclic
timer. `sys_timeouts_init_try` now inserts missing cyclic timers idempotently,
rolls back all cyclic timer nodes added or already present if any insertion fails,
and returns the allocation error. `lwip_init_checked` exposes that result while
preserving the legacy `void lwip_init()` wrapper. The backend marks module setup as
initialized after this one-time pass; a failed create returns `DP_RESOURCE_LIMIT`,
and a later create retries only timer setup instead of rerunning module initialization.
Three isolated tests fail each configured non-TCP cyclic timer allocation in turn,
check timer/netif/heap/pool rollback, then create a working core on the next attempt.
Runtime cyclic timer rescheduling also uses a non-asserting allocation attempt. A
transient failure is repaired at the owner boundary; persistent exhaustion rolls
back the cyclic timer set and fails the core with `DP_RESOURCE_LIMIT`. One-shot
timeout allocation through the legacy void API no longer asserts: it records the
failure, which the core consumes at its owner boundary and reports as
`DP_RESOURCE_LIMIT`. The current default core has no other one-shot registration
call sites; a direct owner-command regression exercises the generic API and verifies
the failed context can be destroyed and recreated.

Exact SHA-256 post-images:

- `src/core/init.c`: `d8cfb5628f6a50d5ebaf73ffa189c8f7d1790ed9015f6a4f0074fe15ea541c2e`.
- `src/include/lwip/init.h`: `64e9bdeafebec615bc75f5bf6c6f800dc44553ae1957e46044f8dd51ba44389c`.
- `src/core/timeouts.c`: `c34e562946c3746b57755c54cc2ac8eda72920ad7f0dccffa5a5ddf863f804db`.
- `src/include/lwip/timeouts.h`: `fda44014b92e13e0f30059df21d98d7d4ccce67feccb61f4097cb0b77c5bb59f`.

Tests inject transient/persistent SYN failures and replay the actual scheduled TCP
timer handler under allocation failure. They check endpoint recovery or explicit
core failure, teardown, reconstruction and heap/all-pool baselines. Cold-start tests
also verify all three cyclic timer insertion positions and retry after rollback.
Two runtime cyclic timer reschedule tests inject transient and persistent
`MEMP_SYS_TIMEOUT` failures, verifying owner-boundary repair or clean core failure,
teardown and recreation. Handler replay isolates allocation behavior and does not
establish timer scheduling precision. A one-shot allocation test confirms the old
assertion is replaced by `DP_RESOURCE_LIMIT`, no timeout node is published, and a
subsequent context can schedule and cancel a timer. Full R01, JNI OOM, and device-gate
verification remain open.

## NDP queue allocation accounting (local correction, 2026-10-23)

- File: `src/core/ipv6/nd6.c`, cumulative with the reviewed NDP chain and its local
  cursor/PMTU/entry-validation corrections.
- Previous post-image: `77dcd0cf78909203632185ca43f5fc5c3b6262022e1ff05f2f45751b86621eaf`.
- Current post-image: `26b48a9dcec3bb662f610dcdd0ec8c115f317dfa9a8a0b1898b8ca4edb43b047`.
- Increment `nd6_queue_size` only after `memp_malloc(MEMP_ND6_QUEUE)` succeeds,
  including the retry after evicting an older entry. Allocation failure previously
  consumed logical queue capacity despite allocating no queue node; repeated failures
  could make later UDP sends to an unresolved neighbor fail as if the bounded queue
  were full.
- The regression injects all 16 configured queue-node allocation failures, checks
  heap/pbuf/all MEMP usage after each, then disables injection, learns the neighbor
  from a valid NA and verifies that the exact queued UDP datagram is emitted. The
  test failed against the prior post-image and passes with this correction.
- This repairs queue-size accounting for this allocation path. It does not establish
  all neighbor-state/queue combinations, TCP/reassembly queue behavior, full R01/G3,
  security approval or device-gate evidence.
