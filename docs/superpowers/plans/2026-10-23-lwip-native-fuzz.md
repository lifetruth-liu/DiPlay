# lwIP native fuzz execution plan

Goal: implement test-plan.md's three host fuzz entry points with reproducible seeds,
saved corpus/crashes and measured CPU accounting. This does not pass device gates.

1. Add parser/report tests for the driver: help, target selection, CPU budget versus
   run-limited smoke, seed/replay, nonzero failure, CPU use from the actual child.
2. Add optional Clang/libFuzzer CMake instrumentation of the real stack and port.
   Keep normal Android and host builds unchanged. Fail clearly without toolchain.
3. Add Ethernet, endpoint-command and lifecycle target modes with bounded inputs,
   real core creation/teardown, generation validation and resource assertions.
   Include valid seed traffic so parsing does not stop at Ethernet/checksum rejection.
4. Add scripts/fuzz_lwip.sh and its Python driver, structured version/environment/
   CPU/wall-time metadata, corpus preservation and single-file replay.
5. Run short seeded sanitizer campaigns and replay; wire short smoke coverage to CI,
   upload logs/corpus/build metadata. Never label smoke as 24 CPU-hour acceptance.
6. Run mandatory Android checks, vendor/APK/public-tree checks, document evidence and
   remaining long campaign/device requirements, then commit the reviewable result.

The existing written development plan authorizes this implementation. Production
backend selection and business-channel migration remain gated as previously agreed.
