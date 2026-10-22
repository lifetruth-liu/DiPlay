import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'generate_lwip_fuzz_seeds.py'
SPEC = importlib.util.spec_from_file_location('generate_lwip_fuzz_seeds', SCRIPT)
SEEDS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SEEDS)


class FuzzSeedTest(unittest.TestCase):
    def test_each_target_has_deterministic_nonempty_seeds(self):
        for target, expected in SEEDS.seed_sets().items():
            with self.subTest(target=target), tempfile.TemporaryDirectory() as first, \
                    tempfile.TemporaryDirectory() as second:
                one = SEEDS.write_seeds(Path(first), target)
                two = SEEDS.write_seeds(Path(second), target)
                self.assertEqual(set(expected), {path.name for path in one})
                self.assertEqual({path.name: path.read_bytes() for path in one},
                                 {path.name: path.read_bytes() for path in two})
                self.assertTrue(all(path.stat().st_size for path in one))

    def test_ethernet_seeds_cover_valid_udp_tcp_and_icmpv6(self):
        seeds = SEEDS.seed_sets()['ethernet']
        udp = seeds['00-udp-v6'][1:]
        tcp = seeds['01-tcp-syn-v6'][1:]
        echo = seeds['02-icmpv6-echo'][1:]
        self.assertEqual(b'\x86\xdd', udp[12:14])
        self.assertEqual((17, 47000), (udp[20], int.from_bytes(udp[56:58], 'big')))
        self.assertEqual((6, 7000, 2), (tcp[20], int.from_bytes(tcp[56:58], 'big'), tcp[67]))
        self.assertEqual((58, 128), (echo[20], echo[54]))
        self.assertTrue(all(SEEDS.has_valid_transport_checksum(frame) for frame in (udp, tcp, echo)))

    def test_command_seeds_include_valid_framed_input(self):
        seed = SEEDS.seed_sets()['commands']['01-framed-udp']
        self.assertEqual(16, seed[0])
        self.assertEqual(len(seed) - 4, seed[3])
        self.assertEqual(b'\x07\x02', seed[4:6])

    def test_command_payload_limit_matches_fuzzer_encoding(self):
        with self.assertRaises(ValueError):
            SEEDS.command(9, payload=b'x' * 256)


if __name__ == '__main__':
    unittest.main()
