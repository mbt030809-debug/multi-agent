#!/usr/bin/env python3
"""
MbtA Unified Local Bridge
Listens on 127.0.0.1:8765 and bridges Termux CLI, Claude Code, and Antigravity CLI
with per-session conversation continuity.
Usage: python3 ~/multi-agent/scripts/mbta-bridge.py
"""
import datetime
import glob
import base64
import http.server
import time
import uuid
import json
import os
import re
import sqlite3
import subprocess
import sys
import threading

SESSION_FILE = os.path.expanduser("~/.cache/mbta-bridge-sessions.json")
SESSION_LOCK = threading.Lock()
QUOTA_CACHE = {}
QUOTA_LOCK = threading.Lock()


def iso_to_ms(value):
    """ISO8601 문자열(예: 2026-11-28T08:00:00.123+00:00)을 epoch ms로 변환. 실패하면 0"""
    if not value or not isinstance(value, str):
        return 0
    try:
        from datetime import datetime, timezone
        v = value.strip().replace("Z", "+00:00")
        dt = datetime.fromisoformat(v)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return int(dt.timestamp() * 1000)
    except Exception:
        return 0


def duration_to_ms(text):
    """'133h24m19s', 'in 2h 30m', '3d 4h' 같은 남은 시간 문구를 ms로 변환. 못 읽으면 0"""
    if not text:
        return 0
    total = 0
    for num, unit in re.findall(r"(\d+)\s*([dhms])(?![a-z])", str(text).lower()):
        n = int(num)
        total += n * {"d": 86400, "h": 3600, "m": 60, "s": 1}[unit]
    return total * 1000


def fetch_agy_quota():
    try:
        proc = subprocess.run(
            ["agy", "-p", "/usage", "--output-format", "text"],
            capture_output=True, text=True, timeout=25
        )
        out = (proc.stdout or "") + "\n" + (proc.stderr or "")
        g5 = g7 = c5 = c7 = None
        reset5 = reset7 = 0
        now_ms = int(time.time() * 1000)
        for line in out.splitlines():
            parts = [p.strip() for p in line.split("\t")]
            if len(parts) < 3:
                continue
            group = parts[0].lower()
            metric = parts[1].lower()
            m = re.search(r"(\d{1,3})\s*%", parts[2])
            if not m:
                continue
            val = max(0, min(100, int(m.group(1))))
            rest_ms = duration_to_ms(" ".join(parts[3:])) if len(parts) > 3 else 0
            if "gemini" in group:
                if "five hour" in metric or "5" in metric:
                    g5 = val
                    if rest_ms:
                        reset5 = now_ms + rest_ms
                elif "weekly" in metric or "week" in metric:
                    g7 = val
                    if rest_ms:
                        reset7 = now_ms + rest_ms
            elif "claude" in group or "gpt" in group:
                if "five hour" in metric or "5" in metric:
                    c5 = val
                elif "weekly" in metric or "week" in metric:
                    c7 = val
        primary = g5 if g5 is not None else (g7 if g7 is not None else c5)
        if primary is None:
            return {"ok": False, "quotaPercent": None, "quotaLabel": ""}
        lbl_parts = []
        if g5 is not None:
            lbl_parts.append(f"5시간 {g5}%")
        if g7 is not None:
            lbl_parts.append(f"주간 {g7}%")
        if c5 is not None and c7 is not None:
            lbl_parts.append(f"Claude/GPT 5시간 {c5}% · 주간 {c7}%")
        return {"ok": True, "quotaPercent": primary, "quotaLabel": " · ".join(lbl_parts),
                "fiveHourResetsAt": reset5, "weekResetsAt": reset7}
    except Exception as e:
        return {"ok": False, "quotaPercent": None, "quotaLabel": "", "error": str(e)}


def fetch_claude_quota():
    try:
        import urllib.request
        cred_path = os.path.expanduser("~/.claude/.credentials.json")
        if not os.path.exists(cred_path):
            return {"ok": False, "quotaPercent": None, "quotaLabel": ""}
        with open(cred_path, "r", encoding="utf-8") as f:
            creds = json.load(f)
        tok = (creds.get("claudeAiOauth") or {}).get("accessToken") or creds.get("accessToken")
        if not tok:
            return {"ok": False, "quotaPercent": None, "quotaLabel": ""}
        req = urllib.request.Request(
            "https://api.anthropic.com/api/oauth/usage",
            headers={
                "Authorization": f"Bearer {tok}",
                "anthropic-beta": "oauth-2025-04-20",
                "Content-Type": "application/json",
            },
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        fh = data.get("five_hour") or {}
        sd = data.get("seven_day") or {}
        h5 = max(0, min(100, round(100.0 - float(fh["utilization"])))) if isinstance(fh.get("utilization"), (int, float)) else None
        d7 = max(0, min(100, round(100.0 - float(sd["utilization"])))) if isinstance(sd.get("utilization"), (int, float)) else None
        primary = h5 if h5 is not None else d7
        if primary is None:
            return {"ok": False, "quotaPercent": None, "quotaLabel": ""}
        parts = []
        if h5 is not None:
            parts.append(f"5시간 {h5}%")
        if d7 is not None:
            parts.append(f"주간 {d7}%")
        return {"ok": True, "quotaPercent": primary, "quotaLabel": " · ".join(parts),
                "fiveHourResetsAt": iso_to_ms(fh.get("resets_at")), "weekResetsAt": iso_to_ms(sd.get("resets_at"))}
    except Exception as e:
        return {"ok": False, "quotaPercent": None, "quotaLabel": "", "error": str(e)}


def fetch_real_quota(kind, force=False):
    now = time.time()
    with QUOTA_LOCK:
        cached = QUOTA_CACHE.get(kind)
        if not force and cached and (now - cached[0] < 120):
            return cached[1]
    res = fetch_claude_quota() if kind == "claude" else fetch_agy_quota()
    if res.get("ok"):
        with QUOTA_LOCK:
            QUOTA_CACHE[kind] = (time.time(), res)
    return res


DOWNLOAD_ROOTS = [os.path.expanduser("~"), "/storage/emulated/0", "/sdcard"]
DOWNLOAD_EXTS = {"apk", "zip", "pdf", "png", "jpg", "jpeg", "gif", "webp", "mp4", "mp3", "csv", "xls", "xlsx", "doc", "docx", "ppt", "pptx", "tar", "gz", "7z", "html", "htm", "txt", "md", "json", "log"}


def safe_download_path(raw):
    """앱에서 파일 카드로 내려받을 수 있는 경로만 허용 (홈 폴더/공용 저장소 하위의 실제 파일)"""
    if not raw:
        return ""
    p = os.path.realpath(os.path.expanduser(raw))
    if not os.path.isfile(p):
        return ""
    # 자격증명 등이 새지 않도록: 산출물 확장자만, 숨김 파일/폴더(.claude, .bashrc 등) 차단
    ext = os.path.splitext(p)[1].lower().lstrip(".")
    if ext not in DOWNLOAD_EXTS:
        return ""
    for root in DOWNLOAD_ROOTS:
        rr = os.path.realpath(root)
        if p.startswith(rr + os.sep):
            rel = p[len(rr) + 1:]
            if any(part.startswith(".") for part in rel.split(os.sep)):
                return ""
            return p
    return ""


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


PROGRESS_LOCK = threading.Lock()
PROGRESS_MAP = {}
ACTIVE_PROCS = {}
ACTIVE_STARTED = {}
MODELS_CACHE = {}
JOBS_LOCK = threading.Lock()
JOBS_MAP = {}
ASK_CAPTURE = {}

ASK_SYSTEM_PROMPT = (
    "사용자의 결정이 필요하거나 질문할 때는 반드시 AskUserQuestion 도구로 선택지(2~4개)와 함께 질문하세요. "
    "도구를 쓸 수 없으면 답변 끝에 <<ASK>>{\"question\":\"질문\",\"options\":[\"선택지1\",\"선택지2\"]}<</ASK>> 형식으로만 질문하세요. "
    "질문이 아닌 일반 답변에는 질문창용 선택지를 만들지 마세요."
)


def fetch_agy_models():
    try:
        proc = subprocess.run(["agy", "models"], capture_output=True, text=True, timeout=15)
        out = []
        for line in (proc.stdout or "").splitlines():
            line = line.strip()
            if not line or line.startswith("Fetching") or line.startswith("ERROR:"):
                continue
            parts = re.split(r"\t|\s{2,}", line, maxsplit=1)
            if len(parts) == 2 and parts[1].strip():
                out.append(parts[1].strip())
        if out:
            return out
    except Exception:
        pass
    return [
        "Gemini 4 Neon (Medium)",
        "Gemini 4 Neon (High)",
        "Gemini 4 Neon (Low)",
        "Gemini 4 Hydrogen (Low)",
        "Claude Sonnet 5.5 (Medium)",
        "Claude Opus 5.5 (Medium)",
    ]


def fetch_claude_models():
    models = ["sonnet", "opus", "haiku", "claude-sonnet-4-6", "claude-opus-4-6", "claude-haiku-4-5"]
    try:
        cfg_path = os.path.expanduser("~/.claude/settings.json")
        if os.path.exists(cfg_path):
            with open(cfg_path, "r", encoding="utf-8") as f:
                cfg = json.load(f)
            m = (cfg.get("model") or "").strip()
            if m and m not in models:
                models.insert(0, m)
    except Exception:
        pass
    return models


def append_progress(key, line):
    if not key or not line:
        return
    clean = line.strip()
    if not clean or "Initialized Android local timezone" in clean:
        return
    with PROGRESS_LOCK:
        arr = PROGRESS_MAP.setdefault(key, [])
        arr.append(clean[:160])
        if len(arr) > 30:
            del arr[:-30]


def load_agy_messages(sid, limit=40):
    tpath = os.path.expanduser(f"~/.gemini/antigravity-cli/brain/{sid}/.system_generated/logs/transcript.jsonl")
    msgs = []
    try:
        with open(tpath, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                if not line.strip():
                    continue
                try:
                    obj = json.loads(line)
                except Exception:
                    continue
                t = obj.get("type")
                if t == "USER_INPUT":
                    c = str(obj.get("content") or "")
                    m = re.search(r"<USER_REQUEST>\s*(.*?)\s*</USER_REQUEST>", c, re.S)
                    txt = (m.group(1) if m else c).strip()
                    if txt:
                        msgs.append({"role": "user", "content": txt})
                elif t == "PLANNER_RESPONSE":
                    txt = str(obj.get("content") or "").strip()
                    if txt:
                        msgs.append({"role": "assistant", "content": txt})
    except Exception:
        pass
    return msgs[-limit:]


def load_claude_messages(sid, limit=40):
    import glob
    matches = glob.glob(os.path.expanduser(f"~/.claude/projects/*/{sid}.jsonl"))
    if not matches:
        return []
    msgs = []
    try:
        with open(matches[0], "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                if not line.strip():
                    continue
                try:
                    obj = json.loads(line)
                except Exception:
                    continue
                t = obj.get("type")
                if t in ("user", "assistant"):
                    msg = obj.get("message", {})
                    content = msg.get("content") if isinstance(msg, dict) else ""
                    if isinstance(content, list):
                        content = "\n".join(
                            b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text"
                        )
                    txt = str(content or "").strip()
                    if txt and not txt.startswith(("<task-notification>", "<system-reminder>", "<command-name>", "<local-command-stdout>")):
                        msgs.append({"role": t, "content": txt})
    except Exception:
        pass
    return msgs[-limit:]


def run_cli(cmd, cwd=None, timeout=1800, progress_key="", stream_mode=False):
    work_dir = cwd if (cwd and os.path.isdir(cwd)) else None
    if not progress_key:
        return subprocess.run(cmd, cwd=work_dir, capture_output=True, text=True, timeout=timeout)
    with PROGRESS_LOCK:
        PROGRESS_MAP[progress_key] = [f"▶ {' '.join(cmd[:3])} 실행 중..."]
        ACTIVE_STARTED[progress_key] = time.time()
    proc = subprocess.Popen(
        cmd,
        cwd=work_dir,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    with PROGRESS_LOCK:
        ACTIVE_PROCS[progress_key] = proc
    stderr_lines = []
    stdout_lines = []
    final_result = None
    delta_buf = ""

    def _read_err():
        try:
            for line in proc.stderr:
                stderr_lines.append(line)
                append_progress(progress_key, line)
        except Exception:
            pass

    t = threading.Thread(target=_read_err, daemon=True)
    t.start()
    try:
        if stream_mode and proc.stdout:
            for line in proc.stdout:
                stdout_lines.append(line)
                s = line.strip()
                if not s:
                    continue
                try:
                    obj = json.loads(s)
                except Exception:
                    continue
                ev = obj.get("event")
                tp = obj.get("type")
                if ev == "init":
                    append_progress(progress_key, "🔗 Antigravity 세션 연결됨")
                elif ev == "step_update":
                    su = obj.get("step_update") or {}
                    st = su.get("step_type", "")
                    state = su.get("state", "")
                    delta = su.get("text_delta", "")
                    if delta:
                        delta_buf = (delta_buf + delta)[-300:]
                        with PROGRESS_LOCK:
                            arr = PROGRESS_MAP.setdefault(progress_key, [])
                            arr[:] = [x for x in arr if not x.startswith("⚙️ ")]
                            msg = f"💬 {delta_buf.replace(chr(10), ' ')}"
                            if arr and arr[-1].startswith("💬 "):
                                arr[-1] = msg
                            else:
                                arr.append(msg)
                    elif st and st != "user_input":
                        if state in ("done", "completed", "finished", "idle", ""):
                            continue
                        detail = su.get("tool_name") or su.get("name") or su.get("summary") or su.get("title") or ""
                        if detail:
                            with PROGRESS_LOCK:
                                arr = PROGRESS_MAP.setdefault(progress_key, [])
                                item = f"🔧 {detail}"
                                if not arr or arr[-1] != item:
                                    arr.append(item)
                        else:
                            with PROGRESS_LOCK:
                                arr = PROGRESS_MAP.setdefault(progress_key, [])
                                if not any(x.startswith("💬 ") for x in arr):
                                    label = "⚙️ 도구 작업 중..." if st == "tool" else f"⚙️ {st} 진행 중..."
                                    if arr and arr[-1].startswith("⚙️ "):
                                        arr[-1] = label
                                    else:
                                        arr.append(label)
                elif ev == "result":
                    final_result = obj.get("result")
                elif tp == "system" and obj.get("subtype") == "init":
                    append_progress(progress_key, "🔗 Claude 세션 연결됨")
                elif tp == "assistant":
                    msg = obj.get("message") or {}
                    for blk in (msg.get("content") or []):
                        if isinstance(blk, dict):
                            if blk.get("type") == "tool_use":
                                if blk.get("name") == "AskUserQuestion" and isinstance(blk.get("input"), dict):
                                    ASK_CAPTURE[progress_key] = blk["input"]
                                append_progress(progress_key, f"🔧 도구 실행: {blk.get('name', '')}")
                            elif blk.get("type") == "text" and blk.get("text"):
                                append_progress(progress_key, f"💬 {blk['text'][:100].replace(chr(10), ' ')}")
                elif tp == "result":
                    final_result = obj
            proc.wait(timeout=timeout)
            out = json.dumps(final_result, ensure_ascii=False) if final_result is not None else "".join(stdout_lines)
        else:
            out, _ = proc.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        proc.kill()
        out, _ = proc.communicate()
    finally:
        with PROGRESS_LOCK:
            if ACTIVE_PROCS.get(progress_key) is proc:
                ACTIVE_PROCS.pop(progress_key, None)
            ACTIVE_STARTED.pop(progress_key, None)
    t.join(timeout=1)
    return subprocess.CompletedProcess(cmd, proc.returncode, out or "", "".join(stderr_lines))


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
            self._send_json(200, {"status": "ok", "endpoints": ["/claude", "/agy", "/exec", "/sessions", "/progress"]})
        elif self.path.startswith("/filestat") or self.path.startswith("/download"):
            from urllib.parse import urlparse, parse_qs
            qs = parse_qs(urlparse(self.path).query)
            raw = (qs.get("path") or [""])[0]
            full = safe_download_path(raw)
            if not full:
                self._send_json(404, {"error": "not found or not allowed"})
                return
            if self.path.startswith("/filestat"):
                self._send_json(200, {"name": os.path.basename(full), "size": os.path.getsize(full)})
                return
            import mimetypes
            ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
            size = os.path.getsize(full)
            from urllib.parse import quote
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(size))
            self.send_header("Content-Disposition", "attachment; filename*=UTF-8''" + quote(os.path.basename(full)))
            self.end_headers()
            with open(full, "rb") as f:
                while True:
                    chunk = f.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
        elif self.path.startswith("/progress"):
            from urllib.parse import urlparse, parse_qs
            qs = parse_qs(urlparse(self.path).query)
            sid = (qs.get("session_id") or [""])[0]
            with PROGRESS_LOCK:
                lines = list(PROGRESS_MAP.get(sid, []))
                proc = ACTIVE_PROCS.get(sid)
                alive = proc is not None and proc.poll() is None
                started = ACTIVE_STARTED.get(sid)
            elapsed_sec = int(time.time() - started) if (alive and started) else 0
            if alive:
                mins, secs = divmod(elapsed_sec, 60)
                t_str = f"{mins}분 {secs}초" if mins > 0 else f"{secs}초"
                lines = [f"🟢 프로세스 활성 · {t_str} 경과"] + lines
            self._send_json(200, {"lines": lines, "alive": alive, "elapsed_sec": elapsed_sec})
        elif self.path.startswith("/session_messages"):
            from urllib.parse import urlparse, parse_qs
            qs = parse_qs(urlparse(self.path).query)
            kind = (qs.get("kind") or ["agy"])[0]
            sid = (qs.get("id") or [""])[0]
            msgs = load_agy_messages(sid) if kind == "agy" else load_claude_messages(sid)
            self._send_json(200, {"messages": msgs})
        elif self.path.startswith("/models"):
            from urllib.parse import urlparse, parse_qs
            qs = parse_qs(urlparse(self.path).query)
            kind = (qs.get("kind") or ["agy"])[0]
            now = time.time()
            cached = MODELS_CACHE.get(kind)
            if cached and now - cached[0] < 600:
                models = cached[1]
            else:
                models = fetch_claude_models() if kind == "claude" else fetch_agy_models()
                MODELS_CACHE[kind] = (now, models)
            self._send_json(200, {"models": models})
        elif self.path.startswith("/quota"):
            from urllib.parse import urlparse, parse_qs
            qs = parse_qs(urlparse(self.path).query)
            kind = (qs.get("kind") or ["agy"])[0]
            force = (qs.get("force") or ["0"])[0] in ("1", "true", "yes")
            res = fetch_real_quota(kind, force=force)
            self._send_json(200, res)
        elif self.path.startswith("/job_status"):
            from urllib.parse import urlparse, parse_qs
            qs = parse_qs(urlparse(self.path).query)
            jid = (qs.get("job_id") or [""])[0]
            with JOBS_LOCK:
                job = dict(JOBS_MAP.get(jid, {"status": "error", "error": "job not found"}))
            self._send_json(200, job)
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

        if self.path == "/cancel":
            sid = (data.get("session_id") or "").strip()
            with PROGRESS_LOCK:
                proc = ACTIVE_PROCS.pop(sid, None)
            if proc is not None:
                try:
                    proc.kill()
                except Exception:
                    pass
                self._send_json(200, {"cancelled": True})
            else:
                self._send_json(200, {"cancelled": False})
            return

        if self.path == "/upload":
            raw_name = os.path.basename((data.get("filename") or "file.bin").strip())
            safe_name = re.sub(r"[^A-Za-z0-9._-]", "_", raw_name) or "file.bin"
            try:
                raw_bytes = base64.b64decode(data.get("base64") or "")
            except Exception as e:
                self._send_json(400, {"error": f"Invalid base64: {e}"})
                return
            up_dir = os.path.expanduser("~/mbta-uploads")
            os.makedirs(up_dir, exist_ok=True)
            full_path = os.path.join(up_dir, f"{int(time.time())}_{safe_name}")
            with open(full_path, "wb") as f:
                f.write(raw_bytes)
            self._send_json(200, {"path": full_path})
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

        if self.path == "/start_job":
            import urllib.request
            job_id = str(uuid.uuid4())
            endpoint = data.get("endpoint") or "/agy"
            if endpoint not in ("/claude", "/agy"):
                endpoint = "/agy"
            with JOBS_LOCK:
                JOBS_MAP[job_id] = {"status": "running"}

            def _worker():
                try:
                    req_body = json.dumps(data).encode("utf-8")
                    req = urllib.request.Request(
                        f"http://127.0.0.1:8765{endpoint}",
                        data=req_body,
                        headers={"Content-Type": "application/json"},
                        method="POST",
                    )
                    with urllib.request.urlopen(req, timeout=1850) as resp:
                        res = json.loads(resp.read().decode("utf-8"))
                    with JOBS_LOCK:
                        JOBS_MAP[job_id] = {
                            "status": "done",
                            "response": res.get("response", ""),
                            "input_tokens": res.get("input_tokens", 0),
                            "output_tokens": res.get("output_tokens", 0),
                            "model": res.get("model", ""),
                        }
                except Exception as e:
                    err_msg = str(e)
                    if hasattr(e, "read"):
                        try:
                            err_body = json.loads(e.read().decode("utf-8"))
                            err_msg = err_body.get("error", err_msg)
                        except Exception:
                            pass
                    with JOBS_LOCK:
                        JOBS_MAP[job_id] = {"status": "error", "error": err_msg}

            threading.Thread(target=_worker, daemon=True).start()
            self._send_json(200, {"job_id": job_id})
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
            extra_sys = (data.get("system_prompt") or "").strip()
            sys_prompt = ASK_SYSTEM_PROMPT + (("\n\n" + extra_sys) if extra_sys else "")
            cmd = ["claude", "-p", prompt, "--output-format", "stream-json", "--verbose",
                   "--append-system-prompt", sys_prompt]
            if data.get("model"):
                cmd.extend(["--model", data["model"]])
            effort = (data.get("effort") or "").strip().lower()
            if effort in ("low", "medium", "high", "max"):
                cmd.extend(["--effort", effort])
            if data.get("skip_permissions"):
                cmd.append("--dangerously-skip-permissions")
            elif permission_mode in ("plan", "acceptEdits", "auto", "default", "bypassPermissions", "dontAsk"):
                cmd.extend(["--permission-mode", permission_mode])
            if key:
                with SESSION_LOCK:
                    cli_sid = load_sessions().get(key)
                if not cli_sid and data.get("cli_session_id"):
                    cli_sid = str(data.get("cli_session_id")).strip()
                if cli_sid:
                    cmd.extend(["--resume", cli_sid])
            ASK_CAPTURE.pop(mbta_session_id, None)
            proc = run_cli(cmd, cwd=cwd, progress_key=mbta_session_id, stream_mode=True)
            if proc.returncode != 0:
                self._send_json(500, {"error": proc.stderr or proc.stdout or "claude failed"})
                return
            parsed = extract_json_payload(proc.stdout)
            if parsed:
                ask = ASK_CAPTURE.pop(mbta_session_id, None)
                if ask and "<<ASK>>" not in str(parsed.get("result", "")):
                    parsed["result"] = (str(parsed.get("result", "")).rstrip() + "\n\n<<ASK>>"
                                        + json.dumps(ask, ensure_ascii=False) + "<</ASK>>")
                usage = parsed.get("usage", {})
                new_sid = parsed.get("session_id", "")
                if key and new_sid:
                    with SESSION_LOCK:
                        sessions = load_sessions()
                        sessions[key] = new_sid
                        save_sessions(sessions)
                mu = parsed.get("modelUsage")
                served = next(iter(mu.keys()), "") if isinstance(mu, dict) and mu else ""
                self._send_json(200, {
                    "response": parsed.get("result", proc.stdout),
                    "input_tokens": usage.get("input_tokens", 0),
                    "output_tokens": usage.get("output_tokens", 0),
                    "session_id": new_sid,
                    "model": served
                })
            else:
                self._send_json(200, {"response": proc.stdout.strip()})
            return

        if self.path == "/agy":
            key = f"agy:{mbta_session_id}" if mbta_session_id else ""
            cwd = (data.get("cwd") or "").strip()
            model = normalize_agy_model(data.get("model") or "")
            permission_mode = (data.get("permission_mode") or "").strip()
            agy_sys = (data.get("system_prompt") or "").strip()
            if agy_sys:
                prompt = agy_sys + "\n\n" + prompt
            cmd = ["agy", "-p", prompt, "--output-format", "stream-json"]
            if data.get("skip_permissions") or permission_mode == "auto":
                cmd.extend(["--mode", "auto", "--dangerously-skip-permissions"])
            elif permission_mode == "plan":
                cmd.extend(["--mode", "plan"])
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
            proc = run_cli(cmd, cwd=cwd, progress_key=mbta_session_id, stream_mode=True)
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
