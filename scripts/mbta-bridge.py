#!/usr/bin/env python3
"""
MbtA Unified Local Bridge
Listens on 127.0.0.1:8765 and bridges Termux CLI, Claude Code, and Antigravity CLI.
Usage: python3 ~/multi-agent/scripts/mbta-bridge.py
"""
import http.server
import json
import subprocess
import sys


def run_cli(cmd, timeout=180):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


class BridgeHandler(http.server.BaseHTTPRequestHandler):
    def _send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._send_json(200, {"status": "ok"})
        else:
            self._send_json(404, {"error": "Not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
        try:
            data = json.loads(raw)
        except Exception:
            self._send_json(400, {"error": "Invalid JSON"})
            return

        if self.path == "/exec":
            command = data.get("command", "")
            proc = subprocess.run(command, shell=True, capture_output=True, text=True, timeout=120)
            self._send_json(200, {"output": proc.stdout + proc.stderr, "exitCode": proc.returncode})
            return

        prompt = data.get("prompt", "")
        if not prompt:
            self._send_json(400, {"error": "Missing prompt"})
            return

        if self.path == "/claude":
            cmd = ["claude", "-p", prompt, "--output-format", "json"]
            if data.get("session_id"):
                cmd.extend(["--resume", data["session_id"]])
            proc = run_cli(cmd)
            if proc.returncode != 0:
                self._send_json(500, {"error": proc.stderr or proc.stdout or "claude failed"})
                return
            try:
                parsed = json.loads(proc.stdout)
                usage = parsed.get("usage", {})
                self._send_json(200, {
                    "response": parsed.get("result", proc.stdout),
                    "input_tokens": usage.get("input_tokens", 0),
                    "output_tokens": usage.get("output_tokens", 0),
                    "session_id": parsed.get("session_id", "")
                })
            except Exception:
                self._send_json(200, {"response": proc.stdout})
            return

        if self.path == "/agy":
            proc = run_cli(["agy", "-p", prompt])
            if proc.returncode != 0:
                self._send_json(500, {"error": proc.stderr or proc.stdout or "agy failed"})
                return
            self._send_json(200, {"response": proc.stdout.strip()})
            return

        self._send_json(404, {"error": f"Unknown endpoint: {self.path}"})

    def log_message(self, format, *args):
        sys.stderr.write("[MbtA-Bridge] " + (format % args) + "\n")


if __name__ == "__main__":
    server = http.server.HTTPServer(("127.0.0.1", 8765), BridgeHandler)
    print("MbtA Bridge listening on http://127.0.0.1:8765 (/exec, /claude, /agy)")
    server.serve_forever()
