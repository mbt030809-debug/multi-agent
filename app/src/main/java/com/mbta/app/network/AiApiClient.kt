package com.mbta.app.network

import com.mbta.app.model.AiConnection
import com.mbta.app.model.ChatMessage
import com.mbta.app.model.ConnectionKind
import com.mbta.app.model.MessageRole
import com.mbta.app.model.defaultEndpointFor
import com.mbta.app.model.defaultModelFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class AiReply(
    val text: String,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val quotaPercent: Int? = null,
    val quotaLabel: String = "",
    val error: String? = null
)

object AiApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchAvailableModels(connection: AiConnection): List<String> = withContext(Dispatchers.IO) {
        val fallback = com.mbta.app.model.availableModelsFor(connection)
        try {
            when (connection.kind) {
                ConnectionKind.REMOTE_CONTROL -> {
                    val kind = if (connection.endpointUrl.lowercase().contains("/claude")) "claude" else "agy"
                    val req = Request.Builder().url("http://127.0.0.1:8765/models?kind=$kind").get().build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext fallback
                        val arr = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject["models"]?.jsonArray
                        val list = arr?.mapNotNull { it.jsonPrimitive.contentOrNull }?.filter { it.isNotBlank() }.orEmpty()
                        list.ifEmpty { fallback }
                    }
                }
                ConnectionKind.OPENAI_API -> {
                    if (connection.apiKey.isBlank()) return@withContext fallback
                    val req = Request.Builder()
                        .url("https://api.openai.com/v1/models")
                        .addHeader("Authorization", "Bearer ${connection.apiKey.trim()}")
                        .get()
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext fallback
                        val arr = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject["data"]?.jsonArray
                        val list = arr?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                            ?.filter { it.matches(Regex("^(gpt-|o1|o3|o4|chatgpt-).*")) }
                            ?.sortedDescending()
                            ?.take(20)
                            .orEmpty()
                        list.ifEmpty { fallback }
                    }
                }
                ConnectionKind.CLAUDE_API -> {
                    if (connection.apiKey.isBlank()) return@withContext fallback
                    val req = Request.Builder()
                        .url("https://api.anthropic.com/v1/models")
                        .addHeader("x-api-key", connection.apiKey.trim())
                        .addHeader("anthropic-version", "2023-06-01")
                        .get()
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext fallback
                        val arr = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject["data"]?.jsonArray
                        val list = arr?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                            ?.take(20)
                            .orEmpty()
                        list.ifEmpty { fallback }
                    }
                }
                ConnectionKind.GEMINI_API -> {
                    if (connection.apiKey.isBlank()) return@withContext fallback
                    val req = Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models?key=${connection.apiKey.trim()}")
                        .get()
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext fallback
                        val arr = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject["models"]?.jsonArray
                        val list = arr?.mapNotNull { el ->
                            val obj = el.jsonObject
                            val methods = obj["supportedGenerationMethods"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                            if ("generateContent" in methods) {
                                obj["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/")
                            } else null
                        }?.take(20).orEmpty()
                        list.ifEmpty { fallback }
                    }
                }
                else -> fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

    suspend fun send(
        connection: AiConnection,
        history: List<ChatMessage>,
        userMessage: String,
        sessionId: String = "",
        cwd: String = "",
        cliSessionId: String = "",
        effort: String = ""
    ): AiReply = withContext(Dispatchers.IO) {
        if (connection.kind != ConnectionKind.TERMUX_CLI &&
            connection.kind != ConnectionKind.REMOTE_CONTROL &&
            connection.apiKey.isBlank()
        ) {
            return@withContext AiReply(
                text = "",
                error = "${connection.name}의 API 키가 설정되지 않았습니다. 설정에서 API 키를 입력해주세요."
            )
        }

        try {
            when (connection.kind) {
                ConnectionKind.CLAUDE_API -> sendClaude(connection, history, userMessage, effort)
                ConnectionKind.OPENAI_API -> sendOpenAi(connection, history, userMessage, effort)
                ConnectionKind.GEMINI_API -> sendGemini(connection, history, userMessage)
                ConnectionKind.TERMUX_CLI -> sendTermuxCli(connection, userMessage)
                ConnectionKind.REMOTE_CONTROL -> sendRemoteControl(connection, history, userMessage, sessionId, cwd, cliSessionId, effort)
                else -> AiReply(text = "", error = "지원하지 않는 연결 형식입니다.")
            }
        } catch (e: Exception) {
            AiReply(text = "", error = "요청 오류: ${e.message ?: "알 수 없는 오류"}")
        }
    }

    private fun sendClaude(
        connection: AiConnection,
        history: List<ChatMessage>,
        userMessage: String,
        effort: String = ""
    ): AiReply {
        val url = connection.endpointUrl.ifBlank { defaultEndpointFor(ConnectionKind.CLAUDE_API) }
        val model = connection.model.ifBlank { defaultModelFor(ConnectionKind.CLAUDE_API) }
        val systemText = history.filter { it.role == MessageRole.SYSTEM }
            .joinToString("\n\n") { it.content }

        val messagesArray = buildJsonArray {
            history.filter { it.role != MessageRole.SYSTEM }.forEach { msg ->
                add(buildJsonObject {
                    put("role", JsonPrimitive(if (msg.role == MessageRole.USER) "user" else "assistant"))
                    put("content", JsonPrimitive(msg.content))
                })
            }
            add(buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", JsonPrimitive(userMessage))
            })
        }

        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("max_tokens", JsonPrimitive(4096))
            if (systemText.isNotBlank()) {
                put("system", JsonPrimitive(systemText))
            }
            put("messages", messagesArray)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", connection.apiKey.trim())
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return AiReply(text = "", error = "Claude API 오류 (${response.code}): ${extractError(bodyStr)}")
            }
            val root = json.parseToJsonElement(bodyStr).jsonObject
            val text = root["content"]?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString("")
                .orEmpty()
            val usage = root["usage"]?.jsonObject
            val inTok = usage?.get("input_tokens")?.jsonPrimitive?.longOrNull ?: 0L
            val outTok = usage?.get("output_tokens")?.jsonPrimitive?.longOrNull ?: 0L
            val rlLimit = response.header("anthropic-ratelimit-tokens-limit")?.toLongOrNull()
            val rlRem = response.header("anthropic-ratelimit-tokens-remaining")?.toLongOrNull()
            val quotaPct = if (rlLimit != null && rlRem != null && rlLimit > 0L) {
                ((rlRem * 100L) / rlLimit).toInt().coerceIn(0, 100)
            } else null
            return AiReply(
                text = text,
                inputTokens = inTok,
                outputTokens = outTok,
                quotaPercent = quotaPct,
                quotaLabel = if (quotaPct != null) "Claude API" else ""
            )
        }
    }

    private fun sendOpenAi(
        connection: AiConnection,
        history: List<ChatMessage>,
        userMessage: String,
        effort: String = ""
    ): AiReply {
        val url = connection.endpointUrl.ifBlank { defaultEndpointFor(ConnectionKind.OPENAI_API) }
        val model = connection.model.ifBlank { defaultModelFor(ConnectionKind.OPENAI_API) }

        val messagesArray = buildJsonArray {
            history.forEach { msg ->
                val roleStr = when (msg.role) {
                    MessageRole.SYSTEM -> "system"
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                }
                add(buildJsonObject {
                    put("role", JsonPrimitive(roleStr))
                    put("content", JsonPrimitive(msg.content))
                })
            }
            add(buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", JsonPrimitive(userMessage))
            })
        }

        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            if (effort.isNotBlank()) {
                put("reasoning_effort", JsonPrimitive(effort.lowercase()))
            }
            put("messages", messagesArray)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${connection.apiKey.trim()}")
            .addHeader("content-type", "application/json")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return AiReply(text = "", error = "OpenAI API 오류 (${response.code}): ${extractError(bodyStr)}")
            }
            val root = json.parseToJsonElement(bodyStr).jsonObject
            val text = root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")
                ?.jsonPrimitive?.contentOrNull.orEmpty()
            val usage = root["usage"]?.jsonObject
            val inTok = usage?.get("prompt_tokens")?.jsonPrimitive?.longOrNull ?: 0L
            val outTok = usage?.get("completion_tokens")?.jsonPrimitive?.longOrNull ?: 0L
            val rlLimit = response.header("anthropic-ratelimit-tokens-limit")?.toLongOrNull()
            val rlRem = response.header("anthropic-ratelimit-tokens-remaining")?.toLongOrNull()
            val quotaPct = if (rlLimit != null && rlRem != null && rlLimit > 0L) {
                ((rlRem * 100L) / rlLimit).toInt().coerceIn(0, 100)
            } else null
            return AiReply(
                text = text,
                inputTokens = inTok,
                outputTokens = outTok,
                quotaPercent = quotaPct,
                quotaLabel = if (quotaPct != null) "Claude API" else ""
            )
        }
    }

    private fun sendGemini(
        connection: AiConnection,
        history: List<ChatMessage>,
        userMessage: String
    ): AiReply {
        val baseUrl = connection.endpointUrl.ifBlank { defaultEndpointFor(ConnectionKind.GEMINI_API) }.trimEnd('/')
        val model = connection.model.ifBlank { defaultModelFor(ConnectionKind.GEMINI_API) }
        val url = "$baseUrl/models/$model:generateContent?key=${connection.apiKey.trim()}"

        val systemText = history.filter { it.role == MessageRole.SYSTEM }
            .joinToString("\n\n") { it.content }

        val contentsArray = buildJsonArray {
            history.filter { it.role != MessageRole.SYSTEM }.forEach { msg ->
                add(buildJsonObject {
                    put("role", JsonPrimitive(if (msg.role == MessageRole.USER) "user" else "model"))
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", JsonPrimitive(msg.content)) })
                    })
                })
            }
            add(buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", JsonPrimitive(userMessage)) })
                })
            })
        }

        val payload = buildJsonObject {
            if (systemText.isNotBlank()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", JsonPrimitive(systemText)) })
                    })
                })
            }
            put("contents", contentsArray)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .addHeader("content-type", "application/json")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return AiReply(text = "", error = "Gemini API 오류 (${response.code}): ${extractError(bodyStr)}")
            }
            val root = json.parseToJsonElement(bodyStr).jsonObject
            val parts = root["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
            val text = parts?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString("").orEmpty()
            val usage = root["usageMetadata"]?.jsonObject
            val inTok = usage?.get("promptTokenCount")?.jsonPrimitive?.longOrNull ?: 0L
            val outTok = usage?.get("candidatesTokenCount")?.jsonPrimitive?.longOrNull ?: 0L
            val rlLimit = response.header("anthropic-ratelimit-tokens-limit")?.toLongOrNull()
            val rlRem = response.header("anthropic-ratelimit-tokens-remaining")?.toLongOrNull()
            val quotaPct = if (rlLimit != null && rlRem != null && rlLimit > 0L) {
                ((rlRem * 100L) / rlLimit).toInt().coerceIn(0, 100)
            } else null
            return AiReply(
                text = text,
                inputTokens = inTok,
                outputTokens = outTok,
                quotaPercent = quotaPct,
                quotaLabel = if (quotaPct != null) "Claude API" else ""
            )
        }
    }

    private fun sendTermuxCli(
        connection: AiConnection,
        command: String
    ): AiReply {
        val url = connection.endpointUrl.ifBlank { defaultEndpointFor(ConnectionKind.TERMUX_CLI) }
        val payload = buildJsonObject {
            put("command", JsonPrimitive(command))
        }.toString()

        val reqBuilder = Request.Builder()
            .url(url)
            .addHeader("content-type", "application/json")
            .post(payload.toRequestBody(jsonMediaType))
        if (connection.apiKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer ${connection.apiKey.trim()}")
        }

        return try {
            client.newCall(reqBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return AiReply(text = "", error = "Termux CLI 오류 (${response.code}): ${extractError(bodyStr)}")
                }
                val output = runCatching {
                    val obj = json.parseToJsonElement(bodyStr).jsonObject
                    obj["output"]?.jsonPrimitive?.contentOrNull
                        ?: obj["text"]?.jsonPrimitive?.contentOrNull
                        ?: bodyStr
                }.getOrElse { bodyStr }
                AiReply(text = output.ifBlank { "(출력 없음)" })
            }
        } catch (e: Exception) {
            AiReply(
                text = "",
                error = "Termux 브릿지에 연결할 수 없습니다 ($url).\n설정 > Termux 브릿지 안내에서 서버 실행 명령을 복사해 Termux에서 먼저 실행해주세요.\n(${e.message})"
            )
        }
    }

    private fun sendRemoteControl(
        connection: AiConnection,
        history: List<ChatMessage>,
        prompt: String,
        sessionId: String,
        cwd: String = "",
        cliSessionId: String = "",
        effort: String = ""
    ): AiReply {
        val url = connection.endpointUrl.ifBlank { defaultEndpointFor(ConnectionKind.REMOTE_CONTROL) }
        val isLocalBridge = (url.contains("127.0.0.1:8765/agy") || url.contains("127.0.0.1:8765/claude") ||
            url.contains("localhost:8765/agy") || url.contains("localhost:8765/claude"))
        val bridgeEndpoint = if (url.lowercase().contains("/claude")) "/claude" else "/agy"
        val payload = buildJsonObject {
            put("prompt", JsonPrimitive(prompt))
            if (isLocalBridge) {
                put("endpoint", JsonPrimitive(bridgeEndpoint))
            }
            if (connection.model.isNotBlank()) {
                put("model", JsonPrimitive(connection.model))
            }
            if (effort.isNotBlank()) {
                put("effort", JsonPrimitive(effort.lowercase()))
            }
            if (sessionId.isNotBlank()) {
                put("mbta_session_id", JsonPrimitive(sessionId))
            }
            if (cliSessionId.isNotBlank()) {
                put("cli_session_id", JsonPrimitive(cliSessionId))
            }
            if (cwd.isNotBlank()) {
                put("cwd", JsonPrimitive(cwd))
            }
            if (connection.permissionMode.isNotBlank()) {
                put("permission_mode", JsonPrimitive(connection.permissionMode))
            }
            put("history", buildJsonArray {
                history.forEach { msg ->
                    add(buildJsonObject {
                        put("role", JsonPrimitive(msg.role.name.lowercase()))
                        put("content", JsonPrimitive(msg.content))
                    })
                }
            })
        }.toString()

        if (isLocalBridge) {
            val startReq = Request.Builder()
                .url("http://127.0.0.1:8765/start_job")
                .addHeader("content-type", "application/json")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            val jobId = runCatching {
                client.newCall(startReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val bodyStr = resp.body?.string().orEmpty()
                    json.parseToJsonElement(bodyStr).jsonObject["job_id"]?.jsonPrimitive?.contentOrNull
                }
            }.getOrNull()

            if (!jobId.isNullOrBlank()) {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        Thread.sleep(1500L)
                    } catch (_: InterruptedException) {
                        return AiReply(text = "", error = "작업이 취소되었습니다.")
                    }
                    val pollReq = Request.Builder()
                        .url("http://127.0.0.1:8765/job_status?job_id=$jobId")
                        .get()
                        .build()
                    val pollResult = runCatching {
                        client.newCall(pollReq).execute().use { resp ->
                            if (!resp.isSuccessful) return@use null
                            json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                        }
                    }.getOrNull() ?: continue
                    val status = pollResult["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (status == "done") {
                        val text = pollResult["response"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val inTok = pollResult["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
                        val outTok = pollResult["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
                        return AiReply(text = text, inputTokens = inTok, outputTokens = outTok)
                    } else if (status == "error") {
                        val err = pollResult["error"]?.jsonPrimitive?.contentOrNull ?: "로컬 브릿지 실행 오류"
                        return AiReply(text = "", error = err)
                    }
                }
                return AiReply(text = "", error = "작업이 취소되었습니다.")
            }
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .addHeader("content-type", "application/json")
            .post(payload.toRequestBody(jsonMediaType))
        if (connection.apiKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer ${connection.apiKey.trim()}")
        }

        return try {
            client.newCall(reqBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code == 405) {
                        return AiReply(
                            text = "",
                            error = "Remote Control 오류 (405): 입력하신 URL($url)은 웹페이지 주소로 보입니다.\n설정 > 해당 연결 편집에서 모드를 '웹 원격'으로 변경하거나, 로컬 브릿지 URL(http://127.0.0.1:8765/claude 또는 /agy)을 사용하세요."
                        )
                    }
                    return AiReply(text = "", error = "Remote Control 오류 (${response.code}): ${extractError(bodyStr)}")
                }
                val parsed = runCatching {
                    val obj = json.parseToJsonElement(bodyStr).jsonObject
                    val text = obj["response"]?.jsonPrimitive?.contentOrNull
                        ?: obj["output"]?.jsonPrimitive?.contentOrNull
                        ?: obj["text"]?.jsonPrimitive?.contentOrNull
                        ?: bodyStr
                    val inTok = obj["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
                    val outTok = obj["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
                    AiReply(text = text, inputTokens = inTok, outputTokens = outTok)
                }.getOrElse { AiReply(text = bodyStr) }
                parsed
            }
        } catch (e: Exception) {
            AiReply(
                text = "",
                error = "Remote Control 서버에 연결할 수 없습니다 ($url).\n(${e.message})"
            )
        }
    }

    private fun extractError(bodyStr: String): String {
        return runCatching {
            val obj = json.parseToJsonElement(bodyStr)
            if (obj is JsonObject) {
                obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: obj["message"]?.jsonPrimitive?.contentOrNull
                    ?: bodyStr.take(200)
            } else {
                bodyStr.take(200)
            }
        }.getOrElse { bodyStr.take(200) }
    }
}
