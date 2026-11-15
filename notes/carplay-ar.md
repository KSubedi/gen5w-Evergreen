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
units (m/km/mi/ft/yd). They are rounded; smooth interpolation/exact distances
are not implemented. The separate trap frame is untouched, so this does not
implement the cluster's filling distance bar or overwrite speed-limit data.
This maps existing icon information, rather than introducing a second iAP2 parser.

The AR flag is **off by default**. On a generated and mock-tested experimental
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

Inspect a temporary disassembly of the current reconstructed DiPO and count
calls to `NavigationCluster.updateTBTInfo`. Supply that count explicitly using
`--expected-calls`; an unverified guess must not be silently accepted. The tool
refuses unsupported call shapes or a different count. Both turn-by-turn and
Bluetooth bridge builds must have the inspected count; otherwise inspect and
adapt the tool for that variant.

```sh
python3 tools/build-carplay-ar.py --variant ev6 \
  --stock /private/LEAFIA/private/stock --out /private/evergreen-ar \
  --expected-calls INSPECTED_COUNT
EVERGREEN_PATCHES_DIR=/private/evergreen-ar/patches \
  sh tools/build-package.sh dist/Evergreen_CarPlay_AR-experimental.zip
MOCK_SH=mksh MOCK_NEMESIS=1.15 sh tools/mock/install-test.sh \
  dist/Evergreen_CarPlay_AR-experimental.zip \
  CVEV.USA.0000.V015.012.260507 cvev_us /private/LEAFIA/private/stock
```

Generation reconstructs the existing DiPO variants and verifies their manifest
APK hashes before modifying them. New builds are assembled twice and compared,
disassembled again, and round-trip checked through lpatch. Only deltas and a
build report leave the temporary working directory. The committed patch sets
stay unchanged; unrelated variants and the universal build are not updated.
The generated manifest records the new APK hashes for the normal installer.
The compatibility website will still describe the committed builds until the
new deltas are promoted and `tools/compat-built.py` is run.

## Validation status and next steps

- Helper compilation to DEX and synthetic host tests pass locally.
- Real EV6 stock inputs are not yet available in this workspace.
- Firmware-specific call-site inspection, real delta generation and a full
  mock install/uninstall restoring stock are pending those inputs.
- No installable AR package has been produced for a real car.
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
drive, checks the nine known stock files against the EV6 manifest and uses an
exact matching `projection.tbt` backup if the live file has been modified.
It additionally copies framework JAR/ODEX code for call-site inspection.
It collects no keys, logs, account data, phone data, or security partitions.
Generated stock files are private and must not be committed. This collection
is enough for AR patch generation and interface inspection; a full mock system
may additionally need the other baseline firmware files used by Evergreen's
wireless/audio parts.

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
