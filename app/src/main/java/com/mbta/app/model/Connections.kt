package com.mbta.app.model

import kotlinx.serialization.Serializable

@Serializable
enum class AppThemeMode {
    DARK,
    LIGHT,
    SYSTEM
}

@Serializable
enum class ConnectionKind {
    CLAUDE_WEB,
    CHATGPT_WEB,
    GEMINI_WEB,
    REMOTE_WEB,
    CLAUDE_API,
    OPENAI_API,
    GEMINI_API,
    TERMUX_CLI,
    REMOTE_CONTROL
}

@Serializable
enum class RemoteMode {
    AUTO,
    BRIDGE_API,
    WEB_EMBED
}

@Serializable
data class AiConnection(
    val id: String,
    val name: String,
    val kind: ConnectionKind,
    val apiKey: String = "",
    val model: String = "",
    val endpointUrl: String = "",
    val desktopMode: Boolean = false,
    val remoteMode: RemoteMode = RemoteMode.BRIDGE_API,
    val permissionMode: String = "acceptEdits",
    val enabled: Boolean = true,
    val accountName: String = ""
)

fun ConnectionKind.isHiddenWebBridge(): Boolean =
    this == ConnectionKind.CLAUDE_WEB ||
        this == ConnectionKind.CHATGPT_WEB ||
        this == ConnectionKind.GEMINI_WEB ||
        this == ConnectionKind.REMOTE_WEB

/** 웹 연결의 세션별 WebView 풀 키 — 세션마다 독립된 WebView/대화를 쓰도록 분리 */
fun webSessionKey(connId: String, sessionId: String): String = "$connId#$sessionId"

fun AiConnection.usesHiddenWebBridge(): Boolean =
    kind.isHiddenWebBridge() ||
        (kind == ConnectionKind.REMOTE_CONTROL && remoteMode == RemoteMode.WEB_EMBED)

fun ConnectionKind.isNativeChat(): Boolean =
    this == ConnectionKind.CLAUDE_API ||
        this == ConnectionKind.OPENAI_API ||
        this == ConnectionKind.GEMINI_API

fun ConnectionKind.displayLabel(): String = when (this) {
    ConnectionKind.CLAUDE_WEB -> "Claude (계정)"
    ConnectionKind.CHATGPT_WEB -> "ChatGPT (계정)"
    ConnectionKind.GEMINI_WEB -> "Gemini (계정)"
    ConnectionKind.REMOTE_WEB -> "Remote (계정)"
    ConnectionKind.CLAUDE_API -> "Claude API"
    ConnectionKind.OPENAI_API -> "ChatGPT API"
    ConnectionKind.GEMINI_API -> "Gemini API"
    ConnectionKind.TERMUX_CLI -> "Termux CLI"
    ConnectionKind.REMOTE_CONTROL -> "로컬 브릿지"
}

/** 연결 종류 배지: Claude Code 웹(claude.ai/code)은 'Remote'가 아니라 '웹'으로 표시 */
fun AiConnection.kindBadge(): String =
    if (kind == ConnectionKind.REMOTE_WEB && endpointUrl.contains("claude.ai/code", ignoreCase = true)) "웹"
    else kind.displayLabel()

/** 연결 표시 이름: "(계정)/(웹)" 접미사를 떼고, 로그인 계정이 있으면 "이름 (계정ID)", 없고 이름/종류가 중복이면 순번 */
fun AiConnection.labelIn(all: List<AiConnection>): String {
    val base = name.replace(Regex("\\s*\\((?:계정|웹)\\)"), "").trim().ifEmpty { name }
    if (accountName.isNotBlank()) {
        val acct = accountName.trim().substringBefore("@").ifBlank { accountName.trim() }
        return "$base ($acct)"
    }
    val same = all.filter { it.name == name && it.kind == kind }
    if (same.size > 1) {
        val idx = same.indexOfFirst { it.id == id } + 1
        return "$base #$idx"
    }
    return base
}

/** 그룹(웹/브릿지/API/Remote)으로 설명되는 "(계정)", "(웹)" 접미사를 뗀 짧은 이름 */
fun ConnectionKind.shortLabel(): String =
    displayLabel().replace(Regex("\\s*\\((?:계정|웹)\\)"), "").trim()

fun defaultModelFor(kind: ConnectionKind): String = when (kind) {
    ConnectionKind.CLAUDE_API -> "claude-sonnet-4-20250514"
    ConnectionKind.OPENAI_API -> "gpt-4o"
    ConnectionKind.GEMINI_API -> "gemini-2.5-flash"
    ConnectionKind.CLAUDE_WEB -> "Sonnet"
    ConnectionKind.CHATGPT_WEB -> "GPT-4o"
    ConnectionKind.GEMINI_WEB -> "Gemini 2.5 Pro"
    ConnectionKind.REMOTE_WEB, ConnectionKind.REMOTE_CONTROL -> "Gemini 4 Neon (Medium)"
    else -> ""
}

fun availableModelsFor(kind: ConnectionKind): List<String> = when (kind) {
    ConnectionKind.CLAUDE_API -> listOf(
        "claude-sonnet-4-20250514",
        "claude-opus-4-20250514",
        "claude-3-7-sonnet-latest",
        "claude-3-5-haiku-latest"
    )
    ConnectionKind.OPENAI_API -> listOf("gpt-4o", "gpt-4.1", "o3-mini", "o4-mini")
    ConnectionKind.GEMINI_API -> listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash")
    ConnectionKind.CLAUDE_WEB -> listOf("Sonnet", "Opus", "Haiku")
    ConnectionKind.CHATGPT_WEB -> listOf("GPT-4o", "o3", "o4-mini", "GPT-4.5")
    ConnectionKind.GEMINI_WEB -> listOf("Gemini 2.5 Pro", "Gemini 2.5 Flash", "Gemini Hydrogen", "Gemini Neon")
    ConnectionKind.REMOTE_WEB, ConnectionKind.REMOTE_CONTROL -> listOf(
        "Gemini 4 Neon (Low)",
        "Gemini 4 Neon (Medium)",
        "Gemini 4 Neon (High)",
        "Gemini 4 Hydrogen (Low)",
        "Claude Sonnet 5.5 (Medium)",
        "Claude Opus 5.5 (Medium)"
    )
    ConnectionKind.TERMUX_CLI -> emptyList()
}

fun defaultModelFor(connection: AiConnection): String {
    val lower = connection.endpointUrl.lowercase()
    if ((connection.kind == ConnectionKind.REMOTE_CONTROL || connection.kind == ConnectionKind.REMOTE_WEB) &&
        (lower.contains("/claude") || lower.contains("claude.ai"))
    ) {
        return "sonnet"
    }
    return defaultModelFor(connection.kind)
}

fun availableModelsFor(connection: AiConnection): List<String> {
    val lower = connection.endpointUrl.lowercase()
    if ((connection.kind == ConnectionKind.REMOTE_CONTROL || connection.kind == ConnectionKind.REMOTE_WEB) &&
        (lower.contains("/claude") || lower.contains("claude.ai"))
    ) {
        return listOf("sonnet", "opus", "haiku", "claude-sonnet-4-6", "claude-opus-4-6", "claude-haiku-4-5")
    }
    return availableModelsFor(connection.kind)
}

private val parenEffortRegex = Regex("\\s*\\((Low|Medium|High|Max)\\)\\s*$", RegexOption.IGNORE_CASE)

fun stripParenEffort(model: String): String = model.replace(parenEffortRegex, "").trim()

fun extractParenEffort(model: String): String =
    parenEffortRegex.find(model)?.groupValues?.getOrNull(1)?.trim().orEmpty()

/**
 * effort가 모델 이름의 "(Low/Medium/High)" 변형으로 표현되는 연결(Antigravity 등)에서,
 * 별도로 고른 effort를 모델 이름에 반영한다. 변형이 없으면 모델을 그대로 돌려준다.
 */
fun modelWithEffort(model: String, effort: String, available: List<String>): String {
    if (model.isBlank() || effort.isBlank()) return model
    if (extractParenEffort(model).isNotBlank()) return model
    val base = stripParenEffort(model)
    return available.firstOrNull {
        stripParenEffort(it).equals(base, ignoreCase = true) && extractParenEffort(it).equals(effort, ignoreCase = true)
    } ?: model
}

fun availableEffortsFor(
    connection: AiConnection,
    activeModel: String,
    availableModels: List<String> = emptyList()
): List<String> {
    val mLower = activeModel.lowercase()
    // 1. Models with explicit "(Low/Medium/High/Max)" variants (e.g. Antigravity / Remote Control)
    val base = stripParenEffort(activeModel)
    val variantEfforts = availableModels
        .filter { stripParenEffort(it).equals(base, ignoreCase = true) }
        .mapNotNull { extractParenEffort(it).ifBlank { null } }
        .distinct()
    if (variantEfforts.size >= 2) {
        val order = listOf("Low", "Medium", "High", "Max")
        return variantEfforts.sortedBy { e -> order.indexOfFirst { it.equals(e, ignoreCase = true) }.let { if (it < 0) 99 else it } }
    }
    val urlLower = connection.endpointUrl.lowercase()
    if (connection.usesHiddenWebBridge() && urlLower.contains("claude.ai/code")) {
        return if (mLower.contains("haiku")) emptyList() else listOf("low", "medium", "high", "extra", "max")
    }
    return when (connection.kind) {
        ConnectionKind.REMOTE_CONTROL -> {
            if (urlLower.contains("/claude")) {
                if (mLower.contains("haiku")) emptyList() else listOf("low", "medium", "high", "max")
            } else {
                emptyList()
            }
        }
        ConnectionKind.OPENAI_API -> {
            if (mLower.startsWith("o1") || mLower.startsWith("o3") || mLower.startsWith("o4") || mLower.startsWith("gpt-5")) {
                listOf("low", "medium", "high")
            } else emptyList()
        }
        ConnectionKind.CLAUDE_API -> {
            if (mLower.contains("sonnet-4") || mLower.contains("opus-4") || mLower.contains("3-7-sonnet")) {
                listOf("low", "medium", "high")
            } else emptyList()
        }
        ConnectionKind.GEMINI_API -> {
            if (mLower.contains("2.5-pro") || mLower.contains("2.5-flash") || mLower.contains("gemini-3") || mLower.contains("gemini-4")) {
                listOf("low", "medium", "high")
            } else emptyList()
        }
        else -> emptyList()
    }
}

fun defaultEndpointFor(kind: ConnectionKind): String = when (kind) {
    ConnectionKind.CLAUDE_WEB -> "https://claude.ai"
    ConnectionKind.CHATGPT_WEB -> "https://chatgpt.com"
    ConnectionKind.GEMINI_WEB -> "https://gemini.google.com"
    ConnectionKind.REMOTE_WEB -> "https://claude.ai/code"
    ConnectionKind.CLAUDE_API -> "https://api.anthropic.com/v1/messages"
    ConnectionKind.OPENAI_API -> "https://api.openai.com/v1/chat/completions"
    ConnectionKind.GEMINI_API -> "https://generativelanguage.googleapis.com/v1beta"
    ConnectionKind.TERMUX_CLI -> "http://127.0.0.1:8765/exec"
    ConnectionKind.REMOTE_CONTROL -> "http://127.0.0.1:8765/claude"
}

fun defaultContextLimitForKind(kind: ConnectionKind): Long? = when (kind) {
    ConnectionKind.CLAUDE_WEB, ConnectionKind.CLAUDE_API -> 200_000L
    ConnectionKind.CHATGPT_WEB, ConnectionKind.OPENAI_API -> 128_000L
    ConnectionKind.GEMINI_WEB, ConnectionKind.GEMINI_API -> 1_000_000L
    ConnectionKind.REMOTE_CONTROL, ConnectionKind.REMOTE_WEB -> 1_000_000L
    else -> null
}

fun defaultContextLimitFor(connection: AiConnection): Long? {
    val lower = connection.endpointUrl.lowercase()
    if ((connection.kind == ConnectionKind.REMOTE_CONTROL || connection.kind == ConnectionKind.REMOTE_WEB) &&
        (lower.contains("/claude") || lower.contains("claude.ai"))
    ) {
        return 200_000L
    }
    return defaultContextLimitForKind(connection.kind)
}

data class DetectedConnection(
    val kind: ConnectionKind,
    val suggestedName: String,
    val endpointUrl: String,
    val apiKey: String = ""
)

fun autoDetectConnection(rawInput: String): DetectedConnection {
    val raw = rawInput.trim()
    val lower = raw.lowercase()
    return when {
        raw.startsWith("sk-ant-") -> DetectedConnection(
            kind = ConnectionKind.CLAUDE_API,
            suggestedName = "Claude API",
            endpointUrl = defaultEndpointFor(ConnectionKind.CLAUDE_API),
            apiKey = raw
        )
        raw.startsWith("sk-") -> DetectedConnection(
            kind = ConnectionKind.OPENAI_API,
            suggestedName = "ChatGPT API",
            endpointUrl = defaultEndpointFor(ConnectionKind.OPENAI_API),
            apiKey = raw
        )
        raw.startsWith("AIza") -> DetectedConnection(
            kind = ConnectionKind.GEMINI_API,
            suggestedName = "Gemini API",
            endpointUrl = defaultEndpointFor(ConnectionKind.GEMINI_API),
            apiKey = raw
        )
        lower.contains("claude.ai/code") -> DetectedConnection(
            kind = ConnectionKind.REMOTE_WEB,
            suggestedName = "Claude Code Remote",
            endpointUrl = normalizeUrl(raw)
        )
        lower.contains("claude.ai") -> DetectedConnection(
            kind = ConnectionKind.CLAUDE_WEB,
            suggestedName = "Claude",
            endpointUrl = normalizeUrl(raw)
        )
        lower.contains("chatgpt.com") || lower.contains("chat.openai.com") -> DetectedConnection(
            kind = ConnectionKind.CHATGPT_WEB,
            suggestedName = "ChatGPT",
            endpointUrl = normalizeUrl(raw)
        )
        lower.contains("gemini.google.com") -> DetectedConnection(
            kind = ConnectionKind.GEMINI_WEB,
            suggestedName = "Gemini",
            endpointUrl = normalizeUrl(raw)
        )
        lower.contains("antigravity") -> DetectedConnection(
            kind = ConnectionKind.REMOTE_WEB,
            suggestedName = "Antigravity Remote",
            endpointUrl = normalizeUrl(raw)
        )
        lower.contains("127.0.0.1") || lower.contains("localhost") -> when {
            lower.endsWith("/exec") -> DetectedConnection(
                kind = ConnectionKind.TERMUX_CLI,
                suggestedName = "Termux CLI",
                endpointUrl = normalizeUrl(raw, defaultScheme = "http://")
            )
            lower.endsWith("/agy") -> DetectedConnection(
                kind = ConnectionKind.REMOTE_CONTROL,
                suggestedName = "Antigravity 로컬",
                endpointUrl = normalizeUrl(raw, defaultScheme = "http://")
            )
            lower.endsWith("/claude") -> DetectedConnection(
                kind = ConnectionKind.REMOTE_CONTROL,
                suggestedName = "Claude Code 로컬",
                endpointUrl = normalizeUrl(raw, defaultScheme = "http://")
            )
            else -> DetectedConnection(
                kind = ConnectionKind.REMOTE_CONTROL,
                suggestedName = "로컬 브릿지",
                endpointUrl = normalizeUrl(raw, defaultScheme = "http://")
            )
        }
        else -> DetectedConnection(
            kind = ConnectionKind.REMOTE_WEB,
            suggestedName = "Remote Control",
            endpointUrl = normalizeUrl(raw)
        )
    }
}

private fun normalizeUrl(raw: String, defaultScheme: String = "https://"): String {
    if (raw.isBlank()) return defaultScheme
    return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "$defaultScheme$raw"
}
