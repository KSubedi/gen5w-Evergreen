Experimental CarPlay AR HUD for **Kia EV6 GT-Line, USA, Gen5W firmware 260507**, with the factory AR HUD. This is a NEMESIS application update, not a firmware flash. Other models, regions and firmware are not supported by this installer.

<!-- ar-demo:start -->
## Demo video

[Watch or download the EV6 CarPlay AR video](https://github.com/KSubedi/gen5w-Evergreen/releases/download/v1.2.0.103-beta.1/EV6-CarPlay-AR-demo.mov). Mirrored as a release asset so it remains available independently of the Discord link.
<!-- ar-demo:end -->

## Download

Install **Evergreen_EV6_US_260507_AR_1.2.0.103.zip** from the assets below. The other ZIPs are optional controls, not the installer. This prerelease keeps the existing `wireless.carplay.aa` package identity and enables AR and advanced features.

## What changed

- Converts supported CarPlay maneuvers to native AR turns near the maneuver.
- Uses exact maneuver distance with bounded 250 ms interpolation and a progress overlay that preserves OEM speed limits and warnings.
- **1.2.0.103 refreshes guidance when maneuver details arrive**, addressing a callback-order gap that could leave the first turn blank or delay the small HUD arrow until another route update.
- Adds a host replay harness and limited numeric AR diagnostics.

## Install / update

1. Have **NEMESIS 1.16 or newer** installed. Save your current working Evergreen installer for rollback. Disable an HMG-API wireless mod first if one is installed; both modify the same apps.
2. Extract **Evergreen_EV6_US_260507_AR_1.2.0.103.zip** onto the root of an empty FAT32 or exFAT USB drive that the head unit reads. The root must contain `NEMESIS.data` and `packages/`. Do not place another NEMESIS kit on the same drive.
3. Park safely and keep the car in **READY**. Insert USB and approve the Evergreen update. Existing Evergreen users on NEMESIS 1.16+ can update in place; do not manually remove the backups. Coming from original Leafia 1.0.5 or older: uninstall it in NEMESIS Manager and restart first.
4. Let installation complete. Remove USB when the head unit restarts, as the installer instructs. Do not interrupt an active installation.
5. Reconnect CarPlay, select **AR navigation mode** in the car's HUD settings, and start a fresh route. AR appears near supported maneuvers, within approximately **457 m / 1,500 ft**. Check initial behavior while parked; a passenger can observe during normal driving.

## Controls and rollback

Extract **one** control ZIP at a time onto a separate empty USB drive and run through NEMESIS while parked. Start a fresh route after changing modes.

- **Evergreen-AR-basic.zip:** keep basic AR, disable advanced distance/metadata/progress and the new details refresh.
- **Evergreen-AR-advanced.zip:** restore advanced features.
- **Evergreen-AR-disable.zip / Evergreen-AR-enable.zip:** turn AR off/on.
- **Evergreen-AR-status.zip:** show the selected mode.
- To roll back fully, reinstall your saved working Evergreen version through NEMESIS. To restore stock, uninstall Evergreen in NEMESIS Manager and restart; the installer retains checksum-gated backups.

## Known issues and diagnostics

- **Missing highway-exit AR remains under investigation.** Exit mappings and the activation distance are unchanged. This release does not claim to fix every exit or late-arrow case.
- The owner tested **1.2.0.102** for three hours of driving with generally good results. The **1.2.0.103 fix has not yet been car-tested**.
- Occasional CarPlay freezes were reported before AR was added. Their cause is unresolved; this update does not claim to fix them.
- Progress needs the native navigation app's first real speed-limit/trap frame. It never invents a speed limit. Interpolation stops after 1.5 seconds and holds until fresh data.

If highway AR is missing, park and run **Evergreen-AR-diagnostics.zip before restarting the head unit**. Send `AR-DIAGNOSTICS.txt` from USB. It exports only the `EvergreenAR` log tag; conversion records contain numeric maneuver codes, distance and conversion result, with no road names, coordinates or phone identifiers. The log buffer may rotate, so an empty file does not prove the feature was inactive. This kit does not collect general CarPlay crash logs.

## Validation

1,036 Java assertions and 28 Python integration cases passed, including state replay, patch injection, delta round trips and numeric NEMESIS metadata. The tested app payloads passed mock install/uninstall with Android up and down; uninstall restored the tested files byte for byte. The mock uses real matching stock apps/framework plus synthetic configuration fixtures and a stubbed dexopt; it cannot verify Dalvik behavior or physical HUD rendering. GitHub Actions runs checks before publication and verifies the compiled helper hashes against the tested delta snapshot.

The repository and release contain our code, hashes and delta patches, **no complete manufacturer apps or private vehicle dumps**. The installer rebuilds apps from the car's own checksum-matched stock files.

Verify downloads with `sha256sum -c SHA256SUMS` (macOS: `shasum -a 256 -c SHA256SUMS`). Source and replay documentation: [CarPlay AR notes](https://github.com/KSubedi/gen5w-Evergreen/blob/main/notes/carplay-ar.md).
