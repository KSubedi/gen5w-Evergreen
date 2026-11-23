# Apple Maps EV routing over CarPlay (1.3.0.124)

Apple Maps plans charging stops and shows battery on arrival for a car that identifies itself
as electric over iAP2 and sends Apple's vehicle-status EV data. Two upstream projects do this as
HMG-API mods; this package builds the same pieces into its own CarPlay bridge, without HMG-API.

| Piece | Upstream | Here |
|---|---|---|
| Live EV data (battery, range, charging and consumption contracts) every 10 s while subscribed | [egmp-carplay-ev-routing](https://github.com/isaacwisdom/egmp-carplay-ev-routing) v0.5.0 (`d148562`) by Isaac Wisdom, GPL-3.0-or-later | `src/carplay-ev/native/{ev_extension,bridge_core,bridge_jni}.c`, `java/.../{NativeBridge,VehicleSource,Subscription}.java`: unchanged |
| Vehicle identity in the iAP2 identification (electric, name, CCS + J1772 and their power), and the vehicle information/status groups | [hmgapi-wireless-cp-aa](https://github.com/Open-HMG/hmgapi-wireless-cp-aa) (`f657ca4`), `native/identity/identity.c` (GPL-3.0-or-later), recipe `carplay-identification-fc7`, `IdentityInstaller` | `native/identity.c` (adapted), `native/gate.s`, `recipes/carplay-ev-identity.recipe`, `CarPlayEv.arm/enableGroups` |
| Entry points: HMG-API hooks on `Iap2Service.onCreate/onDestroy`, `Iap2InitInformationMsg.checkSupportsFeatures`, `mHandleIap2`, `mLock` (HMG-API's `maps/base/iap2.json`, which the EV6 USA 260507 map uses unchanged) | EvRouting/AppProvider | `CarPlayEv.java`; three `invoke-static` calls patched into the bridge Iap2ServerApp |
| Compile-only `com.tcc.iap2` stubs | egmp-carplay-ev-routing `stubs/` | `src/carplay-ev/stubs` (never dexed); checked against the car's `com.tcc.iap2.odex`: same signatures |

## The libiap2session.so gate (md5 `fc7cb675…`, US 260507: EV6 and Ioniq 5)

HMG-API applies `carplay-identification-fc7` in memory: an anonymous code segment holding the
gate and its handler word, made writable with `mprotect` when the identity is armed. Patched into
the file instead, a writable code page would need SELinux `execmod`, so the gate is split:

- the gate code (0x50 bytes) goes into the zero padding after `.rodata` (0x2e340; free up to
  0x2ecb8), which the executable segment's last page already maps; the RX `PT_LOAD` file/mem
  size grows from 0x2e334 to 0x2e390;
- its handler word goes into the zero-filled tail of `.bss` (0x35120, same page as the end of
  `.bss`); the RW `PT_LOAD` memory size grows by 4;
- `iAP2SessionPacketMakePacketUsingParamListNew` (0x1aa04) starts with `b.w gate` instead of
  `push.w {r4-r11,lr}`; the gate runs that push before resuming at 0x1aa08;
- 0x129c6: the "group 13" enable bytes from the HMG-API recipe, unchanged.

All in place: nothing moves, the file size is the same, and `recipes/carplay-ev-identity.recipe`
(made by `tools/mkrecipe.py`) holds only hashes and the new bytes. Header version 2 at gate+0x20
tells `identity.c` where the handler word is (an offset), so it stores the handler with a plain
atomic store. Checked: the b.w decodes to the gate the way `identity.c` decodes it; both literal
offsets reach 0x35120; the resume address is 0x1aa09; host and ARM `lpatch` give the same file
(`15d81416…`), PATCHABLE on stock and APPLIED after.

## libcarplayev.so

`bridge_jni.c` (with `bridge_core.c` and `ev_extension.c`) and `identity.c` in one library, built
with zig or clang, no libc or NDK: imports only `dlopen`, `dlsym` (libdl.so) and `memset`
(libc.so), SysV hash, no unwind tables (they would import `__aeabi_unwind_cpp_pr*`), relocations
RELATIVE/GLOB_DAT/JUMP_SLOT only. `tools/build-carplay-ev.py` checks the imports.

## Install (part 3, `comp/3_bridge/scripts/common.sh`)

A `3_bridge+ev app Iap2ServerApp` manifest line gives EV installs the bridge build plus the three
calls (`patches/ev6/Iap2ServerApp.bridge.ev.lpd`); `UWP-NO-EV` gives the plain bridge build and
nothing below. With the EV build, part 3 installs `/system/lib/libcarplayev.so` (md5 from
`files/libcarplayev.so.md5`) and applies the recipe with a backup; a library the recipe doesn't
fit is left alone (CarPlay as before, `CarPlayEv` stays inactive: it checks the patched md5).
Uninstall and a failed install put the stock library back and remove ours. Mock head unit: EV,
`MOCK_NO_EV`, `MOCK_FAIL_PROJECTION` and a modified `libiap2session.so` all uninstall to stock.

## At run time (`CarPlayEv`)

`onCreate` (end): off if `carplay-ev.disabled` is in the package folder (control kits
`carplay-ev-off`/`-on`; read at the app's start); inactive unless `libiap2session.so` is this
build's patched file; loads `libcarplayev.so`, reads the EV6 profile, arms the identity, turns on
the two groups, opens the bridge and registers an iAP2 listener (support type 0x100).
`checkSupportsFeatures` (end): arms and turns on the groups if not yet. Notify 15 starts the
10 s sender under `Iap2Service.mLock` with `mHandleIap2`; 16 or a disconnect stops it.
`onDestroy`: stops everything. Log tag `CarPlayEV`; the main events and a send line a minute
also go to `EvergreenAR` (`cpev …`), so the drive logs have them. Nothing throws into the app.

## The EV6 profile (`src/carplay-ev/profile/kia-ev6-us-77kwh.json`)

egmp-carplay-ev-routing's EV6 profile (a copy of its Ioniq 5 values, which it reports within 1-2%
on an EV6), with the DC power at 53% and 80% (41,022 and 61,920 Wh) lowered from 160 to 135 kW,
from a 2022 EV6's DC stops (egmp-aa-ev-hmg-api 0.8.0, issue #13). 77.4 kWh only, as upstream.

## On the car (2026-11-23, owner's EV6, 1.3.0.124)

Owner: CarPlay and Android Auto both flawless, state of charge right. The export: identity armed
at the iAP2 app's start (12:05:11), Apple Maps subscribed 40 s later, every send `send=0`, SOC
90 -> 88% with energy 69,660 -> 68,112 Wh (77.4 kWh x SOC), range 407 -> 399 km, auxiliary
power from the one-minute average (800-1,300 W with climate on). A CarPlay -> Android Auto
switch at 12:24: the phone left without the head unit ending the session; the link watchdog
ended it after 30 s (`link_dead`, `end_session`, re-advertise) and the EV sender stopped with
the subscription. The iPhone is re-paired once after installing (upstream's instruction), then
the car is added in Apple Maps > profile > Vehicles. Released as 1.3.0.124.
