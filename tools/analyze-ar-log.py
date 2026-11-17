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
TURN = re.compile(r"EvergreenAR(?:\(\s*\d+\))?: turn (?P<msg>icon=.*)$")
# 109 records outside the event stream: a crash of the projection app, or where its sender thread is while input is quiet.
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
                           "t": int(r.group("t")), "seq": int(r.group("seq")), "drop": int(r.group("drop")),
                           "msg": r.group("msg"), "file": path.name})
            continue
        u = TURN.search(rest)
        if u:
            turns.append({"pid": int(m.group("pid")), "msg": u.group("msg")})


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
    # Rotated files overlap with recent-logcat; keep one record per (pid, seq, t).
    seen, unique = set(), []
    for e in events:
        key = (e["pid"], e["seq"], e["t"])
        if key not in seen:
            seen.add(key); unique.append(e)
    unique.sort(key=lambda e: (e["pid"], e["t"], e["seq"]))
    return unique, turns


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


def analyze(events, turns, threads=None):
    by = collections.defaultdict(list)
    for e in events: by[e["pid"]].append(e)
    def label(t):
        f = kv("x " + t["msg"])
        text = "icon=%s raw=%s result=%s output=%s" % (f.get("icon"), f.get("raw"), f.get("result"), f.get("output"))
        # 108 records: the side chosen for a generic exit and the phone's junction angles; 109: the exit
        # style, always shown because its default differs between 109 (slight) and 110 (sign).
        for key in ("exit_side", "exit_style", "angle_values", "offset"):
            if key in f and f[key] not in ("0", "-"):
                text += " %s=%s" % (key, f[key])
        return text
    report = {"processes": {}, "turn_records": dict(collections.Counter(label(t) for t in turns).most_common(40))}
    if threads:
        report["thread_records"] = threads
    for pid, L in sorted(by.items()):
        kinds = collections.Counter(e["msg"].split()[0] for e in L)
        role = "dipo" if "sender_prepare" in kinds or "exact" in kinds else "navigation" if "trap_input" in kinds else "unknown"
        report["processes"][str(pid)] = dict(role=role, **(analyze_process(L) if role == "dipo" else {"records": len(L), "events": dict(kinds.most_common())}))
    return report


def write_replays(events, directory):
    """One sanitized trace per DiPO process: details/packet/route/tick/stop only."""
    directory = Path(directory); directory.mkdir(parents=True, exist_ok=True)
    by = collections.defaultdict(list)
    for e in events: by[e["pid"]].append(e)
    written = []
    for pid, L in sorted(by.items()):
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
        path = directory / ("replay-pid%d.csv" % pid); path.write_text("\n".join(lines) + "\n"); written.append(path)
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
    report = analyze(events, turns, thread_records(a.source))
    if a.json:
        print(json.dumps(report, indent=2))
    else:
        for pid, r in report["processes"].items():
            print("process %s (%s): %d records" % (pid, r["role"], r["records"]))
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
            for s in r["segments"]:
                print("  state=%s maneuver=%s %4ds in=%s gate=%s out=%s mstate=%s" % (s["state"], s["maneuver"], s["seconds"], s["inputs"], s["gate"], s["out"], s["mstate"]))
        if report["turn_records"]:
            print("turn records:")
            for k, n in report["turn_records"].items(): print("  %5d  %s" % (n, k))
        for k, n in report.get("thread_records", {}).items(): print("thread record x%d: %s" % (n, k))
    if a.replay:
        for path in write_replays(events, a.replay): print("wrote", path)


if __name__ == "__main__":
    main()
