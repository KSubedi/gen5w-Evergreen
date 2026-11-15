#!/usr/bin/env python3
"""Build the stock-only USB collector, with the exact US EV6 checksums."""
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
out = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "dist/EV6-260507-stock-collector.zip"
out.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
    for name in ("NEMESIS.data", "NEMESIS.sh"):
        archive.write(ROOT / "kits/ar-stock" / name, name)
    archive.writestr("expected-stock.txt", (ROOT / "patches/ev6/stock").read_text()
                     + (ROOT / "kits/ar-stock/extra-stock.txt").read_text())
    archive.writestr("README.txt", """2022-24 US EV6, firmware 260507. Requires NEMESIS.
Unzip these files onto an empty USB drive, keeping NEMESIS.data at its root.
With the car parked and head unit running, insert the drive and run the script
when NEMESIS prompts. After it completes, remove the drive.
Share the new EV6-AR-STOCK folder privately. Do not post OEM files to GitHub.
The collector reads only firmware apps/libraries and framework code. It does
not read keys, logs, accounts, phone data or vehicle security partitions.
It writes only to the USB drive. Existing matching Evergreen stock backups
are used when the installed app has already been modified. Missing exact
stock files are listed in report.txt; they are never guessed or substituted.
This kit does not install the AR prototype or change the car.
""")
print(out)
