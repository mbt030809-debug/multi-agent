package com.mbta.app.model

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
enum class TokenSource {
    ESTIMATED_WEB,
    API_EXACT,
    CLI_BRIDGE
}

@Serializable
data class AgentTokenUsage(
    val panelId: String,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val contextLimit: Long? = null,
    val customLimit: Long? = null,
    val quotaPercent: Int? = null,
    val quotaLabel: String = "",
    /** 5시간/주간 한도가 초기화되는 시각 (epoch ms, 0이면 알 수 없음) */
    val quotaResetFiveHourMs: Long = 0L,
    val quotaResetWeekMs: Long = 0L,
    val source: TokenSource = TokenSource.ESTIMATED_WEB,
    val updatedAt: Long = 0L
) {
    val totalTokens: Long
        get() = inputTokens + outputTokens
}

/** 남은 시간을 "2시간 15분", "3일 4시간", "12분"처럼 표시 */
fun formatRemaining(ms: Long): String {
    val totalMin = (ms / 60_000L).coerceAtLeast(0L)
    val days = totalMin / (24 * 60)
    val hours = (totalMin % (24 * 60)) / 60
    val mins = totalMin % 60
    return when {
        days > 0 -> if (hours > 0) "${days}일 ${hours}시간" else "${days}일"
        hours > 0 -> if (mins > 0) "${hours}시간 ${mins}분" else "${hours}시간"
        else -> "${mins}분"
    }
}

/** 초기화 시각 요약: "2시간 15분 후 · 오후 5:30" (알 수 없으면 빈 문자열) */
fun formatResetSummary(resetMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (resetMs <= 0L) return ""
    if (resetMs <= nowMs) return "곧 초기화"
    val clock = java.text.SimpleDateFormat(
        if (resetMs - nowMs >= 20L * 3600_000L) "M/d (E) a h:mm" else "a h:mm",
        Locale.KOREA
    ).format(java.util.Date(resetMs))
    return "${formatRemaining(resetMs - nowMs)} 후 · $clock"
}

fun defaultContextLimit(panelId: String): Long? = when (panelId) {
    "claude" -> 200_000L
    "chatgpt" -> 128_000L
    "gemini" -> 1_000_000L
    else -> null
}

fun formatTokens(count: Long): String = when {
    count < 1_000L -> count.toString()
    count < 1_000_000L -> String.format(Locale.US, "%.1fK", count / 1_000.0)
    else -> String.format(Locale.US, "%.2fM", count / 1_000_000.0)
}
