package com.mbta.app.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.mbta.app.network.AiApiClient
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    store: WorkspaceStore,
    themeMode: AppThemeMode,
    tokenUsageMap: Map<String, AgentTokenUsage>,
    onThemeModeChanged: (AppThemeMode) -> Unit,
    onRecordTokens: (connectionId: String, kind: ConnectionKind, inTok: Long, outTok: Long) -> Unit,
    onResetAgentTokens: (String) -> Unit,
    onResetAllTokens: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var connections by remember { mutableStateOf(store.loadConnections()) }
    var projects by remember { mutableStateOf(store.loadProjects()) }
    var sessions by remember { mutableStateOf(store.loadSessions()) }
    var activeProjectId by remember { mutableStateOf(store.loadActiveProjectId()) }
    var activeSessionId by remember { mutableStateOf(store.loadActiveSessionId()) }
    var secondarySessionId by remember { mutableStateOf(store.loadSecondarySessionId()) }
    var splitMode by remember { mutableStateOf(store.loadSplitMode()) }
    var focusedPane by remember { mutableIntStateOf(0) }

    var showSettings by remember { mutableStateOf(false) }
    var showTokenDashboard by remember { mutableStateOf(false) }
    var showProjectMenu by remember { mutableStateOf(false) }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var editingProject by remember { mutableStateOf<Project?>(null) }
    var showNewSessionDialog by remember { mutableStateOf(false) }

    val sendingMap = remember { mutableStateMapOf<String, Boolean>() }

    val enabledConnections = connections.filter { it.enabled }
    val activeProject = projects.find { it.id == activeProjectId } ?: projects.first()
    val projectSessions = sessions.filter { it.projectId == activeProject.id }
    val primarySession = projectSessions.find { it.id == activeSessionId }
        ?: projectSessions.firstOrNull()
        ?: run {
            val created = AgentSession(
                id = "sess_${UUID.randomUUID()}",
                projectId = activeProject.id,
                title = "세션 1",
                connectionId = enabledConnections.firstOrNull()?.id
            )
            sessions = sessions + created
            store.saveSessions(sessions)
            activeSessionId = created.id
            store.saveActiveSessionId(created.id)
            created
        }

    val secondarySession = projectSessions.find { it.id == secondarySessionId }
        ?: projectSessions.firstOrNull { it.id != primarySession.id }
        ?: primarySession

    val totalTokensAll = tokenUsageMap.values.sumOf { it.totalTokens }

    if (showSettings) {
        SettingsScreen(
            themeMode = themeMode,
            connections = connections,
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
                store.saveConnections(connections)
            },
            onBack = { showSettings = false }
        )
        return
    }

    fun sendMessageInSession(session: AgentSession, conn: AiConnection, text: String) {
        val userMsg = ChatMessage(
            id = "msg_${UUID.randomUUID()}",
            role = MessageRole.USER,
            content = text
        )
        val historyBefore = session.messages
        sessions = sessions.map {
            if (it.id == session.id) it.copy(messages = it.messages + userMsg) else it
        }
        store.saveSessions(sessions)
        sendingMap[session.id] = true

        scope.launch {
            val reply = AiApiClient.send(conn, historyBefore, text)
            val assistantMsg = ChatMessage(
                id = "msg_${UUID.randomUUID()}",
                role = MessageRole.ASSISTANT,
                content = reply.error ?: reply.text.ifBlank { "(응답 없음)" },
                inputTokens = reply.inputTokens,
                outputTokens = reply.outputTokens,
                agentName = conn.name
            )
            sessions = sessions.map {
                if (it.id == session.id) it.copy(messages = it.messages + assistantMsg) else it
            }
            store.saveSessions(sessions)
            if (reply.inputTokens > 0L || reply.outputTokens > 0L) {
                onRecordTokens(conn.id, conn.kind, reply.inputTokens, reply.outputTokens)
            }
            sendingMap[session.id] = false
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
        val remainingInProject = projectSessions.filterNot { it.id == session.id }
        sessions = sessions.filterNot { it.id == session.id }
        if (remainingInProject.isEmpty()) {
            val replacement = AgentSession(
                id = "sess_${UUID.randomUUID()}",
                projectId = activeProject.id,
                title = "세션 1",
                connectionId = enabledConnections.firstOrNull()?.id
            )
            sessions = sessions + replacement
            activeSessionId = replacement.id
            store.saveActiveSessionId(replacement.id)
        } else if (activeSessionId == session.id) {
            activeSessionId = remainingInProject.first().id
            store.saveActiveSessionId(activeSessionId)
        }
        store.saveSessions(sessions)
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
                    items(projectSessions, key = { it.id }) { sess ->
                        val connName = enabledConnections.find { it.id == sess.connectionId }?.name
                        val isSelected = if (splitMode == SplitMode.SINGLE) {
                            sess.id == primarySession.id
                        } else {
                            sess.id == primarySession.id || sess.id == secondarySession.id
                        }
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (splitMode == SplitMode.SINGLE || focusedPane == 0) {
                                    activeSessionId = sess.id
                                    store.saveActiveSessionId(sess.id)
                                } else {
                                    secondarySessionId = sess.id
                                    store.saveSecondarySessionId(sess.id)
                                }
                            },
                            label = {
                                val label = if (connName != null) "${sess.title} · $connName" else sess.title
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
            val primaryConn = enabledConnections.find { it.id == primarySession.connectionId }
            val secondaryConn = enabledConnections.find { it.id == secondarySession.connectionId }

            when (splitMode) {
                SplitMode.SINGLE -> {
                    SessionPane(
                        session = primarySession,
                        connection = primaryConn,
                        enabledConnections = enabledConnections,
                        tokenUsage = primaryConn?.let { tokenUsageMap[it.id] },
                        isSending = sendingMap[primarySession.id] == true,
                        isFocused = true,
                        showFocusBorder = false,
                        onFocus = { focusedPane = 0 },
                        onSendMessage = { text ->
                            if (primaryConn != null) sendMessageInSession(primarySession, primaryConn, text)
                        },
                        onHandoffTo = { target -> handoffSession(primarySession, target) },
                        onDeleteSession = { deleteSession(primarySession) },
                        onOpenTokenDashboard = { showTokenDashboard = true },
                        onOpenSettings = { showSettings = true },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                SplitMode.SPLIT_VERTICAL -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        SessionPane(
                            session = primarySession,
                            connection = primaryConn,
                            enabledConnections = enabledConnections,
                            tokenUsage = primaryConn?.let { tokenUsageMap[it.id] },
                            isSending = sendingMap[primarySession.id] == true,
                            isFocused = focusedPane == 0,
                            showFocusBorder = true,
                            onFocus = { focusedPane = 0 },
                            onSendMessage = { text ->
                                if (primaryConn != null) sendMessageInSession(primarySession, primaryConn, text)
                            },
                            onHandoffTo = { target -> handoffSession(primarySession, target) },
                            onDeleteSession = { deleteSession(primarySession) },
                            onOpenTokenDashboard = { showTokenDashboard = true },
                            onOpenSettings = { showSettings = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
                        SessionPane(
                            session = secondarySession,
                            connection = secondaryConn,
                            enabledConnections = enabledConnections,
                            tokenUsage = secondaryConn?.let { tokenUsageMap[it.id] },
                            isSending = sendingMap[secondarySession.id] == true,
                            isFocused = focusedPane == 1,
                            showFocusBorder = true,
                            onFocus = { focusedPane = 1 },
                            onSendMessage = { text ->
                                if (secondaryConn != null) sendMessageInSession(secondarySession, secondaryConn, text)
                            },
                            onHandoffTo = { target -> handoffSession(secondarySession, target) },
                            onDeleteSession = { deleteSession(secondarySession) },
                            onOpenTokenDashboard = { showTokenDashboard = true },
                            onOpenSettings = { showSettings = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                }
                SplitMode.SPLIT_HORIZONTAL -> {
                    Row(modifier = Modifier.fillMaxSize()) {
                        SessionPane(
                            session = primarySession,
                            connection = primaryConn,
                            enabledConnections = enabledConnections,
                            tokenUsage = primaryConn?.let { tokenUsageMap[it.id] },
                            isSending = sendingMap[primarySession.id] == true,
                            isFocused = focusedPane == 0,
                            showFocusBorder = true,
                            onFocus = { focusedPane = 0 },
                            onSendMessage = { text ->
                                if (primaryConn != null) sendMessageInSession(primarySession, primaryConn, text)
                            },
                            onHandoffTo = { target -> handoffSession(primarySession, target) },
                            onDeleteSession = { deleteSession(primarySession) },
                            onOpenTokenDashboard = { showTokenDashboard = true },
                            onOpenSettings = { showSettings = true },
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                        )
                        VerticalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
                        SessionPane(
                            session = secondarySession,
                            connection = secondaryConn,
                            enabledConnections = enabledConnections,
                            tokenUsage = secondaryConn?.let { tokenUsageMap[it.id] },
                            isSending = sendingMap[secondarySession.id] == true,
                            isFocused = focusedPane == 1,
                            showFocusBorder = true,
                            onFocus = { focusedPane = 1 },
                            onSendMessage = { text ->
                                if (secondaryConn != null) sendMessageInSession(secondarySession, secondaryConn, text)
                            },
                            onHandoffTo = { target -> handoffSession(secondarySession, target) },
                            onDeleteSession = { deleteSession(secondarySession) },
                            onOpenTokenDashboard = { showTokenDashboard = true },
                            onOpenSettings = { showSettings = true },
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                        )
                    }
                }
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
            onSave = { name ->
                val target = editingProject
                if (target == null) {
                    val newProj = Project(id = "proj_${UUID.randomUUID()}", name = name)
                    projects = projects + newProj
                    store.saveProjects(projects)
                    activeProjectId = newProj.id
                    store.saveActiveProjectId(newProj.id)
                } else {
                    projects = projects.map { if (it.id == target.id) it.copy(name = name) else it }
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

    if (showNewSessionDialog) {
        NewSessionDialog(
            enabledConnections = enabledConnections,
            defaultIndex = projectSessions.size + 1,
            onDismiss = { showNewSessionDialog = false },
            onOpenSettings = {
                showNewSessionDialog = false
                showSettings = true
            },
            onCreate = { title, connId ->
                val newSess = AgentSession(
                    id = "sess_${UUID.randomUUID()}",
                    projectId = activeProject.id,
                    title = title,
                    connectionId = connId
                )
                sessions = sessions + newSess
                store.saveSessions(sessions)
                activeSessionId = newSess.id
                store.saveActiveSessionId(newSess.id)
                showNewSessionDialog = false
            }
        )
    }
}

@Composable
private fun ProjectEditorDialog(
    initial: Project?,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: (Project) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "새 프로젝트" else "프로젝트 편집") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("프로젝트 이름") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onSave(name.trim().ifEmpty { "새 프로젝트" }) }) {
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
    defaultIndex: Int,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onCreate: (title: String, connectionId: String) -> Unit
) {
    var title by remember { mutableStateOf("세션 $defaultIndex") }
    var selectedConnId by remember { mutableStateOf(enabledConnections.firstOrNull()?.id) }

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
                            onCreate(title.trim().ifEmpty { "세션 $defaultIndex" }, id)
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
