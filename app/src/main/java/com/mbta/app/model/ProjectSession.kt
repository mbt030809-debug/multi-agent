package com.mbta.app.model

import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: String,
    val name: String,
    val workDir: String = "",
    val memoryText: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class CustomSkill(
    val id: String,
    val command: String,
    val title: String,
    val description: String = "",
    val promptTemplate: String
)

@Serializable
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

@Serializable
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val agentName: String = "",
    val isError: Boolean = false,
    val isPinned: Boolean = false
)

@Serializable
data class SubagentRun(
    val id: String,
    val label: String,
    val prompt: String,
    val model: String = "",
    val status: String = "running", // running, done, error, cancelled
    val output: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0L
)

@Serializable
data class AgentSession(
    val id: String,
    val projectId: String,
    val title: String,
    val connectionId: String? = null,
    val selectedModel: String = "",
    val selectedEffort: String = "",
    val worktreePath: String = "",
    val webConversationUrl: String = "",
    val cliSessionId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val isOrchestration: Boolean = false,
    val subConnectionId: String? = null,
    val subModel: String = "",
    val subEffort: String = "",
    val maxParallelSubagents: Int = 3,
    val maxDelegationRounds: Int = 3,
    val skipPermissions: Boolean = false,
    val subagentRuns: List<SubagentRun> = emptyList()
)
