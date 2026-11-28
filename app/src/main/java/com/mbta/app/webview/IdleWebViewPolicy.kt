package com.mbta.app.webview

/**
 * 웹 창의 세 가지 상태 (UI·안드로이드와 분리해 테스트 가능)
 * - 액티브: 결과를 기다리고 있고 보고 있음 → 3초마다 확인
 * - 슬로우: 결과를 기다리지만 보고 있지 않음 → 15초마다 확인
 * - 팬딩: 기다리는 작업 없음 → 확인하지 않고, 30초 뒤 창을 닫음
 * 생성 종료는 확인 간격과 별개로 화면 변화 감시(MutationObserver)가 바로 확인
 */
internal object IdleWebViewPolicy {
    const val ACTIVE_CHECK_MS = 3_000L
    const val SLOW_CHECK_MS = 15_000L
    /** 작업이 끝난 뒤 이 시간 동안 쓰지 않으면 닫음 (보고 있는 세션도 같음) */
    const val IDLE_MS = 30_000L
    const val SWEEP_MS = 5_000L
    /** 새로 연 페이지가 화면을 그릴 때까지의 여유 */
    const val SETTLE_MS = 3_000L
    /** 다시 보게 됐을 때 이 시간 동안 답변 변화가 없었으면 한 번만 새로고침 */
    const val STALL_RELOAD_MS = 30_000L

    fun checkIntervalMs(viewing: Boolean): Long = if (viewing) ACTIVE_CHECK_MS else SLOW_CHECK_MS

    fun shouldSleep(keepAlive: Boolean, busy: Boolean, idleMs: Long): Boolean =
        !keepAlive && !busy && idleMs >= IDLE_MS

    fun shouldReloadOnView(busy: Boolean, alreadyReloaded: Boolean, sinceProgressMs: Long): Boolean =
        busy && !alreadyReloaded && sinceProgressMs >= STALL_RELOAD_MS
}
