#!/usr/bin/env python3
"""The one-drive installer/log kit (kits/ar-combo/NEMESIS.sh) against a fake head unit."""
import json, os, shutil, subprocess, tempfile, unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ID = 'wireless.carplay.aa'


class ComboTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.usb = self.root/'usb'; self.car = self.root/'car'; self.bin = self.root/'bin'
        for d in (self.usb/'packages', self.bin): d.mkdir(parents=True)
        for name in ('NEMESIS.sh', 'NEMESIS.data'): shutil.copy(ROOT/'kits/ar-combo'/name, self.usb/name)
        shutil.copy(ROOT/'kits/ar-control/NEMESIS.sh', self.usb/'hotspot.sh')
        (self.usb/'AR_VERSION').write_text('1.3.0.116\n'); (self.usb/'packages'/(ID+'.zip')).write_bytes(b'package')
        self.pkg = self.car/'data/nemesis/apps'/ID; self.debug = self.car/'data/nemesis'/(ID+'-ar-debug')
        self.calls = self.root/'calls'; self.toasts = self.root/'toasts'
        def tool(name, body): (self.bin/name).write_text('#!/bin/sh\n'+body); (self.bin/name).chmod(0o755)
        tool('getprop', 'case "$1" in sys.boot_completed) echo "${BOOT-1}";; ro.build.product) echo "${PRODUCT-CVEV.USA.0000.V015.012.260507}";; esac\n')
        tool('nemesis', 'echo "$*" >> "%s"\ncase "$1" in\n pkg-list) [ -n "$INSTALLED" ] && echo "%s|Evergreen|0|application";;\n'
             ' install) case "$*" in *--force*) exit "${FORCE_RC-0}";; esac; exit "${INSTALL_RC-0}";;\n log) echo "nemesis log line";;\n service) [ "$2" = status ] && echo "%s/ar-debug|restart|running|0";;\nesac\nexit 0\n' % (self.calls, ID, ID))
        tool('am', 'echo "$*" >> "%s"\n' % self.toasts)
        tool('tail', 'echo "tail: not found" >&2; exit 127\n')  # the unit has none
        tool('logcat', 'echo "EvergreenAR: recent"\n')
        tool('md5', 'case "$1" in *AR-LOGS-*) [ -n "$BAD_COPY" ] && { echo 00000000000000000000000000000000 "$1"; exit 0; };; esac\nexec md5sum "$@"\n')
        self.env = dict(os.environ, EVERGREEN_AR_SOURCE_ROOT=str(self.car), PATH=str(self.bin)+os.pathsep+os.environ['PATH'])

    def installed(self, version, logs=True):
        (self.pkg/'svc').mkdir(parents=True, exist_ok=True); (self.pkg/'svc/ar-debug.sh').write_text('recorder')
        (self.pkg/'AR_INSTALL.json').write_text(json.dumps({'version': version}, indent=2))
        if logs:
            self.debug.mkdir(parents=True, exist_ok=True)
            for n in ('flow.log', 'flow.log.1', 'STATUS.txt'): (self.debug/n).write_text('trace '+n)

    def run_kit(self, **env):
        return subprocess.run([os.environ.get('KIT_SH', 'sh'), str(self.usb/'NEMESIS.sh')], cwd=self.usb, env=dict(self.env, **env), capture_output=True, text=True)

    def calls_text(self): return self.calls.read_text() if self.calls.exists() else ''
    def logs_dirs(self, prefix='AR-LOGS-2'): return sorted(p for p in self.usb.iterdir() if p.name.startswith(prefix))

    def test_descriptor_runs_the_script_and_names_the_package(self):
        text = (self.usb/'NEMESIS.data').read_text()
        self.assertIn('type=execute', text); self.assertIn('path=NEMESIS.sh', text)
        self.assertIn('packages/wireless.carplay.aa.zip', text)  # the package's option-file lookups grep for it

    def test_nothing_while_android_is_starting(self):
        self.installed('1.3.0.116')
        r = self.run_kit(BOOT='0'); self.assertEqual(r.returncode, 0)
        self.assertEqual(self.calls_text(), ''); self.assertFalse((self.usb/'EVERGREEN-STATUS.txt').exists())
        self.assertTrue((self.debug/'flow.log').exists())

    def test_first_plug_in_installs(self):
        r = self.run_kit(); self.assertEqual(r.returncode, 0, r.stdout)
        self.assertIn('install packages/%s.zip' % ID, self.calls_text()); self.assertNotIn('--update', self.calls_text())
        self.assertIn('collects the logs', (self.usb/'EVERGREEN-STATUS.txt').read_text())
        self.assertIn('installing 1.3.0.116', self.toasts.read_text())

    def test_older_build_updates_and_keeps_its_logs(self):
        self.installed('1.3.0.115')
        r = self.run_kit(INSTALLED='1'); self.assertEqual(r.returncode, 0, r.stdout)
        self.assertIn('install --update packages/%s.zip' % ID, self.calls_text())
        kept = self.logs_dirs('AR-LOGS-BEFORE-UPDATE-'); self.assertEqual(len(kept), 1)
        self.assertEqual((kept[0]/'flow.log.1').read_text(), 'trace flow.log.1')
        self.assertTrue((self.debug/'flow.log').exists())  # nothing deleted on an update

    def test_installed_version_collects_logs_and_keeps_recording(self):
        self.installed('1.3.0.116')
        r = self.run_kit(INSTALLED='1'); self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertNotIn('install', self.calls_text())
        out = self.logs_dirs(); self.assertEqual(len(out), 1); out = out[0]
        for n in ('flow.log', 'flow.log.1', 'STATUS.txt'): self.assertEqual((out/n).read_text(), 'trace '+n)
        self.assertEqual(len((out/'VERIFIED.txt').read_text().splitlines()), 3)
        self.assertFalse((self.debug/'flow.log').exists()); self.assertFalse((self.debug/'flow.log.1').exists())
        calls = self.calls_text().splitlines()
        self.assertLess(calls.index('service stop %s/ar-debug' % ID), calls.index('service start %s/ar-debug' % ID))
        self.assertTrue((self.usb/'egmp-aa-ev-logs').is_dir())
        self.assertTrue((out/'hotspot.txt').is_file()); self.assertFalse((self.usb/'AR_MODE').exists())
        self.assertIn('Recording continues', (self.usb/'EVERGREEN-STATUS.txt').read_text())
        self.assertIn((out/'AR_INSTALL.json').read_text(), (self.pkg/'AR_INSTALL.json').read_text())
        self.assertFalse((out/'install-logs').exists())

    def test_failed_copy_check_deletes_nothing_and_restarts_recording(self):
        self.installed('1.3.0.116')
        r = self.run_kit(INSTALLED='1', BAD_COPY='1'); self.assertEqual(r.returncode, 1)
        self.assertTrue((self.debug/'flow.log').exists()); self.assertTrue((self.debug/'flow.log.1').exists())
        self.assertIn('service start %s/ar-debug' % ID, self.calls_text())
        self.assertIn('Nothing on the head unit was deleted', (self.usb/'EVERGREEN-STATUS.txt').read_text())

    def test_evergreen_install_file_reinstalls_the_same_version(self):
        self.installed('1.3.0.116'); (self.usb/'EVERGREEN-INSTALL').write_text('')
        r = self.run_kit(INSTALLED='1'); self.assertEqual(r.returncode, 0)
        self.assertIn('install --update', self.calls_text()); self.assertFalse((self.usb/'EVERGREEN-INSTALL').exists())

    def test_nemesis_refusals_are_explained(self):
        r = self.run_kit(INSTALL_RC='20'); self.assertEqual(r.returncode, 1)
        self.assertIn('compatibility risk', (self.usb/'EVERGREEN-STATUS.txt').read_text())
        r = self.run_kit(INSTALL_RC='22'); self.assertIn('blocked', (self.usb/'EVERGREEN-STATUS.txt').read_text())
        (self.car/'data/nemesis').mkdir(parents=True, exist_ok=True); (self.car/'data/nemesis'/(ID+'.log')).write_text('install steps')
        r = self.run_kit(INSTALL_RC='1'); self.assertIn('code 1', (self.usb/'EVERGREEN-STATUS.txt').read_text())
        (self.car/'data/nemesis/wireless.projection.log').write_text('part steps')
        r = self.run_kit(INSTALL_RC='1')
        self.assertEqual((self.usb/'INSTALL-LOG'/(ID+'.log')).read_text(), 'install steps')
        self.assertEqual((self.usb/'INSTALL-LOG/wireless.projection.log').read_text(), 'part steps')
        self.assertIn('parts installed: none', (self.usb/'INSTALL-LOG/state.txt').read_text())
        self.assertIn('nemesis log line', (self.usb/'INSTALL-LOG/nemesis.log').read_text())
        self.assertIn('INSTALL-LOG folder', (self.usb/'EVERGREEN-STATUS.txt').read_text())

    def test_old_version_with_no_parts_is_replaced_past_its_failed_uninstall(self):
        self.installed('1.3.0.115')
        r = self.run_kit(INSTALLED='1', INSTALL_RC='5'); self.assertEqual(r.returncode, 0, r.stdout)
        self.assertIn('install --update --force packages/%s.zip' % ID, self.calls_text())
        self.assertTrue((self.usb/'INSTALL-LOG-FIRST-TRY').is_dir())
        r = self.run_kit(INSTALLED='1', INSTALL_RC='5', FORCE_RC='1'); self.assertEqual(r.returncode, 1)
        self.assertIn('code 1', (self.usb/'EVERGREEN-STATUS.txt').read_text())

    def test_installed_parts_are_never_forced(self):
        self.installed('1.3.0.115')
        (self.car/'data/nemesis'/(ID+'.parts')).write_text('1_projection 2_tbt\n')
        r = self.run_kit(INSTALLED='1', INSTALL_RC='5'); self.assertEqual(r.returncode, 1)
        self.assertNotIn('--force', self.calls_text()); self.assertIn('code 5', (self.usb/'EVERGREEN-STATUS.txt').read_text())

    def test_other_cars_and_incomplete_drives_are_refused(self):
        r = self.run_kit(PRODUCT='NEEV.USA.0000.V015.012.260507'); self.assertEqual(r.returncode, 1)
        self.assertNotIn('install', self.calls_text())
        (self.usb/'packages'/(ID+'.zip')).unlink()
        r = self.run_kit(); self.assertEqual(r.returncode, 1); self.assertIn('incomplete', (self.usb/'EVERGREEN-STATUS.txt').read_text())


if __name__ == '__main__':
    unittest.main()
