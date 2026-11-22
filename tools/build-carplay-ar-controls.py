#!/usr/bin/env python3
"""Build USB controls for a generated AR prototype: flags, tracing, tuning, baseline."""
import argparse
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODES = ("enable", "disable", "status", "basic", "advanced", "diagnostics",
         "trace-on", "trace-off", "tune-reset", "baseline", "hotspot")
RANGES = {"street_ar_meters": (30, 457), "gain": (0.5, 1.0), "backstep_meters": (0, 50),
          "bar_meters": (30, 457), "turn_offset_meters": (0, 60), "guidance_restart_s": (0, 600)}
EXIT_SIDES = ("auto", "right", "left", "generic")
EXIT_STYLES = ("slight", "sign")
RECOVER = ("off", "keyframe", "screen", "reconnect")
README = ("Unzip to an empty USB drive and run through NEMESIS while parked.\n"
          "Controls change AR flags, tracing, road-test tuning or a baseline request only.\n"
          "Diagnostics exports bounded EvergreenAR logs, any cluster baseline and build metadata to AR-DEBUG/.\n"
          "Hotspot writes HOTSPOT/hotspot.txt (the hotspot's channel settings and history; no network name,\n"
          "password or hardware address) and works with any Evergreen build.\n"
          "No /system writes, no reboot. Run diagnostics parked after a drive. Wait for Export complete, then zip AR-DEBUG/ and share privately.\n"
          "Enable/status require a generated US EV6 260507 AR build; disable works even without one.\n"
          "The normal Evergreen package has no AR prototype to enable.\n")


def tuning(values, exit_side=None, exit_style=None, recover=None, android_auto=None):
    lines = []
    for key, value in values.items():
        low, high = RANGES[key]
        if value is None:
            continue
        if not low <= value <= high:
            raise ValueError("%s must be between %s and %s" % (key, low, high))
        lines.append("%s=%g" % (key, value))
    if exit_side is not None:
        if exit_side not in EXIT_SIDES:
            raise ValueError("exit_side must be one of " + ", ".join(EXIT_SIDES))
        lines.append("exit_side=" + exit_side)
    if exit_style is not None:
        if exit_style not in EXIT_STYLES:
            raise ValueError("exit_style must be one of " + ", ".join(EXIT_STYLES))
        lines.append("exit_style=" + exit_style)
    if recover is not None:
        if recover not in RECOVER:
            raise ValueError("recover must be one of " + ", ".join(RECOVER))
        lines.append("recover=" + recover)
    if android_auto is not None:
        if android_auto not in ("on", "off"):
            raise ValueError("android_auto must be on or off")
        lines.append("android_auto=" + android_auto)
    if values.get("guidance_restart_s") not in (None, 0) and values["guidance_restart_s"] < 60:
        raise ValueError("guidance_restart_s must be 0 (off) or between 60 and 600")
    if not lines:
        raise ValueError("no tuning values given")
    return "\n".join(lines) + "\n"


def kit(output, mode, extra=None):
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name in ("NEMESIS.data", "NEMESIS.sh"):
            archive.write(ROOT / "kits/ar-control" / name, name)
        archive.writestr("AR_MODE", mode + "\n")
        for name, data in (extra or {}).items():
            archive.writestr(name, data)
        archive.writestr("README.txt", README)
    print(output)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("directory", nargs="?", type=Path, default=ROOT / "dist")
    p.add_argument("--street-ar-meters", type=float, help="large street-turn AR starts inside this distance (30-457)")
    p.add_argument("--gain", type=float, help="fraction of the closing rate used between phone samples (0.5-1)")
    p.add_argument("--backstep-meters", type=float, help="hold instead of stepping back for increases up to this (0-50)")
    p.add_argument("--bar-meters", type=float, help="window over which the street-turn bar runs 90 to 10 (30-457; stock 457)")
    p.add_argument("--turn-offset-meters", type=float, help="added to the large AR arrow's distance only (0-60)")
    p.add_argument("--exit-side", choices=EXIT_SIDES, help="side for the phone's direction-unspecified exit (raw 8)")
    p.add_argument("--exit-style", choices=EXIT_STYLES, help="exits as a slight-turn arrow or the native exit sign (default)")
    p.add_argument("--recover", choices=RECOVER, help="stalled CarPlay picture: off, key frame, + screen return (default), + reconnect")
    p.add_argument("--guidance-restart-s", type=float, help="restart silent phone guidance after this many seconds (60-600, 0 off; default 90)")
    p.add_argument("--android-auto", choices=("on", "off"), help="Android Auto turns as AR (on, default) or GPM's icons only")
    p.add_argument("--tune-name", help="suffix for the tuning kit file name: Evergreen-AR-tune-<name>.zip")
    p.add_argument("--baseline-minutes", type=int, default=20, help="stock-navigation cluster capture length (1-60)")
    a = p.parse_args()
    a.directory.mkdir(parents=True, exist_ok=True)
    if not 1 <= a.baseline_minutes <= 60:
        p.exit(1, "baseline minutes must be between 1 and 60\n")
    for mode in MODES:
        extra = {"AR_BASELINE_MINUTES": "%d\n" % a.baseline_minutes} if mode == "baseline" else None
        kit(a.directory / ("Evergreen-AR-" + mode + ".zip"), mode, extra)
    values = {"street_ar_meters": a.street_ar_meters, "gain": a.gain, "backstep_meters": a.backstep_meters,
              "bar_meters": a.bar_meters, "turn_offset_meters": a.turn_offset_meters,
              "guidance_restart_s": a.guidance_restart_s}
    if any(v is not None for v in values.values()) or a.exit_side is not None or a.exit_style is not None or a.recover is not None or a.android_auto is not None:
        try:
            text = tuning(values, a.exit_side, a.exit_style, a.recover, a.android_auto)
        except ValueError as error:
            p.exit(1, str(error) + "\n")
        name = "Evergreen-AR-tune" + ("-" + a.tune_name if a.tune_name else "") + ".zip"
        kit(a.directory / name, "tune", {"AR_TUNING": text})


if __name__ == "__main__":
    main()
