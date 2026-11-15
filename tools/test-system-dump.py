#!/usr/bin/env python3
"""Behavior tests using synthetic regular files; never opens a car/device."""
# SPDX-License-Identifier: GPL-3.0-only
import hashlib
import importlib.util
import os
from pathlib import Path
import signal
import shutil
import resource
import subprocess
import tempfile
import time
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


verifier = load("verifier", ROOT / "tools/system-dump/verify-dump.py")
builder = load("builder", ROOT / "tools/build-system-dump-collector.py")


class DumpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = tempfile.TemporaryDirectory()
        cls.binary = Path(cls.build.name) / "collector"
        # CC may be a whitespace-separated command, e.g. 'zig cc'.
        import shlex
        cc = shlex.split(os.environ.get("CC", "cc"))
        subprocess.run(cc + ["-DDUMP_TEST", "-O2", "-Wall", "-Wextra", "-Werror",
                            str(ROOT / "src/system-dump/collector.c"), "-o", str(cls.binary)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.build.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "synthetic-system"
        self.data = bytes(range(256)) * 10000 + b"last short chunk"
        self.source.write_bytes(self.data)
        self.usb = self.root / "usb"
        self.usb.mkdir()
        self.dump = self.usb / "SYSTEM-DUMP"
        self.env = dict(os.environ, DUMP_TEST_SOURCE=str(self.source))

    def command(self, mode="--foreground", product="CVEV.USA.0000.V015.012.260507"):
        return [str(self.binary), mode, str(self.usb), product]

    def run_dump(self, **env):
        return subprocess.run(self.command(), env=dict(self.env, **env), capture_output=True, timeout=10)

    def log(self):
        return (self.dump / "collector.log").read_text()

    def test_launcher_diagnostics(self):
        import sys
        subprocess.run([sys.executable, str(ROOT / "tools/system-dump/test-launcher.py")], check=True)

    def test_roundtrip_checksums_assembly_and_repeat(self):
        self.assertEqual(self.run_dump().returncode, 0, self.log())
        info = verifier.verify(self.dump, self.root / "system.img")
        self.assertEqual(info["sha256"], hashlib.sha256(self.data).hexdigest())
        self.assertEqual((self.root / "system.img").read_bytes(), self.data)
        self.assertEqual(self.source.read_bytes(), self.data)
        chunks = sorted(self.dump.glob("*.bin"))
        self.assertEqual(len(chunks), 3)
        mtimes = [p.stat().st_mtime_ns for p in chunks]
        self.assertEqual(self.run_dump().returncode, 0, self.log())
        self.assertEqual(mtimes, [p.stat().st_mtime_ns for p in chunks])
        self.assertIn("Verified saved chunk", self.log())

    def test_interruption_resume_and_lock(self):
        env = dict(self.env, DUMP_TEST_PAUSE_US="100000")
        p = subprocess.Popen(self.command(), env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.addCleanup(lambda: p.kill() if p.poll() is None else None)
        deadline = time.monotonic() + 6
        while time.monotonic() < deadline and not (self.dump / "system.000000.bin.sha256").exists():
            time.sleep(.02)
        self.assertTrue((self.dump / "system.000000.bin.sha256").exists())
        duplicate = self.run_dump()
        self.assertNotEqual(duplicate.returncode, 0)
        self.assertIn(b"already running", duplicate.stderr)
        p.send_signal(signal.SIGTERM)
        p.communicate(timeout=5)
        self.assertEqual(p.returncode, 2)
        self.assertIn("state=PAUSED", (self.dump / "STATUS.txt").read_text())
        self.assertFalse((self.dump / "COMPLETE").exists())
        first = (self.dump / "system.000000.bin").stat().st_mtime_ns
        self.assertEqual(self.run_dump().returncode, 0, self.log())
        self.assertEqual(first, (self.dump / "system.000000.bin").stat().st_mtime_ns)
        verifier.verify(self.dump)

    def test_low_space_stops_before_image_write_then_resumes(self):
        self.assertNotEqual(self.run_dump(DUMP_TEST_AVAILABLE="0").returncode, 0)
        self.assertIn("state=NEEDS_SPACE", (self.dump / "STATUS.txt").read_text())
        self.assertFalse(list(self.dump.glob("*.bin")))
        self.assertEqual(self.run_dump().returncode, 0, self.log())

    def test_changed_source_rejected(self):
        self.assertEqual(self.run_dump().returncode, 0)
        self.source.write_bytes(b"X" + self.data[1:])
        self.assertNotEqual(self.run_dump().returncode, 0)
        self.assertIn("system differs", self.log())
        self.assertIn("state=ERROR", (self.dump / "STATUS.txt").read_text())
        self.assertFalse((self.dump / "COMPLETE").exists())

    def test_corrupt_committed_chunk_rejected_on_car_and_host(self):
        self.assertEqual(self.run_dump().returncode, 0)
        chunk = self.dump / "system.000000.bin"
        data = chunk.read_bytes()
        chunk.write_bytes(b"X" + data[1:])
        with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
            verifier.verify(self.dump, self.root / "bad.img")
        self.assertFalse((self.root / "bad.img").exists())
        self.assertFalse((self.root / "bad.img.part").exists())
        self.assertNotEqual(self.run_dump().returncode, 0)
        self.assertIn("saved chunk checksum mismatch", self.log())

    def test_orphan_partial_and_receipt_commit_recovery(self):
        self.assertEqual(self.run_dump().returncode, 0)
        (self.dump / "system.000001.bin.sha256").unlink()
        (self.dump / "system.000001.bin.part").write_bytes(b"incomplete")
        self.assertEqual(self.run_dump().returncode, 0, self.log())
        self.assertFalse(list(self.dump.glob("*.part")))
        verifier.verify(self.dump)

    def test_stop_marker_requires_removal(self):
        self.dump.mkdir()
        (self.dump / "STOP").touch()
        self.assertEqual(self.run_dump().returncode, 2)
        self.assertFalse(list(self.dump.glob("*.bin")))
        (self.dump / "STOP").unlink()
        self.assertEqual(self.run_dump().returncode, 0)

    def test_changed_product_identity_rejected(self):
        self.assertEqual(self.run_dump().returncode, 0)
        result = subprocess.run(self.command(product="OTHER.260507"), env=self.env, capture_output=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("dump identity differs", self.log())

    def test_source_and_usb_links_rejected(self):
        self.source.unlink()
        self.source.mkdir()
        self.assertNotEqual(self.run_dump().returncode, 0)
        self.source.rmdir()
        self.source.write_bytes(self.data)
        other = self.root / "outside"
        other.mkdir()
        shutil.rmtree(self.dump)
        self.dump.symlink_to(other, target_is_directory=True)
        self.assertNotEqual(self.run_dump().returncode, 0)
        self.assertEqual(list(other.iterdir()), [])

    def test_chunk_symlink_does_not_overwrite_target(self):
        self.assertEqual(self.run_dump().returncode, 0)
        target = self.root / "keep"
        target.write_bytes(b"must remain untouched")
        chunk = self.dump / "system.000000.bin"
        chunk.unlink()
        chunk.symlink_to(target)
        self.assertNotEqual(self.run_dump().returncode, 0)
        self.assertEqual(target.read_bytes(), b"must remain untouched")

    def test_host_rejects_incomplete_extra_missing_and_oversized_metadata(self):
        self.assertEqual(self.run_dump().returncode, 0)
        complete = self.dump / "COMPLETE"
        complete.unlink()
        with self.assertRaises(ValueError):
            verifier.verify(self.dump)
        complete.touch()
        extra = self.dump / "system.999999.bin"
        extra.touch()
        with self.assertRaisesRegex(ValueError, "unexpected"):
            verifier.verify(self.dump)
        extra.unlink()
        meta = self.dump / "image.info"
        original = meta.read_text()
        meta.write_text(original.replace("chunk_size=1048576", "chunk_size=1"))
        with self.assertRaisesRegex(ValueError, "chunk size"):
            verifier.verify(self.dump)

    def test_existing_host_files_preserved(self):
        self.assertEqual(self.run_dump().returncode, 0)
        path = self.root / "existing.img"
        path.write_bytes(b"keep")
        with self.assertRaises(ValueError):
            verifier.verify(self.dump, path)
        self.assertEqual(path.read_bytes(), b"keep")
        partial = self.root / "new.img.part"
        partial.write_bytes(b"earlier run")
        with self.assertRaises(FileExistsError):
            verifier.verify(self.dump, self.root / "new.img")
        self.assertEqual(partial.read_bytes(), b"earlier run")

    def test_detached_worker_returns_and_finishes(self):
        started = time.monotonic()
        result = subprocess.run(self.command("--start"), env=dict(self.env, DUMP_TEST_PAUSE_US="50000"),
                                capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(b"STARTED, not finished", result.stdout)
        self.assertLess(time.monotonic()-started, 2)
        deadline = time.monotonic()+8
        while time.monotonic()<deadline and not (self.dump / "COMPLETE").exists():
            time.sleep(.05)
        self.assertTrue((self.dump / "COMPLETE").exists(), self.log())
        verifier.verify(self.dump)

    def test_large_source_with_small_memory_limit(self):
        # A sparse 64 MiB source cannot fit under this 24 MiB address-space limit.
        with self.source.open("wb") as stream:
            stream.truncate(64 * 1024**2 + 57)
        def limit():
            resource.setrlimit(resource.RLIMIT_AS, (24 * 1024**2, 24 * 1024**2))
        result = subprocess.run(self.command(), env=self.env, capture_output=True, timeout=20, preexec_fn=limit)
        self.assertEqual(result.returncode, 0, self.log())
        self.assertEqual(verifier.verify(self.dump)["bytes"], 64 * 1024**2 + 57)

    def test_sha256_padding_and_empty_partial_cleanup(self):
        # Cross-check padding boundaries against Python's independent SHA-256.
        for length in (1, 55, 56, 63, 64, 65, 127, 128, 129):
            if self.dump.exists():
                shutil.rmtree(self.dump)
            data = bytes(range(256))[:length]
            self.source.write_bytes(data)
            self.assertEqual(self.run_dump().returncode, 0, self.log())
            self.assertEqual(verifier.verify(self.dump)["sha256"], hashlib.sha256(data).hexdigest())


class PackageTest(unittest.TestCase):
    def test_arm_kit_structure_and_hashes(self):
        binary = ROOT / "src/system-dump/build/collector-arm"
        if not binary.exists():
            self.skipTest("Build the production ARM helper first")
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "collector.zip"
            builder.build(binary, output)
            with zipfile.ZipFile(output) as archive:
                self.assertEqual(set(archive.namelist()), {"collector-arm", "collector.md5", "collector.sha256",
                                                        "verify-dump.py", "README.txt", "NEMESIS.sh", "NEMESIS.data"})
                self.assertEqual(archive.read("collector.sha256").decode().strip(), hashlib.sha256(binary.read_bytes()).hexdigest())
                self.assertNotIn(b"NEMESIS.emergency", archive.read("NEMESIS.sh"))

    def test_wrong_architecture_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "bad"
            path.write_bytes(b"not an ARM executable")
            with self.assertRaises(ValueError):
                builder.build(path, Path(temp) / "bad.zip")


if __name__ == "__main__":
    unittest.main()
