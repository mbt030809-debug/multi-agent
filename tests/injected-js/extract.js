// HeadlessWebBridge.kt 안의 주입 스크립트를 꺼내 Kotlin 치환 변수를 채움
const fs = require('fs');
const path = require('path');

const SOURCE = path.join(__dirname, '../../app/src/main/java/com/mbta/app/webview/HeadlessWebBridge.kt');

function rawString(src, name) {
  const m = new RegExp(`(?:val ${name}\\b[^=]*=|fun ${name}\\([\\s\\S]*?return)\\s*"""([\\s\\S]*?)"""`).exec(src);
  if (!m) throw new Error(`스크립트를 찾을 수 없음: ${name}`);
  return m[1];
}

function automationScript({ kind = 'GEMINI_WEB', prompt, checkMs, resume = false }) {
  const src = fs.readFileSync(SOURCE, 'utf8');
  let js = rawString(src, 'buildAutomationJs');
  for (const name of ['DEEP_QUERY_JS', 'ROW_TEXT_JS']) js = js.split('$' + name).join(rawString(src, name));
  const values = {
    $quotedReqId: JSON.stringify('r1'), $quotedKind: JSON.stringify(kind), $quotedPrompt: JSON.stringify(prompt),
    $checkMs: String(checkMs), $resume: String(resume),
  };
  for (const [k, v] of Object.entries(values)) js = js.split(k).join(v);
  const left = js.match(/\$[A-Za-z{]\w*/g);
  if (left) throw new Error(`채우지 못한 Kotlin 변수: ${[...new Set(left)].join(', ')}`);
  return js;
}

module.exports = { automationScript };
