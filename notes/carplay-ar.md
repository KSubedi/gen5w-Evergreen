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

## 1.2.0.104 diagnostic drive build

Recovers supported raw maneuver types before the stock zero-icon gate. It
fingerprints ten DiPO methods and the display-distance field in addition to the
existing exact receipt. Display/exact receipts bind to the current maneuver at
packet completion, so a replacement cannot borrow the old rounded distance.
The callback clears previous main/small HUD state and motion on replacement,
using the existing OEM clear path, without sending raw vehicle frames. Large
ordinary-turn AR is delayed to 75 m; far small cues and highway conversion
windows remain available. Unknown metadata stays unmapped rather than guessing.

ArHudDebug (eleventh helper) records the navigation flow with monotonic times,
sequence and dropped-event counts, at most 50 events/sec. A NEMESIS root service
streams only EvergreenAR warning records to eight rotating 8 MiB files in
`/data/nemesis/wireless.carplay.aa-ar-debug`. Route/maneuver callback boundaries,
parser receipts, selection, outgoing main/HUD calls, progress IPC/overlay, reset
and heartbeat records are included; no phone identities, road names, geometry,
raw messages, general crash logs or video are collected. API return does not
confirm cluster/HUD rendering. The export kit copies logs plus build metadata
to AR-DEBUG on USB, then syncs. These logs are bounded, not permanent retention.
This is a car-test candidate; the unchanged prior release remains the rollback.

Host validation: 1,055 Java assertions and 29 Python integration cases, including
logger rate limit/payload preservation, rotation-service invocation, persistent
log export, zero-gate recovery and exact/display replacement handling.

## Finish diagnostic logging after a drive

`tools/build-ar-finish.py` builds the separate `Evergreen-AR-export-stop-clean.zip`
for an installed 1.2.0.104. It replaces only our fingerprint-matched recorder
script with an idle script, stops the NEMESIS recorder service and its isolated
process group (NEMESIS uses setsid; stopping just the shell can leave logcat).
It copies and verifies source/destination MD5s, syncs, rechecks all log copies,
then deletes only those verified internal flow/status logs. USB failure, checksum
mismatch, sync failure or unconfirmed stop preserves internal logs. AR and OEM
files stay unchanged. The idle script prevents capture returning at reboot;
reinstalling a diagnostic build may re-enable it. Android's normal bounded
in-memory logcat is not disabled or cleared. Output is AR-DEBUG-FINISHED on USB;
wait for Export and cleanup complete before removing it. Host tests cover success,
repeat, corruption, unknown recorder, stop/flush failure and process-group syntax
under sh and mksh. Physical-head-unit execution remains to be verified.

## First diagnostic-drive findings

The private 104 export passed all seven recorded file MD5 checks and reported
successful recorder stop and internal-log cleanup. Parsed own-tag records cover
two head-unit uptime epochs; that alone does not establish a crash. No raw logs
are committed.

One current maneuver with raw code 8 and exit angle 0 was rejected by the zero
gate 78 times despite valid exact-distance updates from 1,100 m to 2 m. The
recovered icon stayed zero and the sender returned before main/HUD calls. This
establishes a remaining mapping gap; the earlier angled-22/23 hypothesis was
not the code observed in this segment. Raw 13 recovered successfully 38 times,
including native fork output. A sanitized replay now preserves this distinction.

All 675 recorded ordinary-turn native sends were at or below 74.97 m, consistent
with the 75 m policy. Fourteen maneuver-replacement clear sequences were recorded.
This demonstrates software sends, not physical HUD acknowledgement. All 1,531
recorded main-send attempts had paired returns (longest 263 ms); one last HUD
return was absent amid the logger's dropped-event burst. There were 17 five-second
input-expiry events, which cannot alone distinguish a stopped phone update from
stationary/paused guidance. No own-tag exception/failure was recorded.

Diagnostic limits: 145 dropped records in the first DiPO epoch and 50 in the
second. Critical-transition tracing should be protected from burst sampling.
The rotated file was about 1 MiB despite requesting 8 MiB; effective device
rotation limits need confirmation, so the 64 MiB configuration is an upper-bound
estimate rather than observed retention. The trace label 'unmapped' also includes
ordinary turns deliberately deferred between 75 m and 457 m; these are not
unknown maneuver types. These are investigation notes, not a new installer.

## 1.2.0.105 test build

Street-turn native AR begins at 125 m (about 410 ft), an intermediate setting
between the original 457 m and the 104 build's 75 m. Small cues remain visible
earlier. The captured raw-8 gap now recovers to generic exit icon 86, a
projection exit representation already used for direction-unspecified off-ramps
in the documented cluster protocol. Distance continues updating; no left/right
AR direction is synthesized from traffic side or absent geometry. Explicit
directional exit/fork recovery and maneuver-replacement invalidation remain.

The captured code-8 replay now asserts delivery to both main and small HUD
paths down to 2 m. The boundary test checks deferred street AR at 126 m and
native AR at 125 m. Logs distinguish deferred street AR from unmapped turns;
important transitions get a separate bounded 100-event/sec budget in addition
to routine 50/sec tracing. Recorder rotation explicitly requests 1 MiB files
and 31 backups (32 MiB total), matching the size observed in the private export.
AOSP KitKat's logcat accepts a configurable integer backup count; this vendor
unit's behavior with 31 backups still needs confirmation.

The finish kit now checks the installed version and known 104/105 recorder
fingerprints, retaining copy verification and cleanup failure guards. Use the
new finish kit after the test drive, since the old kit only recognized 104.
These changes are host-tested test candidates; physical generic-exit rendering
and the timing preference need the owner's drive feedback. No freeze fix is
claimed from the navigation-only trace.

## 1.2.0.106 test build

Read from the 104 drive export (two head-unit epochs, 15,158 own-tag records,
1,129 exact-distance receipts at a steady 1 Hz) rather than from new hypotheses:

- **Blank start of every route.** The first maneuver of both routes was raw type
  11 (CarPlay's start-route instruction) in route state 6 (proceed to route),
  rejected by the zero-icon gate 45 times over 16-26 s. `recoverIcon` now maps 11
  to the straight cue 65; `cueOnly(11)` keeps it out of large AR conversion.
- **All 17 five-second expiries were a standing car.** Distances before and
  after each gap were equal (e.g. 12 m, silent for 31 s); eight were inside the
  street window, i.e. waiting at the light to turn. The phone sends nothing
  while stationary. The lease now follows the route: maneuver change, route
  stop, source change and disconnect end it as before; `ArHudIpc.HOLD_MS`
  (300 s) only catches a stuck sender. The 3 s publish heartbeat still covers a
  dead DiPO.
- **Bar scale.** All 675 street-turn sends in 104 carried bar 10 because `bar()`
  divided by 457 m while street AR started at 75 m (125 m in 105). Street turns
  now fill the bar over their own window (`bar(meters, street)`), 90 down to 10
  as the stock navigation does; exits/forks/roundabouts keep the 457 m scale.
- **Backward steps between phone samples.** 36 % of phone samples that followed
  an extrapolated tick moved the distance back up, median 1.3-2.2 m, up to 10 m,
  because a car approaching a turn is slowing. Replaying the captured samples
  offline: full-rate projection mean error 2.4 m with 53 % back-steps; 0.8x
  projection 2.7 m with 28 %. `ArHudMotion` now projects with gain 0.8, and
  `send()` holds the sent distance when a sample is above it by at most 15 m
  (`backstep_meters`); larger increases (reroute, backing up) go through.
- **Rerouting.** DiPO parses iAP2 item 1 of RouteGuidanceUpdate into route
  state (`aR`): 1 guiding, 2 arrived, 3 loading, 5 rerouting, 6 proceed to
  route in this log. During state 5 the runtime now passes the OEM frame through
  unchanged with no smoothing, instead of animating a maneuver that may be gone.
  Both observed reroutes lasted under a second.
- **Diagnostics for the next decisions.** DiPO also stores item 2, the phone's
  maneuver state (`aS`, logged `mstate=`, never used by the stock app) and the
  junction element angles (`DT`, logged as a count `angles=`). If `mstate` is
  the expected continue/initial/prepare/execute signal it can replace the
  hand-tuned street window later; the angle count shows whether generic exits
  (raw 8) carry geometry that could give them a side. Logged only.
- **Tuning without reinstalling.** `ArHudTuning` reads `ar-tuning` next to the
  flags (key=value: `street_ar_meters` 30-457, `gain` 0.5-1, `backstep_meters`
  0-50; anything else keeps the default). `tools/build-carplay-ar-controls.py
  --street-ar-meters 90 --gain 0.7` builds `Evergreen-AR-tune.zip`; `tune-reset`
  removes the file. Values take effect within two seconds.
- **Trace gate.** Navigation-cadence tracing runs only while `ar-trace.enabled`
  exists. The diagnostic installer creates it (`--no-trace` omits it); the
  `trace-on`/`trace-off` kits switch it. The once-per-second `turn` records and
  warnings stay on.
- **Stock-navigation baseline.** A new idle NEMESIS service (`svc/ar-baseline.sh`)
  waits for `ar-baseline.requested` from the `baseline` kit, then records the
  cluster service's last turn/trap frame lines from `dumpsys com.hkmc.micom.cluster`
  once a second for the requested minutes (max 60, 4 MiB) into the debug
  directory, dropping turn-list road-name lines. Drive the same route with the
  car's own navigation and export with the diagnostics or finish kit to get the
  stock exit types, start distances and bar cadence for comparison.

Host validation: 1,102 Java checks and 41 Python cases, including the two new
sanitized replays (`observed-route-start.csv`, `stationary-then-reroute.csv`),
tuning parse/range/file tests, the trace gate, the baseline service with a
stub `dumpsys`, control-kit validation of tuning input, and the finish kit on
104/105/106 recorders. In the 104 log the patched sender returned within 12 ms
at the 99th percentile (one 290 ms outlier), so AR is not blocking the CarPlay
callback thread; the freeze question stays open. Physical HUD rendering, the
`mstate` meaning and the preferred timing need the owner's drive feedback.

## 1.2.0.107 test build

Polish and overhead items found while reading the 104 export, plus the tooling
to read the next one:

- **HUD distance glides with the arrow.** DiPO's `sendHudDist(float, int)` only
  uses the cluster reference and its arguments (integer part plus tenths for
  km/mi, then `updateTBTSubInfo2C`), and the log shows it carries the same
  value and unit enum as the main turn (0 mismatches in 611 pairs). Its entry,
  which we already hook, now passes through `ArHudRuntime.hudDistance`, so the
  OEM's own call carries the exact distance while a maneuver is active; the
  4 Hz worker re-enters the method reflectively when the displayed number
  (whole feet/meters, tenths of a mile) would change. Unavailable layouts log
  once and keep the OEM cadence. Cue-only maneuvers keep the OEM value.
- **No blank between consecutive maneuvers.** The zero main frame and HUD blank
  on replacement are deferred to the end of the sender callback and skipped
  when the new maneuver's main frame or HUD list was sent in that callback
  (13 of 14 replacements in the log followed within 5-10 ms). When nothing is
  sent, the clear happens as before (`clear_previous_main`/`clear_previous_hud`
  vs `clear_skipped`).
- **Overhead.** `ArHudIpc.enabled()/advanced()` cache the two flag files for one
  second instead of stat-ing them on every 250 ms tick in both processes; the
  forwarder uses the same cache. Trace text at hot call sites is built only
  when `ArHudDebug.on()`; `ipc_*`, `worker_alive` and `hud_distance` records
  have their own 20/s budget so they cannot crowd out transitions.
- **`tools/analyze-ar-log.py`** reads an AR-DEBUG(-FINISHED) export (rotated
  files and recent logcat, de-duplicated) and prints per-process cadence, gate
  counts, send latency, expiries with their silent context, back-steps,
  replacement timing, tuning in effect, maneuver segments with the phone's
  maneuver states, and the once-per-second turn records; `--json` for the full
  report, `--replay DIR` for sanitized replay traces (`details`/`packet`/`route`/
  `tick`/`stop`, numbers only) that run through `tools/replay-carplay-ar.sh
  --live`. The 104 export replays end to end that way.
- The replay harness gained a `packet` event (maneuver list and distance in one
  update, as DiPO receives it) and records the HUD distance.

Host validation: 1,127 Java checks and 44 Python cases. Roundabouts from raw
exit angles and directional generic exits remain deferred until a 106/107 log
shows the angle data; use the analyser's `mstate` and `angles` output.

## 1.2.0.108 test build

Read from the 107 drive export (25 minutes, 12,539 records, 770 exact receipts
at 1 Hz, no expiries, 13 of 14 replacements with the clear skipped, send latency
p99 1 ms). The owner reported two things: highway exits showed a "continue on
this road" icon, and the large AR arrow rotated further than the real distance.

- **Exits.** Both exits of the drive were CarPlay type 8 (direction-unspecified
  off-ramp) with exit angle 0; the junction angle array had one element on one
  of them and was absent on the other. The stock sender's switch maps CarPlay's
  sided exits (22/23) to icons 82/81 and forks (52/53) to 174/173, and its
  US-region filter then refuses those codes ("not send by navi": 73, 76, 81,
  82, 84, 85, 87, 88, 90, 91, 161, 162, 172-174, 177, 183, 184 and the
  clockwise roundabouts), so no exit ever reached the cluster on this firmware;
  type 8 is not in the switch at all. The generic icon 86 we recovered to in
  106/107 passes the filter but is drawn as "continue on the road". 108 gives
  type 8 a side (`ArHudTurns.exitSide`): the exit angle's sign when it is
  non-zero, else the traffic side (right in right-hand traffic). Far: icon 81/82
  in the main frame and the HUD list; within 457 m: native `EXIT_RIGHT`/`EXIT_LEFT`
  with the 457 m bar. `exit_side=auto|right|left|generic` overrides it. 81 and
  82 are the codes HMG-API's mod sends on US Ioniq 5s; this is their first use
  on the EV6.
- **Arrow rotation.** The distance we send is the phone's exact distance,
  0-3 m behind it between samples (0.8x projection, one back-step in 152
  samples), so the number is not ahead of reality. What changed since the 104
  drive, which had no such report: the bar (constant 10 within 75 m then; 90 to
  10 over the 125 m street window in 106/107) and the start of the large arrow
  (75 m then, 125 m now). The cluster service packs the bar into the turn frame
  (one nibble, `round(bar/10)`, next to the distance's tenths digit), so the AR
  unit does receive it. 108 puts the bar back on the stock scale, 90 to 10 over
  457 m (`bar_meters`, default 457), which is what every stock approach sends,
  and adds `turn_offset_meters` (0-60, default 0) that moves only the large
  arrow's distance so the owner can test the placement hypothesis in one drive
  with the ready-made tune kits (offset 15/30, bar 125, street 75). The HUD
  number and the lease keep the phone's distance. A stock-navigation baseline
  capture on the same roads (the baseline kit; its `dumpsys` filter matches
  the service's "TBT Info" / "Distance" / "BarGraph Level" labels) would settle
  which input the AR unit animates from.
- Turn records add `angle_values=` (the phone's junction element angles, at
  most eight), `extra=` (its item 12), `exit_side=` and `offset=`; the icon
  gate record adds `exit_side=`. The analyser keeps the side and angle values in
  its turn-record summary. `DV` joins the verified field list.

Host validation: 1,156 Java checks and 44 Python cases; the 107 export replays
through the analyser unchanged.

## 1.2.0.109 test build

The 108 road test failed and its export did too, so this build was made
without logs. The owner's report: on the first route neither the AR turn nor
the small HUD started, and CarPlay did not reconnect after toggling the phone's
Wi-Fi and Bluetooth; after a head-unit restart the exit arrow and sign appeared
(the exit was close), and that picture then stayed until navigation was
stopped, while speed and speed limit kept updating. The export "failed" with
no reason visible; the 108 kit's version pattern and recorder checksum were
right, so the likely causes are the 107 kit (which rejects 108) or the nested
controls zip.

What is known and what is not:

- 108 was the first build to hand this cluster icon 81 (far main frame and the
  small HUD list, including while the native sign was up) and native
  `EXIT_RIGHT`. Icon 81 is on the stock US app's refused list. A cluster that
  ignores the frame explains both observations: nothing at the start of a route
  whose first maneuver is a far exit, and, because the deferred clear is
  skipped when the next maneuver's frame was *sent*, the previous picture
  staying when the next maneuver is again a far exit. That is a hypothesis.
- The other candidates are the AR unit holding the native exit until a blank,
  and the phone callback stalling. The helper has no lock-order inversion with
  the sender's monitor, and the cluster service's `updateTBTInfo` only packs
  bytes and posts a message. The 108 logs on the head unit (PIDs, `seq`
  restarts, `worker_alive input_age`, `main_sent` types) decide between them.

Changes:

- `recoverIcon` never returns 81/82: raw 8 (and 22/23 when the stock table
  gives icon 0) become 66/71, or 86 with `exit_side=generic`. A test walks all
  raw values and sides.
- `exit_style=slight|sign` (default `slight`). Slight: native `TURN` at 45/315
  degrees inside the full 457 m window (the street window does not apply to
  exits), bar on the 457 m scale. Sign: native `EXIT_RIGHT`/`EXIT_LEFT` inside
  457 m with icon 66/71 far and in the HUD list, so the sign can be tested
  without the refused icons; a replaced exit sign is cleared in `prepare()`
  before the next maneuver's frame instead of through the deferred clear.
- Lease expiry (`HOLD_MS`) now sends the type-0 main clear and the stock
  `sendHudBlank` once, outside the runtime lock.
- `ArHudDebug` is the process's default uncaught-exception handler in front of
  the existing one (class and method names only, no messages), installed at
  the first `prepare()`. The worker logs the sender thread's state and top
  frames once when active input has been quiet for 15 s. No new class: the
  helper count stays twelve.
- Finish kit: versions 104-109, the 108 recorder checksum, an early plain copy
  to `AR-DEBUG-SNAPSHOT` (removed again after a verified export), the failure
  reason in `AR-EXPORT-ERROR.txt`, and a service status that cannot be read no
  longer aborts the run (the service is still stopped by name; a recorder that
  keeps writing fails the checksum comparison and nothing is deleted).
- The analyser reports the crash and quiet-input records and the exit style.

Not proven on the car before this build: icons 66/71 and `TURN` at 45/315
degrees (both stock-sendable), and the expiry clear.

## 1.2.0.110 test build

The 108 export arrived after 109 was handed over. It replaces the guesswork in
the 109 section above. All times are head-unit uptime; nothing was dropped
from the trace and there is no crash record.

- First route (first boot): the projection app's sender received no route or
  maneuver callback at all for eight minutes. The only helper activity is a
  `reset` with the stock HUD blank at three moments that match the owner
  switching the phone's Wi-Fi/Bluetooth. The navigation process kept seeing
  speed limits, so the car was driving. The helper had nothing to convert.
- Second route (after the head-unit restart): 28 s after boot raw 8 arrived
  with exact distances 447, 443, 424 and 410 m inside 1.7 s. The gate
  recovered icon 81, the worker sent native `EXIT_RIGHT` sixteen times down
  to an interpolated 384 m and the HUD list got icon 81 once. The owner saw
  the arrow and the proper exit sign, so the native exit type renders on
  this car. Then no phone input for 540.7 s, while speed limits show the car
  driving for another three minutes. The sender's callback had returned
  normally and the worker stayed alive; the 300 s lease expired, but 108 sent
  no clear on expiry, so the sign stayed. The next phone input was a new
  guidance start (state 6, raw 11) while parked; stopping navigation then
  blanked the HUD.
- So the frozen picture was lost phone input with nothing to clear it, not
  the cluster ignoring icon 81. Why the phone's guidance stopped arriving
  (twice on that drive, never on the 107 drive two hours earlier) is not in
  the logs: 108 had no record of the sender thread's state. 109 and later log
  it after 15 quiet seconds (`input_quiet`).

Changes:

- `ArHudMotion.passedAt()`: when the last two exact samples gave a closing
  rate of at least 12 m/s (both the smoothed speed and the last interval),
  the maneuver counts as passed at the last sample plus twice the remaining
  time plus 10 s, at least 20 s. `tick()` then runs the normal expiry: lease
  off, type-0 main clear, stock HUD blank, once. On the 108 sequence that is
  48 s after the last sample. In the 104 and 107 drives every one of the 31
  silences longer than 10 s followed samples at 1.7 m/s or less, so the rule
  would not have fired on any recorded stop; a hard stop from speed inside
  one phone interval would be cleared and comes back with the next sample.
- `exit_style` defaults to `sign` again; `slight` stays available. Icons
  81/82 stay unused (far and HUD list are 66/71), which is the one part of
  the 108 picture that 110 does not reproduce: 108's list entry was icon 81.
- `timer_expired` records carry `passed=` and are critical records, so the
  rate limiter cannot drop them. The crash handler prints the stack trace
  when there is no previous handler (host tests only).
- Finish kit and recorder checksum list: 104-110.

Not proven on the car: the moving-loss clear, and the sign together with the
66/71 list icon.

## 1.2.0.111 test build

The 110 export (35 minutes, city streets and highway; the owner: "worked
really well", three requests). The DiPO process logged 26,968 records with
1,636 exact samples at 1 Hz; no `timer_expired`, no moving-loss expiry, no
crash record, `worker_alive` never late, callback time p50 5 ms / p99 14 ms.

- Street turns: far icon 67/70, `street_ar_deferred` from about 450 m, native
  `TURN` from 111-125 m with bar 20, then 10 below 91 m. The large arrow was
  therefore up for 7-12 s and the bar moved one step. Owner: the rotating
  arrow comes up only briefly, and it zooms only right before the turn.
- Forks (raw 14, type 25) from 446 and 406 m and exits (raw 8, native
  `EXIT_RIGHT`) from 452 and 446 m, each with continuous data down to 0 m
  and the bar from 90. Raw 12 (icon 112) stays `unmapped`.
- A main-thread `reset` is the stock `u.nS()` ("stopTurnByTurn"), reached
  from `e/b.N()` when `DipoService` sees the phone's turn-by-turn state go
  inactive. It is the phone ending guidance, not the driver leaving the
  CarPlay screen. On this drive: 16:40:01 (guidance ended and restarted while
  parked) and 16:53:53 (arrival).
- "CarPlay got stuck a couple of times, fine after leaving CarPlay and coming
  back": the records show no gap in the phone's guidance or in the DiPO
  process while the car moved. The 100 s without a distance change at
  16:27:56 was a stop 55 m before a turn (three `input_quiet` records, sender
  idle in its message queue). The one stretch that looks like a driver
  without a working map is 16:35:34-16:37:27: seven reroutes in two minutes,
  each new first turn driven past, then a two-minute stop and the guidance
  restart above. That reading is an inference; the trace has no record of
  the picture. The freeze with the same recovery predates AR (see the first
  section). Nothing here shows our code involved, and nothing rules out a
  cost we add; the health record below is there to find out.

Changes:

- `street_ar_meters` default 200 (was 125): 11-19 s of large arrow at
  40-65 km/h. The original 457 m was reported as showing before intervening
  side streets; 125 m is one kit away (`tune-street-125`).
- The bar keeps the 457 m scale, which inside 200 m gives 40, 30, 20, 10
  (steps at 183, 137 and 91 m). The reports so far: bar 90-10 over 125 m
  (106/107) "rotates too far, looks closer than it is"; bar 20-10 (108-110)
  "zooms only right near the turn". Which input the AR unit animates from is
  still unknown, and the bar's sense is an assumption. Kits `tune-bar-300`
  (60-10 inside the window) and `tune-bar-200` (90-10) vary only the bar; the
  offset kits vary only the distance. A stock baseline capture remains the
  way to stop guessing.
- `ArHudDebug.health()`: every five seconds from the worker thread, outside
  the runtime lock, one critical-budget record `health dt= busy= wait= load=
  free_kb= threads= proc= top=<thread>:<ticks>,.. net=<if>:<rx>/<tx>,..` from
  `/proc/stat`, `/proc/loadavg`, `/proc/meminfo`, `/proc/self/task/*/stat`
  and `/proc/net/dev`. Differences since the previous sample; the first
  sample only sets baselines. Names are reduced to `[A-Za-z0-9_.-]`; no
  addresses, no payload. Bytes still arriving during a frozen picture point
  at decoding/rendering on the head unit; none arriving, at the phone or the
  link; a pegged CPU or one runaway thread shows in `busy`/`top`.
- Analyser: process runs are split by helper version and by uptime starting
  over (the 110 export had PID 1066 as the DiPO process of a 109 boot and of
  the 110 boot, reported as one), each with its version and wall-clock span;
  health summary with receive-quiet runs of 10 s or more.
- Finish kit and recorder checksum list: 104-111.

Not proven on the car: that `/proc/net/dev` and the task table are readable
from the projection app on this firmware (an unreadable source gives `-`),
and any effect of the bar on the arrow.

Host validation: 1,579 Java checks and 49 Python cases. The 110 export
replayed through this build starts its street approaches at 188-199 m with
bar 40, 30, 20, 10. Mock install and uninstall (Android up and down) return
to stock.

## 1.2.0.112 test build

The 111 export: two boots (17:49-18:39, PID 1073; 19:03-19:42, PID 1045),
65,605 DiPO records, 4,307 exact samples at 1 Hz, 1,045 health records.
Owner: AR went really well; CarPlay froze twice, once around 6:33 pm, once
while driving; only the CarPlay screen froze, the HUD kept working.

- **Freezes, now visible.** The health record's per-interface byte counts
  show three stalls of the picture stream (normally 1.2-10 MB/s received on
  `wlan0`):
  - 18:14:57-18:15:27, 30 s on the highway: received 13-256 KB/s, sent
    11-33 KB/s, the `AirPlayScreenRe` and `AirPlayAudioSen` threads idle,
    guidance continuing at 1 Hz (4947 to 4785 m). Back by itself.
  - 18:33:35-18:33:56 (the 6:33 pm report): last exact sample 18:33:34.97
    (201 m), then 0 bytes received for two samples while 46-62 KB/s was
    still sent; three `reset`s at 18:33:45-46 (`nT()` from the disconnect);
    `gcd-work` at 72 % CPU 18:33:51 and `AirPlayScreenRe` back at 18:33:56:
    a session rebuild. Picture back; no turn-by-turn data arrived again in
    that boot (the drive ended 18:36).
  - 19:11:06-19:12:41, 95 s on the highway before an exit: received 58-83
    KB/s, sent a steady 68 KB/s, `AirPlayScreenRe` idle, `AirPlayAudioSen`
    busy as always, guidance at 1 Hz from 2860 m down to 13 m, CPU 15-21 %,
    1.9 GB free. No reset; the picture returned (3.9 MB/s) at 19:12:41 as
    the exit was reached.
  In none of them was a head-unit thread busy or stuck; the helper's worker
  (`Evergreen-AR-tu`) used 2-3 ticks per 5 s. The head unit was waiting for
  frames that did not come. Whether the link lost them (TCP video stream
  stalling while UDP audio trickles) or the phone stopped encoding cannot be
  told from byte counts alone.
- **Bug (111): spurious expiries.** 25 `timer_expired` records with
  `input_age` of -1 to -29 ms, each followed by `clear_expired_main` and
  `hud_blank_sent`, then the next sample re-sent the maneuver. `run()` read
  the clock, `tick()` logged the heartbeat, released the lock to take the
  health sample (10-30 ms of `/proc` reads), and re-acquired it after the
  phone's callback had stored a newer `receivedAt`; `now < receivedAt` then
  counted as an expiry. One blank frame per occurrence.
- Other: the 200 m street window gave `TURN` from 188-200 m; exits (raw 8)
  and forks (raw 13/14) converted throughout; `silences >5 s` were all stops
  (the car below 2 m/s first) except the stalls above.

Changes:

- `ArHudRuntime.tick()` returns whether the heartbeat fired; `run()` takes
  the health sample afterwards with a fresh clock reading, and inside the
  lock a `receivedAt` ahead of `now` is treated as `now` (never an expiry).
  Test `lateTick()`.
- `ArHudDebug.health()` adds `wifi=<if>:<link>/<level>/<noise>/<retry>/
  <misc>/<missed>` from `/proc/net/wireless` (counters as differences) and
  `tcp=<in>/<out>/<retransmitted>` segments from `/proc/net/snmp`. No
  addresses. The analyser reports level (p50/p5/min), retries, missed
  beacons, retransmissions per sample and per quiet run.
- Finish kit and recorder checksum list: 104-112.

Not proven on the car: that `/proc/net/wireless` and `/proc/net/snmp` are
readable from the projection app (an unreadable source gives `-`).

Host validation: 1,582 Java checks and 49 Python cases.
