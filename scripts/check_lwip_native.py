"""Verify the pinned vendor tree and (optionally) four-ABI APK ELF packaging. No downloads."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1] / "shared/src/main/jni/lwip_backend"
MANIFEST_HASH = "7882cdb8bc927ecd47a5b1e06b99f3190f245998e937fd0d043380c5e4a6f8c0"
# Original release manifest stays immutable. Each reviewed backport permits one
# exact post-image, never an arbitrary change to a listed source file.
BACKPORTS = {
    # This post-image includes the four-commit dependency chain in BACKPORTS.md.
    "src/core/ipv6/nd6.c": (
        "c415888732a6b68e9276a6f50276bf1de524b0aa2403121b18c15b1bec3fe0b4",
        "73aef9287f9afbc1a40ed08094b0c7b925733c1fc16a623f47bbb924d07f56fe",
        "7d1327e51407440350c5687d65dfa78de0f8f961",
    ),
    "src/core/ipv6/ip6_frag.c": (
        "c770139ab8d31da076fbf9b74a260511b647c377241bf1344547d02f6e0cc99d",
        "6d3783d2fcbe1187eac34a6b732436447288b9740b6ec9a8d69002acb08680f3",
        "56b29f8bcfaefe2974dca67bde16cc7c391feaeb",
    ),
    "src/core/tcp_in.c": (
        "b4d1f0bf55dd782aa75f94069284bf99019aa8c42ee040c4e9e966ae8172b443",
        "bb0062732bf843e8b19b55aa9ee8e17c35f36dd6bf35a1321307ce53a0fff717",
        "3d896ba0a37ff3ce73270ca5e230707fe47f60e3",
    ),
}
ABIS = {"armeabi-v7a": (1, 40), "arm64-v8a": (2, 183), "x86": (1, 3), "x86_64": (2, 62)}


def verify_source():
    manifest = (ROOT / "UPSTREAM-SHA256.txt").read_bytes()
    if hashlib.sha256(manifest).hexdigest() != MANIFEST_HASH:
        raise ValueError("upstream manifest changed: review the upstream pin and verifier together")
    expected = {}
    vendor = ROOT / "third_party/lwip"
    for line in manifest.decode("ascii").splitlines():
        digest, name = line.split("  ", 1)
        path = Path(name)
        if path.is_absolute() or ".." in path.parts or name in expected:
            raise ValueError("invalid vendor manifest entry")
        expected[name] = digest
    for name, (original, patched, commit) in BACKPORTS.items():
        if expected.get(name) != original:
            raise ValueError("backport does not match the original release: " + name)
        expected[name] = patched
    actual = {p.relative_to(vendor).as_posix(): p for p in vendor.rglob("*") if p.is_file()}
    if set(actual) != set(expected):
        raise ValueError("vendor file inventory differs from the pinned archive")
    for name, path in actual.items():
        if path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest() != expected[name]:
            raise ValueError("modified upstream source: " + name)
    return {"lwip": "2.2.1", "commit": "77dcd25a72509eb83f72b033d219b1d40cd8eb95", "files": len(actual),
            "backports": sorted({entry[2] for entry in BACKPORTS.values()})}


def inspect_elf(data, abi):
    elf_class, machine = ABIS[abi]
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] != elf_class or data[5:7] != b"\x01\x01":
        raise ValueError("unexpected ELF identity for " + abi)
    if struct.unpack_from("<H", data, 16)[0] != 3:
        raise ValueError("expected a shared ELF object for " + abi)
    if struct.unpack_from("<H", data, 18)[0] != machine:
        raise ValueError("unexpected ELF machine for " + abi)
    if elf_class == 1:
        phoff = struct.unpack_from("<I", data, 28)[0]
        size, count = struct.unpack_from("<HH", data, 42)
        layout = "<8I"
    else:
        phoff = struct.unpack_from("<Q", data, 32)[0]
        size, count = struct.unpack_from("<HH", data, 54)
        layout = "<II6Q"
    loads, writable, stack_known = [], 0, False
    if size != struct.calcsize(layout) or phoff + count * size > len(data):
        raise ValueError("invalid ELF program-header table")
    for index in range(count):
        header = struct.unpack_from(layout, data, phoff + index * size)
        if elf_class == 1:
            kind, offset, address, _, filesz, memsz, flags, alignment = header
        else:
            kind, flags, offset, address, _, filesz, memsz, alignment = header
        if kind == 0x6474E551:
            stack_known = True
            if flags & 1:
                raise ValueError("executable stack in " + abi)
        if kind != 1:
            continue
        if filesz > memsz or offset + filesz > len(data):
            raise ValueError("invalid ELF load range")
        if alignment < 16384 or alignment & (alignment - 1) or (offset - address) % 16384:
            raise ValueError("ELF is not compatible with 16 KiB pages: " + abi)
        if flags & 3 == 3:
            raise ValueError("writable executable segment in " + abi)
        loads.append(alignment)
        if flags & 2:
            writable += memsz
    if not stack_known:
        raise ValueError("ELF stack permissions are unspecified in " + abi)
    if not loads or writable + 65536 > 16 * 1024 * 1024:
        raise ValueError("static stack/data budget exceeds the backend ceiling")
    return {"elf_class": elf_class, "load_alignment": loads, "data_bss_bytes": writable}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    report = {"source": verify_source()}
    if args.apk:
        with zipfile.ZipFile(str(args.apk)) as apk:
            report["apk_sha256"] = hashlib.sha256(args.apk.read_bytes()).hexdigest()
            report["elf"] = {}
            for abi in ABIS:
                name = "lib/{}/libdiplay_lwip.so".format(abi)
                report["elf"][abi] = inspect_elf(apk.read(name), abi)
                entry = apk.getinfo(name)
                apk.fp.seek(entry.header_offset)
                local_header = apk.fp.read(30)
                name_size, extra_size = struct.unpack_from("<HH", local_header, 26)
                data_offset = entry.header_offset + 30 + name_size + extra_size
                if entry.compress_type == zipfile.ZIP_STORED and data_offset % 16384:
                    raise ValueError("uncompressed library is not ZIP-aligned to 16 KiB: " + abi)
                report["elf"][abi]["zip_compressed"] = entry.compress_type != zipfile.ZIP_STORED
                report["elf"][abi]["zip_data_offset"] = data_offset
            license_text = (ROOT / "third_party/lwip/COPYING").read_bytes().replace(b"\r\n", b"\n")
            notice = apk.read("assets/diplay-lwip-NOTICE.txt").replace(b"\r\n", b"\n")
            if license_text not in notice:
                raise ValueError("APK does not retain the lwIP license")
        report["limitations"] = "ELF/package checks do not prove Android loading or device interoperability"
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
