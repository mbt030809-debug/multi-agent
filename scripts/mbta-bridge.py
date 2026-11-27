#!/usr/bin/env python3
"""
MbtA Unified Local Bridge
Listens on 127.0.0.1:8765 and bridges Termux CLI, Claude Code, and Antigravity CLI
with per-session conversation continuity.
Usage: python3 ~/multi-agent/scripts/mbta-bridge.py
"""
import datetime
import glob
import http.server
import json
import os
import re
import sqlite3
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


def normalize_agy_model(raw_model):
    m = (raw_model or "").strip()
    if not m:
        return ""
    mapping = {
        "gemini 4 neon": "gemini-4-neon-medium",
        "gemini 4 neon (low)": "gemini-4-neon-low",
        "gemini 4 neon (medium)": "gemini-4-neon-medium",
        "gemini 4 neon (high)": "gemini-4-neon-high",
        "gemini 4 neon (xhigh)": "gemini-4-neon-xhigh",
        "gemini 4 hydrogen (low)": "gemini-4-hydrogen-low",
        "gemini 4 hydrogen (medium)": "gemini-4-hydrogen-medium",
        "gemini 4 hydrogen (high)": "gemini-4-hydrogen-high",
        "claude sonnet": "claude-sonnet-5-5-medium",
        "claude sonnet 5.5 (medium)": "claude-sonnet-5-5-medium",
        "claude opus": "claude-opus-5-5-medium",
        "claude opus 5.5 (medium)": "claude-opus-5-5-medium",
    }
    return mapping.get(m.lower(), m)


def fmt_mtime(path):
    try:
        from datetime import datetime
        return datetime.fromtimestamp(os.path.getmtime(path)).strftime("%m/%d %H:%M")
    except Exception:
        return ""


def extract_agy_title(brain_dir):
    sid = os.path.basename(brain_dir)
    tpath = os.path.join(brain_dir, ".system_generated", "logs", "transcript.jsonl")
    try:
        with open(tpath, "r", encoding="utf-8", errors="ignore") as f:
            for i, line in enumerate(f):
                if i > 15:
                    break
                obj = json.loads(line)
                if obj.get("type") == "USER_INPUT":
                    c = str(obj.get("content") or "")
                    m = re.search(r"<USER_REQUEST>\s*(.*?)\s*</USER_REQUEST>", c, re.S)
                    txt = (m.group(1) if m else c).strip()
                    txt = re.sub(r"\s+", " ", txt)
                    if txt:
                        return txt[:45]
    except Exception:
        pass
    return f"Antigravity ({sid[:8]})"


def extract_claude_title(jsonl_path):
    proj = os.path.basename(os.path.dirname(jsonl_path)).lstrip("-").replace("-", "/")
    try:
        with open(jsonl_path, "r", encoding="utf-8", errors="ignore") as f:
            for i, line in enumerate(f):
                if i > 20:
                    break
                obj = json.loads(line)
                if obj.get("type") == "user":
                    msg = obj.get("message", {})
                    content = msg.get("content") if isinstance(msg, dict) else ""
                    if isinstance(content, list):
                        content = " ".join(
                            b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text"
                        )
                    txt = re.sub(r"\s+", " ", str(content or "")).strip()
                    if txt:
                        return txt[:45]
    except Exception:
        pass
    return f"Claude · {proj}"


def run_cli(cmd, cwd=None, timeout=300):
    work_dir = cwd if (cwd and os.path.isdir(cwd)) else None
    return subprocess.run(cmd, cwd=work_dir, capture_output=True, text=True, timeout=timeout)


TRIVIAL_GREETINGS = {"ㅎㅇ", "ㅎㅇㅎㅇ", "ㅎㅎ", "ㅎㅎㅇ", "ㅇㅇ", "ㅇㅇㅇ", "ㅇㅇㅇ?", "안녕", "안녕하세요", "hi", "hello", "hey", "test", "테스트"}


def clean_session_title(text, max_len=80):
    if not text:
        return ""
    s = re.sub(r"\s+", " ", str(text)).strip()
    if len(s) > max_len:
        s = s[: max_len - 1].rstrip() + "…"
    return s


def is_trivial_greeting(text):
    s = re.sub(r"[\s!?.,~]+", "", (text or "").lower())
    return s in TRIVIAL_GREETINGS or len(s) <= 2


def extract_claude_session_title(jsonl_path):
    custom_title = ""
    summary = ""
    first_user = ""
    better_user = ""
    try:
        with open(jsonl_path, "r", encoding="utf-8", errors="ignore") as fp:
            for line in fp:
                if not line.strip():
                    continue
                try:
                    obj = json.loads(line)
                except Exception:
                    continue
                t = obj.get("type")
                if t == "custom-title" and obj.get("customTitle"):
                    custom_title = str(obj["customTitle"]).strip()
                elif t == "summary" and obj.get("summary"):
                    summary = str(obj["summary"]).strip()
                elif t == "user":
                    msg = obj.get("message", {})
                    content = msg.get("content", "")
                    if isinstance(content, list):
                        content = " ".join(
                            c.get("text", "")
                            for c in content
                            if isinstance(c, dict) and c.get("type") == "text"
                        )
                    s = str(content).strip()
                    if (
                        s
                        and not s.startswith("<local-command")
                        and not s.startswith("<command-name")
                        and not s.startswith("<system")
                    ):
                        if not first_user:
                            first_user = s
                        elif not better_user and is_trivial_greeting(first_user) and not is_trivial_greeting(s):
                            better_user = s
    except Exception:
        pass
    chosen = custom_title or summary or better_user or first_user
    return clean_session_title(chosen)


def parse_iso_to_ms(ts_str):
    if not ts_str or ts_str.startswith("0001-"):
        return 0
    try:
        s = str(ts_str).strip().replace(" ", "T")
        # Truncate fractional seconds to 6 digits for Python <3.11 compatibility
        s = re.sub(r"(\.\d{6})\d+", r"\1", s)
        if s.endswith("Z"):
            s = s[:-1] + "+00:00"
        dt = datetime.datetime.fromisoformat(s)
        return int(dt.timestamp() * 1000)
    except Exception:
        return 0


def extract_agy_transcript_title(brain_dir):
    tr_path = os.path.join(brain_dir, ".system_generated", "logs", "transcript.jsonl")
    if not os.path.isfile(tr_path):
        return ""
    first_req = ""
    better_req = ""
    try:
        with open(tr_path, "r", encoding="utf-8", errors="ignore") as fp:
            for i, line in enumerate(fp):
                if i > 60:
                    break
                if '"USER_INPUT"' not in line:
                    continue
                try:
                    obj = json.loads(line)
                except Exception:
                    continue
                if obj.get("type") != "USER_INPUT":
                    continue
                content = str(obj.get("content", ""))
                m = re.search(r"<USER_REQUEST>\s*(.*?)\s*</USER_REQUEST>", content, re.S)
                req = (m.group(1) if m else content).strip()
                if not req:
                    continue
                if not first_req:
                    first_req = req
                elif not better_req and is_trivial_greeting(first_req) and not is_trivial_greeting(req):
                    better_req = req
                    break
    except Exception:
        pass
    return clean_session_title(better_req or first_req)


def list_agy_sessions(limit=25):
    summaries = {}
    db_path = os.path.expanduser("~/.gemini/antigravity-cli/conversation_summaries.db")
    if os.path.isfile(db_path):
        try:
            conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=2.0)
            cur = conn.cursor()
            cur.execute(
                "SELECT conversation_id, title, preview, step_count, last_modified_time FROM conversation_summaries"
            )
            for cid, title, preview, step_count, lmt in cur.fetchall():
                summaries[cid] = {
                    "title": (title or "").strip(),
                    "preview": (preview or "").strip(),
                    "step_count": step_count or 0,
                    "updated_at": parse_iso_to_ms(lmt),
                }
            conn.close()
        except Exception:
            pass

    brain_root = os.path.expanduser("~/.gemini/antigravity-cli/brain")
    brain_dirs = {}
    if os.path.isdir(brain_root):
        for d in glob.glob(os.path.join(brain_root, "*")):
            base = os.path.basename(d)
            if os.path.isdir(d) and base != "tempmediaStorage":
                brain_dirs[base] = d

    all_ids = set(brain_dirs.keys()) | set(summaries.keys())
    items = []
    for sid in all_ids:
        info = summaries.get(sid, {})
        bdir = brain_dirs.get(sid)
        title = clean_session_title(info.get("title") or "")
        if not title:
            title = clean_session_title(info.get("preview") or "")
        if not title and bdir:
            title = extract_agy_transcript_title(bdir)
        if not title:
            continue

        updated_at = info.get("updated_at", 0)
        if bdir:
            tr_path = os.path.join(bdir, ".system_generated", "logs", "transcript.jsonl")
            try:
                file_ms = int(os.path.getmtime(tr_path if os.path.isfile(tr_path) else bdir) * 1000)
                if file_ms > updated_at:
                    updated_at = file_ms
            except Exception:
                pass

        items.append({"id": sid, "title": title, "kind": "agy", "updatedAt": updated_at})

    items.sort(key=lambda x: x.get("updatedAt", 0), reverse=True)
    return items[:limit]


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
            self._send_json(200, {"status": "ok", "endpoints": ["/claude", "/agy", "/exec", "/sessions"]})
        elif self.path.startswith("/sessions"):
            kind = "claude" if "kind=claude" in self.path else ("agy" if "kind=agy" in self.path else "all")
            items = []
            if kind in ("claude", "all"):
                files = sorted(glob.glob(os.path.expanduser("~/.claude/projects/*/*.jsonl")), key=os.path.getmtime, reverse=True)
                for f in files:
                    title = extract_claude_session_title(f)
                    if not title:
                        continue
                    sid = os.path.splitext(os.path.basename(f))[0]
                    updated_at = int(os.path.getmtime(f) * 1000)
                    items.append({"id": sid, "title": title, "kind": "claude", "updatedAt": updated_at})
                    if len(items) >= 25:
                        break
            if kind in ("agy", "all"):
                items.extend(list_agy_sessions(limit=25))
            if kind == "all":
                items.sort(key=lambda x: x.get("updatedAt", 0), reverse=True)
                items = items[:30]
            self._send_json(200, {"sessions": items})
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
            cwd = (data.get("cwd") or "").strip()
            permission_mode = (data.get("permission_mode") or "").strip()
            cmd = ["claude", "-p", prompt, "--output-format", "json"]
            if data.get("model"):
                cmd.extend(["--model", data["model"]])
            if permission_mode in ("plan", "acceptEdits", "auto", "default", "bypassPermissions", "dontAsk"):
                cmd.extend(["--permission-mode", permission_mode])
            if key:
                with SESSION_LOCK:
                    cli_sid = load_sessions().get(key)
                if not cli_sid and data.get("cli_session_id"):
                    cli_sid = str(data.get("cli_session_id")).strip()
                if cli_sid:
                    cmd.extend(["--resume", cli_sid])
            proc = run_cli(cmd, cwd=cwd)
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
            cwd = (data.get("cwd") or "").strip()
            model = normalize_agy_model(data.get("model") or "")
            permission_mode = (data.get("permission_mode") or "").strip()
            cmd = ["agy", "-p", prompt, "--output-format", "json"]
            if permission_mode == "plan":
                cmd.extend(["--mode", "plan"])
            elif permission_mode == "auto":
                cmd.extend(["--mode", "auto", "--dangerously-skip-permissions"])
            else:
                cmd.extend(["--mode", "accept-edits"])
            if model:
                cmd.extend(["--model", model])
            if key:
                with SESSION_LOCK:
                    cli_sid = load_sessions().get(key)
                if not cli_sid and data.get("cli_session_id"):
                    cli_sid = str(data.get("cli_session_id")).strip()
                if cli_sid:
                    cmd.extend(["--resume", cli_sid])
            proc = run_cli(cmd, cwd=cwd)
            if proc.returncode != 0 and "invalid model selection" in ((proc.stderr or "") + (proc.stdout or "")).lower():
                fallback_cmd = ["agy", "-p", prompt, "--output-format", "json"]
                if key and cli_sid:
                    fallback_cmd.extend(["--resume", cli_sid])
                proc = run_cli(fallback_cmd, cwd=cwd)
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
