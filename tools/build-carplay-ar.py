#!/usr/bin/env python3
"""Build a private AR patch tree from an existing Evergreen variant and stock files.

Rebuilds BOTH DiPO variants (turn-by-turn and Bluetooth bridge), verifies their
existing APK hashes, injects the helper, assembles deterministically, and verifies
new deltas by round trip. Writes a complete patches/ copy to a NEW output folder;
never overwrites the committed patches or publishes a full modified OEM app.
Needs host lpatch, smali/baksmali, built AR helpers, tools/requirements.txt.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    subprocess.run([str(x) for x in args], check=True)


def md5(path):
    return hashlib.md5(Path(path).read_bytes()).hexdigest()


def build(stock, variant, output, helpers, calls, patches=None, lpatch=None):
    patches = Path(patches or ROOT / "patches").resolve()
    lpatch = Path(lpatch or ROOT / "tools/lpatch/lpatch-host").resolve()
    stock, output = Path(stock).resolve(), Path(output).resolve()
    if not variant or Path(variant).name != variant or variant in (".", ".."):
        raise ValueError("variant must be a single patch-set name")
    if output.exists():
        raise ValueError("output already exists; choose a new private directory")
    if output.is_relative_to(ROOT) and not output.is_relative_to(ROOT / "dist"):
        raise ValueError("keep generated output outside the repository or under ignored dist/")
    if output.is_relative_to(stock):
        raise ValueError("output must not be inside the stock collection")
    manifest = patches / variant / "manifest"
    lines = manifest.read_text().splitlines()
    wanted = dict(line.split() for line in (patches / variant / "stock").read_text().splitlines() if line.strip())
    for name in ("HKMC_DiPO.apk", "HKMC_DiPO.odex"):
        if md5(stock / "app" / name) != wanted.get(name):
            raise ValueError("stock checksum mismatch: " + name)
    entries = [line.split() for line in lines if len(line.split()) == 5
               and line.split()[1:3] == ["app", "HKMC_DiPO"]]
    if {e[0] for e in entries} != {"2_tbt", "3_bridge"} or len(entries) != 2:
        raise ValueError("expected exactly the turn-by-turn and bridge DiPO entries")
    spec = importlib.util.spec_from_file_location("patch_carplay_ar", ROOT / "tools/patch-carplay-ar.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    output.parent.mkdir(parents=True, exist_ok=True)
    # Full OEM files exist only inside this temporary directory, removed on failure too.
    with tempfile.TemporaryDirectory(prefix="evergreen-ar-") as temp:
        temp = Path(temp)
        staged = temp / "patches"
        shutil.copytree(patches, staged)
        report = {"variant": variant, "on_car_tested": False, "enabled_by_default": False, "builds": []}
        for part, kind, name, old_patch, expected_apk in entries:
            dex, apk = temp / "base.dex", temp / "base.apk"
            run(lpatch, "delta", patches / old_patch, stock / "app/HKMC_DiPO.odex", dex)
            run(lpatch, "zipadd", stock / "app/HKMC_DiPO.apk", dex, apk)
            if md5(apk) != expected_apk:
                raise ValueError("existing Evergreen APK did not match manifest: " + part)
            tree = temp / part
            run("baksmali", "d", dex, "-o", tree)
            module.patch(tree, helpers, calls)
            result = temp / (part + ".dex")
            run("smali", "a", "-j", "1", "--api", "18", "-o", result, tree)
            repeat = temp / "repeat.dex"
            run("smali", "a", "-j", "1", "--api", "18", "-o", repeat, tree)
            if result.read_bytes() != repeat.read_bytes():
                raise ValueError("DEX assembly is not deterministic")
            # Parse the assembled dex again; catches assembler/format errors, not
            # Dalvik verification or the OEM service's actual behavior on a car.
            run("baksmali", "d", result, "-o", temp / (part + "-verify"))
            new_patch = variant + "/HKMC_DiPO." + part.split("_", 1)[1] + ".ar.lpd"
            run(sys.executable, ROOT / "tools/mkdelta.py", stock / "app/HKMC_DiPO.odex", result, staged / new_patch)
            roundtrip = temp / "roundtrip.dex"
            run(lpatch, "delta", staged / new_patch, stock / "app/HKMC_DiPO.odex", roundtrip)
            if result.read_bytes() != roundtrip.read_bytes():
                raise ValueError("delta round trip failed")
            run(lpatch, "zipadd", stock / "app/HKMC_DiPO.apk", result, apk)
            checksum = md5(apk)
            old_line = " ".join((part, kind, name, old_patch, expected_apk))
            new_line = " ".join((part, kind, name, new_patch, checksum))
            lines = [new_line if line.strip() == old_line else line for line in lines]
            report["builds"].append({"part": part, "patch": new_patch, "apk_md5": checksum, "calls": calls})
        (staged / variant / "manifest").write_text("\n".join(lines) + "\n")
        # Only delta files, checksums and report leave the private temporary tree.
        output.mkdir()
        shutil.copytree(staged, output / "patches")
        (output / "ar-build.json").write_text(json.dumps(report, indent=2) + "\n")
    return report


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--stock", type=Path, required=True)
    p.add_argument("--variant", required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--expected-calls", type=int, required=True)
    p.add_argument("--helpers", type=Path, default=ROOT / "src/carplay-ar/build/smali")
    a = p.parse_args()
    try:
        report = build(a.stock, a.variant, a.out, a.helpers, a.expected_calls)
        print(json.dumps(report, indent=2))
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        p.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
