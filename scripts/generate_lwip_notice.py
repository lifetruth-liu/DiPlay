"""Generate the APK's lwIP copyright/license notices from the pinned source headers."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
vendor = root / "shared/src/main/jni/lwip_backend/third_party/lwip"
groups = {}
for path in sorted(vendor.rglob("*")):
    if path.suffix not in (".c", ".h"):
        continue
    text = path.read_bytes().decode("utf-8").replace("\r\n", "\n")
    for block in re.findall(r"/\*.*?\*/", text, flags=re.S):
        if "copyright" in block.lower() or "permission to use" in block.lower():
            groups.setdefault(block, []).append(path.relative_to(vendor).as_posix())
notice = [
    "lwIP 2.2.1 / STABLE-2_2_1_RELEASE",
    "https://savannah.nongnu.org/projects/lwip/",
    "The prototype compiles IPv6 core and Ethernet modules. Additional notices from the",
    "bundled source distribution are retained below for completeness.",
    vendor.joinpath("COPYING").read_bytes().decode("utf-8").replace("\r\n", "\n"),
]
for block, paths in groups.items():
    notice.extend(("Files: " + ", ".join(paths), block))
target = root / "shared/src/main/assets/diplay-lwip-NOTICE.txt"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(("\n\n".join(notice) + "\n").encode("utf-8"))
print("Generated", target, "with", len(groups), "upstream notice blocks")
