EVERGREEN 1.2.0-ar.1 -- EXPERIMENTAL CARPLAY AR HUD
Target: Kia EV6 USA, head-unit firmware 260507, factory AR HUD.
This is a NEMESIS application update, not a firmware/bootloader flash.

WHAT THIS FIRST BUILD DOES
Keeps Evergreen's wireless CarPlay/Android Auto and audio implementation.
Converts supported CarPlay turn icons to native AR turn type/angle within
approximately 457 m (1,500 ft). AR is enabled by this explicit experimental
installer. Unknown/far turns pass through. The car must have HUD AR mode selected.
This first build uses the phone's rounded display distance; exact-distance
interpolation and the separate filling progress bar are not wired yet.
No AR hardware or real-car test has been completed for this build.

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
