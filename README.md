# Ultimate Wireless Projection

**Wireless CarPlay and Android Auto for Hyundai Motor Group Gen5W head units, with your phone's navigation on the factory AR HUD.**

Ultimate Wireless Projection is a [NEMESIS](https://github.com/xwtk/hmg_nemesis) package. It started as a fork of [Evergreen](https://github.com/zpoon/gen5w-Evergreen), which continues [HMG Leafia Plus](https://github.com/VysseCodes/HMG-Leafia-Plus). Development here moves fast, so it is now maintained as its own project; see [Credits](#credits) for everyone whose work it builds on.

> [!IMPORTANT]
> **Tested car:** 2022 Kia EV6 GT-Line, USA, Gen5W head-unit firmware **260507**, with the factory AR HUD. The installer only installs on that firmware. Want it on your Hyundai, Kia or Genesis? See [Help port it to your car](#help-port-it-to-your-car).

**[⬇ Download the latest release](https://github.com/KSubedi/ultimate-wireless-projection/releases/tag/v1.3.0.121-beta.1)** · [Report a problem](https://github.com/KSubedi/ultimate-wireless-projection/issues/new/choose) · [Contributing](CONTRIBUTING.md)

## Features

### Wireless CarPlay and Android Auto
- Wireless CarPlay and wireless Android Auto through the head unit's own Wi-Fi hotspot, started over Bluetooth like a factory wireless unit.
- Good-quality CarPlay audio over Wi-Fi: music as AAC, and Siri, calls and the microphone as Opus, decoded by the head unit's own codecs.
- Turn-by-turn from the phone on the instrument cluster and the HUD, for both.
- Settings > Phone Projection still opens the stock Device Connections screen (CarPlay split screen, connected phones).

### AR HUD, for CarPlay and Android Auto
- Near a maneuver, the HUD shows the car's own AR graphics instead of a flat icon:
  - **Street turns:** the large rotating AR arrow (left, right, slight, sharp, straight).
  - **Highway exits:** the native left/right exit sign, from about 457 m (1,500 ft).
  - **Other maneuvers:** forks, U-turns and roundabouts.
- The arrow uses the phone's exact distance and moves smoothly between the phone's once-a-second updates. The progress bar and the HUD's distance number count down with it.
- Works with Apple Maps, Google Maps and Waze. Set the HUD to AR mode in the car's settings.

### EV features for Android Auto
From [egmp-aa-ev-feat](https://github.com/willemcvu/egmp-aa-ev-feat) by willemcvu, built into the same package:
- **EV routing in Google Maps:** the head unit sends Maps the battery's live energy, so Maps shows battery % on arrival and plans charging stops with realistic charge times.
- **Battery preconditioning:** when the next stop is a fast charger (over 50 kW) within an hour, the car is asked to warm the battery, the same request its own navigation makes. The car's battery conditioning setting is the off switch.
- **Calibrated for the EV6, and learns your car:** the EV6 has its own energy model, which then adjusts to your car's real highway consumption and DC charging speed from its own drives and charging stops.
- **Maps' battery % matches the dash** instead of reading up to 2 points low.
- **EV logs** (driving and charging, no location) are saved with the other logs.
- Not wanted? Put an empty file named `UWP-NO-EV` next to `NEMESIS.data` before installing. CarPlay isn't affected.

### EV routing in Apple Maps (CarPlay)
From [egmp-carplay-ev-routing](https://github.com/isaacwisdom/egmp-carplay-ev-routing) by Isaac Wisdom, with the vehicle identity from HMG-API's Wireless CarPlay mod, built into this package (no HMG-API needed):
- **The iPhone sees an electric car:** Apple Maps can add it under Vehicles, plan charging stops and show battery on arrival.
- **Live data:** battery %, range and the car's charging and consumption figures, every 10 seconds while Maps asks. The charging curve uses the EV6's measured DC power.
- **Setup:** after installing, re-pair the iPhone (remove it on the head unit, forget the car on the phone), then add the car in Apple Maps: profile picture → Vehicles. 77.4 kWh battery only.

### Recovery from freezes and drops
- **Frozen CarPlay screen:** after 10 seconds without video, the phone is asked for a fresh frame. If that doesn't help within 4 seconds, the head unit takes the screen back for a moment (a short black flash), which restarts the phone's video. This is a common problem with every wireless package on these cars; its root cause is still being investigated ([notes](notes/hotspot-channel-debugging.md)).
- **Phone gone from Wi-Fi without the head unit noticing:** after 30 seconds without contact, the HUD arrow is cleared and CarPlay restarts so the phone can reconnect. Before, the last arrow could stay up until the car was switched off.
- **Silent route guidance** (seen with Waze and ABRP): after 90 seconds without turn-by-turn during a route, the head unit asks the phone to resend it.

### Safe to install and remove
- **One USB drive does everything:** the first time you plug it in it installs; every time after that it saves the logs.
- **A failed install leaves the car untouched** and saves an `INSTALL-LOG` folder to the drive. Updating over a version whose install failed works.
- **Uninstall restores stock**, including the projection settings (wired CarPlay stays on).
- **Control kits** turn AR on or off, switch between basic and advanced AR, tune the arrow, and stop recording, without reinstalling.
- **Private logs:** no road names, locations, Wi-Fi names, passwords or phone identifiers.

## What's different from other packages

This isn't just "wireless on". The AR HUD was tuned over about 20 test builds, each made from logs recorded on real drives.

**AR arrow timing**
- The street-turn arrow distance was tuned on the road: 457 m showed it before side streets, 125 m felt too late, so it's 200 m.
- On Android Auto it starts about 15 seconds ahead at speed (up to 457 m), so it doesn't come up at the last second on faster roads.
- The progress bar uses the same scale as the car's own navigation.

**A smooth, honest countdown**
- The phone sends its distance once a second; the HUD updates four times a second in between.
- Between updates it projects at 80% of your closing speed, because cars slow down before a turn, so the arrow never jumps backwards.
- The HUD's distance number counts down with the arrow.

**Fixes for things phones get wrong**
- Apple Maps' "1,000 ft" (with a comma) no longer shows as 0.
- Exits sent without a side get it from the exit angle, or default to the right.
- No false "turn now" arrows when Google Maps reports 0 m before a turn's real distance arrives.
- No blank HUD between back-to-back turns; the arrow stays while you wait at a light; while the phone reroutes, the plain icon replaces an outdated arrow.
- Stuck arrows are cleared: phone dropped (30 s), guidance lost while moving, or nothing from the phone for 5 minutes.

**Built carefully**
- It changes only the bytes it needs, and only after checking they match stock exactly. Patched apps are rebuilt on your car from its own files; no Hyundai or Kia files are distributed.
- The EV features and AR are built into one package, so they don't fight over the same files.
- Every build passes about 1,700 automated checks plus an install and uninstall on a simulated head unit, which must leave it byte-for-byte stock.

## Install

You need [NEMESIS](https://github.com/xwtk/hmg_nemesis) 1.15 or newer on the head unit.

1. Download `UltimateWirelessProjection_EV6_US_260507_<version>.zip` (`Evergreen_EV6_US_260507_AR_<version>.zip` for 1.3.0.121 and earlier) from the [latest release](https://github.com/KSubedi/ultimate-wireless-projection/releases/tag/v1.3.0.121-beta.1).
2. Unzip it onto an empty USB drive (exFAT or FAT32). The top of the drive should show `NEMESIS.data`, `NEMESIS.sh` and `packages`. **Keep this drive**: it also collects the logs.
3. Park with the car in READY and wait until the head unit has fully started. Plug in the drive and approve the NEMESIS prompt.
4. The head unit restarts by itself when the install is done. Remove the drive while it restarts.
5. Set the HUD to AR mode, connect your phone and start a route.

It installs over earlier versions, Evergreen and Leafia (same package id, `wireless.carplay.aa`), and over stock. Coming from HMG-API's wireless mod? Turn that mod off first; both change the same files.

**Uninstall** any time from NEMESIS Manager (My Packages → Uninstall), then restart the head unit. Every file goes back to stock.

### Good to know
- **Keep the head unit's own Wi-Fi client off** (Settings > Wi-Fi). The hotspot and the Wi-Fi client share one radio and one channel; with the client on, wireless CarPlay and Android Auto drop every minute or two.
- **Switching the car off doesn't restart the head unit.** For a real restart, switch off, open the driver's door and wait for the screen to go dark, or hold the head unit's power button for about 10 seconds.
- **Won't connect after an install?** Restart the head unit. If that doesn't help, forget the car on the phone (CarPlay or Android Auto, Bluetooth and Wi-Fi), delete the phone on the car, and pair again.
- **Keep the car in READY** while installing or testing for a long time; otherwise the 12 V battery drains.

> [!CAUTION]
> **Don't use NEMESIS's Settings → Use Telematics Internet.** It sends NEMESIS Manager's traffic over the car's connected-services SIM, which the manufacturer provides for its own services only, and could get the SIM or connected services suspended.

## Report a problem

Logs record in the background; there's nothing to do while driving.

1. After the drive, park with the car in READY and wait until the head unit has fully started.
2. Plug in **the same drive you installed from** and approve the NEMESIS prompt.
3. Wait for **"logs saved"**, then about 20 seconds more for the EV logs. `UWP-STATUS.txt` on the drive says what it did.
4. Zip the newest `AR-LOGS-<date-time>` folder (and `egmp-aa-ev-logs` for Android Auto).
5. [Open an issue](https://github.com/KSubedi/ultimate-wireless-projection/issues/new/choose) with the **EV6 AR drive report** form and attach the zip. Note the clock time of anything odd, what the phone showed and what the HUD showed.

If an install failed, attach the `INSTALL-LOG` folder from the drive instead. Security problems go through [SECURITY.md](SECURITY.md), not public issues.

## Help port it to your car

So far it has only been tested on the Kia EV6 (US, 260507). The same head unit runs in many Hyundai, Kia and Genesis cars, and the AR work should carry over to other cars with an AR HUD. Porting starts with five minutes in your car:

1. Download **UWP-Porting-Kit.zip** from the [releases](https://github.com/KSubedi/ultimate-wireless-projection/releases), unzip it onto an empty USB drive and run it through NEMESIS. It's read-only and changes nothing on the car.
2. Zip the `UWP-PORT/attach-to-issue` folder it writes and attach it to a [port request](https://github.com/KSubedi/ultimate-wireless-projection/issues/new?template=port-request.yml).

The [porting guide](PORTING.md) explains what the kit collects, what happens next, and how developers build a port.

This repository still contains Evergreen's multi-car package and compatibility data. For cars other than the EV6 today, [Evergreen](https://github.com/zpoon/gen5w-Evergreen) and its [compatibility page](https://zpoon.github.io/gen5w-Evergreen/) may already have a build.

## How it works

- **Byte-checked patches.** Recipes (`recipes/`) list the new bytes for each native-library patch with hashes of the region around them. `lpatch`, a small ARM tool that runs on the head unit, patches only when every checked region matches stock.
- **Rebuilt on the car.** The projection apps are rebuilt on the head unit from its own stock files with the patch sets in `patches/`. The repository holds only those deltas, never Hyundai or Kia files.
- **The AR helpers** (`src/carplay-ar/`) are our own GPL-3.0 code, added to the rebuilt CarPlay, Android Auto and navigation apps. They convert the phone's maneuvers into the HUD's native AR types and send them with the phone's exact distance.
- **Every write to `/system`** is checked against stock first, backed up, and restored byte for byte on uninstall.

Developer notes: [AR HUD development and replay](notes/carplay-ar.md), [EV features](notes/ev-features.md), [hotspot channel switching](notes/hotspot-channel-debugging.md), [porting guide](PORTING.md), [contributor guide](CONTRIBUTING.md).

## Contributing

Contributions are encouraged and will be accepted. Car reports, test drives, logs, bug fixes, ports to new cars and new features are all welcome. The project is maintained by [KSubedi](https://github.com/KSubedi): every change goes through a pull request that the maintainer reviews and merges, so that what reaches people's cars stays safe and consistent. See [CONTRIBUTING.md](CONTRIBUTING.md) for the policy, setup, tests and rules.

## Credits

Ultimate Wireless Projection stands on a lot of community work:

- **[HMG Leafia Plus](https://github.com/VysseCodes/HMG-Leafia-Plus)** by [VysseCodes](https://github.com/VysseCodes): the original package. Its `lpatch`, recipes, CarPlay Bluetooth bridge, CarPlay Wi-Fi and audio patches, `libev6aac.so` converter and the Leafia kit are the foundation of this project. Support its author through [GitHub Sponsors](https://github.com/sponsors/VysseCodes) or [Ko-fi](https://ko-fi.com/vyssecodes).
- **[Evergreen](https://github.com/zpoon/gen5w-Evergreen)** by [zpoon](https://github.com/zpoon): the community continuation of Leafia that this project forked, including its multi-car builds, porting tools, mock head unit and compatibility page.
- **[NEMESIS](https://github.com/xwtk/hmg_nemesis)** by xwtk: the platform everything installs through, and its community packages *Enable Wireless Features* and *Turn-by-Turn Guidance for Phone Projection*, which the wireless and turn-by-turn parts build on.
- **[egmp-aa-ev-feat](https://github.com/willemcvu/egmp-aa-ev-feat)** by [willemcvu](https://github.com/willemcvu): EV routing, battery preconditioning and EV logging for Android Auto, used with their permission.
- **[egmp-aa-ev-hmg-api](https://github.com/AwsomeFox/egmp-aa-ev-hmg-api)** by [AwsomeFox](https://github.com/AwsomeFox): the EV6 energy-model calibration, highway-consumption and DC-charge learning, and the battery % curve (AGPL-3.0, see NOTICE.md). Its EV6 calibration came from logs shared by biscober.
- **[HMG-API](https://github.com/VysseCodes/HMG-API)** and **[Open-HMG's Wireless CarPlay + Android Auto mod](https://github.com/Open-HMG/hmgapi-wireless-cp-aa)**: the documented AR HUD protocol and cluster turn formats that showed AR was possible from projection (this project's AR code is a separate implementation), and the CarPlay vehicle identity code and iAP2 hook map used for Apple Maps EV routing.
- **[hwjang1/ev6kr-wireless-carplay](https://github.com/hwjang1/ev6kr-wireless-carplay)**: EV6 CarPlay research, including a report of AR arrows working on Korean EV6 firmware.
- **[aa-proxy-rs](https://github.com/aa-proxy/aa-proxy-rs)**, **[open-android-auto](https://github.com/Deadknight/open-android-auto)** ([mrmees's fork](https://github.com/mrmees/open-android-auto)) and **[ArdaMapsEnergyProvider](https://github.com/Deadknight/ArdaMapsEnergyProvider)**: Android Auto protocol and EV energy-model references.
- **[egmp-carplay-ev-routing](https://github.com/isaacwisdom/egmp-carplay-ev-routing)** by [Isaac Wisdom](https://github.com/isaacwisdom): Apple Maps EV routing over CarPlay (GPL-3.0); its charging and consumption dictionaries come from [augard/evmaps](https://github.com/augard/evmaps).
- **[gen5w-preconditioning](https://github.com/paytondevs/gen5w-preconditioning)** by paytondevs: a reference for the battery preconditioning commands, which this project sends the same way.
- **[nudl](https://github.com/chenxiaolong/nudl)** by chenxiaolong and **[HKG_Gen5W_ReverseEngineer](https://github.com/cantcs/HKG_Gen5W_ReverseEngineer)** by cantcs: used to download and study the stock firmware the patches are built against.
- **Contributors:**
  - **devilscent** (Discord): helped refine and calibrate the AR HUD.
  - [Isaac Wisdom](https://github.com/isaacwisdom): CarPlay turn-by-turn maneuver fix.
  - [gdonaldson1676](https://github.com/gdonaldson1676): Ioniq 5 260903 port.
  - [W-Floyd](https://github.com/W-Floyd): help with the project.
- **EV6 owners and testers** in the NEMESIS community, whose drive logs shaped every AR build.

Licences and the details of what came from where are in [NOTICE.md](NOTICE.md).

## Licence and disclaimer

Ultimate Wireless Projection is GPL-3.0 (see [LICENSE](LICENSE)), like HMG Leafia Plus and Evergreen. Parts derived from NEMESIS packages, egmp-aa-ev-hmg-api and HMG-API's Wireless mod stay under their original AGPL-3.0 licence (see [NOTICE.md](NOTICE.md)).

It is an unofficial community project, not affiliated with or endorsed by Hyundai, Kia, Genesis, Apple or Google. Modifying your head unit is at your own risk.
