package com.mbta.app.model

import kotlinx.serialization.Serializable

@Serializable
enum class PanelType {
    AI_WEB,
    REMOTE_CONTROL,
    TERMUX_CLI
}

@Serializable
enum class SplitMode {
    SINGLE,
    SPLIT_VERTICAL,
    SPLIT_HORIZONTAL
}

@Serializable
data class AiPanel(
    val id: String,
    val name: String,
    val url: String,
    val type: PanelType = PanelType.AI_WEB,
    val desktopMode: Boolean = false
)

@Serializable
data class WorkspacePreset(
    val id: String,
    val name: String,
    val primaryPanelId: String,
    val secondaryPanelId: String? = null,
    val splitMode: SplitMode = SplitMode.SINGLE
)

object DefaultData {
    val defaultPanels = listOf(
        AiPanel(
            id = "claude",
            name = "Claude",
            url = "https://claude.ai",
            type = PanelType.AI_WEB
        ),
        AiPanel(
            id = "chatgpt",
            name = "ChatGPT",
            url = "https://chatgpt.com",
            type = PanelType.AI_WEB
        ),
        AiPanel(
            id = "gemini",
            name = "Gemini",
            url = "https://gemini.google.com",
            type = PanelType.AI_WEB
        ),
        AiPanel(
            id = "termux",
            name = "Termux CLI",
            url = "http://127.0.0.1:7681",
            type = PanelType.TERMUX_CLI
        ),
        AiPanel(
            id = "remote",
            name = "Remote Control",
            url = "https://claude.ai/code",
            type = PanelType.REMOTE_CONTROL
        )
    )

    val defaultPresets = listOf(
        WorkspacePreset(
            id = "preset_claude_single",
            name = "Claude 단독",
            primaryPanelId = "claude",
            secondaryPanelId = null,
            splitMode = SplitMode.SINGLE
        ),
        WorkspacePreset(
            id = "preset_claude_gemini",
            name = "Claude + Gemini",
            primaryPanelId = "claude",
            secondaryPanelId = "gemini",
            splitMode = SplitMode.SPLIT_VERTICAL
        ),
        WorkspacePreset(
            id = "preset_claude_chatgpt",
            name = "Claude + ChatGPT",
            primaryPanelId = "claude",
            secondaryPanelId = "chatgpt",
            splitMode = SplitMode.SPLIT_VERTICAL
        ),
        WorkspacePreset(
            id = "preset_claude_termux",
            name = "Claude + Termux",
            primaryPanelId = "claude",
            secondaryPanelId = "termux",
            splitMode = SplitMode.SPLIT_VERTICAL
        )
    )
}
