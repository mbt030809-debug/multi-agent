package com.mbta.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mbta.app.network.GitBridgeClient
import com.mbta.app.network.GitStatusResult
import kotlinx.coroutines.launch

@Composable
fun GitWorkspaceDialog(
    workDir: String,
    onDismiss: () -> Unit,
    onSendDiffFeedback: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<GitStatusResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var commitMsg by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }
    var actionResult by remember { mutableStateOf<String?>(null) }

    fun reload() {
        loading = true
        scope.launch {
            status = GitBridgeClient.fetchStatus(workDir)
            loading = false
        }
    }

    LaunchedEffect(workDir) { reload() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Git · Diff · CI", fontWeight = FontWeight.Bold)
                IconButton(onClick = { reload() }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "새로고침", modifier = Modifier.size(18.dp))
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                val st = status
                if (st != null) {
                    if (!st.isGit) {
                        Text(st.error ?: "Git 저장소가 아닙니다.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    } else {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("브랜치: ${st.branch} · 변경 ${st.files.size}개", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                if (st.ciSummary.isNotBlank()) {
                                    Text("CI: ${st.ciSummary}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = commitMsg,
                                onValueChange = { commitMsg = it },
                                label = { Text("커밋 메시지") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                enabled = commitMsg.isNotBlank() && st.files.isNotEmpty(),
                                onClick = {
                                    scope.launch {
                                        val (_, out) = GitBridgeClient.commit(workDir, commitMsg.trim())
                                        actionResult = out
                                        commitMsg = ""
                                        reload()
                                    }
                                }
                            ) { Text("커밋", fontSize = 11.sp) }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val (_, out) = GitBridgeClient.push(workDir)
                                        actionResult = out
                                        reload()
                                    }
                                }
                            ) { Text("푸시", fontSize = 11.sp) }
                        }

                        if (!actionResult.isNullOrBlank()) {
                            Text(actionResult.orEmpty(), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }

                        if (st.diff.isNotBlank()) {
                            val diffLines = remember(st.diff) { st.diff.lines().take(200) }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 190.dp)
                            ) {
                                LazyColumn(modifier = Modifier.padding(6.dp)) {
                                    items(diffLines) { line ->
                                        val bg = when {
                                            line.startsWith("+") && !line.startsWith("+++") -> Color(0x2840A02B)
                                            line.startsWith("-") && !line.startsWith("---") -> Color(0x28D20F39)
                                            else -> Color.Transparent
                                        }
                                        val fg = when {
                                            line.startsWith("@@") || line.startsWith("diff --git") -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.onSurface
                                        }
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(bg)
                                                .horizontalScroll(rememberScrollState())
                                        ) {
                                            Text(
                                                text = line,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = fg
                                            )
                                        }
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = feedback,
                                    onValueChange = { feedback = it },
                                    label = { Text("Diff 수정 피드백") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    enabled = feedback.isNotBlank(),
                                    onClick = {
                                        val msg = "아래 Git Diff를 검토하고 피드백을 반영해줘.\n\n[피드백]\n${feedback.trim()}\n\n```diff\n${st.diff.take(6000)}\n```"
                                        onSendDiffFeedback(msg)
                                    }
                                ) { Text("전송", fontSize = 11.sp) }
                            }
                        } else {
                            Text("작업 트리가 깨끗합니다 (변경사항 없음).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}
