#!/usr/bin/env python3
"""Run the read-only Leafia kit against a fake head unit and check what each level collects.

The kit's absolute paths (/system, /data, /etc, /vendor) are pointed at a temporary tree and
Android's commands are stubbed. Checks the porting level (Ultimate Wireless Projection's porting
kit): public report and stock files only, written as UWP-PORT/, never logs or key files.
Set KIT_SH to run it in another shell (the head unit's is mksh).
"""
import os, re, shutil, subprocess, tempfile, unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KIT = ROOT / "kits/leafia/NEMESIS.sh"
SH = os.environ.get("KIT_SH", "sh")

STUBS = {
    "getprop": 'case "$1" in ro.build.product) echo CVEV.USA.0000.V015.012.260507;; ro.product.device) echo wp_daudioplus_cvev_us;;'
               ' ro.mobis.targetdir) echo "$FAKE/system/onebin/cvev_us";; sys.boot_completed) echo 1;; "") echo "[ro.x]: [y]";; *) echo;; esac\n',
    "md5": 'for f in "$@"; do set -- $(md5sum "$f") ; echo "$1  $f"; done\n',
    "pm": 'echo "feature:android.hardware.wifi"; echo "package:com.hkmc.gpm"\n',
    "settings": "echo 0\n",
    "dumpsys": "echo 'mNetworkInfo state: DISCONNECTED, name: home'\n",
    "logcat": "echo '11-23 10:00:00.000 I/Phone(1): name=Kai iPhone'\n",
    "dmesg": "echo '<6>[1.0] secret phone'\n",
    "nemesis": "echo log line\n",
}
for name in ("ip", "ndc", "iptables", "wl", "netcfg", "df", "am"):
    STUBS[name] = "exit 0\n"


class LeafiaKitTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.fake = self.tmp / "car"; self.usb = self.tmp / "usb"; self.bin = self.tmp / "bin"
        for d in ("system/app", "system/lib", "system/framework", "system/etc/permissions", "system/etc/wifi",
                  "system/onebin/cvev_us", "data/nemesis", "data/tombstones", "data/anr", "data/system/dropbox", "vendor/lib"):
            (self.fake / d).mkdir(parents=True)
        for n in ("HKMC_DiPO", "HKMC_GPM", "HKMC_Navi_ExSL", "Iap2ServerApp"):
            for e in ("apk", "odex"): (self.fake / f"system/app/{n}.{e}").write_bytes(b"stock " + n.encode())
        for n in ("libCarPlayCore.so", "libautoreceiver_infobank.so"): (self.fake / "system/lib" / n).write_bytes(b"lib")
        (self.fake / "system/framework/framework2.odex").write_bytes(b"fw")
        (self.fake / "system/etc/permissions/com.hkmc.hardware.ev.xml").write_text("<permissions/>")
        (self.fake / "system/onebin/cvev_us/featurexml.cfg").write_text("-feature\n")
        (self.fake / "system/build.prop").write_text("ro.build.product=CVEV\n")
        (self.fake / "system/etc/image.dat").write_bytes(b"KEY")
        (self.fake / "data/nemesis/nemesis.log").write_text("nemesis log\n")
        (self.fake / "data/tombstones/tombstone_00").write_text("TOMBSTONE-CONTENT\n")
        self.bin.mkdir(); self.usb.mkdir()
        for name, body in STUBS.items():
            (self.bin / name).write_text("#!/bin/sh\n" + body); (self.bin / name).chmod(0o755)
        text = KIT.read_text()
        text = re.sub(r"^export PATH=.*$", "export PATH=%s:$PATH" % self.bin, text, flags=re.M)
        text = re.sub(r"^export LD_LIBRARY_PATH=.*$", "", text, flags=re.M)
        for prefix in ("/system/", "/data/", "/etc/", "/vendor/"):
            text = re.sub(r'(?<![\w$./-])' + re.escape(prefix), str(self.fake) + prefix, text)
        (self.usb / "NEMESIS.sh").write_text(text)

    def tearDown(self): shutil.rmtree(self.tmp)

    def run_kit(self, level):
        (self.usb / "LEAFIA-LEVEL").write_text(level + "\n")
        env = dict(os.environ, FAKE=str(self.fake))
        return subprocess.run([SH, "NEMESIS.sh"], cwd=self.usb, env=env, capture_output=True, text=True, timeout=120)

    def files(self, d):
        return sorted(str(p.relative_to(d)) for p in d.rglob("*") if p.is_file())

    def test_porting_collects_report_and_stock_only(self):
        r = self.run_kit("porting")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        out = self.usb / "UWP-PORT"
        self.assertIn("UWP-PORT/NEXT-STEPS.txt", r.stdout)
        self.assertFalse((self.usb / "LEAFIA").exists())
        report = (out / "attach-to-issue/LEAFIA-REPORT.txt").read_text()
        self.assertIn("prop ro.build.product=CVEV.USA.0000.V015.012.260507", report)
        self.assertTrue((out / "attach-to-issue/summary.txt").is_file())
        private = out / "send-privately"
        for want in ("stock/app/HKMC_GPM.odex", "stock/app/HKMC_DiPO.apk", "stock/lib/libautoreceiver_infobank.so",
                     "stock/framework/framework2.odex", "stock/etc/permissions/com.hkmc.hardware.ev.xml",
                     "stock/onebin/featurexml.cfg", "stock/build.prop"):
            self.assertTrue((private / want).is_file(), want)
        self.assertFalse((private / "logs").exists(), "no logs in a porting kit")
        self.assertFalse((private / "keys").exists(), "no key files in a porting kit")
        everything = "".join(p.read_bytes().decode("latin-1") for p in out.rglob("*") if p.is_file())
        for secret in ("KEY", "Kai iPhone", "secret phone", "nemesis log", "TOMBSTONE-CONTENT"):
            self.assertNotIn(secret, everything)
        steps = (out / "NEXT-STEPS.txt").read_text()
        self.assertIn("issues/new?template=port-request.yml", steps)
        self.assertIn("Don't post them publicly", steps)

    @unittest.skipUnless("mksh" in SH, "the standard level uses mksh syntax (set -A); run with KIT_SH=mksh")
    def test_standard_level_unchanged(self):
        r = self.run_kit("standard")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        out = self.usb / "LEAFIA"
        self.assertTrue((out / "public/LEAFIA-REPORT.txt").is_file())
        self.assertTrue((out / "private/keys/image.dat").is_file())
        self.assertTrue((out / "private/logs").is_dir())
        self.assertFalse((out / "private/stock").exists())
        self.assertFalse((self.usb / "UWP-PORT").exists())


if __name__ == "__main__":
    unittest.main()
