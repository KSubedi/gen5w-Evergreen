# Notices

## Ultimate Wireless Projection

Ultimate Wireless Projection (https://github.com/KSubedi/ultimate-wireless-projection), maintained by KSubedi, is a fork of Evergreen by zpoon (https://github.com/zpoon/gen5w-Evergreen), GPL-3.0, and keeps the same licences. Its AR HUD code (`src/carplay-ar/`) is new GPL-3.0 work; it follows the AR HUD protocol and cluster turn formats documented by Open-HMG (https://github.com/Open-HMG/hmgapi-wireless-cp-aa) and imports no HMG-API code. The sections below describe Evergreen and HMG Leafia Plus as they were forked.

## Evergreen

Evergreen is a community fork of HMG Leafia Plus by VysseCodes (https://github.com/VysseCodes/HMG-Leafia-Plus), GPL-3.0, maintained by zpoon. Everything below describes HMG Leafia Plus as it was forked; Evergreen keeps the same licences.

## egmp-aa-ev-feat (EV features, permission)

egmp-aa-ev-feat by willemcvu (https://github.com/willemcvu/egmp-aa-ev-feat): Google Maps EV routing, battery preconditioning and EV logging for Android Auto. At the time of writing, that repository has no licence file. Its author gave permission publicly, in the NEMESIS community Discord on 2026-11-03 (13:06 UTC), in reply to taking it over:

> "Yes, go for it. I do need to add a license but have absolutely no problems with anyone taking it and doing whatever with it. I'd like to continue improving the energy consumption models but can do that easily as a PR on someone else's repo too eventually."

Message link (visible to members of that server): https://discord.com/channels/1074629698379841567/1558908831143297125/1567163151449989332

On that basis, code taken from egmp-aa-ev-feat into Evergreen is distributed under Evergreen's licence, GPL-3.0, with credit to willemcvu. Its bundled `lpatch` came from HMG Leafia Plus and is GPL-3.0 already.

## egmp-aa-ev-hmg-api (EV learning and calibration, AGPL-3.0)

egmp-aa-ev-hmg-api by AwsomeFox (anthonyanderson), https://github.com/AwsomeFox/egmp-aa-ev-hmg-api, is a fork of egmp-aa-ev-feat licensed under the GNU Affero General Public License v3.0. From its v0.6.0 to v0.8.2 (2026-11), Ultimate Wireless Projection 1.3.0.123 takes, with that repository's history kept as the merge base:

- `ev/gpm-ev/src/.../ChargeCurve.java`, `ChargeLearn.java`, `RoadLearn.java` and `SocCurve.java`, and the tests `ChargeLearnTest`, `RoadLearnTest` and `SocCurveTest`: new files, AGPL-3.0.
- Its changes to `EvModelSender`, `EvModelTemplate`, `EvDriveLog`, `EvCar` and `PrecondCore` (EV6 road load, DC plateau, capacity at 100% display, highway and DC charge learning, the reconnect fix), and to `ev/tools/build_ioniq5_model.py`, `analyze_drive_logs.py`, `evlog_schema.json` and `ev/docs`: those changes are AGPL-3.0.
- The library patch's sensor-type limit raised to 26 for the battery % curve, matching its recipe `ev-sensor-48ee793a`.

GPLv3 section 13 permits combining GPL-3.0 and AGPL-3.0 code in one work; the parts above stay under the AGPL-3.0, and the rest of this project under the GPL-3.0. SocCurve's send was adapted to this project's `sendRawMessage`; nothing from its HMG-API runtime (`hmgapi-mod`) is used.

The EV6 calibration in it came from a 2022 EV6 owner's logs (egmp-aa-ev-hmg-api issue #13, by biscober). Its 0.8.1 fixes credit Weab-chan's Canadian test build.

## egmp-carplay-ev-routing and HMG-API Wireless identity (Apple Maps EV routing, 1.3.0.124)

- egmp-carplay-ev-routing by Isaac Wisdom, https://github.com/isaacwisdom/egmp-carplay-ev-routing (v0.5.0, d148562), GPL-3.0-or-later: `src/carplay-ev/native/ev_extension.c`, `bridge_core.c`, `bridge_jni.c`, `native/compat/stdio.h`, and `src/carplay-ev/java/.../NativeBridge.java`, `VehicleSource.java`, `Subscription.java`, unchanged; `CarPlayEv.java` follows its EvRouting/AppProvider; the EV6 profile starts from its `vehicles/kia/ev6/us-77kwh/profile.json` (whose charging and consumption dictionaries it credits to augard/evmaps). Its compile-only `com.tcc.iap2` stubs (`src/carplay-ev/stubs`) were adapted from HMG-API reference material (AGPL-3.0) and are not shipped.
- Open-HMG/hmgapi-wireless-cp-aa (HMG-API's Wireless CarPlay + Android Auto mod), https://github.com/Open-HMG/hmgapi-wireless-cp-aa (f657ca4): `src/carplay-ev/native/identity.c` comes from its `native/identity/identity.c` (marked GPL-3.0-or-later; the repository is AGPL-3.0) with the changes listed in its header; `native/gate.s` follows its `recipe_gate.s`; the patch bytes at 0x129c6 in `recipes/carplay-ev-identity.recipe` are its `carplay-identification-fc7` recipe's. These parts stay under the licence they came with (AGPL-3.0 where the GPL-or-later grant doesn't apply).
- Hook point names and fields come from HMG-API's `maps/base/iap2.json` (AGPL-3.0, VysseCodes/Open-HMG).
- CarPlay Ultra-Lite (test build, branch `feature/carplay-ultra-lite`), https://github.com/SafariForest/carplay-ultra-lite-gen5w (1.3.3), GPL-3.0-or-later: `src/carplay-ultra/upstream/` is its source release, unchanged (its `LICENSE` and `NOTICE` there); its classes go into our DiPO build and its code segment (the release bytes) into `patches/common/libCarPlayCore.ultra.lpd`; `packages/wireless-carplay-aa/svc/carplay-ultra-helper.sh` is its `svc/helper.sh`. The HMG-API stand-in in `src/carplay-ultra/shim/` is our own code with the class and method names CarPlay Ultra-Lite calls. CarPlay Ultra-Lite contains work from LIVI (below).
- **Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI)**, GPL-3.0-or-later, through CarPlay Ultra-Lite (its vehicle state protocol description, climate and display data and protocol code). LIVI's additional term under GPL-3.0 section 7(b) asks every work containing it to keep this attribution in its legal notices and documentation.

## NEMESIS

HMG Leafia Plus packages install and run through **NEMESIS** by xwtk:
https://github.com/xwtk/hmg_nemesis. NEMESIS is licensed under the GNU Affero General Public License v3.0.

The Wireless CarPlay/AA package builds on these NEMESIS community packages. Their scripts and builds remain under the AGPL-3.0:

- **Enable Wireless Features** (`wireless.projection`). The HMG Leafia Plus version is a slimmed copy of its scripts: hotspot-only, plus a fix that finds the region folder via `ro.mobis.targetdir`.
- **Turn-by-Turn Guidance for Phone Projection** (`projection.tbt`). Used as-is; its app builds are the base for the CarPlay bridge builds.

## Original HMG Leafia Plus work (GPL-3.0)

`lpatch`, the recipes and recipe tooling, the Leafia kit, the compatibility site, the CarPlay Bluetooth bridge, the CarPlay Wi-Fi/audio patches and the `libev6aac.so` audio converter.

## Community contributions

- **AR HUD refinement and calibration**: devilscent (NEMESIS community Discord), who helped refine and calibrate the AR HUD behaviour.
- **CarPlay turn-by-turn maneuver fix** (DiPO route-guidance parser wrote maneuver indexes into a copy of the index array, so every maneuver read as -1 and CarPlay guidance never reached the cluster). Contributed by [Isaac Wisdom](https://github.com/isaacwisdom); applied to the EV6, Ioniq 5 and K5 builds.

## Thanks

- [W-Floyd](https://github.com/W-Floyd), for their help with this project.

## Third-party code used at runtime

The audio converter loads the head unit's own `libstagefright_soft_aacdec.so` (Fraunhofer FDK AAC) and `libopus.so` at runtime. Nothing from them is bundled.
