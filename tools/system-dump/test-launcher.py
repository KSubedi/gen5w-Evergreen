#!/usr/bin/env python3
"""Exercise launcher diagnostics in a temporary fake USB/staging environment."""
import os
from pathlib import Path
import subprocess
import shlex
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class LauncherTest(unittest.TestCase):
    def test_startup_errors_and_append(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            usb = root / 'usb'
            usb.mkdir()
            bin_dir = root / 'bin'
            bin_dir.mkdir()
            for name, text in {
                'md5': '#!/bin/sh\nmd5sum "$@"\n',
                'getprop': '#!/bin/sh\necho CVEV.USA.260507\n',
                # Snapshot the log at every flush so errors cannot pass tests
                # merely because the host's page cache makes them readable.
                'sync': '#!/bin/sh\ncat SYSTEM-DUMP-LAUNCH.txt > flushed-log\necho flush >> flush-count\n',
            }.items():
                path = bin_dir / name
                path.write_text(text)
                path.chmod(0o700)
            source = (ROOT / 'kits/system-dump/NEMESIS.sh').read_text()
            source = source.replace('export PATH=', '# test PATH=')
            source = source.replace('/storage/usb*|/mnt/media_rw/usb*', str(usb))
            source = source.replace('STAGE=/data/nemesis/system-dump', 'STAGE=' + str(root / 'stage'))
            script = root / 'launch.sh'
            script.write_text(source)
            env = dict(os.environ, PATH=str(bin_dir) + ':' + os.environ['PATH'])
            def run():
                return subprocess.run(shlex.split(os.environ.get('DUMP_TEST_SHELL', 'sh')) + [str(script)], cwd=usb, env=env, capture_output=True)
            self.assertEqual(run().returncode, 1)
            log = usb / 'SYSTEM-DUMP-LAUNCH.txt'
            self.assertIn('Incomplete collector kit', log.read_text())
            self.assertIn('launch v4', log.read_text())
            self.assertIn('Launcher exit=1 step=validate-kit', log.read_text())
            self.assertEqual(log.read_bytes(), (usb / 'flushed-log').read_bytes())
            self.assertEqual((usb / 'flush-count').read_text().splitlines(), ['flush', 'flush'])
            helper = usb / 'collector-arm'
            helper.write_text('#!/bin/sh\necho "synthetic native mount refusal" >&2\nexit 7\n')
            import hashlib
            (usb / 'collector.md5').write_text(hashlib.md5(helper.read_bytes()).hexdigest() + '\n')
            self.assertEqual(run().returncode, 7)
            text = log.read_text()
            self.assertIn('Incomplete collector kit', text)
            self.assertIn('synthetic native mount refusal', text)
            self.assertIn('Launcher exit=7 step=start-native', text)
            self.assertEqual(log.read_bytes(), (usb / 'flushed-log').read_bytes())
            self.assertEqual(len((usb / 'flush-count').read_text().splitlines()), 4)
            log.unlink()
            target = root / 'untouched'
            target.write_text('original')
            log.symlink_to(target)
            self.assertEqual(run().returncode, 1)
            self.assertEqual(target.read_text(), 'original')


if __name__ == '__main__':
    unittest.main()
