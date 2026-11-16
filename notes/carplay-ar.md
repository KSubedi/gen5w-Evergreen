# CarPlay AR HUD for the US EV6

Target: the owner's **2022 Kia EV6 GT-Line, USA, firmware 260507** (`ev6`).
The owner confirmed **1.2.0.101 basic AR works really well**. Version **1.2.0.102**
adds exact distance, bounded interpolation, metadata and progress. The owner
reported an all-day test including three hours of driving: generally worked well,
with highway-exit arrows missing, occasional blank initial guidance until the next
turn, and occasional late small-arrow rotation. CarPlay also froze a couple of
times and recovered by leaving and reopening projection; the owner had observed
that before AR. These reports do not yet establish the individual causes. Normal
Evergreen remains unchanged by default.

## Implemented behavior

The proven basic adapter maps supported projection icons to native turn types
inside approximately 457 m: straight, slight/ordinary/sharp turns, U-turns,
forks, exits and unambiguous roundabout exits. It sends the native angle and
zero side-street bits through the existing `NavigationCluster.updateTBTInfo`
call. Unknown/far/error/native messages pass through. It does not infer arrival
from zero distance or add raw vehicle commands.

The advanced adapter:

- Captures exact meters only when DiPO actually parses iAP2 item 10. A default
  zero field is never treated as a measurement. Raw receipts bind to the current
  maneuver at packet completion, including when distance precedes the maneuver
  list; a new maneuver cannot borrow the previous maneuver's distance.
- Reads the inspected maneuver type, driving side, junction and exit angle from
  the app's own state. Missing/unknown metadata falls back to basic AR. Native
  arrival messages are preserved rather than synthesized from a rounded zero.
- Estimates closing rate from consecutive exact-distance samples. This CarPlay
  parser does **not** expose time to the next maneuver, so destination ETA is not
  used as a substitute. Stationary/increasing distances, implausible rates,
  new maneuvers/components/owners and long gaps reset velocity.
- Sends changing near-turn distances every 250 ms, preserving the cluster's
  original units (m/km/mi/ft/yd). Extrapolation stops after 1.5 s and holds; it
  never runs below zero. Route stop, disconnect and source changes cancel it.
  Five seconds without phone input removes the progress lease.
- Adds a progress overlay inside the navigation app. It copies the latest OEM
  trap frame and changes **only** the bar. The verified legacy argument is p12,
  zero-based index 11; the newer object has 22 public int fields, all copied.
  Speed limit, units, cameras, country and warning fields remain the OEM's.
  Native navigation's original main-turn calls stay unchanged.

The progress path wraps all five existing trap calls in `HKMC_Navi_ExSL`, both
`updateTBTTrapInfo(17 ints)` and `updateTBTTrapInfoNew(NaviTbtTrapInfo)`. On a bar
change it replays the navigation app's latest frame; it never guesses a limit
or reads the cluster's delayed speed-limit echo. With no cached OEM frame, it
waits for one (normally a map speed-limit update). Expiry or disabling advanced
mode restores that frame's original bar once. Frame replacement and OEM updates
serialize, so an old cached speed limit cannot race over a newer one.

DiPO and navigation exchange only a tiny bar lease under the package's own
`ar-state/` directory. The installer owns that directory to system uid 1000,
mode 0755; only DiPO writes, navigation reads a mode-0644 atomic file. DiPO's
stock manifest declares `android.uid.system`; navigation is a separate app uid.
The lease contains a boot nonce, monotonic times, active flag and bar, with no
road names, coordinates or phone identifiers. It expires within five seconds,
requires a heartbeat within three seconds, and is rejected across reboots.
No filesystem sync is performed at navigation cadence: this is expiring runtime
state, not an archive. Optional IPC permission failure logs a warning and retains
the OEM bar while allowing the existing wireless install and smooth AR to run.
The original apps' wireless/audio implementation remains.

Two daemon workers live in the existing app processes. They do not hold wake
locks. The turn worker sends only changed, valid near-turn distances; the bar
worker sends only a changed overlay or restoration, plus the OEM app's own
normal updates. New helper failures use log tag `EvergreenAR` without route data.

## Controls and rollback

The helper is disabled without `ar-hud.enabled`. The explicitly selected
experimental installer includes that file and restricts installation to
`CVEV.USA.*.260507`. Normal Evergreen and private delta generation stay opt-in.
Build USB controls with `python3 tools/build-carplay-ar-controls.py`:

- `enable` / `disable`: change the overall AR flag; disable works even if the
  prototype/target firmware is absent.
- `basic`: sets `ar-advanced.disabled`, returning to the proven icon adapter and
  restoring the OEM bar without removing wireless projection.
- `advanced`: removes that file when schema-2 adapters are installed. If AR was
  globally disabled, use the enable kit too.
- `status`: reports overall state and whether basic mode is selected.

These controls change only package-owned files and do not reboot. Start a fresh
navigation route after a switch. Keep the car HUD in AR mode. The prior
`1.2.0.101` installer remains a full rollback to the owner-confirmed basic build;
normal Evergreen remains another option. NEMESIS uninstall restores stock.

## Build and verify

Use Python 3.9+, JDK 17, smali/baksmali 2.5.2 with all dependencies, host lpatch,
zip/unzip, and `pip install --require-hashes -r tools/requirements.txt`.
The head-unit mock needs qemu-arm-static and preferably mksh. R8/D8 8.3.37 is
pinned in CI (SHA-256 `900dfbc649519969fc5a4c7520d6b7355338e565fa1249874e0190b8d61b1199`).

```sh
cc -DHOST -O2 -o tools/lpatch/lpatch-host tools/lpatch/lpatch.c
D8_JAR=/private/tools/r8.jar sh src/carplay-ar/build.sh
sh tools/test-carplay-ar.sh
python3 tools/build-carplay-ar-advanced.py \
  --stock /private/EV6-AR-STOCK/stock --inspect --out /private/ev6-advanced-plan.json
# Review method/API fingerprints, register shapes and all five navigation call sites.
python3 tools/build-carplay-ar-advanced.py \
  --stock /private/EV6-AR-STOCK/stock --plan /private/ev6-advanced-plan.json \
  --out /private/evergreen-advanced-ar
python3 tools/build-carplay-ar-installer.py \
  --patches /private/evergreen-advanced-ar/patches --enable-ar
MOCK_SH=mksh MOCK_NEMESIS=1.15 sh tools/mock/install-test.sh \
  dist/Evergreen_EV6_US_260507_AR_1.2.0.103.zip \
  CVEV.USA.0000.V015.012.260507 cvev_us /private/mock-stock
```

The builder verifies all stock inputs, reconstructs and hash-checks the original
Evergreen APKs, deodexes against the full ordered framework (Dalvik API **18**),
and verifies field/API layouts. It modifies both DiPO variants and one navigation
build, assembles each twice, disassembles again, then verifies delta round trips
and final APK hashes. No platform/OEM compile stub is included in the DEX.
Only deltas, fingerprints and reports leave temporary private storage. The
committed stock patches and compatibility data stay unchanged.

Schema-2 build/deployment reports record all three deltas and framework hashes.
The labelled installer preserves existing services, adds an early firmware gate,
creates only its dedicated IPC directory, and delegates to Evergreen's existing
checksum-gated install/uninstall. Every final descriptor is validated **after**
all overrides. NEMESIS accepts numeric versions: the first `1.2.0-ar.1` package
was rejected before install; a regression check prevents that error recurring.

For a basic-only private delta build, `tools/build-carplay-ar.py --variant ev6`
retains the schema-1 inspection-plan workflow. It does not wire advanced hooks.

## Validation and remaining car checks

The owner's collection supplied all 12 known app/library files with matching
US EV6 260507 hashes plus the full framework. They remain private. The API
inspection confirms the nine-argument main turn call, legacy bar index 11,
all 22 trap-object fields, six DiPO adapter methods and five navigation trap
sites. Original native-navigation main-turn calls are preserved.

Synthetic tests (1,036 Java checks and 28 Python integration cases) cover
distance units/rate bounds, missing or partial metadata,
packet ordering, resets, stale/out-of-order data, source/owner/maneuver changes,
boot-bound IPC, every trap field, restoration of the OEM bar, API/register
mismatch rejection, three real DEX/delta round trips from synthetic inputs,
package modes, firmware gates, final numeric metadata and stale build plans.
They contain no manufacturer implementation.

Real-input mock full-mode install/uninstall uses mksh and the actual ARM lpatch
under qemu. The apps/libraries/framework are real; uncollected build.prop/Wi-Fi
configuration and codec-presence checks use marked synthetic fixtures. Uninstall
restores that tested system tree byte for byte. The mock dexopt is a stub: it
cannot verify Dalvik runtime behavior or the physical HUD.

The owner reported a generally successful three-hour drive with 1.2.0.102.
Detailed acceptance for 1.2.0.103 remains pending: stationary arrows, smooth normal
turns, forks/exits/roundabouts, rerouting, consecutive maneuvers, navigation app
switches, route ending/disconnect, basic/disable controls, and speed-limit/camera
changes while the bar is active. Do not infer advanced success from the confirmed
basic build. Keep development on this branch until those checks pass.

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

## Follow-up from the 1.2.0.102 road test

Code inspection identified investigation targets, not verified fixes:

- AR conversion is capped at 457 m for every maneuver. Only exit icons 81/82
  have direct exit conversion; the broader projection exit/ramp family needs
  individually verified semantics before adding mappings. Existing raw metadata
  conversion also recognizes maneuver types 22/23. Missing highway AR is not
  yet attributable to one of these paths without the actual input values.
- The adapter wraps the final main-turn call. DiPO can return before that call
  when route/current-maneuver/display-distance state is incomplete. A startup
  fix must handle partial packet ordering without borrowing a previous route's
  state or guessing a maneuver.
- DiPO sends the small HUD arrow via sendHud after the wrapped main-turn call.
  The 250 ms worker updates main-turn distance only; it does not replay the
  separate HUD turn-list path. Determine which state changes trigger sendHud
  before changing its cadence.
- Projection freezes predate AR per the owner. Recovery by reopening projection
  alone does not distinguish a rendering/session stall from a callback block.
  Keep diagnosis separate and check that AR adds no blocking cycle.

Navigation app and whether initial blank means all guidance versus large AR only
are awaiting clarification. The initial investigation preceded the 1.2.0.103
refresh fix described below.

Host callback-order replay and its limitations are documented in
[`src/carplay-ar/replay/README.md`](../src/carplay-ar/replay/README.md).
The comparison mode now executes the production refresh routine through a
synthetic private sender callback; it does not execute the OEM sender itself.

## 1.2.0.103: refresh after maneuver details

The builder now fingerprints DiPO's `Al` route map and sixth method,
`u.d(Object)`, and inserts a normal-return callback. With advanced AR enabled,
it finds active routes whose current maneuver is present and re-enters the
verified private `a(Integer,Object)` sender. That sender retains all original
readiness checks and sends both the main turn and the small HUD list. No phone
restart/request, new vehicle command, distance mutation or freshness reset is
added. The adapter holds no metadata monitor while invoking the OEM sender.
Layouts above 16 components are skipped, and reflection failures leave the
normal route-update path available. Basic mode disables the extra refresh.

The highway investigation remains open. Inspection of this firmware's raw
22/23 maneuver branches shows icons 82/81, already supported. Some broader
exit/ramp icons are not mapped, but those alone do not establish this failure.
There is no verified reason yet to widen the 457 m activation window or turn
an exit into an ordinary street turn. The new rate-limited EvergreenAR trace
records conversion state (numeric metadata and distance only). The separate
`Evergreen-AR-diagnostics.zip` kit exports only that log tag to USB; it does not
collect stock apps, general logs, phone IDs, names or coordinates. Native exit
output 17/18 in the log proves the software sent that representation, not that
the physical HUD rendered it. Kernel/logcat buffer retention remains limited.

## Apple Maps follow-up after 1.2.0.103

The owner reports improvement, but an instruction can remain at straight/10 ft
while Apple Maps advances to an exit one mile away; highway exits can be absent
entirely. Large ordinary-turn AR appears before intervening side streets. No new
diagnostic trace has been supplied for these incidents.

Inspection finds another gate before our final-call adapter: the stock sender
returns for icon zero. Its raw 22/23 exit branches require traffic side, junction
and optional exit angle all zero; a nonzero exit angle goes to the unmapped
result. Raw keep-left/right 13/14 are also absent from the original switch, even
though our metadata converter understands them. The details-refresh fix alone
cannot repair a maneuver rejected inside the re-entered sender. This is a
verified code limitation, not confirmation of the actual phone packet.

The synthetic straight-to-rejected-exit replay demonstrates old main/HUD state
remaining when the new maneuver has icon zero. Timer expiry removes the progress
lease and stops sending but does not clear that separate HUD list. A recognized
far exit replaces the old instruction in the model, and a recognized near exit
produces native type 17. Physical HUD rendering is still outside this model.

A subsequent fix should recover supported raw metadata before the zero-icon
gate, invalidate old smoothing and displayed state on maneuver replacement, and
keep ordinary small guidance active before large street-turn AR. A shorter
street-turn activation distance (approximately 60-90 m / 200-300 ft) is a policy
option, not an ability to detect passed side streets. Parsed junction-element
angles describe the target junction; this feed has no verified catalogue of all
intervening intersections or road geometry. Highway preparation cues should not
be delayed merely because ordinary street-turn AR needs a shorter window.

No production changes or new installer are included in this investigation commit.
