#!/usr/bin/env python3
"""Roll the telemetry hoard into per-app / per-run aggregates.

Reads ~/balatro-telemetry/phone.log (the canonical event stream both apps POST
home — see telemetry-home.py) and produces:
  - a human summary on stdout (sessions, runs, win rate, hands by type,
    top purchases, score records, fps percentiles)
  - ~/balatro-telemetry/rollup.json with the full aggregate tree
  - optionally (--publish) the same JSON onto NATS at balatro.stats.rollup,
    so anything on the bus can consume the digest without re-parsing history

Line format: 'epoch session KIND k=v k=v ...'. Sessions starting with 'r' are
the Kotlin rebuild; bare-hex sessions are the LÖVE build. Values may be huge
(Talisman big numbers) — anything float() rejects is kept as a string and
excluded from numeric stats. Run via `just tel-stats`.
"""

import argparse
import json
import re
import socket
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

LOG_FILE = Path.home() / "balatro-telemetry" / "phone.log"
OUT_FILE = Path.home() / "balatro-telemetry" / "rollup.json"

HAND_EVENTS = {"PLAY_HAND", "ROUND_HAND"}
SCORE_EVENTS = {"HAND_SCORE"}
BUY_EVENTS = {"BUY", "RUN_BUY", "RUN_BUY_PLANET", "RUN_BUY_TAROT", "RUN_BUY_SPECTRAL", "RUN_BUY_CARD"}


def parse(line):
    parts = line.split()
    if len(parts) < 3:
        return None
    try:
        epoch = int(parts[0])
    except ValueError:
        return None
    props = {}
    for tok in parts[3:]:
        if "=" in tok:
            k, v = tok.split("=", 1)
            props[k] = v
    return epoch, parts[1], parts[2], props


def num(v):
    try:
        f = float(v)
        return f if f == f and abs(f) != float("inf") else None
    except (TypeError, ValueError):
        return None


def pctile(sorted_vals, p):
    if not sorted_vals:
        return None
    i = min(len(sorted_vals) - 1, max(0, int(round(p / 100 * (len(sorted_vals) - 1)))))
    return sorted_vals[i]


def day(epoch):
    return datetime.fromtimestamp(epoch, timezone.utc).strftime("%Y-%m-%d")


def rollup(path):
    apps = {name: {
        "events": 0, "sessions": set(), "days": set(), "first": None, "last": None,
        "kinds": Counter(), "hands": Counter(), "hand_levels": Counter(),
        "buys": Counter(), "sells": 0, "crashes": 0,
        "runs": 0, "wins": 0, "losses": 0, "best_ante": 0, "run_durations": [],
        "best_score": 0.0, "best_score_hand": None, "scores": [],
        "fps": [],
    } for name in ("love", "rebuild")}

    with open(path, errors="replace") as f:
        for line in f:
            ev = parse(line.strip())
            if not ev:
                continue
            epoch, session, kind, props = ev
            a = apps["rebuild" if session.startswith("r") else "love"]
            a["events"] += 1
            a["sessions"].add(session)
            a["days"].add(day(epoch))
            a["first"] = min(a["first"] or epoch, epoch)
            a["last"] = max(a["last"] or epoch, epoch)
            a["kinds"][kind] += 1

            if kind in HAND_EVENTS and "hand" in props:
                a["hands"][props["hand"]] += 1
            if kind in SCORE_EVENTS:
                s = num(props.get("score"))
                if s is not None:
                    a["scores"].append(s)
                    if s > a["best_score"]:
                        a["best_score"], a["best_score_hand"] = s, props.get("hand")
            if kind in BUY_EVENTS:
                a["buys"][props.get("card") or props.get("key") or "unknown"] += 1
            if kind in ("SELL", "RUN_SELL"):
                a["sells"] += 1
            if kind == "CRASH":
                a["crashes"] += 1
            # RUN_START is the canonical run-begin; CONFIG fires alongside it in
            # newer LÖVE builds — counting both would double those runs, so
            # CONFIG only backfills builds that predate RUN_START (see below).
            if kind in ("GAME_OVER",):
                won = props.get("won") == "true"
                a["wins" if won else "losses"] += 1
                d = num(props.get("duration_s"))
                if d:
                    a["run_durations"].append(d)
                ante = num(props.get("ante"))
                if ante:
                    a["best_ante"] = max(a["best_ante"], int(ante))
            if kind in ("RUN_WIN", "ROUND_WIN") and kind == "RUN_WIN":
                a["wins"] += 1
            if kind == "ROUND_LOSE":
                a["losses"] += 1
            for fk in ("fps",):
                v = num(props.get(fk))
                if v and kind in ("PERF", "PERF_SNAPSHOT"):
                    a["fps"].append(v)

    out = {}
    for name, a in apps.items():
        a["runs"] = a["kinds"].get("RUN_START") or a["kinds"].get("CONFIG") or 0
        fps = sorted(a["fps"])
        scores = sorted(a["scores"])
        out[name] = {
            "events": a["events"],
            "sessions": len(a["sessions"]),
            "active_days": len(a["days"]),
            "first_seen": day(a["first"]) if a["first"] else None,
            "last_seen": day(a["last"]) if a["last"] else None,
            "event_kinds": dict(a["kinds"].most_common()),
            "runs_started": a["runs"],
            "wins": a["wins"], "losses": a["losses"],
            "win_rate": round(a["wins"] / (a["wins"] + a["losses"]), 3) if (a["wins"] + a["losses"]) else None,
            "best_ante": a["best_ante"] or None,
            "avg_run_minutes": round(sum(a["run_durations"]) / len(a["run_durations"]) / 60, 1) if a["run_durations"] else None,
            "hands_played": sum(a["hands"].values()),
            "hands_by_type": dict(a["hands"].most_common()),
            "best_hand_score": a["best_score"] or None,
            "best_hand_score_hand": a["best_score_hand"],
            "median_hand_score": pctile(scores, 50),
            "purchases": sum(a["buys"].values()),
            "top_purchases": dict(a["buys"].most_common(10)),
            "sells": a["sells"],
            "crashes": a["crashes"],
            "fps_p50": pctile(fps, 50), "fps_p5": pctile(fps, 5),
        }
    return out


def publish(url, subject, payload):
    m = re.match(r"(?:nats://)?([^:/]+)(?::(\d+))?", url)
    s = socket.create_connection((m.group(1), int(m.group(2) or 4222)), 3)
    s.settimeout(3)
    s.recv(4096)
    s.sendall(b'CONNECT {"verbose":false,"name":"balatro-tel-rollup"}\r\n')
    s.sendall(f"PUB {subject} {len(payload)}\r\n".encode() + payload + b"\r\n")
    s.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", default=str(LOG_FILE))
    ap.add_argument("--out", default=str(OUT_FILE))
    ap.add_argument("--publish", metavar="NATS_URL", default=None,
                    help="also publish the rollup JSON to balatro.stats.rollup")
    args = ap.parse_args()

    out = rollup(args.log)
    out["generated"] = datetime.now(timezone.utc).isoformat()
    Path(args.out).write_text(json.dumps(out, indent=2) + "\n")

    for name in ("love", "rebuild"):
        r = out[name]
        print(f"\n── {name} ─────────────────────────────────")
        print(f"  {r['events']:,} events · {r['sessions']} sessions · {r['active_days']} active days"
              f" ({r['first_seen']} → {r['last_seen']})")
        print(f"  runs: {r['runs_started']} started · {r['wins']}W/{r['losses']}L"
              f" (win rate {r['win_rate']}) · best ante {r['best_ante']}"
              + (f" · avg run {r['avg_run_minutes']} min" if r["avg_run_minutes"] else ""))
        print(f"  hands: {r['hands_played']:,}" +
              (f" · best {r['best_hand_score']:.3g} ({r['best_hand_score_hand']})" if r["best_hand_score"] else ""))
        for h, n in list(r["hands_by_type"].items())[:8]:
            print(f"    {h:<22} {n:,}")
        print(f"  economy: {r['purchases']} buys · {r['sells']} sells")
        for c, n in list(r["top_purchases"].items())[:5]:
            print(f"    {c:<28} {n}")
        if r["fps_p50"]:
            print(f"  fps: p50 {r['fps_p50']:.1f} · p5 {r['fps_p5']:.1f}")
        print(f"  crashes: {r['crashes']}")
    print(f"\nrollup.json → {args.out}")

    if args.publish:
        try:
            publish(args.publish, "balatro.stats.rollup", json.dumps(out).encode())
            print(f"published → {args.publish} balatro.stats.rollup")
        except Exception as e:
            print(f"publish failed (rollup.json still written): {e}", file=sys.stderr)


if __name__ == "__main__":
    main()
