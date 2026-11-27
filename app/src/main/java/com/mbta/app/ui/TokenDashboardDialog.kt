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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mbta.app.model.AgentTokenUsage
import com.mbta.app.model.AiConnection
import com.mbta.app.model.TokenSource
import com.mbta.app.model.defaultContextLimitFor
import com.mbta.app.model.displayLabel
import com.mbta.app.model.formatTokens
import kotlin.math.roundToInt

@Composable
fun TokenDashboardDialog(
    connections: List<AiConnection>,
    tokenUsageMap: Map<String, AgentTokenUsage>,
    onResetAgent: (String) -> Unit,
    onResetAll: () -> Unit,
    onRefreshQuotas: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val totalInput = tokenUsageMap.values.sumOf { it.inputTokens }
    val totalOutput = tokenUsageMap.values.sumOf { it.outputTokens }
    val totalAll = totalInput + totalOutput

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("에이전트별 잔여 한도 · 사용량", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
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
                                source = TokenSource.API_EXACT
                            )
                            val customLim = usage.customLimit
                            val remPctFloat: Float? = when {
                                usage.quotaPercent != null -> usage.quotaPercent.toFloat().coerceIn(0f, 100f)
                                customLim != null && customLim > 0L -> (((customLim - usage.totalTokens).coerceAtLeast(0L).toDouble() / customLim.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f)
                                else -> null
                            }
                            val weeklyPct: Int? = remember(usage.quotaLabel) {
                                Regex("주간\\s*(\\d{1,3})%").find(usage.quotaLabel)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)
                            }
                            val remLabel = when {
                                usage.quotaPercent != null -> "5h ${usage.quotaPercent}%"
                                remPctFloat != null -> "남음 ${remPctFloat.roundToInt()}%"
                                else -> null
                            }
                            val remColor = when {
                                remPctFloat != null && remPctFloat <= 15f -> MaterialTheme.colorScheme.error
                                remPctFloat != null && remPctFloat <= 35f -> androidx.compose.ui.graphics.Color(0xFFFF9800)
                                else -> MaterialTheme.colorScheme.primary
                            }
                            val weeklyColor = when {
                                weeklyPct != null && weeklyPct <= 15 -> MaterialTheme.colorScheme.error
                                weeklyPct != null && weeklyPct <= 35 -> androidx.compose.ui.graphics.Color(0xFFFF9800)
                                else -> MaterialTheme.colorScheme.secondary
                            }

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
                                        if (remLabel != null) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = remColor.copy(alpha = 0.14f),
                                                modifier = Modifier.padding(end = 4.dp)
                                            ) {
                                                Text(
                                                    text = remLabel,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = remColor,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
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

                                    if (remPctFloat != null) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = if (usage.quotaPercent != null) "5시간 한도 · 남음 ${usage.quotaPercent}%" else "사용자 한도 · ${remLabel ?: ""}",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = remColor
                                        )
                                        LinearProgressIndicator(
                                            progress = { (remPctFloat / 100f).coerceIn(0f, 1f) },
                                            color = remColor,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 2.dp)
                                                .height(5.dp)
                                        )
                                        if (weeklyPct != null) {
                                            Spacer(modifier = Modifier.height(5.dp))
                                            Text(
                                                text = "일주일 한도 · 남음 ${weeklyPct}%",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = weeklyColor
                                            )
                                            LinearProgressIndicator(
                                                progress = { (weeklyPct / 100f).coerceIn(0f, 1f) },
                                                color = weeklyColor,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 2.dp)
                                                    .height(5.dp)
                                            )
                                        }
                                        if (usage.quotaLabel.isNotBlank()) {
                                            Text(
                                                text = usage.quotaLabel,
                                                fontSize = 9.5.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 3.dp)
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = "실제 잔여량 미제공 (누적 사용량만 표시)",
                                            fontSize = 9.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRefreshQuotas) {
                    Text("새로고침")
                }
                TextButton(onClick = onDismiss) {
                    Text("닫기")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onResetAll) {
                Text("전체 초기화", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}
