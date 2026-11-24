# CarPlay Ultra-Lite on the EV6: vehicle commands to review, and the first car test

Branch `feature/carplay-ultra-lite`. The port itself is described in
[src/carplay-ultra/README.md](../src/carplay-ultra/README.md). This page is what has to happen before it runs on a
car: our rule is that every new vehicle command goes through the stock services and gets its own review. CarPlay
Ultra-Lite adds several, all written and tested by its author on a Hyundai Ioniq 6 (US, 260507), none yet on an EV6.

## What was checked off-car (Kia EV6 US 260507 stock files)

- `libCarPlayCore.so` is byte-identical to the Ioniq 6's (`d3a47296…`); the patched library matches every stock and
  patched window of upstream's release recipe.
- The two listeners CarPlay Ultra-Lite answers by hand (`com.hkmc.datc.IDATCListener`, `com.hkmc.bcm.IBCMListener`)
  give the same fingerprints on the EV6's `automotive.odex` as on the Ioniq 6 (`upstream/tools/listener_fingerprint.py`):
  `8a834814…` and `b5a76b60…`. CarPlay Ultra-Lite registers neither if its fingerprint differs.
- Every call below exists in the EV6's framework with the same parameter and return types. CarPlay Ultra-Lite looks
  transaction codes up by name and descriptor at run time, so a missing or changed call leaves that control out
  instead of calling something else.

That proves the calls resolve, not that their values mean the same on the EV6 (the flag numbers and ranges were
decoded on the Ioniq 6, `ClimateMap.java`). That is what the review and the car test are for.

## Vehicle commands (writes)

All through the stock services' binder interfaces, never raw CAN. Sent only when the phone changes a value in CarPlay.

| Service / call | Sent for | Values (from `ClimateMap.java` / `CarClimate.java`) |
|---|---|---|
| `IDATCService.setVRClimateStatus(flag, value, unit)` | climate on/off, AUTO, A/C, front defrost, recirculation, SYNC, vent mode, driver temperature (°F) | flag 12 power 1/2; 8 AUTO 0/1; 9 A/C 0/1; 10 front defrost 0/1; 11 intake 1 recirculate, 2 fresh; 22 SYNC; 6 vent 1 face, 2 face+feet, 3 feet; 2 driver set temperature in °F with unit 2; 5 fan: 0 off, 2-9 for CarPlay's levels 1-8 |
| `IDATCService.setFrontClimateControl(item, value)` | Driver Only (CarPlay's passenger zone power), passenger temperature, °C temperatures | 24 Driver Only 1/0; 31 passenger °F as (°F - 56); 28/29 driver/passenger °C as (°C - 14) * 2. **°C is untested upstream** ("only °F seen so far"); a US EV6 is °F |
| `IUSMService.setFrontSeatHeatDrvStep(step)` / `setFrontSeatHeatAstStep(step)` | seat heat, both front seats | the car's own step values (3 levels). Heat only: the EV6's seat ventilation and heated wheel aren't in CarPlay Ultra-Lite |
| `IVRService.set_RrDefogger_Cmd_AVN(1/0)` | rear window defrost | upstream: "puts its value straight on the CAN signal C_RrDefoggerCmdAVN, 1 on and 0 off (what the stock voice handler sends)". Not a toggle. READY only. Setting `rear_defrost=false` leaves the button out |
| `IMicomAudioService.setVolume(stream, level, ?)`, `muteAV()`, `unMuteAV()` | CarPlay's Volume page (media, navigation, phone, Siri) | the car's own levels; setting `volume_link=false` leaves it out |

Reads (no review needed, listed for completeness): `getVRClimateStatus`, `getTemperatureUnit`, `getDriverOnlyValue`,
seat heat states, `getDistanceSpeedUnit`, `getBattCondIndSta`, `getHEVStatus`, the HSCAN telematics reads for battery,
range, odometer and tyres, the audio getters, and the DATC/BCM listeners.

Not sent at all in this port: the volume-key experiment (`IMicomAudioService.adjustsuggestedStreamVolume`,
`hideVolumeDisplay` from system_server) is left out.

Things for the review to settle:

1. The DATC flags and ranges on the EV6 (power 1/2, intake 1/2, vent modes, fan 2-9, temperature range 62-90 °F):
   compare the EV6's DATC code (`com.hkmc.datc` in its framework and the climate app) with the Ioniq 6's, or log the
   flags while pressing the EV6's own buttons, as upstream did. `CarClimate` logs every command it sends
   (`CAR sent ...` / `CAR ... failed`), which makes the write test easy to follow.
2. The rear defroster call: same CAN signal on the EV6? (`IVRService` implementation in its framework.)
3. Seat heat steps: the EV6 GT-Line logs `getFrontSeatHeat_*State` values; upstream's decoding is 2 off, 6/7/8.
4. Volume: `setVolume`'s third argument and the stream numbers.

## First car test (EV6 US 260507, after the review)

Install drive: the package with an empty `UWP-CARPLAY-ULTRA` next to `NEMESIS.data`. Have a standard USB kit ready.

1. Install log: `CarPlay Ultra-Lite (test build): 2 file(s) built` and `libCarPlayCore.so is CarPlay Ultra-Lite's
   build`. CarPlay must still connect wirelessly before anything else is judged.
2. Forget the car on the iPhone and pair again (online). The kit's `carplay-ultra.log` should show
   `starting CarPlay Ultra-Lite 1.3.3`, `libCarPlayCore.so additions on`, the M4 line, `CAR` listener lines, and
   CarPlayEV's `identity armed: ... CarPlay theme 44C9FA31EC:985:19.0`.
3. Climate, read side first: change temperature, fan and A/C on the car's own panel and watch CarPlay follow.
4. Climate, write side, parked: each control from CarPlay once, checking the car's panel each time. Rear defrost in
   READY. Seat heat both seats.
5. Vehicle app: battery, range, odometer against the cluster; the Car home screen button.
6. Volume page against the knob.
7. Theme: CarPlay Settings > Themes. If CarPlay goes black: uninstall, or reinstall with `climate-only` in the marker
   file. Upstream's bundle also turns on EV Routing's `carplay_start_session`, which this port doesn't have yet.
8. Our own features still work with it: turn-by-turn on the cluster and HUD (Apple Maps), AR arrows, the frozen-screen
   recovery. CarPlay Ultra-Lite adds an instrument panel display and a virtual cluster to the session; watch for the
   cluster's turn-by-turn going missing.
9. Uninstall: CarPlay back to the classic look after pairing again, wireless CarPlay still working.

If CarPlay crashes twice within 10 minutes, CarPlay Ultra-Lite's crash guard keeps it off for that start, and our gate
keeps `libCarPlayCore.so` stock until the Java side starts again.
