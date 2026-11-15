#!/usr/bin/env python3
"""Synthetic original fixtures only: no manufacturer apps or stock files."""
import importlib.util
import io
import os
import json
import copy
import hashlib
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / "tools" / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


patcher = load("patch_ar", "patch-carplay-ar.py")
builder = load("build_ar", "build-carplay-ar.py")
verifier = load("verify_ar", "check-carplay-ar-build.py")
installer = load("install_ar", "build-carplay-ar-installer.py")
HELPERS = ROOT / "src/carplay-ar/build/smali"
LPATCH = ROOT / "tools/lpatch/lpatch-host"
FIXTURE = """.class public Lsynthetic/Phone;
.super Ljava/lang/Object;
.method public static turn(Lcom/hkmc/cluster/NavigationCluster;IIFFIIIII)V
    .registers 10
    invoke-virtual/range {p0 .. p9}, Lcom/hkmc/cluster/NavigationCluster;->updateTBTInfo(IIFFIIIII)V
    return-void
.end method
"""


class PatchTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.tree = Path(self.tmp.name) / "smali"
        self.tree.mkdir()
        self.source = self.tree / "Phone.smali"
        self.source.write_text(FIXTURE)

    def test_rewrite_and_assemble(self):
        self.assertEqual(patcher.patch(self.tree, HELPERS, 1), 1)
        self.assertIn("invoke-static/range {p0 .. p9}", self.source.read_text())
        dex = Path(self.tmp.name) / "result.dex"
        builder.run("smali", "a", "-j", "1", "--api", "18", "-o", dex, self.tree)
        self.assertTrue(dex.read_bytes().startswith(b"dex\n035"))
        with self.assertRaises(ValueError):
            patcher.patch(self.tree, HELPERS, 1)

    def test_count_failure_does_not_modify(self):
        with self.assertRaises(ValueError):
            patcher.patch(self.tree, HELPERS, 2)
        self.assertEqual(self.source.read_text(), FIXTURE)
        self.assertFalse((self.tree / "org").exists())

    def test_unknown_signature_is_rejected(self):
        self.source.write_text(FIXTURE.replace("(IIFFIIIII)V", "(III)V"))
        with self.assertRaises(ValueError):
            patcher.patch(self.tree, HELPERS, 1)

    def test_wrong_register_count_is_rejected(self):
        self.source.write_text(FIXTURE.replace("{p0 .. p9}", "{p0 .. p8}"))
        with self.assertRaises(ValueError):
            patcher.patch(self.tree, HELPERS, 1)

    def test_stubs_cannot_be_injected(self):
        with self.assertRaises(ValueError):
            patcher.patch(self.tree, ROOT / "src/carplay-ar/stubs", 1)

    def test_inspection_reports_method_and_registers_without_changing_tree(self):
        sites = patcher.inspect(self.tree)
        self.assertEqual(sites[0]["file"], "Phone.smali")
        self.assertEqual(sites[0]["method"], "turn(Lcom/hkmc/cluster/NavigationCluster;IIFFIIIII)V")
        self.assertEqual(sites[0]["registers"], "{p0 .. p9}")
        self.assertEqual(self.source.read_text(), FIXTURE)

    def test_cluster_call_outside_method_rejected(self):
        self.source.write_text(FIXTURE + '    invoke-virtual/range {p0 .. p9}, ' + patcher.NC + '->updateTBTInfo' + patcher.SIG + '\n')
        with self.assertRaises(ValueError):
            patcher.inspect(self.tree)

    def test_private_builder_round_trip(self):
        temp = Path(self.tmp.name)
        stock = temp / "stock/app"
        stock.mkdir(parents=True)
        # Own synthetic bytes as the delta's source; lpatch delta treats source as opaque.
        (stock / "HKMC_DiPO.odex").write_bytes(b"Synthetic original source, not an OEM odex")
        with zipfile.ZipFile(stock / "HKMC_DiPO.apk", "w") as archive:
            archive.writestr("synthetic.txt", "No manufacturer content")
        base = temp / "base.dex"
        builder.run("smali", "a", "-j", "1", "--api", "18", "-o", base, self.tree)
        patches = temp / "input-patches"
        (patches / "synthetic").mkdir(parents=True)
        delta = patches / "synthetic/original.lpd"
        builder.run(sys.executable, ROOT / "tools/mkdelta.py", stock / "HKMC_DiPO.odex", base, delta)
        apk = temp / "base.apk"
        builder.run(LPATCH, "zipadd", stock / "HKMC_DiPO.apk", base, apk)
        manifest = patches / "synthetic/manifest"
        manifest.write_text("".join(part + " app HKMC_DiPO synthetic/original.lpd " + builder.md5(apk) + "\n"
                                   for part in ("2_tbt", "3_bridge")))
        (patches / "synthetic/stock").write_text("".join(name + " " + builder.md5(stock / name) + "\n"
                                                  for name in ("HKMC_DiPO.apk", "HKMC_DiPO.odex")))
        output = temp / "output"
        plan = builder.inspect_build(stock.parent, "synthetic", patches, LPATCH)
        self.assertEqual([b["calls"] for b in plan["builds"]], [1,1])
        stale = copy.deepcopy(plan)
        stale["builds"][0]["base_dex_sha256"] = "0" * 64
        with self.assertRaises(ValueError):
            builder.build(stock.parent, "synthetic", temp / "stale-output", HELPERS, patches=patches, lpatch=LPATCH, plan=stale)
        self.assertFalse((temp / "stale-output").exists())
        report = builder.build(stock.parent, "synthetic", output, HELPERS, patches=patches, lpatch=LPATCH, plan=plan)
        self.assertEqual(len(report["builds"]), 2)
        self.assertFalse(report["on_car_tested"])
        self.assertEqual(json.loads((output / "call-sites.json").read_text()), plan)
        verifier.validate(output / "patches", output / "ar-build.json")
        self.assertEqual(manifest.read_text().count("original.lpd"), 2)
        self.assertEqual((output / "patches/synthetic/manifest").read_text().count(".ar.lpd"), 2)
        self.assertFalse(list(output.rglob("*.apk")))
        self.assertFalse(list(output.rglob("*.dex")))
        self.assertFalse(list(output.rglob("*.odex")))
        # Exercise the package boundary with generated deltas and provenance.
        shutil.copytree(ROOT / "patches", output / "patches", dirs_exist_ok=True)
        package = temp / "synthetic-package.zip"
        subprocess.run(["sh", str(ROOT / "tools/build-package.sh"), str(package)],
                       env=dict(os.environ, EVERGREEN_PATCHES_DIR=str(output / "patches")), check=True)
        with zipfile.ZipFile(package) as outer:
            with zipfile.ZipFile(io.BytesIO(outer.read("packages/wireless.carplay.aa.zip"))) as inner:
                self.assertEqual(json.loads(inner.read("AR_BUILD.json")), report)
                self.assertEqual(inner.read("patches/" + report["builds"][0]["patch"]),
                                 (output / "patches" / report["builds"][0]["patch"]).read_bytes())
        generated = output / "patches" / report["builds"][0]["patch"]
        generated.write_bytes(generated.read_bytes() + b"corrupted")
        with self.assertRaises(ValueError):
            verifier.validate(output / "patches", output / "ar-build.json")
        with self.assertRaises(ValueError):
            builder.build(stock.parent, "synthetic", output, HELPERS, 1, patches, LPATCH)
        (stock / "HKMC_DiPO.odex").write_bytes(b"wrong firmware")
        with self.assertRaises(ValueError):
            builder.build(stock.parent, "synthetic", temp / "bad-output", HELPERS, 1, patches, LPATCH)
        self.assertFalse((temp / "bad-output").exists())


class PackageTest(unittest.TestCase):
    def test_enabled_installer_is_restricted_and_preserves_verified_deltas(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            patches = tmp / "patches"
            shutil.copytree(ROOT / "patches", patches)
            report = {"schema": 1, "variant": "ev6", "on_car_tested": False, "enabled_by_default": False,
                      "features": {"icon_ar": True, "metadata_adapter": False, "distance_adapter": False,
                                   "progress_adapter": False}, "builds": []}
            manifest = patches / "ev6/manifest"
            lines = manifest.read_text().splitlines()
            for part in ("2_tbt", "3_bridge"):
                name = "ev6/HKMC_DiPO." + part.split("_", 1)[1] + ".ar.lpd"
                data = ("synthetic package fixture " + part).encode()
                (patches / name).write_bytes(data)
                checksum = "0" * 32
                lines = [part + " app HKMC_DiPO " + name + " " + checksum
                         if line.split()[:3] == [part, "app", "HKMC_DiPO"] else line for line in lines]
                report["builds"].append({"part": part, "patch": name, "calls": 1,
                                         "patch_sha256": hashlib.sha256(data).hexdigest(), "apk_md5": checksum})
            manifest.write_text("\n".join(lines)+"\n")
            report_path = tmp / "ar-build.json"
            report_path.write_text(json.dumps(report))
            output = tmp / "ar.zip"
            with self.assertRaises(ValueError):
                installer.build(patches, output, False)
            installer.build(patches, output, True)
            self.assertFalse(json.loads(report_path.read_text())["enabled_by_default"])
            with zipfile.ZipFile(output) as outer:
                self.assertIn("README-AR.txt", outer.namelist())
                with zipfile.ZipFile(io.BytesIO(outer.read("packages/wireless.carplay.aa.zip"))) as inner:
                    descriptor = inner.read("NEMESIS.data").decode()
                    self.assertIn('os="CVEV.USA.*.260507"', descriptor)
                    self.assertIn("package-version=1.2.0.101", descriptor)
                    self.assertIn("preinstall=scripts/ar-pre.sh", descriptor)
                    self.assertIn("name=evlog-offload", descriptor)
                    self.assertIn("name=wifi-client-warn", descriptor)
                    self.assertIn("ar-hud.enabled", inner.namelist())
                    self.assertTrue((inner.getinfo("tools/lpatch").external_attr >> 16) & 0o111)
                    deployed = json.loads(inner.read("AR_BUILD.json"))
                    self.assertTrue(deployed["enabled_by_default"])
                    self.assertEqual(deployed["activation"], "explicit-experimental-installer")
                    self.assertEqual(json.loads(inner.read("AR_INSTALL.json"))["enable_requested"], True)
                    for build in report["builds"]:
                        self.assertEqual(hashlib.sha256(inner.read("patches/"+build["patch"])).hexdigest(), build["patch_sha256"])
            with self.assertRaises(ValueError):
                installer.build(patches, output, True)
            with mock.patch.object(installer, "VERSION", "1.2.0-ar.1"):
                with self.assertRaises(subprocess.CalledProcessError):
                    installer.build(patches, tmp / "bad-version.zip", True)
            self.assertFalse((tmp / "bad-version.zip").exists())
            report["enabled_by_default"] = True
            report_path.write_text(json.dumps(report))
            with self.assertRaises(ValueError):
                verifier.validate(patches, report_path)

    def test_installer_firmware_guard_before_original_preinstall(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / "scripts").mkdir()
            (tmp / "scripts/pre.sh").write_text('echo delegated > PRE_RAN\n')
            for name in ("AR_BUILD.json", "AR_INSTALL.json", "ar-hud.enabled"):
                (tmp / name).write_text("synthetic\n")
            bin = tmp / "bin"
            bin.mkdir()
            (bin / "getprop").write_text('#!/bin/sh\necho "$AR_TEST_PRODUCT"\n')
            (bin / "getprop").chmod(0o755)
            script = (ROOT / "kits/ar-installer/ar-pre.sh").read_text()
            script = script.replace("export PATH=", "export PATH=" + str(bin) + ":").replace("exec /system/bin/sh", "exec /bin/sh")
            (tmp / "guard.sh").write_text(script)
            for product, expected in (("CVEV.CAN.0000.V015.011.260206", False),
                                      ("CVEV.USA.0000.V014.011.251120", False),
                                      ("CVEV.USA.0000.V015.012.260507", True)):
                result = subprocess.run(["sh", "guard.sh"], cwd=tmp,
                                        env=dict(os.environ, AR_TEST_PRODUCT=product), capture_output=True)
                self.assertEqual(result.returncode == 0, expected)
                self.assertEqual((tmp / "PRE_RAN").exists(), expected)

    def test_override_is_packaged_and_cars_table_is_checked(self):
        with tempfile.TemporaryDirectory() as temp:
            temp = Path(temp)
            patches = temp / "patches"
            shutil.copytree(ROOT / "patches", patches)
            (patches / "AR_TEST_MARKER").write_text("Synthetic package override check")
            output = temp / "package.zip"
            env = dict(os.environ, EVERGREEN_PATCHES_DIR=str(patches))
            subprocess.run(["sh", str(ROOT / "tools/build-package.sh"), str(output)], env=env, check=True)
            with zipfile.ZipFile(output) as outer:
                with zipfile.ZipFile(io.BytesIO(outer.read("packages/wireless.carplay.aa.zip"))) as inner:
                    self.assertEqual(inner.read("patches/AR_TEST_MARKER"), b"Synthetic package override check")
                    self.assertEqual(inner.read("patches/ev6/manifest"), (patches / "ev6/manifest").read_bytes())
            (patches / "cars").write_text("unexpected variant\n")
            rejected = temp / "rejected.zip"
            result = subprocess.run(["sh", str(ROOT / "tools/build-package.sh"), str(rejected)], env=env)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(rejected.exists())

    def test_invalid_ar_report_cannot_be_packaged(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            shutil.copytree(ROOT / "patches", tmp / "patches")
            (tmp / "ar-build.json").write_text('{}')
            result = subprocess.run(["sh", str(ROOT / "tools/build-package.sh"), str(tmp / "bad.zip")],
                                    env=dict(os.environ, EVERGREEN_PATCHES_DIR=str(tmp / "patches")))
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((tmp / "bad.zip").exists())


class CollectorTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.usb = self.root / "usb"
        self.usb.mkdir()
        self.source = self.root / "car"
        self.live = self.source / "system/app/HKMC_DiPO.apk"
        self.live.parent.mkdir(parents=True)
        self.live.write_bytes(b"our synthetic stock app")
        (self.usb / "expected-stock.txt").write_text("HKMC_DiPO.apk " + builder.md5(self.live) + "\n")
        firmware = self.source / "system/framework"
        firmware.mkdir()
        (firmware / "automotive.jar").write_bytes(b"our synthetic interface")
        secrets = self.source / "system/etc"
        secrets.mkdir()
        (secrets / "image.dat").write_bytes(b"must not collect")
        bin = self.root / "bin"
        bin.mkdir()
        commands = {"getprop": '#!/bin/sh\necho "$AR_TEST_PRODUCT"\n',
                    "md5": '#!/bin/sh\nexec md5sum "$@"\n'}
        for name, text in commands.items():
            path = bin / name
            path.write_text(text)
            path.chmod(0o755)
        self.env = dict(os.environ, EVERGREEN_AR_SOURCE_ROOT=str(self.source),
                        AR_TEST_PRODUCT="CVEV.USA.0000.V015.012.260507",
                        PATH=str(bin) + os.pathsep + os.environ["PATH"])

    def collect(self):
        return subprocess.run(["sh", str(ROOT / "kits/ar-stock/NEMESIS.sh")],
                              cwd=self.usb, env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_stock_backup_and_source_unchanged(self):
        backup = self.source / "data/nemesis/backup/projection.tbt/HKMC_DiPO.apk"
        backup.parent.mkdir(parents=True)
        shutil.copyfile(self.live, backup)
        self.live.write_bytes(b"modified live app")
        before = {p.relative_to(self.source): p.read_bytes() for p in self.source.rglob("*") if p.is_file()}
        self.assertEqual(self.collect().returncode, 0)
        output = self.usb / "EV6-AR-STOCK"
        self.assertEqual((output / "stock/app/HKMC_DiPO.apk").read_bytes(), backup.read_bytes())
        self.assertTrue((output / "stock/framework/automotive.jar").exists())
        self.assertFalse(list(output.rglob("image.dat")))
        after = {p.relative_to(self.source): p.read_bytes() for p in self.source.rglob("*") if p.is_file()}
        self.assertEqual(before, after)
        report = (output / "report.txt").read_bytes()
        self.assertNotEqual(self.collect().returncode, 0)
        self.assertEqual((output / "report.txt").read_bytes(), report)

    def test_wrong_firmware_writes_nothing(self):
        self.env["AR_TEST_PRODUCT"] = "CVEV.USA.0000.V014.011.251120"
        self.assertNotEqual(self.collect().returncode, 0)
        self.assertFalse((self.usb / "EV6-AR-STOCK").exists())

    def test_modified_app_without_stock_is_reported(self):
        self.live.write_bytes(b"wrong app")
        self.assertNotEqual(self.collect().returncode, 0)
        output = self.usb / "EV6-AR-STOCK"
        self.assertFalse((output / "stock/app/HKMC_DiPO.apk").exists())
        self.assertIn("MISSING_STOCK", (output / "report.txt").read_text())

    def test_collector_archive_contains_no_firmware_or_keys(self):
        output = self.root / "collector.zip"
        builder.run(sys.executable, ROOT / "tools/build-carplay-ar-collector.py", output)
        with zipfile.ZipFile(output) as archive:
            self.assertEqual(set(archive.namelist()), {"NEMESIS.data", "NEMESIS.sh", "expected-stock.txt", "README.txt"})
            self.assertEqual(archive.read("expected-stock.txt"), (ROOT / "patches/ev6/stock").read_bytes()
                             + (ROOT / "kits/ar-stock/extra-stock.txt").read_bytes())


class ControlTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.usb = self.root / "usb"
        self.usb.mkdir()
        self.package = self.root / "car/data/nemesis/apps/wireless.carplay.aa"
        self.package.mkdir(parents=True)
        self.marker = self.package / "ar-hud.enabled"
        bin = self.root / "bin"
        bin.mkdir()
        getprop = bin / "getprop"
        getprop.write_text('#!/bin/sh\necho "$AR_TEST_PRODUCT"\n')
        getprop.chmod(0o755)
        self.env = dict(os.environ, EVERGREEN_AR_SOURCE_ROOT=str(self.root / "car"),
                        AR_TEST_PRODUCT="CVEV.USA.0000.V015.012.260507", PATH=str(bin)+os.pathsep+os.environ["PATH"])

    def control(self, mode):
        (self.usb / "AR_MODE").write_text(mode+'\n')
        return subprocess.run(["sh", str(ROOT / "kits/ar-control/NEMESIS.sh")], cwd=self.usb,
                              env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_regular_evergreen_cannot_be_enabled(self):
        self.assertNotEqual(self.control("enable").returncode, 0)
        self.assertFalse(self.marker.exists())

    def test_enable_status_disable_changes_only_marker(self):
        report = self.package / "AR_BUILD.json"
        report.write_text('{"variant": "ev6"}\n')
        self.assertEqual(self.control("enable").returncode, 0)
        self.assertTrue(self.marker.is_file())
        self.assertIn(b"is enabled", self.control("status").stdout)
        self.assertEqual(self.control("disable").returncode, 0)
        self.assertFalse(self.marker.exists())
        self.assertIn(b"is disabled", self.control("status").stdout)
        self.assertEqual(report.read_text(), '{"variant": "ev6"}\n')

    def test_wrong_firmware_and_unknown_mode_rejected_but_disable_available(self):
        (self.package / "AR_BUILD.json").write_text('{"variant": "ev6"}\n')
        self.env["AR_TEST_PRODUCT"]="CVEV.CAN.0000.V015.011.260206"
        self.assertNotEqual(self.control("enable").returncode, 0)
        self.assertNotEqual(self.control("unexpected").returncode, 0)
        self.marker.touch()
        self.assertEqual(self.control("disable").returncode, 0)
        self.assertFalse(self.marker.exists())


if __name__ == "__main__":
    unittest.main()
