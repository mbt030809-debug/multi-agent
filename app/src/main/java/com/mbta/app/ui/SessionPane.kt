package com.mbta.app.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mbta.app.model.AgentSession
import com.mbta.app.model.AgentTokenUsage
import com.mbta.app.model.AiConnection
import com.mbta.app.model.ChatMessage
import com.mbta.app.model.ConnectionKind
import com.mbta.app.model.CustomSkill
import com.mbta.app.model.MessageRole
import com.mbta.app.model.Project
import com.mbta.app.model.availableModelsFor
import com.mbta.app.model.defaultModelFor
import com.mbta.app.model.displayLabel
import com.mbta.app.model.formatTokens
import com.mbta.app.model.isHiddenWebBridge
import com.mbta.app.webview.HeadlessWebBridge

private data class SlashSkill(
    val command: String,
    val title: String,
    val description: String,
    val promptTemplate: String = "",
    val isAction: Boolean = false
)

private val builtInSkills = listOf(
    SlashSkill("/plan", "플랜 작성", "단계별 구현 계획 수립", "다음 작업의 단계별 구현 플랜을 작성해줘:\n"),
    SlashSkill("/review", "코드·문서 리뷰", "버그, 보안, 개선점 점검", "다음 내용을 리뷰하고 문제점과 개선안을 알려줘:\n"),
    SlashSkill("/fix", "오류 분석·해결", "원인 파악 및 수정 코드 제안", "다음 오류의 원인을 분석하고 해결 방법을 알려줘:\n"),
    SlashSkill("/summary", "핵심 요약", "3줄 요약 및 액션 아이템", "다음 내용을 핵심 3줄과 액션 아이템으로 요약해줘:\n"),
    SlashSkill("/refactor", "리팩토링", "가독성·성능 개선", "다음 코드를 더 깔끔하고 효율적으로 리팩토링해줘:\n"),
    SlashSkill("/translate", "번역", "자연스러운 한/영 번역", "다음 내용을 자연스럽게 번역해줘:\n"),
    SlashSkill("/handoff", "세션 전달 요약", "다른 AI에게 넘길 컨텍스트 정리", "지금까지의 대화 핵심과 현재 상태를 다른 AI가 바로 이어서 작업할 수 있게 요약해줘."),
    SlashSkill("/orchestrate", "멀티 AI 오케스트레이션", "여러 AI 파이프라인 실행 창 열기", isAction = true),
    SlashSkill("/clear", "대화 초기화", "현재 세션 메시지 비우기", isAction = true)
)

@Composable
fun SessionPane(
    project: Project,
    session: AgentSession,
    connection: AiConnection?,
    enabledConnections: List<AiConnection>,
    customSkills: List<CustomSkill>,
    useProjectContext: Boolean,
    tokenUsage: AgentTokenUsage?,
    isLoggedIn: Boolean,
    isSending: Boolean,
    isFocused: Boolean,
    showFocusBorder: Boolean,
    onFocus: () -> Unit,
    onToggleProjectContext: () -> Unit,
    onSendMessage: (String) -> Unit,
    onRetryLast: () -> Unit,
    onClearSession: () -> Unit,
    onOpenOrchestration: () -> Unit,
    onShareMarkdown: () -> Unit,
    onSelectModel: (String) -> Unit,
    onHandoffTo: (AiConnection) -> Unit,
    onDeleteSession: () -> Unit,
    onOpenTokenDashboard: () -> Unit,
    onOpenSettings: () -> Unit,
    onLoginConnection: (AiConnection) -> Unit,
    headlessWebBridge: HeadlessWebBridge,
    modifier: Modifier = Modifier
) {
    var inputText by remember(session.id) { mutableStateOf("") }
    var showHandoffDialog by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    var showCustomModelDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(session.messages.size) {
        if (session.messages.isNotEmpty()) {
            listState.animateScrollToItem(session.messages.lastIndex)
        }
    }

    val borderColor = if (showFocusBorder && isFocused) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    }

    Column(
        modifier = modifier
            .border(
                width = if (showFocusBorder && isFocused) 1.5.dp else 0.dp,
                color = borderColor
            )
            .background(MaterialTheme.colorScheme.background)
            .clickable { onFocus() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(
                    if (isFocused && showFocusBorder) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    }
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = connection?.name ?: "연결 없음",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (connection != null) {
                val models = availableModelsFor(connection.kind)
                if (models.isNotEmpty()) {
                    val activeModel = session.selectedModel.ifBlank {
                        connection.model.ifBlank { defaultModelFor(connection.kind) }
                    }
                    Spacer(modifier = Modifier.size(6.dp))
                    Box {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                            modifier = Modifier.clickable { showModelMenu = true }
                        ) {
                            Text(
                                text = "$activeModel ▾",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showModelMenu,
                            onDismissRequest = { showModelMenu = false }
                        ) {
                            models.forEach { m ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            m,
                                            fontWeight = if (m == activeModel) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 13.sp
                                        )
                                    },
                                    onClick = {
                                        showModelMenu = false
                                        onSelectModel(m)
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("직접 입력...", fontSize = 13.sp) },
                                onClick = {
                                    showModelMenu = false
                                    showCustomModelDialog = true
                                }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))

            if (connection != null) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .clickable { onOpenTokenDashboard() }
                ) {
                    Text(
                        text = formatTokens(tokenUsage?.totalTokens ?: 0L),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (connection != null && connection.kind.isHiddenWebBridge()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .clickable { onLoginConnection(connection) }
                ) {
                    Text(
                        text = if (isLoggedIn) "● 로그인됨" else "○ 로그인 필요",
                        fontSize = 10.sp,
                        color = if (isLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                IconButton(
                    onClick = { onLoginConnection(connection) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Visibility,
                        contentDescription = "숨김 화면 보기",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (session.messages.isNotEmpty()) {
                IconButton(
                    onClick = onShareMarkdown,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Markdown 공유",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (session.messages.isNotEmpty() && enabledConnections.size > 1) {
                IconButton(
                    onClick = { showHandoffDialog = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.CallSplit,
                        contentDescription = "다른 AI로 세션 전달",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            IconButton(
                onClick = onDeleteSession,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "세션 삭제",
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        if (isSending) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }

        if (connection == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
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
                        Text("연결된 AI / CLI가 없습니다", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(
                            text = "설정에서 Claude, ChatGPT, Gemini, Termux CLI 또는 Remote Control을 등록한 뒤 새 세션을 만들어 주세요.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("연결 설정 열기")
                        }
                    }
                }
            }
        } else {
            SelectionContainer(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(session.messages, key = { it.id }) { msg ->
                        ChatBubble(
                            message = msg,
                            isCli = connection.kind == ConnectionKind.TERMUX_CLI,
                            onRetryLast = onRetryLast,
                            onQuoteToInput = { quoted ->
                                inputText = if (inputText.isBlank()) quoted else "$inputText\n$quoted"
                            }
                        )
                    }
                }
            }

            if (inputText.startsWith("@") && !inputText.contains(" ") && !inputText.contains("\n")) {
                val query = inputText.removePrefix("@").lowercase()
                val matchedConns = enabledConnections.filter {
                    it.name.lowercase().contains(query)
                }
                if (matchedConns.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp)
                            .heightIn(max = 170.dp)
                    ) {
                        LazyColumn(contentPadding = PaddingValues(6.dp)) {
                            items(matchedConns, key = { it.id }) { target ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { inputText = "@${target.name} " }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("@${target.name}", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                    Text(target.kind.displayLabel(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            if (inputText.startsWith("/") && !inputText.contains("\n")) {
                val query = inputText.lowercase()
                val allSkills = builtInSkills + customSkills.map {
                    SlashSkill(it.command, it.title, it.description, it.promptTemplate)
                }
                val matched = allSkills.filter {
                    it.command.lowercase().contains(query) || it.title.lowercase().contains(query.removePrefix("/"))
                }
                if (matched.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp)
                            .heightIn(max = 190.dp)
                    ) {
                        LazyColumn(contentPadding = PaddingValues(6.dp)) {
                            items(matched, key = { it.command }) { skill ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (skill.isAction) {
                                                inputText = ""
                                                when (skill.command) {
                                                    "/orchestrate" -> onOpenOrchestration()
                                                    "/clear" -> onClearSession()
                                                }
                                            } else {
                                                inputText = skill.promptTemplate
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = skill.command,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = skill.title,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = skill.description,
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

            if (project.workDir.isNotBlank() || project.memoryText.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (useProjectContext) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable { onToggleProjectContext() }
                    ) {
                        val label = buildString {
                            if (project.workDir.isNotBlank()) append("📁 ${project.workDir}")
                            if (project.workDir.isNotBlank() && project.memoryText.isNotBlank()) append(" · ")
                            if (project.memoryText.isNotBlank()) append("공용 메모리")
                            append(if (useProjectContext) " ON" else " OFF")
                        }
                        Text(
                            text = label,
                            fontSize = 10.sp,
                            color = if (useProjectContext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            if (connection.kind == ConnectionKind.TERMUX_CLI) "Termux 명령어 입력..."
                            else "${connection.name}에게 메시지 입력..."
                        )
                    },
                    maxLines = 4,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    enabled = inputText.isNotBlank() && !isSending,
                    onClick = {
                        val text = inputText.trim()
                        if (text.isNotEmpty()) {
                            inputText = ""
                            onSendMessage(text)
                        }
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "전송")
                }
            }
        }
    }

    if (showCustomModelDialog) {
        var customModel by remember { mutableStateOf(session.selectedModel) }
        AlertDialog(
            onDismissRequest = { showCustomModelDialog = false },
            title = { Text("모델명 직접 입력") },
            text = {
                OutlinedTextField(
                    value = customModel,
                    onValueChange = { customModel = it },
                    label = { Text("모델명") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = customModel.isNotBlank(),
                    onClick = {
                        onSelectModel(customModel.trim())
                        showCustomModelDialog = false
                    }
                ) { Text("적용") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomModelDialog = false }) { Text("취소") }
            }
        )
    }

    if (showHandoffDialog) {
        val targets = enabledConnections.filter { it.id != connection?.id }
        AlertDialog(
            onDismissRequest = { showHandoffDialog = false },
            title = { Text("다른 AI로 세션 전달") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "현재 대화 내역을 컨텍스트로 담아 같은 프로젝트에 새 하위 세션을 생성합니다.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    targets.forEach { target ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showHandoffDialog = false
                                    onHandoffTo(target)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(target.name, fontWeight = FontWeight.Medium)
                                Text(
                                    target.kind.displayLabel(),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHandoffDialog = false }) {
                    Text("닫기")
                }
            }
        )
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    isCli: Boolean,
    onRetryLast: () -> Unit,
    onQuoteToInput: (String) -> Unit = {}
) {
    when (message.role) {
        MessageRole.SYSTEM -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = message.content,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
        MessageRole.USER -> {
            val clipboard = LocalClipboardManager.current
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.widthIn(max = 300.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            text = if (isCli) "$ ${message.content}" else message.content,
                            fontSize = 14.sp,
                            fontFamily = if (isCli) FontFamily.Monospace else FontFamily.Default,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "복사",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                modifier = Modifier.clickable { clipboard.setText(AnnotatedString(message.content)) }
                            )
                            Text(
                                text = "인용",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                modifier = Modifier.clickable { onQuoteToInput(message.content) }
                            )
                        }
                    }
                }
            }
        }
        MessageRole.ASSISTANT -> {
            val clipboard = LocalClipboardManager.current
            val bubbleColor = if (message.isError) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = bubbleColor,
                    modifier = Modifier.widthIn(max = 320.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (message.agentName.isNotBlank()) {
                                Text(
                                    text = message.agentName,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Spacer(modifier = Modifier.size(1.dp))
                            }
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(message.content)) },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "복사",
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        FormattedMessageContent(text = message.content, isCli = isCli)
                        if (message.isError) {
                            TextButton(
                                onClick = onRetryLast,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.size(4.dp))
                                Text("다시 시도", fontSize = 11.sp)
                            }
                        }
                        if (message.inputTokens > 0L || message.outputTokens > 0L) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "입력 ${formatTokens(message.inputTokens)} · 출력 ${formatTokens(message.outputTokens)}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormattedMessageContent(text: String, isCli: Boolean) {
    val clipboard = LocalClipboardManager.current
    val segments = remember(text) {
        val result = mutableListOf<Triple<Boolean, String, String>>()
        val regex = Regex("```([a-zA-Z0-9_+-]*)\\n([\\s\\S]*?)```")
        var lastIdx = 0
        for (match in regex.findAll(text)) {
            if (match.range.first > lastIdx) {
                val plain = text.substring(lastIdx, match.range.first).trim()
                if (plain.isNotEmpty()) result.add(Triple(false, "", plain))
            }
            val lang = match.groupValues[1].ifBlank { "code" }
            val code = match.groupValues[2].trimEnd()
            result.add(Triple(true, lang, code))
            lastIdx = match.range.last + 1
        }
        if (lastIdx < text.length) {
            val tail = text.substring(lastIdx).trim()
            if (tail.isNotEmpty()) result.add(Triple(false, "", tail))
        }
        if (result.isEmpty()) result.add(Triple(false, "", text))
        result
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEach { (isCode, lang, body) ->
            if (!isCode) {
                Text(
                    text = body,
                    fontSize = 14.sp,
                    fontFamily = if (isCli) FontFamily.Monospace else FontFamily.Default,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(lang, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(body)) },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "코드 복사",
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            Text(
                                text = body,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}
