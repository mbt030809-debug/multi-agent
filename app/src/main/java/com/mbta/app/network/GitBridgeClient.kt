package com.mbta.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class GitStatusResult(
    val isGit: Boolean = false,
    val branch: String = "",
    val files: List<String> = emptyList(),
    val diff: String = "",
    val ciSummary: String = "",
    val error: String? = null
)

object GitBridgeClient {
    private const val BASE = "http://127.0.0.1:8765"
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE/health").get().build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun cancelSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) return@withContext false
        try {
            val body = buildJsonObject { put("session_id", JsonPrimitive(sessionId)) }.toString()
            val req = Request.Builder().url("$BASE/cancel").post(body.toRequestBody(jsonMedia)).build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun uploadFile(filename: String, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val body = buildJsonObject {
                put("filename", JsonPrimitive(filename))
                put("base64", JsonPrimitive(b64))
            }.toString()
            val req = Request.Builder().url("$BASE/upload").post(body.toRequestBody(jsonMedia)).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val raw = resp.body?.string().orEmpty()
                json.parseToJsonElement(raw).jsonObject["path"]?.jsonPrimitive?.contentOrNull
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchSessionMessages(
        kind: String,
        sessionId: String,
        agentName: String
    ): List<com.mbta.app.model.ChatMessage> = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) return@withContext emptyList()
        try {
            val encoded = java.net.URLEncoder.encode(sessionId, "UTF-8")
            val req = Request.Builder().url("$BASE/session_messages?kind=$kind&id=$encoded").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val raw = resp.body?.string().orEmpty()
                val arr = json.parseToJsonElement(raw).jsonObject["messages"]?.jsonArray ?: return@withContext emptyList()
                arr.mapNotNull { el ->
                    val obj = el.jsonObject
                    val roleStr = obj["role"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (content.isBlank()) return@mapNotNull null
                    val role = if (roleStr == "user") com.mbta.app.model.MessageRole.USER else com.mbta.app.model.MessageRole.ASSISTANT
                    com.mbta.app.model.ChatMessage(
                        id = "msg_${java.util.UUID.randomUUID()}",
                        role = role,
                        content = content,
                        agentName = if (role == com.mbta.app.model.MessageRole.ASSISTANT) agentName else ""
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun fetchProgress(sessionId: String): List<String> = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) return@withContext emptyList()
        try {
            val encoded = java.net.URLEncoder.encode(sessionId, "UTF-8")
            val req = Request.Builder().url("$BASE/progress?session_id=$encoded").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val raw = resp.body?.string().orEmpty()
                val arr = json.parseToJsonElement(raw).jsonObject["lines"]?.jsonArray ?: return@withContext emptyList()
                arr.mapNotNull { it.jsonPrimitive.contentOrNull }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun fetchBridgeSessions(kind: String): List<com.mbta.app.webview.ExternalSessionItem> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE/sessions?kind=$kind").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val raw = resp.body?.string().orEmpty()
                val arr = json.parseToJsonElement(raw).jsonObject["sessions"]?.jsonArray ?: return@withContext emptyList()
                arr.mapNotNull { el ->
                    val obj = el.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: id
                    val updatedAt = obj["updatedAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                    val updatedLabel = obj["updatedLabel"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    com.mbta.app.webview.ExternalSessionItem(
                        id = id,
                        title = title,
                        updatedAt = updatedAt,
                        updatedLabel = updatedLabel
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun fetchStatus(cwd: String): GitStatusResult = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject { put("cwd", JsonPrimitive(cwd)) }.toString()
            val req = Request.Builder().url("$BASE/git/status").post(body.toRequestBody(jsonMedia)).build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val obj = json.parseToJsonElement(raw).jsonObject
                GitStatusResult(
                    isGit = obj["isGit"]?.jsonPrimitive?.booleanOrNull == true,
                    branch = obj["branch"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    files = obj["files"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                    diff = obj["diff"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    ciSummary = obj["ciSummary"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    error = obj["error"]?.jsonPrimitive?.contentOrNull
                )
            }
        } catch (e: Exception) {
            GitStatusResult(isGit = false, error = "로컬 브릿지 연결 실패 (${e.message})")
        }
    }

    suspend fun createWorktree(cwd: String, name: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("cwd", JsonPrimitive(cwd))
                put("name", JsonPrimitive(name))
            }.toString()
            val req = Request.Builder().url("$BASE/git/worktree/create").post(body.toRequestBody(jsonMedia)).build()
            client.newCall(req).execute().use { resp ->
                val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                if (obj["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                    obj["worktreePath"]?.jsonPrimitive?.contentOrNull
                } else null
            }
        }.getOrNull()
    }

    suspend fun commit(cwd: String, message: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("cwd", JsonPrimitive(cwd))
                put("message", JsonPrimitive(message))
            }.toString()
            val req = Request.Builder().url("$BASE/git/commit").post(body.toRequestBody(jsonMedia)).build()
            client.newCall(req).execute().use { resp ->
                val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull == true
                val out = obj["output"]?.jsonPrimitive?.contentOrNull.orEmpty()
                ok to out
            }
        } catch (e: Exception) {
            false to (e.message ?: "오류")
        }
    }

    suspend fun push(cwd: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject { put("cwd", JsonPrimitive(cwd)) }.toString()
            val req = Request.Builder().url("$BASE/git/push").post(body.toRequestBody(jsonMedia)).build()
            client.newCall(req).execute().use { resp ->
                val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull == true
                val out = obj["output"]?.jsonPrimitive?.contentOrNull.orEmpty()
                ok to out
            }
        } catch (e: Exception) {
            false to (e.message ?: "오류")
        }
    }

    data class QuotaInfo(
        val pct: Int?,
        val label: String,
        val fiveHourResetMs: Long = 0L,
        val weekResetMs: Long = 0L
    )

    suspend fun fetchQuota(kind: String, force: Boolean = false): QuotaInfo = withContext(Dispatchers.IO) {
        runCatching {
            val f = if (force) "1" else "0"
            val req = Request.Builder().url("$BASE/quota?kind=$kind&force=$f").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching QuotaInfo(null, "")
                val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                val pct = obj["quotaPercent"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val lbl = obj["quotaLabel"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val r5 = obj["fiveHourResetsAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                val r7 = obj["weekResetsAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                QuotaInfo(pct, lbl, r5, r7)
            }
        }.getOrDefault(QuotaInfo(null, ""))
    }
}
