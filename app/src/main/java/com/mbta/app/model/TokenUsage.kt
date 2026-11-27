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
    val source: TokenSource = TokenSource.ESTIMATED_WEB,
    val updatedAt: Long = 0L
) {
    val totalTokens: Long
        get() = inputTokens + outputTokens
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
