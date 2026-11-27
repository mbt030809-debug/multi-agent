package com.mbta.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.mbta.app.R
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mbta.app.model.AgentSession
import com.mbta.app.model.AgentTokenUsage
import com.mbta.app.model.AiConnection
import com.mbta.app.model.ChatMessage
import com.mbta.app.model.ConnectionKind
import com.mbta.app.model.CustomSkill
import com.mbta.app.model.MessageRole
import com.mbta.app.model.Project
import com.mbta.app.model.availableEffortsFor
import com.mbta.app.model.availableModelsFor
import com.mbta.app.model.defaultContextLimitFor
import com.mbta.app.model.defaultContextLimitForKind
import com.mbta.app.model.defaultModelFor
import com.mbta.app.model.displayLabel
import com.mbta.app.model.extractParenEffort
import com.mbta.app.model.formatTokens
import com.mbta.app.model.isHiddenWebBridge
import com.mbta.app.model.stripParenEffort
import com.mbta.app.model.usesHiddenWebBridge
import com.mbta.app.network.GitBridgeClient
import com.mbta.app.webview.HeadlessWebBridge
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val codeFenceRegex = Regex("```[\\s\\S]*?```")
private val inlineCodeRegex = Regex("`[^`\\n]+`")
private val urlRegex = Regex("https?://\\S+")
private val permissionCardRegex = Regex(
    "(?:flagged\\s+as\\s+potentially\\s+unsafe|위험\\s*요소\\s*검토|민감하다고\\s*생각하실\\s*수도\\s*있는\\s*작업)",
    RegexOption.IGNORE_CASE
)
private val permissionLastLineRegex = Regex(
    "(?:\\[y/n]|\\(y/n\\)|\\(yes/no\\)|allow\\s+once|always\\s+allow|" +
        "^(?:do\\s+you\\s+want\\s+to|would\\s+you\\s+like\\s+to)\\s+(?:allow|approve|grant|run|execute)\\b|" +
        "^(?:이\\s*작업을|명령\\s*실행을|권한을|파일\\s*(?:수정|삭제)을).{0,20}(?:허용|승인)(?:할까요|하시겠습니까)\\s*\\??$)",
    RegexOption.IGNORE_CASE
)
private val openEndedSignoffRegex = Regex(
    "(?:도와드릴까요|궁금한|있으신가요|있으세요|있나요|있을까요|말씀해\\s*주세요|알려\\s*주세요|확인해\\s*보시고)",
    RegexOption.IGNORE_CASE
)
private val confirmQuestionEndingRegex = Regex(
    "(?:할까요|진행할까요|적용할까요|수정할까요|푸시할까요|빌드할까요|드릴까요|하시겠습니까|할래요|원하시나요|어떨까요|괜찮을까요|좋을까요|맞나요|맞을까요)\\s*\\??$"
)
private val englishYesNoQuestionRegex = Regex(
    "^(?:shall|should|would|do|can|could|may)\\s+[^\\n]{3,}\\?$",
    RegexOption.IGNORE_CASE
)
private val numberedOptionRegex = Regex("^\\s*([1-8])[.)]\\s+(.+)$")

internal data class DetectedQuestion(
    val title: String,
    val options: List<String>,
    val isPermission: Boolean = false
)

internal fun detectQuickReplies(rawContent: String): List<String> =
    detectQuestionPrompt(rawContent)?.options ?: emptyList()

internal fun detectQuestionPrompt(rawContent: String): DetectedQuestion? {
    if (rawContent.isBlank()) return null
    val cleaned = rawContent
        .replace(codeFenceRegex, "")
        .replace(inlineCodeRegex, "")
        .replace(urlRegex, "")
        .trim()
    if (cleaned.isEmpty()) return null

    val nonBlankLines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (nonBlankLines.isEmpty()) return null

    val permBlock = nonBlankLines.takeLast(12).joinToString("\n")
    val tailThree = nonBlankLines.takeLast(3).map { it.lowercase() }
    val lastLine = nonBlankLines.last()
    val isBulletOrQuotedLast = lastLine.startsWith("-") || lastLine.startsWith("*") || lastLine.startsWith("•") || lastLine.startsWith("\"") || lastLine.startsWith("'")

    // 1. 실제 권한/승인/위험 명령 확인 프롬프트만 엄격하게 감지 (일반 대화의 '허용' 단어 오탐 방지)
    val hasExplicitAllowDenyPair =
        (tailThree.any { it == "허용" } && tailThree.any { it == "허용 안 함" || it == "거부" }) ||
            (tailThree.any { it == "allow" || it == "approve" } && tailThree.any { it == "deny" || it == "don't allow" || it == "reject" })
    val isWebApprovalCard = hasExplicitAllowDenyPair ||
        (permissionCardRegex.containsMatchIn(permBlock) && (tailThree.any { it == "허용" || it == "allow" || it == "허용 안 함" || it == "deny" }))
    val isDirectCliPermPrompt = !isBulletOrQuotedLast && permissionLastLineRegex.containsMatchIn(lastLine)
    if (isWebApprovalCard || isDirectCliPermPrompt) {
        val opts = if (permBlock.contains("허용 안 함") || tailThree.any { it == "허용 안 함" }) {
            listOf("허용", "허용 안 함")
        } else {
            listOf("허용", "항상 허용", "거부")
        }
        return DetectedQuestion(title = permBlock.take(500), options = opts, isPermission = true)
    }

    // 2. 마지막 문장이 의례적인 마무리 인사/열린 질문이면 표시하지 않음
    if (openEndedSignoffRegex.containsMatchIn(lastLine)) {
        return null
    }

    // 3. 꼬리 영역(마지막 14줄)에 실제 질문 + 1번부터 시작하는 연속 선택지(2~8개)가 함께 있을 때만 질문지 추출
    val tailBlock = nonBlankLines.takeLast(14)
    val questionLine = tailBlock.lastOrNull { line ->
        !openEndedSignoffRegex.containsMatchIn(line) &&
            !numberedOptionRegex.matches(line) && (
            line.endsWith("?") ||
                confirmQuestionEndingRegex.containsMatchIn(line) ||
                englishYesNoQuestionRegex.containsMatchIn(line)
            )
    } ?: return null

    val optionMatches = tailBlock.mapNotNull { line ->
        val m = numberedOptionRegex.matchEntire(line) ?: return@mapNotNull null
        val num = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val label = m.groupValues[2]
            .replace("**", "")
            .replace("__", "")
            .trim()
            .take(180)
        if (label.isNotEmpty()) num to label else null
    }
    val startsFromOne = optionMatches.size >= 2 && optionMatches.first().first == 1 &&
        optionMatches.map { it.first } == (1..optionMatches.size).toList()
    if (startsFromOne) {
        return DetectedQuestion(
            title = questionLine.replace("**", "").trim(),
            options = optionMatches.map { it.second },
            isPermission = false
        )
    }

    // 4. 마지막 줄 자체가 명확한 확인/예·아니오 질문(?로 끝남)일 때만 표시
    val isClearConfirmation = !isBulletOrQuotedLast && lastLine.endsWith("?") && (
        confirmQuestionEndingRegex.containsMatchIn(lastLine) ||
            englishYesNoQuestionRegex.containsMatchIn(lastLine) ||
            Regex("(?:할까요|하시겠습니까|할래요|괜찮을까요|맞나요|진행해|바꿀까요|넣을까요|뺄까요)").containsMatchIn(lastLine)
        )

    return if (isClearConfirmation) {
        DetectedQuestion(
            title = lastLine.replace("**", "").trim(),
            options = listOf("네, 진행해줘", "아니요"),
            isPermission = false
        )
    } else null
}

private data class PendingAttachment(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
    val uri: Uri? = null
)

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
    onShareMarkdown: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectEffort: (String) -> Unit = {},
    onHandoffTo: (target: AiConnection, smartSummary: Boolean, includeGit: Boolean, autoSend: Boolean) -> Unit,
    onDeleteSession: () -> Unit,
    onOpenTokenDashboard: () -> Unit,
    onOpenSettings: () -> Unit,
    onLoginConnection: (AiConnection) -> Unit,
    headlessWebBridge: HeadlessWebBridge,
    queuedMessages: List<String> = emptyList(),
    onCancelSending: () -> Unit = {},
    onRemoveQueued: (Int) -> Unit = {},
    onSendQueuedNow: (Int) -> Unit = {},
    onRenameSession: (String) -> Unit = {},
    onTogglePinMessage: (String) -> Unit = {},
    onStopSubagent: (String) -> Unit = {},
    onRetrySubagent: (String) -> Unit = {},
    onAttachOrchestration: (enabled: Boolean, subConnId: String?, subModel: String, subEffort: String, maxParallel: Int, maxRounds: Int) -> Unit = { _, _, _, _, _, _ -> },
    onToggleSkipPermissions: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var inputText by remember(session.id) { mutableStateOf("") }
    var attachments by remember(session.id) { mutableStateOf<List<PendingAttachment>>(emptyList()) }
    var showHandoffDialog by remember { mutableStateOf(false) }
    var showAttachOrchDialog by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    var showEffortMenu by remember { mutableStateOf(false) }
    var showCustomModelDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var inspectingSubagentId by remember(session.id) { mutableStateOf<String?>(null) }
    var dismissedQuestionMsgId by remember(session.id) { mutableStateOf("") }
    var expandedQuestionMsgId by remember(session.id) { mutableStateOf<String?>(null) }
    var autoOpenedQuestionMsgId by remember(session.id) { mutableStateOf("") }
    var autoApprovedMsgId by remember(session.id) { mutableStateOf("") }
    val listState = remember(session.id) {
        androidx.compose.foundation.lazy.LazyListState(
            firstVisibleItemIndex = (session.messages.size - 1).coerceAtLeast(0),
            firstVisibleItemScrollOffset = 100000
        )
    }
    val context = LocalContext.current
    val uiPrefs = remember(context) { context.getSharedPreferences("mbta_ui_prefs", android.content.Context.MODE_PRIVATE) }
    var zoomScale by remember { mutableFloatStateOf(uiPrefs.getFloat("chat_zoom_scale", 1f).coerceIn(0.7f, 1.8f)) }
    fun updateZoom(newScale: Float) {
        val clamped = newScale.coerceIn(0.7f, 1.8f)
        zoomScale = clamped
        uiPrefs.edit().putFloat("chat_zoom_scale", clamped).apply()
    }
    val paneScope = rememberCoroutineScope()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val added = mutableListOf<PendingAttachment>()
        var skippedLarge = 0
        for (uri in uris) {
            runCatching {
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                var name = "attachment"
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
                }
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
                if (bytes.size <= 10 * 1024 * 1024) {
                    added.add(PendingAttachment(name, mime, bytes, uri))
                } else {
                    skippedLarge++
                }
            }
        }
        if (added.isNotEmpty()) {
            attachments = attachments + added
        }
        if (skippedLarge > 0) {
            android.widget.Toast.makeText(context, "10MB 초과 파일 ${skippedLarge}개는 제외되었습니다.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(session.id, session.messages.size) {
        if (session.messages.isNotEmpty()) {
            listState.scrollToItem(session.messages.lastIndex, 100000)
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
                .height(30.dp)
                .background(
                    if (isFocused && showFocusBorder) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    }
                )
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { showRenameDialog = true }
                    .padding(end = 2.dp)
            ) {
                ProviderMarkIcon(connection = connection, size = 14.dp)
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = session.title.ifBlank { connection?.name ?: "연결 없음" },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp)
                )
            }
            if (connection != null) {
                var dynamicModels by remember(connection.id, connection.endpointUrl) { mutableStateOf<List<String>>(emptyList()) }
                LaunchedEffect(showModelMenu, connection.id) {
                    if (showModelMenu) {
                        dynamicModels = com.mbta.app.network.AiApiClient.fetchAvailableModels(connection)
                    }
                }
                val models = dynamicModels.ifEmpty { availableModelsFor(connection) }
                if (models.isNotEmpty()) {
                    val activeModel = session.selectedModel.ifBlank {
                        connection.model.ifBlank { defaultModelFor(connection) }
                    }
                    val efforts = availableEffortsFor(connection, activeModel, models)
                    val parenEffort = extractParenEffort(activeModel)
                    val modelBadgeLabel = if (parenEffort.isNotBlank() && efforts.isNotEmpty()) {
                        stripParenEffort(activeModel)
                    } else {
                        activeModel
                    }
                    Spacer(modifier = Modifier.size(6.dp))
                    Box {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                            modifier = Modifier.clickable { showModelMenu = true }
                        ) {
                            Text(
                                text = "$modelBadgeLabel ▾",
                                fontSize = 9.5.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
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
                    if (efforts.isNotEmpty()) {
                        val activeEffort = when {
                            parenEffort.isNotBlank() -> parenEffort
                            session.selectedEffort.isNotBlank() && efforts.any { it.equals(session.selectedEffort, ignoreCase = true) } -> session.selectedEffort
                            efforts.any { it.equals("medium", ignoreCase = true) } -> efforts.first { it.equals("medium", ignoreCase = true) }
                            else -> efforts.first()
                        }
                        Spacer(modifier = Modifier.size(4.dp))
                        Box {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                                modifier = Modifier.clickable { showEffortMenu = true }
                            ) {
                                Text(
                                    text = "$activeEffort ▾",
                                    fontSize = 9.5.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showEffortMenu,
                                onDismissRequest = { showEffortMenu = false }
                            ) {
                                efforts.forEach { ef ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                ef,
                                                fontWeight = if (ef.equals(activeEffort, ignoreCase = true)) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 13.sp
                                            )
                                        },
                                        onClick = {
                                            showEffortMenu = false
                                            onSelectEffort(ef)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))

            if (connection != null) {
                val customLim = tokenUsage?.customLimit
                val lastTracked = session.messages.lastOrNull { it.inputTokens > 0L || it.outputTokens > 0L }
                val sessionCtxTokens = if (lastTracked != null) {
                    lastTracked.inputTokens + lastTracked.outputTokens
                } else {
                    session.messages.sumOf { (it.content.length / 3L).coerceAtLeast(0L) }
                }
                val totalTok = tokenUsage?.totalTokens ?: sessionCtxTokens
                val remPct = tokenUsage?.quotaPercent
                val customRemPct = if (customLim != null && customLim > 0L) {
                    (((customLim - totalTok).coerceAtLeast(0L).toDouble() / customLim.toDouble()) * 100.0).coerceIn(0.0, 100.0)
                } else null
                val effectiveRemPct = remPct?.toDouble() ?: customRemPct
                val badgeText = when {
                    remPct != null -> "5h $remPct%"
                    customRemPct != null -> "남음 ${customRemPct.roundToInt()}%"
                    else -> formatTokens(totalTok)
                }
                val badgeColor = when {
                    effectiveRemPct != null && effectiveRemPct <= 15.0 -> MaterialTheme.colorScheme.error
                    effectiveRemPct != null && effectiveRemPct <= 35.0 -> androidx.compose.ui.graphics.Color(0xFFFF9800)
                    else -> MaterialTheme.colorScheme.primary
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .clickable { onOpenTokenDashboard() }
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 9.5.sp,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
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
                        fontSize = 9.5.sp,
                        color = if (isLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                IconButton(
                    onClick = { onLoginConnection(connection) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Visibility,
                        contentDescription = "숨김 화면 보기",
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            if (session.messages.isNotEmpty() && enabledConnections.isNotEmpty()) {
                IconButton(
                    onClick = { showHandoffDialog = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_handoff),
                        contentDescription = "세션 전달",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (enabledConnections.isNotEmpty()) {
                IconButton(
                    onClick = { showAttachOrchDialog = true },
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        Icons.Default.AccountTree,
                        contentDescription = "현재 세션에 서브 모델 연결",
                        tint = if (session.isOrchestration) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            Surface(
                onClick = {
                    val nextOn = !session.skipPermissions
                    onToggleSkipPermissions()
                    android.widget.Toast.makeText(
                        context,
                        if (nextOn) "⚡ 자동 승인 ON (--dangerously-skip-permissions)" else "🔒 자동 승인 OFF",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                },
                shape = RoundedCornerShape(6.dp),
                color = if (session.skipPermissions) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
                modifier = Modifier.size(26.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (session.skipPermissions) Icons.Default.GppMaybe else Icons.Default.Shield,
                        contentDescription = "권한 자동 승인 토글",
                        tint = if (session.skipPermissions) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

        }

        if (session.isOrchestration) {
            val subConn = enabledConnections.find { it.id == (session.subConnectionId ?: session.connectionId) }
            val subModelLabel = session.subModel.ifBlank { subConn?.let { defaultModelFor(it) } ?: "" }
            val runningCount = session.subagentRuns.count { it.status == "running" }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "⚡ 오케스트레이션",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "서브: ${subConn?.name ?: "동일"}${if (subModelLabel.isNotBlank()) " · $subModelLabel" else ""} (최대 ${session.maxParallelSubagents}×${session.maxDelegationRounds})",
                            fontSize = 9.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (runningCount > 0) {
                            Text(
                                text = "실행중 $runningCount",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (session.subagentRuns.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            session.subagentRuns.takeLast(12).forEach { run ->
                                val (icon, fgColor, bgColor) = when (run.status) {
                                    "running" -> Triple("⏳", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
                                    "done" -> Triple("✓", androidx.compose.ui.graphics.Color(0xFF15803D), androidx.compose.ui.graphics.Color(0xFF22C55E).copy(alpha = 0.14f))
                                    "error" -> Triple("✗", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f))
                                    else -> Triple("■", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                                }
                                Surface(
                                    shape = RoundedCornerShape(7.dp),
                                    color = bgColor,
                                    border = BorderStroke(0.8.dp, fgColor.copy(alpha = 0.45f)),
                                    modifier = Modifier.clickable { inspectingSubagentId = run.id }
                                ) {
                                    Text(
                                        text = "$icon ${run.label}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = fgColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .widthIn(max = 150.dp)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
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
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    val zoom = event.calculateZoom()
                                    if (zoom != 1f) {
                                        updateZoom(zoomScale * zoom)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
                contentPadding = PaddingValues(start = 25.dp, end = 25.dp, top = 3.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                itemsIndexed(session.messages, key = { _, m -> m.id }) { idx, msg ->
                    ChatBubble(
                        message = msg,
                        isCli = connection.kind == ConnectionKind.TERMUX_CLI,
                        fontScale = zoomScale,
                        isStreaming = isSending && idx == session.messages.lastIndex && msg.role == MessageRole.ASSISTANT,
                        onRetryLast = onRetryLast,
                        onQuoteToInput = { quoted ->
                            inputText = if (inputText.isBlank()) quoted else "$inputText\n$quoted"
                        },
                        onTogglePin = { onTogglePinMessage(msg.id) }
                    )
                }
                if (isSending && session.messages.lastOrNull()?.role != MessageRole.ASSISTANT) {
                    item(key = "waiting_spinner") {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text("응답 기다리는 중...", fontSize = (13f * zoomScale).sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
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
                                                    "/orchestrate" -> showAttachOrchDialog = true
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

            if (queuedMessages.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "대기 큐 (${queuedMessages.size})",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    queuedMessages.forEachIndexed { idx, q ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = q.take(24) + if (q.length > 24) "…" else "",
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "바로 보내기",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { onSendQueuedNow(idx) }
                                )
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "큐 제거",
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { onRemoveQueued(idx) }
                                )
                            }
                        }
                    }
                }
            }

            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (attachments.size >= 2) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                            modifier = Modifier.clickable { attachments = emptyList() }
                        ) {
                            Text(
                                text = "${attachments.size}개 · 전체삭제",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    attachments.forEachIndexed { idx, att ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "📎 ${att.name}",
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 160.dp)
                                )
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "제거",
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { attachments = attachments.filterIndexed { i, _ -> i != idx } }
                                )
                            }
                        }
                    }
                }
            }

            val lastMsg = session.messages.lastOrNull()
            val detectedQuestion = remember(lastMsg?.id, lastMsg?.content, isSending, dismissedQuestionMsgId) {
                if (lastMsg == null || lastMsg.role != MessageRole.ASSISTANT || lastMsg.isError || lastMsg.id == dismissedQuestionMsgId) {
                    null
                } else {
                    val q = detectQuestionPrompt(lastMsg.content)
                    if (q != null && (!isSending || q.isPermission)) q else null
                }
            }
            LaunchedEffect(lastMsg?.id, detectedQuestion?.isPermission, session.skipPermissions) {
                if (detectedQuestion != null && lastMsg != null) {
                    if (detectedQuestion.isPermission && session.skipPermissions && autoApprovedMsgId != lastMsg.id) {
                        autoApprovedMsgId = lastMsg.id
                        dismissedQuestionMsgId = lastMsg.id
                        expandedQuestionMsgId = null
                        android.widget.Toast.makeText(context, "⚡ 권한 자동 승인됨", android.widget.Toast.LENGTH_SHORT).show()
                        if (connection != null && connection.usesHiddenWebBridge() && headlessWebBridge != null) {
                            headlessWebBridge.clickWebActionButton(connection.id, "허용") { clicked ->
                                if (!clicked) onSendMessage("허용")
                            }
                        } else {
                            onSendMessage("허용")
                        }
                    } else if (autoOpenedQuestionMsgId != lastMsg.id) {
                        autoOpenedQuestionMsgId = lastMsg.id
                        expandedQuestionMsgId = lastMsg.id
                    }
                }
            }
            if (detectedQuestion != null && lastMsg != null) {
                Surface(
                    color = if (detectedQuestion.isPermission) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expandedQuestionMsgId = lastMsg.id }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (detectedQuestion.isPermission) "⚠️ 권한 확인이 필요합니다" else "📋 AI 질문에 답변하기 (${detectedQuestion.options.size}개 선택지)",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (detectedQuestion.isPermission) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "열기",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (detectedQuestion.isPermission) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "질문창 닫기",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier
                                .size(17.dp)
                                .clickable {
                                    dismissedQuestionMsgId = lastMsg.id
                                    expandedQuestionMsgId = null
                                }
                        )
                    }
                }
            }

            if (detectedQuestion != null && lastMsg != null && expandedQuestionMsgId == lastMsg.id) {
                var selectedOpts by remember(lastMsg.id) { mutableStateOf<Set<Int>>(emptySet()) }
                var customAnswer by remember(lastMsg.id) { mutableStateOf("") }
                Dialog(
                    onDismissRequest = { expandedQuestionMsgId = null },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth(0.94f)
                            .heightIn(min = 320.dp, max = 620.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (detectedQuestion.isPermission) "⚠️ 권한 · 실행 확인" else "📋 AI 질문 답변",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (detectedQuestion.isPermission) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                                TextButton(onClick = { expandedQuestionMsgId = null }) {
                                    Text("접기", fontSize = 12.sp)
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 150.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .verticalScroll(rememberScrollState())
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = detectedQuestion.title,
                                        fontSize = 12.5.sp,
                                        lineHeight = 18.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            if (detectedQuestion.isPermission) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    detectedQuestion.options.forEach { choice ->
                                        val isApprove = choice == "허용" || choice == "항상 허용"
                                        val isDeny = choice == "허용 안 함" || choice == "거부"
                                        Surface(
                                            onClick = {
                                                dismissedQuestionMsgId = lastMsg.id
                                                expandedQuestionMsgId = null
                                                if ((isApprove || isDeny) && connection != null && connection.usesHiddenWebBridge() && headlessWebBridge != null) {
                                                    headlessWebBridge.clickWebActionButton(connection.id, choice) { clicked ->
                                                        if (clicked) {
                                                            android.widget.Toast.makeText(context, "'$choice' 버튼을 클릭했습니다.", android.widget.Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            onSendMessage(choice)
                                                        }
                                                    }
                                                } else {
                                                    onSendMessage(choice)
                                                }
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            color = when {
                                                isApprove -> MaterialTheme.colorScheme.primaryContainer
                                                isDeny -> MaterialTheme.colorScheme.errorContainer
                                                else -> MaterialTheme.colorScheme.secondaryContainer
                                            },
                                            border = BorderStroke(
                                                1.dp,
                                                if (isApprove) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                                            ),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)
                                            ) {
                                                Text(
                                                    text = choice,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isApprove) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f, fill = false)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "선택지 (복수 선택 가능)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    detectedQuestion.options.forEachIndexed { idx, opt ->
                                        val isSel = idx in selectedOpts
                                        Surface(
                                            onClick = {
                                                selectedOpts = if (isSel) selectedOpts - idx else selectedOpts + idx
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSel) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                            border = BorderStroke(
                                                if (isSel) 1.5.dp else 1.dp,
                                                if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.Top,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = if (isSel) "☑" else "☐",
                                                    fontSize = 14.sp,
                                                    color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = opt,
                                                    fontSize = 13.sp,
                                                    lineHeight = 18.sp,
                                                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = customAnswer,
                                    onValueChange = { customAnswer = it },
                                    label = { Text("세부 답변 · 추가 요청 (선택/직접 입력)", fontSize = 12.sp) },
                                    minLines = 3,
                                    maxLines = 6,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = {
                                            dismissedQuestionMsgId = lastMsg.id
                                            expandedQuestionMsgId = null
                                        }
                                    ) { Text("무시") }
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Button(
                                        enabled = selectedOpts.isNotEmpty() || customAnswer.isNotBlank(),
                                        onClick = {
                                            val chosenText = selectedOpts.sorted().mapNotNull { detectedQuestion.options.getOrNull(it) }
                                            val replyBody = buildString {
                                                if (chosenText.isNotEmpty()) {
                                                    if (chosenText.size == 1) {
                                                        append(chosenText.first())
                                                    } else {
                                                        append("선택:\n")
                                                        chosenText.forEachIndexed { i, c -> append("${i + 1}. $c\n") }
                                                    }
                                                }
                                                if (customAnswer.isNotBlank()) {
                                                    if (isNotEmpty()) append("\n\n")
                                                    append(customAnswer.trim())
                                                }
                                            }.trim()
                                            dismissedQuestionMsgId = lastMsg.id
                                            expandedQuestionMsgId = null
                                            if (replyBody.isNotEmpty()) onSendMessage(replyBody)
                                        }
                                    ) {
                                        Text("답변 전송")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(start = 8.dp, end = 8.dp, top = 1.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = "파일 첨부", modifier = Modifier.size(18.dp))
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (inputText.isEmpty()) {
                            Text(
                                text = if (connection.kind == ConnectionKind.TERMUX_CLI) "Termux 명령어 입력..."
                                else "${connection.name}에게 메시지 입력...",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                maxLines = 1
                            )
                        }
                        BasicTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            maxLines = 4,
                            textStyle = TextStyle(
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Surface(
                    onClick = { onSendMessage("Keep going") },
                    shape = RoundedCornerShape(8.dp),
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_whip_keep),
                            contentDescription = "Keep going",
                            tint = androidx.compose.ui.graphics.Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                if (isSending) {
                    IconButton(onClick = onCancelSending) {
                        Icon(Icons.Default.Stop, contentDescription = "작업 취소", tint = MaterialTheme.colorScheme.error)
                    }
                }
                IconButton(
                    enabled = inputText.isNotBlank() || attachments.isNotEmpty(),
                    onClick = {
                        val text = inputText.trim()
                        val pendingAtts = attachments
                        inputText = ""
                        attachments = emptyList()
                        if (pendingAtts.isEmpty()) {
                            if (text.isNotEmpty()) onSendMessage(text)
                        } else {
                            paneScope.launch {
                                val extra = StringBuilder()
                                val textExts = setOf("txt", "md", "kt", "java", "py", "js", "ts", "json", "xml", "yml", "yaml", "sh", "log", "csv", "html", "css", "c", "cpp", "h", "rs", "go")
                                var webAttached = false
                                for (att in pendingAtts) {
                                    val ext = att.name.substringAfterLast('.', "").lowercase()
                                    if ((att.mimeType.startsWith("text/") || ext in textExts) && att.bytes.size <= 65536) {
                                        extra.append("\n\n[📎 첨부: ${att.name}]\n```\n${att.bytes.toString(Charsets.UTF_8)}\n```")
                                    } else if (connection.usesHiddenWebBridge()) {
                                        val b64 = android.util.Base64.encodeToString(att.bytes, android.util.Base64.NO_WRAP)
                                        val attached = headlessWebBridge.attachFileToWebInputSuspend(
                                            key = connection.id,
                                            url = connection.endpointUrl,
                                            filename = att.name,
                                            mimeType = att.mimeType,
                                            base64Data = b64,
                                            contentUri = att.uri
                                        )
                                        if (attached) {
                                            webAttached = true
                                            kotlinx.coroutines.delay(350L)
                                        } else {
                                            extra.append("\n[📎 첨부 실패: ${att.name}]")
                                        }
                                    } else {
                                        val savedPath = GitBridgeClient.uploadFile(att.name, att.bytes)
                                        if (savedPath != null) {
                                            extra.append("\n[📎 첨부: ${att.name}]\n경로: $savedPath")
                                        } else {
                                            extra.append("\n[📎 첨부 실패: ${att.name}]")
                                        }
                                    }
                                }
                                if (webAttached) kotlinx.coroutines.delay(500L)
                                val combined = (text + extra.toString()).trim().ifBlank { "첨부한 파일을 확인해줘." }
                                onSendMessage(combined)
                            }
                        }
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "전송")
                }
            }
        }
    }

    val inspectedRun = session.subagentRuns.find { it.id == inspectingSubagentId }
    if (inspectedRun != null) {
        val elapsedSec = if (inspectedRun.finishedAt > inspectedRun.startedAt) {
            (inspectedRun.finishedAt - inspectedRun.startedAt) / 1000L
        } else {
            ((System.currentTimeMillis() - inspectedRun.startedAt) / 1000L).coerceAtLeast(0L)
        }
        AlertDialog(
            onDismissRequest = { inspectingSubagentId = null },
            title = {
                Text(
                    text = "서브 에이전트 · ${inspectedRun.label}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 360.dp)
                ) {
                    Text(
                        text = "상태: ${inspectedRun.status} · 모델: ${inspectedRun.model.ifBlank { "기본" }} · ${elapsedSec}초",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("지시 내용", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = inspectedRun.prompt,
                            fontSize = 11.sp,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Text("실행 결과 / 로그", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 180.dp)
                    ) {
                        item {
                            SelectionContainer {
                                Text(
                                    text = inspectedRun.output.ifBlank {
                                        if (inspectedRun.status == "running") "서브 에이전트가 작업 중입니다..." else "(출력 없음)"
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (inspectedRun.status == "running") {
                        TextButton(
                            onClick = {
                                onStopSubagent(inspectedRun.id)
                                inspectingSubagentId = null
                            }
                        ) { Text("중단", color = MaterialTheme.colorScheme.error) }
                    } else {
                        TextButton(
                            onClick = {
                                onRetrySubagent(inspectedRun.id)
                                inspectingSubagentId = null
                            }
                        ) { Text("재실행") }
                    }
                    TextButton(onClick = { inspectingSubagentId = null }) { Text("닫기") }
                }
            }
        )
    }

    if (showRenameDialog) {
        var newTitle by remember(session.id, session.title) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("세션 이름 변경") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("세션 이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = newTitle.isNotBlank(),
                    onClick = {
                        onRenameSession(newTitle.trim())
                        showRenameDialog = false
                    }
                ) { Text("변경") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("취소") }
            }
        )
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
        var smartSummary by remember { mutableStateOf(true) }
        var includeGit by remember { mutableStateOf(project.workDir.isNotBlank()) }
        var autoSend by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showHandoffDialog = false },
            title = { Text("세션 통째로 이관", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "새 탭을 만들지 않고 현재 세션에서 AI/세션을 바로 교체해 이어서 작업합니다.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            onClick = { smartSummary = true },
                            shape = RoundedCornerShape(8.dp),
                            color = if (smartSummary) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (smartSummary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "⚡ 스마트 압축",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (smartSummary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                        Surface(
                            onClick = { smartSummary = false },
                            shape = RoundedCornerShape(8.dp),
                            color = if (!smartSummary) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (!smartSummary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "📄 전체 원문",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (!smartSummary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                    if (project.workDir.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Git 현재 상태(수정 파일·diff) 포함", fontSize = 12.sp)
                            androidx.compose.material3.Switch(checked = includeGit, onCheckedChange = { includeGit = it })
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("교체 후 새 AI에 즉시 인수인계 전송", fontSize = 12.sp)
                        androidx.compose.material3.Switch(checked = autoSend, onCheckedChange = { autoSend = it })
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    Text("이관할 AI 선택", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    enabledConnections.forEach { target ->
                        val isSameAi = target.id == connection?.id
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSameAi) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showHandoffDialog = false
                                    onHandoffTo(target, smartSummary, includeGit, autoSend)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(target.name, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text(
                                    text = if (isSameAi) "새 세션 리프레시" else target.kind.displayLabel(),
                                    fontSize = 10.5.sp,
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

    if (showAttachOrchDialog) {
        var subConnId by remember(session.id) {
            mutableStateOf(session.subConnectionId ?: session.connectionId ?: enabledConnections.firstOrNull()?.id)
        }
        val subConn = enabledConnections.find { it.id == subConnId } ?: connection ?: enabledConnections.firstOrNull()
        val subModels = remember(subConn?.id) { subConn?.let { availableModelsFor(it) } ?: emptyList() }
        var subModel by remember(subConn?.id) {
            mutableStateOf(
                if (session.isOrchestration && session.subConnectionId == subConn?.id && session.subModel.isNotBlank()) {
                    session.subModel
                } else {
                    subModels.lastOrNull { m ->
                        val l = m.lowercase()
                        l.contains("mini") || l.contains("haiku") || l.contains("flash") || l.contains("fast") || l.contains("lite")
                    } ?: subConn?.let { it.model.ifBlank { defaultModelFor(it) } } ?: ""
                }
            )
        }
        val subEfforts = remember(subConn?.id, subModel) {
            if (subConn != null) availableEffortsFor(subConn, subModel) else emptyList()
        }
        var subEffort by remember(subConn?.id, subModel) {
            mutableStateOf(if (session.isOrchestration && session.subConnectionId == subConn?.id) session.subEffort else "")
        }
        var maxParallel by remember(session.id) { mutableStateOf(session.maxParallelSubagents.coerceIn(1, 8)) }
        var maxRounds by remember(session.id) { mutableStateOf(session.maxDelegationRounds.coerceIn(1, 6)) }

        AlertDialog(
            onDismissRequest = { showAttachOrchDialog = false },
            title = {
                Text(
                    text = if (session.isOrchestration) "⚡ 서브 모델 설정" else "⚡ 현재 세션에 서브 모델 연결",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "현재 세션(${connection?.name ?: "메인"})을 메인으로 유지하면서, 필요 시 병렬 호출할 하위 모델을 연결합니다.",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("서브 AI 선택", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        enabledConnections.forEach { c ->
                            val selected = c.id == subConnId
                            Surface(
                                onClick = { subConnId = c.id },
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    text = c.name,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                    if (subModels.isNotEmpty()) {
                        Text("서브 모델 선택", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            subModels.forEach { m ->
                                val selected = m == subModel
                                Surface(
                                    onClick = { subModel = m },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                                ) {
                                    Text(
                                        text = m,
                                        fontSize = 10.sp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                                    )
                                }
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
                                val selected = ef == subEffort
                                Surface(
                                    onClick = { subEffort = if (selected) "" else ef },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                                ) {
                                    Text(
                                        text = ef,
                                        fontSize = 10.sp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
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
                                modifier = Modifier.size(26.dp)
                            ) { Text("-", fontSize = 13.sp) }
                            Text("${maxParallel}개", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            OutlinedButton(
                                onClick = { if (maxParallel < 8) maxParallel++ },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(26.dp)
                            ) { Text("+", fontSize = 13.sp) }
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
                                modifier = Modifier.size(26.dp)
                            ) { Text("-", fontSize = 13.sp) }
                            Text("${maxRounds}회", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            OutlinedButton(
                                onClick = { if (maxRounds < 6) maxRounds++ },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(26.dp)
                            ) { Text("+", fontSize = 13.sp) }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAttachOrchDialog = false
                        onAttachOrchestration(true, subConn?.id, subModel, subEffort, maxParallel, maxRounds)
                    }
                ) {
                    Text(if (session.isOrchestration) "적용" else "서브 연결")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (session.isOrchestration) {
                        TextButton(
                            onClick = {
                                showAttachOrchDialog = false
                                onAttachOrchestration(false, null, "", "", maxParallel, maxRounds)
                            }
                        ) { Text("연결 해제", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { showAttachOrchDialog = false }) { Text("취소") }
                }
            }
        )
    }
}

@Composable
private fun ActionSquareBadge(
    letter: String,
    tint: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit
) {
    val badgeColor = tint ?: when (letter) {
        "C" -> androidx.compose.ui.graphics.Color(0xFF7B93A8)
        "P" -> androidx.compose.ui.graphics.Color(0xFF7D9A84)
        "I" -> androidx.compose.ui.graphics.Color(0xFFA89378)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        color = badgeColor.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.32f)),
        modifier = Modifier.size(22.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = letter,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = badgeColor.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun WavingDotsIndicator(statusText: String? = null) {
    val transition = rememberInfiniteTransition(label = "dots")
    val offsets = listOf(0, 130, 260).map { delayMs ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = -5f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 380),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(delayMs)
            ),
            label = "dot_$delayMs"
        )
    }
    Row(
        modifier = Modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        offsets.forEach { anim ->
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .offset(y = anim.value.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
        if (!statusText.isNullOrBlank()) {
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = statusText,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    isCli: Boolean,
    fontScale: Float = 1f,
    isStreaming: Boolean = false,
    onRetryLast: () -> Unit,
    onQuoteToInput: (String) -> Unit = {},
    onTogglePin: () -> Unit = {}
) {
    when (message.role) {
        MessageRole.SYSTEM -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (message.content.contains("불러오는 중")) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        text = message.content,
                        fontSize = (9.5f * fontScale).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                    Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) {
                        SelectionContainer {
                            Text(
                                text = if (isCli) "$ ${message.content}" else message.content,
                                fontSize = (11.5f * fontScale).sp,
                                fontFamily = if (isCli) FontFamily.Monospace else FontFamily.Default,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ActionSquareBadge("C") { clipboard.setText(AnnotatedString(message.content)) }
                            ActionSquareBadge("P") { onQuoteToInput(message.content) }
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = if (message.isPinned) "핀 해제" else "핀 고정",
                                tint = if (message.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.45f),
                                modifier = Modifier
                                    .size(15.dp)
                                    .clickable { onTogglePin() }
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
                    Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (message.agentName.isNotBlank()) {
                                Text(
                                    text = message.agentName,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Spacer(modifier = Modifier.size(1.dp))
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = if (message.isPinned) "핀 해제" else "핀 고정",
                                    tint = if (message.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                    modifier = Modifier
                                        .size(15.dp)
                                        .clickable { onTogglePin() }
                                )
                                ActionSquareBadge("C") { clipboard.setText(AnnotatedString(message.content)) }
                                ActionSquareBadge("P") { onQuoteToInput(message.content) }
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        if (isStreaming && (message.content == "응답 대기 중..." || message.content == "⏳ 작업 중...")) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(vertical = 2.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(
                                    text = message.content,
                                    fontSize = (10.5f * fontScale).sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            val (displayBody, processStatus) = remember(message.content, isStreaming) {
                                if (!isStreaming) {
                                    message.content to null
                                } else {
                                    val statusRegex = Regex("^(?:🟢\\s*)?(프로세스 활성\\s*·\\s*.+경과)$")
                                    var foundStatus: String? = null
                                    val bodyLines = message.content.lines().filter { line ->
                                        val m = statusRegex.matchEntire(line.trim())
                                        if (m != null) {
                                            foundStatus = m.groupValues[1]
                                            false
                                        } else {
                                            true
                                        }
                                    }
                                    bodyLines.joinToString("\n").trim() to foundStatus
                                }
                            }
                            if (displayBody.isNotEmpty()) {
                                SelectionContainer {
                                    FormattedMessageContent(text = displayBody, isCli = isCli, fontScale = fontScale)
                                }
                            }
                            if (isStreaming) {
                                WavingDotsIndicator(statusText = processStatus)
                            }
                        }
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
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "입력 ${formatTokens(message.inputTokens)} · 출력 ${formatTokens(message.outputTokens)}",
                                fontSize = 9.sp,
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
private fun FormattedMessageContent(text: String, isCli: Boolean, fontScale: Float = 1f) {
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
                    fontSize = (11.5f * fontScale).sp,
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
                    Column(modifier = Modifier.padding(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(lang, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                            ActionSquareBadge("C") { clipboard.setText(AnnotatedString(body)) }
                        }
                        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            Text(
                                text = body,
                                fontSize = (10f * fontScale).sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        val context = LocalContext.current
        val urls = remember(text) {
            Regex("https?://[^\\s<>)\"'`\\]]+").findAll(text).map { it.value.trimEnd('.', ',', ';') }.distinct().take(4).toList()
        }
        val paths = remember(text) {
            Regex("(?:~/|/(?:data|sdcard|storage|home|etc|usr|tmp)/)[^\\s<>)\"'`\\]]+")
                .findAll(text).map { it.value.trimEnd('.', ',', ';') }
                .filter { p -> urls.none { it.contains(p) } }
                .distinct().take(4).toList()
        }
        urls.forEach { u ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = u,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    ActionSquareBadge("C") { clipboard.setText(AnnotatedString(u)) }
                    ActionSquareBadge("I") {
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(u)).apply {
                                setPackage("com.sec.android.app.sbrowser")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }.onFailure {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    }
                }
            }
        }
        paths.forEach { p ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = p,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    ActionSquareBadge("C") { clipboard.setText(AnnotatedString(p)) }
                }
            }
        }
    }
}
