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
    val enabled: Boolean = true
)

fun ConnectionKind.isHiddenWebBridge(): Boolean =
    this == ConnectionKind.CLAUDE_WEB ||
        this == ConnectionKind.CHATGPT_WEB ||
        this == ConnectionKind.GEMINI_WEB ||
        this == ConnectionKind.REMOTE_WEB

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
    else -> null
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
