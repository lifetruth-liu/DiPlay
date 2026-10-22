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

This record covers two fixes. NDP and PMTU audit findings remain
open; it does not establish full upstream security approval or device gates.
