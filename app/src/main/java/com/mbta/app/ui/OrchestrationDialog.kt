package com.mbta.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mbta.app.model.AiConnection

data class OrchestrationStep(
    val connectionId: String,
    val roleInstruction: String
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrchestrationDialog(
    enabledConnections: List<AiConnection>,
    onDismiss: () -> Unit,
    onRun: (goal: String, steps: List<OrchestrationStep>) -> Unit
) {
    var goal by remember { mutableStateOf("") }
    val steps = remember {
        mutableStateListOf<OrchestrationStep>().apply {
            if (enabledConnections.isNotEmpty()) {
                add(OrchestrationStep(enabledConnections[0].id, "초안 · 분석"))
                val second = enabledConnections.getOrElse(1) { enabledConnections[0] }
                add(OrchestrationStep(second.id, "검토 · 개선"))
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("멀티 AI 오케스트레이션") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    label = { Text("목표 / 프롬프트 입력") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(steps) { idx, step ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("${idx + 1}단계", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    if (steps.size > 1) {
                                        IconButton(
                                            onClick = { steps.removeAt(idx) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "단계 삭제", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    enabledConnections.forEach { conn ->
                                        FilterChip(
                                            selected = step.connectionId == conn.id,
                                            onClick = { steps[idx] = step.copy(connectionId = conn.id) },
                                            label = { Text(conn.name, fontSize = 11.sp) }
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = step.roleInstruction,
                                    onValueChange = { steps[idx] = step.copy(roleInstruction = it) },
                                    label = { Text("역할 지시") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                if (enabledConnections.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            val nextConn = enabledConnections[steps.size % enabledConnections.size]
                            steps.add(OrchestrationStep(nextConn.id, "최종 요약"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(4.dp))
                        Text("단계 추가")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = goal.isNotBlank() && steps.isNotEmpty(),
                onClick = { onRun(goal.trim(), steps.toList()) }
            ) {
                Text("파이프라인 실행")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}
