# lwIP upstream pin

The pinned source is vendored and compiled into the prototype `libdiplay_lwip.so`.
Only the explicit P2 prototype/test factory loads it; production sessions do not
select this backend yet.

- Release: **2.2.1**.
- Official Git: `https://git.savannah.nongnu.org/git/lwip.git` (redirects to the GNU HTTPS Git host).
- Annotated tag: `STABLE-2_2_1_RELEASE`.
- Tag object: `009c2256469004009488b3385ba269461e8eb616`.
- Peeled commit: `77dcd25a72509eb83f72b033d219b1d40cd8eb95`.
- Archive: `https://download.savannah.nongnu.org/releases/lwip/lwip-2.2.1.zip`.
- Archive SHA-256: `7b622662dba2383d71f874f2e494b54ae948531559c17acbe75797966d646878`.
- Checked on: 2026-10-21 using `git ls-remote` and PowerShell `Get-FileHash -Algorithm SHA256`.
- License: the archive's `lwip-2.2.1/COPYING` contains the three-clause BSD terms;
  retain it unchanged when vendoring source and reproduce it in binary notices.
- Local patches: TCP option bounds fix `3d896ba0a37ff3ce73270ca5e230707fe47f60e3`
  and IPv6 empty reassembly cleanup `56b29f8bcfaefe2974dca67bde16cc7c391feaeb`,
  plus the four-commit NDP chain ending `7d1327e51407440350c5687d65dfa78de0f8f961`;
  the NDP post-image also includes a local remaining-length guard for cursor advance.
  see [backport record](BACKPORTS.md). On 2026-10-22 the ZIP detached signature was verified with
  fingerprint `746BEB8E56372EB695EA02E71D6B61C1B84299F5`, anchored to the maintainer's
  Savannah account key. GPG returned GOODSIG/VALIDSIG; owner trust remains undefined.
  The annotated Git tag itself is unsigned. Signature SHA-256:
  `e934e3c4d0fd4b6ac213db43d163707942184df6df8784c0f084ed5d95532a18`.
- Security review is **not passed**: NDP/PMTU paths still have relevant post-release fixes requiring regression
  tests; PMTU still needs a backport. TCP option bounds and IPv6 empty reassembly cleanup are
  backported with targeted regressions, not a complete security assessment.
  See [audit snapshot](../../../../../docs/lwIP/upstream-audit.md) for the exact
  upstream review head, configuration applicability and open work.

Build inputs are vendored under `third_party/lwip/`: the complete `src/` tree and
COPYING, README, FILES, CHANGELOG and UPGRADING (314 files, three exact post-images). Builds do
not download dependencies. `UPSTREAM-SHA256.txt` records every file; its SHA-256 is
`7882cdb8bc927ecd47a5b1e06b99f3190f245998e937fd0d043380c5e4a6f8c0`.
`python scripts/check_lwip_native.py` checks the original manifest, exact inventory
and the explicitly pinned post-image of each backported file.
Git attributes preserve upstream bytes. Port/configuration changes live outside
the upstream tree. `scripts/generate_lwip_notice.py` generates the packaged
`diplay-lwip-NOTICE.txt` asset from COPYING and upstream copyright notices.

For any future upgrade, independently verify the official release/tag and archive, preserve
licenses, record every patch, and rerun protocol/JNI/cancellation tests, sanitizer/fuzz,
four-ABI builds and target ARM performance. Do not mix Kotlin and native handle ABI versions.
