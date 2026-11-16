# Host navigation replay

Run from the repository root with JDK 17 on PATH:

```
sh tools/replay-carplay-ar.sh src/carplay-ar/replay/startup-late-details.csv
sh tools/replay-carplay-ar.sh src/carplay-ar/replay/startup-late-details.csv --refresh-details
```

Output is CSV: simulated time, event, cumulative main-turn calls, last main
turn type/angle/distance in meters, last small-HUD icon and cumulative HUD calls.
Rows with unchanged call counts mean no new output. Last values can remain visible
in the trace after stop; this records history, not the physical HUD's blanking.

The default models the pre-fix (1.2.0.102) split callback policy: route/distance updates
refresh outputs; maneuver-details updates populate the cache only. The optional
`--refresh-details` mode runs the production ArHudRoute.refreshDetails routine shipped by the
1.2.0.103 hook. The reflective OEM sender is replaced with a test callback. Neither mode reproduces the manufacturer's parser implementation.

Events use ordered nonnegative millisecond times:

| Event | Arguments | Meaning |
|---|---|---|
| route | maneuver ID | Start/update current maneuver |
| details | ID, raw type, angle, projection icon | Insert/update maneuver metadata |
| distance | integer meters | Exact and display distance update |
| tick | none | Advance the real smoothing runtime at this timestamp |
| stop | none | Cancel runtime and guidance |
| source | none | Clear route and maneuver state for a new source |

Scenario metadata uses synthetic Java objects with the verified private field
names. The real ArHudRoute, ArHudRuntime, ArHudMotion and ArHudTurns execute.
Background threads are disabled and time is injected; no sleeping or vehicle
connection is needed. Global runtime state means scenarios run sequentially.
The small-HUD output is a behavioral model, not execution of OEM sendHud or an
emulator of its packet format. Projection icon is supplied explicitly, so these
tests cannot discover an incorrect OEM raw-type-to-icon mapping.

`tools/test-carplay-ar.sh` runs replay assertions for both startup orderings,
source changes, stop, interpolation, HUD/main separation and known exit coverage
limits. Known gaps are characterized explicitly rather than expected to pass as
fixed. Add a trace and regression assertion before changing production hooks.

Not simulated: iAP2 wire decoding, actual DiPO/Dalvik execution, Binder timing,
Wi-Fi, CarPlay video freezes, cluster firmware or visual AR animation. These
require additional integration work and car testing. No OEM files are included.
