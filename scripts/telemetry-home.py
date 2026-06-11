#!/usr/bin/env python3
"""Telemetry phone-home receiver.

The Android build POSTs batched telemetry lines (same format as the on-device
telemetry.log) to /ingest whenever it flushes. This appends them to
~/balatro-telemetry/phone.log so the data lands on this machine the moment it
happens — no adb, no watcher, works from anywhere on the tailnet.

Optionally tees every parsed event into a self-hosted OpenPanel instance
(--op-url/--op-client) so the firehose gets a real UI: live event stream,
fps/heap charts, per-session filtering. phone.log stays canonical — OpenPanel
forwarding is fire-and-forget on a worker thread and drops on any error.

Run via `just tel-home` (foreground) or install the systemd user unit it
prints with --print-unit. Binds the Tailscale IP only.
"""

import argparse
import json
import os
import queue
import sys
import threading
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

LOG_DIR = Path.home() / "balatro-telemetry"
LOG_FILE = LOG_DIR / "phone.log"

OP_QUEUE: "queue.Queue[str]" = queue.Queue(maxsize=5000)
OP_URL = None
OP_CLIENT = None


def parse_line(line):
    """'epoch session EVENT k=v k=v ...' -> OpenPanel track payload, or None."""
    parts = line.split()
    if len(parts) < 3:
        return None
    try:
        ts = datetime.fromtimestamp(int(parts[0]), timezone.utc).isoformat()
    except ValueError:
        return None
    props = {}
    for tok in parts[3:]:
        if "=" not in tok:
            continue
        k, v = tok.split("=", 1)
        try:
            props[k] = int(v)
        except ValueError:
            try:
                props[k] = float(v)
            except ValueError:
                props[k] = v
    return {
        "type": "track",
        "payload": {
            "name": parts[2],
            "profileId": parts[1],
            "timestamp": ts,
            "properties": props,
        },
    }


def op_worker():
    while True:
        line = OP_QUEUE.get()
        payload = parse_line(line)
        if not payload:
            continue
        try:
            req = urllib.request.Request(
                OP_URL + "/track",
                data=json.dumps(payload).encode(),
                headers={
                    "content-type": "application/json",
                    "openpanel-client-id": OP_CLIENT,
                },
            )
            urllib.request.urlopen(req, timeout=3).read()
        except Exception:
            pass  # phone.log is canonical; the tee is best-effort


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/ingest":
            self.send_response(404)
            self.end_headers()
            return
        length = int(self.headers.get("Content-Length", 0))
        if length <= 0 or length > 1024 * 1024:
            self.send_response(400)
            self.end_headers()
            return
        body = self.rfile.read(length).decode("utf-8", "replace")
        if not body.endswith("\n"):
            body += "\n"
        with open(LOG_FILE, "a") as f:
            f.write(body)
        self.send_response(204)
        self.end_headers()
        if OP_URL and OP_CLIENT:
            for line in body.splitlines():
                if line.strip():
                    try:
                        OP_QUEUE.put_nowait(line)
                    except queue.Full:
                        break  # shed load; phone.log has everything

    def log_message(self, fmt, *args):  # quiet; the payload IS the log
        pass


UNIT = """[Unit]
Description=Balatro phone telemetry receiver
After=tailscaled.service

[Service]
ExecStart={python} {script} --bind {bind} --port {port}{op_args}
Restart=on-failure

[Install]
WantedBy=default.target
"""


def main():
    global OP_URL, OP_CLIENT
    ap = argparse.ArgumentParser()
    ap.add_argument("--bind", default="100.87.221.109")
    ap.add_argument("--port", type=int, default=8753)
    ap.add_argument("--op-url", default=None,
                    help="OpenPanel base URL (e.g. https://op.syntax-parser.com); enables the tee")
    ap.add_argument("--op-client", default=None,
                    help="OpenPanel write client id (ignoreCorsAndSecret client)")
    ap.add_argument("--print-unit", action="store_true",
                    help="print a systemd user unit and exit")
    args = ap.parse_args()

    if args.print_unit:
        op_args = ""
        if args.op_url and args.op_client:
            op_args = f" --op-url {args.op_url} --op-client {args.op_client}"
        print(UNIT.format(python=sys.executable,
                          script=os.path.abspath(__file__),
                          bind=args.bind, port=args.port, op_args=op_args))
        return

    OP_URL, OP_CLIENT = args.op_url, args.op_client
    if OP_URL and OP_CLIENT:
        threading.Thread(target=op_worker, daemon=True).start()

    LOG_DIR.mkdir(exist_ok=True)
    srv = ThreadingHTTPServer((args.bind, args.port), Handler)
    tee = f" (tee -> {OP_URL})" if OP_URL and OP_CLIENT else ""
    print(f"telemetry-home: listening on {args.bind}:{args.port} -> {LOG_FILE}{tee}")
    srv.serve_forever()


if __name__ == "__main__":
    main()
