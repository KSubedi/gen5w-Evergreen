#!/usr/bin/env python3
"""Validate generated AR delta/manifest fingerprints before packaging.

This checks the host build report, not physical HUD behavior or Dalvik runtime
verification. The normal installer still checks the car's stock files itself.
"""
import argparse
import hashlib
import json
from pathlib import Path

PARTS = {"2_tbt", "3_bridge"}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate(patches, report):
    patches, report = Path(patches).resolve(), Path(report).resolve()
    data = json.loads(report.read_text())
    if not isinstance(data, dict):
        raise ValueError("AR report must be an object")
    variant = data.get("variant")
    if data.get("schema") != 1 or not isinstance(variant, str) or Path(variant).name != variant or variant in (".", ".."):
        raise ValueError("unsupported AR build report")
    enabled = data.get("enabled_by_default")
    if (data.get("on_car_tested") is not False or type(enabled) is not bool
            or (enabled and data.get("activation") != "explicit-experimental-installer")):
        raise ValueError("prototype report must explicitly be untested and opted in when enabled")
    features = data.get("features", {})
    if features != {"icon_ar": True, "metadata_adapter": False, "distance_adapter": False, "progress_adapter": False}:
        raise ValueError("unknown AR adapter capabilities; inspect this build before packaging")
    builds = data.get("builds", [])
    if len(builds) != 2 or {b.get("part") for b in builds} != PARTS:
        raise ValueError("AR report must cover both DiPO builds")
    lines = [line.split() for line in (patches / variant / "manifest").read_text().splitlines()]
    for build in builds:
        relative = Path(build["patch"])
        expected = variant + "/HKMC_DiPO." + build["part"].split("_", 1)[1] + ".ar.lpd"
        if str(relative) != expected or not isinstance(build.get("calls"), int) or build["calls"] < 1:
            raise ValueError("unexpected AR patch entry")
        if digest(patches / relative) != build.get("patch_sha256"):
            raise ValueError("AR patch fingerprint mismatch: " + expected)
        entries = [line for line in lines if len(line) == 5 and line[:3] == [build["part"], "app", "HKMC_DiPO"]]
        if entries != [[build["part"], "app", "HKMC_DiPO", expected, build["apk_md5"]]]:
            raise ValueError("AR manifest doesn't match build report")
    return data


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("patches", type=Path)
    p.add_argument("report", type=Path)
    a = p.parse_args()
    try:
        validate(a.patches, a.report)
    except (ValueError, KeyError, TypeError, OSError) as error:
        p.exit(1, str(error) + "\n")
    print("AR build fingerprints and both manifest entries verified")
