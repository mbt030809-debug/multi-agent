package com.mbta.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mbta.app.model.AiConnection
import com.mbta.app.model.SubAgentConfig
import com.mbta.app.model.availableEffortsFor
import com.mbta.app.model.availableModelsFor
import com.mbta.app.model.defaultModelFor
import com.mbta.app.model.labelIn
import java.util.UUID

private const val MAX_SUBS = 5

private fun newUid(): String = UUID.randomUUID().toString().take(8)

/** 비어 있는 가장 작은 번호의 서브를 추가 (남아 있는 서브의 id 는 바뀌지 않음) */
internal fun newSubAgent(existing: List<SubAgentConfig>, connection: AiConnection): SubAgentConfig {
    val used = existing.map { it.id }.toSet()
    val id = generateSequence(1) { it + 1 }.map { "s$it" }.first { it !in used }
    return SubAgentConfig(id = id, uid = newUid(), connectionId = connection.id, model = defaultModelFor(connection))
}

@Composable
private fun PickChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Text(
            text = text,
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

/** 서브 목록 편집: 서브마다 AI·모델·effort·역할 */
@Composable
internal fun SubAgentEditor(
    subs: List<SubAgentConfig>,
    connections: List<AiConnection>,
    onChange: (List<SubAgentConfig>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        subs.forEachIndexed { index, sub ->
            fun update(next: SubAgentConfig) = onChange(subs.mapIndexed { i, s -> if (i == index) next else s })
            val conn = connections.find { it.id == sub.connectionId }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("서브 ${sub.id}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        if (subs.size > 1) {
                            TextButton(onClick = { onChange(subs.filterIndexed { i, _ -> i != index }) }) {
                                Text("삭제", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    ChipRow {
                        connections.forEach { c ->
                            PickChip(c.labelIn(connections), c.id == sub.connectionId) {
                                // AI를 바꾸면 이전 대화는 이어갈 수 없으므로 새 대화로 시작
                                if (c.id != sub.connectionId) {
                                    update(sub.resetConversation(newUid()).copy(connectionId = c.id, model = defaultModelFor(c), effort = ""))
                                }
                            }
                        }
                    }
                    val models = conn?.let { availableModelsFor(it) }.orEmpty()
                    if (models.isNotEmpty()) {
                        ChipRow { models.forEach { m -> PickChip(m, m == sub.model) { update(sub.copy(model = m, effort = "")) } } }
                    }
                    val efforts = conn?.let { availableEffortsFor(it, sub.model) }.orEmpty()
                    if (efforts.isNotEmpty()) {
                        ChipRow {
                            Text("Effort:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            efforts.forEach { e -> PickChip(e, e == sub.effort) { update(sub.copy(effort = if (sub.effort == e) "" else e)) } }
                        }
                    }
                    OutlinedTextField(
                        value = sub.role,
                        onValueChange = { update(sub.copy(role = it.take(60))) },
                        label = { Text("역할 (예: 조사, 작성, 검토)", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        if (subs.size < MAX_SUBS && connections.isNotEmpty()) {
            TextButton(onClick = { onChange(subs + newSubAgent(subs, connections.first())) }) {
                Text("+ 서브 추가", fontSize = 12.sp)
            }
        }
    }
}

/** 반복 한도와 위임 기준 */
@Composable
internal fun OrchLimits(maxRounds: Int, onMaxRounds: (Int) -> Unit, sensitivity: String, onSensitivity: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("최대 지휘 반복", fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { if (maxRounds > 1) onMaxRounds(maxRounds - 1) }) { Text("-", fontSize = 15.sp) }
                Text("${maxRounds}회", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { if (maxRounds < 6) onMaxRounds(maxRounds + 1) }) { Text("+", fontSize = 15.sp) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("서브에게 맡기기", fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("low" to "낮음", "medium" to "보통", "high" to "높음").forEach { (key, label) ->
                    PickChip(label, sensitivity == key) { onSensitivity(key) }
                }
            }
        }
        Text(
            "작은 작업은 메인이 직접 처리합니다. 웹 서브는 하나씩 실행합니다.",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
