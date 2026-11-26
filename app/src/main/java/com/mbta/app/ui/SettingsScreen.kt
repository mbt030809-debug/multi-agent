package com.mbta.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mbta.app.model.AiConnection
import com.mbta.app.model.AppThemeMode
import com.mbta.app.model.ConnectionKind
import com.mbta.app.model.CustomSkill
import com.mbta.app.model.RemoteMode
import com.mbta.app.model.autoDetectConnection
import com.mbta.app.model.defaultEndpointFor
import com.mbta.app.model.defaultModelFor
import com.mbta.app.model.displayLabel
import com.mbta.app.model.isHiddenWebBridge
import java.util.UUID

private const val TERMUX_BRIDGE_CMD = "python3 ~/multi-agent/scripts/mbta-bridge.py"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    themeMode: AppThemeMode,
    connections: List<AiConnection>,
    customSkills: List<CustomSkill>,
    loginStatusMap: Map<String, Boolean>,
    onThemeModeChanged: (AppThemeMode) -> Unit,
    onSaveConnection: (AiConnection) -> Unit,
    onDeleteConnection: (String) -> Unit,
    onSaveSkill: (CustomSkill) -> Unit,
    onDeleteSkill: (String) -> Unit,
    onLoginConnection: (AiConnection) -> Unit,
    onBack: () -> Unit
) {
    var editingConnection by remember { mutableStateOf<AiConnection?>(null) }
    var addingKind by remember { mutableStateOf<ConnectionKind?>(null) }
    var showSmartAdd by remember { mutableStateOf(false) }
    var editingSkill by remember { mutableStateOf<CustomSkill?>(null) }
    var showAddSkill by remember { mutableStateOf(false) }
    var copiedBanner by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("앱 테마", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        AppThemeMode.DARK to "다크",
                        AppThemeMode.LIGHT to "라이트",
                        AppThemeMode.SYSTEM to "시스템"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { onThemeModeChanged(mode) },
                            label = { Text(label) }
                        )
                    }
                }
            }

            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("커스텀 / 스킬 (${customSkills.size}개)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    OutlinedButton(onClick = { showAddSkill = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(4.dp))
                        Text("스킬 추가", fontSize = 12.sp)
                    }
                }
            }

            items(customSkills, key = { it.id }) { skill ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${skill.command} · ${skill.title}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            if (skill.description.isNotBlank()) {
                                Text(skill.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { editingSkill = skill }) {
                            Icon(Icons.Default.Edit, contentDescription = "편집", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onDeleteSkill(skill.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "삭제", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Spacer(modifier = Modifier.height(8.dp))
                Text("AI · CLI · Remote 연결 추가", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = { showSmartAdd = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("스마트 연결 추가 (URL 또는 API 키 자동 분류)")
                }
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ConnectionKind.entries.forEach { kind ->
                        OutlinedButton(onClick = { addingKind = kind }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(4.dp))
                            Text(kind.displayLabel(), fontSize = 12.sp)
                        }
                    }
                }
            }

            item {
                Text(
                    "등록된 연결 (${connections.size}개)",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }

            if (connections.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "등록된 연결이 없습니다. 위 버튼을 눌러 Claude, ChatGPT, Gemini, Termux CLI 또는 Remote Control을 추가하세요.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            } else {
                items(connections, key = { it.id }) { conn ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(conn.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = conn.kind.displayLabel(),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                val subtitle = if (conn.model.isNotBlank()) {
                                    conn.model
                                } else {
                                    conn.endpointUrl.ifBlank { defaultEndpointFor(conn.kind) }
                                }
                                Text(
                                    text = subtitle,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (conn.kind.isHiddenWebBridge()) {
                                val isLoggedIn = loginStatusMap[conn.id] == true
                                OutlinedButton(
                                    onClick = { onLoginConnection(conn) },
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Text(
                                        text = if (isLoggedIn) "● 로그인됨" else "○ 로그인 필요",
                                        fontSize = 11.sp,
                                        color = if (isLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Switch(
                                checked = conn.enabled,
                                onCheckedChange = { enabled ->
                                    onSaveConnection(conn.copy(enabled = enabled))
                                }
                            )
                            IconButton(onClick = { editingConnection = conn }) {
                                Icon(Icons.Default.Edit, contentDescription = "편집", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { onDeleteConnection(conn.id) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "삭제",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("커스텀 / 스킬 (${customSkills.size}개)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    OutlinedButton(onClick = { showAddSkill = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(4.dp))
                        Text("스킬 추가", fontSize = 12.sp)
                    }
                }
            }

            items(customSkills, key = { it.id }) { skill ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${skill.command} · ${skill.title}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            if (skill.description.isNotBlank()) {
                                Text(skill.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { editingSkill = skill }) {
                            Icon(Icons.Default.Edit, contentDescription = "편집", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onDeleteSkill(skill.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "삭제", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Termux 로컬 브릿지 실행 명령", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            IconButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(TERMUX_BRIDGE_CMD))
                                    copiedBanner = true
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "복사", modifier = Modifier.size(16.dp))
                            }
                        }
                        Text(
                            text = "Termux에서 아래 명령을 실행하면 MbtA 앱 테마 안에서 바로 CLI 명령을 실행할 수 있습니다.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = TERMUX_BRIDGE_CMD,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 4
                        )
                        if (copiedBanner) {
                            Text(
                                text = "클립보드에 복사되었습니다.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    val initialForDialog = editingConnection ?: addingKind?.let { kind ->
        AiConnection(
            id = "conn_${UUID.randomUUID()}",
            name = kind.displayLabel(),
            kind = kind,
            model = defaultModelFor(kind),
            endpointUrl = defaultEndpointFor(kind),
            enabled = true
        )
    }

    if (initialForDialog != null) {
        ConnectionEditorDialog(
            initial = initialForDialog,
            isNew = editingConnection == null,
            onDismiss = {
                editingConnection = null
                addingKind = null
            },
            onSave = { saved ->
                onSaveConnection(saved)
                editingConnection = null
                addingKind = null
            }
        )
    }

    if (showSmartAdd) {
        SmartAddConnectionDialog(
            onDismiss = { showSmartAdd = false },
            onAdd = { conn, openLogin ->
                onSaveConnection(conn)
                showSmartAdd = false
                if (openLogin && conn.kind.isHiddenWebBridge()) {
                    onLoginConnection(conn)
                }
            }
        )
    }
    if (showAddSkill || editingSkill != null) {
        SkillEditorDialog(
            initial = editingSkill,
            onDismiss = {
                showAddSkill = false
                editingSkill = null
            },
            onSave = { saved ->
                onSaveSkill(saved)
                showAddSkill = false
                editingSkill = null
            }
        )
    }
}

@Composable
private fun SkillEditorDialog(
    initial: CustomSkill?,
    onDismiss: () -> Unit,
    onSave: (CustomSkill) -> Unit
) {
    var command by remember { mutableStateOf(initial?.command ?: "/") }
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var promptTemplate by remember { mutableStateOf(initial?.promptTemplate ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "커스텀 / 스킬 추가" else "스킬 편집") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("명령어 (예: /blog)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("제목") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("짧은 설명 (선택)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = promptTemplate,
                    onValueChange = { promptTemplate = it },
                    label = { Text("자동 입력될 프롬프트") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = command.isNotBlank() && title.isNotBlank() && promptTemplate.isNotBlank(),
                onClick = {
                    val cmd = command.trim().let { if (it.startsWith("/")) it else "/$it" }
                    onSave(
                        CustomSkill(
                            id = initial?.id ?: "skill_${UUID.randomUUID()}",
                            command = cmd,
                            title = title.trim(),
                            description = description.trim(),
                            promptTemplate = promptTemplate
                        )
                    )
                }
            ) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}
    onDismiss: () -> Unit,
    onAdd: (AiConnection, Boolean) -> Unit
) {
    var rawInput by remember { mutableStateOf("") }
    val detected = remember(rawInput) {
        if (rawInput.isBlank()) null else autoDetectConnection(rawInput)
    }
    var customName by remember(detected?.suggestedName) {
        mutableStateOf(detected?.suggestedName ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("스마트 연결 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "URL(Claude, ChatGPT, Gemini, Antigravity Remote 등) 또는 API 키를 붙여넣으면 자동으로 분류됩니다.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = rawInput,
                    onValueChange = { rawInput = it },
                    label = { Text("URL 또는 API 키") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (detected != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "자동 분류: ${detected.kind.displayLabel()}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (detected.apiKey.isNotEmpty()) "API 키 감지됨" else detected.endpointUrl,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("표시 이름") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            val canSave = detected != null
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (detected != null && detected.kind.isHiddenWebBridge()) {
                    Button(
                        onClick = {
                            val conn = AiConnection(
                                id = "conn_${UUID.randomUUID()}",
                                name = customName.trim().ifEmpty { detected.suggestedName },
                                kind = detected.kind,
                                apiKey = detected.apiKey,
                                model = defaultModelFor(detected.kind),
                                endpointUrl = detected.endpointUrl,
                                enabled = true
                            )
                            onAdd(conn, true)
                        }
                    ) {
                        Text("추가 후 로그인")
                    }
                } else {
                    Button(
                        enabled = canSave,
                        onClick = {
                            detected?.let { d ->
                                val conn = AiConnection(
                                    id = "conn_${UUID.randomUUID()}",
                                    name = customName.trim().ifEmpty { d.suggestedName },
                                    kind = d.kind,
                                    apiKey = d.apiKey,
                                    model = defaultModelFor(d.kind),
                                    endpointUrl = d.endpointUrl,
                                    enabled = true
                                )
                                onAdd(conn, false)
                            }
                        }
                    ) {
                        Text("추가")
                    }
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

@Composable
private fun ConnectionEditorDialog(
    initial: AiConnection,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (AiConnection) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var endpointUrl by remember { mutableStateOf(initial.endpointUrl) }
    var remoteMode by remember { mutableStateOf(initial.remoteMode) }
    var showKey by remember { mutableStateOf(false) }

    val isApiKind = initial.kind == ConnectionKind.CLAUDE_API ||
        initial.kind == ConnectionKind.OPENAI_API ||
        initial.kind == ConnectionKind.GEMINI_API
    val isRemoteKind = initial.kind == ConnectionKind.REMOTE_CONTROL
    val isWebBridge = initial.kind.isHiddenWebBridge()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "${initial.kind.displayLabel()} 추가" else "${initial.name} 편집") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("표시 이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!isWebBridge) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(if (isApiKind) "API Key" else "인증 토큰 (선택)") },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "키 보기"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (isApiKind) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("모델명") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = endpointUrl,
                    onValueChange = { endpointUrl = it },
                    label = { Text(if (isApiKind) "엔드포인트 URL (기본값 사용 가능)" else "서버 / 원격 URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isRemoteKind) {
                    Text(
                        "웹 URL 대신 로컬 브릿지(127.0.0.1:8765/agy 또는 /claude)를 사용하면 웹페이지 없이 100% MbtA 네이티브 테마로 대화할 수 있습니다.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            name = "Antigravity"
                            endpointUrl = "http://127.0.0.1:8765/agy"
                            remoteMode = RemoteMode.BRIDGE_API
                        }) { Text("Agy 로컬", fontSize = 11.sp) }
                        TextButton(onClick = {
                            name = "Claude Code"
                            endpointUrl = "http://127.0.0.1:8765/claude"
                            remoteMode = RemoteMode.BRIDGE_API
                        }) { Text("Claude 로컬", fontSize = 11.sp) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            RemoteMode.BRIDGE_API to "네이티브 브릿지",
                            RemoteMode.WEB_EMBED to "웹 원격"
                        ).forEach { (mode, label) ->
                            FilterChip(
                                selected = remoteMode == mode,
                                onClick = { remoteMode = mode },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim().ifEmpty { initial.kind.displayLabel() },
                            apiKey = apiKey.trim(),
                            model = model.trim(),
                            endpointUrl = endpointUrl.trim().ifEmpty { defaultEndpointFor(initial.kind) },
                            remoteMode = remoteMode
                        )
                    )
                }
            ) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}
