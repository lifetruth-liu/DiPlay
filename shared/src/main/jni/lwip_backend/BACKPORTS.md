# Reviewed local backports

Base: lwIP 2.2.1, commit `77dcd25a72509eb83f72b033d219b1d40cd8eb95`.
`UPSTREAM-SHA256.txt` remains the original release inventory. The verifier pins
its digest and requires the original digest below to match that inventory before
accepting exactly the reviewed post-image. No other vendor modification is allowed.

## TCP option byte bounds

- Official upstream commit: `3d896ba0a37ff3ce73270ca5e230707fe47f60e3`.
- File: `src/core/tcp_in.c`.
- Original SHA-256: `b4d1f0bf55dd782aa75f94069284bf99019aa8c42ee040c4e9e966ae8172b443`.
- Post-image SHA-256: `bb0062732bf843e8b19b55aa9ee8e17c35f36dd6bf35a1321307ce53a0fff717`.
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
- Post-image SHA-256: `6d3783d2fcbe1187eac34a6b732436447288b9740b6ec9a8d69002acb08680f3`.
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
- Scope: this first directed frame test covers NS. Directed NA, Redirect, RA and
  non-Ethernet address-length/copy cases are still pending; generic sanitizer,
  frame and kernel tests alone do not establish complete NDP security coverage.
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
- Same file: `src/core/ipv6/nd6.c`; final SHA-256 including all NDP changes above:
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

NDP directed coverage and broader protocol audit findings remain
open; it does not establish full upstream security approval or device gates.
