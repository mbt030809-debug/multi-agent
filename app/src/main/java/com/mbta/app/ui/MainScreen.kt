package com.mbta.app.ui

import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mbta.app.data.WorkspaceStore
import com.mbta.app.model.AgentSession
import com.mbta.app.model.AgentTokenUsage
import com.mbta.app.model.AiConnection
import com.mbta.app.model.AppThemeMode
import com.mbta.app.model.ChatMessage
import com.mbta.app.model.ConnectionKind
import com.mbta.app.model.MessageRole
import com.mbta.app.model.Project
import com.mbta.app.model.SplitMode
import com.mbta.app.model.TokenSource
import com.mbta.app.model.defaultContextLimitForKind
import com.mbta.app.model.displayLabel
import com.mbta.app.model.formatTokens
import com.mbta.app.model.isHiddenWebBridge
import com.mbta.app.network.AiApiClient
import com.mbta.app.network.GitBridgeClient
import com.mbta.app.util.AppUpdater
import com.mbta.app.util.UpdateInfo
import com.mbta.app.webview.HeadlessWebBridge
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    store: WorkspaceStore,
    headlessWebBridge: HeadlessWebBridge,
    themeMode: AppThemeMode,
    tokenUsageMap: Map<String, AgentTokenUsage>,
    onThemeModeChanged: (AppThemeMode) -> Unit,
    onRecordTokens: (connectionId: String, kind: ConnectionKind, inTok: Long, outTok: Long) -> Unit,
    onResetAgentTokens: (String) -> Unit,
    onResetAllTokens: () -> Unit,
    onNotifyIfBackground: (String, String) -> Unit = { _, _ -> }
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
    var showOrchestrationDialog by remember { mutableStateOf(false) }
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

    val sendingMap = remember { mutableStateMapOf<String, Boolean>() }
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
        connections.filter { it.kind.isHiddenWebBridge() }.forEach { conn ->
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

    fun sendMessageInSession(session: AgentSession, conn: AiConnection, text: String) {
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
        if (targetConn.kind.isHiddenWebBridge()) {
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
                    sessions = sessions.map { s ->
                        if (s.id == session.id) {
                            s.copy(messages = s.messages.map { m ->
                                if (m.id == placeholderId) {
                                    m.copy(content = finalText, inputTokens = inTok, outputTokens = outTok)
                                } else m
                            })
                        } else s
                    }
                    store.saveSessions(sessions)
                    onRecordTokens(targetConn.id, targetConn.kind, inTok, outTok)
                    sendingMap[session.id] = false
                    if (activeSessionId != session.id) unreadDoneSessions[session.id] = true
                    onNotifyIfBackground("${targetConn.name} 응답 완료", finalText.take(120))
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
                }
            )
            return
        }

        sessions = sessions.map {
            if (it.id == session.id) it.copy(messages = it.messages + userMsg) else it
        }
        store.saveSessions(sessions)
        sendingMap[session.id] = true

        scope.launch {
            val reply = AiApiClient.send(effectiveConn, historyBefore, effectivePrompt, session.id, cwd = effectiveDir)
            val assistantMsg = ChatMessage(
                id = "msg_${UUID.randomUUID()}",
                role = MessageRole.ASSISTANT,
                content = reply.error ?: reply.text.ifBlank { "(응답 없음)" },
                inputTokens = reply.inputTokens,
                outputTokens = reply.outputTokens,
                agentName = displayAgentName,
                isError = reply.error != null
            )
            sessions = sessions.map {
                if (it.id == session.id) it.copy(messages = it.messages + assistantMsg) else it
            }
            store.saveSessions(sessions)
            if (reply.inputTokens > 0L || reply.outputTokens > 0L) {
                onRecordTokens(targetConn.id, targetConn.kind, reply.inputTokens, reply.outputTokens)
            }
            sendingMap[session.id] = false
            if (activeSessionId != session.id) unreadDoneSessions[session.id] = true
            if (reply.error == null) {
                onNotifyIfBackground("${targetConn.name} 응답 완료", assistantMsg.content.take(120))
            }
        }
    }

    fun handoffSession(fromSession: AgentSession, targetConn: AiConnection) {
        val transcript = fromSession.messages
            .filter { it.role != MessageRole.SYSTEM }
            .joinToString("\n") { msg ->
                val who = if (msg.role == MessageRole.USER) "User" else (msg.agentName.ifBlank { "Assistant" })
                "[$who]: ${msg.content}"
            }
        val sysMsg = ChatMessage(
            id = "msg_${UUID.randomUUID()}",
            role = MessageRole.SYSTEM,
            content = "이전 세션('${fromSession.title}')에서 전달된 컨텍스트:\n\n$transcript"
        )
        val newSess = AgentSession(
            id = "sess_${UUID.randomUUID()}",
            projectId = activeProject.id,
            title = "${targetConn.name} 이어받기",
            connectionId = targetConn.id,
            messages = listOf(sysMsg)
        )
        sessions = sessions + newSess
        store.saveSessions(sessions)
        activeSessionId = newSess.id
        store.saveActiveSessionId(newSess.id)
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
        sessions = sessions.map { if (it.id == session.id) it.copy(messages = emptyList()) else it }
        store.saveSessions(sessions)
    }

    fun selectModelForSession(session: AgentSession, conn: AiConnection, model: String) {
        sessions = sessions.map { if (it.id == session.id) it.copy(selectedModel = model) else it }
        store.saveSessions(sessions)
        if (conn.kind.isHiddenWebBridge()) {
            headlessWebBridge.switchWebModel(conn.id, model)
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

    fun runOrchestration(goal: String, steps: List<OrchestrationStep>) {
        val firstConnId = steps.firstOrNull()?.connectionId ?: return
        val orchSess = AgentSession(
            id = "sess_${UUID.randomUUID()}",
            projectId = activeProject.id,
            title = "오케스트레이션",
            connectionId = firstConnId,
            messages = listOf(
                ChatMessage(
                    id = "msg_${UUID.randomUUID()}",
                    role = MessageRole.USER,
                    content = goal
                )
            )
        )
        sessions = sessions + orchSess
        store.saveSessions(sessions)
        activeSessionId = orchSess.id
        store.saveActiveSessionId(orchSess.id)
        sendingMap[orchSess.id] = true

        scope.launch {
            var prevOutput = ""
            for ((idx, step) in steps.withIndex()) {
                val conn = enabledConnections.find { it.id == step.connectionId } ?: continue
                val stepPrompt = buildString {
                    append("목표: ").append(goal).append("\n")
                    if (step.roleInstruction.isNotBlank()) {
                        append("역할: ").append(step.roleInstruction).append("\n")
                    }
                    if (prevOutput.isNotBlank()) {
                        append("\n이전 단계 결과:\n").append(prevOutput)
                    }
                }

                if (conn.kind.isHiddenWebBridge()) {
                    val (triple, errMsg) = headlessWebBridge.sendPromptSuspend(
                        key = conn.id,
                        kind = conn.kind,
                        url = conn.endpointUrl,
                        prompt = stepPrompt
                    )
                    val out = triple.first
                    if (errMsg != null) {
                        val errBubble = ChatMessage(
                            id = "msg_${UUID.randomUUID()}",
                            role = MessageRole.ASSISTANT,
                            content = errMsg,
                            agentName = "${idx + 1}단계 · ${conn.name}",
                            isError = true
                        )
                        sessions = sessions.map {
                            if (it.id == orchSess.id) it.copy(messages = it.messages + errBubble) else it
                        }
                        store.saveSessions(sessions)
                        break
                    }
                    prevOutput = out
                    onRecordTokens(conn.id, conn.kind, triple.second, triple.third)
                    val msg = ChatMessage(
                        id = "msg_${UUID.randomUUID()}",
                        role = MessageRole.ASSISTANT,
                        content = out,
                        inputTokens = triple.second,
                        outputTokens = triple.third,
                        agentName = "${idx + 1}단계 · ${conn.name} (${step.roleInstruction})"
                    )
                    sessions = sessions.map {
                        if (it.id == orchSess.id) it.copy(messages = it.messages + msg) else it
                    }
                    store.saveSessions(sessions)
                    continue
                } else {
                    val orchDir = orchSess.worktreePath.ifBlank { activeProject.workDir }
                    val reply = AiApiClient.send(conn, emptyList(), stepPrompt, orchSess.id, cwd = orchDir)
                    if (reply.error != null) {
                        val errBubble = ChatMessage(
                            id = "msg_${UUID.randomUUID()}",
                            role = MessageRole.ASSISTANT,
                            content = reply.error,
                            agentName = "${idx + 1}단계 · ${conn.name}",
                            isError = true
                        )
                        sessions = sessions.map {
                            if (it.id == orchSess.id) it.copy(messages = it.messages + errBubble) else it
                        }
                        store.saveSessions(sessions)
                        break
                    }
                    prevOutput = reply.text
                    if (reply.inputTokens > 0L || reply.outputTokens > 0L) {
                        onRecordTokens(conn.id, conn.kind, reply.inputTokens, reply.outputTokens)
                    }
                    val msg = ChatMessage(
                        id = "msg_${UUID.randomUUID()}",
                        role = MessageRole.ASSISTANT,
                        content = reply.text,
                        inputTokens = reply.inputTokens,
                        outputTokens = reply.outputTokens,
                        agentName = "${idx + 1}단계 · ${conn.name} (${step.roleInstruction})"
                    )
                    sessions = sessions.map {
                        if (it.id == orchSess.id) it.copy(messages = it.messages + msg) else it
                    }
                    store.saveSessions(sessions)
                }
            }
            sendingMap[orchSess.id] = false
            onNotifyIfBackground("오케스트레이션 완료", prevOutput.take(120).ifBlank { "파이프라인이 종료되었습니다." })
        }
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
        if (conn != null && conn.kind.isHiddenWebBridge()) {
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
                    connections.filter { it.enabled && it.kind.isHiddenWebBridge() }.forEach { conn ->
                        headlessWebBridge.attachToHiddenHost(host, conn.id, conn.endpointUrl)
                    }
                }
            }
        )

        // Layer 1: Opaque MbtA Native UI
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding(),
            color = MaterialTheme.colorScheme.background
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
                TopAppBar(
                    title = {
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable { showProjectMenu = true }
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = activeProject.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "프로젝트 선택")
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
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    actions = {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .clickable { showTokenDashboard = true }
                        ) {
                            Text(
                                text = "Tokens ${formatTokens(totalTokensAll)}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        if (activeProject.workDir.isNotBlank()) {
                            IconButton(onClick = { showGitDialog = true }) {
                                Icon(Icons.Default.Code, contentDescription = "Git · Diff · CI")
                            }
                        }
                        if (enabledConnections.size >= 2) {
                            IconButton(onClick = { showFanOutDialog = true }) {
                                Icon(Icons.Default.CompareArrows, contentDescription = "동시 비교")
                            }
                        }
                        if (enabledConnections.isNotEmpty()) {
                            IconButton(onClick = { showOrchestrationDialog = true }) {
                                Icon(Icons.Default.AccountTree, contentDescription = "오케스트레이션")
                            }
                        }
                        IconButton(onClick = { cycleSplitMode() }) {
                            val icon = when (splitMode) {
                                SplitMode.SINGLE -> Icons.Default.ViewAgenda
                                SplitMode.SPLIT_VERTICAL -> Icons.Default.ViewStream
                                SplitMode.SPLIT_HORIZONTAL -> Icons.Default.ViewColumn
                            }
                            Icon(icon, contentDescription = "화면 분할")
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "설정")
                        }
                    }
                )

                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(validProjectSessions, key = { it.id }) { sess ->
                        val connName = enabledConnections.find { it.id == sess.connectionId }?.name
                        val isSelected = if (splitMode == SplitMode.SINGLE) {
                            sess.id == primarySession?.id
                        } else {
                            sess.id == primarySession?.id || sess.id == secondarySession?.id
                        }
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                unreadDoneSessions.remove(sess.id)
                                if (splitMode == SplitMode.SINGLE || focusedPane == 0) {
                                    activeSessionId = sess.id
                                    store.saveActiveSessionId(sess.id)
                                } else {
                                    secondarySessionId = sess.id
                                    store.saveSecondarySessionId(sess.id)
                                }
                            },
                            label = {
                                val statusDot = when {
                                    sendingMap[sess.id] == true -> "🟡 "
                                    sess.messages.lastOrNull()?.isError == true -> "🔴 "
                                    unreadDoneSessions[sess.id] == true -> "🟢 "
                                    else -> ""
                                }
                                val label = if (connName != null) "$statusDot${sess.title} · $connName" else "$statusDot${sess.title}"
                                Text(label, maxLines = 1)
                            }
                        )
                    }
                    item {
                        IconButton(
                            onClick = { showNewSessionDialog = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "새 하위 세션")
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
                        onOpenOrchestration = { showOrchestrationDialog = true },
                        onShareMarkdown = { shareSessionAsMarkdown(context, activeProject, primarySession) },
                        onSelectModel = { model ->
                            if (primaryConn != null) selectModelForSession(primarySession, primaryConn, model)
                        },
                        onHandoffTo = { target -> handoffSession(primarySession, target) },
                        onDeleteSession = { deleteSession(primarySession) },
                        onOpenTokenDashboard = { showTokenDashboard = true },
                        onOpenSettings = { showSettings = true },
                        onLoginConnection = { conn -> loginConnection = conn },
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
                            onOpenOrchestration = { showOrchestrationDialog = true },
                            onShareMarkdown = { shareSessionAsMarkdown(context, activeProject, primarySession) },
                            onSelectModel = { model ->
                                if (primaryConn != null) selectModelForSession(primarySession, primaryConn, model)
                            },
                            onHandoffTo = { target -> handoffSession(primarySession, target) },
                            onDeleteSession = { deleteSession(primarySession) },
                            onOpenTokenDashboard = { showTokenDashboard = true },
                            onOpenSettings = { showSettings = true },
                            onLoginConnection = { conn -> loginConnection = conn },
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
                            onOpenOrchestration = { showOrchestrationDialog = true },
                            onShareMarkdown = { shareSessionAsMarkdown(context, activeProject, secondarySession ?: primarySession) },
                            onSelectModel = { model ->
                                val targetSess = secondarySession ?: primarySession
                                if (secondaryConn != null) selectModelForSession(targetSess, secondaryConn, model)
                            },
                            onHandoffTo = { target -> handoffSession(secondarySession ?: primarySession, target) },
                            onDeleteSession = { deleteSession(secondarySession ?: primarySession) },
                            onOpenTokenDashboard = { showTokenDashboard = true },
                            onOpenSettings = { showSettings = true },
                            onLoginConnection = { conn -> loginConnection = conn },
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
                            onOpenOrchestration = { showOrchestrationDialog = true },
                            onShareMarkdown = { shareSessionAsMarkdown(context, activeProject, primarySession) },
                            onSelectModel = { model ->
                                if (primaryConn != null) selectModelForSession(primarySession, primaryConn, model)
                            },
                            onHandoffTo = { target -> handoffSession(primarySession, target) },
                            onDeleteSession = { deleteSession(primarySession) },
                            onOpenTokenDashboard = { showTokenDashboard = true },
                            onOpenSettings = { showSettings = true },
                            onLoginConnection = { conn -> loginConnection = conn },
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
                            onOpenOrchestration = { showOrchestrationDialog = true },
                            onShareMarkdown = { shareSessionAsMarkdown(context, activeProject, secondarySession ?: primarySession) },
                            onSelectModel = { model ->
                                val targetSess = secondarySession ?: primarySession
                                if (secondaryConn != null) selectModelForSession(targetSess, secondaryConn, model)
                            },
                            onHandoffTo = { target -> handoffSession(secondarySession ?: primarySession, target) },
                            onDeleteSession = { deleteSession(secondarySession ?: primarySession) },
                            onOpenTokenDashboard = { showTokenDashboard = true },
                            onOpenSettings = { showSettings = true },
                            onLoginConnection = { conn -> loginConnection = conn },
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
            onDismiss = { showTokenDashboard = false }
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

    if (showOrchestrationDialog) {
        OrchestrationDialog(
            enabledConnections = enabledConnections,
            onDismiss = { showOrchestrationDialog = false },
            onRun = { goal, steps ->
                showOrchestrationDialog = false
                runOrchestration(goal, steps)
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
                    if (conn.kind.isHiddenWebBridge()) {
                        headlessWebBridge.getOrCreateWebView(conn.id, conn.endpointUrl)
                    }
                    sessions = sessions + newSess
                    store.saveSessions(sessions)
                    activeSessionId = newSess.id
                    store.saveActiveSessionId(newSess.id)
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
private fun NewSessionDialog(
    enabledConnections: List<AiConnection>,
    hasWorkDir: Boolean,
    defaultIndex: Int,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onCreate: (title: String, connectionId: String, useWorktree: Boolean) -> Unit
) {
    var title by remember { mutableStateOf("세션 $defaultIndex") }
    var selectedConnId by remember { mutableStateOf(enabledConnections.firstOrNull()?.id) }
    var useWorktree by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 하위 세션") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (enabledConnections.isEmpty()) {
                    Text(
                        "등록된 AI / CLI 연결이 없습니다. 먼저 설정에서 연결을 추가해주세요.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("세션 이름") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
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
                            onCreate(title.trim().ifEmpty { "세션 $defaultIndex" }, id, useWorktree)
                        }
                    }
                ) {
                    Text("생성")
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
