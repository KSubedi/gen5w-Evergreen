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

### FAQ

**Do I need to uninstall HMG-API before running the porting kit?**
No. The kit only reads files, and HMG-API applies its mods in memory without changing files on disk, so the kit still collects your car's stock files. (Port request #1 came from a car with HMG-API's Wireless CarPlay mod installed; every file matched stock.) You *do* need to turn off HMG-API's Wireless CarPlay + Android Auto mod later, before installing a test build, because both change the same apps.

**What if Evergreen, Leafia or Ultimate Wireless Projection is installed?**
Those change the projection apps and libraries on disk, so the kit would collect modified files. Uninstall the package in NEMESIS Manager and restart the head unit before running the kit, or say in your port request that it's installed.

**Does the kit change anything on my car?**
No. It writes only to the USB drive. It never collects logs, accounts, paired phones, Wi-Fi passwords, firmware key files, the VIN or serial numbers.

**Why can't I post `send-privately` on the issue?**
It holds Hyundai/Kia system files (the projection apps, libraries and framework), which aren't ours to publish. They're only used to build and test your car's port, and are never committed to this repository; the repository keeps only small patch files that rebuild everything from your car's own files at install time.

**Do I need to send `send-privately` at all?**
Often not. If your report shows the same firmware files as a car we already have, the build can be made from the public report alone, and the issue will say so.

**My car has no AR HUD (or no HUD). Is it still worth it?**
Yes. Wireless CarPlay and Android Auto, and turn-by-turn on the cluster (and on a regular HUD), work without the AR HUD. Say what your car has in the port request.

**My car isn't electric. Can it be ported?**
Yes. The EV features simply stay off; everything else works.

**My firmware is older than the one listed for my car.**
Update the head unit first with the free Hyundai, Kia or Genesis Navigation Updater. Ports are made for each region's newest firmware.

**The kit didn't finish, or `UWP-PORT` is missing.**
Make sure NEMESIS 1.15 or newer is installed, the drive is exFAT or FAT32 with 200 MB free, nothing else is on it, and the head unit had fully started before you plugged it in. Then run it again.

**How long does a port take, and what happens after?**
It depends on how different your car's apps are. A car whose firmware matches one we already support can get a test build quickly; others need their changes ported and checked first. Either way, you get a test build on the issue, and the drive logs you send afterwards decide when it's marked supported.

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

Pass `--variant <patch set>` (default `ev6`) to both builder steps. Builds exist for the EV6 US 260507 (`ev6`) and the Ioniq 5 US 260903 (`neev23_260903`). The Ioniq 5's CarPlay app needed one adaptation: its obfuscator named the AR fields one letter on, which the build check and `ArHudRoute` now recognise. A new car needs:
- its fingerprints reviewed;
- an entry in `CARS` in `tools/build-carplay-ar-installer.py`, which sets the installer's name and firmware gate;
- its variant added to the verified framework list in `tools/check-carplay-ar-build.py` and to the product patterns in `kits/ar-control` and `kits/ar-finish`;
- its AR patch tree added to the unified installer build (`build-carplay-ar-installer.py --patches <tree>` once per car, all from one commit);
- a first drive with logs, to confirm the HUD's AR types and the arrow timing. The [AR development notes](notes/carplay-ar.md) describe each piece and how it was tuned.

### 4. Test

- `tools/mock/install-test.sh` with the owner's stock files: install, then uninstall must restore every file byte for byte. Run it with `MOCK_SH=mksh`, the head unit's own shell.
- `sh tools/test-carplay-ar.sh` and the other `tools/test-*` suites.
- A real drive: the owner installs the test build, drives, and attaches the logs to the issue. `tools/analyze-ar-log.py` summarises them.

### 5. Submit

Open a pull request with the patch set, the `patches/cars` line, the compatibility entry (`docs/data/compat.json`, status `testing`) and what was tested. The maintainer reviews and merges it ([contribution policy](CONTRIBUTING.md#contribution-policy)); the car moves to `supported` once an owner confirms it on the road.
