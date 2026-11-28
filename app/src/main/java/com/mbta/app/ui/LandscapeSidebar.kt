package com.mbta.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sync
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mbta.app.model.AgentSession
import com.mbta.app.model.AiConnection
import com.mbta.app.model.Project
import com.mbta.app.model.SplitMode
import com.mbta.app.model.availableEffortsFor
import com.mbta.app.model.availableModelsFor
import com.mbta.app.model.defaultModelFor
import com.mbta.app.model.formatTokens
import com.mbta.app.model.labelIn

/** 가로모드 좌측 패널: 프로젝트 · 도구 · 세션 목록 · 모델/effort 선택 */
@Composable
internal fun LandscapeSidebar(
    projects: List<Project>,
    activeProject: Project,
    sessions: List<AgentSession>,
    enabledConnections: List<AiConnection>,
    primarySession: AgentSession?,
    secondarySession: AgentSession?,
    splitMode: SplitMode,
    sendingMap: Map<String, Boolean>,
    unreadDoneSessions: Map<String, Boolean>,
    totalTokens: Long,
    onSelectProject: (Project) -> Unit,
    onNewProject: () -> Unit,
    onEditProject: () -> Unit,
    onSearch: () -> Unit,
    onTokens: () -> Unit,
    onGit: () -> Unit,
    onFanOut: () -> Unit,
    onCycleSplit: () -> Unit,
    onSettings: () -> Unit,
    onSelectSession: (AgentSession) -> Unit,
    onDeleteSession: (AgentSession) -> Unit,
    onNewSession: () -> Unit,
    onSelectModel: (AgentSession, AiConnection, String) -> Unit,
    onSelectEffort: (AgentSession, AiConnection, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showProjectMenu by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .width(224.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // 프로젝트
        Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showProjectMenu = true }
            ) {
                Text(
                    text = activeProject.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = "프로젝트 선택", modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = showProjectMenu, onDismissRequest = { showProjectMenu = false }) {
                projects.forEach { proj ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                proj.name,
                                fontWeight = if (proj.id == activeProject.id) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            showProjectMenu = false
                            onSelectProject(proj)
                        }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("+ 새 프로젝트") }, onClick = { showProjectMenu = false; onNewProject() })
                DropdownMenuItem(text = { Text("현재 프로젝트 편집") }, onClick = { showProjectMenu = false; onEditProject() })
            }
        }

        // 도구 아이콘
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onSearch, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Search, contentDescription = "통합 검색 · 핀", modifier = Modifier.size(18.dp))
            }
            if (activeProject.workDir.isNotBlank()) {
                IconButton(onClick = onGit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Code, contentDescription = "Git · Diff · CI", modifier = Modifier.size(18.dp))
                }
            }
            if (enabledConnections.size >= 2) {
                IconButton(onClick = onFanOut, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.CompareArrows, contentDescription = "동시 비교", modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = onCycleSplit, modifier = Modifier.size(32.dp)) {
                val icon = when (splitMode) {
                    SplitMode.SINGLE -> Icons.Default.ViewAgenda
                    SplitMode.SPLIT_VERTICAL -> Icons.Default.ViewStream
                    SplitMode.SPLIT_HORIZONTAL -> Icons.Default.ViewColumn
                }
                Icon(icon, contentDescription = "화면 분할", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Settings, contentDescription = "설정", modifier = Modifier.size(18.dp))
            }
        }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clickable { onTokens() }
        ) {
            Text(
                text = "Tokens ${formatTokens(totalTokens)}",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), modifier = Modifier.padding(top = 4.dp))

        // 세션 목록
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(sessions, key = { _, s -> s.id }) { idx, sess ->
                val conn = enabledConnections.find { it.id == sess.connectionId }
                val isSelected = if (splitMode == SplitMode.SINGLE) {
                    sess.id == primarySession?.id
                } else {
                    sess.id == primarySession?.id || sess.id == secondarySession?.id
                }
                Surface(
                    onClick = { onSelectSession(sess) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SessionStatusBadge(sess, conn, sendingMap[sess.id] == true, unreadDoneSessions[sess.id] == true)
                        ProviderMarkIcon(connection = conn, size = 19.dp)
                        val t = sess.title.trim()
                        val isDefault = t.isBlank() || t.matches(Regex("^세션\\s*\\d+$")) || t == "기본 세션" ||
                            (conn != null && t == conn.name.trim())
                        Text(
                            text = if (isDefault) (conn?.labelIn(enabledConnections) ?: "${idx + 1}") else t,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "세션 삭제",
                            modifier = Modifier.size(15.dp).clickable { onDeleteSession(sess) },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                    }
                }
            }
            item {
                Surface(
                    onClick = onNewSession,
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "새 하위 세션", modifier = Modifier.size(16.dp))
                        Text("새 세션", fontSize = 12.sp)
                    }
                }
            }
        }

        // 현재 세션 모델 / effort
        val sess = primarySession
        val conn = sess?.let { s -> enabledConnections.find { it.id == s.connectionId } }
        if (sess != null && conn != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            SidebarModelPicker(sess, conn, onSelectModel, onSelectEffort)
        }
    }
}

@Composable
private fun SidebarModelPicker(
    session: AgentSession,
    connection: AiConnection,
    onSelectModel: (AgentSession, AiConnection, String) -> Unit,
    onSelectEffort: (AgentSession, AiConnection, String) -> Unit
) {
    var dynamicModels by remember(connection.id) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(connection.id) {
        dynamicModels = com.mbta.app.network.AiApiClient.fetchAvailableModels(connection)
    }
    val models = dynamicModels.ifEmpty { availableModelsFor(connection) }
    if (models.isEmpty()) return
    val activeModel = session.selectedModel.ifBlank { connection.model.ifBlank { defaultModelFor(connection) } }
    val efforts = availableEffortsFor(connection, activeModel, models)
    val activeEffort = when {
        session.selectedEffort.isNotBlank() && efforts.any { it.equals(session.selectedEffort, ignoreCase = true) } -> session.selectedEffort
        efforts.any { it.equals("medium", ignoreCase = true) } -> "medium"
        else -> efforts.firstOrNull().orEmpty()
    }
    var showModel by remember { mutableStateOf(false) }
    var showEffort by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(modifier = Modifier.weight(1f, fill = false)) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                modifier = Modifier.clickable { showModel = true }
            ) {
                Text(
                    text = "$activeModel ▾",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 130.dp).padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
            DropdownMenu(expanded = showModel, onDismissRequest = { showModel = false }) {
                models.forEach { m ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                m,
                                fontWeight = if (m == activeModel) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        },
                        onClick = { showModel = false; onSelectModel(session, connection, m) }
                    )
                }
            }
        }
        if (efforts.isNotEmpty()) {
            Box {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                    modifier = Modifier.clickable { showEffort = true }
                ) {
                    Text(
                        text = "$activeEffort ▾",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
                DropdownMenu(expanded = showEffort, onDismissRequest = { showEffort = false }) {
                    efforts.forEach { ef ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    ef,
                                    fontWeight = if (ef.equals(activeEffort, ignoreCase = true)) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                            },
                            onClick = { showEffort = false; onSelectEffort(session, connection, ef) }
                        )
                    }
                }
            }
        }
    }
}


/** 세션 상태 배지: 진행중(파랑 회전 화살표) / 질문(노랑 ?) / 오류(빨강 !) / 완료 미확인(초록 체크) / 확인함(검정 점) */
@androidx.compose.runtime.Composable
internal fun SessionStatusBadge(
    sess: com.mbta.app.model.AgentSession,
    conn: com.mbta.app.model.AiConnection?,
    sending: Boolean,
    unreadDone: Boolean
) {
    val last = sess.messages.lastOrNull()
    val hasQuestion = androidx.compose.runtime.remember(last?.id, last?.content, sending) {
        !sending && last != null && last.role == com.mbta.app.model.MessageRole.ASSISTANT && !last.isError &&
            detectQuestionPrompt(
                last.content,
                structuredOnly = conn?.endpointUrl?.lowercase()?.let { it.contains("/claude") || it.contains("claude.ai") } == true
            ) != null
    }
    val size = 18.dp
    when {
        sending -> {
            val rot = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
                .animateFloat(
                    0f, 360f,
                    androidx.compose.animation.core.infiniteRepeatable(
                        androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.LinearEasing)
                    ),
                    label = "rot"
                )
            Box(Modifier.size(size).background(Color(0xFF3B82F6), CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Sync, contentDescription = "진행 중", tint = Color.White,
                    modifier = Modifier.size(12.dp).graphicsLayer { rotationZ = -rot.value }
                )
            }
        }
        last?.isError == true -> Box(Modifier.size(size).background(Color(0xFFEF4444), CircleShape), contentAlignment = Alignment.Center) {
            Text("!", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        hasQuestion -> Box(Modifier.size(size).background(Color(0xFFFACC15), CircleShape), contentAlignment = Alignment.Center) {
            Text("?", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        unreadDone -> Box(Modifier.size(size).background(Color(0xFF22C55E), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, contentDescription = "완료", tint = Color.White, modifier = Modifier.size(12.dp))
        }
        else -> Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Box(Modifier.size(7.dp).background(Color.Black, CircleShape))
        }
    }
}
