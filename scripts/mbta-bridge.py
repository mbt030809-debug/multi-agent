#!/usr/bin/env python3
"""
MbtA Unified Local Bridge
Listens on 127.0.0.1:8765 and bridges Termux CLI, Claude Code, and Antigravity CLI
with per-session conversation continuity.
Usage: python3 ~/multi-agent/scripts/mbta-bridge.py
"""
import http.server
import json
import os
import subprocess
import sys
import threading

SESSION_FILE = os.path.expanduser("~/.cache/mbta-bridge-sessions.json")
SESSION_LOCK = threading.Lock()


def load_sessions():
    try:
        with open(SESSION_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_sessions(sessions):
    os.makedirs(os.path.dirname(SESSION_FILE), exist_ok=True)
    with open(SESSION_FILE, "w", encoding="utf-8") as f:
        json.dump(sessions, f, ensure_ascii=False, indent=2)


def extract_json_payload(stdout_text):
    for line in stdout_text.splitlines():
        s = line.strip()
        if s.startswith("{") and s.endswith("}"):
            try:
                return json.loads(s)
            except Exception:
                pass
    idx = stdout_text.find("{")
    if idx != -1:
        try:
            return json.loads(stdout_text[idx:])
        except Exception:
            pass
    return None


def run_cli(cmd, timeout=240):
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
            self._send_json(200, {"status": "ok", "endpoints": ["/claude", "/agy", "/exec"]})
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

        mbta_session_id = data.get("mbta_session_id", "")

        if self.path == "/claude":
            key = f"claude:{mbta_session_id}" if mbta_session_id else ""
            cmd = ["claude", "-p", prompt, "--output-format", "json"]
            if key:
                with SESSION_LOCK:
                    cli_sid = load_sessions().get(key)
                if cli_sid:
                    cmd.extend(["--resume", cli_sid])
            proc = run_cli(cmd)
            if proc.returncode != 0:
                self._send_json(500, {"error": proc.stderr or proc.stdout or "claude failed"})
                return
            parsed = extract_json_payload(proc.stdout)
            if parsed:
                usage = parsed.get("usage", {})
                new_sid = parsed.get("session_id", "")
                if key and new_sid:
                    with SESSION_LOCK:
                        sessions = load_sessions()
                        sessions[key] = new_sid
                        save_sessions(sessions)
                self._send_json(200, {
                    "response": parsed.get("result", proc.stdout),
                    "input_tokens": usage.get("input_tokens", 0),
                    "output_tokens": usage.get("output_tokens", 0),
                    "session_id": new_sid
                })
            else:
                self._send_json(200, {"response": proc.stdout.strip()})
            return

        if self.path == "/agy":
            key = f"agy:{mbta_session_id}" if mbta_session_id else ""
            cmd = ["agy", "-p", prompt, "--output-format", "json"]
            if key:
                with SESSION_LOCK:
                    cli_sid = load_sessions().get(key)
                if cli_sid:
                    cmd.extend(["--resume", cli_sid])
            proc = run_cli(cmd)
            if proc.returncode != 0:
                self._send_json(500, {"error": proc.stderr or proc.stdout or "agy failed"})
                return
            parsed = extract_json_payload(proc.stdout)
            if parsed:
                text = parsed.get("result") or parsed.get("response") or parsed.get("text") or proc.stdout
                new_sid = parsed.get("conversation_id") or parsed.get("session_id") or ""
                if key and new_sid:
                    with SESSION_LOCK:
                        sessions = load_sessions()
                        sessions[key] = new_sid
                        save_sessions(sessions)
                usage = parsed.get("usage", {})
                self._send_json(200, {
                    "response": text,
                    "input_tokens": usage.get("input_tokens", 0),
                    "output_tokens": usage.get("output_tokens", 0)
                })
            else:
                lines = [l for l in proc.stdout.splitlines() if not l.startswith("ERROR: logging before google.Init")]
                self._send_json(200, {"response": "\n".join(lines).strip() or proc.stdout.strip()})
            return

        self._send_json(404, {"error": f"Unknown endpoint: {self.path}"})

    def log_message(self, format, *args):
        sys.stderr.write("[MbtA-Bridge] " + (format % args) + "\n")


if __name__ == "__main__":
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 8765), BridgeHandler)
    print("MbtA Bridge listening on http://127.0.0.1:8765 (/exec, /claude, /agy)")
    server.serve_forever()
