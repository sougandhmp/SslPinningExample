#!/usr/bin/env python3
"""Tiny HTTPS test server for the SSL pinning sample.

    python3 scripts/server.py --key a    # current key (pinned)
    python3 scripts/server.py --key b    # rotated/backup key (pinned)
    python3 scripts/server.py --key c    # rogue key (CA-trusted, NOT pinned)

Stop it with Ctrl+C and restart it with another --key to simulate a rotation.
"""
import argparse
import json
import pathlib
import ssl
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CERTS = pathlib.Path(__file__).resolve().parent / "certs"

parser = argparse.ArgumentParser()
parser.add_argument("--key", choices=["a", "b", "c"], default="a")
parser.add_argument("--port", type=int, default=8443)
parser.add_argument("--bind", default="0.0.0.0")
args = parser.parse_args()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        body = json.dumps({"ok": True, "servedKey": args.key.upper(), "path": self.path}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *a):
        print(f"[key {args.key.upper()}] {self.client_address[0]} {fmt % a}")


ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain(CERTS / f"cert_{args.key}.pem", CERTS / f"key_{args.key}.pem")

httpd = ThreadingHTTPServer((args.bind, args.port), Handler)
httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
print(f"Serving key {args.key.upper()} on https://{args.bind}:{args.port}  (Ctrl+C to stop)")
try:
    httpd.serve_forever()
except KeyboardInterrupt:
    pass
