# Ultimate Wireless Projection

**Wireless CarPlay and Android Auto for Hyundai Motor Group Gen5W head units, with your phone's navigation on the factory AR HUD.**

Ultimate Wireless Projection is a [NEMESIS](https://github.com/xwtk/hmg_nemesis) package. It started as a fork of [Evergreen](https://github.com/zpoon/gen5w-Evergreen), which continues [HMG Leafia Plus](https://github.com/VysseCodes/HMG-Leafia-Plus). Development here moves fast, so it is now maintained as its own project; see [Credits](#credits) for everyone whose work it builds on.

> [!IMPORTANT]
> **Tested car:** 2022 Kia EV6 GT-Line, USA, Gen5W head-unit firmware **260507**, with the factory AR HUD. A **Hyundai Ioniq 5 (US, 260903)** has been driven too, and builds for the **Kia EV6 (Canada, 260206)** and **Hyundai Sonata Hybrid (US, 260206)** are in testing. Each installer only installs on its own car and firmware: see [Cars and port status](#cars-and-port-status). Want it on your Hyundai, Kia or Genesis? See [Help port it to your car](#help-port-it-to-your-car).

**[⬇ Download the latest release](https://github.com/KSubedi/ultimate-wireless-projection/releases/tag/v1.3.0.128-beta.1)** · [Report a problem](https://github.com/KSubedi/ultimate-wireless-projection/issues/new/choose) · [Contributing](CONTRIBUTING.md)

## Features

| | Android Auto | CarPlay |
|---|:---:|:---:|
| **Wireless connection** | ✅ | ✅ |
| **AR navigation on the HUD** | ✅ | ✅ |
| **Dashboard navigation** (turn-by-turn on the cluster and HUD) | ✅ | ✅ |
| **EV routing** (charging stops, battery on arrival) | ✅ Google Maps | ✅ Apple Maps |
| **Automatic battery preconditioning** | ✅ | ❌ not yet¹ |
| **Live EV battery state** in the maps app | ✅ | ✅ |

¹ Preconditioning starts when the maps app tells the head unit the next stop is a fast charger. Google Maps does this over Android Auto; we haven't yet found an equivalent signal from Apple Maps over CarPlay.

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
- **One installer for every supported car:** it picks your car's build from the head unit's product id and refuses cars it has no build for.
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

1. Download `UltimateWirelessProjection_<version>.zip` from the [latest release](https://github.com/KSubedi/ultimate-wireless-projection/releases/tag/v1.3.0.128-beta.1). The same file works on every car in [Cars and port status](#cars-and-port-status).
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

## Cars and port status

### Cars with a build

| Car | Head-unit firmware | Status |
|---|---|---|
| Kia EV6, US (2022–2024) | 260507 | ✅ Driven and confirmed (CarPlay and Android Auto) |
| Hyundai Ioniq 5, US (2022–2024) | 260903 | ✅ Driven and confirmed (CarPlay, AR HUD, Apple Maps EV routing; [#1](https://github.com/KSubedi/ultimate-wireless-projection/issues/1)) |
| Kia EV6, Canada (2022–2024) | 260206 | 🧪 Built, waiting for its first drive ([#2](https://github.com/KSubedi/ultimate-wireless-projection/issues/2)) |
| Hyundai Sonata Hybrid, US (2022) | 260206 | 🧪 Built, waiting for its first drive ([#3](https://github.com/KSubedi/ultimate-wireless-projection/issues/3)); regular HUD, so no AR; no EV features |

They all use the same installer, `UltimateWirelessProjection_<version>.zip`, and get everything in the [feature table](#features): the AR HUD and the EV features need the car's factory AR HUD and the 77.4 kWh battery (Apple Maps EV routing turns itself off on other packs). On a car without the AR HUD, AR turns itself off and turn-by-turn shows as the car draws it.

### Ready to build: Evergreen already supports the car

These have Evergreen's wireless CarPlay, Android Auto and dashboard turn-by-turn patch sets in this repository. An owner's [porting kit](#help-port-it-to-your-car) report is all that's needed to add the AR HUD and EV builds and test them.

| Car | Head-unit firmware | Notes |
|---|---|---|
| Hyundai Ioniq 5, US (2022–2024) | 260507 | Same Android Auto library as the EV6 |
| Hyundai Ioniq 5, Canada (2022) | 260206 | |
| Kia EV6, Korea | 260902 | |
| Hyundai Palisade, US (2025) | 260903 | AR HUD only if fitted; no EV features |
| Kia Sportage and Sportage Hybrid, US (2023) | 260507 | No AR HUD; no EV features |

### Port help wanted

Other Gen5W cars (the head unit in trims with built-in navigation; US and Canada firmware 260507 / 260206). None has a patch set yet. Run the porting kit and file a [port request](https://github.com/KSubedi/ultimate-wireless-projection/issues/new?template=port-request.yml):

| Make | Models |
|---|---|
| Hyundai | **Ioniq 6** (2023–2026, E-GMP EV) · **Kona** and Kona Electric (2020–2023) · Palisade (2020–2023) · Tucson, Hybrid, Plug-in Hybrid (2022–2023) · Santa Fe, Hybrid, Plug-in Hybrid (2021–2023) · Santa Cruz (2022–2023) · Sonata (2020–2023) and Sonata Hybrid (other years) · Elantra, Hybrid, N (2021–2026) · Ioniq Hybrid, Plug-in, Electric (2020–2022) · Venue (2021–2026) · Veloster N (2021) |
| Kia | **Niro**, Hybrid, Plug-in Hybrid, EV (2020–2026) · Sorento, Hybrid, Plug-in Hybrid (2021–2022) · Telluride (2020–2023) · Sportage, Hybrid, Plug-in Hybrid (2023) · Carnival (2022–2023) · K5 (2021–2022) · Seltos (2021–2024) · Soul and Soul EV (2020–2023) · Stinger (2022–2023) · Forte (2022) · Cadenza (2020) |
| Genesis | G70 (2022–2026) |

Bold: most wanted. EV features need a Hyundai Motor Group EV; the AR HUD needs a factory AR HUD; everything else (wireless CarPlay and Android Auto, dashboard turn-by-turn) works on any Gen5W car with built-in navigation and a Wi-Fi module. Trims without built-in navigation ("Display Audio") and newer ccNC head units aren't Gen5W and can't be supported.

## Help port it to your car

It runs on the Kia EV6 (US, 260507) and the Hyundai Ioniq 5 (US, 260903), with the Canadian EV6 and the US Sonata Hybrid in testing. The same head unit runs in many Hyundai, Kia and Genesis cars ([list](#port-help-wanted)), and the AR work carries over to other cars with an AR HUD. Porting starts with five minutes in your car:

1. Download **UWP-Porting-Kit.zip** from the [releases](https://github.com/KSubedi/ultimate-wireless-projection/releases), unzip it onto an empty USB drive and run it through NEMESIS. It's read-only and changes nothing on the car.
2. Zip the `UWP-PORT/attach-to-issue` folder it writes and attach it to a [port request](https://github.com/KSubedi/ultimate-wireless-projection/issues/new?template=port-request.yml).

The [porting guide](PORTING.md) explains what the kit collects, what happens next, and how developers build a port.

This repository also contains Evergreen's multi-car package and compatibility data. Until a car has its own build here, [Evergreen](https://github.com/zpoon/gen5w-Evergreen) and its [compatibility page](https://zpoon.github.io/gen5w-Evergreen/) may already have wireless CarPlay and Android Auto for it.

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
