package com.mbta.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mbta.app.model.AgentTokenUsage
import com.mbta.app.model.AiConnection
import com.mbta.app.model.TokenSource
import com.mbta.app.model.defaultContextLimitForKind
import com.mbta.app.model.displayLabel
import com.mbta.app.model.formatTokens

@Composable
fun TokenDashboardDialog(
    connections: List<AiConnection>,
    tokenUsageMap: Map<String, AgentTokenUsage>,
    onResetAgent: (String) -> Unit,
    onResetAll: () -> Unit,
    onSetCustomLimit: (String, Long?) -> Unit = { _, _ -> },
    onDismiss: () -> Unit
) {
    val totalInput = tokenUsageMap.values.sumOf { it.inputTokens }
    val totalOutput = tokenUsageMap.values.sumOf { it.outputTokens }
    val totalAll = totalInput + totalOutput

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("에이전트별 토큰 사용량") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("전체 합계", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                formatTokens(totalAll),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            "입력 ${formatTokens(totalInput)} / 출력 ${formatTokens(totalOutput)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (connections.isEmpty()) {
                    Text(
                        text = "설정에서 AI 또는 CLI 연결을 등록하면 에이전트별 토큰 사용량이 표시됩니다.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(connections, key = { it.id }) { conn ->
                            val usage = tokenUsageMap[conn.id] ?: AgentTokenUsage(
                                panelId = conn.id,
                                contextLimit = defaultContextLimitForKind(conn.kind),
                                source = TokenSource.API_EXACT
                            )
                            val limit = usage.customLimit ?: usage.contextLimit ?: defaultContextLimitForKind(conn.kind)

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = conn.name,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                text = conn.kind.displayLabel(),
                                                fontSize = 10.sp,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { onResetAgent(conn.id) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.RestartAlt,
                                                contentDescription = "초기화",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = "입력 ${formatTokens(usage.inputTokens)} · 출력 ${formatTokens(usage.outputTokens)} · 합계 ${formatTokens(usage.totalTokens)}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    if (usage.quotaPercent != null) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        val progress = (usage.quotaPercent / 100f).coerceIn(0f, 1f)
                                        LinearProgressIndicator(
                                            progress = { progress },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(5.dp)
                                        )
                                        Text(
                                            text = "${usage.quotaLabel.ifBlank { "계정 한도" }}: ${usage.quotaPercent}%",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    } else if (limit != null && limit > 0L) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        val progress = (usage.totalTokens.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
                                        LinearProgressIndicator(
                                            progress = { progress },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(5.dp)
                                        )
                                        Text(
                                            text = "한도 ${formatTokens(limit)} 중 ${(progress * 100).toInt()}%",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.padding(top = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(100_000L to "100K", 500_000L to "500K", 1_000_000L to "1M", 5_000_000L to "5M").forEach { (valLim, lbl) ->
                                            val sel = usage.customLimit == valLim
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                                modifier = Modifier.padding(vertical = 2.dp)
                                            ) {
                                                TextButton(
                                                    onClick = { onSetCustomLimit(conn.id, if (sel) null else valLim) },
                                                    modifier = Modifier.height(24.dp),
                                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                                ) {
                                                    Text(lbl, fontSize = 10.sp)
                                                }
                                            }
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
            TextButton(onClick = onDismiss) {
                Text("닫기")
            }
        },
        dismissButton = {
            TextButton(onClick = onResetAll) {
                Text("전체 초기화", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}
