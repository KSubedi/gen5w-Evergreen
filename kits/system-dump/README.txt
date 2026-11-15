EVERGREEN SYSTEM PARTITION DUMP COLLECTOR (experimental; no car test yet)

WHAT IT CAPTURES
A complete, decrypted, as-installed ext4 /system partition image, including
filesystem metadata and unused blocks. Installed modifications remain present.
This is not necessarily original stock and is not a whole-device backup.
It does not collect /data, maps, boot/recovery, modem, MICOM, telematics/security
partitions, or other disks. Do not use this image as a flashing/recovery recipe.
Firmware can contain proprietary code and model-level keys: keep the whole
collection private. Do not post it to GitHub or a public file service.

BEFORE STARTING
1. Use a reliable USB stick of at least 16 GB, with more free space than the
   entire system partition plus 256 MiB. A 32 GB stick gives useful margin.
   Use a filesystem/partition layout that this head unit already reads. FAT32
   works with the 64 MiB chunks; exFAT support varies by head unit.
2. Unzip this kit directly onto an otherwise empty USB drive. Do not put other
   NEMESIS kit descriptors on the same drive. Keep verify-dump.py for your PC.
3. Park the car, put the EV in READY with enough traction-battery charge, and
   keep it attended. Disable an automatic vehicle shutoff timer if applicable.
   Accessory mode can time out or drain the 12 V battery. A software wake lock
   cannot prevent the vehicle from cutting head-unit power.
4. NEMESIS must already be installed. The collector requires a read-only ext4
   /system mount and refuses a writable one. It does not remount anything.

RUNNING
Insert the USB and approve execution in NEMESIS. The initial success dialog
means STARTED, not finished. The detached worker has no overall time limit and
is independent of the dialog. Do not start firmware updates or install/remove
mods during capture. Leave the USB and head unit powered throughout.

All bulk output is USB/SYSTEM-DUMP/. A small static helper and process lock
remain under /data/nemesis/system-dump/ (no firmware or dump is staged there).
There is no reboot hook or service installed. Run the kit again after a restart
to resume. It will not automatically run while Android is down.

Watch SYSTEM-DUMP/STATUS.txt, using an existing ADB connection if desired:
  adb shell "cat /storage/usb0/SYSTEM-DUMP/STATUS.txt"
The mount may instead be usb1 or usb2. collector.log records errors and chunk
completion. A best-effort head-unit toast announces completion/pause/error;
STATUS.txt and COMPLETE are authoritative if the toast is missed or unsupported.
STATUS.txt includes phase, progress, elapsed time and wake-lock
availability. Re-running the kit while active refuses a duplicate worker.

Runtime depends on partition size and USB speed; hashing and read-back add I/O.
Allow 15-30 minutes initially, and longer for slow media; there is no deadline.
The worker uses a 256 KiB payload buffer, no compression and no full-image RAM
allocation, runs at reduced CPU priority, checks space before each new chunk,
and reserves 256 MiB. File sync and best-effort cache eviction precede checksum
read-back. USB/controller caches and sudden power loss cannot be fully guarded.
A renewable 60-second kernel wake lock is attempted, but may be unavailable.

STOP / INTERRUPTION / RESUME
To pause with ADB (adjust mount path):
  adb shell "touch /storage/usb0/SYSTEM-DUMP/STOP"
Wait for state=PAUSED in STATUS.txt. Remove STOP to allow the next run:
  adb shell "rm /storage/usb0/SYSTEM-DUMP/STOP"
Then rerun the USB kit. STOP is polled between bounded I/O operations; a kernel
I/O hang can still delay it. Do not unplug as a routine way to stop.

After an unexpected restart, reconnect the same USB and rerun. Committed chunks
are checked against their SHA-256 receipts AND the current source. A partial
chunk is rewritten; a changed source or corrupted committed chunk is rejected.
Keep the errors for diagnosis. Use a fresh output folder/USB for changed
firmware. For NEEDS_SPACE, free unrelated USB files and rerun. Do not remove
committed chunks to make space. If STATUS.txt says RUNNING after a power loss,
that file is stale; the actual process lock determines whether a worker exists.

FINISH / ARCHIVE / EXTRACT
Wait for state=COMPLETE and the COMPLETE file, then safely eject the USB. Copy
the ENTIRE SYSTEM-DUMP directory to a private computer folder. Keep the small
verifier from this kit alongside it. On a computer with Python 3.9+:
  python3 verify-dump.py /private/SYSTEM-DUMP
To verify AND assemble a single system.img (needs additional image-sized space):
  python3 verify-dump.py /private/SYSTEM-DUMP --assemble /private/system.img
The tool streams data and prints a SHA-256 for the complete image. It never
overwrites an existing image or .part file. Assemble on a filesystem supporting
files above 4 GB. Keep the original chunks and receipts as your archival copy.

The resulting file is an ext4 partition image, not an Android sparse image.
On Linux, debugfs from e2fsprogs can inspect/extract it without mounting or root:
  debugfs -R 'ls -l /' /private/system.img
  mkdir /private/extracted-system
  debugfs -R 'rdump / /private/extracted-system' /private/system.img
Do not use debugfs -w. macOS/Windows can use a Linux VM with e2fsprogs.
The AR stock-only collector remains useful: it recovers original app/library
backups that a raw, currently modified /system image does not include.
