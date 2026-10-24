from pathlib import Path
import os
import subprocess
import tempfile
import unittest


REPOSITORY = Path(__file__).resolve().parents[2]
ENTRY_SCRIPT = REPOSITORY / 'scripts' / 'test_lwip_host.sh'


class HostTestEntryCliTest(unittest.TestCase):
    @unittest.skipUnless(os.name == 'posix', 'host entry test requires a POSIX shell')
    def test_documented_sanitizer_list_enables_address_and_undefined_checks(self):
        with tempfile.TemporaryDirectory(prefix='lwip host cli ') as folder:
            root = Path(folder)
            fake_cmake = root / 'cmake'
            fake_ctest = root / 'ctest'
            cmake_args = root / 'cmake-args.txt'
            fake_cmake.write_text(
                '#!/usr/bin/env bash\n'
                'printf "%s\\n" "$@" >> "$FAKE_CMAKE_ARGS"\n'
                'if [[ "$1" == --version ]]; then echo "cmake version test-double"; exit 0; fi\n'
                'if [[ "$1" == -S ]]; then\n'
                '  while (($#)); do\n'
                '    if [[ "$1" == -B ]]; then mkdir -p "$2"; break; fi\n'
                '    shift\n'
                '  done\n'
                'fi\n',
                encoding='utf-8',
            )
            fake_ctest.write_text(
                '#!/usr/bin/env bash\n'
                'if [[ "$1" == -N ]]; then echo "Total Tests: 1"; exit 0; fi\n'
                'if [[ -n "${ASAN_OPTIONS:-}" && -n "${UBSAN_OPTIONS:-}" ]]; then\n'
                '  echo "sanitizer environment configured"\n'
                'else\n'
                '  exit 1\n'
                'fi\n'
                'while (($#)); do\n'
                '  if [[ "$1" == --output-log ]]; then echo "100% tests passed" > "$2"; break; fi\n'
                '  shift\n'
                'done\n',
                encoding='utf-8',
            )
            fake_cmake.chmod(0o755)
            fake_ctest.chmod(0o755)
            environment = os.environ.copy()
            environment.update({
                'CMAKE': str(fake_cmake),
                'CTEST': str(fake_ctest),
                'FAKE_CMAKE_ARGS': str(cmake_args),
            })

            result = subprocess.run(
                ['bash', str(ENTRY_SCRIPT), '--build-dir', str(root / 'build'),
                 '--sanitizers', 'address,undefined'],
                cwd=REPOSITORY,
                env=environment,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                encoding='utf-8',
                errors='replace',
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stderr + result.stdout)
            self.assertIn('-DDIPLAY_SANITIZERS=ON', cmake_args.read_text(encoding='utf-8').splitlines())
            self.assertIn('sanitizer environment configured', result.stdout)


if __name__ == '__main__':
    unittest.main()
