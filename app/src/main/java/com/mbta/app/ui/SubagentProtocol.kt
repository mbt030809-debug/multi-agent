package com.mbta.app.ui

import org.json.JSONArray

internal data class SubagentRequest(val role: String, val task: String, val model: String = "")

internal data class SubagentParseResult(
    val requests: List<SubagentRequest>,
    /** <<SUBAGENTS>> 블록은 있었지만 JSON이 잘못된 경우의 오류 설명 (없으면 null) */
    val error: String? = null,
    val hasBlock: Boolean = false
)

/** 오케스트레이션 서브에이전트 블록 파싱 · 모델 결정 · 파일 겹침 직렬화 (UI와 분리해 테스트 가능) */
internal object SubagentProtocol {
    private val blockRegex = Regex("<<SUBAGENTS>>([\\s\\S]*?)<</SUBAGENTS>>", RegexOption.IGNORE_CASE)
    private val pathRegex = Regex("[A-Za-z0-9_./-]*[A-Za-z0-9_-]\\.[A-Za-z0-9]{1,6}\\b|[A-Za-z0-9_-]+/[A-Za-z0-9_./-]+")
    private val writeRegex = Regex(
        "수정|변경|작성|추가|삭제|리팩토링|구현|교체|생성|고쳐|바꿔|fix|edit|write|modify|refactor|implement|create|delete|rename",
        RegexOption.IGNORE_CASE
    )

    fun stripBlock(text: String): String = text.replace(blockRegex, "").trim()

    fun parse(text: String): SubagentParseResult {
        val match = blockRegex.find(text) ?: return SubagentParseResult(emptyList())
        val raw = match.groupValues[1].trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            val arr = JSONArray(raw)
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val role = obj.optString("role").ifBlank { obj.optString("label") }.ifBlank { "서브 ${i + 1}" }.trim().take(24)
                    val task = obj.optString("task").ifBlank { obj.optString("prompt") }.trim()
                    val model = obj.optString("model").trim()
                    if (task.isNotBlank()) add(SubagentRequest(role, task, model))
                }
            }
            if (list.isEmpty()) {
                SubagentParseResult(emptyList(), "블록 안에 task가 있는 항목이 없습니다.", hasBlock = true)
            } else {
                SubagentParseResult(list, null, hasBlock = true)
            }
        } catch (e: Exception) {
            SubagentParseResult(emptyList(), "JSON 형식 오류: ${e.message?.take(120) ?: "해석 실패"}", hasBlock = true)
        }
    }

    /** 요청한 모델을 연결의 사용 가능 모델 중에서 찾아 결정. 못 찾으면 기본값 */
    fun resolveModel(requested: String, available: List<String>, default: String): String {
        val r = requested.trim()
        if (r.isEmpty()) return default
        available.firstOrNull { it.equals(r, ignoreCase = true) }?.let { return it }
        available.firstOrNull { it.contains(r, ignoreCase = true) }?.let { return it }
        return default
    }

    private fun paths(task: String): Set<String> =
        pathRegex.findAll(task).map { it.value.trim().trimStart('.', '/').lowercase() }.filter { it.length >= 3 }.toSet()

    private fun isWrite(task: String): Boolean = writeRegex.containsMatchIn(task)

    private fun conflicts(a: SubagentRequest, b: SubagentRequest): Boolean {
        if (!isWrite(a.task) || !isWrite(b.task)) return false
        return paths(a.task).intersect(paths(b.task)).isNotEmpty()
    }

    /** 같은 파일을 쓰는 작업끼리는 서로 다른 배치로 나눠 순차 실행. 각 배치는 최대 maxParallel개 병렬. (요청 인덱스 반환) */
    fun batches(requests: List<SubagentRequest>, maxParallel: Int): List<List<Int>> {
        val cap = maxParallel.coerceAtLeast(1)
        val out = mutableListOf<MutableList<Int>>()
        for (i in requests.indices) {
            val target = out.firstOrNull { b -> b.size < cap && b.none { conflicts(requests[it], requests[i]) } }
            if (target != null) target.add(i) else out.add(mutableListOf(i))
        }
        return out
    }

    /** 선택한 모델과 실제 사용된 모델이 다른지 (패밀리 이름 기준) */
    fun modelMismatch(requested: String, served: String): Boolean {
        val r = requested.trim().lowercase()
        val s = served.trim().lowercase()
        if (r.isEmpty() || s.isEmpty()) return false
        if (s.contains(r) || r.contains(s)) return false
        val family = listOf("opus", "sonnet", "haiku", "fable").firstOrNull { r.contains(it) } ?: return false
        return !s.contains(family)
    }
}
