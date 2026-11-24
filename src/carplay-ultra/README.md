# CarPlay Ultra-Lite test port

**Status: test build, opt-in, Kia EV6 US 260507 only, not yet run on a car.** The vehicle commands listed in
[notes/carplay-ultra-lite.md](../../notes/carplay-ultra-lite.md) need their review before the first car test.

[CarPlay Ultra-Lite](https://github.com/SafariForest/carplay-ultra-lite-gen5w) (GPL-3.0-or-later) brings CarPlay Ultra
features to a Gen5W head unit: the car's climate inside CarPlay (dock temperatures, Apple's Climate app, a panel per
seat, seat heat, front and rear defrost), the car's page in CarPlay's Vehicle app (battery, range, odometer, Light
Vibes and Launchpad switches, buttons to the head unit's own screens), the car's volume levels in CarPlay, and, with
the theme, the CarPlay Ultra look picked in CarPlay Settings > Themes. It is an HMG-API mod, built and tested on a
Hyundai Ioniq 6 (US, 260507).

Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI).

## How this port works

HMG-API hooks DiPO's Java at run time and patches `libCarPlayCore.so` in memory. This package does neither: it
rebuilds apps and libraries on the unit from its own stock files (see the top-level README). So the same code goes in
on disk:

| Upstream (HMG-API mod) | Here |
|---|---|
| `hooks.jar` loaded into DiPO, entry `ioniq6.cpclimate.CpClimate.appLoaded` | CarPlay Ultra-Lite's classes, unchanged, added to our bridge build of DiPO; `org.uwp.carplayultra.Ultra.onCreate` (called at the end of `telechips.CarPlayService.onCreate`) calls `appLoaded` once per process |
| hook points `dipo.carplay.session_started` / `session_finalized` | calls at the start and before each return of `CarPlayService.onSessionStarted(int)` / `onSessionFinalized(int)` into `Ultra`, which runs the hooks CarPlay Ultra-Lite registered (`shim/hmgapi/hook/Hooks`) |
| `hmgapi.hook.*` (ModContext, MethodHook, LoadedApp, ModEntry) | `shim/`: our own small classes with the same names and the methods CarPlay Ultra-Lite calls |
| memory recipe `carplay-climate-m1` on `libCarPlayCore.so`, applied only when the Java side starts | `patches/common/libCarPlayCore.ultra.lpd`: the stock library with our Wi-Fi session patch (`recipes/carplay-core-wifi`) and CarPlay Ultra-Lite's code segment as a real `PT_LOAD` (its own `tools/build.py` makes that file before it turns it into a memory recipe), behind our gate (`native/gate.S`, below) |
| `network` rule: TCP 7130-7135 in from the hotspot | service `carplay-ultra` (`packages/wireless-carplay-aa/svc/carplay-ultra.sh`): the same rule in our chain `UWP_ULTRA`, checked every 10 s |
| service `svc/helper.sh` (root) | the same script, unchanged, as `svc/carplay-ultra-helper.sh`, started by that service |
| E-GMP CarPlay EV Routing's `carplay_theme` setting | our EV build of Iap2ServerApp (`src/carplay-ev`) already carries the identification code for a theme; `CarPlayEv` passes the id written to `carplay-ultra.theme` by part 3_bridge |
| HMG-API settings screen | the upstream defaults; `uwp-carplay-ultra.conf` in DiPO's files folder can override them (below) |

The code segment shipped is byte for byte the one in CarPlay Ultra-Lite 1.3.3's release recipe, the code that ran on
the Ioniq 6. `tools/build-carplay-ultra.py` builds it from `upstream/native` too; a different compiler gives slightly
different bytes, so the script checks the release segment against its own build (same entry stubs where the recipe
branches, same filled table so the same library targets, same description blob) and then uses the release bytes.
Upstream does the same for the same reason (`CPC_HOOKS_O`).

**The gate.** Upstream's library patch exists only while its Java side runs: when its crash guard trips (two DiPO
crashes in 10 minutes) or the mod is off, the library stays stock. On disk the patch is always there, and its C code
falls back to defaults without DiPO's `cpc-*` files (it would still offer the phone the vehicle state protocol, with
nothing answering on 7130-7135). So the three hook sites branch to `native/gate.S` first (220 bytes after the release
segment, in the same `PT_LOAD`): with DiPO's `files/cpc-on`, on to CarPlay Ultra-Lite's stub; without it, the replaced
instruction(s) redone and back, as stock. `Ultra.onCreate` deletes `cpc-on` at every DiPO start and writes it only
once CarPlay Ultra-Lite has started (its `appLoaded` returned and wrote `cpc-dock`, which it doesn't when its guard
trips). The build checks the off paths against the stock instructions (the two `ldr r0 / add r0, pc` sites compute
`gLogCategory_AirPlayReceiverCore`), and `tools/test-carplay-ultra-gate.py` runs the gate under qemu-arm on both paths
(landing place, arguments, `lr` and stack at each site).

`libCarPlayCore.so` is the same file on the EV6 and the Ioniq 6 (md5 `d3a47296…`). The DATC and BCM listener
fingerprints CarPlay Ultra-Lite checks before registering are the same on the EV6 US 260507 framework, and every car
call it makes resolves there with the same signature (checked from the EV6's `automotive.odex`).

## Installing (test)

1. Put an empty file named `UWP-CARPLAY-ULTRA` next to `NEMESIS.data` on the install drive. Write `climate-only` in
   it for climate and the Vehicle page without the theme (the classic CarPlay look).
2. Install as usual. The install log says `CarPlay Ultra-Lite (test build): 2 file(s) built`. Without the file, or on
   a car with no CarPlay Ultra-Lite build, the package installs exactly as before.
3. With the theme: on the iPhone, Settings > General > CarPlay > the car > Forget This Car, and connect again, online
   (the phone downloads the theme). Then CarPlay Settings > Themes.

The theme needs our EV build of Iap2ServerApp (it carries the identification code); with `UWP-NO-EV` there is no
theme, only climate and the Vehicle page.

Uninstalling puts the stock DiPO and `libCarPlayCore.so` back (the mock checks `/system` is byte-identical) and
removes `carplay-ultra.on`, `carplay-ultra.theme`, DiPO's `uwp-carplay-ultra.conf` and `cpc-*` files, and the
firewall chain. CarPlay Ultra-Lite's log in DiPO's files folder stays.

## Settings

DiPO's files folder (`/data/data/com.daudio.app.dipo/files/uwp-carplay-ultra.conf`), `key=value` lines, read when
CarPlay starts (restart the head unit after a change):

| Key | Default | |
|---|---|---|
| `enabled` | true | false: CarPlay Ultra-Lite doesn't start, and the gate keeps `libCarPlayCore.so` stock (no `cpc-on`) |
| `carplay_ultra` | true | false: climate and the Vehicle page with the classic CarPlay look (what `climate-only` writes) |
| `rear_defrost` | true | the rear window defrost button (works in READY only) |
| `vehicle_page` | true | the car's page in CarPlay's Vehicle app |
| `volume_link` | true | CarPlay's Volume page shows and sets the car's levels |
| `drive_log` | false | upstream test aid |
| `adb_keepalive` | false | upstream test aid |

Left out on purpose: `carplay_volume_slider` (an experiment in system_server's key handling, off upstream too) and
the iAP-over-CarPlay experiments (off in the 1.3.3 release). E-GMP CarPlay EV Routing's `carplay_start_session`
(the 0x4300/0x4301 CarPlayAvailability answer, on in upstream's bundle) is not in this port yet; if the theme doesn't
load without it, it is the next thing to add (`identity.c` / `StartSession.java` in upstream's EV Routing source).

## Logs

CarPlay Ultra-Lite logs to logcat (tag `CarPlayUltra`) and to `uwp-carplay-ultra.log` in DiPO's files folder. The
`carplay-ultra` service copies it to `/data/nemesis/carplay.ultra.log`, and the USB kit (standard level and up) saves
it as `LEAFIA/private/logs/carplay-ultra.log`. Private: it holds DiPO's CarPlay lines and the car's AirPlay
identifiers.

## Layout

| Path | What |
|---|---|
| `upstream/` | CarPlay Ultra-Lite 1.3.3's source zip (`carplay-ultra-lite-1.3.3-source.zip`, sha256 `ea679ecf94764d71b550704d0d69fce8cf0a0f16dd94b554b9bd8d7c4f5e2f45`), unchanged, without the compatibility check (`check/`). `mod/carplay-climate-m1.recipe` is its release recipe (the reference segment). To update: replace the folder, rerun `tools/build-carplay-ultra.py`, and check its `release_code` still holds |
| `shim/` | the HMG-API stand-in |
| `glue/` | `Ultra`, the starter our DiPO build calls |
| `native/gate.S` | the on/off gate in front of the release segment |
| `test/` | JVM test of the stand-in (`tools/test-carplay-ultra.sh`, in CI); `test/gate/harness.S` for `tools/test-carplay-ultra-gate.py` (qemu-arm, zig; not in CI) |

Build (needs the EV6's stock files, which can't live in this repo):

```
. /work/toolchains/ev-env.sh   # or equivalent: javac, D8_JAR, baksmali, smali, ANDROID_HOME, pyelftools, bsdiff4
ZIG=zig python3 tools/build-carplay-ultra.py --stock ev6=<stock tree with app/HKMC_DiPO.* and lib/libCarPlayCore.so>
python3 tools/compat-built.py
```

Mock head unit: `MOCK_ULTRA=1` (or `climate`) `tools/mock/install-test.sh ...` with the EV6's stock tree checks the
install puts both builds live with the right files and the uninstall leaves `/system` byte-identical and the files gone.

It writes `patches/common/libCarPlayCore.ultra.lpd`, `patches/ev6/HKMC_DiPO.bridge.ultra.lpd` and the two
`3_bridge+ultra` lines in `patches/ev6/manifest`. Two runs give the same bytes.

Another car needs: the same `libCarPlayCore.so` (or a ported segment table), a bridge DiPO with the same
`CarPlayService` methods, the listener fingerprints (`upstream/tools/listener_fingerprint.py` on its framework), and
the vehicle command review for that car.
