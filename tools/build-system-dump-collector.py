#!/usr/bin/env python3
"""Package the separately built static ARM system collector and host verifier."""
# SPDX-License-Identifier: GPL-3.0-only
import argparse
import hashlib
from pathlib import Path
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def build(binary, output):
    native = binary.read_bytes()
    # ELF32 little-endian ARM ET_EXEC. A static build must have no PT_INTERP.
    if len(native) < 52 or native[:7] != b"\x7fELF\x01\x01\x01" or struct.unpack_from("<HH", native, 16) != (2, 40):
        raise ValueError("Expected a static 32-bit ARM Linux executable")
    offset = struct.unpack_from("<I", native, 28)[0]
    entry, count = struct.unpack_from("<HH", native, 42)
    if entry < 32 or not count or offset + entry * count > len(native):
        raise ValueError("Invalid ELF program headers")
    if any(struct.unpack_from("<I", native, offset + entry * i)[0] == 3 for i in range(count)):
        raise ValueError("Collector must be static; dynamic loader found")
    files = {"collector-arm": native, "collector.md5": (hashlib.md5(native).hexdigest()+"\n").encode(),
             "collector.sha256": (hashlib.sha256(native).hexdigest()+"\n").encode(),
             "verify-dump.py": (ROOT / "tools/system-dump/verify-dump.py").read_bytes(),
             "README.txt": (ROOT / "kits/system-dump/README.txt").read_bytes()}
    for name in ("NEMESIS.data", "NEMESIS.sh"):
        files[name] = (ROOT / "kits/system-dump" / name).read_bytes()
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in files.items():
            item = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            item.external_attr = 0o100644 << 16
            item.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(item, data)
    print(f"{output} ({output.stat().st_size} bytes)")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--binary", type=Path, default=ROOT / "src/system-dump/build/collector-arm")
    p.add_argument("--out", type=Path, default=ROOT / "dist/Evergreen-system-dump-collector.zip")
    a = p.parse_args()
    try:
        build(a.binary, a.out)
    except (ValueError, OSError, struct.error) as error:
        p.exit(1, str(error)+"\n")
