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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mbta.app.model.AiConnection
import com.mbta.app.model.labelIn
import com.mbta.app.model.isHiddenWebBridge
import com.mbta.app.network.AiApiClient
import com.mbta.app.webview.HeadlessWebBridge
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

data class FanOutResult(
    val connection: AiConnection,
    val text: String,
    val inTok: Long,
    val outTok: Long,
    val isError: Boolean
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FanOutDialog(
    enabledConnections: List<AiConnection>,
    headlessWebBridge: HeadlessWebBridge,
    onDismiss: () -> Unit,
    onAdopt: (prompt: String, winner: FanOutResult) -> Unit
) {
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    val selectedIds = remember {
        mutableStateListOf<String>().apply {
            enabledConnections.take(2).forEach { add(it.id) }
        }
    }
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<FanOutResult>>(emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("동시 비교 (Fan-Out)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("동시에 보낼 프롬프트") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    enabledConnections.forEach { conn ->
                        val selected = conn.id in selectedIds
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (selected) selectedIds.remove(conn.id) else selectedIds.add(conn.id)
                            },
                            label = { Text(conn.labelIn(enabledConnections), fontSize = 11.sp) }
                        )
                    }
                }
                if (running) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (results.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(results, key = { it.connection.id }) { res ->
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
                                        Text(
                                            res.connection.labelIn(enabledConnections),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = if (res.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                        )
                                        if (!res.isError) {
                                            TextButton(onClick = { onAdopt(prompt.trim(), res) }) {
                                                Text("이 답변 채택", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.padding(2.dp))
                                    Text(res.text, fontSize = 12.sp, maxLines = 8)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !running && prompt.isNotBlank() && selectedIds.size >= 2,
                onClick = {
                    val p = prompt.trim()
                    val targets = enabledConnections.filter { it.id in selectedIds }
                    running = true
                    results = emptyList()
                    scope.launch {
                        val out = coroutineScope {
                            targets.map { conn ->
                                async {
                                    if (conn.kind.isHiddenWebBridge()) {
                                        val (triple, err) = headlessWebBridge.sendPromptSuspend(
                                            key = conn.id,
                                            kind = conn.kind,
                                            url = conn.endpointUrl,
                                            prompt = p
                                        )
                                        FanOutResult(
                                            connection = conn,
                                            text = err ?: triple.first,
                                            inTok = triple.second,
                                            outTok = triple.third,
                                            isError = err != null
                                        )
                                    } else {
                                        val reply = AiApiClient.send(conn, emptyList(), p, "fanout")
                                        FanOutResult(
                                            connection = conn,
                                            text = reply.error ?: reply.text,
                                            inTok = reply.inputTokens,
                                            outTok = reply.outputTokens,
                                            isError = reply.error != null
                                        )
                                    }
                                }
                            }.awaitAll()
                        }
                        results = out
                        running = false
                    }
                }
            ) {
                Text("동시 실행")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}
