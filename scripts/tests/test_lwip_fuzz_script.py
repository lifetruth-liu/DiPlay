from pathlib import Path
import subprocess
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'fuzz_lwip.sh'


@unittest.skipUnless(__import__('sys').platform == 'linux', 'the campaign entry point is Bash/Linux')
class FuzzScriptTest(unittest.TestCase):
    def run_script(self, *args):
        return subprocess.run(['bash', str(SCRIPT), *args], text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_help_does_not_require_build_tools_or_arguments(self):
        result = self.run_script('--help')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('--cpu-hours HOURS', result.stdout)
        self.assertIn('--runs COUNT', result.stdout)

    def test_invalid_target_and_missing_mode_fail_before_build(self):
        bad_target = self.run_script('--target', 'other', '--output', 'build/fuzz', '--runs', '1')
        self.assertEqual(2, bad_target.returncode)
        self.assertIn('target', bad_target.stderr)
        missing_mode = self.run_script('--target', 'ethernet', '--output', 'build/fuzz')
        self.assertEqual(2, missing_mode.returncode)
        self.assertIn('choose one', missing_mode.stderr)


if __name__ == '__main__':
    unittest.main()
