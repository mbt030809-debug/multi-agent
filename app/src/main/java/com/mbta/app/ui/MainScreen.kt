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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material.icons.filled.Web
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mbta.app.data.WorkspaceStore
import com.mbta.app.model.AiPanel
import com.mbta.app.model.PanelType
import com.mbta.app.model.SplitMode
import com.mbta.app.model.WorkspacePreset
import com.mbta.app.webview.WebViewManager
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    store: WorkspaceStore,
    webViewManager: WebViewManager,
    onFocusedPanelChanged: (String) -> Unit
) {
    var panels by remember { mutableStateOf(store.loadPanels()) }
    var presets by remember { mutableStateOf(store.loadPresets()) }
    var primaryPanelId by remember { mutableStateOf(store.loadPrimaryPanelId()) }
    var secondaryPanelId by remember { mutableStateOf(store.loadSecondaryPanelId()) }
    var splitMode by remember { mutableStateOf(store.loadSplitMode()) }
    var focusedPane by remember { mutableIntStateOf(0) }

    var showPresetDialog by remember { mutableStateOf(false) }
    var editingPanel by remember { mutableStateOf<AiPanel?>(null) }
    var showAddPanelDialog by remember { mutableStateOf(false) }

    val primaryPanel = panels.find { it.id == primaryPanelId } ?: panels.first()
    val secondaryPanel = panels.find { it.id == secondaryPanelId }
        ?: panels.firstOrNull { it.id != primaryPanel.id }
        ?: primaryPanel

    val activeFocusedPanelId = if (splitMode != SplitMode.SINGLE && focusedPane == 1) {
        secondaryPanel.id
    } else {
        primaryPanel.id
    }
    onFocusedPanelChanged(activeFocusedPanelId)

    fun selectPanel(panelId: String) {
        if (splitMode == SplitMode.SINGLE) {
            primaryPanelId = panelId
            store.savePrimaryPanelId(panelId)
        } else {
            if (focusedPane == 0) {
                if (panelId == secondaryPanel.id) {
                    secondaryPanelId = primaryPanel.id
                    store.saveSecondaryPanelId(primaryPanel.id)
                }
                primaryPanelId = panelId
                store.savePrimaryPanelId(panelId)
            } else {
                if (panelId == primaryPanel.id) {
                    primaryPanelId = secondaryPanel.id
                    store.savePrimaryPanelId(secondaryPanel.id)
                }
                secondaryPanelId = panelId
                store.saveSecondaryPanelId(panelId)
            }
        }
    }

    fun swapPanes() {
        val oldPrimary = primaryPanel.id
        primaryPanelId = secondaryPanel.id
        secondaryPanelId = oldPrimary
        store.savePrimaryPanelId(primaryPanelId)
        store.saveSecondaryPanelId(secondaryPanelId)
    }

    fun cycleSplitMode() {
        val next = when (splitMode) {
            SplitMode.SINGLE -> SplitMode.SPLIT_VERTICAL
            SplitMode.SPLIT_VERTICAL -> SplitMode.SPLIT_HORIZONTAL
            SplitMode.SPLIT_HORIZONTAL -> SplitMode.SINGLE
        }
        splitMode = next
        store.saveSplitMode(next)
        if (next == SplitMode.SINGLE) {
            focusedPane = 0
        }
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    title = {
                        Text(
                            text = "MbtA",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    actions = {
                        if (splitMode != SplitMode.SINGLE) {
                            IconButton(onClick = { swapPanes() }) {
                                Icon(Icons.Default.SwapHoriz, contentDescription = "화면 교체")
                            }
                        }
                        IconButton(onClick = { cycleSplitMode() }) {
                            val icon = when (splitMode) {
                                SplitMode.SINGLE -> Icons.Default.ViewAgenda
                                SplitMode.SPLIT_VERTICAL -> Icons.Default.ViewStream
                                SplitMode.SPLIT_HORIZONTAL -> Icons.Default.ViewColumn
                            }
                            Icon(icon, contentDescription = "화면 분할 전환")
                        }
                        IconButton(onClick = { showPresetDialog = true }) {
                            Icon(Icons.Default.Bookmarks, contentDescription = "워크스페이스 프리셋")
                        }
                        IconButton(onClick = { showAddPanelDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "패널 추가")
                        }
                    }
                )

                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(panels, key = { it.id }) { panel ->
                        val isSelected = if (splitMode == SplitMode.SINGLE) {
                            panel.id == primaryPanel.id
                        } else {
                            panel.id == primaryPanel.id || panel.id == secondaryPanel.id
                        }
                        val typeIcon = when (panel.type) {
                            PanelType.AI_WEB -> Icons.Default.SmartToy
                            PanelType.TERMUX_CLI -> Icons.Default.Terminal
                            PanelType.REMOTE_CONTROL -> Icons.Default.Web
                        }
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectPanel(panel.id) },
                            label = { Text(panel.name, maxLines = 1) },
                            leadingIcon = {
                                Icon(
                                    imageVector = typeIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
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
            when (splitMode) {
                SplitMode.SINGLE -> {
                    WebViewPane(
                        panel = primaryPanel,
                        isFocused = true,
                        showFocusBorder = false,
                        webViewManager = webViewManager,
                        onFocus = { focusedPane = 0 },
                        onEdit = { editingPanel = primaryPanel },
                        onToggleDesktop = { updated ->
                            panels = panels.map { if (it.id == updated.id) updated else it }
                            store.savePanels(panels)
                            webViewManager.setDesktopMode(updated.id, updated.desktopMode)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                SplitMode.SPLIT_VERTICAL -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        WebViewPane(
                            panel = primaryPanel,
                            isFocused = focusedPane == 0,
                            showFocusBorder = true,
                            webViewManager = webViewManager,
                            onFocus = { focusedPane = 0 },
                            onEdit = { editingPanel = primaryPanel },
                            onToggleDesktop = { updated ->
                                panels = panels.map { if (it.id == updated.id) updated else it }
                                store.savePanels(panels)
                                webViewManager.setDesktopMode(updated.id, updated.desktopMode)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                        HorizontalDivider(
                            thickness = 2.dp,
                            color = MaterialTheme.colorScheme.outline
                        )
                        WebViewPane(
                            panel = secondaryPanel,
                            isFocused = focusedPane == 1,
                            showFocusBorder = true,
                            webViewManager = webViewManager,
                            onFocus = { focusedPane = 1 },
                            onEdit = { editingPanel = secondaryPanel },
                            onToggleDesktop = { updated ->
                                panels = panels.map { if (it.id == updated.id) updated else it }
                                store.savePanels(panels)
                                webViewManager.setDesktopMode(updated.id, updated.desktopMode)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                }
                SplitMode.SPLIT_HORIZONTAL -> {
                    Row(modifier = Modifier.fillMaxSize()) {
                        WebViewPane(
                            panel = primaryPanel,
                            isFocused = focusedPane == 0,
                            showFocusBorder = true,
                            webViewManager = webViewManager,
                            onFocus = { focusedPane = 0 },
                            onEdit = { editingPanel = primaryPanel },
                            onToggleDesktop = { updated ->
                                panels = panels.map { if (it.id == updated.id) updated else it }
                                store.savePanels(panels)
                                webViewManager.setDesktopMode(updated.id, updated.desktopMode)
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                        )
                        VerticalDivider(
                            thickness = 2.dp,
                            color = MaterialTheme.colorScheme.outline
                        )
                        WebViewPane(
                            panel = secondaryPanel,
                            isFocused = focusedPane == 1,
                            showFocusBorder = true,
                            webViewManager = webViewManager,
                            onFocus = { focusedPane = 1 },
                            onEdit = { editingPanel = secondaryPanel },
                            onToggleDesktop = { updated ->
                                panels = panels.map { if (it.id == updated.id) updated else it }
                                store.savePanels(panels)
                                webViewManager.setDesktopMode(updated.id, updated.desktopMode)
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                        )
                    }
                }
            }
        }
    }

    if (showAddPanelDialog || editingPanel != null) {
        AddEditPanelDialog(
            initial = editingPanel,
            canDelete = editingPanel != null && panels.size > 1,
            onDismiss = {
                showAddPanelDialog = false
                editingPanel = null
            },
            onSave = { saved ->
                val exists = panels.any { it.id == saved.id }
                panels = if (exists) {
                    panels.map { if (it.id == saved.id) saved else it }
                } else {
                    panels + saved
                }
                store.savePanels(panels)
                if (exists) {
                    webViewManager.loadUrl(saved.id, saved.url)
                } else {
                    selectPanel(saved.id)
                }
                showAddPanelDialog = false
                editingPanel = null
            },
            onDelete = { toDelete ->
                panels = panels.filterNot { it.id == toDelete.id }
                store.savePanels(panels)
                webViewManager.removeWebView(toDelete.id)
                if (primaryPanelId == toDelete.id) {
                    primaryPanelId = panels.first().id
                    store.savePrimaryPanelId(primaryPanelId)
                }
                if (secondaryPanelId == toDelete.id) {
                    secondaryPanelId = panels.first().id
                    store.saveSecondaryPanelId(secondaryPanelId)
                }
                editingPanel = null
            }
        )
    }

    if (showPresetDialog) {
        WorkspacePresetDialog(
            presets = presets,
            panels = panels,
            onDismiss = { showPresetDialog = false },
            onApply = { preset ->
                splitMode = preset.splitMode
                primaryPanelId = preset.primaryPanelId
                secondaryPanelId = preset.secondaryPanelId ?: secondaryPanelId
                store.saveSplitMode(splitMode)
                store.savePrimaryPanelId(primaryPanelId)
                store.saveSecondaryPanelId(secondaryPanelId)
                focusedPane = 0
                showPresetDialog = false
            },
            onSaveCurrent = { name ->
                val newPreset = WorkspacePreset(
                    id = "preset_${UUID.randomUUID()}",
                    name = name,
                    primaryPanelId = primaryPanel.id,
                    secondaryPanelId = if (splitMode == SplitMode.SINGLE) null else secondaryPanel.id,
                    splitMode = splitMode
                )
                presets = presets + newPreset
                store.savePresets(presets)
            },
            onDelete = { preset ->
                presets = presets.filterNot { it.id == preset.id }
                store.savePresets(presets)
            }
        )
    }
}

@Composable
private fun WebViewPane(
    panel: AiPanel,
    isFocused: Boolean,
    showFocusBorder: Boolean,
    webViewManager: WebViewManager,
    onFocus: () -> Unit,
    onEdit: () -> Unit,
    onToggleDesktop: (AiPanel) -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (showFocusBorder && isFocused) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    }

    Column(
        modifier = modifier.border(
            width = if (showFocusBorder && isFocused) 1.5.dp else 0.dp,
            color = borderColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(
                    if (isFocused && showFocusBorder) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                )
                .clickable { onFocus() }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = panel.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { webViewManager.goBack(panel.id) },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "뒤로",
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(
                onClick = { webViewManager.reload(panel.id) },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "새로고침",
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(
                onClick = { onToggleDesktop(panel.copy(desktopMode = !panel.desktopMode)) },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = if (panel.desktopMode) Icons.Default.Computer else Icons.Default.PhoneAndroid,
                    contentDescription = "데스크톱 모드 전환",
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "패널 편집",
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        key(panel.id) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val webView = webViewManager.getOrCreateWebView(panel)
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    FrameLayout(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        addView(webView)
                    }
                },
                update = { container ->
                    val webView = webViewManager.getOrCreateWebView(panel)
                    if (webView.parent !== container) {
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        container.removeAllViews()
                        container.addView(webView)
                    }
                }
            )
        }
    }
}

@Composable
private fun AddEditPanelDialog(
    initial: AiPanel?,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (AiPanel) -> Unit,
    onDelete: (AiPanel) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "https://") }
    var type by remember { mutableStateOf(initial?.type ?: PanelType.AI_WEB) }
    var desktopMode by remember { mutableStateOf(initial?.desktopMode ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "패널 추가" else "패널 편집") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL (예: https://claude.ai 또는 http://127.0.0.1:7681)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PanelType.entries.forEach { entry ->
                        val label = when (entry) {
                            PanelType.AI_WEB -> "AI 웹"
                            PanelType.TERMUX_CLI -> "Termux"
                            PanelType.REMOTE_CONTROL -> "Remote"
                        }
                        FilterChip(
                            selected = type == entry,
                            onClick = { type = entry },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("데스크톱 User-Agent")
                    Switch(checked = desktopMode, onCheckedChange = { desktopMode = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedName = name.trim().ifEmpty { "AI 패널" }
                    val trimmedUrl = url.trim().let {
                        if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
                    }
                    onSave(
                        AiPanel(
                            id = initial?.id ?: "panel_${UUID.randomUUID()}",
                            name = trimmedName,
                            url = trimmedUrl,
                            type = type,
                            desktopMode = desktopMode
                        )
                    )
                }
            ) {
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
private fun WorkspacePresetDialog(
    presets: List<WorkspacePreset>,
    panels: List<AiPanel>,
    onDismiss: () -> Unit,
    onApply: (WorkspacePreset) -> Unit,
    onSaveCurrent: (String) -> Unit,
    onDelete: (WorkspacePreset) -> Unit
) {
    var newPresetName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("워크스페이스 프리셋") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = newPresetName,
                        onValueChange = { newPresetName = it },
                        label = { Text("현재 레이아웃 저장") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = {
                            val trimmed = newPresetName.trim()
                            if (trimmed.isNotEmpty()) {
                                onSaveCurrent(trimmed)
                                newPresetName = ""
                            }
                        }
                    ) {
                        Text("저장")
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(presets, key = { it.id }) { preset ->
                        val p1 = panels.find { it.id == preset.primaryPanelId }?.name ?: preset.primaryPanelId
                        val p2 = preset.secondaryPanelId?.let { id ->
                            panels.find { it.id == id }?.name ?: id
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onApply(preset) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(preset.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                    val desc = if (preset.splitMode == SplitMode.SINGLE || p2 == null) {
                                        p1
                                    } else {
                                        "$p1 + $p2"
                                    }
                                    Text(
                                        desc,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = { onDelete(preset) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "프리셋 삭제",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기")
            }
        }
    )
}
