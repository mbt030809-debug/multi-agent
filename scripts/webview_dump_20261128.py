#!/usr/bin/env python3
"""MbtA 앱 WebView 진단 덤프 (Termux + adb 무선 디버깅).
사용: python scripts/webview_dump_20261128.py [주소 필터, 기본 google]
필요: pkg install android-tools python / pip install websocket-client
"""
import json, subprocess, sys, urllib.request

PORT = 9222
FILTER = sys.argv[1] if len(sys.argv) > 1 else "google"

def sh(*a):
    return subprocess.run(a, capture_output=True, text=True).stdout.strip()

pid = sh("adb", "shell", "pidof", "com.mbta.app")
if not pid:
    sys.exit("앱 프로세스 없음 (앱 실행 후 다시)")
socks = [l.split("@")[-1] for l in sh("adb", "shell", "cat", "/proc/net/unix").splitlines() if "devtools_remote" in l]
sock = next((s for s in socks if s.endswith(pid)), socks[0] if socks else None)
if not sock:
    sys.exit("devtools 소켓 없음 (앱 v0.8.64 이상인지, WebView 세션이 열려 있는지 확인)")
sh("adb", "forward", f"tcp:{PORT}", f"localabstract:{sock}")
pages = json.load(urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json"))
print("pages:")
for p in pages:
    print(" -", p.get("type"), p.get("url", "")[:90])
target = next((p for p in pages if FILTER in p.get("url", "")), None)
if not target:
    sys.exit(f"'{FILTER}' 포함 페이지 없음")

JS = r"""
(function(){
  function vis(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}
  var ed=document.querySelector('rich-textarea [contenteditable], div.ql-editor, [contenteditable]:not([contenteditable="false"]), textarea');
  var out={url:location.href, editor: ed?{tag:ed.tagName,len:((ed.value!==undefined?ed.value:ed.innerText)||'').trim().length}:null, buttons:[]};
  var sc=ed; for(var i=0;i<5&&sc&&sc.parentElement;i++) sc=sc.parentElement;
  var bs=(sc||document).querySelectorAll('button,[role=button]');
  for(var j=0;j<bs.length&&j<30;j++){var b=bs[j];
    out.buttons.push({label:b.getAttribute('aria-label'),text:(b.innerText||'').trim().slice(0,20),cls:(b.className||'').toString().slice(0,50),disabled:b.disabled,ariaDisabled:b.getAttribute('aria-disabled'),vis:vis(b)});}
  return JSON.stringify(out);
})()
"""
try:
    import websocket
except ImportError:
    sys.exit("pip install websocket-client 먼저")
ws = websocket.create_connection(target["webSocketDebuggerUrl"], timeout=10, suppress_origin=True)
ws.send(json.dumps({"id": 1, "method": "Runtime.evaluate", "params": {"expression": JS, "returnByValue": True}}))
res = json.loads(ws.recv())
val = res.get("result", {}).get("result", {}).get("value")
print(json.dumps(json.loads(val), ensure_ascii=False, indent=1) if val else res)
