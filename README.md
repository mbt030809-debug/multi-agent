# MbtA (Multi-Agent Android App)

여러 AI 서비스(Claude, ChatGPT, Gemini), Remote Control, Termux CLI를 하나의 Android 앱에서 탭 전환 및 화면 분할로 동시에 사용할 수 있는 멀티 에이전트 앱입니다.

## Phase 1 기능
- **멀티 AI WebView 패널**: Claude, ChatGPT, Gemini, Termux CLI(`http://127.0.0.1:7681`), Remote Control 기본 제공 및 커스텀 패널 추가/편집
- **영구 세션 유지**: 쿠키/로그인 상태 유지 및 탭 전환 시 리로드 방지
- **화면 분할 (Split View)**: 단일 / 상하 분할 / 좌우 분할 지원 및 화면 교체
- **워크스페이스 프리셋**: 자주 쓰는 AI 조합 저장 및 원클릭 복원

## APK 다운로드
GitHub Actions의 **Build MbtA APK** 워크플로우 실행 결과에서 `MbtA-debug-apk` 아티팩트를 다운로드해 설치할 수 있습니다.
