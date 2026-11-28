# MbtA 핸드오프 → TM(Termux) 세션 (2026-11-28)

## 목적
폰 직접 접근이 필요한 **Gemini 웹 전송 실패** 원인 확정 (클라우드 세션은 폰 접근 불가)

## 현재 상태
- 저장소 `mbt030809-debug/multi-agent`, 개발 브랜치 `claude/happy-dirac-7i5ch2`
- 배포 완료: main = v0.8.64 (PR #16). 설치 앱 버전이 0.8.64인지 확인
- 이후 dev 브랜치에 진단 스크립트 1개만 추가 (`d6d74ca`, 앱 변경 없음)

## 증상 (Gemini 웹 연결 세션)
1. 입력창에 명령 글자는 들어가는데 전송이 안 됨 (화면에 글자 + 파란 ↑ 버튼 보임)
2. 3분 뒤 "응답 시간이 초과되었습니다" → 다음 요청도 "메인 오케스트레이터 분석 중..."만 뜨고 Gemini 웹에 전달 안 됨
3. 잘 되다가 갑자기 안 됨 (Gemini 웹 화면 구조 변경 / 긴 오케스트레이터 프롬프트 의심)
4. Gemini 웹 헤더는 "Gemini Neon", 앱 모델 칩은 "Gemini 2.5 Pro" (불일치)

## 코드 분석 결과 (확정 아님)
- 실패 단계: 입력창 채움까지 성공 → **전송 클릭 단계에서 무효**
- 파일: `app/src/main/java/com/mbta/app/webview/HeadlessWebBridge.kt` `buildAutomationJs` → `proceedWithInput`
  - 입력: `execCommand('insertText')` + InputEvent
  - 전송: `trySend()` (최대 8회, 0.5초 간격) → `findSendBtn()` 셀렉터/입력창 주변 버튼 → `sb.click()`, 3회째부터 합성 Enter
- 후보 원인
  - 전송 버튼 못 찾거나 다른 버튼 선택 (마이크↔전송 교체 타이밍, `aria-disabled`)
  - 합성(비신뢰) click/Enter를 Gemini가 무시
  - 긴 프롬프트 입력 반영 지연 (Quill/Angular 상태 미반영)
- v0.8.63부터 TIMEOUT 오류 메시지 끝에 `[전송 진단 input=…, last=…, buttons=[…]]` 포함 → 빨간 오류 말풍선 캡처로도 확인 가능

## 해야 할 일 (TM 세션)
1. 폰 무선 디버깅 연결 (`adb pair 192.168.219.100:<페어링 포트>` → `adb connect <메인 포트>`), 이전 시도는 주소 오타·만료로 `protocol fault`
2. 앱 실행 + Gemini 세션 열어 실패 재현 상태에서:
```bash
git fetch origin claude/happy-dirac-7i5ch2 && git checkout FETCH_HEAD -- scripts/webview_dump_20261128.py
pip install websocket-client
python scripts/webview_dump_20261128.py gemini
```
3. 출력(입력창 글자 수, 버튼 label/disabled/ariaDisabled/visible)으로 원인 확정
4. 확정되면 수정안 제시 (예: 정확한 전송 버튼 셀렉터, aria-disabled 대기, 신뢰 가능한 입력 — CDP `Input.dispatchKeyEvent`/터치 이벤트 주입). **수정·배포 전 사용자 승인 받기**

## 사용자 선호
- 짧게, 개조식 / 생성 파일 이름 끝에 날짜 / 배포 확인은 승인 버튼(AskUserQuestion) / 원인분석 → 플랜 → 허락 순서 (무작정 수정 금지)

## 배포 절차 (참고)
- 버전 올림 `app/build.gradle.kts`(다음 0.8.65/versionCode 75) → dev 푸시 → GitHub MCP로 PR 생성·머지 → Actions가 APK 릴리즈
- 브리지(`scripts/mbta-bridge.py`) 변경 시 Termux에서 `git pull` 후 재시작
- 이 환경은 main/태그 직접 푸시 불가 (프록시 403)

## 미해결/기타
- 웹 질문카드(건너뛰기/제출) 감지 보강(v0.8.63) 실기기 확인 필요
- 배터리 사용량 절감안 제안됨 (WebView 일시정지, 폴링 백오프 등) — 사용자 선택 대기
- 노출된 토큰 폐기는 사용자 몫
