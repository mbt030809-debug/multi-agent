# MbtA 구조와 역할

새 기능을 만들기 전에 이 표에서 담당 위치를 찾고, 이미 있으면 그곳을 확장합니다. 같은 역할을 다른 파일에 다시 만들지 않습니다.
역할이 옮겨지면 같은 커밋에서 이 표를 고칩니다.

경로 기준: `app/src/main/java/com/mbta/app/`

## 지금 담당 위치 → 옮겨갈 곳

| 역할 | 지금 | 목표 | 단계 |
|---|---|---|---|
| AI에게 보내고 답 받기 | `ui/MainScreen.kt` (일반·오케 메인·서브), `ui/FanOutDialog.kt` (비교) — **중복** | `agent/AgentGateway` 하나 | 2 |
| API·CLI 브리지 통신 | `network/AiApiClient.kt`, `network/GitBridgeClient.kt` | 유지 (`agent` 만 호출) | 2 |
| 질문 카드 확인, CLI 진행 표시 | `ui/MainScreen.kt` — 각 2벌 | `agent` | 2 |
| 일반 대화의 대기열·중지·재시도·알림 | `ui/MainScreen.kt` | `chat/ChatRunner` | 3 |
| 오케 지휘 규칙·반복 | `orch/Conductor.kt`, `orch/ConductorProtocol.kt` | 유지 | - |
| 오케를 앱에 연결 (서브 대화·상태) | `ui/MainScreen.kt` | `orch/OrchRunner` | 3 |
| 세션 변경·저장 | `ui/MainScreen.kt` 곳곳, `data/WorkspaceStore.kt` | `store/SessionRepository` | 3 |
| 웹 창 생성·휴면·확인 간격 | `webview/HeadlessWebBridge.kt`, `webview/IdleWebViewPolicy.kt` | `web/pool` | 4 |
| 웹 전송·답변 읽기 스크립트 | `webview/HeadlessWebBridge.kt` (`buildAutomationJs`) | `web/automation` + `assets/*.js` | 4 |
| 사이트별 화면 선택자 | 전송 스크립트와 `fetchWebSessionMessages` 에 각각 — **중복** | `web/sites` 한 곳 | 4 |
| 웹 대화 불러오기·계정·사용량·질문 카드 | `webview/HeadlessWebBridge.kt` | `web/history`, `web/account`, `web/question` | 4 |
| 웹 글자 정리 | `webview/WebTextCleaner.kt` | `web` | 4 |
| 연결·모델·effort 규칙 | `model/Connections.kt` | 유지 | - |
| 모델·effort 선택 화면 | `SessionPane`, `LandscapeSidebar`, `NewSessionDialog`, `SubAgentEditor` — **중복** | `ui/common/ModelPicker` | 5 |
| 대화 화면 배치 | `MainScreen` 의 `SessionPane(...)` 호출 5곳 — **중복** | `ui/common/PaneHost` | 5 |
| 백그라운드 서비스·알림·업데이트 | `service/`, `util/` | 유지 | - |

## 의존 방향

`ui → chat·orch → agent → web·network → model`

- 아래 계층은 위 계층을 참조하지 않습니다.
- 2단계가 끝나면 `ui` 는 전송 함수(`AiApiClient.send`, `sendPrompt`)를 직접 부르지 않습니다. 그때 CI 검사를 추가합니다.

## 정리 규칙

- 정리 커밋은 동작을 바꾸지 않습니다. 버그 수정과 새 기능은 별도 커밋입니다.
- 새 경로로 옮기면 같은 단계에서 예전 코드를 지웁니다.

## 자동 검사

| 검사 | 위치 | 실행 |
|---|---|---|
| Kotlin 단위 테스트 | `app/src/test/` | CI `gradle testDebugUnitTest` |
| 주입 스크립트 모의 시험 | `tests/injected-js/` | `node --test tests/injected-js/*.test.js` |

모의 시험은 실제 AI 웹 페이지의 변경을 잡지 못합니다. 웹 연결을 바꾸면 실기기에서도 확인합니다.
