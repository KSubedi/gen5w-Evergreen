#!/usr/bin/env python3
"""Summarize an AR diagnostic export (AR-DEBUG / AR-DEBUG-FINISHED) and optionally
write sanitized replay traces for tools/replay-carplay-ar.sh.

Reads only our own EvergreenAR records: numeric maneuver codes, distances,
states and timings. The export contains no road names or coordinates, and the
replay traces carry only maneuver numbers, raw types, icons and meters.
"""
import argparse
import collections
import json
from pathlib import Path
import re
import statistics

RECORD = re.compile(r"EvergreenAR(?:\(\s*(?P<bpid>\d+)\))?: v(?P<v>\d+) t=(?P<t>\d+) seq=(?P<seq>\d+) dropped=(?P<drop>\d+) (?P<msg>.*?)(?: critical_dropped=(?P<cdrop>\d+))?$")
THREADTIME = re.compile(r"^(?P<wall>\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+(?P<pid>\d+)\s+(?P<tid>\d+)\s+W\s+(?P<rest>EvergreenAR.*)$")
BRIEF = re.compile(r"^W/(?P<rest>EvergreenAR\(\s*(?P<pid>\d+)\): .*)$")
# 114: Android Auto's once-a-second conversion record (GPM).
AATURN = re.compile(r"EvergreenAR(?:\(\s*\d+\))?: aaturn (?P<msg>event=.*)$")
TURN = re.compile(r"EvergreenAR(?:\(\s*\d+\))?: turn (?P<msg>icon=.*)$")
# 109 records outside the event stream: a crash of the projection app, or where its sender thread is while input is quiet.
# 113: the recorder's copy of the hotspot's kernel events (channel switch, disconnects).
WIFI = re.compile(r"EvergreenAR(?:\(\s*\d+\))?: v\d+ wifi kernel_s=(?P<kernel>[0-9.]+) event=(?P<event>[a-z_]+)(?: (?P<key>[a-z]+)=(?P<value>\d+))?\s*$")
THREAD = re.compile(r"EvergreenAR(?:\(\s*\d+\))?: v\d+ (?P<msg>(?:uncaught|input_quiet) thread=.*)$")


def kv(message):
    return dict(part.split("=", 1) for part in message.split()[1:] if "=" in part)


def parse_file(path, events, turns):
    for line in path.read_text(errors="replace").splitlines():
        m = THREADTIME.match(line) or BRIEF.match(line)
        if not m:
            continue
        rest = m.group("rest")
        r = RECORD.search(rest)
        if r:
            events.append({"wall": m.groupdict().get("wall"), "pid": int(m.group("pid")), "tid": int(m.groupdict().get("tid") or 0),
                           "v": int(r.group("v")), "t": int(r.group("t")), "seq": int(r.group("seq")), "drop": int(r.group("drop")),
                           "msg": r.group("msg"), "file": path.name})
            continue
        u = TURN.search(rest)
        if u:
            turns.append({"pid": int(m.group("pid")), "msg": u.group("msg")})
            continue
        a = AATURN.search(rest)
        if a:
            turns.append({"pid": int(m.group("pid")), "msg": a.group("msg"), "aa": True})


def wifi_records(source):
    """The hotspot's kernel events, once each (rotated files and recent-logcat overlap)."""
    seen, found = set(), []
    for path in files_of(source):
        for line in path.read_text(errors="replace").splitlines():
            m = THREADTIME.match(line) or BRIEF.match(line)
            w = m and WIFI.search(m.group("rest"))
            if not w or (w.group("kernel"), w.group("event")) in seen: continue
            seen.add((w.group("kernel"), w.group("event")))
            found.append({"wall": m.groupdict().get("wall"), "kernel_s": float(w.group("kernel")), "event": w.group("event"),
                          w.group("key") or "detail": int(w.group("value")) if w.group("value") else None})
    return sorted(found, key=lambda x: x["kernel_s"])


def wall_seconds(wall):
    """Seconds within the year from a threadtime stamp (MM-DD HH:MM:SS.mmm), for nearness only."""
    try:
        day, clock = wall.split(); month, mday = (int(x) for x in day.split("-")); h, m, sec = clock.split(":")
        return ((month * 31 + mday) * 24 + int(h)) * 3600 + int(m) * 60 + float(sec)
    except (AttributeError, ValueError):
        return None


def analyze_screen(events, wifi):
    """113: CarPlay picture events, the recovery steps taken and what brought the picture back."""
    kinds = collections.Counter()
    recovered, restarts, failures, stalls = [], [], [], []
    for e in events:
        msg = e["msg"]; k = msg.split()[0]
        if k == "screen":
            what = msg.split()[1] if len(msg.split()) > 1 else "?"
            kinds[what] += 1
            if what == "timeout": stalls.append(e)
        elif k == "recover":
            kinds["step " + kv(msg).get("step", "?") + " " + msg.split()[2]] += 1
        elif k in ("link_dead", "link_back"):
            kinds[k] += 1
            (recovered if k == "link_back" else failures).append(
                {"wall": e["wall"], "after_ms": int(kv(msg).get("after_ms", -1)), "step": "link"} if k == "link_back" else msg.split(" guidance_state")[0])
        elif k == "recover_ok":
            d = kv(msg); recovered.append({"wall": e["wall"], "after_ms": int(d.get("after_ms", -1)), "step": int(d.get("step", -1))})
        elif k in ("recover_failed", "guidance_restart_failed"):
            failures.append(msg)
        elif k == "guidance_restart":
            restarts.append({"wall": e["wall"], "silent_ms": int(kv(msg).get("silent_ms", -1))})
    if not kinds and not restarts and not failures: return None
    switches = [w for w in wifi if w["event"] == "ch_switch"]
    near = []
    for e in stalls:
        t = wall_seconds(e["wall"])
        if t is None: continue
        close = [w for w in switches if w["wall"] and wall_seconds(w["wall"]) is not None and -60 <= t - wall_seconds(w["wall"]) <= 60]
        near.append({"wall": e["wall"], "channel_switch_within_60s": [(w["wall"], w.get("freq")) for w in close]})
    return {"events": dict(kinds.most_common()), "recovered": recovered, "guidance_restarts": restarts,
            "failures": sorted(set(failures)), "stalls": near}


def files_of(source):
    source = Path(source)
    files = sorted(source.glob("flow.log*"), key=lambda p: (-int(p.suffix[1:]) if p.suffix[1:].isdigit() else 0)) if source.is_dir() else [source]
    if source.is_dir() and (source / "recent-logcat.txt").is_file():
        files.append(source / "recent-logcat.txt")
    return files


def thread_records(source):
    """Crash and quiet-input records with their counts; frames are class and method names only."""
    found = collections.Counter()
    for path in files_of(source):
        for line in path.read_text(errors="replace").splitlines():
            m = THREADTIME.match(line) or BRIEF.match(line)
            t = m and THREAD.search(m.group("rest"))
            if t:
                found["pid %s: %s" % (m.group("pid"), t.group("msg"))] += 1
    return dict(found.most_common(40))


def load(source):
    files = files_of(source)
    events, turns = [], []
    for path in files:
        parse_file(path, events, turns)
    # Rotated files overlap with recent-logcat; keep one record per (pid, version, seq, t).
    seen, unique = set(), []
    for e in events:
        key = (e["pid"], e["v"], e["seq"], e["t"])
        if key not in seen:
            seen.add(key); e["order"] = len(unique); unique.append(e)
    unique.sort(key=lambda e: (e["pid"], e["t"], e["seq"]))
    return unique, turns


def sessions(events):
    """One list of records per process run, keyed "pid" or "pid/n" when the PID was reused.

    The head unit hands out the same PID boot after boot, and an export spans several boots and
    helper versions: a new version, or an uptime that starts over, is a different process.
    """
    by = collections.defaultdict(list)
    for e in events: by[e["pid"]].append(e)
    out = collections.OrderedDict()
    for pid, L in sorted(by.items()):
        runs = []
        # Logging order (oldest rotated file first), not the wall clock: that can be set mid-boot.
        for e in sorted(L, key=lambda e: e.get("order", 0)):
            run = runs[-1] if runs else None
            if e["wall"] is None:
                # recent-logcat lines carry no time: they belong to the latest run of their version.
                run = next((r for r in reversed(runs) if r["v"] == e["v"]), None)
            elif run is not None and (run["v"] != e["v"] or e["t"] < run["last"] - 60000):
                run = None
            if run is None:
                run = {"v": e["v"], "last": e["t"], "events": []}; runs.append(run)
            run["events"].append(e); run["last"] = max(run["last"], e["t"])
        for n, run in enumerate(runs, 1):
            run["events"].sort(key=lambda e: (e["t"], e["seq"]))
            out[str(pid) if len(runs) == 1 else "%d/%d" % (pid, n)] = run["events"]
    return out


def analyze_health(events):
    """The helper's five-second health records (111): CPU, busiest threads, bytes per interface."""
    rows = []
    for e in events:
        if not e["msg"].startswith("health "): continue
        d = kv(e["msg"])
        try: dt = int(d["dt"])
        except (KeyError, ValueError): continue
        if dt <= 0: continue
        net = {}
        for part in d.get("net", "-").split(","):
            name, _, counts = part.partition(":"); rx, _, tx = counts.partition("/")
            if rx.lstrip("-").isdigit() and tx.lstrip("-").isdigit(): net[name] = (int(rx) * 1000 / dt, int(tx) * 1000 / dt)
        top = {}
        for part in d.get("top", "-").split(","):
            name, _, ticks = part.rpartition(":")
            if name and ticks.isdigit(): top[name] = int(ticks)
        number = lambda key: int(d[key]) if d.get(key, "").lstrip("-").isdigit() else None
        # 112: wifi=<if>:<link>/<level>/<noise>/<retry>/<misc>/<missed beacons>; tcp=<in>/<out>/<retransmitted> segments.
        radio = {}
        for part in d.get("wifi", "-").split(","):
            name, _, counts = part.partition(":"); f = counts.split("/")
            if len(f) == 6 and all(x.lstrip("-").isdigit() for x in f): radio[name] = [int(x) for x in f]
        tcp = d.get("tcp", "-").split("/")
        tcp = [int(x) for x in tcp] if len(tcp) == 3 and all(x.lstrip("-").isdigit() for x in tcp) else None
        rows.append({"t": e["t"], "wall": e["wall"], "dt": dt, "busy": number("busy"), "wait": number("wait"), "free_kb": number("free_kb"),
                     "load": float(d["load"]) if re.fullmatch(r"\d+(\.\d+)?", d.get("load", "")) else None,
                     "proc": (number("proc") or 0) * 1000 / dt, "net": net, "top": top, "rx": sum(v[0] for v in net.values()),
                     "radio": radio, "tcp": tcp})
    if not rows: return None
    def spread(values):
        values = [v for v in values if v is not None and v >= 0]
        return {"p50": quantile(values, .5), "p95": quantile(values, .95), "max": max(values)} if values else None
    out = {"samples": len(rows), "busy_percent": spread(r["busy"] for r in rows), "iowait_percent": spread(r["wait"] for r in rows),
           "load": spread(r["load"] for r in rows), "process_ticks_per_s": spread(round(r["proc"]) for r in rows),
           "free_kb_min": min([r["free_kb"] for r in rows if r["free_kb"] is not None] or [None]),
           "late_samples": [{"wall": r["wall"], "t": r["t"], "dt": r["dt"]} for r in rows if r["dt"] >= 7500][:20]}
    names = sorted({n for r in rows for n in r["net"]})
    out["interfaces"] = {n: {"rx_bytes_per_s": spread(round(r["net"][n][0]) for r in rows if n in r["net"]),
                             "tx_bytes_per_s": spread(round(r["net"][n][1]) for r in rows if n in r["net"])} for n in names}
    ticks = collections.Counter()
    for r in rows: ticks.update(r["top"])
    out["busiest_threads_ticks"] = dict(ticks.most_common(10))
    radios = sorted({n for r in rows for n in r["radio"]})
    def low(values):
        values = sorted(values)
        return {"p50": quantile(values, .5), "p5": quantile(values, .05), "min": values[0]} if values else None
    out["radio"] = {n: {"level_dbm": low(-abs(r["radio"][n][1]) for r in rows if n in r["radio"]),
                        "retries_per_sample": spread(r["radio"][n][3] for r in rows if n in r["radio"]),
                        "missed_beacons": sum(r["radio"][n][5] for r in rows if n in r["radio"])} for n in radios}
    tcp_rows = [r for r in rows if r["tcp"]]
    out["tcp"] = {"retransmitted_per_sample": spread(r["tcp"][2] for r in tcp_rows),
                  "retransmitted_percent": round(100 * sum(r["tcp"][2] for r in tcp_rows) / max(1, sum(r["tcp"][1] for r in tcp_rows)), 2)} if tcp_rows else None
    # A link that falls silent while the session lasts: where a frozen picture would show if the data stopped arriving.
    median = quantile([r["rx"] for r in rows], .5) or 0
    limit = max(1000, median * 0.05)
    quiet, run = [], None
    for r in rows:
        if median > limit and r["rx"] < limit:
            if run is None: run = {"wall": r["wall"], "t": r["t"] - r["dt"], "seconds": 0, "busy": [], "retrans": 0, "level": []}; quiet.append(run)
            run["seconds"] += round(r["dt"] / 1000); run["busy"].append(r["busy"])
            run["retrans"] += r["tcp"][2] if r["tcp"] else 0
            run["level"] += [-abs(v[1]) for v in r["radio"].values()]
        else: run = None
    out["receive_quiet"] = [{"wall": q["wall"], "t": q["t"], "seconds": q["seconds"], "busy_percent_max": max([b for b in q["busy"] if b is not None] or [None]),
                             "tcp_retransmitted": q["retrans"], "level_dbm_min": min(q["level"]) if q["level"] else None}
                            for q in quiet if q["seconds"] >= 10][:40]
    out["receive_median_bytes_per_s"] = round(median)
    return out


def quantile(values, q):
    values = sorted(values)
    return values[min(len(values) - 1, int(q * len(values)))] if values else None


def analyze_process(events):
    kinds = collections.Counter(e["msg"].split()[0] for e in events)
    out = {"records": len(events), "t_first": events[0]["t"], "t_last": events[-1]["t"],
           "max_dropped": max(e["drop"] for e in events), "events": dict(kinds.most_common())}
    exact = [(e["t"], int(kv(e["msg"])["meters"])) for e in events if e["msg"].startswith("exact ")]
    gaps = [b[0] - a[0] for a, b in zip(exact, exact[1:])]
    out["exact"] = {"count": len(exact), "median_interval_ms": statistics.median(gaps) if gaps else None,
                    "silences_over_5s": sorted(g for g in gaps if g >= 5000)[-10:]}
    out["icon_gate"] = dict(collections.Counter(
        "waiting_for_distance" if "waiting" in e["msg"] else
        "raw%s->%s" % (kv(e["msg"]).get("raw"), kv(e["msg"]).get("recovered")) for e in events if e["msg"].startswith("icon_gate")).most_common())
    expiries = []
    for e in events:
        if e["msg"].startswith("timer_expired"):
            before = [x for x in exact if x[0] <= e["t"]][-1:]
            after = [x for x in exact if x[0] > e["t"]][:1]
            expiries.append({"t": e["t"], "meters_before": before[0][1] if before else None, "meters_after": after[0][1] if after else None,
                             "moving": kv(e["msg"]).get("passed") == "true",
                             "silent_s": round((after[0][0] - before[0][0]) / 1000) if before and after else None})
    out["expiries"] = expiries
    latency, pending = [], {}
    for e in events:
        k = e["msg"].split()[0]
        if k == "main_send": pending[e["tid"]] = e["t"]
        elif k == "main_sent" and e["tid"] in pending: latency.append(e["t"] - pending.pop(e["tid"]))
    out["main_send_latency_ms"] = {"count": len(latency), "p50": quantile(latency, .5), "p99": quantile(latency, .99), "max": max(latency) if latency else None}
    sent = [e for e in events if e["msg"].startswith("main_sent")]
    out["main_sent_types"] = dict(collections.Counter(kv(e["msg"])["type"] for e in sent).most_common())
    out["interpolated_sends"] = sum(1 for e in sent if kv(e["msg"]).get("force") == "false")
    back, forward, last = 0, 0, None
    for e in sent:
        d = kv(e["msg"]); m = float(d["meters"])
        if last and last[1] == d["type"] and d.get("exact") == "true" and e["t"] - last[0] < 1500 and d.get("force") == "true" and last[3] == "false":
            if m - last[2] > 0.3: back += 1
            else: forward += 1
        last = (e["t"], d["type"], m, d.get("force"))
    out["phone_sample_after_tick"] = {"stepped_back": back, "continued": forward}
    replacements = []
    for i, e in enumerate(events):
        if e["msg"].startswith("maneuver_replaced"):
            nxt = next((x for x in events[i:] if x["msg"].startswith("main_sent")), None)
            replacements.append(nxt["t"] - e["t"] if nxt else None)
    out["replacement_to_next_send_ms"] = replacements
    out["clears"] = dict(collections.Counter(e["msg"].split()[0] for e in events if e["msg"].startswith("clear_")))
    out["tuning"] = sorted({e["msg"] for e in events if e["msg"].startswith("tuning")})
    out["health"] = analyze_health(events)
    segments, current = [], None
    for e in events:
        k = e["msg"].split()[0]
        if k == "sender_prepare":
            d = kv(e["msg"]); key = (d.get("state"), d.get("current"))
            if current is None or current["key"] != key:
                current = {"key": key, "t0": e["t"], "t1": e["t"], "inputs": collections.Counter(), "gate": collections.Counter(),
                           "out": collections.Counter(), "mstate": collections.Counter()}
                segments.append(current)
            current["t1"] = e["t"]
        elif current is not None:
            d = kv(e["msg"])
            if k == "main_input":
                current["inputs"][d.get("icon")] += 1
                if d.get("mstate") not in (None, "-1"): current["mstate"][d["mstate"]] += 1
            elif k == "main_sent": current["out"][d["type"]] += 1
            elif k == "icon_gate": current["gate"]["wait" if "waiting" in e["msg"] else "raw%s->%s" % (d.get("raw"), d.get("recovered"))] += 1
            elif k in ("timer_expired", "maneuver_replaced", "hud_blank_sent", "rerouting_passthrough", "details_refresh"): current["out"][k] += 1
    out["segments"] = [{"state": s["key"][0], "maneuver": s["key"][1], "seconds": round((s["t1"] - s["t0"]) / 1000),
                        "inputs": dict(s["inputs"]), "gate": dict(s["gate"]), "out": dict(s["out"]), "mstate": dict(s["mstate"])} for s in segments]
    return out


AA_EVENTS = {1: "depart", 2: "name_change", 3: "slight", 4: "turn", 5: "sharp", 6: "u_turn", 7: "on_ramp", 8: "off_ramp",
             9: "fork", 10: "merge", 11: "roundabout_enter", 12: "roundabout_exit", 13: "roundabout", 14: "straight",
             16: "ferry_boat", 17: "ferry_train", 19: "destination"}
AA_SIDES = {1: "left", 2: "right", 3: "unspecified"}


def analyze_auto(events):
    """114: Android Auto (GPM) turns, what went out as native AR, distance cadence, expiries."""
    turns = collections.Counter(); sent = collections.Counter(); passed = collections.Counter(); status = collections.Counter()
    distances, expiries, stops = [], [], 0
    for e in events:
        k = e["msg"].split()[0]; d = kv(e["msg"])
        if k == "auto_turn":
            ev, sd = int(d.get("event", -1)), int(d.get("side", 0))
            turns["%s %s" % (AA_EVENTS.get(ev, "event%d" % ev), AA_SIDES.get(sd, "side%d" % sd))] += 1
        elif k == "auto_sent" and d.get("force") == "true": sent[d.get("type")] += 1
        elif k == "auto_passed": passed[d.get("icon")] += 1
        elif k == "auto_distance": distances.append(e["t"])
        elif k == "auto_status": status[d.get("status")] += 1
        elif k == "auto_expired": expiries.append({"wall": e["wall"], "input_age": int(d.get("input_age", -1)), "passed": d.get("passed") == "true"})
        elif k == "auto_stopped": stops += 1
    gaps = [b - a for a, b in zip(distances, distances[1:])]
    glides = sum(1 for e in events if e["msg"].startswith("auto_sent") and "force=false" in e["msg"])
    return {"maneuvers": dict(turns.most_common()), "native_sent": dict(sent.most_common()), "glide_sends": glides,
            "gpm_frames_passed": dict(passed.most_common(12)), "status": dict(status),
            "distance_updates": len(distances), "distance_median_interval_ms": statistics.median(gaps) if gaps else None,
            "distance_silences_over_5s": sorted(g for g in gaps if g >= 5000)[-10:], "expiries": expiries, "stops": stops}


def analyze(events, turns, threads=None, wifi=None):
    def label(t):
        f = kv("x " + t["msg"])
        text = "icon=%s raw=%s result=%s output=%s" % (f.get("icon"), f.get("raw"), f.get("result"), f.get("output"))
        # 108 records: the side chosen for a generic exit and the phone's junction angles; 109: the exit
        # style, always shown because its default differs between 109 (slight) and 110 (sign).
        for key in ("exit_side", "exit_style", "angle_values", "offset"):
            if key in f and f[key] not in ("0", "-"):
                text += " %s=%s" % (key, f[key])
        return text
    def aa_label(t):
        f = kv("x " + t["msg"])
        ev = int(f.get("event", "-1")) if f.get("event", "-1").lstrip("-").isdigit() else -1
        sd = int(f.get("side", "0")) if f.get("side", "0").isdigit() else 0
        return "%s %s icon=%s result=%s output=%s dir=%s" % (AA_EVENTS.get(ev, "event%d" % ev), AA_SIDES.get(sd, "side%d" % sd),
                                                             f.get("icon"), f.get("result"), f.get("output"), f.get("dir"))
    report = {"processes": {}, "turn_records": dict(collections.Counter(label(t) for t in turns if not t.get("aa")).most_common(40)),
              "android_auto_records": dict(collections.Counter(aa_label(t) for t in turns if t.get("aa")).most_common(40))}
    if threads:
        report["thread_records"] = threads
    if wifi:
        report["wifi"] = {"events": dict(collections.Counter(w["event"] for w in wifi).most_common()),
                          "channels": dict(collections.Counter(w.get("freq") for w in wifi if w["event"] == "ch_switch").most_common()),
                          "records": [(w["wall"], w["kernel_s"], w["event"], w.get("freq", w.get("reason"))) for w in wifi][-60:]}
    for name, L in sessions(events).items():
        kinds = collections.Counter(e["msg"].split()[0] for e in L)
        role = ("dipo" if "sender_prepare" in kinds or "exact" in kinds or "screen" in kinds else
                "android_auto" if "auto_input" in kinds or "auto_turn" in kinds or "auto_alive" in kinds else
                "navigation" if "trap_input" in kinds else "unknown")
        walls = [e["wall"] for e in L if e["wall"]]
        report["processes"][name] = dict(role=role, version="v%d" % L[0]["v"], wall_first=walls[0] if walls else None, wall_last=walls[-1] if walls else None,
                                         **(analyze_process(L) if role == "dipo" else {"records": len(L), "events": dict(kinds.most_common())}))
        if role == "dipo":
            report["processes"][name]["screen"] = analyze_screen(L, wifi or [])
        if role == "android_auto":
            report["processes"][name]["android_auto"] = analyze_auto(L)
            report["processes"][name]["health"] = analyze_health(L)
    return report


def write_replays(events, directory):
    """One sanitized trace per DiPO process: details/packet/route/tick/stop only."""
    directory = Path(directory); directory.mkdir(parents=True, exist_ok=True)
    written = []
    for name, L in sessions(events).items():
        if not any(e["msg"].startswith("sender_prepare") for e in L): continue
        lines = ["# Sanitized from a diagnostic export by tools/analyze-ar-log.py: maneuver numbers,",
                 "# raw types, icons, states and meters only. Times are relative milliseconds."]
        t0 = L[0]["t"]; known = {}; state = None; current = None; pending_exact = None; last_time = 0
        def emit(t, text):
            nonlocal last_time
            t = max(t - t0, last_time); last_time = t; lines.append("%d,%s" % (t, text))
        for e in L:
            k = e["msg"].split()[0]; d = kv(e["msg"])
            if k == "icon_gate" and "waiting" not in e["msg"] and current is not None:
                raw, angle = int(d.get("raw", -1)), int(d.get("angle", 0))
                icon = int(d.get("recovered", 0))
                if known.get(current) != (raw, angle, icon):
                    known[current] = (raw, angle, icon); emit(e["t"], "details,%d,%d,%d,%d" % (current, raw, angle, icon))
            elif k == "main_input" and current is not None and d.get("icon", "0") != "0":
                icon = int(d["icon"]); raw, angle, old = known.get(current, (-1, 0, 0))
                if old != icon and old == 0:
                    known[current] = (raw, angle, icon); emit(e["t"], "details,%d,%d,%d,%d" % (current, raw, angle, icon))
            elif k == "exact":
                pending_exact = int(d["meters"])
            elif k == "sender_prepare":
                new_state, new_current = d.get("state"), int(d.get("current", -1))
                if new_current < 0: continue
                if new_state == "0":
                    if state != "0": emit(e["t"], "stop")
                    state, current = new_state, new_current; continue
                if new_current != current and new_current not in known:
                    known[new_current] = (-1, 0, 0); emit(e["t"], "details,%d,-1,0,0" % new_current)
                if pending_exact is not None:
                    emit(e["t"], "packet,%d,%d,%s" % (new_current, pending_exact, new_state)); pending_exact = None
                elif new_current != current or new_state != state:
                    emit(e["t"], "route,%d,%s" % (new_current, new_state))
                state, current = new_state, new_current
            elif k == "timer_expired" or k == "worker_alive":
                emit(e["t"], "tick")
        path = directory / ("replay-pid%s.csv" % name.replace("/", "-")); path.write_text("\n".join(lines) + "\n"); written.append(path)
    return written


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("source", type=Path, help="AR-DEBUG(-FINISHED) directory or one log file")
    p.add_argument("--json", action="store_true", help="print the full report as JSON")
    p.add_argument("--replay", type=Path, help="write sanitized replay CSVs into this directory")
    a = p.parse_args()
    events, turns = load(a.source)
    if not events:
        p.exit(1, "no EvergreenAR records found\n")
    report = analyze(events, turns, thread_records(a.source), wifi_records(a.source))
    if a.json:
        print(json.dumps(report, indent=2))
    else:
        for pid, r in report["processes"].items():
            print("process %s %s (%s): %d records, %s .. %s" % (pid, r["version"], r["role"], r["records"], r["wall_first"], r["wall_last"]))
            if r["role"] == "android_auto":
                aa = r["android_auto"]
                print("  maneuvers from Android Auto:", aa["maneuvers"])
                print("  native AR frames (new maneuver/update):", aa["native_sent"], "glide frames:", aa["glide_sends"])
                print("  GPM frames passed unchanged (by icon):", aa["gpm_frames_passed"])
                print("  exact distance updates %d, median interval %s ms, silences >5 s: %s" % (
                    aa["distance_updates"], aa["distance_median_interval_ms"], aa["distance_silences_over_5s"]))
                print("  status:", aa["status"], "expiries (wall, input age ms, passed):", [(x["wall"], x["input_age"], x["passed"]) for x in aa["expiries"]], "stops:", aa["stops"])
                if r.get("health"): print("  health: %d samples; CPU busy %% %s" % (r["health"]["samples"], r["health"]["busy_percent"]))
                continue
            if r["role"] != "dipo": continue
            print("  uptime %d..%d s, max dropped %d" % (r["t_first"] // 1000, r["t_last"] // 1000, r["max_dropped"]))
            print("  exact receipts %d, median interval %s ms, silences >5 s: %s" % (r["exact"]["count"], r["exact"]["median_interval_ms"], r["exact"]["silences_over_5s"]))
            print("  icon gate:", r["icon_gate"])
            print("  main sends by type:", r["main_sent_types"], "interpolated:", r["interpolated_sends"])
            print("  send latency ms p50/p99/max: %s/%s/%s" % (r["main_send_latency_ms"]["p50"], r["main_send_latency_ms"]["p99"], r["main_send_latency_ms"]["max"]))
            print("  phone sample after a tick: stepped back %d, continued %d" % (r["phone_sample_after_tick"]["stepped_back"], r["phone_sample_after_tick"]["continued"]))
            print("  expiries (meters before, after, silent s, lost while moving):", [(x["meters_before"], x["meters_after"], x["silent_s"], x["moving"]) for x in r["expiries"]])
            print("  replacement -> next send ms:", r["replacement_to_next_send_ms"], "clears:", r["clears"])
            if r["tuning"]: print("  tuning:", r["tuning"])
            sc = r.get("screen")
            if sc:
                print("  screen events:", sc["events"])
                print("  picture recovered (wall, after ms, step):", [(x["wall"], x["after_ms"], x["step"]) for x in sc["recovered"]])
                if sc["guidance_restarts"]: print("  guidance restarts (wall, silent ms):", [(x["wall"], x["silent_ms"]) for x in sc["guidance_restarts"]])
                if sc["failures"]: print("  recovery failures:", sc["failures"])
                for x in sc["stalls"]: print("  stall %s: channel switches within 60 s: %s" % (x["wall"], x["channel_switch_within_60s"]))
            h = r["health"]
            if h:
                print("  health: %d samples; CPU busy %% p50/p95/max %s; load %s; this process ticks/s %s; least free memory %s kB" % (
                    h["samples"], h["busy_percent"], h["load"], h["process_ticks_per_s"], h["free_kb_min"]))
                for name, i in h["interfaces"].items(): print("  health: %s bytes/s received %s sent %s" % (name, i["rx_bytes_per_s"], i["tx_bytes_per_s"]))
                print("  health: busiest threads (ticks):", h["busiest_threads_ticks"])
                for name, i in h["radio"].items(): print("  health: %s level dBm %s, retries per sample %s, missed beacons %d" % (name, i["level_dbm"], i["retries_per_sample"], i["missed_beacons"]))
                if h["tcp"]: print("  health: TCP retransmitted per sample %s (%s%% of sent segments)" % (h["tcp"]["retransmitted_per_sample"], h["tcp"]["retransmitted_percent"]))
                print("  health: receive quiet for 10 s or more (wall, s, max busy %%, TCP retransmits, min dBm; median %d bytes/s):" % h["receive_median_bytes_per_s"],
                      [(q["wall"], q["seconds"], q["busy_percent_max"], q["tcp_retransmitted"], q["level_dbm_min"]) for q in h["receive_quiet"]])
                if h["late_samples"]: print("  health: worker late:", [(x["wall"], x["dt"]) for x in h["late_samples"]])
            for s in r["segments"]:
                print("  state=%s maneuver=%s %4ds in=%s gate=%s out=%s mstate=%s" % (s["state"], s["maneuver"], s["seconds"], s["inputs"], s["gate"], s["out"], s["mstate"]))
        if report["turn_records"]:
            print("turn records:")
            for k, n in report["turn_records"].items(): print("  %5d  %s" % (n, k))
        if report.get("android_auto_records"):
            print("Android Auto turn records:")
            for k, n in report["android_auto_records"].items(): print("  %5d  %s" % (n, k))
        for k, n in report.get("thread_records", {}).items(): print("thread record x%d: %s" % (n, k))
        if report.get("wifi"):
            w = report["wifi"]
            print("hotspot kernel events:", w["events"], "channels switched to (MHz):", w["channels"])
            for x in w["records"]: print("  %s kernel %.3f s %s %s" % (x[0], x[1], x[2], "" if x[3] is None else x[3]))
    if a.replay:
        for path in write_replays(events, a.replay): print("wrote", path)


if __name__ == "__main__":
    main()
