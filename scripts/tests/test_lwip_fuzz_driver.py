import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest


DRIVER = Path(__file__).resolve().parents[1] / 'lwip_fuzz_driver.py'


class FuzzDriverTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location('lwip_fuzz_driver', DRIVER)
        cls.driver = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.driver)

    def parse(self, *args):
        return self.driver.parse_args(['--target', 'ethernet', '--output', 'build/fuzz', *args])

    def test_requires_explicit_budget_or_smoke_or_replay(self):
        for args in [[], ['--runs', '0'], ['--cpu-hours', 'nan'], ['--cpu-hours', '-1'], ['--cpu-hours', '1e308'],
                     ['--seed', '0', '--runs', '1'], ['--runs', '1', '--cpu-hours', '8']]:
            with self.subTest(args=args), self.assertRaises(SystemExit):
                self.parse(*args)

    def test_cpu_budget_and_smoke_are_distinct(self):
        cpu = self.parse('--cpu-hours', '8')
        self.assertEqual(28800, cpu.cpu_hours * 3600)
        smoke = self.parse('--runs', '100', '--seed', '37')
        self.assertEqual(100, smoke.runs)
        self.assertIsNone(smoke.cpu_hours)
        self.assertEqual(37, smoke.seed)

    def test_replay_uses_only_named_file(self):
        args = self.parse('--replay', 'crash-input')
        command = self.driver.fuzzer_command(Path('/bin/fuzzer'), args, Path('/corpus'), Path('/crashes'), 0)
        self.assertIn('crash-input', command)
        self.assertNotIn('/corpus', command)
        self.assertNotIn('-max_total_time=10', command)

    def test_smoke_command_has_seed_and_bounded_input(self):
        args = self.parse('--runs', '100', '--seed', '37')
        command = self.driver.fuzzer_command(Path('/bin/fuzzer'), args, Path('/corpus'), Path('/crashes'), 0)
        self.assertIn('-runs=100', command)
        self.assertIn('-seed=37', command)
        self.assertIn('-max_len=8192', command)
        self.assertIn('-artifact_prefix=' + str(Path('/crashes')) + '/', command)

    def test_failed_or_short_campaign_never_passes_cpu_gate(self):
        self.assertFalse(self.driver.budget_complete(100, 99, 0))
        self.assertFalse(self.driver.budget_complete(100, 101, 1))
        self.assertFalse(self.driver.budget_complete(None, 101, 0))
        self.assertTrue(self.driver.budget_complete(100, 100, 0))

    @unittest.skipUnless(sys.platform == 'linux', 'wait4 CPU accounting requires Linux')
    def test_child_cpu_and_failure_are_measured(self):
        with tempfile.TemporaryDirectory() as folder:
            result = self.driver.run_child([sys.executable, '-c', 'sum(range(1000000)); raise SystemExit(3)'],
                                           Path(folder) / 'child.log', {})
        self.assertEqual(3, result['returncode'])
        self.assertGreater(result['user_seconds'] + result['system_seconds'], 0)

    @unittest.skipUnless(sys.platform == 'linux', 'wait4 CPU accounting requires Linux')
    def test_failed_smoke_writes_report_without_budget_credit(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            binary = root / 'fake-fuzzer'
            binary.write_text('#!/usr/bin/env python3\nsum(range(20000000))\nraise SystemExit(3)\n')
            binary.chmod(0o755)
            output = root / 'output'
            output.mkdir()
            (output / 'build-metadata.json').write_text('{"compiler_version":"Clang 18"}')
            code = self.driver.main(['--target', 'commands', '--output', str(output),
                                     '--binary', str(binary), '--runs', '1'])
            report = json.loads(next(output.glob('campaign-*/report.json')).read_text())
            self.assertEqual(1, code)
            self.assertEqual(3, report['returncode'])
            self.assertEqual('smoke', report['mode'])
            self.assertFalse(report['cpu_budget_complete'])
            self.assertGreater(report['measured_cpu_seconds'], 0)
            self.assertTrue(Path(report['batches'][0]['log']).is_file())
            self.assertEqual('Clang 18', report['build_metadata']['compiler_version'])


if __name__ == '__main__':
    unittest.main()
