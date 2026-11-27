package com.mbta.app.ui

import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mbta.app.data.WorkspaceStore
import com.mbta.app.service.AgentKeepAliveService
import com.mbta.app.model.AgentSession
import com.mbta.app.model.AgentTokenUsage
import com.mbta.app.model.AiConnection
import com.mbta.app.model.AppThemeMode
import com.mbta.app.model.ChatMessage
import com.mbta.app.model.ConnectionKind
import com.mbta.app.model.MessageRole
import com.mbta.app.model.Project
import com.mbta.app.model.SplitMode
import com.mbta.app.model.SubagentRun
import com.mbta.app.model.TokenSource
import com.mbta.app.model.availableEffortsFor
import com.mbta.app.model.availableModelsFor
import com.mbta.app.model.defaultContextLimitForKind
import com.mbta.app.model.defaultModelFor
import com.mbta.app.model.displayLabel
import com.mbta.app.model.formatTokens
import com.mbta.app.model.isHiddenWebBridge
import com.mbta.app.model.usesHiddenWebBridge
import com.mbta.app.network.AiApiClient
import com.mbta.app.network.GitBridgeClient
import com.mbta.app.util.AppUpdater
import com.mbta.app.util.UpdateInfo
import com.mbta.app.webview.HeadlessWebBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    store: WorkspaceStore,
    headlessWebBridge: HeadlessWebBridge,
    themeMode: AppThemeMode,
    tokenUsageMap: MutableMap<String, AgentTokenUsage>,
    onThemeModeChanged: (AppThemeMode) -> Unit,
    onRecordTokens: (connectionId: String, kind: ConnectionKind, inTok: Long, outTok: Long) -> Unit,
    onResetAgentTokens: (String) -> Unit,
    onResetAllTokens: () -> Unit,
    onNotifyIfBackground: (String, String) -> Unit = { _, _ -> },
    onNotifyEvent: (sessionId: String, title: String, body: String, isUrgent: Boolean, actions: List<String>) -> Unit = { _, _, _, _, _ -> },
    pendingNotifSessionId: String? = null,
    pendingNotifReply: Pair<String, String>? = null,
    onConsumeNotifAction: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var connections by remember { mutableStateOf(store.loadConnections()) }
    var customSkills by remember { mutableStateOf(store.loadCustomSkills()) }
    var projects by remember { mutableStateOf(store.loadProjects()) }
    var useProjectContext by remember { mutableStateOf(true) }
    var sessions by remember { mutableStateOf(store.loadSessions()) }
    var activeProjectId by remember { mutableStateOf(store.loadActiveProjectId()) }
    var activeSessionId by remember { mutableStateOf(store.loadActiveSessionId()) }
    var secondarySessionId by remember { mutableStateOf(store.loadSecondarySessionId()) }
    var splitMode by remember { mutableStateOf(store.loadSplitMode()) }
    var focusedPane by remember { mutableIntStateOf(0) }

    var showSettings by remember { mutableStateOf(false) }
    var loginConnection by remember { mutableStateOf<AiConnection?>(null) }
    var showTokenDashboard by remember { mutableStateOf(false) }
    var showGlobalSearch by remember { mutableStateOf(false) }
    val autoRetryCountMap = remember { mutableStateMapOf<String, Int>() }
    var showGitDialog by remember { mutableStateOf(false) }
    var showFanOutDialog by remember { mutableStateOf(false) }
    var showProjectMenu by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateDownloading by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf(0f) }
    var updateStatusMsg by remember { mutableStateOf<String?>(null) }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var editingProject by remember { mutableStateOf<Project?>(null) }
    var showNewSessionDialog by remember { mutableStateOf(false) }
    var showExistingSessionDialog by remember { mutableStateOf(false) }
    var sessionPendingDelete by remember { mutableStateOf<AgentSession?>(null) }

    val sendingMap = remember { mutableStateMapOf<String, Boolean>() }
    val activeSendingCount = sendingMap.values.count { it }
    LaunchedEffect(activeSendingCount) {
        if (activeSendingCount > 0) {
            AgentKeepAliveService.start(context, "AI 작업 진행 중 (${activeSendingCount}개 세션)")
        } else {
            AgentKeepAliveService.stop(context)
        }
    }
    val activeJobs = remember { mutableStateMapOf<String, kotlinx.coroutines.Job>() }
    val subagentJobsMap = remember { mutableStateMapOf<String, kotlinx.coroutines.Job>() }
    val queuedPromptsMap = remember { mutableStateMapOf<String, List<String>>() }
    val unreadDoneSessions = remember { mutableStateMapOf<String, Boolean>() }
    val loginStatusMap = remember {
        mutableStateMapOf<String, Boolean>().apply {
            store.loadConfirmedLogins().forEach { id -> put(id, true) }
        }
    }

    fun persistConfirmedLogins() {
        val confirmed = loginStatusMap.filterValues { it }.keys.toSet()
        store.saveConfirmedLogins(confirmed)
    }

    fun refreshLoginStatuses() {
        connections.filter { it.usesHiddenWebBridge() }.forEach { conn ->
            headlessWebBridge.checkLoginStatus(conn.id, conn.kind, conn.endpointUrl) { ok ->
                if (ok) {
                    loginStatusMap[conn.id] = true
                    persistConfirmedLogins()
                }
            }
        }
    }

    LaunchedEffect(connections, showSettings, loginConnection) {
        refreshLoginStatuses()
    }

    LaunchedEffect(Unit) {
        availableUpdate = AppUpdater.checkLatestRelease(context)
    }

    val enabledConnections = connections.filter { it.enabled }
    val activeProject = projects.find { it.id == activeProjectId } ?: projects.first()
    val validProjectSessions = sessions.filter { sess ->
        sess.projectId == activeProject.id &&
            sess.connectionId != null &&
            enabledConnections.any { c -> c.id == sess.connectionId }
    }
    val primarySession: AgentSession? =
        validProjectSessions.find { it.id == activeSessionId } ?: validProjectSessions.firstOrNull()

    val secondarySession: AgentSession? =
        validProjectSessions.find { it.id == secondarySessionId }
            ?: validProjectSessions.firstOrNull { it.id != primarySession?.id }
            ?: primarySession

    val totalTokensAll = tokenUsageMap.values.sumOf { it.totalTokens }

    fun isSavableConversationUrl(curUrl: String, baseUrl: String): Boolean {
        val c = curUrl.trim().trimEnd('/')
        val b = baseUrl.trim().trimEnd('/')
        if (c.isBlank() || c == b) return false
        val lower = c.lowercase()
        if (lower.contains("accounts.google.com") || lower.contains("/login") || lower.contains("/signin") || lower.contains("/oauth")) {
            return false
        }
        return lower.contains("/c/") ||
            lower.contains("/chat/") ||
            lower.contains("/app/") ||
            lower.contains("/r/")
    }

    fun applyQuotaUpdate(connId: String, pct: Int, lbl: String) {
        val cur = tokenUsageMap[connId] ?: AgentTokenUsage(panelId = connId)
        tokenUsageMap[connId] = cur.copy(
            quotaPercent = pct,
            quotaLabel = lbl,
            updatedAt = System.currentTimeMillis()
        )
        store.saveTokenUsage(tokenUsageMap)
    }

    fun refreshQuotaForConnection(conn: AiConnection, force: Boolean = false) {
        when (conn.kind) {
            ConnectionKind.REMOTE_CONTROL -> {
                val bridgeKind = if (conn.endpointUrl.lowercase().contains("/agy")) "agy" else "claude"
                scope.launch {
                    val (pct, lbl) = GitBridgeClient.fetchQuota(bridgeKind, force)
                    if (pct != null) applyQuotaUpdate(conn.id, pct, lbl)
                }
            }
            ConnectionKind.CLAUDE_WEB -> {
                headlessWebBridge.fetchClaudeWebUsagePercent(conn.id) { pct, lbl ->
                    if (pct != null) applyQuotaUpdate(conn.id, pct, lbl)
                }
            }
            ConnectionKind.CHATGPT_WEB -> {
                headlessWebBridge.fetchChatGptWebUsagePercent(conn.id) { pct, lbl ->
                    if (pct != null) applyQuotaUpdate(conn.id, pct, lbl)
                }
            }
            else -> Unit
        }
    }

    fun refreshAllQuotas(force: Boolean = false) {
        enabledConnections.forEach { conn -> refreshQuotaForConnection(conn, force) }
    }

    LaunchedEffect(enabledConnections.size) {
        refreshAllQuotas(force = false)
    }

    LaunchedEffect(activeSessionId) {
        val sess = sessions.find { it.id == activeSessionId } ?: return@LaunchedEffect
        val conn = connections.find { it.id == sess.connectionId } ?: return@LaunchedEffect
        if (conn.usesHiddenWebBridge() && sess.webConversationUrl.isNotBlank()) {
            headlessWebBridge.navigateIfNeeded(conn.id, sess.webConversationUrl)
        }
        refreshQuotaForConnection(conn, force = false)
    }

    fun parseSubagentRequests(text: String): List<Pair<String, String>> {
        val match = Regex("<<SUBAGENTS>>([\\s\\S]*?)<</SUBAGENTS>>", RegexOption.IGNORE_CASE).find(text)
            ?: return emptyList()
        val raw = match.groupValues[1].trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val role = obj.optString("role").ifBlank { obj.optString("label") }.ifBlank { "서브 ${i + 1}" }.trim().take(24)
                    val task = obj.optString("task").ifBlank { obj.optString("prompt") }.trim()
                    if (task.isNotBlank()) add(role to task)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun updateSubagentRun(sessionId: String, runId: String, transform: (SubagentRun) -> SubagentRun) {
        sessions = sessions.map { s ->
            if (s.id == sessionId) {
                s.copy(subagentRuns = s.subagentRuns.map { r -> if (r.id == runId) transform(r) else r })
            } else s
        }
        store.saveSessions(sessions)
    }

    suspend fun runSingleSubagent(
        session: AgentSession,
        subConn: AiConnection,
        subModel: String,
        subEffort: String,
        effectiveDir: String,
        run: SubagentRun
    ): SubagentRun {
        val effectiveSubConn = if (subModel.isNotBlank()) subConn.copy(model = subModel) else subConn
        val subPrompt = buildString {
            append("[서브에이전트 작업 지시 — 역할: ${run.label}]\n")
            if (useProjectContext && effectiveDir.isNotBlank()) append("- 작업 경로: $effectiveDir\n")
            if (useProjectContext && activeProject.memoryText.isNotBlank()) append("- 프로젝트 지침: ${activeProject.memoryText}\n")
            append("- 보고 규칙: 메인 오케스트레이터의 토큰을 아끼기 위해 핵심 결과·발견점·변경사항만 간결하고 정확하게 요약해 보고하세요.\n\n")
            append(run.prompt)
        }
        return try {
            val (outText, errText) = if (effectiveSubConn.usesHiddenWebBridge()) {
                val (triple, err) = headlessWebBridge.sendPromptSuspend(
                    key = effectiveSubConn.id,
                    kind = effectiveSubConn.kind,
                    url = effectiveSubConn.endpointUrl,
                    prompt = subPrompt
                )
                if (triple.second > 0L || triple.third > 0L) {
                    onRecordTokens(effectiveSubConn.id, effectiveSubConn.kind, triple.second, triple.third)
                }
                triple.first to err
            } else {
                val reply = AiApiClient.send(
                    connection = effectiveSubConn,
                    history = emptyList(),
                    userMessage = subPrompt,
                    sessionId = "${session.id}_sub_${run.id}",
                    cwd = effectiveDir,
                    effort = subEffort,
                    skipPermissions = session.skipPermissions
                )
                if (reply.inputTokens > 0L || reply.outputTokens > 0L) {
                    onRecordTokens(effectiveSubConn.id, effectiveSubConn.kind, reply.inputTokens, reply.outputTokens)
                }
                reply.text to reply.error
            }
            val finished = run.copy(
                status = if (errText != null) "error" else "done",
                output = errText ?: outText.ifBlank { "(응답 없음)" },
                finishedAt = System.currentTimeMillis()
            )
            updateSubagentRun(session.id, run.id) { finished }
            finished
        } catch (ce: CancellationException) {
            val cancelled = run.copy(
                status = "cancelled",
                output = "사용자가 중단했습니다.",
                finishedAt = System.currentTimeMillis()
            )
            updateSubagentRun(session.id, run.id) { cancelled }
            cancelled
        } catch (e: Exception) {
            val errored = run.copy(
                status = "error",
                output = e.message ?: "서브에이전트 오류",
                finishedAt = System.currentTimeMillis()
            )
            updateSubagentRun(session.id, run.id) { errored }
            errored
        } finally {
            subagentJobsMap.remove(run.id)
        }
    }

    fun stopSubagentRun(sessionId: String, runId: String) {
        subagentJobsMap.remove(runId)?.cancel()
        updateSubagentRun(sessionId, runId) {
            if (it.status == "running") {
                it.copy(status = "cancelled", output = it.output.ifBlank { "사용자가 중단했습니다." }, finishedAt = System.currentTimeMillis())
            } else it
        }
    }

    fun retrySubagentRun(sessionId: String, runId: String) {
        val sess = sessions.find { it.id == sessionId } ?: return
        val run = sess.subagentRuns.find { it.id == runId } ?: return
        val subConn = enabledConnections.find { it.id == (sess.subConnectionId ?: sess.connectionId) }
            ?: enabledConnections.find { it.id == sess.connectionId } ?: return
        val subModel = sess.subModel.ifBlank { defaultModelFor(subConn) }
        val effectiveDir = sess.worktreePath.ifBlank { activeProject.workDir }
        val restarted = run.copy(status = "running", output = "", startedAt = System.currentTimeMillis(), finishedAt = 0L)
        updateSubagentRun(sessionId, runId) { restarted }
        val job = scope.launch {
            runSingleSubagent(sess, subConn, subModel, sess.subEffort, effectiveDir, restarted)
        }
        subagentJobsMap[runId] = job
    }

    fun sendMessageInSession(session: AgentSession, conn: AiConnection, text: String) {
        if (text.isBlank()) return
        if (sendingMap[session.id] == true) {
            queuedPromptsMap[session.id] = (queuedPromptsMap[session.id] ?: emptyList()) + text.trim()
            return
        }
        if (session.isOrchestration && !text.startsWith("@")) {
            val effectiveMainConn = if (session.selectedModel.isNotBlank()) conn.copy(model = session.selectedModel) else conn
            val subConn = enabledConnections.find { it.id == (session.subConnectionId ?: session.connectionId) } ?: conn
            val subModel = session.subModel.ifBlank { defaultModelFor(subConn) }
            val effectiveDir = session.worktreePath.ifBlank { activeProject.workDir }
            val maxParallel = session.maxParallelSubagents.coerceIn(1, 8)
            val maxRounds = session.maxDelegationRounds.coerceIn(1, 6)

            val userMsg = ChatMessage(
                id = "msg_${UUID.randomUUID()}",
                role = MessageRole.USER,
                content = text
            )
            val placeholderId = "msg_${UUID.randomUUID()}"
            val placeholder = ChatMessage(
                id = placeholderId,
                role = MessageRole.ASSISTANT,
                content = "⏳ 메인 오케스트레이터 분석 중...",
                agentName = "${conn.name} (오케스트레이터)"
            )
            val baseHistory = session.messages
            sessions = sessions.map {
                if (it.id == session.id) it.copy(messages = it.messages + userMsg + placeholder) else it
            }
            store.saveSessions(sessions)
            sendingMap[session.id] = true

            fun setPlaceholder(msg: String) {
                sessions = sessions.map { s ->
                    if (s.id == session.id) {
                        s.copy(messages = s.messages.map { m -> if (m.id == placeholderId) m.copy(content = msg) else m })
                    } else s
                }
            }

            val orchJob = scope.launch {
                try {
                    val roundHistory = baseHistory.toMutableList()
                    val sensitivityRule = when (session.subagentSensitivity) {
                        "high" -> "1. [호출 민감도: 높음] 단순 인사나 1줄 확인을 제외한 거의 모든 조사·분석·구현·수정 작업을 메인이 직접 하지 말고, 메인은 기획·분배·최종 종합만 맡아 반드시 <<SUBAGENTS>>로 서브에이전트에 분할 위임하세요."
                        "low" -> "1. [호출 민감도: 낮음] 일반적인 질문과 소규모 작업은 메인이 직접 처리하고, 대규모 병렬 조사나 다수 모듈 동시 작업처럼 꼭 필요할 때만 <<SUBAGENTS>>를 출력하세요."
                        else -> "1. [호출 민감도: 보통] 단순 1~2줄 질문이나 단일 파일 소규모 수정만 메인이 직접 처리하고, 2개 이상 파일 수정·코드 탐색/조사·리팩토링·테스트 검증처럼 분업이 가능한 작업은 메인 토큰을 아끼기 위해 적극적으로 <<SUBAGENTS>>로 병렬 위임하세요."
                    }
                    var currentPrompt = buildString {
                        append("[오케스트레이터 프로토콜]\n")
                        append("당신은 메인 오케스트레이터이며, 저비용 서브에이전트(${subConn.name}${if (subModel.isNotBlank()) " / $subModel" else ""})를 동적으로 생성해 병렬 작업시킬 수 있습니다.\n")
                        append(sensitivityRule).append("\n")
                        append("2. 서브에이전트를 위임할 때는 응답 맨 끝에 아래 JSON 블록을 출력하세요 (최대 ${maxParallel}개 병렬, 최대 ${maxRounds}라운드):\n")
                        append("<<SUBAGENTS>>\n[{\"role\": \"짧은 역할명\", \"task\": \"구체적 작업 지시\"}]\n<</SUBAGENTS>>\n")
                        append("3. 파일 충돌 방지: 읽기/조사는 병렬 위임하되, 파일 수정 작업은 반드시 서로 겹치지 않는 파일 단위로 분할해 지시하세요.\n")
                        append("4. 서브에이전트 결과는 핵심 요약으로 보고됩니다. 결과를 받으면 종합해 사용자에게 최종 답변을 작성하세요.\n")
                        if (useProjectContext && (effectiveDir.isNotBlank() || activeProject.memoryText.isNotBlank())) {
                            append("\n[프로젝트 컨텍스트]\n")
                            if (effectiveDir.isNotBlank()) append("- 작업 경로: $effectiveDir\n")
                            if (activeProject.memoryText.isNotBlank()) append("- 공용 지침: ${activeProject.memoryText}\n")
                        }
                        append("\n[사용자 요청]\n").append(text)
                    }

                    var finalAnswer = ""
                    var finalError: String? = null
                    var totalInTok = 0L
                    var totalOutTok = 0L

                    for (round in 1..(maxRounds + 1)) {
                        val (mainText, mainErr) = if (effectiveMainConn.usesHiddenWebBridge()) {
                            val (triple, err) = headlessWebBridge.sendPromptSuspend(
                                key = effectiveMainConn.id,
                                kind = effectiveMainConn.kind,
                                url = effectiveMainConn.endpointUrl,
                                prompt = currentPrompt
                            )
                            totalInTok += triple.second
                            totalOutTok += triple.third
                            if (triple.second > 0L || triple.third > 0L) {
                                onRecordTokens(effectiveMainConn.id, effectiveMainConn.kind, triple.second, triple.third)
                            }
                            triple.first to err
                        } else {
                            val reply = AiApiClient.send(
                                connection = effectiveMainConn,
                                history = roundHistory,
                                userMessage = currentPrompt,
                                sessionId = session.id,
                                cwd = effectiveDir,
                                cliSessionId = session.cliSessionId,
                                effort = session.selectedEffort,
                                skipPermissions = session.skipPermissions
                            )
                            totalInTok += reply.inputTokens
                            totalOutTok += reply.outputTokens
                            if (reply.inputTokens > 0L || reply.outputTokens > 0L) {
                                onRecordTokens(effectiveMainConn.id, effectiveMainConn.kind, reply.inputTokens, reply.outputTokens)
                            }
                            if (reply.quotaPercent != null) {
                                val cur = tokenUsageMap[effectiveMainConn.id] ?: AgentTokenUsage(panelId = effectiveMainConn.id)
                                tokenUsageMap[effectiveMainConn.id] = cur.copy(
                                    quotaPercent = reply.quotaPercent,
                                    quotaLabel = reply.quotaLabel,
                                    updatedAt = System.currentTimeMillis()
                                )
                                store.saveTokenUsage(tokenUsageMap)
                            }
                            reply.text to reply.error
                        }

                        if (mainErr != null) {
                            finalError = mainErr
                            break
                        }

                        val requests = if (round <= maxRounds) parseSubagentRequests(mainText).take(maxParallel) else emptyList()
                        if (requests.isEmpty()) {
                            finalAnswer = mainText.replace(
                                Regex("<<SUBAGENTS>>[\\s\\S]*?<</SUBAGENTS>>", RegexOption.IGNORE_CASE),
                                ""
                            ).trim().ifBlank { mainText.ifBlank { "(응답 없음)" } }
                            break
                        }

                        roundHistory.add(ChatMessage(id = "rh_u_$round", role = MessageRole.USER, content = currentPrompt))
                        roundHistory.add(ChatMessage(id = "rh_a_$round", role = MessageRole.ASSISTANT, content = mainText))

                        val runs = requests.map { (role, task) ->
                            SubagentRun(
                                id = "sub_${UUID.randomUUID().toString().take(8)}",
                                label = role,
                                prompt = task,
                                model = subModel.ifBlank { subConn.name },
                                status = "running",
                                startedAt = System.currentTimeMillis()
                            )
                        }
                        sessions = sessions.map { s ->
                            if (s.id == session.id) s.copy(subagentRuns = s.subagentRuns + runs) else s
                        }
                        store.saveSessions(sessions)
                        setPlaceholder("⚡ 라운드 $round/$maxRounds · 서브에이전트 ${runs.size}개 실행 중 (${runs.joinToString(", ") { it.label }})...")

                        val latestSess = sessions.find { it.id == session.id } ?: session
                        val completedRuns = if (subConn.usesHiddenWebBridge()) {
                            runs.map { run ->
                                val d = scope.async { runSingleSubagent(latestSess, subConn, subModel, session.subEffort, effectiveDir, run) }
                                subagentJobsMap[run.id] = d
                                d.await()
                            }
                        } else {
                            coroutineScope {
                                runs.map { run ->
                                    val d = async { runSingleSubagent(latestSess, subConn, subModel, session.subEffort, effectiveDir, run) }
                                    subagentJobsMap[run.id] = d
                                    d
                                }.awaitAll()
                            }
                        }

                        setPlaceholder("⏳ 메인 오케스트레이터가 서브에이전트 결과 종합 중...")
                        val reportText = buildString {
                            append("[서브에이전트 라운드 $round/$maxRounds 결과 보고]\n")
                            completedRuns.forEach { r ->
                                val statusLabel = when (r.status) {
                                    "done" -> "완료"
                                    "cancelled" -> "중단됨"
                                    else -> "오류"
                                }
                                append("\n### [${r.label}] ($statusLabel)\n")
                                append(r.output.take(1800))
                                if (r.output.length > 1800) append("\n...(생략됨, 전체 로그는 모니터에서 확인)")
                                append("\n")
                            }
                            if (round >= maxRounds) {
                                append("\n위임 라운드 한도(${maxRounds}회)에 도달했습니다. 지금까지의 결과를 종합해 사용자에게 최종 답변을 작성하세요.")
                            } else {
                                append("\n추가 위임이 필요하면 <<SUBAGENTS>>를 출력하고, 충분하면 사용자에게 최종 답변을 작성하세요.")
                            }
                        }
                        currentPrompt = reportText
                    }

                    activeJobs.remove(session.id)
                    val finalMsg = ChatMessage(
                        id = placeholderId,
                        role = MessageRole.ASSISTANT,
                        content = finalError ?: finalAnswer.ifBlank { "(응답 없음)" },
                        inputTokens = totalInTok,
                        outputTokens = totalOutTok,
                        agentName = "${conn.name} (오케스트레이터)",
                        isError = finalError != null
                    )
                    sessions = sessions.map { s ->
                        if (s.id == session.id) {
                            s.copy(messages = s.messages.map { m -> if (m.id == placeholderId) finalMsg else m })
                        } else s
                    }
                    store.saveSessions(sessions)
                    sendingMap[session.id] = false
                    if (activeSessionId != session.id) unreadDoneSessions[session.id] = true
                    if (finalError == null) {
                        onNotifyEvent(session.id, "⚡ ${conn.name} 오케스트레이션 완료", finalMsg.content.take(140), false, emptyList())
                    } else {
                        onNotifyEvent(session.id, "❌ ${conn.name} 오케스트레이션 오류", finalError.take(140), true, listOf("Keep going"))
                    }
                    val q = queuedPromptsMap[session.id].orEmpty()
                    if (q.isNotEmpty()) {
                        val nextPrompt = q.first()
                        queuedPromptsMap[session.id] = q.drop(1)
                        val latest = sessions.find { it.id == session.id }
                        if (latest != null) sendMessageInSession(latest, conn, nextPrompt)
                    }
                } catch (_: CancellationException) {
                    activeJobs.remove(session.id)
                    sendingMap[session.id] = false
                }
            }
            activeJobs[session.id] = orchJob
            return
        }
        val mentionedConn = if (text.startsWith("@")) {
            enabledConnections.firstOrNull { c ->
                text.startsWith("@${c.name} ", ignoreCase = true)
            }
        } else null

        val targetConn = mentionedConn ?: conn
        val effectiveConn = if (mentionedConn == null && session.selectedModel.isNotBlank()) {
            targetConn.copy(model = session.selectedModel)
        } else {
            targetConn
        }
        val cleanPrompt = if (mentionedConn != null) {
            text.substring(mentionedConn.name.length + 2).trim()
        } else {
            text
        }
        val effectiveDir = session.worktreePath.ifBlank { activeProject.workDir }
        val effectivePrompt = if (useProjectContext && (effectiveDir.isNotBlank() || activeProject.memoryText.isNotBlank())) {
            if (targetConn.kind == ConnectionKind.TERMUX_CLI && effectiveDir.isNotBlank()) {
                "cd $effectiveDir && $cleanPrompt"
            } else {
                buildString {
                    append("[프로젝트 컨텍스트]\n")
                    if (effectiveDir.isNotBlank()) append("- 작업 경로: $effectiveDir\n")
                    if (activeProject.memoryText.isNotBlank()) append("- 공용 지침: ${activeProject.memoryText}\n")
                    append("\n").append(cleanPrompt)
                }
            }
        } else {
            cleanPrompt
        }
        val displayAgentName = if (mentionedConn != null) "@${mentionedConn.name}" else targetConn.name

        val userMsg = ChatMessage(
            id = "msg_${UUID.randomUUID()}",
            role = MessageRole.USER,
            content = text
        )
        val historyBefore = session.messages
        if (targetConn.usesHiddenWebBridge()) {
            val placeholderId = "msg_${UUID.randomUUID()}"
            val placeholder = ChatMessage(
                id = placeholderId,
                role = MessageRole.ASSISTANT,
                content = "응답 대기 중...",
                agentName = displayAgentName
            )
            sessions = sessions.map {
                if (it.id == session.id) it.copy(messages = it.messages + userMsg + placeholder) else it
            }
            store.saveSessions(sessions)
            sendingMap[session.id] = true

            headlessWebBridge.sendPrompt(
                key = targetConn.id,
                kind = targetConn.kind,
                url = targetConn.endpointUrl,
                prompt = effectivePrompt,
                conversationUrl = session.webConversationUrl,
                onChunk = { partial ->
                    sessions = sessions.map { s ->
                        if (s.id == session.id) {
                            s.copy(messages = s.messages.map { m ->
                                if (m.id == placeholderId) m.copy(content = partial) else m
                            })
                        } else s
                    }
                },
                onDone = { finalText, inTok, outTok ->
                    val curUrl = headlessWebBridge.currentUrl(targetConn.id).orEmpty()
                    val newConvUrl = if (isSavableConversationUrl(curUrl, targetConn.endpointUrl) && curUrl != session.webConversationUrl) {
                        curUrl
                    } else {
                        session.webConversationUrl
                    }
                    sessions = sessions.map { s ->
                        if (s.id == session.id) {
                            s.copy(
                                webConversationUrl = newConvUrl,
                                messages = s.messages.map { m ->
                                    if (m.id == placeholderId) {
                                        m.copy(content = finalText, inputTokens = inTok, outputTokens = outTok)
                                    } else m
                                }
                            )
                        } else s
                    }
                    store.saveSessions(sessions)
                    onRecordTokens(targetConn.id, targetConn.kind, inTok, outTok)
                    sendingMap[session.id] = false
                    if (activeSessionId != session.id) unreadDoneSessions[session.id] = true
                    val replies = detectQuickReplies(finalText)
                    when {
                        replies.firstOrNull() == "허용" -> onNotifyEvent(session.id, "⚠️ ${targetConn.name} 권한 승인 필요", finalText.take(140), true, listOf("허용", "거부"))
                        replies.isNotEmpty() -> onNotifyEvent(session.id, "❓ ${targetConn.name} 확인 대기 중", finalText.take(140), true, replies.take(2))
                        else -> onNotifyEvent(session.id, "✅ ${targetConn.name} 응답 완료", finalText.take(140), false, emptyList())
                    }
                },
                onError = { errMsg ->
                    sessions = sessions.map { s ->
                        if (s.id == session.id) {
                            s.copy(messages = s.messages.map { m ->
                                if (m.id == placeholderId) m.copy(content = errMsg, isError = true) else m
                            })
                        } else s
                    }
                    store.saveSessions(sessions)
                    sendingMap[session.id] = false
                    onNotifyEvent(session.id, "❌ ${targetConn.name} 작업 오류", errMsg.take(140), true, emptyList())
                }
            )
            return
        }

        val isBridgeKind = effectiveConn.kind == ConnectionKind.REMOTE_CONTROL || effectiveConn.kind == ConnectionKind.TERMUX_CLI
        val placeholderId = if (isBridgeKind) "msg_${UUID.randomUUID()}" else ""
        val initialMessages = if (isBridgeKind) {
            session.messages + userMsg + ChatMessage(
                id = placeholderId,
                role = MessageRole.ASSISTANT,
                content = "⏳ 작업 중...",
                agentName = displayAgentName
            )
        } else {
            session.messages + userMsg
        }
        sessions = sessions.map {
            if (it.id == session.id) it.copy(messages = initialMessages) else it
        }
        store.saveSessions(sessions)
        sendingMap[session.id] = true

        val pollJob = if (isBridgeKind) {
            scope.launch {
                var lastSig = ""
                var lastChangedAt = System.currentTimeMillis()
                var stuckNotified = false
                while (sendingMap[session.id] == true) {
                    kotlinx.coroutines.delay(1200L)
                    val lines = GitBridgeClient.fetchProgress(session.id)
                    val hasTextOutput = lines.any { it.startsWith("💬 ") }
                    val filtered = lines.filter { line ->
                        val t = line.trim()
                        if (t.matches(Regex("^⚙️.*\\((done|completed|finished)\\)$", RegexOption.IGNORE_CASE))) {
                            false
                        } else if (hasTextOutput && t.startsWith("⚙️ ")) {
                            false
                        } else {
                            true
                        }
                    }.fold(mutableListOf<String>()) { acc, line ->
                        if (acc.lastOrNull() != line) acc.add(line)
                        acc
                    }
                    val contentSig = filtered.filterNot { it.contains("프로세스 활성") }.joinToString("\n")
                    if (contentSig != lastSig) {
                        lastSig = contentSig
                        lastChangedAt = System.currentTimeMillis()
                        stuckNotified = false
                    } else if (!stuckNotified && System.currentTimeMillis() - lastChangedAt >= 120_000L) {
                        stuckNotified = true
                        onNotifyEvent(
                            session.id,
                            "⏱️ ${targetConn.name} 응답 지연",
                            "2분 동안 새 진행 업데이트가 없습니다.",
                            true,
                            listOf("Keep going")
                        )
                    }
                    if (filtered.isNotEmpty()) {
                        val logText = "⏳ 작업 진행 로그:\n" + filtered.joinToString("\n")
                        sessions = sessions.map { s ->
                            if (s.id == session.id) {
                                s.copy(messages = s.messages.map { m ->
                                    if (m.id == placeholderId) m.copy(content = logText) else m
                                })
                            } else s
                        }
                    }
                }
            }
        } else null

        val job = scope.launch {
            val reply = AiApiClient.send(effectiveConn, historyBefore, effectivePrompt, session.id, cwd = effectiveDir, cliSessionId = session.cliSessionId, effort = session.selectedEffort, skipPermissions = session.skipPermissions)
            pollJob?.cancel()
            activeJobs.remove(session.id)
            val assistantMsg = ChatMessage(
                id = if (isBridgeKind) placeholderId else "msg_${UUID.randomUUID()}",
                role = MessageRole.ASSISTANT,
                content = reply.error ?: reply.text.ifBlank { "(응답 없음)" },
                inputTokens = reply.inputTokens,
                outputTokens = reply.outputTokens,
                agentName = displayAgentName,
                isError = reply.error != null
            )
            sessions = sessions.map {
                if (it.id == session.id) {
                    val updated = if (isBridgeKind) {
                        it.messages.map { m -> if (m.id == placeholderId) assistantMsg else m }
                    } else {
                        it.messages + assistantMsg
                    }
                    it.copy(messages = updated)
                } else it
            }
            store.saveSessions(sessions)
            if (reply.inputTokens > 0L || reply.outputTokens > 0L) {
                onRecordTokens(targetConn.id, targetConn.kind, reply.inputTokens, reply.outputTokens)
            }
            if (reply.quotaPercent != null) {
                val cur = tokenUsageMap[targetConn.id] ?: AgentTokenUsage(panelId = targetConn.id)
                tokenUsageMap[targetConn.id] = cur.copy(
                    quotaPercent = reply.quotaPercent,
                    quotaLabel = reply.quotaLabel,
                    updatedAt = System.currentTimeMillis()
                )
                store.saveTokenUsage(tokenUsageMap)
            }
            sendingMap[session.id] = false
            if (activeSessionId != session.id) unreadDoneSessions[session.id] = true
            if (reply.error == null) {
                autoRetryCountMap[session.id] = 0
                val replies = detectQuickReplies(assistantMsg.content)
                when {
                    replies.firstOrNull() == "허용" -> onNotifyEvent(session.id, "⚠️ ${targetConn.name} 권한 승인 필요", assistantMsg.content.take(140), true, listOf("허용", "거부"))
                    replies.isNotEmpty() -> onNotifyEvent(session.id, "❓ ${targetConn.name} 확인 대기 중", assistantMsg.content.take(140), true, replies.take(2))
                    else -> onNotifyEvent(session.id, "✅ ${targetConn.name} 응답 완료", assistantMsg.content.take(140), false, emptyList())
                }
            } else {
                onNotifyEvent(session.id, "❌ ${targetConn.name} 작업 오류", reply.error.take(140), true, listOf("Keep going"))
                val autoResumeOn = context.getSharedPreferences("mbta_ui_prefs", android.content.Context.MODE_PRIVATE)
                    .getBoolean("auto_resume_enabled", true)
                val isTransient = Regex("(timeout|timed out|rate.?limit|429|502|503|overloaded|connection|reset|broken pipe|eof)", RegexOption.IGNORE_CASE)
                    .containsMatchIn(reply.error)
                val retries = autoRetryCountMap[session.id] ?: 0
                if (autoResumeOn && isTransient && retries < 2) {
                    autoRetryCountMap[session.id] = retries + 1
                    scope.launch {
                        kotlinx.coroutines.delay(5000L)
                        val latestSess = sessions.find { it.id == session.id }
                        if (latestSess != null && sendingMap[session.id] != true) {
                            sendMessageInSession(latestSess, conn, "Keep going")
                        }
                    }
                }
            }
            val q = queuedPromptsMap[session.id].orEmpty()
            if (q.isNotEmpty()) {
                val nextPrompt = q.first()
                queuedPromptsMap[session.id] = q.drop(1)
                val latestSess = sessions.find { it.id == session.id }
                if (latestSess != null) sendMessageInSession(latestSess, conn, nextPrompt)
            }
        }
        job.invokeOnCompletion { pollJob?.cancel() }
        activeJobs[session.id] = job
    }

    fun cancelSessionTask(session: AgentSession, conn: AiConnection) {
        activeJobs.remove(session.id)?.cancel()
        session.subagentRuns.filter { it.status == "running" }.forEach { run ->
            subagentJobsMap.remove(run.id)?.cancel()
        }
        sendingMap[session.id] = false
        if (conn.usesHiddenWebBridge()) {
            headlessWebBridge.cancelActiveRequest(conn.id)
        } else if (conn.kind == ConnectionKind.REMOTE_CONTROL || conn.kind == ConnectionKind.TERMUX_CLI) {
            scope.launch { GitBridgeClient.cancelSession(session.id) }
        }
        sessions = sessions.map { s ->
            if (s.id == session.id) {
                val last = s.messages.lastOrNull()
                val updated = if (last != null && last.role == MessageRole.ASSISTANT && (last.content.startsWith("⏳") || last.content.startsWith("⚡") || last.content == "응답 대기 중...")) {
                    s.messages.dropLast(1) + last.copy(content = "⛔ 작업이 취소되었습니다.", isError = true)
                } else {
                    s.messages + ChatMessage(id = "msg_${UUID.randomUUID()}", role = MessageRole.SYSTEM, content = "⛔ 작업이 취소되었습니다.")
                }
                val updatedRuns = s.subagentRuns.map { r ->
                    if (r.status == "running") r.copy(status = "cancelled", output = "메인 작업 취소로 중단됨", finishedAt = System.currentTimeMillis()) else r
                }
                s.copy(messages = updated, subagentRuns = updatedRuns)
            } else s
        }
        store.saveSessions(sessions)
    }

    fun sendQueuedNow(session: AgentSession, conn: AiConnection, idx: Int) {
        val cur = queuedPromptsMap[session.id].orEmpty()
        val msg = cur.getOrNull(idx) ?: return
        queuedPromptsMap[session.id] = cur.filterIndexed { i, _ -> i != idx }
        if (sendingMap[session.id] == true) {
            cancelSessionTask(session, conn)
        }
        val latest = sessions.find { it.id == session.id } ?: session
        sendMessageInSession(latest, conn, msg)
    }

    fun handoffSession(
        fromSession: AgentSession,
        targetConn: AiConnection,
        smartSummary: Boolean = true,
        includeGit: Boolean = true,
        autoSend: Boolean = true
    ) {
        val prevConn = enabledConnections.find { it.id == fromSession.connectionId }
        if (sendingMap[fromSession.id] == true && prevConn != null) {
            cancelSessionTask(fromSession, prevConn)
        }
        scope.launch {
            val nonSys = fromSession.messages.filter { it.role != MessageRole.SYSTEM }
            val pinned = nonSys.filter { it.isPinned }
            val effectiveDir = fromSession.worktreePath.ifBlank { activeProject.workDir }
            val gitSnapshot = if (includeGit && effectiveDir.isNotBlank()) {
                val st = GitBridgeClient.fetchStatus(effectiveDir)
                if (st.isGit) {
                    buildString {
                        append("[현재 Git 상태]\n")
                        if (st.branch.isNotBlank()) append("- 브랜치: ${st.branch}\n")
                        if (st.files.isNotEmpty()) append("- 변경 파일:\n").append(st.files.take(20).joinToString("\n")).append("\n")
                        if (st.diff.isNotBlank()) append("- 최근 Diff 요약:\n").append(st.diff.take(1500)).append("\n")
                    }
                } else ""
            } else ""

            val contextBody = buildString {
                append("[세션 인수인계 — 기존 세션 '${fromSession.title}' (${prevConn?.name ?: "이전 AI"} → ${targetConn.name})]\n")
                if (pinned.isNotEmpty()) {
                    append("\n### 📌 핀 고정된 핵심 내용\n")
                    pinned.forEach { m ->
                        val who = if (m.role == MessageRole.USER) "User" else (m.agentName.ifBlank { "Assistant" })
                        append("[$who]: ${m.content.take(1200)}\n\n")
                    }
                }
                if (smartSummary && nonSys.size > 4) {
                    val older = nonSys.dropLast(4)
                    val recent = nonSys.takeLast(4)
                    append("\n### 이전 대화 핵심 흐름 (${older.size}개 메시지 압축)\n")
                    older.forEach { m ->
                        val who = if (m.role == MessageRole.USER) "User" else (m.agentName.ifBlank { "Assistant" })
                        val compact = m.content.replace(Regex("\\s+"), " ").trim().take(240)
                        append("- [$who]: $compact\n")
                    }
                    append("\n### 최근 대화 원문\n")
                    recent.forEach { m ->
                        val who = if (m.role == MessageRole.USER) "User" else (m.agentName.ifBlank { "Assistant" })
                        append("[$who]: ${m.content.take(2000)}\n\n")
                    }
                } else {
                    append("\n### 이전 대화 원문\n")
                    nonSys.forEach { m ->
                        val who = if (m.role == MessageRole.USER) "User" else (m.agentName.ifBlank { "Assistant" })
                        append("[$who]: ${m.content}\n\n")
                    }
                }
                if (gitSnapshot.isNotBlank()) {
                    append("\n").append(gitSnapshot)
                }
                append("\n위 인수인계 맥락을 정확히 숙지했다면 핵심만 2~3줄로 확인 응답해 주세요. 바로 이어서 작업하겠습니다.")
            }

            val isSameAi = targetConn.id == fromSession.connectionId
            val modeLabel = if (smartSummary) "스마트 압축" else "전체 원문"
            val bannerMsg = ChatMessage(
                id = "msg_${UUID.randomUUID()}",
                role = MessageRole.SYSTEM,
                content = if (isSameAi) {
                    "🔄 ${targetConn.name} 새 세션으로 리프레시 이관 ($modeLabel)${if (!autoSend) "\n\n$contextBody" else ""}"
                } else {
                    "🔄 ${prevConn?.name ?: "이전 AI"} → ${targetConn.name} 세션 이관 ($modeLabel)${if (!autoSend) "\n\n$contextBody" else ""}"
                }
            )
            val replaced = fromSession.copy(
                connectionId = targetConn.id,
                selectedModel = if (isSameAi) fromSession.selectedModel else "",
                selectedEffort = if (isSameAi) fromSession.selectedEffort else "",
                webConversationUrl = "",
                cliSessionId = "",
                messages = listOf(bannerMsg)
            )
            sessions = sessions.map { if (it.id == fromSession.id) replaced else it }
            store.saveSessions(sessions)

            if (targetConn.usesHiddenWebBridge()) {
                headlessWebBridge.getOrCreateWebView(targetConn.id, targetConn.endpointUrl)
                headlessWebBridge.navigateIfNeeded(targetConn.id, targetConn.endpointUrl)
            }
            if (autoSend) {
                sendMessageInSession(replaced, targetConn, contextBody)
            }
        }
    }

    fun deleteSession(session: AgentSession) {
        val remainingInProject = validProjectSessions.filterNot { it.id == session.id }
        sessions = sessions.filterNot { it.id == session.id }
        if (activeSessionId == session.id) {
            activeSessionId = remainingInProject.firstOrNull()?.id ?: ""
            store.saveActiveSessionId(activeSessionId)
        }
        store.saveSessions(sessions)
    }

    fun clearSessionMessages(session: AgentSession) {
        sessions = sessions.map { if (it.id == session.id) it.copy(webConversationUrl = "", messages = emptyList()) else it }
        store.saveSessions(sessions)
    }

    fun selectModelForSession(session: AgentSession, conn: AiConnection, model: String) {
        sessions = sessions.map { if (it.id == session.id) it.copy(selectedModel = model) else it }
        store.saveSessions(sessions)
        if (conn.usesHiddenWebBridge()) {
            headlessWebBridge.switchWebModel(conn.id, model)
        }
    }

    fun selectEffortForSession(session: AgentSession, conn: AiConnection, effort: String) {
        val curModel = session.selectedModel.ifBlank { conn.model.ifBlank { com.mbta.app.model.defaultModelFor(conn) } }
        val hasParen = com.mbta.app.model.extractParenEffort(curModel).isNotBlank()
        val updatedModel = if (hasParen) {
            "${com.mbta.app.model.stripParenEffort(curModel)} ($effort)"
        } else {
            session.selectedModel
        }
        sessions = sessions.map {
            if (it.id == session.id) it.copy(selectedModel = updatedModel, selectedEffort = effort) else it
        }
        store.saveSessions(sessions)
    }

    fun renameSession(session: AgentSession, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        sessions = sessions.map { if (it.id == session.id) it.copy(title = trimmed) else it }
        store.saveSessions(sessions)
    }

    fun toggleSkipPermissions(session: AgentSession) {
        sessions = sessions.map { s ->
            if (s.id == session.id) s.copy(skipPermissions = !s.skipPermissions) else s
        }
        store.saveSessions(sessions)
    }

    fun attachOrchestrationToSession(
        session: AgentSession,
        enabled: Boolean,
        subConnId: String?,
        subModel: String,
        subEffort: String,
        maxParallel: Int,
        maxRounds: Int,
        sensitivity: String = "medium"
    ) {
        sessions = sessions.map { s ->
            if (s.id == session.id) {
                s.copy(
                    isOrchestration = enabled,
                    subConnectionId = if (enabled) subConnId else null,
                    subModel = if (enabled) subModel else "",
                    subEffort = if (enabled) subEffort else "",
                    maxParallelSubagents = maxParallel.coerceIn(1, 8),
                    maxDelegationRounds = maxRounds.coerceIn(1, 6),
                    subagentSensitivity = sensitivity.ifBlank { "medium" }
                )
            } else s
        }
        store.saveSessions(sessions)
        if (enabled && subConnId != null) {
            val subConn = enabledConnections.find { it.id == subConnId }
            if (subConn != null && subConn.usesHiddenWebBridge()) {
                headlessWebBridge.getOrCreateWebView(subConn.id, subConn.endpointUrl)
            }
        }
    }

    fun togglePinMessage(sessionId: String, messageId: String) {
        sessions = sessions.map { s ->
            if (s.id == sessionId) {
                s.copy(messages = s.messages.map { m ->
                    if (m.id == messageId) m.copy(isPinned = !m.isPinned) else m
                })
            } else s
        }
        store.saveSessions(sessions)
    }

    LaunchedEffect(pendingNotifSessionId, pendingNotifReply) {
        val targetSid = pendingNotifSessionId ?: return@LaunchedEffect
        val targetSess = sessions.find { it.id == targetSid }
        if (targetSess != null) {
            if (targetSess.projectId != activeProjectId) {
                activeProjectId = targetSess.projectId
                store.saveActiveProjectId(targetSess.projectId)
            }
            activeSessionId = targetSess.id
            store.saveActiveSessionId(targetSess.id)
            unreadDoneSessions.remove(targetSess.id)
            val replyPair = pendingNotifReply
            if (replyPair != null && replyPair.first == targetSess.id) {
                val targetConn = connections.find { it.id == targetSess.connectionId }
                if (targetConn != null) {
                    val replyText = replyPair.second
                    val isPermChoice = replyText in setOf("허용", "허용 안 함", "항상 허용", "거부")
                    if (isPermChoice && targetConn.usesHiddenWebBridge()) {
                        headlessWebBridge.clickWebActionButton(targetConn.id, replyText) { clicked ->
                            if (!clicked) sendMessageInSession(targetSess, targetConn, replyText)
                        }
                    } else {
                        sendMessageInSession(targetSess, targetConn, replyText)
                    }
                }
            }
        }
        onConsumeNotifAction()
    }

    val lastRefreshAtMap = remember { mutableStateMapOf<String, Long>() }
    fun refreshSessionFromRemote(sess: AgentSession) {
        if (sendingMap[sess.id] == true) return
        val now = System.currentTimeMillis()
        val prev = lastRefreshAtMap[sess.id] ?: 0L
        if (now - prev < 3000L) return
        lastRefreshAtMap[sess.id] = now

        val conn = connections.find { it.id == sess.connectionId } ?: return
        val mergeFetched: (List<ChatMessage>) -> Unit = { fetched ->
            if (fetched.isNotEmpty() && sendingMap[sess.id] != true) {
                val latestSess = sessions.find { it.id == sess.id }
                val curNonSys = latestSess?.messages?.filter { it.role != MessageRole.SYSTEM }.orEmpty()
                val hasNew = fetched.size > curNonSys.size ||
                    fetched.last().content != curNonSys.lastOrNull()?.content ||
                    fetched.last().role != curNonSys.lastOrNull()?.role
                if (hasNew) {
                    sessions = sessions.map { if (it.id == sess.id) it.copy(messages = fetched) else it }
                    store.saveSessions(sessions)
                }
            }
        }
        if (conn.usesHiddenWebBridge()) {
            val liveUrl = headlessWebBridge.currentUrl(conn.id).orEmpty()
            val targetUrl = sess.webConversationUrl.ifBlank {
                if (isSavableConversationUrl(liveUrl, conn.endpointUrl)) liveUrl else ""
            }
            if (targetUrl.isNotBlank()) {
                if (sess.webConversationUrl.isBlank()) {
                    sessions = sessions.map { if (it.id == sess.id) it.copy(webConversationUrl = targetUrl) else it }
                    store.saveSessions(sessions)
                }
                headlessWebBridge.fetchWebSessionMessages(
                    key = conn.id,
                    kind = conn.kind,
                    url = targetUrl,
                    agentName = conn.name,
                    forceReload = true,
                    onResult = mergeFetched
                )
            }
        } else if (!conn.usesHiddenWebBridge() && sess.cliSessionId.isNotBlank()) {
            val bridgeKind = if (conn.endpointUrl.lowercase().contains("/agy")) "agy" else "claude"
            scope.launch {
                mergeFetched(GitBridgeClient.fetchSessionMessages(bridgeKind, sess.cliSessionId, conn.name))
            }
        }
    }

    fun retryLastInSession(session: AgentSession, conn: AiConnection) {
        val lastUserIdx = session.messages.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIdx == -1) return
        val lastPrompt = session.messages[lastUserIdx].content
        val trimmed = session.messages.take(lastUserIdx)
        val cleaned = session.copy(messages = trimmed)
        sessions = sessions.map { if (it.id == session.id) cleaned else it }
        store.saveSessions(sessions)
        sendMessageInSession(cleaned, conn, lastPrompt)
    }

    fun cycleSplitMode() {
        val next = when (splitMode) {
            SplitMode.SINGLE -> SplitMode.SPLIT_VERTICAL
            SplitMode.SPLIT_VERTICAL -> SplitMode.SPLIT_HORIZONTAL
            SplitMode.SPLIT_HORIZONTAL -> SplitMode.SINGLE
        }
        splitMode = next
        store.saveSplitMode(next)
        if (next == SplitMode.SINGLE) focusedPane = 0
    }

    val activeWebSessions = listOfNotNull(primarySession, secondarySession).distinctBy { it.id }.mapNotNull { sess ->
        val conn = enabledConnections.find { it.id == sess.connectionId }
        if (conn != null && conn.usesHiddenWebBridge()) {
            Triple(conn.id, conn.endpointUrl, sess)
        } else null
    }.distinctBy { it.first }

    Box(modifier = Modifier.fillMaxSize()) {
        // Layer 0: Hidden full-viewport host for active WebView bridges
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { host ->
                if (loginConnection == null) {
                    connections.filter { it.enabled && it.usesHiddenWebBridge() }.forEach { conn ->
                        headlessWebBridge.attachToHiddenHost(host, conn.id, conn.endpointUrl)
                    }
                }
            }
        )

        // Layer 1: Opaque MbtA Native UI
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
            if (showSettings) {
                SettingsScreen(
                    themeMode = themeMode,
                    connections = connections,
                    customSkills = customSkills,
                    loginStatusMap = loginStatusMap,
                    onThemeModeChanged = onThemeModeChanged,
                    onSaveConnection = { saved ->
                        val exists = connections.any { it.id == saved.id }
                        connections = if (exists) {
                            connections.map { if (it.id == saved.id) saved else it }
                        } else {
                            connections + saved
                        }
                        store.saveConnections(connections)
                        if (saved.usesHiddenWebBridge() && saved.endpointUrl.isNotBlank()) {
                            headlessWebBridge.navigateIfNeeded(saved.id, saved.endpointUrl)
                        }
                    },
                    onDeleteConnection = { connId ->
                        connections = connections.filterNot { it.id == connId }
                        sessions = sessions.filterNot { it.connectionId == connId }
                        store.saveConnections(connections)
                        store.saveSessions(sessions)
                    },
                    onSaveSkill = { saved ->
                        val exists = customSkills.any { it.id == saved.id }
                        customSkills = if (exists) {
                            customSkills.map { if (it.id == saved.id) saved else it }
                        } else {
                            customSkills + saved
                        }
                        store.saveCustomSkills(customSkills)
                    },
                    onDeleteSkill = { skillId ->
                        customSkills = customSkills.filterNot { it.id == skillId }
                        store.saveCustomSkills(customSkills)
                    },
                    onLoginConnection = { conn -> loginConnection = conn },
                    onCopyDiagnostics = {
                        val baseLogs = headlessWebBridge.getLogsText()
                        val activeWebKey = primarySession?.connectionId?.takeIf { cid ->
                            connections.find { it.id == cid }?.usesHiddenWebBridge() == true
                        }
                        val copyToClip: (String) -> Unit = { fullText ->
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("MbtA Diagnostics", fullText))
                            android.widget.Toast.makeText(context, "진단 로그가 클립보드에 복사되었습니다.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        if (activeWebKey != null) {
                            headlessWebBridge.dumpDomDiagnostics(activeWebKey) { domDump ->
                                copyToClip("$baseLogs\n\nDOM Dump ($activeWebKey):\n$domDump")
                            }
                        } else {
                            copyToClip(baseLogs)
                        }
                    },
                    onCheckUpdate = {
                        scope.launch {
                            val info = AppUpdater.checkLatestRelease(context)
                            if (info != null) {
                                availableUpdate = info
                            } else {
                                updateStatusMsg = "최신 버전입니다."
                            }
                        }
                    },
                    onBack = { showSettings = false }
                )
            } else {
                Scaffold(
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(34.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { showProjectMenu = true }
                                .padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = activeProject.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = "프로젝트 선택",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showProjectMenu,
                            onDismissRequest = { showProjectMenu = false }
                        ) {
                            projects.forEach { proj ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            proj.name,
                                            fontWeight = if (proj.id == activeProject.id) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        activeProjectId = proj.id
                                        store.saveActiveProjectId(proj.id)
                                        val firstSess = sessions.firstOrNull { it.projectId == proj.id }
                                        if (firstSess != null) {
                                            activeSessionId = firstSess.id
                                            store.saveActiveSessionId(firstSess.id)
                                        }
                                        showProjectMenu = false
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("+ 새 프로젝트") },
                                onClick = {
                                    showProjectMenu = false
                                    showNewProjectDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("현재 프로젝트 편집") },
                                onClick = {
                                    showProjectMenu = false
                                    editingProject = activeProject
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { showGlobalSearch = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Search, contentDescription = "통합 검색 · 핀", modifier = Modifier.size(18.dp))
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .clickable { showTokenDashboard = true }
                    ) {
                        Text(
                            text = "Tokens ${formatTokens(totalTokensAll)}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (activeProject.workDir.isNotBlank()) {
                        IconButton(onClick = { showGitDialog = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Code, contentDescription = "Git · Diff · CI", modifier = Modifier.size(18.dp))
                        }
                    }
                    if (enabledConnections.size >= 2) {
                        IconButton(onClick = { showFanOutDialog = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.CompareArrows, contentDescription = "동시 비교", modifier = Modifier.size(18.dp))
                        }
                    }
                    IconButton(onClick = { cycleSplitMode() }, modifier = Modifier.size(32.dp)) {
                        val icon = when (splitMode) {
                            SplitMode.SINGLE -> Icons.Default.ViewAgenda
                            SplitMode.SPLIT_VERTICAL -> Icons.Default.ViewStream
                            SplitMode.SPLIT_HORIZONTAL -> Icons.Default.ViewColumn
                        }
                        Icon(icon, contentDescription = "화면 분할", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { showSettings = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = "설정", modifier = Modifier.size(18.dp))
                    }
                }

                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 0.dp, bottom = 2.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    itemsIndexed(validProjectSessions, key = { _, it -> it.id }) { idx, sess ->
                        val conn = enabledConnections.find { it.id == sess.connectionId }
                        val isSelected = if (splitMode == SplitMode.SINGLE) {
                            sess.id == primarySession?.id
                        } else {
                            sess.id == primarySession?.id || sess.id == secondarySession?.id
                        }
                        val dotColor = when {
                            sendingMap[sess.id] == true -> Color(0xFFF59E0B)
                            sess.messages.lastOrNull()?.isError == true -> Color(0xFFEF4444)
                            unreadDoneSessions[sess.id] == true -> Color(0xFF22C55E)
                            else -> Color.Black
                        }
                        Surface(
                            onClick = {
                                unreadDoneSessions.remove(sess.id)
                                if (splitMode == SplitMode.SINGLE || focusedPane == 0) {
                                    activeSessionId = sess.id
                                    store.saveActiveSessionId(sess.id)
                                } else {
                                    secondarySessionId = sess.id
                                    store.saveSecondarySessionId(sess.id)
                                }
                                refreshSessionFromRemote(sess)
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(dotColor, CircleShape)
                                )
                                ProviderMarkIcon(connection = conn, size = 19.dp)
                                val trimmedTitle = sess.title.trim()
                                val isDefaultTitle = trimmedTitle.isBlank() ||
                                    trimmedTitle.matches(Regex("^세션\\s*\\d+$")) ||
                                    trimmedTitle == "기본 세션" ||
                                    (conn != null && trimmedTitle == conn.name.trim())
                                val baseLabel = if (isDefaultTitle) "${idx + 1}" else trimmedTitle
                                val tabLabel = if (sess.isOrchestration) "⚡$baseLabel" else baseLabel
                                Text(
                                    text = tabLabel,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = if (isDefaultTitle) Modifier else Modifier.widthIn(max = 100.dp)
                                )
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "세션 삭제",
                                    modifier = Modifier
                                        .size(15.dp)
                                        .clickable { sessionPendingDelete = sess },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                )
                            }
                        }
                    }
                    item {
                        IconButton(
                            onClick = { showNewSessionDialog = true },
                            modifier = Modifier.size(31.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "새 하위 세션", modifier = Modifier.size(19.dp))
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (primarySession == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (enabledConnections.isEmpty()) {
                                Text("등록된 AI 연결이 없습니다", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(
                                    text = "설정에서 먼저 AI(Claude, ChatGPT, Gemini 등)를 연결하세요.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(onClick = { showSettings = true }) {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text("연결 설정 열기")
                                }
                            } else {
                                Text("열려 있는 세션이 없습니다", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(
                                    text = "연결할 AI를 선택해 새 세션을 만들어 주세요.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(onClick = { showNewSessionDialog = true }) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text("+ 새 세션 만들기")
                                }
                            }
                        }
                    }
                }
            } else {
            val primaryConn = enabledConnections.find { it.id == primarySession.connectionId }
            val secondaryConn = secondarySession?.let { s -> enabledConnections.find { it.id == s.connectionId } }

            when (splitMode) {
                SplitMode.SINGLE -> {
                    SessionPane(
                        project = activeProject,
                        session = primarySession,
                        connection = primaryConn,
                        enabledConnections = enabledConnections,
                        customSkills = customSkills,
                        useProjectContext = useProjectContext,
                        tokenUsage = primaryConn?.let { tokenUsageMap[it.id] },
                        isLoggedIn = primaryConn?.let { loginStatusMap[it.id] == true } ?: false,
                        isSending = sendingMap[primarySession.id] == true,
                        isFocused = true,
                        showFocusBorder = false,
                        onFocus = { focusedPane = 0 },
                        onToggleProjectContext = { useProjectContext = !useProjectContext },
                        onSendMessage = { text ->
                            if (primaryConn != null) sendMessageInSession(primarySession, primaryConn, text)
                        },
                        onRetryLast = {
                            if (primaryConn != null) retryLastInSession(primarySession, primaryConn)
                        },
                        onClearSession = { clearSessionMessages(primarySession) },
                        onShareMarkdown = { shareSessionAsMarkdown(context, activeProject, primarySession) },
                        onSelectModel = { model ->
                            if (primaryConn != null) selectModelForSession(primarySession, primaryConn, model)
                        },
                        onSelectEffort = { ef ->
                            if (primaryConn != null) selectEffortForSession(primarySession, primaryConn, ef)
                        },
                        onHandoffTo = { target, smart, git, auto -> handoffSession(primarySession, target, smart, git, auto) },
                        onDeleteSession = { deleteSession(primarySession) },
                        onOpenTokenDashboard = { showTokenDashboard = true },
                        onOpenSettings = { showSettings = true },
                        onLoginConnection = { conn -> loginConnection = conn },
                        headlessWebBridge = headlessWebBridge,
                        queuedMessages = queuedPromptsMap[primarySession.id].orEmpty(),
                        onCancelSending = { if (primaryConn != null) cancelSessionTask(primarySession, primaryConn) },
                        onRemoveQueued = { idx ->
                            val cur = queuedPromptsMap[primarySession.id].orEmpty()
                            queuedPromptsMap[primarySession.id] = cur.filterIndexed { i, _ -> i != idx }
                        },
                        onSendQueuedNow = { idx ->
                            if (primaryConn != null) sendQueuedNow(primarySession, primaryConn, idx)
                        },
                        onRenameSession = { newTitle -> renameSession(primarySession, newTitle) },
                        onTogglePinMessage = { msgId -> togglePinMessage(primarySession.id, msgId) },
                        onStopSubagent = { runId -> stopSubagentRun(primarySession.id, runId) },
                        onRetrySubagent = { runId -> retrySubagentRun(primarySession.id, runId) },
                        onAttachOrchestration = { en, subId, subM, subE, maxP, maxR, sens ->
                            attachOrchestrationToSession(primarySession, en, subId, subM, subE, maxP, maxR, sens)
                        },
                        onToggleSkipPermissions = { toggleSkipPermissions(primarySession) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                SplitMode.SPLIT_VERTICAL -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        SessionPane(
                            project = activeProject,
                            session = primarySession,
                            connection = primaryConn,
                            enabledConnections = enabledConnections,
                            customSkills = customSkills,
                            useProjectContext = useProjectContext,
                            tokenUsage = primaryConn?.let { tokenUsageMap[it.id] },
                            isLoggedIn = primaryConn?.let { loginStatusMap[it.id] == true } ?: false,
                            isSending = sendingMap[primarySession.id] == true,
                            isFocused = focusedPane == 0,
                            showFocusBorder = true,
                            onFocus = { focusedPane = 0 },
                            onToggleProjectContext = { useProjectContext = !useProjectContext },
                            onSendMessage = { text ->
                                if (primaryConn != null) sendMessageInSession(primarySession, primaryConn, text)
                            },
                            onRetryLast = {
                                if (primaryConn != null) retryLastInSession(primarySession, primaryConn)
                            },
                            onClearSession = { clearSessionMessages(primarySession) },
                                onShareMarkdown = { shareSessionAsMarkdown(context, activeProject, primarySession) },
                            onSelectModel = { model ->
                                if (primaryConn != null) selectModelForSession(primarySession, primaryConn, model)
                            },
                            onSelectEffort = { ef ->
                                if (primaryConn != null) selectEffortForSession(primarySession, primaryConn, ef)
                            },
                            onHandoffTo = { target, smart, git, auto -> handoffSession(primarySession, target, smart, git, auto) },
                            onDeleteSession = { deleteSession(primarySession) },
                            onOpenTokenDashboard = { showTokenDashboard = true },
                            onOpenSettings = { showSettings = true },
                            onLoginConnection = { conn -> loginConnection = conn },
                            headlessWebBridge = headlessWebBridge,
                            queuedMessages = queuedPromptsMap[primarySession.id].orEmpty(),
                            onCancelSending = { if (primaryConn != null) cancelSessionTask(primarySession, primaryConn) },
                            onRemoveQueued = { idx ->
                                val cur = queuedPromptsMap[primarySession.id].orEmpty()
                                queuedPromptsMap[primarySession.id] = cur.filterIndexed { i, _ -> i != idx }
                            },
                            onSendQueuedNow = { idx ->
                                if (primaryConn != null) sendQueuedNow(primarySession, primaryConn, idx)
                            },
                            onRenameSession = { newTitle -> renameSession(primarySession, newTitle) },
                            onTogglePinMessage = { msgId -> togglePinMessage(primarySession.id, msgId) },
                            onStopSubagent = { runId -> stopSubagentRun(primarySession.id, runId) },
                            onRetrySubagent = { runId -> retrySubagentRun(primarySession.id, runId) },
                            onAttachOrchestration = { en, subId, subM, subE, maxP, maxR, sens ->
                                attachOrchestrationToSession(primarySession, en, subId, subM, subE, maxP, maxR, sens)
                            },
                            onToggleSkipPermissions = { toggleSkipPermissions(primarySession) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
                        SessionPane(
                            project = activeProject,
                            session = secondarySession ?: primarySession,
                            connection = secondaryConn,
                            enabledConnections = enabledConnections,
                            customSkills = customSkills,
                            useProjectContext = useProjectContext,
                            tokenUsage = secondaryConn?.let { tokenUsageMap[it.id] },
                            isLoggedIn = secondaryConn?.let { loginStatusMap[it.id] == true } ?: false,
                            isSending = sendingMap[(secondarySession ?: primarySession).id] == true,
                            isFocused = focusedPane == 1,
                            showFocusBorder = true,
                            onFocus = { focusedPane = 1 },
                            onToggleProjectContext = { useProjectContext = !useProjectContext },
                            onSendMessage = { text ->
                                val targetSess = secondarySession ?: primarySession
                                if (secondaryConn != null) sendMessageInSession(targetSess, secondaryConn, text)
                            },
                            onRetryLast = {
                                val targetSess = secondarySession ?: primarySession
                                if (secondaryConn != null) retryLastInSession(targetSess, secondaryConn)
                            },
                            onClearSession = { clearSessionMessages(secondarySession ?: primarySession) },
                                onShareMarkdown = { shareSessionAsMarkdown(context, activeProject, secondarySession ?: primarySession) },
                            onSelectModel = { model ->
                                val targetSess = secondarySession ?: primarySession
                                if (secondaryConn != null) selectModelForSession(targetSess, secondaryConn, model)
                            },
                            onSelectEffort = { ef ->
                                val targetSess = secondarySession ?: primarySession
                                if (secondaryConn != null) selectEffortForSession(targetSess, secondaryConn, ef)
                            },
                            onHandoffTo = { target, smart, git, auto -> handoffSession(secondarySession ?: primarySession, target, smart, git, auto) },
                            onDeleteSession = { deleteSession(secondarySession ?: primarySession) },
                            onOpenTokenDashboard = { showTokenDashboard = true },
                            onOpenSettings = { showSettings = true },
                            onLoginConnection = { conn -> loginConnection = conn },
                            headlessWebBridge = headlessWebBridge,
                            queuedMessages = queuedPromptsMap[primarySession.id].orEmpty(),
                            onCancelSending = { if (primaryConn != null) cancelSessionTask(primarySession, primaryConn) },
                            onRemoveQueued = { idx ->
                                val cur = queuedPromptsMap[primarySession.id].orEmpty()
                                queuedPromptsMap[primarySession.id] = cur.filterIndexed { i, _ -> i != idx }
                            },
                            onSendQueuedNow = { idx ->
                                if (primaryConn != null) sendQueuedNow(primarySession, primaryConn, idx)
                            },
                            onRenameSession = { newTitle -> renameSession(primarySession, newTitle) },
                            onTogglePinMessage = { msgId -> togglePinMessage(primarySession.id, msgId) },
                            onStopSubagent = { runId -> stopSubagentRun((secondarySession ?: primarySession).id, runId) },
                            onRetrySubagent = { runId -> retrySubagentRun((secondarySession ?: primarySession).id, runId) },
                            onAttachOrchestration = { en, subId, subM, subE, maxP, maxR, sens ->
                                attachOrchestrationToSession(secondarySession ?: primarySession, en, subId, subM, subE, maxP, maxR, sens)
                            },
                            onToggleSkipPermissions = { toggleSkipPermissions(secondarySession ?: primarySession) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                }
                SplitMode.SPLIT_HORIZONTAL -> {
                    Row(modifier = Modifier.fillMaxSize()) {
                        SessionPane(
                            project = activeProject,
                            session = primarySession,
                            connection = primaryConn,
                            enabledConnections = enabledConnections,
                            customSkills = customSkills,
                            useProjectContext = useProjectContext,
                            tokenUsage = primaryConn?.let { tokenUsageMap[it.id] },
                            isLoggedIn = primaryConn?.let { loginStatusMap[it.id] == true } ?: false,
                            isSending = sendingMap[primarySession.id] == true,
                            isFocused = focusedPane == 0,
                            showFocusBorder = true,
                            onFocus = { focusedPane = 0 },
                            onToggleProjectContext = { useProjectContext = !useProjectContext },
                            onSendMessage = { text ->
                                if (primaryConn != null) sendMessageInSession(primarySession, primaryConn, text)
                            },
                            onRetryLast = {
                                if (primaryConn != null) retryLastInSession(primarySession, primaryConn)
                            },
                            onClearSession = { clearSessionMessages(primarySession) },
                                onShareMarkdown = { shareSessionAsMarkdown(context, activeProject, primarySession) },
                            onSelectModel = { model ->
                                if (primaryConn != null) selectModelForSession(primarySession, primaryConn, model)
                            },
                            onSelectEffort = { ef ->
                                if (primaryConn != null) selectEffortForSession(primarySession, primaryConn, ef)
                            },
                            onHandoffTo = { target, smart, git, auto -> handoffSession(primarySession, target, smart, git, auto) },
                            onDeleteSession = { deleteSession(primarySession) },
                            onOpenTokenDashboard = { showTokenDashboard = true },
                            onOpenSettings = { showSettings = true },
                            onLoginConnection = { conn -> loginConnection = conn },
                            headlessWebBridge = headlessWebBridge,
                            queuedMessages = queuedPromptsMap[primarySession.id].orEmpty(),
                            onCancelSending = { if (primaryConn != null) cancelSessionTask(primarySession, primaryConn) },
                            onRemoveQueued = { idx ->
                                val cur = queuedPromptsMap[primarySession.id].orEmpty()
                                queuedPromptsMap[primarySession.id] = cur.filterIndexed { i, _ -> i != idx }
                            },
                            onSendQueuedNow = { idx ->
                                if (primaryConn != null) sendQueuedNow(primarySession, primaryConn, idx)
                            },
                            onRenameSession = { newTitle -> renameSession(primarySession, newTitle) },
                            onTogglePinMessage = { msgId -> togglePinMessage(primarySession.id, msgId) },
                            onStopSubagent = { runId -> stopSubagentRun(primarySession.id, runId) },
                            onRetrySubagent = { runId -> retrySubagentRun(primarySession.id, runId) },
                            onAttachOrchestration = { en, subId, subM, subE, maxP, maxR, sens ->
                                attachOrchestrationToSession(primarySession, en, subId, subM, subE, maxP, maxR, sens)
                            },
                            onToggleSkipPermissions = { toggleSkipPermissions(primarySession) },
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                        )
                        VerticalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
                        SessionPane(
                            project = activeProject,
                            session = secondarySession ?: primarySession,
                            connection = secondaryConn,
                            enabledConnections = enabledConnections,
                            customSkills = customSkills,
                            useProjectContext = useProjectContext,
                            tokenUsage = secondaryConn?.let { tokenUsageMap[it.id] },
                            isLoggedIn = secondaryConn?.let { loginStatusMap[it.id] == true } ?: false,
                            isSending = sendingMap[(secondarySession ?: primarySession).id] == true,
                            isFocused = focusedPane == 1,
                            showFocusBorder = true,
                            onFocus = { focusedPane = 1 },
                            onToggleProjectContext = { useProjectContext = !useProjectContext },
                            onSendMessage = { text ->
                                val targetSess = secondarySession ?: primarySession
                                if (secondaryConn != null) sendMessageInSession(targetSess, secondaryConn, text)
                            },
                            onRetryLast = {
                                val targetSess = secondarySession ?: primarySession
                                if (secondaryConn != null) retryLastInSession(targetSess, secondaryConn)
                            },
                            onClearSession = { clearSessionMessages(secondarySession ?: primarySession) },
                                onShareMarkdown = { shareSessionAsMarkdown(context, activeProject, secondarySession ?: primarySession) },
                            onSelectModel = { model ->
                                val targetSess = secondarySession ?: primarySession
                                if (secondaryConn != null) selectModelForSession(targetSess, secondaryConn, model)
                            },
                            onSelectEffort = { ef ->
                                val targetSess = secondarySession ?: primarySession
                                if (secondaryConn != null) selectEffortForSession(targetSess, secondaryConn, ef)
                            },
                            onHandoffTo = { target, smart, git, auto -> handoffSession(secondarySession ?: primarySession, target, smart, git, auto) },
                            onDeleteSession = { deleteSession(secondarySession ?: primarySession) },
                            onOpenTokenDashboard = { showTokenDashboard = true },
                            onOpenSettings = { showSettings = true },
                            onLoginConnection = { conn -> loginConnection = conn },
                            headlessWebBridge = headlessWebBridge,
                            queuedMessages = queuedPromptsMap[primarySession.id].orEmpty(),
                            onCancelSending = { if (primaryConn != null) cancelSessionTask(primarySession, primaryConn) },
                            onRemoveQueued = { idx ->
                                val cur = queuedPromptsMap[primarySession.id].orEmpty()
                                queuedPromptsMap[primarySession.id] = cur.filterIndexed { i, _ -> i != idx }
                            },
                            onSendQueuedNow = { idx ->
                                if (primaryConn != null) sendQueuedNow(primarySession, primaryConn, idx)
                            },
                            onRenameSession = { newTitle -> renameSession(primarySession, newTitle) },
                            onTogglePinMessage = { msgId -> togglePinMessage(primarySession.id, msgId) },
                            onStopSubagent = { runId -> stopSubagentRun((secondarySession ?: primarySession).id, runId) },
                            onRetrySubagent = { runId -> retrySubagentRun((secondarySession ?: primarySession).id, runId) },
                            onAttachOrchestration = { en, subId, subM, subE, maxP, maxR, sens ->
                                attachOrchestrationToSession(secondarySession ?: primarySession, en, subId, subM, subE, maxP, maxR, sens)
                            },
                            onToggleSkipPermissions = { toggleSkipPermissions(secondarySession ?: primarySession) },
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                        )
                    }
                }
            }
            }
        }
            }
        }
        }
        }

        // Layer 2: Full-screen Login Overlay when user taps "로그인"
        val activeLoginConn = loginConnection
        if (activeLoginConn != null) {
            val loginKey = activeLoginConn.id
            Scaffold(
                modifier = Modifier.navigationBarsPadding().imePadding(),
                topBar = {
                    TopAppBar(
                        title = { Text("${activeLoginConn.name} 로그인", fontWeight = FontWeight.Bold) },
                        actions = {
                            IconButton(onClick = { headlessWebBridge.goBack(loginKey) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                            }
                            IconButton(onClick = { headlessWebBridge.reload(loginKey) }) {
                                Icon(Icons.Default.Refresh, contentDescription = "새로고침")
                            }
                            TextButton(onClick = {
                                headlessWebBridge.flushCookies()
                                val curUrl = headlessWebBridge.currentUrl(loginKey).orEmpty().lowercase()
                                if (!curUrl.contains("accounts.google.com") &&
                                    !curUrl.contains("/login") &&
                                    !curUrl.contains("/signin")
                                ) {
                                    loginStatusMap[activeLoginConn.id] = true
                                    persistConfirmedLogins()
                                }
                                loginConnection = null
                                refreshLoginStatuses()
                            }) {
                                Text("완료", fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                }
            ) { pad ->
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(pad),
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            headlessWebBridge.attachToHiddenHost(this, loginKey, activeLoginConn.endpointUrl)
                        }
                    }
                )
            }
        }
    }

    if (showTokenDashboard) {
        TokenDashboardDialog(
            connections = enabledConnections,
            tokenUsageMap = tokenUsageMap,
            onResetAgent = onResetAgentTokens,
            onResetAll = onResetAllTokens,
            onRefreshQuotas = { refreshAllQuotas(force = true) },
            onDismiss = { showTokenDashboard = false }
        )
    }

    if (showGlobalSearch) {
        GlobalSearchDialog(
            projects = projects,
            activeProjectId = activeProject.id,
            sessions = sessions,
            onSelectHit = { projId, sessId ->
                if (projId != activeProjectId) {
                    activeProjectId = projId
                    store.saveActiveProjectId(projId)
                }
                activeSessionId = sessId
                store.saveActiveSessionId(sessId)
                unreadDoneSessions.remove(sessId)
            },
            onDismiss = { showGlobalSearch = false }
        )
    }

    if (showNewProjectDialog || editingProject != null) {
        ProjectEditorDialog(
            initial = editingProject,
            canDelete = editingProject != null && projects.size > 1,
            onDismiss = {
                showNewProjectDialog = false
                editingProject = null
            },
            onSave = { name, workDir, memoryText ->
                val target = editingProject
                if (target == null) {
                    val newProj = Project(
                        id = "proj_${UUID.randomUUID()}",
                        name = name,
                        workDir = workDir,
                        memoryText = memoryText
                    )
                    projects = projects + newProj
                    store.saveProjects(projects)
                    activeProjectId = newProj.id
                    store.saveActiveProjectId(newProj.id)
                } else {
                    projects = projects.map {
                        if (it.id == target.id) it.copy(name = name, workDir = workDir, memoryText = memoryText) else it
                    }
                    store.saveProjects(projects)
                }
                showNewProjectDialog = false
                editingProject = null
            },
            onDelete = { proj ->
                projects = projects.filterNot { it.id == proj.id }
                sessions = sessions.filterNot { it.projectId == proj.id }
                store.saveProjects(projects)
                store.saveSessions(sessions)
                activeProjectId = projects.first().id
                store.saveActiveProjectId(activeProjectId)
                editingProject = null
            }
        )
    }

    val upd = availableUpdate
    if (upd != null) {
        AlertDialog(
            onDismissRequest = { if (!updateDownloading) availableUpdate = null },
            title = { Text("새 업데이트 (${upd.tagName})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (upd.notes.isNotBlank()) {
                        Text(upd.notes, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (updateDownloading) {
                        LinearProgressIndicator(
                            progress = { updateProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("다운로드 중... ${(updateProgress * 100).toInt()}%", fontSize = 11.sp)
                    }
                    if (!updateStatusMsg.isNullOrBlank()) {
                        Text(updateStatusMsg.orEmpty(), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !updateDownloading,
                    onClick = {
                        updateDownloading = true
                        updateProgress = 0f
                        updateStatusMsg = null
                        scope.launch {
                            val file = AppUpdater.downloadApk(context, upd.apkUrl) { p ->
                                updateProgress = p
                            }
                            updateDownloading = false
                            if (file == null) {
                                updateStatusMsg = "APK 다운로드에 실패했습니다."
                            } else {
                                val started = AppUpdater.installApk(context, file)
                                if (!started) {
                                    updateStatusMsg = "출처를 알 수 없는 앱 설치 권한을 허용한 뒤 다시 '지금 업데이트'를 눌러주세요."
                                } else {
                                    availableUpdate = null
                                }
                            }
                        }
                    }
                ) {
                    Text("지금 업데이트")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !updateDownloading,
                    onClick = { availableUpdate = null }
                ) {
                    Text("나중에")
                }
            }
        )
    }

    if (updateStatusMsg != null && availableUpdate == null) {
        AlertDialog(
            onDismissRequest = { updateStatusMsg = null },
            title = { Text("업데이트 확인") },
            text = { Text(updateStatusMsg.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { updateStatusMsg = null }) { Text("확인") }
            }
        )
    }

    if (showGitDialog && activeProject.workDir.isNotBlank()) {
        GitWorkspaceDialog(
            workDir = primarySession?.worktreePath?.ifBlank { activeProject.workDir } ?: activeProject.workDir,
            onDismiss = { showGitDialog = false },
            onSendDiffFeedback = { diffPrompt ->
                showGitDialog = false
                val sess = primarySession
                val conn = sess?.let { s -> enabledConnections.find { it.id == s.connectionId } }
                if (sess != null && conn != null) {
                    sendMessageInSession(sess, conn, diffPrompt)
                }
            }
        )
    }

    if (showFanOutDialog) {
        FanOutDialog(
            enabledConnections = enabledConnections,
            headlessWebBridge = headlessWebBridge,
            onDismiss = { showFanOutDialog = false },
            onAdopt = { prompt, winner ->
                showFanOutDialog = false
                val newSess = AgentSession(
                    id = "sess_${UUID.randomUUID()}",
                    projectId = activeProject.id,
                    title = "${winner.connection.name} 채택",
                    connectionId = winner.connection.id,
                    messages = listOf(
                        ChatMessage(id = "msg_${UUID.randomUUID()}", role = MessageRole.USER, content = prompt),
                        ChatMessage(
                            id = "msg_${UUID.randomUUID()}",
                            role = MessageRole.ASSISTANT,
                            content = winner.text,
                            inputTokens = winner.inTok,
                            outputTokens = winner.outTok,
                            agentName = winner.connection.name
                        )
                    )
                )
                sessions = sessions + newSess
                store.saveSessions(sessions)
                activeSessionId = newSess.id
                store.saveActiveSessionId(newSess.id)
                if (winner.inTok > 0L || winner.outTok > 0L) {
                    onRecordTokens(winner.connection.id, winner.connection.kind, winner.inTok, winner.outTok)
                }
            }
        )
    }

    if (showNewSessionDialog) {
        NewSessionDialog(
            enabledConnections = enabledConnections,
            hasWorkDir = activeProject.workDir.isNotBlank(),
            defaultIndex = validProjectSessions.size + 1,
            onDismiss = { showNewSessionDialog = false },
            onOpenSettings = {
                showNewSessionDialog = false
                showSettings = true
            },
            onOpenExisting = {
                showNewSessionDialog = false
                showExistingSessionDialog = true
            },
            onCreate = { title, connId, useWorktree ->
                val conn = enabledConnections.find { it.id == connId } ?: return@NewSessionDialog
                showNewSessionDialog = false
                scope.launch {
                    val wtPath = if (useWorktree && activeProject.workDir.isNotBlank()) {
                        GitBridgeClient.createWorktree(activeProject.workDir, title) ?: ""
                    } else ""
                    val newSess = AgentSession(
                        id = "sess_${UUID.randomUUID()}",
                        projectId = activeProject.id,
                        title = title,
                        connectionId = conn.id,
                        worktreePath = wtPath
                    )
                    if (conn.usesHiddenWebBridge()) {
                        headlessWebBridge.getOrCreateWebView(conn.id, conn.endpointUrl)
                    }
                    sessions = sessions + newSess
                    store.saveSessions(sessions)
                    activeSessionId = newSess.id
                    store.saveActiveSessionId(newSess.id)
                }
            },
            onCreateOrchestration = { title, mainConnId, mainModel, mainEffort, subConnId, subModel, subEffort, maxParallel, maxRounds, sensitivity, useWorktree ->
                val mainConn = enabledConnections.find { it.id == mainConnId } ?: return@NewSessionDialog
                val subConn = enabledConnections.find { it.id == subConnId } ?: mainConn
                showNewSessionDialog = false
                scope.launch {
                    val wtPath = if (useWorktree && activeProject.workDir.isNotBlank()) {
                        GitBridgeClient.createWorktree(activeProject.workDir, title) ?: ""
                    } else ""
                    val newSess = AgentSession(
                        id = "sess_${UUID.randomUUID()}",
                        projectId = activeProject.id,
                        title = title,
                        connectionId = mainConn.id,
                        selectedModel = mainModel,
                        selectedEffort = mainEffort,
                        worktreePath = wtPath,
                        isOrchestration = true,
                        subConnectionId = subConn.id,
                        subModel = subModel,
                        subEffort = subEffort,
                        maxParallelSubagents = maxParallel.coerceIn(1, 8),
                        maxDelegationRounds = maxRounds.coerceIn(1, 6),
                        subagentSensitivity = sensitivity.ifBlank { "medium" }
                    )
                    if (mainConn.usesHiddenWebBridge()) {
                        headlessWebBridge.getOrCreateWebView(mainConn.id, mainConn.endpointUrl)
                    }
                    if (subConn.usesHiddenWebBridge() && subConn.id != mainConn.id) {
                        headlessWebBridge.getOrCreateWebView(subConn.id, subConn.endpointUrl)
                    }
                    sessions = sessions + newSess
                    store.saveSessions(sessions)
                    activeSessionId = newSess.id
                    store.saveActiveSessionId(newSess.id)
                }
            }
        )
    }

    val delTarget = sessionPendingDelete
    if (delTarget != null) {
        AlertDialog(
            onDismissRequest = { sessionPendingDelete = null },
            title = { Text("세션 삭제") },
            text = { Text("'${delTarget.title}' 세션을 삭제하시겠습니까?\n대화 기록이 함께 삭제됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteSession(delTarget)
                    sessionPendingDelete = null
                }) {
                    Text("삭제", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionPendingDelete = null }) {
                    Text("취소")
                }
            }
        )
    }

    if (showExistingSessionDialog) {
        ExistingSessionPickerDialog(
            enabledConnections = enabledConnections,
            headlessWebBridge = headlessWebBridge,
            onDismiss = { showExistingSessionDialog = false },
            onPick = { conn, item ->
                showExistingSessionDialog = false
                val sessId = "sess_${UUID.randomUUID()}"
                val loadingMsg = ChatMessage(
                    id = "msg_${UUID.randomUUID()}",
                    role = MessageRole.SYSTEM,
                    content = "이전 대화 기록 불러오는 중..."
                )
                val newSess = AgentSession(
                    id = sessId,
                    projectId = activeProject.id,
                    title = item.title.take(30),
                    connectionId = conn.id,
                    webConversationUrl = item.url,
                    cliSessionId = if (item.url.isBlank()) item.id else "",
                    messages = listOf(loadingMsg)
                )
                sessions = sessions + newSess
                store.saveSessions(sessions)
                activeSessionId = newSess.id
                store.saveActiveSessionId(newSess.id)

                val applyMessages: (List<ChatMessage>) -> Unit = { fetched ->
                    val finalMsgs = if (fetched.isNotEmpty()) {
                        fetched
                    } else {
                        listOf(
                            ChatMessage(
                                id = "msg_${UUID.randomUUID()}",
                                role = MessageRole.SYSTEM,
                                content = "연결됨 — 새 메시지를 보내면 기존 대화에 이어집니다."
                            )
                        )
                    }
                    sessions = sessions.map { if (it.id == sessId) it.copy(messages = finalMsgs) else it }
                    store.saveSessions(sessions)
                }

                if (conn.usesHiddenWebBridge() && item.url.isNotBlank()) {
                    headlessWebBridge.fetchWebSessionMessages(conn.id, conn.kind, item.url, conn.name, onResult = applyMessages)
                } else {
                    val bridgeKind = if (conn.endpointUrl.lowercase().contains("/agy")) "agy" else "claude"
                    scope.launch {
                        applyMessages(GitBridgeClient.fetchSessionMessages(bridgeKind, item.id, conn.name))
                    }
                }
            }
        )
    }
}

@Composable
private fun ProjectEditorDialog(
    initial: Project?,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, workDir: String, memoryText: String) -> Unit,
    onDelete: (Project) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var workDir by remember { mutableStateOf(initial?.workDir ?: "") }
    var memoryText by remember { mutableStateOf(initial?.memoryText ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "새 프로젝트" else "프로젝트 편집") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("프로젝트 이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = workDir,
                    onValueChange = { workDir = it },
                    label = { Text("작업 디렉토리 (예: ~/multi-agent)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { workDir = "~/multi-agent" }) { Text("~/multi-agent", fontSize = 11.sp) }
                    TextButton(onClick = { workDir = "~" }) { Text("~ (홈)", fontSize = 11.sp) }
                }
                OutlinedTextField(
                    value = memoryText,
                    onValueChange = { memoryText = it },
                    label = { Text("프로젝트 공용 메모리 / 지침 (선택)") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(name.trim().ifEmpty { "새 프로젝트" }, workDir.trim(), memoryText.trim())
            }) {
                Text("저장")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (canDelete && initial != null) {
                    TextButton(onClick = { onDelete(initial) }) {
                        Text("삭제", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("취소")
                }
            }
        }
    )
}

@Composable
private fun ExistingSessionPickerDialog(
    enabledConnections: List<AiConnection>,
    headlessWebBridge: HeadlessWebBridge,
    onDismiss: () -> Unit,
    onPick: (AiConnection, com.mbta.app.webview.ExternalSessionItem) -> Unit
) {
    val selectable = enabledConnections.filter {
        it.usesHiddenWebBridge() || it.kind == ConnectionKind.REMOTE_CONTROL
    }
    var selectedConn by remember { mutableStateOf(selectable.firstOrNull()) }
    var loading by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<com.mbta.app.webview.ExternalSessionItem>>(emptyList()) }
    var reloadNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(selectedConn?.id, reloadNonce) {
        val conn = selectedConn ?: return@LaunchedEffect
        loading = true
        items = emptyList()
        if (conn.usesHiddenWebBridge()) {
            headlessWebBridge.fetchWebSessions(conn.id, conn.kind, conn.endpointUrl) { list ->
                items = list
                loading = false
            }
        } else {
            val bridgeKind = if (conn.endpointUrl.lowercase().contains("/agy")) "agy" else "claude"
            items = GitBridgeClient.fetchBridgeSessions(bridgeKind)
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("기존 세션 불러오기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectable.isEmpty()) {
                    Text("불러올 수 있는 웹/로컬 브릿지 연결이 없습니다.", fontSize = 13.sp)
                } else {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        selectable.forEach { c ->
                            FilterChip(
                                selected = c.id == selectedConn?.id,
                                onClick = { selectedConn = c },
                                label = { Text(c.name, fontSize = 11.sp) }
                            )
                        }
                    }
                    if (loading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                if (selectedConn?.usesHiddenWebBridge() == true) {
                                    "사이드바를 자동으로 열어 세션 목록을 불러오는 중..."
                                } else {
                                    "세션 목록 불러오는 중..."
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (items.isEmpty()) {
                        Text(
                            "불러올 기존 세션이 없습니다. (웹 연결은 먼저 로그인되어 있어야 합니다)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(items, key = { it.id }) { item ->
                                val timeText = remember(item.updatedAt, item.updatedLabel) {
                                    formatExternalSessionTime(item.updatedAt, item.updatedLabel)
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedConn?.let { onPick(it, item) } }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(
                                            text = item.title,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (timeText.isNotBlank()) {
                                            Text(
                                                text = "수정: $timeText",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (selectable.isNotEmpty() && !loading) {
                TextButton(onClick = { reloadNonce++ }) { Text("새로고침") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}

private fun formatExternalSessionTime(updatedAt: Long, updatedLabel: String): String {
    if (updatedAt > 0L) {
        val now = System.currentTimeMillis()
        val diffMs = now - updatedAt
        if (diffMs in 0..59_999L) return "방금 전"
        if (diffMs in 60_000L..3_599_999L) return "${diffMs / 60_000L}분 전"

        val calNow = java.util.Calendar.getInstance()
        val calThen = java.util.Calendar.getInstance().apply { timeInMillis = updatedAt }
        val sameYear = calNow.get(java.util.Calendar.YEAR) == calThen.get(java.util.Calendar.YEAR)
        val sameDay = sameYear && calNow.get(java.util.Calendar.DAY_OF_YEAR) == calThen.get(java.util.Calendar.DAY_OF_YEAR)
        val yesterday = sameYear && calNow.get(java.util.Calendar.DAY_OF_YEAR) - calThen.get(java.util.Calendar.DAY_OF_YEAR) == 1
        val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(updatedAt))
        return when {
            sameDay -> "오늘 $hm"
            yesterday -> "어제 $hm"
            sameYear -> java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(updatedAt))
            else -> java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(updatedAt))
        }
    }
    return updatedLabel.trim()
}

@Composable
private fun NewSessionDialog(
    enabledConnections: List<AiConnection>,
    hasWorkDir: Boolean,
    defaultIndex: Int,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenExisting: () -> Unit = {},
    onCreate: (title: String, connectionId: String, useWorktree: Boolean) -> Unit,
    onCreateOrchestration: (
        title: String,
        mainConnId: String,
        mainModel: String,
        mainEffort: String,
        subConnId: String,
        subModel: String,
        subEffort: String,
        maxParallel: Int,
        maxRounds: Int,
        sensitivity: String,
        useWorktree: Boolean
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _ -> }
) {
    var isOrchMode by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("세션 $defaultIndex") }
    var selectedConnId by remember { mutableStateOf(enabledConnections.firstOrNull()?.id) }
    var subConnId by remember { mutableStateOf(enabledConnections.firstOrNull()?.id) }

    val mainConn = enabledConnections.find { it.id == selectedConnId }
    val subConn = enabledConnections.find { it.id == subConnId } ?: mainConn
    val mainModels = remember(mainConn?.id) { mainConn?.let { availableModelsFor(it) } ?: emptyList() }
    val subModels = remember(subConn?.id) { subConn?.let { availableModelsFor(it) } ?: emptyList() }

    var mainModel by remember(mainConn?.id) {
        mutableStateOf(mainConn?.let { it.model.ifBlank { defaultModelFor(it) } } ?: "")
    }
    var subModel by remember(subConn?.id) {
        mutableStateOf(
            subModels.lastOrNull { m ->
                val l = m.lowercase()
                l.contains("mini") || l.contains("haiku") || l.contains("flash") || l.contains("fast") || l.contains("lite")
            } ?: subConn?.let { it.model.ifBlank { defaultModelFor(it) } } ?: ""
        )
    }
    val mainEfforts = remember(mainConn?.id, mainModel) {
        if (mainConn != null) availableEffortsFor(mainConn, mainModel) else emptyList()
    }
    val subEfforts = remember(subConn?.id, subModel) {
        if (subConn != null) availableEffortsFor(subConn, subModel) else emptyList()
    }
    var mainEffort by remember(mainConn?.id, mainModel) { mutableStateOf("") }
    var subEffort by remember(subConn?.id, subModel) { mutableStateOf("") }

    var maxParallel by remember { mutableIntStateOf(3) }
    var maxRounds by remember { mutableIntStateOf(3) }
    var sensitivity by remember { mutableStateOf("medium") }
    var useWorktree by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isOrchMode) "⚡ 새 오케스트레이션 세션" else "새 하위 세션", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (enabledConnections.isEmpty()) {
                    Text(
                        "등록된 AI / CLI 연결이 없습니다. 먼저 설정에서 연결을 추가해주세요.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = !isOrchMode,
                            onClick = {
                                if (isOrchMode) {
                                    isOrchMode = false
                                    if (title == "오케스트레이션 $defaultIndex") title = "세션 $defaultIndex"
                                }
                            },
                            label = { Text("일반 세션", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = isOrchMode,
                            onClick = {
                                if (!isOrchMode) {
                                    isOrchMode = true
                                    if (title == "세션 $defaultIndex") title = "오케스트레이션 $defaultIndex"
                                }
                            },
                            label = { Text("⚡ 오케스트레이션", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("세션 이름") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (!isOrchMode) {
                        Text("사용할 AI 선택 (등록된 연결만 표시)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        enabledConnections.forEach { conn ->
                            val selected = conn.id == selectedConnId
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedConnId = conn.id }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(conn.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                    Text(conn.kind.displayLabel(), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = onOpenExisting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("기존 외부 세션 불러오기", fontSize = 12.sp)
                        }
                    } else {
                        Text(
                            "메인 AI와만 대화하면 필요할 때 서브 AI를 자동으로 병렬 생성해 작업합니다.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Main AI selection
                        Text("메인 AI (지휘자 · 고성능 권장)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            enabledConnections.forEach { conn ->
                                FilterChip(
                                    selected = conn.id == selectedConnId,
                                    onClick = { selectedConnId = conn.id },
                                    label = { Text(conn.name, fontSize = 11.sp) }
                                )
                            }
                        }
                        if (mainModels.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                mainModels.forEach { m ->
                                    FilterChip(
                                        selected = m == mainModel,
                                        onClick = { mainModel = m },
                                        label = { Text(m, fontSize = 10.sp) }
                                    )
                                }
                            }
                        }
                        if (mainEfforts.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Effort:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                mainEfforts.forEach { ef ->
                                    FilterChip(
                                        selected = ef == mainEffort,
                                        onClick = { mainEffort = if (mainEffort == ef) "" else ef },
                                        label = { Text(ef, fontSize = 10.sp) }
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                        // Sub AI selection
                        Text("서브 AI (작업자 · 같은 AI의 저비용 모델 가능)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            enabledConnections.forEach { conn ->
                                FilterChip(
                                    selected = conn.id == subConnId,
                                    onClick = { subConnId = conn.id },
                                    label = { Text(conn.name, fontSize = 11.sp) }
                                )
                            }
                        }
                        if (subModels.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                subModels.forEach { m ->
                                    FilterChip(
                                        selected = m == subModel,
                                        onClick = { subModel = m },
                                        label = { Text(m, fontSize = 10.sp) }
                                    )
                                }
                            }
                        }
                        if (subEfforts.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Effort:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                subEfforts.forEach { ef ->
                                    FilterChip(
                                        selected = ef == subEffort,
                                        onClick = { subEffort = if (subEffort == ef) "" else ef },
                                        label = { Text(ef, fontSize = 10.sp) }
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                        // #3 User-configurable guardrail limits
                        Text("안전 제한 설정", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("최대 동시 서브에이전트", fontSize = 12.sp)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { if (maxParallel > 1) maxParallel-- },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.size(28.dp)
                                ) { Text("-", fontSize = 14.sp) }
                                Text("${maxParallel}개", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                OutlinedButton(
                                    onClick = { if (maxParallel < 8) maxParallel++ },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.size(28.dp)
                                ) { Text("+", fontSize = 14.sp) }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("최대 위임 라운드", fontSize = 12.sp)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { if (maxRounds > 1) maxRounds-- },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.size(28.dp)
                                ) { Text("-", fontSize = 14.sp) }
                                Text("${maxRounds}회", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                OutlinedButton(
                                    onClick = { if (maxRounds < 6) maxRounds++ },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.size(28.dp)
                                ) { Text("+", fontSize = 14.sp) }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("호출 민감도", fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf("low" to "낮음", "medium" to "보통", "high" to "높음").forEach { (key, lbl) ->
                                    FilterChip(
                                        selected = sensitivity == key,
                                        onClick = { sensitivity = key },
                                        label = { Text(lbl, fontSize = 10.5.sp) }
                                    )
                                }
                            }
                        }
                    }

                    if (hasWorkDir) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("독립 Git Worktree 격리 (가능 시)", fontSize = 12.sp)
                            androidx.compose.material3.Switch(
                                checked = useWorktree,
                                onCheckedChange = { useWorktree = it }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (enabledConnections.isEmpty()) {
                Button(onClick = onOpenSettings) {
                    Text("설정으로 이동")
                }
            } else {
                Button(
                    enabled = selectedConnId != null,
                    onClick = {
                        selectedConnId?.let { id ->
                            if (isOrchMode) {
                                onCreateOrchestration(
                                    title.trim().ifEmpty { "오케스트레이션 $defaultIndex" },
                                    id,
                                    mainModel,
                                    mainEffort,
                                    subConnId ?: id,
                                    subModel,
                                    subEffort,
                                    maxParallel,
                                    maxRounds,
                                    sensitivity,
                                    useWorktree
                                )
                            } else {
                                onCreate(title.trim().ifEmpty { "세션 $defaultIndex" }, id, useWorktree)
                            }
                        }
                    }
                ) {
                    Text(if (isOrchMode) "오케스트레이션 시작" else "생성")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

private fun shareSessionAsMarkdown(context: Context, project: Project, session: AgentSession) {
    val md = buildString {
        append("# ${project.name} - ${session.title}\n\n")
        session.messages.forEach { msg ->
            when (msg.role) {
                MessageRole.USER -> append("## User\n${msg.content}\n\n")
                MessageRole.ASSISTANT -> append("## ${msg.agentName.ifBlank { "Assistant" }}\n${msg.content}\n\n")
                MessageRole.SYSTEM -> append("> ${msg.content}\n\n")
            }
        }
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_SUBJECT, "${project.name} - ${session.title}")
        putExtra(Intent.EXTRA_TEXT, md)
    }
    context.startActivity(Intent.createChooser(intent, "Markdown으로 공유"))
}
