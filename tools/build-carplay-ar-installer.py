#!/usr/bin/env python3
"""Build the explicitly enabled AR installer: one car (one --patches), or one installer for
every car given (several --patches: the unified installer, which picks each car's patch set on
the car from its product id, as the base package does)."""
import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "1.3.0.126"
# Cars with an AR build: the patch set (the report's variant) -> how the installer names and gates it.
# products: the shell case pattern (and, comma-separated, the NEMESIS os= list).
CARS = {
    "ev6": {"car": "a US Kia EV6 on 260507", "short": "EV6 USA 260507", "title": "EV6 AR",
            "label": "EV6_US_260507", "products": "CVEV.USA.*.260507"},
    "ev6_ca": {"car": "a Canadian Kia EV6 on 260206", "short": "EV6 Canada 260206", "title": "EV6 Canada AR",
               "label": "EV6_CA_260206", "products": "CVEV.CAN.*.260206"},
    "neev23_260903": {"car": "a US Hyundai Ioniq 5 on 260903", "short": "Ioniq 5 USA 260903", "title": "Ioniq 5 AR",
                      "label": "IONIQ5_US_260903",
                      "products": "NEEV.USA.*.260903|NEEV23.USA.*.260903|NEEV23SE.USA.*.260903"},
}


def gated(script, car):
    """A kit script with this car's product pattern and name filled in."""
    text = script.decode()
    if "@AR_PRODUCTS@" not in text or "@AR_CAR@" not in text:
        raise ValueError("script has no product gate placeholders")
    return text.replace("@AR_PRODUCTS@", car["products"]).replace("@AR_CAR@", car["car"]).encode()


def verifier():
    spec = importlib.util.spec_from_file_location("ar_report", ROOT / "tools/check-carplay-ar-build.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def field(text, name, value):
    section = re.search(r"(?ms)^\[nemesis\]\n(.*?)(?=^\[|\Z)", text)
    if section is None:
        raise ValueError("missing nemesis descriptor section")
    result, count = re.subn(r"^" + re.escape(name) + r"=.*$", lambda _: name + "=" + value,
                            section[1], flags=re.M)
    if count != 1:
        raise ValueError("expected exactly one descriptor field: " + name)
    return text[:section.start(1)] + result + text[section.end(1):]


def archive(files, modes=None):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in files.items():
            entry = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            entry.external_attr = (0o100000 | (modes or {}).get(name, 0o644)) << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(entry, data)
    return stream.getvalue()


UNIFIED = {"car": "the Kia EV6 (US 260507, Canada 260206) and Hyundai Ioniq 5 (US 260903)", "title": "AR",
           "short": "EV6 US/CA, Ioniq 5 US", "label": "AR"}


def merge(trees, work):
    """Several cars' AR patch trees (build-carplay-ar-advanced.py outputs) as one tree plus a
    unified report. Each tree is the same base patch set with its own car's folder changed."""
    trees = [Path(t).resolve() for t in trees]
    reports = [verifier().validate(t, t.parent / "ar-build.json") for t in trees]
    variants = [d["variant"] for d in reports]
    if len(set(variants)) != len(variants) or any(v not in CARS for v in variants):
        raise ValueError("each car once, and only cars in CARS: " + ", ".join(variants))
    if any(d["enabled_by_default"] for d in reports):
        raise ValueError("start from disabled, inspected delta builds")
    def files(tree, skip):
        return {str(f.relative_to(tree)): f.read_bytes() for f in tree.rglob("*")
                if f.is_file() and f.relative_to(tree).parts[0] not in skip}
    base = files(trees[0], set(variants))
    for tree in trees[1:]:
        if files(tree, set(variants)) != base:
            raise ValueError("the AR builds were made from different base patch sets: rebuild them from one commit")
    out = work / "unified" / "patches"
    shutil.copytree(trees[0], out)
    for tree, variant in zip(trees, variants):
        shutil.rmtree(out / variant)
        shutil.copytree(tree / variant, out / variant)
    unified = {"schema": 2, "unified": True, "variant": "unified", "enabled_by_default": False,
               "variants": {d["variant"]: d for d in reports}}
    (out.parent / "ar-build.json").write_text(json.dumps(unified, indent=2) + "\n")
    verifier().validate(out, out.parent / "ar-build.json")
    car = dict(UNIFIED, products="|".join(CARS[v]["products"] for v in variants))
    return out, car, variants


def build(patches, output, enable_ar, trace=True):
    if not enable_ar:
        raise ValueError("explicit --enable-ar is required for this experimental installer")
    output = Path(output).absolute()
    keep = tempfile.TemporaryDirectory(prefix="uwp-unified-")
    if isinstance(patches, (list, tuple)) and len(patches) > 1:
        patches, car, variants = merge(patches, Path(keep.name))
        data = json.loads((patches.parent / "ar-build.json").read_text())
    else:
        patches = Path(patches[0] if isinstance(patches, (list, tuple)) else patches).resolve()
        data = verifier().validate(patches, patches.parent / "ar-build.json")
        car = CARS.get(data["variant"])
        if car is None or data["enabled_by_default"]:
            raise ValueError("start from a disabled, inspected delta build of a car in CARS")
        variants = [data["variant"]]
    source_report = patches.parent / "ar-build.json"
    if output.exists() or output.is_symlink():
        raise ValueError("output already exists; choose a new installer filename")
    with tempfile.TemporaryDirectory(prefix="evergreen-ar-installer-") as temp:
        base = Path(temp) / "base.zip"
        subprocess.run(["sh", str(ROOT / "tools/build-package.sh"), str(base)],
                       env=dict(os.environ, EVERGREEN_PATCHES_DIR=str(patches)), check=True)
        with zipfile.ZipFile(base) as outer:
            descriptor = outer.read("NEMESIS.data").decode()
            with zipfile.ZipFile(io.BytesIO(outer.read("packages/wireless.carplay.aa.zip"))) as inner:
                files = {name: inner.read(name) for name in inner.namelist() if not name.endswith("/")}
                modes = {name: (inner.getinfo(name).external_attr >> 16) & 0o777 for name in files}
        original = files["NEMESIS.data"].decode()
        original = field(original, "name", "Ultimate Wireless Projection (" + car["title"] + ")")
        original = field(original, "package-version", VERSION)
        original = field(original, "short-desc", '"Wireless CarPlay/AA with the AR HUD; ' + car["short"] + ' only"')
        original = field(original, "detailed-desc", '"Ultimate Wireless Projection: wireless CarPlay and Android Auto with navigation on the factory AR HUD, and EV routing for Android Auto. Uninstall restores stock. See README-AR.txt."')
        original = field(original, "os", '"' + car["products"].replace("|", ",") + '"')
        original = field(original, "preinstall", "scripts/ar-pre.sh")
        original = field(original, "repository", "https://github.com/KSubedi/ultimate-wireless-projection")
        if data['schema']==2:
            original=field(original,'postinstall','scripts/ar-post.sh')
        if data['schema']==2:
            original += "\n[service.2]\nname=ar-debug\nkind=sh\npath=svc/ar-debug.sh\nuid=root\nmode=restart\n"
            original += "\n[service.3]\nname=ar-baseline\nkind=sh\npath=svc/ar-baseline.sh\nuid=root\nmode=restart\n"
        # Validate the FINAL descriptor, after all experimental overrides.
        # NEMESIS permits dotted numbers only, not SemVer prerelease suffixes.
        checked_descriptor = Path(temp) / "NEMESIS.data"
        checked_descriptor.write_text(original)
        checked_cars = Path(temp) / "cars"
        checked_cars.write_text("".join(line + "\n" for line in (ROOT / "patches/cars").read_text().splitlines()
                                        if len(line.split()) > 1 and line.split()[1] in variants))
        subprocess.run(["python3", str(ROOT / "tools/check-descriptor.py"),
                        str(checked_descriptor), str(checked_cars)], check=True)
        files["NEMESIS.data"] = original.encode()
        files["scripts/ar-pre.sh"] = gated((ROOT / "kits/ar-installer/ar-pre.sh").read_bytes(), car)
        modes["scripts/ar-pre.sh"] = 0o755
        if data['schema']==2:
            files['scripts/ar-post.sh']=(ROOT/'kits/ar-installer/ar-post.sh').read_bytes()
            modes['scripts/ar-post.sh']=0o755
            files['svc/ar-debug.sh']=(ROOT/'kits/ar-installer/ar-debug.sh').read_bytes()
            modes['svc/ar-debug.sh']=0o755
            files['svc/ar-baseline.sh']=(ROOT/'kits/ar-installer/ar-baseline.sh').read_bytes()
            modes['svc/ar-baseline.sh']=0o755
            if trace:
                # Diagnostic candidates trace at navigation cadence; the trace-off control kit removes this.
                files['ar-trace.enabled']=b"Detailed EvergreenAR tracing for this diagnostic build.\n"
        files["ar-hud.enabled"] = b"Explicit opt-in by the experimental AR installer.\n"
        data = json.loads(files["AR_BUILD.json"])
        for entry in [data] + list(data.get("variants", {}).values()):
            entry["enabled_by_default"] = True
            entry["activation"] = "explicit-experimental-installer"
            entry["trace_enabled"] = bool(trace and entry['schema']==2)
        files["AR_BUILD.json"] = (json.dumps(data, indent=2)+"\n").encode()
        files["AR_INSTALL.json"] = (json.dumps({"version": VERSION, "product": car["products"],
            "enable_requested": True, "source_build_report_sha256": hashlib.sha256(source_report.read_bytes()).hexdigest(),
            "on_car_tested": False}, indent=2)+"\n").encode()
        readme = (ROOT / "kits/ar-installer/README.txt").read_bytes()
        if data['schema']==1:
            readme=b"BASIC-ONLY PACKAGE: the advanced adapters described below are not wired in this schema-1 build.\n\n"+readme
        files["README-AR.txt"] = readme
        # The normal verifier remains usable on the deployment report too.
        report = Path(temp) / "deployment-report.json"
        report.write_bytes(files["AR_BUILD.json"])
        verifier().validate(patches, report)
        # One drive: kits/ar-combo installs this version when it isn't installed, and collects
        # the logs once it is (NEMESIS runs its script; the package itself is unchanged).
        combo = ROOT / "kits/ar-combo"
        if "path=packages/wireless.carplay.aa.zip" not in descriptor:
            raise ValueError("unexpected base install descriptor")
        outer_files = {"NEMESIS.data": (combo / "NEMESIS.data").read_bytes(), "NEMESIS.sh": gated((combo / "NEMESIS.sh").read_bytes(), car),
                       "AR_VERSION": (VERSION + "\n").encode(), "hotspot.sh": (ROOT / "kits/ar-control/NEMESIS.sh").read_bytes(),
                       "packages/wireless.carplay.aa.zip": archive(files, modes), "README-AR.txt": readme}
        output.parent.mkdir(parents=True, exist_ok=True)
        # Only publish a finished zip; never clobber an earlier installer.
        with output.open("xb") as stream:
            stream.write(archive(outer_files, {"NEMESIS.sh": 0o755, "hotspot.sh": 0o755}))
    keep.cleanup()
    print(str(output))
    return data


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--patches", type=Path, action="append", required=True,
                   help="a car's AR patch tree; give several for the unified installer")
    p.add_argument("--out", type=Path, help="default: dist/UltimateWirelessProjection_<car label>_<version>.zip")
    p.add_argument("--enable-ar", action="store_true")
    p.add_argument("--no-trace", action="store_true", help="omit ar-trace.enabled (release candidates)")
    a = p.parse_args()
    try:
        out = a.out
        if out is None and len(a.patches) > 1:
            out = ROOT / "dist" / ("UltimateWirelessProjection_" + VERSION + ".zip")
        elif out is None:
            report = json.loads((a.patches[0].resolve().parent / "ar-build.json").read_text())
            car = CARS.get(report.get("variant"))
            if car is None:
                p.exit(1, "no installer name for variant %r\n" % report.get("variant"))
            out = ROOT / "dist" / ("UltimateWirelessProjection_" + car["label"] + "_" + VERSION + ".zip")
        build(a.patches, out, a.enable_ar, not a.no_trace)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        p.exit(1, str(error)+"\n")
