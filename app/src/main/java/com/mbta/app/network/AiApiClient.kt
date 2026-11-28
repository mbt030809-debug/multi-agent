package com.mbta.app.network

import com.mbta.app.model.AiConnection
import com.mbta.app.model.ChatMessage
import com.mbta.app.model.ConnectionKind
import com.mbta.app.model.MessageRole
import com.mbta.app.model.defaultEndpointFor
import com.mbta.app.model.defaultModelFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AiReply(
    val text: String,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val quotaPercent: Int? = null,
    val quotaLabel: String = "",
    val error: String? = null,
    /** 브리지가 알려준 실제 사용 모델 (선택 모델과 다른지 비교용) */
    val servedModel: String = ""
)

object AiApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val attMarker = Regex("<<ATT>>[\\s\\S]*?<</ATT>>")
    private fun stripAtt(text: String): String = text.replace(attMarker, "").trim()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** 로컬 브릿지 작업 최대 대기 시간 (초과 시 작업을 취소하고 오류로 종료) */
    private const val BRIDGE_MAX_WAIT_MS = 90L * 60L * 1000L
    /** 브릿지 작업 취소 요청 전용 클라이언트 (짧은 타임아웃) */
    private val cancelClient: OkHttpClient by lazy { client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build() }
    private const val TRUNCATED_NOTE = "\n\n(⚠️ 최대 길이에 도달해 응답이 잘렸습니다)"
    private val GEMINI_BLOCKED_FINISH = setOf("SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "RECITATION", "IMAGE_SAFETY")

    /** OkHttp 호출을 코루틴 취소와 연동한다. 코루틴이 취소되면 call.cancel() 로 HTTP 요청을 끊는다. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { response.close() }
            }
        })
    }

    /** Claude thinking budget (effort → budget_tokens). 알 수 없는 값이면 null (기존 동작). */
    private fun claudeThinkingBudget(model: String, effort: String): Int? = if (!claudeSupportsThinking(model)) null else when (effort.trim().lowercase()) {
        "low" -> 2048
        "medium" -> 8192
        "high" -> 16384
        "extra", "xhigh" -> 24576
        "max" -> 32000
        else -> null
    }

    /** thinking 미지원 구형 Claude(3.5 이하) 제외 */
    fun claudeSupportsThinking(model: String): Boolean {
        val m = model.lowercase()
        return m.contains("claude") && !Regex("claude-3-(5|haiku|opus|sonnet)|claude-3\\.5|claude-2|claude-instant").containsMatchIn(m)
    }

    /** Gemini thinkingBudget. thinking 미지원 모델(2.0 등)이면 null. */
    private fun geminiThinkingBudget(model: String, effort: String): Int? {
        val lower = model.lowercase()
        val supported = lower.contains("2.5") || lower.contains("gemini-3") || lower.contains("gemini-4")
        if (!supported) return null
        return when (effort.trim().lowercase()) {
            "low" -> 1024
            "medium" -> 8192
            "high" -> 16384
            "extra", "xhigh" -> 24576
            "max" -> 32768
            else -> null
        }
    }

    /** OpenAI reasoning_effort. o 시리즈(o1, o3 …) 또는 gpt-5 계열만 지원. */
    private fun openAiReasoningEffort(model: String, effort: String): String? {
        if (!Regex("^(o\\d|gpt-5)").containsMatchIn(model.lowercase())) return null
        return when (effort.trim().lowercase()) {
            "low" -> "low"
            "medium" -> "medium"
            "high", "extra", "xhigh", "max" -> "high"
            else -> null
        }
    }

    /** 로컬 브릿지 작업 취소 (best-effort). 코루틴 취소 중에도 실행되도록 NonCancellable 에서 호출한다. */
    private suspend fun cancelBridgeSession(sessionId: String) = withContext(NonCancellable + Dispatchers.IO) {
        if (sessionId.isNotBlank()) {
            runCatching {
                val body = buildJsonObject { put("session_id", JsonPrimitive(sessionId)) }.toString()
                val req = Request.Builder()
                    .url("http://127.0.0.1:8765/cancel")
                    .addHeader("content-type", "application/json")
                    .post(body.toRequestBody(jsonMediaType))
                    .build()
                cancelClient.newCall(req).execute().close()
            }
        }
        Unit
    }

    suspend fun fetchAvailableModels(connection: AiConnection): List<String> = withContext(Dispatchers.IO) {
        val fallback = com.mbta.app.model.availableModelsFor(connection)
        try {
            when (connection.kind) {
                ConnectionKind.REMOTE_CONTROL -> {
                    val kind = if (connection.endpointUrl.lowercase().contains("/claude")) "claude" else "agy"
                    val req = Request.Builder().url("http://127.0.0.1:8765/models?kind=$kind").get().build()
                    client.newCall(req).await().use { resp ->
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
                    client.newCall(req).await().use { resp ->
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
                    client.newCall(req).await().use { resp ->
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
                    client.newCall(req).await().use { resp ->
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
        } catch (e: CancellationException) {
            throw e
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
        effort: String = "",
        skipPermissions: Boolean = false,
        systemPrompt: String = ""
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
                ConnectionKind.GEMINI_API -> sendGemini(connection, history, userMessage, effort)
                ConnectionKind.TERMUX_CLI -> sendTermuxCli(connection, userMessage)
                ConnectionKind.REMOTE_CONTROL -> sendRemoteControl(connection, history, userMessage, sessionId, cwd, cliSessionId, effort, skipPermissions, systemPrompt)
                else -> AiReply(text = "", error = "지원하지 않는 연결 형식입니다.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AiReply(text = "", error = "요청 오류: ${e.message ?: "알 수 없는 오류"}")
        }
    }

    private suspend fun sendClaude(
        connection: AiConnection,
        history: List<ChatMessage>,
        userMessage: String,
        effort: String = ""
    ): AiReply {
        val url = connection.endpointUrl.ifBlank { defaultEndpointFor(ConnectionKind.CLAUDE_API) }
        val model = connection.model.ifBlank { defaultModelFor(ConnectionKind.CLAUDE_API) }
        val systemText = history.filter { it.role == MessageRole.SYSTEM }
            .joinToString("\n\n") { stripAtt(it.content) }

        val messagesArray = buildJsonArray {
            history.filter { it.role != MessageRole.SYSTEM }.forEach { msg ->
                add(buildJsonObject {
                    put("role", JsonPrimitive(if (msg.role == MessageRole.USER) "user" else "assistant"))
                    put("content", JsonPrimitive(stripAtt(msg.content)))
                })
            }
            add(buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", JsonPrimitive(userMessage))
            })
        }

        val budget = claudeThinkingBudget(model, effort)
        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            if (budget != null) {
                put("max_tokens", JsonPrimitive(budget + 8192))
                put("thinking", buildJsonObject {
                    put("type", JsonPrimitive("enabled"))
                    put("budget_tokens", JsonPrimitive(budget))
                })
            } else {
                put("max_tokens", JsonPrimitive(4096))
            }
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

        client.newCall(request).await().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return AiReply(text = "", error = "Claude API 오류 (${response.code}): ${extractError(bodyStr)}")
            }
            val root = json.parseToJsonElement(bodyStr).jsonObject
            val text = root["content"]?.jsonArray
                ?.filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString("")
                .orEmpty()
                .let { if (root["stop_reason"]?.jsonPrimitive?.contentOrNull == "max_tokens") it + TRUNCATED_NOTE else it }
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

    private suspend fun sendOpenAi(
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
                    put("content", JsonPrimitive(stripAtt(msg.content)))
                })
            }
            add(buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", JsonPrimitive(userMessage))
            })
        }

        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            openAiReasoningEffort(model, effort)?.let { put("reasoning_effort", JsonPrimitive(it)) }
            put("messages", messagesArray)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${connection.apiKey.trim()}")
            .addHeader("content-type", "application/json")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).await().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return AiReply(text = "", error = "OpenAI API 오류 (${response.code}): ${extractError(bodyStr)}")
            }
            val root = json.parseToJsonElement(bodyStr).jsonObject
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            val text = choice?.get("message")?.jsonObject?.get("content")
                ?.jsonPrimitive?.contentOrNull.orEmpty()
                .let { if (choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull == "length") it + TRUNCATED_NOTE else it }
            val usage = root["usage"]?.jsonObject
            val inTok = usage?.get("prompt_tokens")?.jsonPrimitive?.longOrNull ?: 0L
            val outTok = usage?.get("completion_tokens")?.jsonPrimitive?.longOrNull ?: 0L
            val rlLimit = response.header("x-ratelimit-limit-tokens")?.toLongOrNull()
                ?: response.header("x-ratelimit-limit-requests")?.toLongOrNull()
            val rlRem = response.header("x-ratelimit-remaining-tokens")?.toLongOrNull()
                ?: response.header("x-ratelimit-remaining-requests")?.toLongOrNull()
            val quotaPct = if (rlLimit != null && rlRem != null && rlLimit > 0L) {
                ((rlRem * 100L) / rlLimit).toInt().coerceIn(0, 100)
            } else null
            return AiReply(
                text = text,
                inputTokens = inTok,
                outputTokens = outTok,
                quotaPercent = quotaPct,
                quotaLabel = if (quotaPct != null) "OpenAI API" else ""
            )
        }
    }

    private suspend fun sendGemini(
        connection: AiConnection,
        history: List<ChatMessage>,
        userMessage: String,
        effort: String = ""
    ): AiReply {
        val baseUrl = connection.endpointUrl.ifBlank { defaultEndpointFor(ConnectionKind.GEMINI_API) }.trimEnd('/')
        val model = connection.model.ifBlank { defaultModelFor(ConnectionKind.GEMINI_API) }
        val url = "$baseUrl/models/$model:generateContent?key=${connection.apiKey.trim()}"

        val systemText = history.filter { it.role == MessageRole.SYSTEM }
            .joinToString("\n\n") { stripAtt(it.content) }

        val contentsArray = buildJsonArray {
            history.filter { it.role != MessageRole.SYSTEM }.forEach { msg ->
                add(buildJsonObject {
                    put("role", JsonPrimitive(if (msg.role == MessageRole.USER) "user" else "model"))
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", JsonPrimitive(stripAtt(msg.content))) })
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
            geminiThinkingBudget(model, effort)?.let { budget ->
                put("generationConfig", buildJsonObject {
                    put("thinkingConfig", buildJsonObject {
                        put("thinkingBudget", JsonPrimitive(budget))
                    })
                })
            }
        }.toString()

        val request = Request.Builder()
            .url(url)
            .addHeader("content-type", "application/json")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).await().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return AiReply(text = "", error = "Gemini API 오류 (${response.code}): ${extractError(bodyStr)}")
            }
            val root = json.parseToJsonElement(bodyStr).jsonObject
            val cand = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            val finish = cand?.get("finishReason")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (cand == null) {
                val block = root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
                val why = listOfNotNull(block, finish.ifBlank { null }).joinToString(", ").ifBlank { "응답 후보 없음" }
                return AiReply(text = "", error = "Gemini가 응답을 차단했습니다: $why")
            }
            val parts = cand["content"]?.jsonObject?.get("parts")?.jsonArray
            val text = parts
                ?.filter { it.jsonObject["thought"]?.jsonPrimitive?.contentOrNull != "true" }
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString("").orEmpty()
            if (text.isBlank() && finish in GEMINI_BLOCKED_FINISH) {
                return AiReply(text = "", error = "Gemini가 응답을 차단했습니다: $finish")
            }
            val usage = root["usageMetadata"]?.jsonObject
            val inTok = usage?.get("promptTokenCount")?.jsonPrimitive?.longOrNull ?: 0L
            val outTok = usage?.get("candidatesTokenCount")?.jsonPrimitive?.longOrNull ?: 0L
            return AiReply(
                text = text,
                inputTokens = inTok,
                outputTokens = outTok
            )
        }
    }

    private suspend fun sendTermuxCli(
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
            client.newCall(reqBuilder.build()).await().use { response ->
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AiReply(
                text = "",
                error = "Termux 브릿지에 연결할 수 없습니다 ($url).\n설정 > Termux 브릿지 안내에서 서버 실행 명령을 복사해 Termux에서 먼저 실행해주세요.\n(${e.message})"
            )
        }
    }

    private suspend fun sendRemoteControl(
        connection: AiConnection,
        history: List<ChatMessage>,
        prompt: String,
        sessionId: String,
        cwd: String = "",
        cliSessionId: String = "",
        effort: String = "",
        skipPermissions: Boolean = false,
        systemPrompt: String = ""
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
            if (systemPrompt.isNotBlank()) {
                put("system_prompt", JsonPrimitive(systemPrompt))
            }
            if (skipPermissions) {
                put("skip_permissions", JsonPrimitive(true))
                put("permission_mode", JsonPrimitive("auto"))
            } else if (connection.permissionMode.isNotBlank()) {
                put("permission_mode", JsonPrimitive(connection.permissionMode))
            }
            put("history", buildJsonArray {
                history.forEach { msg ->
                    add(buildJsonObject {
                        put("role", JsonPrimitive(msg.role.name.lowercase()))
                        put("content", JsonPrimitive(stripAtt(msg.content)))
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
            val jobId = try {
                client.newCall(startReq).await().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val bodyStr = resp.body?.string().orEmpty()
                    json.parseToJsonElement(bodyStr).jsonObject["job_id"]?.jsonPrimitive?.contentOrNull
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            if (!jobId.isNullOrBlank()) {
                val startedAt = System.currentTimeMillis()
                var consecutiveFailures = 0
                var finished = false
                try {
                    while (true) {
                        // delay 는 취소를 감지한다. 취소되면 아래 finally 에서 브릿지 작업을 중지한다.
                        delay(1500L)
                        if (System.currentTimeMillis() - startedAt > BRIDGE_MAX_WAIT_MS) {
                            return AiReply(text = "", error = "로컬 브릿지 작업이 최대 대기 시간(90분)을 초과해 중단했습니다.")
                        }
                        val pollReq = Request.Builder()
                            .url("http://127.0.0.1:8765/job_status?job_id=$jobId")
                            .get()
                            .build()
                        val pollResult = try {
                            client.newCall(pollReq).await().use { resp ->
                                if (!resp.isSuccessful) null
                                else json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        if (pollResult == null) {
                            consecutiveFailures++
                            if (consecutiveFailures >= 10) {
                                return AiReply(text = "", error = "로컬 브릿지 상태 조회가 연속 10회 실패해 중단했습니다.")
                            }
                            continue
                        }
                        consecutiveFailures = 0
                        val status = pollResult["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (status == "done") {
                            finished = true
                            val text = pollResult["response"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val inTok = pollResult["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
                            val outTok = pollResult["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
                            val served = pollResult["model"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            return AiReply(text = text, inputTokens = inTok, outputTokens = outTok, servedModel = served)
                        } else if (status == "error") {
                            finished = true
                            val err = pollResult["error"]?.jsonPrimitive?.contentOrNull ?: "로컬 브릿지 실행 오류"
                            return AiReply(text = "", error = err)
                        }
                    }
                } finally {
                    if (!finished) cancelBridgeSession(sessionId)
                }
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
            client.newCall(reqBuilder.build()).await().use { response ->
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
                    AiReply(text = text, inputTokens = inTok, outputTokens = outTok, servedModel = obj["model"]?.jsonPrimitive?.contentOrNull.orEmpty())
                }.getOrElse { AiReply(text = bodyStr) }
                parsed
            }
        } catch (e: CancellationException) {
            throw e
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
