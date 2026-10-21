import struct
import sys
from pathlib import Path
import unittest
import shutil
import tempfile
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_lwip_native import ABIS, inspect_elf, verify_source
import check_lwip_native


def elf(abi, alignment=16384, stack=True, stack_flags=6):
    kind, machine = ABIS[abi]
    data = bytearray(512)
    data[:7] = b"\x7fELF" + bytes((kind, 1, 1))
    struct.pack_into("<HH", data, 16, 3, machine)
    if kind == 1:
        struct.pack_into("<I", data, 28, 64)
        struct.pack_into("<HH", data, 42, 32, 2 if stack else 1)
        struct.pack_into("<8I", data, 64, 1, 0, 0, 0, 256, 1024, 6, alignment)
        if stack:
            struct.pack_into("<8I", data, 96, 0x6474E551, 0, 0, 0, 0, 0, stack_flags, 16)
    else:
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HH", data, 54, 56, 2 if stack else 1)
        struct.pack_into("<II6Q", data, 64, 1, 6, 0, 0, 0, 256, 1024, alignment)
        if stack:
            struct.pack_into("<II6Q", data, 120, 0x6474E551, stack_flags, 0, 0, 0, 0, 0, 16)
    return data


class NativeVerifierTest(unittest.TestCase):
    def test_pinned_source_inventory(self):
        self.assertEqual(314, verify_source()["files"])

    def test_exact_backport_is_reported(self):
        self.assertEqual(
            ["3d896ba0a37ff3ce73270ca5e230707fe47f60e3"],
            verify_source()["backports"],
        )

    def test_rejects_tampered_backport_and_unpatched_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "backend"
            shutil.copytree(check_lwip_native.ROOT / "third_party", root / "third_party")
            shutil.copy(check_lwip_native.ROOT / "UPSTREAM-SHA256.txt", root)
            with patch.object(check_lwip_native, "ROOT", root):
                for name in ("src/core/tcp_in.c", "src/core/udp.c"):
                    path = root / "third_party/lwip" / name
                    original = path.read_bytes()
                    path.write_bytes(original + b"\n/* unexpected modification */\n")
                    with self.assertRaisesRegex(ValueError, "modified upstream source"):
                        verify_source()
                    path.write_bytes(original)

    def test_each_supported_elf_class(self):
        for abi in ABIS:
            self.assertEqual([16384], inspect_elf(elf(abi), abi)["load_alignment"])

    def test_rejects_four_kib_and_invalid_load_alignment(self):
        for alignment in (4096, 24576):
            with self.subTest(alignment=alignment), self.assertRaises(ValueError):
                inspect_elf(elf("arm64-v8a", alignment=alignment), "arm64-v8a")

    def test_rejects_unknown_or_executable_stack(self):
        for data in (elf("x86", stack=False), elf("x86", stack_flags=7)):
            with self.assertRaises(ValueError):
                inspect_elf(data, "x86")

    def test_rejects_wrong_machine_and_truncated_headers(self):
        data = elf("arm64-v8a")
        struct.pack_into("<H", data, 18, 62)
        with self.assertRaises(ValueError):
            inspect_elf(data, "arm64-v8a")
        with self.assertRaises(ValueError):
            inspect_elf(data[:100], "arm64-v8a")


if __name__ == "__main__":
    unittest.main()
