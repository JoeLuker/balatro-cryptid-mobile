#!/usr/bin/env python3
"""Telemetry phone-home receiver.

The Android build POSTs batched telemetry lines (same format as the on-device
telemetry.log) to /ingest whenever it flushes. This appends them to
~/balatro-telemetry/phone.log so the data lands on this machine the moment it
happens — no adb, no watcher, works from anywhere on the tailnet.

Run via `just tel-home` (foreground) or install the systemd user unit it
prints with --print-unit. Binds the Tailscale IP only.
"""

import argparse
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

LOG_DIR = Path.home() / "balatro-telemetry"
LOG_FILE = LOG_DIR / "phone.log"


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

    def log_message(self, fmt, *args):  # quiet; the payload IS the log
        pass


UNIT = """[Unit]
Description=Balatro phone telemetry receiver
After=tailscaled.service

[Service]
ExecStart={python} {script} --bind {bind} --port {port}
Restart=on-failure

[Install]
WantedBy=default.target
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bind", default="100.87.221.109")
    ap.add_argument("--port", type=int, default=8753)
    ap.add_argument("--print-unit", action="store_true",
                    help="print a systemd user unit and exit")
    args = ap.parse_args()

    if args.print_unit:
        print(UNIT.format(python=sys.executable,
                          script=os.path.abspath(__file__),
                          bind=args.bind, port=args.port))
        return

    LOG_DIR.mkdir(exist_ok=True)
    srv = ThreadingHTTPServer((args.bind, args.port), Handler)
    print(f"telemetry-home: listening on {args.bind}:{args.port} -> {LOG_FILE}")
    srv.serve_forever()


if __name__ == "__main__":
    main()
