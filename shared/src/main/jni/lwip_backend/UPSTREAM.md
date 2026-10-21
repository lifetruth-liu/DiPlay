# lwIP upstream pin

This records the candidate only. No upstream source or native backend is compiled here yet.

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
- Local patches: none. Signature verification and security-fix audit: not yet performed.

The downloaded archive is currently in the execution host's temporary directory as
`diplay-lwip-2.2.1.zip`. Build inputs must eventually be vendored under `third_party/lwip/`;
builds must not depend on this temporary file or on downloading code at build time.

For any future upgrade, independently verify the official release/tag and archive, preserve
licenses, record every patch, and rerun protocol/JNI/cancellation tests, sanitizer/fuzz,
four-ABI builds and target ARM performance. Do not mix Kotlin and native handle ABI versions.
