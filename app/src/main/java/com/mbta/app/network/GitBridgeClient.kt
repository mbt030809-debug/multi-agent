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
}
