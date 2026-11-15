#!/usr/bin/env python3
"""Stream-verify a completed system dump, optionally assembling its ext4 image."""
# SPDX-License-Identifier: GPL-3.0-only
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import sys

BUFFER = 256 * 1024


def regular(path):
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"Missing regular file: {path.name}")
    return path


def small_text(path, maximum=4096):
    regular(path)
    if path.stat().st_size > maximum:
        raise ValueError(f"Oversized metadata: {path.name}")
    return path.read_text(encoding="ascii")


def metadata(directory):
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError("Dump directory must be a real directory")
    info = {}
    for line in small_text(directory / "image.info").splitlines():
        key, separator, value = line.partition("=")
        if not separator or key in info:
            raise ValueError("Invalid or duplicate image metadata")
        info[key] = value
    if (info.get("format") != "evergreen-system-dump-v1"
            or info.get("scope") != "system-partition-only"
            or info.get("contents") != "as-installed-not-guaranteed-stock"):
        raise ValueError("Unsupported dump format or scope")
    size, chunk = int(info["size"]), int(info["chunk_size"])
    if not 0 < size <= 128 * 1024**3 or chunk not in (1024**2, 64 * 1024**2):
        raise ValueError("Invalid image or chunk size")
    small_text(directory / "COMPLETE")
    names = {p.name for p in directory.glob("system.*.bin")}
    expected = {f"system.{i:06d}.bin" for i in range((size + chunk - 1) // chunk)}
    if names != expected:
        raise ValueError("Missing or unexpected image chunks")
    return info, size, chunk


def verify(directory, assemble=None):
    directory = Path(directory)
    info, size, chunk = metadata(directory)
    output = partial = None
    whole = hashlib.sha256()
    if assemble is not None:
        output = Path(assemble).absolute()
        if output.exists() or output.is_symlink():
            raise ValueError("Output already exists; choose a new image path")
        if shutil.disk_usage(output.parent).free < size + 256 * 1024**2:
            raise ValueError("Not enough disk space to assemble the image plus a 256 MiB reserve")
        partial = output.with_name(output.name + ".part")
    try:
        # Exclusive creation preserves any earlier incomplete host assembly.
        stream = partial.open("xb") if partial else None
        try:
            for index, offset in enumerate(range(0, size, chunk)):
                name = f"system.{index:06d}.bin"
                path = regular(directory / name)
                expected = small_text(directory / (name + ".sha256"), 100)
                if not re.fullmatch(r"[0-9a-f]{64}\n", expected):
                    raise ValueError(f"Invalid checksum receipt: {name}")
                length = min(chunk, size - offset)
                if path.stat().st_size != length:
                    raise ValueError(f"Wrong chunk size: {name}")
                digest = hashlib.sha256()
                with path.open("rb") as source:
                    remaining = length
                    while remaining:
                        data = source.read(min(BUFFER, remaining))
                        if not data:
                            raise ValueError(f"Truncated chunk: {name}")
                        remaining -= len(data)
                        digest.update(data)
                        whole.update(data)
                        if stream:
                            stream.write(data)
                    if source.read(1):
                        raise ValueError(f"Chunk grew while reading: {name}")
                if digest.hexdigest() != expected.strip():
                    raise ValueError(f"Checksum mismatch: {name}")
                print(f"Verified {index + 1}/{(size + chunk - 1) // chunk}: {name}", file=sys.stderr)
            if stream:
                stream.flush()
                os.fsync(stream.fileno())
        finally:
            if stream:
                stream.close()
        if partial:
            # link gives exclusive final creation; never overwrite a racing output.
            os.link(partial, output)
            partial.unlink()
        return {"bytes": size, "sha256": whole.hexdigest(), "product": info.get("product", ""),
                "image": str(output) if output else None}
    except BaseException:
        # Only remove the partial created by this invocation, not a pre-existing one.
        if partial and "stream" in locals() and stream is not None:
            partial.unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="SYSTEM-DUMP directory copied from USB")
    parser.add_argument("--assemble", type=Path, metavar="SYSTEM.IMG", help="also create a complete ext4 partition image")
    args = parser.parse_args()
    try:
        result = verify(args.directory, args.assemble)
    except (OSError, ValueError, KeyError) as error:
        parser.exit(1, f"Verification failed: {error}\n")
    print(f"Verified {result['bytes']} bytes; image SHA-256: {result['sha256']}")
    if result["image"]:
        print(f"Image: {result['image']}")
