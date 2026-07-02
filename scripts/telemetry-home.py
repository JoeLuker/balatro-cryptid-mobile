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

Optionally tees every event onto NATS (--nats-url), one message per event at
balatro.tel.<app>.<EVENT> (app = rebuild|love, from the session token) with a
JSON body {raw, ts, session, event, props}. Pair with a JetStream stream over
'balatro.tel.>' for durable, replayable hoarding. Same contract: phone.log is
canonical, the NATS tee is best-effort.

Run via `just tel-home` (foreground) or install the systemd user unit it
prints with --print-unit. Binds the Tailscale IP only.
"""

import argparse
import json
import os
import queue
import re
import socket
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

NATS_QUEUE: "queue.Queue[str]" = queue.Queue(maxsize=10000)
NATS_URL = None
NATS_PREFIX = "balatro.tel"


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


class NatsPub:
    """Minimal fire-and-forget NATS publisher — raw protocol over TCP, zero deps.
    phone.log is canonical; this tee drops on any error and reconnects lazily
    (an idle server-side disconnect just costs the next publish a reconnect)."""

    def __init__(self, url):
        m = re.match(r"(?:nats://)?([^:/]+)(?::(\d+))?", url)
        self.host, self.port = m.group(1), int(m.group(2) or 4222)
        self.sock = None

    def _connect(self):
        s = socket.create_connection((self.host, self.port), 3)
        s.settimeout(3)
        s.recv(4096)  # INFO banner
        s.sendall(b'CONNECT {"verbose":false,"pedantic":false,"name":"balatro-tel-home"}\r\n')
        self.sock = s

    def _pump(self):
        """Answer server PINGs so long-lived connections aren't dropped as stale."""
        try:
            self.sock.setblocking(False)
            data = self.sock.recv(4096)
            if b"PING" in data:
                self.sock.setblocking(True)
                self.sock.sendall(b"PONG\r\n")
        except (BlockingIOError, OSError):
            pass
        finally:
            if self.sock is not None:
                self.sock.settimeout(3)

    def publish(self, subject, payload):
        for _attempt in (1, 2):
            try:
                if self.sock is None:
                    self._connect()
                self._pump()
                self.sock.sendall(
                    f"PUB {subject} {len(payload)}\r\n".encode() + payload + b"\r\n")
                return True
            except Exception:
                try:
                    if self.sock is not None:
                        self.sock.close()
                except Exception:
                    pass
                self.sock = None
        return False


def nats_subject(line):
    """'epoch session EVENT ...' -> balatro.tel.<app>.<EVENT>. Rebuild sessions are
    'r'-prefixed (Telemetry.kt); the LÖVE build's are bare hex (android-telemetry.lua)."""
    parts = line.split()
    if len(parts) < 3:
        return None
    app = "rebuild" if parts[1].startswith("r") else "love"
    kind = re.sub(r"[^A-Za-z0-9_-]", "_", parts[2])
    return f"{NATS_PREFIX}.{app}.{kind}"


def nats_worker():
    pub = NatsPub(NATS_URL)
    while True:
        line = NATS_QUEUE.get()
        subject = nats_subject(line)
        if not subject:
            continue
        evt = parse_line(line)  # same parser as the OpenPanel tee
        body = {"raw": line}
        if evt:
            body.update(ts=evt["payload"]["timestamp"], session=evt["payload"]["profileId"],
                        event=evt["payload"]["name"], props=evt["payload"]["properties"])
        pub.publish(subject, json.dumps(body).encode())


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
        # journald tee: every event also goes to stdout — under the systemd
        # unit this flows journal → Alloy → Loki, so Grafana sees the live
        # stream with zero extra infra (LogQL: {unit="user@1000.service"}
        # |= "HAND_SCORE"). phone.log stays canonical.
        sys.stdout.write(body)
        sys.stdout.flush()
        self.send_response(204)
        self.end_headers()
        if OP_URL and OP_CLIENT:
            for line in body.splitlines():
                if line.strip():
                    try:
                        OP_QUEUE.put_nowait(line)
                    except queue.Full:
                        break  # shed load; phone.log has everything
        if NATS_URL:
            for line in body.splitlines():
                if line.strip():
                    try:
                        NATS_QUEUE.put_nowait(line)
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
    ap.add_argument("--nats-url", default=None,
                    help="NATS server (e.g. nats://127.0.0.1:4222); tees every event to "
                         "balatro.tel.<app>.<EVENT> — pair with a JetStream stream on "
                         "'balatro.tel.>' for durable hoarding")
    ap.add_argument("--print-unit", action="store_true",
                    help="print a systemd user unit and exit")
    args = ap.parse_args()

    if args.print_unit:
        op_args = ""
        if args.op_url and args.op_client:
            op_args = f" --op-url {args.op_url} --op-client {args.op_client}"
        if args.nats_url:
            op_args += f" --nats-url {args.nats_url}"
        print(UNIT.format(python=sys.executable,
                          script=os.path.abspath(__file__),
                          bind=args.bind, port=args.port, op_args=op_args))
        return

    OP_URL, OP_CLIENT = args.op_url, args.op_client
    if OP_URL and OP_CLIENT:
        threading.Thread(target=op_worker, daemon=True).start()
    global NATS_URL
    NATS_URL = args.nats_url
    if NATS_URL:
        threading.Thread(target=nats_worker, daemon=True).start()

    LOG_DIR.mkdir(exist_ok=True)
    srv = ThreadingHTTPServer((args.bind, args.port), Handler)
    tee = f" (tee -> {OP_URL})" if OP_URL and OP_CLIENT else ""
    if NATS_URL:
        tee += f" (nats -> {NATS_URL})"
    print(f"telemetry-home: listening on {args.bind}:{args.port} -> {LOG_FILE}{tee}")
    srv.serve_forever()


if __name__ == "__main__":
    main()
