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
import os

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    subprocess.run([str(x) for x in args], check=True)


def md5(path):
    return hashlib.md5(Path(path).read_bytes()).hexdigest()


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def patcher():
    spec = importlib.util.spec_from_file_location("patch_carplay_ar", ROOT / "tools/patch-carplay-ar.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def inputs(stock, variant, patches):
    if not variant or Path(variant).name != variant or variant in (".", ".."):
        raise ValueError("variant must be a single patch-set name")
    lines = (patches / variant / "manifest").read_text().splitlines()
    wanted = dict(line.split() for line in (patches / variant / "stock").read_text().splitlines() if line.strip())
    fingerprint = {}
    for name in ("HKMC_DiPO.apk", "HKMC_DiPO.odex"):
        file = stock / "app" / name
        if md5(file) != wanted.get(name):
            raise ValueError("stock checksum mismatch: " + name)
        fingerprint[name] = sha256(file)
    entries = [line.split() for line in lines if len(line.split()) == 5
               and line.split()[1:3] == ["app", "HKMC_DiPO"]]
    if {e[0] for e in entries} != {"2_tbt", "3_bridge"} or len(entries) != 2:
        raise ValueError("expected exactly the turn-by-turn and bridge DiPO entries")
    return lines, entries, fingerprint


def reconstruct(stock, patches, entry, temp, lpatch):
    part, kind, name, delta, expected_apk = entry
    dex, apk = temp / (part + "-base.dex"), temp / (part + "-base.apk")
    run(lpatch, "delta", patches / delta, stock / "app/HKMC_DiPO.odex", dex)
    run(lpatch, "zipadd", stock / "app/HKMC_DiPO.apk", dex, apk)
    if md5(apk) != expected_apk:
        raise ValueError("existing Evergreen APK did not match manifest: " + part)
    tree = temp / part
    run("baksmali", "d", dex, "-o", tree)
    sites = patcher().inspect(tree)
    if not sites:
        raise ValueError("no supported cluster call sites in " + part)
    return tree, {"part": part, "base_patch": delta, "base_patch_sha256": sha256(patches / delta),
                  "base_dex_sha256": sha256(dex), "calls": len(sites), "sites": sites}


def inspect_build(stock, variant, patches=None, lpatch=None):
    """No helper compilation or OEM-file publication: only names and fingerprints."""
    patches = Path(patches or ROOT / "patches").resolve()
    lpatch = Path(lpatch or ROOT / "tools/lpatch/lpatch-host").resolve()
    stock = Path(stock).resolve()
    _, entries, fingerprint = inputs(stock, variant, patches)
    result = {"schema": 1, "variant": variant, "stock_sha256": fingerprint, "builds": []}
    with tempfile.TemporaryDirectory(prefix="evergreen-inspect-") as temp:
        for entry in entries:
            _, plan = reconstruct(stock, patches, entry, Path(temp), lpatch)
            result["builds"].append(plan)
    return result


def build(stock, variant, output, helpers, calls=None, patches=None, lpatch=None, plan=None):
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
    lines, entries, fingerprint = inputs(stock, variant, patches)
    module = patcher()
    helper_dir = Path(helpers) / "org/evergreen/carplay"
    if {p.name for p in Path(helpers).rglob("*.smali")} != module.HELPER_NAMES:
        raise ValueError("build the ten AR helper classes first")
    if plan is not None and (not isinstance(plan, dict) or plan.get("schema") != 1 or plan.get("variant") != variant
                             or plan.get("stock_sha256") != fingerprint):
        raise ValueError("inspection plan doesn't match these stock inputs")
    if calls is None and plan is None:
        raise ValueError("supply an inspection plan or inspected call counts")
    output.parent.mkdir(parents=True, exist_ok=True)
    # Full OEM files exist only inside this temporary directory, removed on failure too.
    with tempfile.TemporaryDirectory(prefix="evergreen-ar-") as temp:
        temp = Path(temp)
        staged = temp / "patches"
        shutil.copytree(patches, staged)
        report = {"schema": 1, "variant": variant, "on_car_tested": False, "enabled_by_default": False,
                  "stock_sha256": fingerprint,
                  "helper_sha256": {p.name: sha256(p) for p in sorted(helper_dir.glob("*.smali"))},
                  "features": {"icon_ar": True, "metadata_adapter": False,
                               "distance_adapter": False, "progress_adapter": False}, "builds": []}
        actual_plan = {"schema": 1, "variant": variant, "stock_sha256": fingerprint, "builds": []}
        for part, kind, name, old_patch, expected_apk in entries:
            tree, inspected = reconstruct(stock, patches, (part, kind, name, old_patch, expected_apk), temp, lpatch)
            actual_plan["builds"].append(inspected)
            if plan is not None:
                expected = next((p for p in plan.get("builds", []) if p.get("part") == part), None)
                if expected != inspected:
                    raise ValueError("inspection plan is stale for " + part)
                expected_calls = expected["calls"]
            else:
                expected_calls = calls[part] if isinstance(calls, dict) else calls
            module.patch(tree, helpers, expected_calls)
            result = temp / (part + ".dex")
            run("smali", "a", "-j", "1", "--api", "18", "-o", result, tree)
            repeat = temp / "repeat.dex"
            run("smali", "a", "-j", "1", "--api", "18", "-o", repeat, tree)
            if result.read_bytes() != repeat.read_bytes():
                raise ValueError("DEX assembly is not deterministic")
            # Parse the assembled dex again; catches assembler/format errors, not
            # Dalvik verification or the OEM service's actual behavior on a car.
            run("baksmali", "d", result, "-o", temp / (part + "-verify"))
            remaining = module.inspect(temp / (part + "-verify"))
            if (len(remaining) != 3 or {s["file"] for s in remaining} != {
                    "org/evergreen/carplay/ArHudForwarder.smali", "org/evergreen/carplay/ArHudRuntime.smali"}):
                raise ValueError("unexpected direct cluster calls after injection")
            new_patch = variant + "/HKMC_DiPO." + part.split("_", 1)[1] + ".ar.lpd"
            run(sys.executable, ROOT / "tools/mkdelta.py", stock / "app/HKMC_DiPO.odex", result, staged / new_patch)
            roundtrip = temp / "roundtrip.dex"
            run(lpatch, "delta", staged / new_patch, stock / "app/HKMC_DiPO.odex", roundtrip)
            if result.read_bytes() != roundtrip.read_bytes():
                raise ValueError("delta round trip failed")
            apk = temp / "result.apk"
            run(lpatch, "zipadd", stock / "app/HKMC_DiPO.apk", result, apk)
            checksum = md5(apk)
            old_line = " ".join((part, kind, name, old_patch, expected_apk))
            new_line = " ".join((part, kind, name, new_patch, checksum))
            lines = [new_line if line.split() == old_line.split() else line for line in lines]
            report["builds"].append({"part": part, "patch": new_patch, "apk_md5": checksum,
                                     "dex_sha256": sha256(result), "patch_sha256": sha256(staged / new_patch),
                                     "calls": expected_calls})
        if plan is not None and actual_plan != plan:
            raise ValueError("inspection plan contains unexpected builds or fields")
        (staged / variant / "manifest").write_text("\n".join(lines) + "\n")
        # Only delta files, checksums and report leave the private temporary tree.
        with tempfile.TemporaryDirectory(prefix=".ar-publish-", dir=output.parent) as publish:
            publish = Path(publish) / "result"
            publish.mkdir()
            shutil.copytree(staged, publish / "patches")
            (publish / "ar-build.json").write_text(json.dumps(report, indent=2) + "\n")
            (publish / "call-sites.json").write_text(json.dumps(actual_plan, indent=2) + "\n")
            os.rename(publish, output)
    return report


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--stock", type=Path, required=True)
    p.add_argument("--variant", required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--inspect", action="store_true", help="write only a fingerprinted call-site plan; no patches")
    p.add_argument("--plan", type=Path, help="previous --inspect JSON; rejects changed inputs or call sites")
    p.add_argument("--expected-calls", type=int, help="legacy explicit count, same for both builds")
    p.add_argument("--helpers", type=Path, default=ROOT / "src/carplay-ar/build/smali")
    a = p.parse_args()
    try:
        if a.inspect:
            if a.out.exists(): raise ValueError("output already exists")
            report = inspect_build(a.stock, a.variant)
            a.out.parent.mkdir(parents=True, exist_ok=True)
            with a.out.open("x") as f: f.write(json.dumps(report, indent=2) + "\n")
        else:
            plan = json.loads(a.plan.read_text()) if a.plan else None
            report = build(a.stock, a.variant, a.out, a.helpers, a.expected_calls, plan=plan)
        print(json.dumps(report, indent=2))
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        p.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
