# CarPlay AR HUD prototype

Target: the owner's **2022 Kia EV6 GT-Line, USA**, reported to be on the latest
head-unit firmware, subsequently confirmed as **260507**. Evergreen's matching US EV6 patch set is `ev6`.
Matching stock file hashes must be checked before
building a car package. No car has tested this fork's implementation yet.

## Implementation and limits

This fork needs NEMESIS, not HMG-API. DiPO already sends phone turns through
`NavigationCluster.updateTBTInfo(int,int,float,float,int,int,int,int,int)`.
The build tool replaces its calls with a static forwarder taking the same
receiver and arguments. It changes no register allocation or route parsing.
Only DiPO is patched: native navigation, Android Auto, HUD lists and lanes keep
their existing implementations. There are no new vehicle commands or raw CAN
messages. The existing OEM cluster call carries different display values.

With the experimental mode enabled, documented projection icons are converted
to native turn types within 457 m: straight, left/right, slight/sharp turns,
U-turns, left/right forks and exits, and unambiguous roundabout exit icons.
The helper sends the native angle, zero side-street bits and a turn-frame bar.
Unknown types, invalid distances/units/error flags, far-away turns, route clears
and existing native messages pass through unchanged. It does not infer arrival
from a rounded distance. Directions skipped by Evergreen's existing parser are
still skipped: this prototype does not repair missing maneuver metadata.

Distances come from the existing display distance, converted from the cluster's
units (m/km/mi/ft/yd). They are rounded; the live adapter does not yet consume exact iAP2 distances. The separate trap frame is untouched, so this does not
implement the cluster's filling distance bar or overwrite speed-limit data.
The source also includes reusable, tested foundations for richer integration:

- Raw maneuver metadata conversion, including driving side, junction and explicit arrival state.
- Monotonic distance interpolation using time-to-turn, limited to 1.5 seconds of extrapolation.
- A five-second progress lease and a merge helper that preserves all other 17 trap-frame arguments.

Those three foundations are **not connected to DiPO or the OEM trap sender yet**.
Their hook locations and the progress argument index need the actual stock
apps/framework. No guessed index or background sender is installed.

The helper's AR flag is **off by default**. The separate, explicitly selected
`1.2.0.101` experimental installer includes the enable flag and restricts
installation to US EV6 firmware 260507. Its deployment report records that opt-in;
the normal Evergreen installer and private delta builder remain disabled by default.
Generate optional USB controls with
`python3 tools/build-carplay-ar-controls.py`: enable, disable and status archives
run through NEMESIS. Enable/status require an installed generated AR build and
US EV6 firmware 260507. Disable remains available without either prerequisite.
They change only the package's flag, never `/system`, and do not restart CarPlay.

The following manual commands provide the same toggle. On a generated and mock-tested experimental
build, enable it while parked using NEMESIS:

```sh
adb shell "nemesis root -c 'touch /data/nemesis/apps/wireless.carplay.aa/ar-hud.enabled'"
```

Disable it (subsequent turn updates use the original format):

```sh
adb shell "nemesis root -c 'rm -f /data/nemesis/apps/wireless.carplay.aa/ar-hud.enabled'"
```

The file is inside the package's own directory, removed when NEMESIS removes
the package. For testing the toggle, start a fresh navigation route so the HUD
receives a fresh turn. Keep the car's HUD in AR mode.

## Build and verify

Host requirements: Python 3.9+, JDK 17, smali/baksmali 2.5.2, a host C compiler,
zip/unzip, and `pip install --require-hashes -r tools/requirements.txt`.
The original mock head unit also needs qemu-arm-static and preferably mksh.
The helper build uses R8/D8 8.3.37, available from Google's compiler releases:

```sh
curl -fL https://storage.googleapis.com/r8-releases/raw/8.3.37/r8.jar -o /private/tools/r8.jar
echo '900dfbc649519969fc5a4c7520d6b7355338e565fa1249874e0190b8d61b1199  /private/tools/r8.jar' | sha256sum -c -
cc -DHOST -O2 -o tools/lpatch/lpatch-host tools/lpatch/lpatch.c
D8_JAR=/private/tools/r8.jar sh src/carplay-ar/build.sh
sh tools/test-carplay-ar.sh
```

The tests use our own synthetic classes, never manufacturer files. They cover
unit conversion, maneuver types, pass-through, call rewriting, actual DEX
assembly/disassembly, both build variants and delta round trips. They cannot
verify Dalvik's runtime checks or the HUD's physical behavior.

For real patch generation, first collect the Leafia port kit's stock tree
privately. The builder needs `app/HKMC_DiPO.apk` and `app/HKMC_DiPO.odex`;
the full mock install needs the rest of the stock tree too. Do not upload keys,
account data or other private kit output. The builder checks those two files
against `patches/ev6/stock` before rebuilding anything.

First export an inspection plan. Review the method locations, register shapes
and call counts for **both** reconstructed variants. The build checks this
plan's stock, base patch and DEX fingerprints again before changing anything.
A changed input or unsupported call shape fails the build.

```sh
python3 tools/build-carplay-ar.py --variant ev6 \
  --stock /private/LEAFIA/private/stock --inspect \
  --out /private/ev6-call-sites.json
# Review /private/ev6-call-sites.json before building.
python3 tools/build-carplay-ar.py --variant ev6 \
  --stock /private/LEAFIA/private/stock --out /private/evergreen-ar \
  --plan /private/ev6-call-sites.json
EVERGREEN_PATCHES_DIR=/private/evergreen-ar/patches \
  sh tools/build-package.sh dist/Evergreen_CarPlay_AR-experimental.zip
MOCK_SH=mksh MOCK_NEMESIS=1.15 sh tools/mock/install-test.sh \
  dist/Evergreen_CarPlay_AR-experimental.zip \
  CVEV.USA.0000.V015.012.260507 cvev_us /private/LEAFIA/private/stock
```

To build the labelled, enabled experimental installer after reviewing the plan
and generating the real deltas:

```sh
python3 tools/build-carplay-ar-installer.py \
  --patches /private/evergreen-ar/patches --enable-ar
```

This retains the package id `wireless.carplay.aa` for a NEMESIS update, sets the
package version to `1.2.0.101`, preserves existing services, narrows the package's
OS descriptor, and adds an early firmware guard before the original preinstall.
It includes instructions and uses the same stock gates/backups/uninstall. It is
a NEMESIS application package, not a firmware flash. The generated package holds
only deltas and our source-derived helper payload; no complete OEM apps.

Generation reconstructs the existing DiPO variants and verifies their manifest
APK hashes before modifying them. New builds are assembled twice and compared,
disassembled again, and round-trip checked through lpatch. Only deltas and a
build report and call-site plan leave the temporary working directory. The report
records input/helper/output SHA-256 fingerprints and which adapters are wired.
Packaging verifies both AR deltas against that report and includes it as
`AR_BUILD.json` for the USB controls. The committed patch sets
stay unchanged; unrelated variants and the universal build are not updated.
The generated manifest records the new APK hashes for the normal installer.
The compatibility website will still describe the committed builds until the
new deltas are promoted and `tools/compat-built.py` is run.

## Validation status and next steps

- Helper compilation, 509 Java checks and 19 synthetic integration tests pass locally.
- The owner's collection supplied all 12 known app/library files with matching
  US EV6 260507 stock hashes, plus the framework JAR/ODEX files. They stay private.
- The framework confirms `NavigationCluster.updateTBTInfo(IIFFIIIII)V`. Each
  reconstructed DiPO variant has one inspected receiver-plus-nine-argument call
  in `com/daudio/app/dipo/e/u.a(Integer,Object)`. The existing app passes zero
  distance/bar error flags and already maps display units into cluster units.
- Both real DiPO delta builds assemble deterministically, disassemble, and
  round-trip to their expected DEX/APK hashes.
- Mock full-mode install/uninstall passes using mksh and the actual ARM lpatch
  under qemu. Uninstall restores the tested `/system` tree byte for byte. The
  apps/libraries/framework are real; missing build.prop/Wi-Fi configuration and
  codec-presence checks use clearly marked synthetic fixtures. The mock's
  dexopt is a stub, so this does not verify Dalvik or codec runtime behavior.
- The first `1.2.0-ar.1` package was refused immediately on the car. NEMESIS's
  metadata parser rejects non-numeric package versions; the earlier mock tested
  hooks without parsing that field. The replacement uses `1.2.0.101` and the
  builder validates the final narrowed descriptor after all overrides. A
  regression check rejects the old version before publishing any installer.
- An enabled EV6-only `1.2.0.101` installer is built for the next car test.
- On-car verification is pending: ordinary turns, forks, highway exits,
  roundabouts, rerouting, app/source changes, route ending, AR disabled,
  speed limits and a car without HUD where practical.
- Keep this on the development branch; no stable version bump or release tag
  until the generated builds and rollback have been verified.

## Obtaining stock files

The public Kia update catalog and nudl both identify the US 2022-24 EV6 update
as `CV_E.USA.S5W_M.V015.012.260507`. The official system archive was downloaded
and its complete uncompressed CRC32 verified as `82DB0F65` (2,462,935,040 bytes).
It contains `enc_system/enc_system.ext4` (2,146,435,088 bytes): the Android image
is encrypted. Downloading that archive alone does not supply usable stock APKs
or ODEX files; none were decrypted or hash-verified during this work.

The stock-only collector avoids needing an encryption key. Build it with:

```sh
python3 tools/build-carplay-ar-collector.py
```

Unzip `dist/EV6-260507-stock-collector.zip` to an empty USB drive, run through
NEMESIS while parked, and retrieve `EV6-AR-STOCK`. It writes only to the USB
drive, checks nine stock app files plus three stock CarPlay libraries against known
hashes and searches the projection, Bluetooth and audio backups when live files
have been modified.
It additionally copies framework JAR/ODEX code for call-site inspection.
It collects no keys, logs, account data, phone data, or security partitions.
Generated stock files are private and must not be committed. This collection
is enough for AR patch generation and interface inspection; a full mock system
may additionally need other baseline firmware files not present in those backups.

Sources: [Kia software catalog](https://update.kia.com/US/EN/updateNoticeView/software-version),
[nudl](https://github.com/chenxiaolong/nudl),
[Gen5W image-encryption research](https://github.com/cantcs/HKG_Gen5W_ReverseEngineer).

## Research credits

Protocol facts and the implementation approach were informed by:

- Open-HMG: [AR HUD research](https://github.com/Open-HMG/hmgapi-wireless-cp-aa/blob/main/docs/ar-hud.md)
  and [cluster turn formats](https://github.com/Open-HMG/hmgapi-wireless-cp-aa/blob/main/docs/cluster-turns.md).
- hwjang1: [EV6 KR CarPlay research, section 47](https://github.com/hwjang1/ev6kr-wireless-carplay/blob/main/docs/ANALYSIS.md),
  reporting AR arrows working on Korean firmware 260902 on 2026-11-14.

This fork contains a new GPL-3.0-only implementation of that documented protocol;
it imports neither project's HMG-API hook runtime or Java source files.
