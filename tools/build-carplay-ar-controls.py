#!/usr/bin/env python3
"""Build enable/disable/status USB controls for a generated AR prototype."""
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
directory = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "dist"
directory.mkdir(parents=True, exist_ok=True)
for mode in ("enable", "disable", "status", "basic", "advanced"):
    output = directory / ("Evergreen-AR-" + mode + ".zip")
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name in ("NEMESIS.data", "NEMESIS.sh"):
            archive.write(ROOT / "kits/ar-control" / name, name)
        archive.writestr("AR_MODE", mode + "\n")
        archive.writestr("README.txt", "Unzip to an empty USB drive and run through NEMESIS while parked.\n"
                         "This changes only the installed AR prototype's enable flag. No /system writes, no reboot.\n"
                         "Enable/status require a generated US EV6 260507 AR build; disable works even without one.\n"
                         "The normal Evergreen package has no AR prototype to enable.\n")
    print(output)
