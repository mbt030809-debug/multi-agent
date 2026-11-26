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
import re
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

        if self.path == "/git/status":
            cwd = os.path.expanduser(data.get("cwd", "~"))
            if not os.path.isdir(cwd):
                self._send_json(200, {"isGit": False, "error": f"디렉토리를 찾을 수 없습니다: {cwd}"})
                return
            branch_p = subprocess.run(["git", "-C", cwd, "rev-parse", "--abbrev-ref", "HEAD"], capture_output=True, text=True)
            if branch_p.returncode != 0:
                self._send_json(200, {"isGit": False, "error": "Git 저장소가 아닙니다."})
                return
            status_p = subprocess.run(["git", "-C", cwd, "status", "--short"], capture_output=True, text=True)
            diff_p = subprocess.run(["git", "-C", cwd, "diff"], capture_output=True, text=True)
            files = [l for l in status_p.stdout.splitlines() if l.strip()]
            ci_summary = ""
            try:
                env = os.environ.copy()
                env.pop("GITHUB_TOKEN", None)
                gh_p = subprocess.run(
                    ["gh", "run", "list", "--limit", "1", "--json", "status,conclusion,displayTitle"],
                    cwd=cwd, env=env, capture_output=True, text=True, timeout=10
                )
                if gh_p.returncode == 0:
                    runs = json.loads(gh_p.stdout)
                    if runs:
                        r = runs[0]
                        st = r.get("conclusion") or r.get("status") or ""
                        ci_summary = f"{st}: {r.get('displayTitle', '')}"
            except Exception:
                pass
            self._send_json(200, {
                "isGit": True,
                "branch": branch_p.stdout.strip(),
                "files": files,
                "diff": diff_p.stdout[:30000],
                "ciSummary": ci_summary
            })
            return

        if self.path == "/git/worktree/create":
            cwd = os.path.expanduser(data.get("cwd", "~"))
            raw_name = data.get("name", "session")
            safe = re.sub(r"[^a-zA-Z0-9_-]", "-", raw_name).strip("-") or "session"
            wt_root = os.path.expanduser("~/.cache/mbta-worktrees")
            os.makedirs(wt_root, exist_ok=True)
            wt_path = os.path.join(wt_root, f"{safe}-{os.getpid()}")
            branch_name = f"mbta/{safe}-{os.getpid()}"
            p = subprocess.run(
                ["git", "-C", cwd, "worktree", "add", "-b", branch_name, wt_path],
                capture_output=True, text=True
            )
            if p.returncode != 0:
                self._send_json(200, {"ok": False, "error": p.stderr.strip()})
            else:
                self._send_json(200, {"ok": True, "worktreePath": wt_path, "branch": branch_name})
            return

        if self.path == "/git/commit":
            cwd = os.path.expanduser(data.get("cwd", "~"))
            msg = data.get("message", "chore: update via MbtA")
            p1 = subprocess.run(["git", "-C", cwd, "add", "-A"], capture_output=True, text=True)
            p2 = subprocess.run(["git", "-C", cwd, "commit", "-m", msg], capture_output=True, text=True)
            ok = p1.returncode == 0 and p2.returncode == 0
            self._send_json(200, {"ok": ok, "output": (p2.stdout + p2.stderr).strip()})
            return

        if self.path == "/git/push":
            cwd = os.path.expanduser(data.get("cwd", "~"))
            env = os.environ.copy()
            env.pop("GITHUB_TOKEN", None)
            p = subprocess.run(["git", "-C", cwd, "push"], env=env, capture_output=True, text=True, timeout=60)
            self._send_json(200, {"ok": p.returncode == 0, "output": (p.stdout + p.stderr).strip()})
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
