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
    val finishedAt: Long = 0L,
    /** 어느 서브의 작업인지 (SubAgentConfig.id) */
    val subId: String = ""
)

/** 사용자가 설정한 서브 하나와 그 서브의 이어지는 대화 */
@Serializable
data class SubAgentConfig(
    val id: String, // 메인에게 알려주는 이름 (s1, s2 …)
    val uid: String, // 대화 저장용 고유값. 초기화·AI 변경 시 새로 만듦
    val connectionId: String,
    val model: String = "",
    val effort: String = "",
    val role: String = "",
    val started: Boolean = false,
    val conversationUrl: String = "",
    val history: List<ChatMessage> = emptyList()
) {
    fun resetConversation(newUid: String): SubAgentConfig =
        copy(uid = newUid, started = false, conversationUrl = "", history = emptyList())
}

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
    val subagentSensitivity: String = "medium", // low, medium, high
    val skipPermissions: Boolean = false,
    val subagentRuns: List<SubagentRun> = emptyList(),
    val subAgents: List<SubAgentConfig> = emptyList()
) {
    /** 서브 목록. 예전(서브 연결 하나) 세션은 서브 1개로 옮겨서 사용 */
    fun effectiveSubAgents(): List<SubAgentConfig> {
        if (subAgents.isNotEmpty()) return subAgents
        val conn = subConnectionId ?: connectionId ?: return emptyList()
        return listOf(SubAgentConfig(id = "s1", uid = "legacy-$id", connectionId = conn, model = subModel, effort = subEffort))
    }
}
