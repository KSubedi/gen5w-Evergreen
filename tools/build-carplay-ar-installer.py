#!/usr/bin/env python3
"""Build the explicitly enabled, EV6-260507-only experimental NEMESIS update."""
import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "1.3.0.116"


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


def build(patches, output, enable_ar, trace=True):
    if not enable_ar:
        raise ValueError("explicit --enable-ar is required for this experimental installer")
    patches, output = Path(patches).resolve(), Path(output).absolute()
    source_report = patches.parent / "ar-build.json"
    data = verifier().validate(patches, source_report)
    if data["variant"] != "ev6" or data["enabled_by_default"]:
        raise ValueError("start from a disabled, inspected US EV6 delta build")
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
        original = field(original, "name", "Evergreen - CarPlay AR HUD (experimental)")
        original = field(original, "package-version", VERSION)
        original = field(original, "short-desc", '"Experimental CarPlay AR HUD; EV6 USA 260507 only"')
        original = field(original, "detailed-desc", '"Evergreen wireless projection with experimental CarPlay native AR turns. Opted in by this installer. Uninstall restores stock. See README-AR.txt."')
        original = field(original, "os", '"CVEV.USA.*.260507"')
        original = field(original, "preinstall", "scripts/ar-pre.sh")
        original = field(original, "repository", "https://github.com/KSubedi/gen5w-Evergreen")
        if data['schema']==2:
            original=field(original,'postinstall','scripts/ar-post.sh')
            original=field(original,'short-desc','"Experimental smooth CarPlay AR; EV6 USA 260507 only"')
        if data['schema']==2:
            original += "\n[service.2]\nname=ar-debug\nkind=sh\npath=svc/ar-debug.sh\nuid=root\nmode=restart\n"
            original += "\n[service.3]\nname=ar-baseline\nkind=sh\npath=svc/ar-baseline.sh\nuid=root\nmode=restart\n"
        # Validate the FINAL descriptor, after all experimental overrides.
        # NEMESIS permits dotted numbers only, not SemVer prerelease suffixes.
        checked_descriptor = Path(temp) / "NEMESIS.data"
        checked_descriptor.write_text(original)
        checked_cars = Path(temp) / "cars"
        checked_cars.write_text("CVEV.USA. ev6 Kia EV6 (US)\n")
        subprocess.run(["python3", str(ROOT / "tools/check-descriptor.py"),
                        str(checked_descriptor), str(checked_cars)], check=True)
        files["NEMESIS.data"] = original.encode()
        files["scripts/ar-pre.sh"] = (ROOT / "kits/ar-installer/ar-pre.sh").read_bytes()
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
        data["enabled_by_default"] = True
        data["activation"] = "explicit-experimental-installer"
        data["trace_enabled"] = bool(trace and data['schema']==2)
        files["AR_BUILD.json"] = (json.dumps(data, indent=2)+"\n").encode()
        files["AR_INSTALL.json"] = (json.dumps({"version": VERSION, "product": "CVEV.USA.*.260507",
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
        outer_files = {"NEMESIS.data": (combo / "NEMESIS.data").read_bytes(), "NEMESIS.sh": (combo / "NEMESIS.sh").read_bytes(),
                       "AR_VERSION": (VERSION + "\n").encode(), "hotspot.sh": (ROOT / "kits/ar-control/NEMESIS.sh").read_bytes(),
                       "packages/wireless.carplay.aa.zip": archive(files, modes), "README-AR.txt": readme}
        output.parent.mkdir(parents=True, exist_ok=True)
        # Only publish a finished zip; never clobber an earlier installer.
        with output.open("xb") as stream:
            stream.write(archive(outer_files, {"NEMESIS.sh": 0o755, "hotspot.sh": 0o755}))
    print(str(output))
    return data


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--patches", type=Path, required=True)
    p.add_argument("--out", type=Path, default=ROOT / "dist" / ("Evergreen_EV6_US_260507_AR_"+VERSION+".zip"))
    p.add_argument("--enable-ar", action="store_true")
    p.add_argument("--no-trace", action="store_true", help="omit ar-trace.enabled (release candidates)")
    a = p.parse_args()
    try:
        build(a.patches, a.out, a.enable_ar, not a.no_trace)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        p.exit(1, str(error)+"\n")
