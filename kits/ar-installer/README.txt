ULTIMATE WIRELESS PROJECTION 1.3.0.125 -- AR HUD (TEST BUILD)
Wireless CarPlay and Android Auto, with navigation on the factory AR HUD.
https://github.com/KSubedi/ultimate-wireless-projection
Target: Kia EV6 USA, head-unit firmware 260507, factory AR HUD.
This is a NEMESIS application update, not a firmware/bootloader flash.
The owner tested 1.2.0.102 for three hours of driving with generally good results.
This test build applies what the 1.2.0.104 diagnostic drive showed. Its
changes are host-tested and need their own car test.
NEMESIS package versions remain numeric.

NEW IN 1.3.0.125: HYUNDAI IONIQ 5 (US, 260903)
A second car: the US Ioniq 5 on head-unit firmware 260903 gets the same build
as the EV6 (wireless CarPlay and Android Auto, the AR HUD on both, EV routing
in Google Maps and Apple Maps, preconditioning), from its own installer:
UltimateWirelessProjection_IONIQ5_US_260903_<version>.zip.
- Its CarPlay app names two of the fields the AR HUD reads one letter on;
  the AR code recognises either layout and refuses anything else.
- Its vehicle framework was checked against the EV6's: the same commands,
  numbers and reset timer for preconditioning, so the EV features are on.
- Apple Maps EV routing uses the Ioniq 5 profile (egmp-carplay-ev-routing's,
  checked by its author on an Ioniq 5), and turns itself off on a car whose
  battery isn't the 77.4 kWh one (for example the 58 kWh standard range).
- EV6: no change in behaviour; the same battery check applies.

NEW IN 1.3.0.124: EV ROUTING IN APPLE MAPS (CARPLAY)
Apple Maps can now plan charging stops and show battery on arrival over
CarPlay, as Google Maps does over Android Auto. From egmp-carplay-ev-routing
by Isaac Wisdom, with the vehicle identity from HMG-API's Wireless CarPlay
mod (Open-HMG), built into this package (no HMG-API needed):
- When the iPhone connects, the head unit tells it it's an electric car
  (Kia EV6, CCS up to 235 kW, J1772 up to 10.9 kW). Every 10 seconds while
  Apple Maps asks, it sends the battery %, range, and the car's charging and
  consumption figures.
- The charging curve uses the EV6's measured DC power (135 kW between 53%
  and 80%, as for Google Maps). Consumption values are the ones Isaac
  measured within 1-2% on an EV6.
- After installing: remove the iPhone from the head unit's paired devices,
  forget the car on the iPhone (Bluetooth and CarPlay), and pair again.
  Then in Apple Maps tap your profile picture, Vehicles, and add the car.
- For the 77.4 kWh battery only. UWP-NO-EV on the install drive leaves it
  out (with the Android Auto EV features), and UWP-AR-carplay-ev-off.zip
  turns it off without reinstalling.
- New files: /system/lib/libcarplayev.so, and a small patch to
  /system/lib/libiap2session.so. Uninstall puts both back.

NEW IN 1.3.0.123: SMARTER EV ROUTING IN GOOGLE MAPS
Android Auto's EV features now include the work from egmp-aa-ev-hmg-api 0.6.0
to 0.8.2 by AwsomeFox (a fork of egmp-aa-ev-feat), built for this package:
- EV6 calibration: the EV6 gets its own road load instead of the Ioniq 5's,
  which over-predicted EV6 highway use by about 22% (Maps planned too much
  charging), and the DC plateau at 53-80% is 135 kW instead of 160 kW.
- Learns your car's highway consumption: every 10 km of steady driving at
  60 km/h or more adjusts the model (0.7x to 1.3x).
- Learns how fast your car DC-charges, per 10% battery band, from stops at
  chargers Maps was guiding you to. Not AC charging.
- Maps' battery % matches the dash: Maps read up to 2 points low, most near
  empty. The head unit now sends Maps the car's own battery % curve.
- Battery capacity is learned as the energy at 100% on the dash, and a
  -40 C reading at start-up no longer counts as cold weather.
- What is learned applies from the next Android Auto session, never in the
  middle of a route. The EV log header shows it (road_scale, charge_scale).
- A quick Android Auto reconnect can no longer leave two senders running.
CarPlay, Android Auto turn-by-turn and the AR HUD are unchanged from 1.3.0.121.

NEW IN 1.3.0.122: ULTIMATE WIRELESS PROJECTION
This project is now maintained on its own as Ultimate Wireless Projection
(it started as a fork of Evergreen). Nothing changes in the car's behaviour.
- New names: the drive's status file is UWP-STATUS.txt, and the opt-in files
  are UWP-INSTALL (reinstall this version) and UWP-NO-EV (no EV features).
  The old EVERGREEN-INSTALL and EVERGREEN-NO-EV names still work.
- Control kits are named UWP-AR-*.zip.
- Porting to another car: UWP-Porting-Kit.zip (read-only) collects what a port
  needs and says how to file a port request.

NEW IN 1.3.0.121: ANDROID AUTO AR TIMING
From the first 1.3.0.120 drive with Android Auto:
- No more false "turn now" arrows. Google Maps reports 0 m for a new turn
  for a moment before its real distance arrives, and 0 m for minutes for a
  "continue" with no distance yet. Both were drawn as a fully zoomed AR
  arrow (one "go straight" arrow stayed up for five minutes). A turn now gets
  AR only once the phone has placed it at least 10 m ahead; until then the
  HUD shows Android Auto's own icon.
- Street turns start earlier at speed: about 15 seconds ahead at the car's
  speed when that is farther than 200 m, up to 457 m. At 40 mph that is
  about 270 m instead of 200 m; in town nothing changes. Once the arrow is
  up it stays until the turn. Tuning: street_ar_seconds (5-30, 0 turns it
  off). Android Auto only; CarPlay is unchanged.
- The export kits accept 1.3.0.121.

- The cause: the wireless projection part saves the head unit's projection
  settings (added in 117) and renamed that file with "mv -f". The head
  unit's mv takes no options, so the step failed on every fresh install.
  It now uses plain mv, and the test head unit rejects mv options as the
  real one does.
- The head unit has no "tail": the hotspot report's last two sections (hostapd
  channel messages, projection session events) came out empty, and so did
  the NEMESIS log in INSTALL-LOG. Both are fixed.
- 1.3.0.113 to 1.3.0.117 are withdrawn (118 and 119 were never released):
  their install fails with the head
  unit running. Use 1.3.0.120.

NEW IN 1.3.0.119: INSTALL FIXES AND HOTSPOT PRIVACY
- An update over a version whose own install failed no longer stops with
  NEMESIS code 5: that version has nothing installed, so the drive retries
  past its failed uninstall step (NEMESIS --force, which skips only that
  step). A version with parts installed is never forced.
- A part that failed partway through its install is now undone as well, so
  a failed install leaves the system files stock. Before, the wireless
  projection part could leave its first edits behind, and its unfinished
  backup then blocked the next uninstall.
- If an install fails, the drive saves an INSTALL-LOG folder: the install
  log of the package and of each part, free space and the NEMESIS log (no
  names or passwords). Restart the head unit and send that folder. Each log
  run also saves it as install-logs in the logs folder.
- Privacy fix: the hotspot report printed the hotspot's softap.conf, which
  holds the hotspot name and password. It now skips that file and keeps only
  plain setting lines from the other configuration files. If you shared a
  hotspot.txt from 116 or 117, consider changing the hotspot password.
- No CarPlay, Android Auto or HUD changes from 117.
- The export kit accepts 104 to 1.3.0.120.

NEW IN 1.3.0.117: THE ARROW STUCK FOREVER (a tester's two days of logs)
What the logs show: the phone left the head unit's Wi-Fi and never came
back, but the head unit's CarPlay never noticed the session had ended. It
stayed in turn-by-turn mode, the iAP2 link retried every couple of seconds,
and the HUD kept the last arrow until the head unit was restarted.
- During a wireless CarPlay session the phone's iAP2 messages arrive about
  once a second, with or without navigation. After 30 seconds without one,
  the HUD's arrow and turn list are cleared at once, and the dead session
  is ended and CarPlay advertised again so the phone can reconnect (off with
  tune-recover-keyframe or tune-recover-off: then the HUD is still cleared).
- Fixed in 113-116: the 90-second guidance restart reset the AR state
  without clearing the arrow, which could stop the arrow ever being cleared.
  A stop now always clears a frame still showing.
- The 90-second restart no longer runs over a dead link (it can't reach the
  phone); the link watchdog above handles that case.
- The export kit accepts 104 to 1.3.0.117.

NEW IN 1.3.0.116: ONE DRIVE FOR INSTALLING AND FOR LOGS
The installer drive now also collects the logs; there's no separate log kit.
- First time you plug it in (parked, head unit fully started, approve the
  NEMESIS prompt): it installs this version. The head unit restarts by
  itself when it's done.
- Every time after that, once this version is installed: it saves the AR
  logs to a new AR-LOGS-<date-time> folder on the drive (checksum-verified),
  clears them on the head unit and keeps recording for the next drive. It
  also writes the hotspot report into that folder and creates the
  egmp-aa-ev-logs folder, so the EV logs copy too: wait about 20 s after the
  "logs saved" message before unplugging.
- UWP-STATUS.txt on the drive says what it did and what the next plug-in
  will do. While the head unit is still starting (for example the restart
  right after the install) it does nothing.
- An older build's logs are copied to AR-LOGS-BEFORE-UPDATE-<date-time> before
  an update. To reinstall the same version, put an empty file named
  UWP-INSTALL on the drive. To stop recording for good, use
  UWP-AR-export-stop-clean.zip from the controls zip.

NEW IN 1.3.0.115: EV FEATURES FOR ANDROID AUTO (first EV6 build)
The EV features (from egmp-aa-ev-feat by willemcvu), until now only
for the Ioniq 5, are built for the EV6 and on by default:
- EV routing in Google Maps on Android Auto: the head unit sends Maps an
  energy model with the battery's live energy, so Maps shows battery % on
  arrival and plans charging stops with realistic charge times.
- Battery preconditioning: when the next stop is a fast charger (over 50 kW)
  within an hour, the car is asked to warm the battery, the same request the
  car's own navigation makes. The car's own battery conditioning setting is
  the off switch.
- EV logs (driving and charging, no location): copied to any USB drive that
  has a folder named egmp-aa-ev-logs at its top level, within about 20 s.
- The energy model is the Ioniq 5's for now (same battery); expect arrival %
  to be slightly low. Your EV logs are what makes an EV6 model.
- Android Auto AR keeps working: the EV build of the Android Auto app gets
  the AR changes too. An empty file named UWP-NO-EV next to NEMESIS.data
  on the install drive installs without the EV features.
What we need: does Maps show arrival %, does a route to a fast charger start
preconditioning (the car shows it), and the EV logs (folder above) plus the
AR logs (get-logs kit). This doesn't affect CarPlay.

NEW IN 1.3.0.114: ANDROID AUTO AR (first test build)
Android Auto's turns already reached the HUD and its turn list, but as
projection icons, which the AR HUD does not animate. Near a maneuver they now
go out as the car's own types, the same way as CarPlay's:
- Right/left/slight/sharp turns and straight: the large rotating AR arrow
  from 200 m (street_ar_meters), at Android Auto's exact distance, gliding
  smoothly between its once-a-second updates.
- Highway exits: the native right/left exit sign within 457 m. Android Auto
  says which side, so no guessing (exit_style=slight gives the arrow).
- Forks (keep left/right), U-turns, roundabouts (from the exit Android Auto
  gives): native types within 457 m.
- On-ramps, merges, ferries, destinations and anything farther away keep the
  Android Auto icon, as before.
- The progress bar fills like CarPlay's. If the phone's guidance stops while
  you drive, the arrow is cleared once the maneuver is behind you.
- tune-android-auto-off turns this off (Android Auto icons only); disable
  turns off AR for both CarPlay and Android Auto.
What we need from a test drive with Android Auto (Google Maps or Waze): the
AR logs (get-logs kit), and for anything odd the clock time, the maneuver
(e.g. "right exit"), what the phone showed and what the HUD showed. The logs
record Android Auto's maneuver type, side and exact distance and what was
sent to the HUD; never road names.

NEW IN 1.3.0.113 (from two more owners' logs)
Numbered 1.3.0.x because it now builds on Evergreen 1.3.0; it installs over
1.2.0.112 and earlier test builds like any update.
- CarPlay picture freezes are recovered automatically. When no video has
  arrived for 10 seconds, the head unit's own CarPlay app noticed but did
  nothing; leaving CarPlay and coming back was the only fix. Now: at once,
  the phone is asked for a fresh video frame; if the picture has not come
  back 4 seconds later, the head unit takes the screen and hands it back
  1.5 seconds later (a short black flash), which makes the phone restart its
  video stream. An optional third step (tune-recover-reconnect) ends the
  session so the phone reconnects; it drops calls and audio, so it is off
  by default. Nothing happens while you are on another screen.
- If the phone stops sending turn-by-turn while a route is active (seen
  with ABRP and on a plain Evergreen car: the HUD keeps the last turn or
  shows nothing), the head unit asks the phone to resend route guidance
  after 90 seconds of silence (stops at lights were at most 60 seconds).
- "1,000 ft": Apple Maps sends that distance with a comma, which the head
  unit's CarPlay app could not read, so the HUD showed 0 for a moment about
  300 m before a turn. Fixed.
- Uninstall puts the head unit's projection settings back (wired CarPlay
  could stay off after removing a wireless package; reported on a Korean K8).
- The hotspot: another owner's logs show the head unit's own Wi-Fi hotspot
  changing channel by itself while driving, and CarPlay dropping right after
  some of those changes. The recorder now logs every channel change and
  Wi-Fi disconnect (no addresses), and UWP-AR-hotspot.zip collects the
  hotspot's channel settings (no network name, password or address) so we
  can find where the switching comes from. Please run it after a drive with
  a freeze or drop and send HOTSPOT/hotspot.txt.
What we need from a test drive: the AR logs (get-logs kit) and, for every
freeze or drop, the clock time, whether the picture came back by itself
(with or without a short black flash) and whether the HUD kept working.

NEW IN 1.2.0.112 (from the recorded 111 drive)
What the 111 logs show (two drives, 50 and 39 minutes; the owner: AR went
really well, CarPlay froze twice, the HUD kept working): the health records
caught both freezes and a third one.
- 6:33 pm: at 18:33:35 everything from the phone stopped, including the
  guidance (0 bytes received for ten seconds while the head unit was still
  transmitting). The session dropped at 18:33:45 and was rebuilt by 18:33:56,
  after which the picture was back but the phone sent no more turn-by-turn
  data for the rest of that drive. A lost link, not a stuck head unit.
- 7:11 pm, on the highway 2.9 km before an exit: for 95 seconds the picture
  stream fell from about 10 MB/s to 70 KB/s while the guidance kept arriving
  once a second (the HUD worked: 2860 m down to 13 m), the audio stream
  continued both ways, and the head unit was idle (CPU 16-21 %, no thread
  busy, 1.9 GB free). The phone stopped sending screen frames; the session
  stayed up; the picture returned at 19:12:41 without a reconnect.
- 6:14 pm: the same picture with audio paused as well, 30 s, back by itself.
  In all three the head unit was healthy; our helper used under 1 % of one
  core. What this build cannot yet tell is whether the phone stopped sending
  because the link was losing packets or on its own. 112 adds the radio's
  link level, retry and missed-beacon counters and the TCP retransmission
  count to the health record: retransmissions and a weak level during a
  freeze mean the Wi-Fi link; a quiet, clean link means the phone.
- Fixed: 25 times on the 111 drive the arrow and HUD entry were blanked for
  one second (a worker tick read the clock just before the phone's sample
  arrived and took the sample for a future one). The 5-second health sample
  now runs after the tick and off its clock, and a sample from the near
  future is treated as fresh.
Things to try against the freezes, independent of this build: keep the head
unit's own Wi-Fi client off (it is known to drop projection), and note
whether the phone was warm or charging wirelessly when it happened.

NEW IN 1.2.0.111 (from the recorded 110 drive)
What the 110 logs show: guidance arrived once a second for the whole 35
minutes, every exit and fork was converted, and nothing expired, crashed or
ran late. Street turns showed the large arrow from 111-125 m, about 7 to 12
seconds, with the bar at 20 and then 10.
- The large street-turn arrow now starts at 200 m instead of 125 m
  (street_ar_meters). On the stock bar scale that also gives it four bar
  steps (40, 30, 20, 10 from 200, 183, 137 and 91 m) instead of two. Whether
  the arrow's zoom follows the bar or only the distance is still not known;
  the kits zip has tune-bar-300 and tune-bar-200 (bar runs 60 to 10 or 90 to
  10 while the arrow is shown), tune-street-300 (even earlier) and
  tune-street-125 (the 110 behaviour) to compare. A short drive on the car's
  own navigation with UWP-AR-baseline.zip would show what the stock
  sends, which is the scale to copy.
- CarPlay freezing: the owner saw the CarPlay picture stick during the 110
  drive until leaving and reopening CarPlay. Our records for that drive show
  the phone's guidance and the projection app running normally throughout,
  so the cause is outside what they capture; it was also seen before AR
  existed. To classify the next one, the trace adds one "health" record
  every five seconds: CPU share in use, load, free memory, the six busiest
  threads of the projection app by name, and bytes received/sent per network
  interface. Counters and names only: no addresses, no packet contents. A
  frozen picture with bytes still arriving points at the head unit's
  decoding; with no bytes arriving, at the phone or the wireless link.
  Please note the clock time of a freeze.
- The analyser separates process runs that reuse a process ID across boots
  and summarises the health records.

NEW IN 1.2.0.110 (from the recorded 108 drive)
What the 108 logs show: on the first route the phone delivered no guidance to
the head unit at all, so there was nothing to draw. After the head-unit
restart the exit arrived at 447 m, the native exit sign was sent and drawn
correctly, and two seconds later the phone's guidance stopped arriving for
nine minutes while the car drove on. Nothing told the HUD to drop the sign.
Why the phone's guidance stopped is not visible in those logs.
- Guidance lost while moving now clears the HUD. If the last samples showed
  the car closing at 12 m/s (27 mph) or more and then nothing arrives, the
  turn and the small HUD entry are cleared after twice the time the car
  needed to reach the maneuver plus 10 s, never sooner than 20 s. On the 108
  drive that is 48 s instead of never. A car that slowed down before the
  silence (a light, a queue) keeps its picture as before: in the 31 recorded
  stops the phone always reported the car below 2 m/s first.
- The native exit sign is the default again (exit_style=sign): it was drawn
  correctly on the 108 drive. Far away and in the small HUD an exit is the
  slight-right/left icon 66/71. UWP-AR-tune-exit-slight.zip gives the
  109 arrow instead.

NEW IN 1.2.0.109 (after the failed 108 drive, before its logs were available)
109 assumed the cluster might have ignored icon 81. The 108 logs do not
support that; see 110 above. What 109 changed and 110 keeps:
- Exits no longer use icons 81/82. The stock US app refuses to send those to
  the cluster; icon 66/71 is used far away and in the small HUD.
- exit_style=slight|sign. Slight is a large AR arrow at 45 degrees within
  457 m (the 109 default); sign is the native exit sign (the 110 default),
  with an explicit clear before the next maneuver.
  UWP-AR-tune-exit-generic.zip is the 107 behaviour (icon 86, no AR).
- If the phone sends nothing for five minutes during a maneuver, the turn and
  the small HUD entry are cleared instead of staying on the last frame.
- Diagnostics: a crash of the projection app is recorded (thread, exception
  class and method names only, never a message), and once per quiet period of
  15 s the log shows where the app's sender thread is.
- The export kit takes a plain copy of the logs before it checks anything,
  and writes the reason for a failure to AR-EXPORT-ERROR.txt on the USB.

NEW IN 1.2.0.108 (from the recorded 107 drive; exit icons changed in 109)
- Highway exits: the phone's direction-unspecified exit (CarPlay type 8) now
  gets a side. The stock app maps CarPlay's sided exits to the exit icons and
  then refuses to send them on US firmware, so no exit ever reached the HUD;
  the generic icon 86 used in 106/107 is drawn as "continue on this road".
  Now: right exit (icon 81 far, native EXIT_RIGHT within 457 m) in right-hand
  traffic, left when the phone's exit angle is negative. Tunable: exit_side=
  auto|right|left|generic.
- The bar is back on the stock navigation's scale (90 to 10 over 457 m) instead
  of filling over the 125 m street window, since the arrow looked further
  rotated than the real distance on the 107 drive. Tunable: bar_meters.
- New turn_offset_meters tuning (0-60): adds meters to the large AR arrow's
  distance only, to test whether the arrow is placed too close. The small HUD
  number stays the phone's distance.
- Turn records log the phone's junction angle values and its item 12 for the
  next exit/roundabout decisions.

NEW IN 1.2.0.107
- The small HUD's distance number now glides with the AR arrow (4 Hz, same
  value) instead of stepping once a second with the phone's rounded value.
- Consecutive maneuvers switch directly from one arrow to the next; the blank
  frame between them is only sent when the next maneuver cannot be shown yet.
- Lower overhead: opt-in flags are checked once a second, trace text is only
  built while tracing is on, and routine trace chatter has its own budget.

NEW IN 1.2.0.106 (from the recorded drive)
- Route start: the phone's "start route" instruction now shows the small
  straight cue instead of leaving the HUD blank until the first turn.
- Waiting at a light no longer drops the progress bar: the lease follows the
  route, not the phone's update cadence (the phone sends nothing while the car
  stands still).
- The bar fills 90 to 10 over the street-turn window instead of staying at 10.
- Smoother countdown: between phone samples the arrow projects at 80 percent
  of the measured closing rate and never steps back for small corrections.
- While the phone reroutes, the plain icon is shown instead of a stale AR arrow.
- Road-test tuning kit (street AR distance, projection gain, back-step hold)
  that takes effect without reinstalling; trace on/off kits; a stock-navigation
  cluster baseline capture for comparison. See TUNING AND BASELINE below.

WHAT THIS BUILD DOES
Handles the observed code-8 maneuver as a sided exit (right in right-hand
traffic, left when the phone's exit angle says so, or as set by exit_side),
drawn as the native exit sign within 457 m unless exit_style=slight, with its
small-HUD instruction and distance. Explicit left/right exit metadata is drawn
the same way. Deferred street AR is identified as deferred in the logs, rather
than mislabeled unmapped.
Refreshes the existing main-turn and small-HUD paths after maneuver details
arrive, without waiting for the next distance/route-state update. Missing
current-maneuver data and stopped routes do not produce a synthetic turn.
Keeps Evergreen's wireless CarPlay/Android Auto and audio implementation
(the base this project started from).
Large ordinary street-turn AR begins within 200 m (about 650 ft) unless tuned.
Small direction cues remain available earlier. Highway/fork/roundabout native
conversion retains its 457 m (1,500 ft) window; far exit cues use projection icons.
The advanced build uses the phone's exact maneuver distance and raw turn
metadata. It estimates the closing rate from consecutive exact distances and
updates at 250 ms intervals (at 80 percent of that rate, holding rather than
stepping back) for at most 1.5 seconds after a sample, then holds.
It does not guess time-to-turn or extrapolate through stale route changes.
Invalid/missing metadata falls back to the working icon-based AR behavior.

The progress overlay changes only the bar in the navigation app's latest OEM
trap frame. Speed limits, units, camera and warning fields remain the OEM's.
It waits for that app's first real trap frame (often a map speed-limit update);
it never constructs a speed limit from phone data or the cluster's delayed echo.
The lease ends with the route, maneuver, source or connection, across boots,
or when guidance/AR is disabled; a silent phone at a standstill keeps it,
silence after the car was last seen moving ends it (see 110 above).
Both AR and advanced features are enabled by this selected experimental installer.
Unknown/far/native turns pass through. The HUD must have AR mode selected.

INSTALL
1. Save your current installer zip so you can reinstall it if needed.
2. Extract this zip to the root of a USB drive the car already reads. Put no
   other NEMESIS kit on that drive. It should contain NEMESIS.data, NEMESIS.sh
   and packages/. Keep the drive: the same drive collects the logs later.
3. Park in a safe place, keep the car in READY, wait until the head unit has
   fully started, and insert USB. NEMESIS must already be installed. Approve
   the NEMESIS prompt; the drive installs Ultimate Wireless Projection.
   It replaces the same wireless.carplay.aa package; NEMESIS runs the previous
   version's uninstall first. Do not separately remove working backups.
4. Wait for the install and head-unit restart. Remove USB while it restarts,
   as the install message instructs. Do not interrupt an active install.
5. Select the car's AR HUD navigation mode, reconnect CarPlay, and start a new
   navigation route. Native-format arrows are expected near supported maneuvers,
   not continuously along the road or at all distances. Evaluate while parked
   first; a passenger should observe/report behavior during a normal drive.

TURN AR OFF / ROLLBACK
For a quick return to BASIC AR while keeping the new package installed, use
UWP-AR-basic.zip on a separate USB. It disables the new interpolation,
metadata and progress overlay, while retaining the confirmed icon-based AR.
Start a fresh route after switching. UWP-AR-advanced.zip restores advanced
mode when the new adapters are installed. If AR was globally disabled, use the
enable kit too. The previous 1.2.0.101 installer remains a full rollback option.

Use the separately supplied UWP-AR-disable.zip kit on an otherwise empty
USB. It removes only the AR flag and keeps wireless projection installed. Start
a fresh navigation route after toggling. UWP-AR-enable.zip re-enables it.
With an existing ADB connection the equivalent disable command is:
  adb shell "nemesis root -c 'rm -f /data/nemesis/apps/wireless.carplay.aa/ar-hud.enabled'"

To restore your previous build, install its known-working zip through
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
resets, worker heartbeat, the phone's route and maneuver states, the
tuning in effect, and every five seconds a health record (CPU share, load,
free memory, busiest projection-app thread names, bytes per network
interface, Wi-Fi link level and retry counters, TCP retransmissions), the
CarPlay picture's timeouts and every recovery step, and the hotspot's
channel changes and disconnects copied from the kernel log (no addresses). API return is not physical HUD acknowledgement.
It does not capture raw CarPlay video, arbitrary phone packets, Wi-Fi passwords,
road names, coordinates, phone identifiers or general app logs.

TUNING AND BASELINE
UWP-AR-tune.zip (built with tools/build-carplay-ar-controls.py and values
such as --street-ar-meters 90 --gain 0.7 --backstep-meters 10) writes a small
ar-tuning file next to the AR flags. Allowed ranges: street_ar_meters 30-457,
gain 0.5-1, backstep_meters 0-50, bar_meters 30-457, turn_offset_meters 0-60,
exit_side auto|right|left|generic, exit_style slight|sign,
recover off|keyframe|screen|reconnect, guidance_restart_s 0 or 60-600,
android_auto on|off, street_ar_seconds 0 or 5-30; anything else keeps the
built-in default (200 m, 0.8, 15 m, 457 m, 0 m, auto, sign, screen, 90, on,
15 s). The distance and exit
settings apply to Android Auto as well. Values apply
within two seconds; start a fresh route. A tune kit replaces the whole file, so
one kit is in effect at a time. The 1.3.0.115 kits zip has ready-made variants:
tune-street-125 (the 110 start distance), tune-street-300, tune-bar-300 and
tune-bar-200 (the bar runs 60 to 10 or 90 to 10 inside the 200 m window),
tune-offset-15/-30 (arrow placed 15/30 m further), tune-exit-slight (the 109
exit arrow), tune-exit-generic (the 107 exit icon), tune-exit-left,
tune-recover-keyframe (key frame only), tune-recover-reconnect (adds the
session restart), tune-recover-off, tune-guidance-restart-off,
tune-android-auto-off and tune-reset.
UWP-AR-hotspot.zip writes HOTSPOT/hotspot.txt with the hotspot's
channel settings and history (read-only; works with any build of this package;
see notes/hotspot-channel-debugging.md in the repository).
UWP-AR-tune-reset.zip removes the file. UWP-AR-status.zip shows it.
UWP-AR-trace-off.zip stops the detailed per-event trace (the once-per-
second turn records stay); UWP-AR-trace-on.zip restores it.
UWP-AR-baseline.zip asks the head unit to record, once a second for the
requested minutes (default 20), what the cluster service last received from the
car's own navigation: turn type, direction, distance and bar. Insert it parked,
remove the USB, drive a route with the car's navigation, then export with the
diagnostics or finish kit. Only turn/trap frame lines are kept; the turn list's
road names are not. This gives the stock exit types and start distances to
compare the CarPlay conversion against.

AFTER THE DRIVE
1. Park in READY, wait until the head unit has fully started, and plug in the
   same installer drive. Approve the NEMESIS prompt.
2. Wait for the "logs saved" message, then about 20 s more for the EV logs.
   UWP-STATUS.txt on the drive says where everything went.
3. Zip the newest AR-LOGS-<date-time> folder (and egmp-aa-ev-logs, if you use
   Android Auto) and open an issue at
   https://github.com/KSubedi/ultimate-wireless-projection/issues with the zip attached.
   The logs hold no road names, locations, network names, passwords or phone
   identifiers; they do hold timings, distances, speeds and battery data.
   Recording continues, so you can do this after every drive.
   (UWP-AR-export-stop-clean.zip, in the controls zip, also exports but
   stops recording for good. This version supports 104 to 1.3.0.125.)
4. In the issue, give the approximate clock time of anything odd, the app
   (Apple Maps, Google Maps, Waze...), what the phone showed versus the HUD,
   and whether leaving CarPlay or Android Auto and coming back changed it. No interaction
   with diagnostics or observation tasks are needed from the driver in motion.

The finish kit stops persistent recording, verifies USB copies, then removes
the verified internal logs. AR keeps working. If copying/flushing fails, logs
are kept internally for retry. If NEMESIS reports that the operation failed,
the USB holds AR-EXPORT-ERROR.txt with the reason and AR-DEBUG-SNAPSHOT with an
unverified copy of the logs; send both. The copy-only diagnostics kit remains available
for mid-drive parked checks; it does not stop or delete recording.
Persistent capture and these behavioral changes are host-tested, not yet
verified on this car. Missing files are themselves diagnostic evidence.
