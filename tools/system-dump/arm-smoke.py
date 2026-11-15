#!/usr/bin/env python3
"""Run the ARM build under qemu with a synthetic source and verify its output."""
# SPDX-License-Identifier: GPL-3.0-only
import hashlib
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile

spec = importlib.util.spec_from_file_location("verifier", Path(__file__).with_name("verify-dump.py"))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)

production, fixture = map(lambda p: str(Path(p).resolve()), sys.argv[1:])
usage = subprocess.run(["qemu-arm", production], capture_output=True, timeout=10)
assert usage.returncode == 1 and b"usage:" in usage.stderr, usage
with tempfile.TemporaryDirectory() as temp:
    root = Path(temp)
    source, usb = root / "synthetic-system", root / "usb"
    data = bytes(range(256)) * 9000 + b"ARM short final chunk"
    source.write_bytes(data)
    usb.mkdir()
    env = dict(os.environ, DUMP_TEST_SOURCE=str(source))
    command = ["qemu-arm", fixture, "--foreground", str(usb), "CVEV.USA.0000.V015.012.260507"]
    for _ in range(2):
        result = subprocess.run(command, env=env, capture_output=True, timeout=30)
        assert result.returncode == 0, (result, (usb / "SYSTEM-DUMP/collector.log").read_text())
    verified = verifier.verify(usb / "SYSTEM-DUMP", root / "system.img")
    assert verified["sha256"] == hashlib.sha256(data).hexdigest()
    assert (root / "system.img").read_bytes() == data
print("ARM static binary startup, image round trip and resume pass under qemu.")
