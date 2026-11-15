#!/usr/bin/env python3
"""Synthetic original fixtures only: no manufacturer apps or stock files."""
import importlib.util
import io
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / "tools" / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


patcher = load("patch_ar", "patch-carplay-ar.py")
builder = load("build_ar", "build-carplay-ar.py")
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
        report = builder.build(stock.parent, "synthetic", output, HELPERS, 1, patches, LPATCH)
        self.assertEqual(len(report["builds"]), 2)
        self.assertFalse(report["on_car_tested"])
        self.assertEqual(manifest.read_text().count("original.lpd"), 2)
        self.assertEqual((output / "patches/synthetic/manifest").read_text().count(".ar.lpd"), 2)
        self.assertFalse(list(output.rglob("*.apk")))
        self.assertFalse(list(output.rglob("*.dex")))
        self.assertFalse(list(output.rglob("*.odex")))
        with self.assertRaises(ValueError):
            builder.build(stock.parent, "synthetic", output, HELPERS, 1, patches, LPATCH)
        (stock / "HKMC_DiPO.odex").write_bytes(b"wrong firmware")
        with self.assertRaises(ValueError):
            builder.build(stock.parent, "synthetic", temp / "bad-output", HELPERS, 1, patches, LPATCH)
        self.assertFalse((temp / "bad-output").exists())


class PackageTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
