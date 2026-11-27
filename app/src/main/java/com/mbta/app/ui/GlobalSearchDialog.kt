package com.mbta.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mbta.app.model.AgentSession
import com.mbta.app.model.ChatMessage
import com.mbta.app.model.MessageRole
import com.mbta.app.model.Project

private data class SearchHit(
    val project: Project?,
    val session: AgentSession,
    val message: ChatMessage
)

@Composable
fun GlobalSearchDialog(
    projects: List<Project>,
    activeProjectId: String,
    sessions: List<AgentSession>,
    onSelectHit: (projectId: String, sessionId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf(0) } // 0 = 전체, 1 = 핀 고정, 2 = 현재 프로젝트

    val projectById = remember(projects) { projects.associateBy { it.id } }
    val hits = remember(query, filterMode, sessions, activeProjectId) {
        val q = query.trim().lowercase()
        val out = mutableListOf<SearchHit>()
        sessions.forEach { sess ->
            if (filterMode == 2 && sess.projectId != activeProjectId) return@forEach
            val proj = projectById[sess.projectId]
            sess.messages.asReversed().forEach { msg ->
                if (msg.role == MessageRole.SYSTEM) return@forEach
                if (filterMode == 1 && !msg.isPinned) return@forEach
                val matchesQuery = q.isEmpty() ||
                    msg.content.lowercase().contains(q) ||
                    sess.title.lowercase().contains(q) ||
                    msg.agentName.lowercase().contains(q)
                if (matchesQuery) {
                    out.add(SearchHit(proj, sess, msg))
                }
            }
        }
        if (filterMode == 1 || q.isNotEmpty()) out.take(60) else out.take(30)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("통합 검색 · 핀 고정", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("키워드, 세션명, 코드 검색...", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = filterMode == 0,
                        onClick = { filterMode = 0 },
                        label = { Text("전체", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = filterMode == 1,
                        onClick = { filterMode = 1 },
                        label = { Text("핀 고정", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = filterMode == 2,
                        onClick = { filterMode = 2 },
                        label = { Text("현재 프로젝트", fontSize = 11.sp) }
                    )
                }
                if (hits.isEmpty()) {
                    Text(
                        text = if (filterMode == 1) "핀 고정된 메시지가 없습니다. 말풍선 상단의 핀 아이콘을 눌러 고정해 보세요."
                        else "검색 결과가 없습니다.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(hits, key = { "${it.session.id}_${it.message.id}" }) { hit ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectHit(hit.session.projectId, hit.session.id)
                                        onDismiss()
                                    }
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        if (hit.message.isPinned) {
                                            Icon(
                                                Icons.Default.PushPin,
                                                contentDescription = "핀",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                        Text(
                                            text = "${hit.project?.name ?: "프로젝트"} · ${hit.session.title}",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = if (hit.message.role == MessageRole.USER) "나" else hit.message.agentName.ifBlank { "AI" },
                                            fontSize = 9.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = hit.message.content.replace("\n", " ").take(140),
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
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
