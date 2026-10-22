# Linux host lwIP microbenchmark

Run the release-like TCP/UDP benchmark on Linux as root, with permission to create
an isolated network namespace and configure its interfaces:

```sh
bash scripts/benchmark_lwip.sh --output build/lwip-benchmark
```

The default measurement lasts at least 600 seconds: half for one TCP echo stream
using 16 KiB payloads, then the remainder split across UDP echo payloads of 64,
1200, 4096 and 8192 bytes. `--duration SECONDS` changes the requested duration;
runs shorter than 600 seconds are useful for smoke testing but leave
`duration_gate_passed` false. `--network tap` selects TAP; veth is the default.

The client checks every echoed byte and records per-exchange RTT p50/p95/p99/max,
one-way payload throughput, round-trip payload throughput, sample count, and UDP
sent/received/dropped counts for each size. Any UDP loss or integrity failure makes
the command fail while preserving the peer report and kernel log. The aggregate
`report.json` includes source and executable hashes, Git identity when available,
compiler/CMake/kernel versions, build commands/logs, core RX/TX counters and lwIP
heap peak, process CPU/RSS for the benchmark client, and the detailed peer report.

This is an isolated Linux host microbenchmark. It does not measure Android/NCM,
iPhone media traffic, USB bandwidth, device CPU/PSS, complete-session latency, or
headroom against a measured media peak. Those values remain null or explicitly
unevaluated; a successful host run is not device acceptance. The performance
budgets and full A/B/A device method remain in [performance.md](performance.md).
GitHub Actions exposes this run as an opt-in `workflow_dispatch` job and uploads the
aggregate/peer JSON, core log, tool versions and build logs; pull-request CI does not
spend ten minutes on the host benchmark.
