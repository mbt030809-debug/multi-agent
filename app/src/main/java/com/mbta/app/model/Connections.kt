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
    val enabled: Boolean = true
)

fun AiConnection.shouldUseWebEmbed(): Boolean {
    if (kind != ConnectionKind.REMOTE_CONTROL) return false
    return remoteMode == RemoteMode.WEB_EMBED
}

fun ConnectionKind.isNativeChat(): Boolean =
    this == ConnectionKind.CLAUDE_API ||
        this == ConnectionKind.OPENAI_API ||
        this == ConnectionKind.GEMINI_API

fun ConnectionKind.displayLabel(): String = when (this) {
    ConnectionKind.CLAUDE_API -> "Claude API"
    ConnectionKind.OPENAI_API -> "ChatGPT API"
    ConnectionKind.GEMINI_API -> "Gemini API"
    ConnectionKind.TERMUX_CLI -> "Termux CLI"
    ConnectionKind.REMOTE_CONTROL -> "Remote Control"
}

fun defaultModelFor(kind: ConnectionKind): String = when (kind) {
    ConnectionKind.CLAUDE_API -> "claude-sonnet-4-20250514"
    ConnectionKind.OPENAI_API -> "gpt-4o"
    ConnectionKind.GEMINI_API -> "gemini-2.5-flash"
    else -> ""
}

fun defaultEndpointFor(kind: ConnectionKind): String = when (kind) {
    ConnectionKind.CLAUDE_API -> "https://api.anthropic.com/v1/messages"
    ConnectionKind.OPENAI_API -> "https://api.openai.com/v1/chat/completions"
    ConnectionKind.GEMINI_API -> "https://generativelanguage.googleapis.com/v1beta"
    ConnectionKind.TERMUX_CLI -> "http://127.0.0.1:8765/exec"
    ConnectionKind.REMOTE_CONTROL -> "http://127.0.0.1:8765/claude"
}

fun defaultContextLimitForKind(kind: ConnectionKind): Long? = when (kind) {
    ConnectionKind.CLAUDE_API -> 200_000L
    ConnectionKind.OPENAI_API -> 128_000L
    ConnectionKind.GEMINI_API -> 1_000_000L
    else -> null
}
