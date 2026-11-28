package com.mbta.app.ui

import org.json.JSONArray

internal data class SubagentRequest(
    val role: String,
    val task: String,
    val model: String = "",
    /** 이 서브가 달성할 목표 (선택) */
    val objective: String = "",
    /** 기대하는 보고 형식 (선택) */
    val output: String = "",
    /** 이 서브가 소유(수정)하는 파일/폴더. 선언되면 파일 충돌 판정에 우선 사용 */
    val files: List<String> = emptyList(),
    /** 건드리면 안 되는 범위 (선택) */
    val boundaries: String = "",
    /** true면 읽기 전용(파일 수정 금지) */
    val readOnly: Boolean = false
)

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
                    val files = buildList {
                        val fa = obj.optJSONArray("files")
                        if (fa != null) for (k in 0 until fa.length()) fa.optString(k).trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                        else obj.optString("files").split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
                    }
                    val mode = obj.optString("mode").trim().lowercase()
                    val readOnly = obj.optBoolean("readOnly", false) || mode in setOf("read", "readonly", "read-only", "읽기")
                    if (task.isNotBlank()) add(
                        SubagentRequest(
                            role, task, model,
                            objective = obj.optString("objective").trim(),
                            output = obj.optString("output").trim(),
                            files = files,
                            boundaries = obj.optString("boundaries").trim(),
                            readOnly = readOnly
                        )
                    )
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

    private fun norm(f: String): String = f.trim().trimStart('.', '/').trimEnd('/').lowercase()

    /** 선언된 소유 파일끼리 겹치는지 (같은 경로 또는 한쪽이 상위 폴더) */
    internal fun filesOverlap(a: List<String>, b: List<String>): Boolean {
        val na = a.map(::norm).filter { it.isNotEmpty() }
        val nb = b.map(::norm).filter { it.isNotEmpty() }
        return na.any { x -> nb.any { y -> x == y || x.startsWith("$y/") || y.startsWith("$x/") } }
    }

    private fun writes(r: SubagentRequest): Boolean =
        if (r.readOnly) false else r.files.isNotEmpty() || isWrite(r.task)

    private fun conflicts(a: SubagentRequest, b: SubagentRequest): Boolean {
        if (!writes(a) || !writes(b)) return false
        // 소유 파일을 둘 다 선언했으면 선언 기준으로 결정적으로 판정
        if (a.files.isNotEmpty() && b.files.isNotEmpty()) return filesOverlap(a.files, b.files)
        val pa = paths(a.task) + a.files.map(::norm)
        val pb = paths(b.task) + b.files.map(::norm)
        return pa.intersect(pb).isNotEmpty()
    }

    /** 서브에이전트에 전달할 구조화된 지시서 */
    fun brief(r: SubagentRequest): String = buildString {
        if (r.objective.isNotBlank()) append("■ 목표: ").append(r.objective).append("\n")
        append("■ 작업: ").append(r.task).append("\n")
        if (r.files.isNotEmpty()) append("■ 소유 파일(수정 허용 범위): ").append(r.files.joinToString(", ")).append("\n")
        if (r.readOnly) append("■ 모드: 읽기 전용 — 어떤 파일도 수정·생성·삭제하지 마세요.\n")
        else if (r.files.isNotEmpty()) append("■ 위 소유 파일 밖은 수정하지 마세요(필요하면 보고서에 제안만 적기).\n")
        if (r.boundaries.isNotBlank()) append("■ 금지/주의: ").append(r.boundaries).append("\n")
        if (r.output.isNotBlank()) append("■ 보고 형식: ").append(r.output).append("\n")
    }

    /** 위임 라운드에서 파일을 수정한 서브가 있는지 (리뷰어 실행 여부 판단) */
    fun hasWriters(requests: List<SubagentRequest>): Boolean = requests.any(::writes)

    /** 읽기 전용 리뷰어 + 테스트 검증 지시서 */
    fun reviewPrompt(requests: List<SubagentRequest>, outputs: List<String>): String = buildString {
        append("당신은 읽기 전용 코드 리뷰어입니다. 파일을 수정·생성·삭제하지 마세요.\n")
        append("방금 서브에이전트들이 아래 작업을 수행했습니다. 변경 내용을 실제 파일/`git diff`로 확인하고 검증하세요.\n\n")
        requests.forEachIndexed { i, r ->
            if (!writes(r)) return@forEachIndexed
            append("### ${r.role}\n- 작업: ${r.task.take(300)}\n")
            if (r.files.isNotEmpty()) append("- 소유 파일: ${r.files.joinToString(", ")}\n")
            outputs.getOrNull(i)?.let { append("- 보고 요약: ${it.take(400)}\n") }
            append("\n")
        }
        append("검증 항목:\n")
        append("1. 각 서브가 소유 파일 밖을 수정하지 않았는지, 서로의 변경이 충돌하지 않는지\n")
        append("2. 버그·누락·요구사항 불일치\n")
        append("3. 프로젝트의 테스트/린트/빌드 명령이 있으면 실행하고 결과(통과/실패, 실패 로그 핵심)를 보고. 없으면 \"테스트 명령 없음\"이라고 적기\n")
        append("마지막 줄에 `VERDICT: PASS` 또는 `VERDICT: FAIL(이유)` 형식으로 판정을 적으세요.")
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
