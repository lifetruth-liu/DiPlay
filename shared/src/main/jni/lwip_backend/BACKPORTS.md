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

This record covers one fix. NDP, IPv6 reassembly and PMTU audit findings remain
open; it does not establish full upstream security approval or device gates.
