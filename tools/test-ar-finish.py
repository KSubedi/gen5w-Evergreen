#!/usr/bin/env python3
import json,os,shutil,subprocess,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
class FinishTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);self.usb=self.root/'usb';self.usb.mkdir()
        self.pkg=self.root/'car/data/nemesis/apps/wireless.carplay.aa';(self.pkg/'svc').mkdir(parents=True)
        self.logs=self.root/'car/data/nemesis/wireless.carplay.aa-ar-debug';self.logs.mkdir()
        for n in ('flow.log','flow.log.1','STATUS.txt'):(self.logs/n).write_text('trace '+n)
        (self.pkg/'AR_INSTALL.json').write_text(json.dumps({'version':'1.2.0.104'}))
        (self.pkg/'ar-hud.enabled').touch()
        shutil.copy2(ROOT/'kits/ar-installer/ar-debug.sh',self.pkg/'svc/ar-debug.sh')
        import zipfile
        with zipfile.ZipFile(ROOT/'dist/Evergreen-AR-export-stop-clean.zip') as z:z.extractall(self.usb)
        bin=self.root/'bin';bin.mkdir()
        scripts={
            'md5':'md5sum "$@"',
            'getprop':'echo CVEV.USA.0000.V015.012.260507',
            'nemesis':'[ "$AR_FAIL_STOP" = 1 ] && exit 1\necho "wireless.carplay.aa/ar-debug|restart|stopped|0"',
            'logcat':'echo "EvergreenAR own-tag snapshot"',
            'sync':'[ "$AR_FAIL_SYNC" = 1 ] && exit 1\nexit 0',
            'cp':'/bin/cp "$@" || exit 1\ncase "$1" in */flow.log) [ "$AR_CORRUPT" = 1 ] && echo corrupt >> "$2" ;; esac\nexit 0',
        }
        for n,s in scripts.items():
            p=bin/n;p.write_text('#!/bin/sh\n'+s+'\n');p.chmod(0o755)
        self.env=dict(os.environ,EVERGREEN_AR_SOURCE_ROOT=str(self.root/'car'),PATH=str(bin)+':'+os.environ['PATH'])
    def runkit(self):
        return subprocess.run([os.environ.get('AR_FINISH_SHELL','sh'),'NEMESIS.sh'],cwd=self.usb,env=self.env,capture_output=True,timeout=10)
    def test_verified_export_cleans_only_own_logs_and_is_repeatable(self):
        other=self.root/'car/data/nemesis/unrelated.txt';other.write_text('leave alone')
        r=self.runkit();self.assertEqual(r.returncode,0,r.stdout+r.stderr)
        out=self.usb/'AR-DEBUG-FINISHED'
        self.assertEqual((out/'flow.log').read_text(),'trace flow.log')
        self.assertTrue((out/'FINISHED.txt').is_file());self.assertFalse(self.logs.exists())
        self.assertEqual(other.read_text(),'leave alone');self.assertTrue((self.pkg/'ar-hud.enabled').exists())
        self.assertIn('sleep 3600',(self.pkg/'svc/ar-debug.sh').read_text())
        self.assertEqual(self.runkit().returncode,0)
    def test_105_export_and_cleanup(self):
        (self.pkg/'AR_INSTALL.json').write_text(json.dumps({'version':'1.2.0.105'}))
        result=self.runkit();self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        self.assertTrue((self.usb/'AR-DEBUG-FINISHED/FINISHED.txt').exists())
        self.assertFalse(self.logs.exists())
    def test_corrupt_usb_copy_preserves_all_original_logs(self):
        self.env['AR_CORRUPT']='1';r=self.runkit()
        self.assertNotEqual(r.returncode,0)
        for n in ('flow.log','flow.log.1','STATUS.txt'):self.assertEqual((self.logs/n).read_text(),'trace '+n)
        self.assertFalse((self.usb/'AR-DEBUG-FINISHED/FINISHED.txt').exists())
    def test_unknown_recorder_is_not_replaced_or_deleted(self):
        p=self.pkg/'svc/ar-debug.sh';p.write_text('unknown future service')
        self.assertNotEqual(self.runkit().returncode,0)
        self.assertEqual(p.read_text(),'unknown future service');self.assertTrue((self.logs/'flow.log').exists())
    def test_unconfirmed_stop_preserves_internal_logs(self):
        self.env['AR_FAIL_STOP']='1';self.assertNotEqual(self.runkit().returncode,0)
        self.assertTrue((self.logs/'flow.log').exists())
    def test_failed_flush_keeps_internal_logs(self):
        self.env['AR_FAIL_SYNC']='1';self.assertNotEqual(self.runkit().returncode,0)
        self.assertTrue((self.logs/'flow.log').exists())
    def test_negative_process_group_syntax_targets_only_fixture(self):
        # Confirm mksh/dash syntax using our own isolated, reaped process group.
        child=subprocess.Popen(['sleep','30'],start_new_session=True)
        try:
            r=subprocess.run([os.environ.get('AR_FINISH_SHELL','sh'),'-c','kill -TERM "-$1"','fixture',str(child.pid)],capture_output=True)
            self.assertEqual(r.returncode,0,r.stderr)
            self.assertEqual(child.wait(timeout=3),-15)
        finally:
            if child.poll() is None:child.kill();child.wait()
if __name__=='__main__':unittest.main()
