package com.mbta.app.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.mbta.app.model.MessageRole
import com.mbta.app.model.displayLabel
import com.mbta.app.model.formatTokens
import com.mbta.app.model.isHiddenWebBridge

@Composable
fun SessionPane(
    session: AgentSession,
    connection: AiConnection?,
    enabledConnections: List<AiConnection>,
    tokenUsage: AgentTokenUsage?,
    isLoggedIn: Boolean,
    isSending: Boolean,
    isFocused: Boolean,
    showFocusBorder: Boolean,
    onFocus: () -> Unit,
    onSendMessage: (String) -> Unit,
    onHandoffTo: (AiConnection) -> Unit,
    onDeleteSession: () -> Unit,
    onOpenTokenDashboard: () -> Unit,
    onOpenSettings: () -> Unit,
    onLoginConnection: (AiConnection) -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by remember(session.id) { mutableStateOf("") }
    var showHandoffDialog by remember { mutableStateOf(false) }
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
                Spacer(modifier = Modifier.size(6.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                ) {
                    Text(
                        text = connection.kind.displayLabel(),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
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
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(session.messages, key = { it.id }) { msg ->
                    ChatBubble(
                        message = msg,
                        isCli = connection.kind == ConnectionKind.TERMUX_CLI
                    )
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
private fun ChatBubble(message: ChatMessage, isCli: Boolean) {
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.widthIn(max = 300.dp)
                ) {
                    Text(
                        text = if (isCli) "$ ${message.content}" else message.content,
                        fontSize = 14.sp,
                        fontFamily = if (isCli) FontFamily.Monospace else FontFamily.Default,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
        MessageRole.ASSISTANT -> {
            val clipboard = LocalClipboardManager.current
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
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
                                    color = MaterialTheme.colorScheme.primary
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
                        Text(
                            text = message.content,
                            fontSize = 14.sp,
                            fontFamily = if (isCli) FontFamily.Monospace else FontFamily.Default,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
