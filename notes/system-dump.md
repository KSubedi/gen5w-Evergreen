# Resumable system partition collector

Build and download separately from Evergreen's wireless/AR packages. It does
not change installed firmware, remount `/system`, or install a boot service.

The kit captures the complete **as-installed `/system` ext4 partition**, not
the whole eMMC device. User data, telematics/security partitions, bootloader,
MICOM/modem firmware and maps are outside its scope. Full image contents,
including proprietary code and any firmware keys, must remain private. Existing
Evergreen modifications are present in the image; use the separate AR stock
collector to retrieve the original app/library backups too.

The packaged [instructions](../kits/system-dump/README.txt) cover USB preparation,
keeping the EV powered, progress, stopping/resuming and computer-side extraction.
The launch dialog only confirms startup. `SYSTEM-DUMP/STATUS.txt` and `COMPLETE`
indicate collection results; a best-effort OEM toast also announces the outcome.

## Implementation

- A static ARMv7 Linux/musl executable, independent of Android Java/runtime libraries.
- A single 256 KiB payload buffer; SHA-256 runs incrementally, without compression.
- 64 MiB chunks below FAT32's file-size limit, synced before read-back verification.
- A SHA-256 receipt per committed chunk; partial chunks are rewritten on resume.
- Resume validates both saved data and the current source. Changed images are
  rejected rather than silently combining firmware versions.
- Free space checked before every new chunk for the entire remaining image
  plus 256 MiB. Bulk data goes directly to the USB; only the small executable
  and a global process lock are staged under `/data/nemesis/system-dump`.
- Source discovery accepts only the mounted, read-only ext4 `/system` block
  device. No hardcoded partition number or arbitrary device argument.
- Double-fork/`setsid`, detached standard streams and no overall collection
  deadline. The global kernel file lock prevents simultaneous collectors and
  disappears automatically when all worker descriptors close, including on reboot.
- SIGINT/SIGTERM and a USB `STOP` file pause between bounded I/O operations.
- CPU nice level 10, best-effort source cache eviction, periodic status updates,
  and a renewable 60-second kernel wake-lock lease (released on normal exit).

NEMESIS's current `run_script` waits for a script without a collection timeout;
the worker detaches so the handler/dialog lifetime does not control the dump.
This was checked in the local source of
[xwtk/hmg_nemesis](https://github.com/xwtk/hmg_nemesis), `native/util.c` and
`native/usb.c`. Kernel wake-lock timeout units and behavior follow the
[Linux sysfs power ABI](https://www.kernel.org/doc/Documentation/ABI/testing/sysfs-power).

No software can guarantee continued vehicle power, interrupt a stuck kernel
I/O request, or force a failing USB controller to persist writes. The car's
automatic shutoff timer can override the kernel wake lock. Use a reliable USB,
keep the car attended in READY, and verify the archived chunks on a computer.

## Build and validation

Use Zig **0.14.1** (official download and SHA-256 pinned in CI), and Python 3.9+:

```sh
ZIG=/path/to/zig sh src/system-dump/build.sh
CC='/path/to/zig cc' python3 tools/test-system-dump.py
python3 tools/build-system-dump-collector.py
```

The builder rejects the wrong ELF architecture or a dynamic interpreter. The
zip contains only our helper, checksums, launch descriptor/script, instructions
and the host verifier. No manufacturer files or collected dump enters Git.

Synthetic tests cover image/checksum round trips, resume, process locking,
SIGTERM/STOP, missing space, corrupted media, changed source/product, interrupted
commit recovery, detached startup, path links, host overwrite protection and
SHA-256 padding boundaries. A sparse image larger than a 24 MiB address-space
limit verifies that allocation remains bounded. CI also exercises actual ARM
startup, synthetic image capture and resume under qemu. These are host/emulator
tests; **real
head-unit USB, block-read permissions, wake locks and notification behavior
remain untested on a car**.

The host verifier uses the same bounded buffer size and Python's independent
SHA-256 implementation. `--assemble` produces a full ext4 partition image while
verifying, checks destination space, and refuses existing images/partials.
`debugfs` can inspect/extract the image without writable mounting; this is an
analysis archive, not a supported flashing or disaster-recovery process.
