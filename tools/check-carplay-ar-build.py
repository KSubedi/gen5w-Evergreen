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
    if data.get("schema") not in (1,2) or not isinstance(variant, str) or Path(variant).name != variant or variant in (".", ".."):
        raise ValueError("unsupported AR build report")
    enabled = data.get("enabled_by_default")
    if (data.get("on_car_tested") is not False or type(enabled) is not bool
            or (enabled and data.get("activation") != "explicit-experimental-installer")):
        raise ValueError("prototype report must explicitly be untested and opted in when enabled")
    features = data.get("features", {})
    advanced = data['schema']==2
    wanted_features = {"icon_ar": True, "metadata_adapter": advanced, "distance_adapter": advanced, "progress_adapter": advanced}
    if advanced: wanted_features.update({"carplay_recovery": True, "android_auto_ar": True})
    if features != wanted_features:
        raise ValueError("unknown AR adapter capabilities; inspect this build before packaging")
    builds = data.get("builds", [])
    wanted={(part,'HKMC_DiPO') for part in PARTS}
    if advanced:
        wanted.add(('2_tbt','HKMC_Navi_ExSL'));wanted.add(('2_tbt','HKMC_GPM'))
        # The EV build of GPM, where the car has one, needs its AR build too.
        if any(l.split()[:3]==['2_tbt+ev','app','HKMC_GPM'] for l in (patches/variant/'manifest').read_text().splitlines()):
            wanted.add(('2_tbt+ev','HKMC_GPM'))
        api=data.get('framework_api',{})
        # Cars whose framework trap API was inspected: the EV6 US 260507, the EV6 Canada 260206 and
        # the Ioniq 5 US 260903 (the same API: bar index 11, the same trap fields and hashes).
        if variant not in ('ev6','ev6_ca','neev23_260903') or api.get('bar_index')!=11 or not data.get('framework_sha256'):
            raise ValueError('advanced AR requires a verified framework API')
    if len(builds)!=len(wanted) or {(b.get('part'),b.get('name','HKMC_DiPO')) for b in builds}!=wanted:
        raise ValueError("AR report must cover every required DiPO/navigation/Android Auto build")
    lines = [line.split() for line in (patches / variant / "manifest").read_text().splitlines()]
    for build in builds:
        relative = Path(build["patch"])
        name=build.get('name','HKMC_DiPO')
        suffix = "ev" if build["part"] == "2_tbt+ev" else build["part"].split("_", 1)[1]
        expected = variant + "/"+name+"." + suffix + (".advanced-ar.lpd" if advanced else ".ar.lpd")
        if str(relative) != expected or not isinstance(build.get("calls"), int) or build["calls"] < 1:
            raise ValueError("unexpected AR patch entry")
        if digest(patches / relative) != build.get("patch_sha256"):
            raise ValueError("AR patch fingerprint mismatch: " + expected)
        entries = [line for line in lines if len(line) == 5 and line[:3] == [build["part"], "app", name]]
        if entries != [[build["part"], "app", name, expected, build["apk_md5"]]]:
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
