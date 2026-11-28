// 가상 시계와 최소한의 Gemini 모양 화면에서 전송·답변 읽기 스크립트를 실행 (실제 웹 페이지 검증은 아님)
const test = require('node:test');
const assert = require('node:assert');
const vm = require('node:vm');
const { automationScript } = require('./extract');

const PROMPT = 'hello world prompt';

function simulate({ checkMs, resume = false, shownPrompt = PROMPT, genStart = 2000, genEnd = 7300 }) {
  let now = 0, seq = 0;
  const timers = new Map(), observers = [], events = [];
  const addTimer = (f, ms, repeat) => { const id = ++seq; timers.set(id, { f, at: now + ms, ms, repeat }); return id; };
  // 이어 읽기(resume)는 새로고침 뒤라 이미 보낸 상태이고 답변이 화면에 있음
  let reply = resume ? '저장된 답변' : null, generating = false, sent = resume, clicks = 0;
  const mutate = () => observers.forEach(o => o.on && o.cb([]));
  const el = (text, extra = {}) => Object.assign({
    innerText: text, textContent: text, tagName: 'DIV', isContentEditable: false, children: [], shadowRoot: null, parentElement: null,
    getAttribute: () => null, getBoundingClientRect: () => ({ width: 100, height: 30, top: 10, bottom: 40, x: 0, y: 0 }),
    querySelector: () => null, querySelectorAll: () => [], closest: () => null, focus() {}, click() {}, dispatchEvent() {},
    compareDocumentPosition: () => 4, contains: () => false,
  }, extra);
  const editor = el('', { isContentEditable: true });
  const sendButton = el('', {
    disabled: false,
    getAttribute: a => (a === 'aria-label' ? '메시지 보내기' : null),
    click() {
      clicks++; sent = true; editor.innerText = '';
      addTimer(() => { generating = true; reply = '부분'; mutate(); }, genStart);
      addTimer(() => { reply = '최종 답변'; generating = false; mutate(); }, genEnd);
    },
  });
  const match = selector => {
    const s = selector.trim(), out = [];
    if (s.includes('rich-textarea')) out.push(editor);
    if (s === 'button[aria-label="메시지 보내기"]') out.push(sendButton);
    if (s.startsWith('model-response .markdown') && reply) out.push(el(reply));
    if (s.startsWith('user-query') && sent) out.push(el(shownPrompt));
    if (s.includes('aria-label*="Stop"') && generating) out.push(el(''));
    return out;
  };
  const body = el('');
  const bodyText = () => 'x'.repeat((reply || '').length + (generating ? 1 : 0));
  Object.defineProperty(body, 'textContent', { get: bodyText });
  Object.defineProperty(body, 'innerText', { get: bodyText });
  const document = {
    body, activeElement: null,
    querySelector: s => match(s)[0] || null,
    querySelectorAll: s => (s === '*' ? [] : match(s)),
    execCommand: (command, _, value) => { if (command === 'insertText') editor.innerText = value; return true; },
  };
  class MutationObserver {
    constructor(cb) { this.cb = cb; this.on = false; observers.push(this); }
    observe() { this.on = true; }
    disconnect() { this.on = false; }
  }
  const record = type => (_, value) => events.push({ at: now, type, value });
  const getComputedStyle = () => ({ display: 'block', visibility: 'visible', opacity: '1' });
  const window = { getComputedStyle, MbtABridge: { onChunk: record('chunk'), onDone: record('done'), onError: record('error') } };
  class FakeDate extends Date { static now() { return now; } }
  const context = {
    window, document, MutationObserver, getComputedStyle, Date: FakeDate,
    location: { hostname: 'gemini.google.com', pathname: '/app' }, innerHeight: 700, innerWidth: 400,
    Node: { DOCUMENT_POSITION_FOLLOWING: 4 }, Event: class {}, InputEvent: class {}, KeyboardEvent: class {},
    setTimeout: (f, ms) => addTimer(f, ms, false), setInterval: (f, ms) => addTimer(f, ms, true),
    clearTimeout: id => timers.delete(id), clearInterval: id => timers.delete(id),
  };
  vm.createContext(context);
  vm.runInContext(automationScript({ prompt: PROMPT, checkMs, resume }), context);
  while (now < 240000 && timers.size && !events.some(e => e.type !== 'chunk')) {
    const [id, t] = [...timers].reduce((a, b) => (b[1].at < a[1].at ? b : a));
    now = t.at;
    if (t.repeat) t.at = now + Math.max(t.ms, 1); else timers.delete(id);
    t.f();
  }
  return {
    last: events[events.length - 1], clicks,
    observersLeft: observers.filter(o => o.on).length, intervalHookLeft: Boolean(window.__mbtaSetInterval),
  };
}

function assertDone(result, text, latestMs) {
  assert.strictEqual(result.last.type, 'done');
  assert.strictEqual(result.last.value, text);
  assert.ok(result.last.at <= latestMs, `완료가 늦음: ${result.last.at}ms`);
  assert.strictEqual(result.observersLeft, 0);
  assert.strictEqual(result.intervalHookLeft, false);
}

test('보는 중(3초): 한 번 전송하고 생성 종료 직후 완료', () => {
  const r = simulate({ checkMs: 3000 });
  assert.strictEqual(r.clicks, 1);
  assertDone(r, '최종 답변', 12000);
});

test('안 보는 중(15초): 생성 종료는 확인 간격을 기다리지 않음', () => {
  assertDone(simulate({ checkMs: 15000 }), '최종 답변', 12000);
});

test('안 보는 중(15초): 긴 생성도 종료 직후 완료', () => {
  assertDone(simulate({ checkMs: 15000, genEnd: 20000 }), '최종 답변', 26000);
});

test('이어 읽기: 다시 보내지 않고 내 글 뒤의 답변을 읽음', () => {
  const r = simulate({ checkMs: 15000, resume: true });
  assert.strictEqual(r.clicks, 0);
  assertDone(r, '저장된 답변', 46000);
});

test('이어 읽기: 마지막 사용자 글이 다르면 답변을 가져오지 않음', () => {
  const r = simulate({ checkMs: 15000, resume: true, shownPrompt: '전혀 다른 질문' });
  assert.strictEqual(r.clicks, 0);
  assert.strictEqual(r.last.type, 'error');
});
