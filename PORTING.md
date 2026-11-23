# Porting Ultimate Wireless Projection to your car

Ultimate Wireless Projection has been tested on the Kia EV6 (US, head-unit firmware 260507). The same Gen5W head unit runs in many Hyundai, Kia and Genesis cars, so most of the work can carry over. This guide is in two parts: what an **owner** does (about five minutes, read-only), and how a **developer** turns that into a build.

## For owners: request a port

You need [NEMESIS](https://github.com/xwtk/hmg_nemesis) 1.15 or newer on the head unit, and a USB drive (exFAT or FAT32) with at least 200 MB free.

1. **Download** `UWP-Porting-Kit.zip` from the [releases](https://github.com/KSubedi/ultimate-wireless-projection/releases) and unzip everything in it onto the empty USB drive.
2. **Run it.** Park with the car on (in READY for an EV or hybrid) and wait until the head unit has fully started. Plug in the drive and approve the NEMESIS prompt.
3. **Wait** for "Porting data saved" (1–2 minutes), then unplug the drive.
4. **File the request.** On a computer, zip the `UWP-PORT/attach-to-issue` folder from the drive and attach it to a [port request](https://github.com/KSubedi/ultimate-wireless-projection/issues/new?template=port-request.yml). The form asks for your car, whether it has a HUD (and whether the HUD settings offer an AR mode), and what you'd like.
5. **Keep `UWP-PORT/send-privately`.** It holds your car's stock Hyundai/Kia system files, which the build is made from. Don't post them publicly. The maintainer replies on your issue with how to send them privately, or says they aren't needed because your firmware is already known.

The kit changes nothing on the car. It never collects logs, accounts, paired phone data, Wi-Fi passwords, firmware key files, the vehicle security partition, the VIN or serial numbers.

### What the kit collects

| Folder | Contents | Share |
|---|---|---|
| `attach-to-issue/` | `LEAFIA-REPORT.txt`: firmware ids, checksums of the projection apps and libraries, the cluster calls the firmware offers, and feature flags. `summary.txt`: NEMESIS and package state, hotspot state. | Public, on the issue |
| `send-privately/stock/` | The projection apps (CarPlay, Android Auto, iAP2, navigation, Bluetooth settings), the CarPlay and Android Auto libraries, the framework, feature files and `build.prop` | Privately, when asked |

### What happens next

1. The report shows whether your car runs the same projection code as a car we already support. If it does, a build can often be made from the report alone.
2. Otherwise the maintainer ports the changes from your stock files and tests the result on a simulated head unit made from them. It must install and uninstall back to byte-for-byte stock before it reaches your car.
3. You get a test build to install from USB and drive with. The same drive saves the logs afterwards; attach them to your issue ([how](README.md#report-a-problem)).
4. Once a build has worked on a real car, it's listed as supported.

What to expect per feature:

- **Wireless CarPlay and Android Auto, turn-by-turn on the cluster and HUD:** most Gen5W cars with built-in navigation and a Wi-Fi module.
- **AR HUD:** cars whose HUD offers an AR mode. The AR arrows use the HUD's own graphics, so the car must have the AR HUD hardware.
- **EV features:** Hyundai Motor Group EVs on the E-GMP platform (EV6, Ioniq 5, Ioniq 6, GV60 and others) with Android Auto and Google Maps.

Trims without built-in navigation have a different head unit ("Display Audio") that NEMESIS doesn't support. Only each car's newest head-unit firmware for its region is ported; update with the free Navigation Updater first.

## For developers: build a port

Read [CONTRIBUTING.md](CONTRIBUTING.md) first: setup, the mock head unit and the safety rules apply to every port. Stock files stay private: never commit them or full modified apps.

### 1. Read the report

From `attach-to-issue/LEAFIA-REPORT.txt`:

- `prop ro.build.product`: model, region and firmware (for example `CVEV.USA.0000.V015.012.260507`).
- `file` lines: checksums of the projection apps and libraries. Identical checksums to a supported car mean identical files.
- `code` lines: the code inside each app's `.odex`. Matching code on a different firmware means the **universal build** applies (`patches/universal/`), with no port needed.
- `api` lines: which cluster calls the firmware offers. Turn-by-turn needs all of them.
- `prop ro.product.opt.hud.artist` and the owner's answer tell you whether there's an (AR) HUD.

### 2. Port the wireless and turn-by-turn changes

With the owner's `send-privately/stock` folder:

```sh
python3 tools/port.py --variant <name> --prefix <PRODUCT_PREFIX> --name "<Car name>" \
    --new-stock <stock> --new-fw <stock>/framework \
    --ref-variant ev6 --ref-stock <ev6 stock> --ref-fw <ev6 framework> \
    --ref-builds <ev6 builds> --out-builds <private builds dir>
```

It compares the new car's apps with the reference car's class by class, carries our changes over, and checks every reference and system call against the new framework. It writes `patches/<name>/` and the car's line in `patches/cars`, or stops and lists the classes a person has to merge. Details: `tools/port.py --help` and [patches/README.md](patches/README.md).

### 3. Port the AR HUD

The AR helpers (`src/carplay-ar/`) are added to the rebuilt CarPlay app (DiPO), the Android Auto app (GPM) and the navigation app. `tools/build-carplay-ar-advanced.py --inspect` checks the stock apps' method and API fingerprints, register shapes and all navigation call sites against what the helpers expect:

```sh
D8_JAR=<r8.jar> sh src/carplay-ar/build.sh
python3 tools/build-carplay-ar-advanced.py --stock <stock> --inspect --out <plan.json>
python3 tools/build-carplay-ar-advanced.py --stock <stock> --plan <plan.json> --out <private out>
python3 tools/build-carplay-ar-installer.py --patches <private out>/patches --enable-ar
```

Today these tools are gated to the EV6 (US, 260507): the fingerprints, the installer's firmware gate and the AR turn tables were made for it. A new car needs its fingerprints reviewed, the gate extended, and a first drive with logs to confirm the HUD's AR types and the arrow timing. The [AR development notes](notes/carplay-ar.md) describe each piece and how it was tuned.

### 4. Test

- `tools/mock/install-test.sh` with the owner's stock files: install, then uninstall must restore every file byte for byte. Run it with `MOCK_SH=mksh`, the head unit's own shell.
- `sh tools/test-carplay-ar.sh` and the other `tools/test-*` suites.
- A real drive: the owner installs the test build, drives, and attaches the logs to the issue. `tools/analyze-ar-log.py` summarises them.

### 5. Submit

Open a pull request with the patch set, the `patches/cars` line, the compatibility entry (`docs/data/compat.json`, status `testing`) and what was tested. The maintainer reviews and merges it ([contribution policy](CONTRIBUTING.md#contribution-policy)); the car moves to `supported` once an owner confirms it on the road.
