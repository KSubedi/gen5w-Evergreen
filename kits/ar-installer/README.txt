EVERGREEN 1.2.0.105 -- EXPERIMENTAL ADVANCED CARPLAY AR HUD
Target: Kia EV6 USA, head-unit firmware 260507, factory AR HUD.
This is a NEMESIS application update, not a firmware/bootloader flash.
The owner tested 1.2.0.102 for three hours of driving with generally good results.
This diagnostic build adds exit recovery, maneuver replacement clearing and
closer street-turn AR. These changes need their own car test.
NEMESIS package versions remain numeric.

WHAT THIS BUILD DOES
Handles the observed code-8 maneuver using a generic exit cue, including its
small-HUD instruction and distance. It does not invent left/right geometry
when the phone supplies a direction-unspecified maneuver. Explicit left/right
exit metadata still uses directional conversion. Deferred street AR is now
identified as deferred in the logs, rather than mislabeled unmapped.
Refreshes the existing main-turn and small-HUD paths after maneuver details
arrive, without waiting for the next distance/route-state update. Missing
current-maneuver data and stopped routes do not produce a synthetic turn.
Keeps Evergreen's wireless CarPlay/Android Auto and audio implementation.
Large ordinary street-turn AR begins within 125 m (about 410 ft). Small
direction cues remain available earlier. Highway/fork/roundabout native
conversion retains its 457 m (1,500 ft) window; far exit cues use projection icons.
The advanced build uses the phone's exact maneuver distance and raw turn
metadata. It estimates the closing rate from consecutive exact distances and
updates at 250 ms intervals for at most 1.5 seconds after a sample, then holds.
It does not guess time-to-turn or extrapolate through stale route changes.
Invalid/missing metadata falls back to the working icon-based AR behavior.

The progress overlay changes only the bar in the navigation app's latest OEM
trap frame. Speed limits, units, camera and warning fields remain the OEM's.
It waits for that app's first real trap frame (often a map speed-limit update);
it never constructs a speed limit from phone data or the cluster's delayed echo.
The lease expires on stale data, across boots, or when guidance/AR is disabled.
Both AR and advanced features are enabled by this selected experimental installer.
Unknown/far/native turns pass through. The HUD must have AR mode selected.

INSTALL
1. Save the working Evergreen installer so you can reinstall it if needed.
2. Extract this zip to the root of a USB drive the car already reads. Put no
   other NEMESIS kit on that drive. It should contain NEMESIS.data and packages/.
3. Park in a safe place, keep the car in READY, and insert USB. NEMESIS must
   already be installed. Approve installation/update of Evergreen CarPlay AR.
   It replaces the same wireless.carplay.aa package; NEMESIS runs the previous
   version's uninstall first. Do not separately remove working backups.
4. Wait for the install and head-unit restart. Remove USB while it restarts,
   as Evergreen's install message instructs. Do not interrupt an active install.
5. Select the car's AR HUD navigation mode, reconnect CarPlay, and start a new
   navigation route. Native-format arrows are expected near supported maneuvers,
   not continuously along the road or at all distances. Evaluate while parked
   first; a passenger should observe/report behavior during a normal drive.

TURN AR OFF / ROLLBACK
For a quick return to BASIC AR while keeping the new package installed, use
Evergreen-AR-basic.zip on a separate USB. It disables the new interpolation,
metadata and progress overlay, while retaining the confirmed icon-based AR.
Start a fresh route after switching. Evergreen-AR-advanced.zip restores advanced
mode when the new adapters are installed. If AR was globally disabled, use the
enable kit too. The previous 1.2.0.101 installer remains a full rollback option.

Use the separately supplied Evergreen-AR-disable.zip kit on an otherwise empty
USB. It removes only the AR flag and keeps wireless projection installed. Start
a fresh navigation route after toggling. Evergreen-AR-enable.zip re-enables it.
With an existing ADB connection the equivalent disable command is:
  adb shell "nemesis root -c 'rm -f /data/nemesis/apps/wireless.carplay.aa/ar-hud.enabled'"

To restore your previous Evergreen build, install its known-working zip through
NEMESIS as an update. To return to stock, uninstall wireless.carplay.aa through
NEMESIS; its original backups are restored. If an install reports failed rollback
or says not to reboot, follow that message and collect the NEMESIS logs first.

TEST FEEDBACK
Report the navigation app, maneuver and approximate distance, whether the HUD
was in AR mode, and whether AR-disable returns the expected original behavior.
NEMESIS Manager's Logs > Copy to USB includes the package install logs. Avoid
publishing full stock firmware or private phone/route logs.
New helper failures are logged with tag EvergreenAR, without road names,
coordinates or phone identifiers.

DIAGNOSTIC DRIVE -- NO USB NEEDED WHILE DRIVING
Logging starts automatically after installation and the head-unit restart.
A root NEMESIS service captures only our numeric EvergreenAR navigation tag,
with up to thirty-two 1 MiB files (about 32 MiB total). Log writes are streamed by
logcat, not buffered in Java memory. Routine events are capped at 50/second; critical transitions have a separate
100/second budget. Dropped counts are recorded. Detailed logging is enabled for this diagnostic build.

It covers route/maneuver callbacks, parsed exact/display distance receipts,
current maneuver/state, missing metadata and icon recovery, replacement clears,
main cluster calls, small HUD calls/distance, progress IPC and OEM bar overlay,
resets and worker heartbeat. API return is not physical HUD acknowledgement.
It does not capture raw CarPlay video, arbitrary phone packets, Wi-Fi passwords,
road names, coordinates, phone identifiers or general app logs.

AFTER THE DRIVE
1. Park in READY. Extract the NEW Evergreen-AR-export-stop-clean.zip onto a
   separate empty USB. This version supports both 104 and 105.
2. Insert it and let NEMESIS run the export. Wait for "Export and cleanup complete" before
   removing USB. The kit flushes writes before showing completion.
3. Zip the AR-DEBUG-FINISHED folder and send it privately, using an upload link if it is
   too large for chat. Keep VERIFIED.txt and FINISHED.txt too. STATUS.txt
   show whether persistent recording was available. Old files rotate; export
   soon after the issue. Logs normally survive restart, but recent logcat may not.
4. Tell us the approximate time, what Apple Maps showed versus HUD, left/right
   exit and whether switching out of CarPlay and back changed it. No interaction
   with diagnostics or observation tasks are needed from the driver in motion.

The finish kit stops persistent recording, verifies USB copies, then removes
the verified internal logs. AR keeps working. If copying/flushing fails, logs
are kept internally for retry. The copy-only diagnostics kit remains available
for mid-drive parked checks; it does not stop or delete recording.
Persistent capture and these behavioral changes are host-tested, not yet
verified on this car. Missing files are themselves diagnostic evidence.
