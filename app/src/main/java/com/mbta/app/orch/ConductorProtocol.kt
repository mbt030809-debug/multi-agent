package com.mbta.app.orch

import org.json.JSONArray

/** 사용자가 설정한 서브 하나 (메인에게는 id·이름·역할만 알려줌) */
data class SubDef(val id: String, val aiName: String, val role: String)

/** 메인이 서브에게 내린 작업 지침 하나. after 의 서브가 먼저 끝나야 하고 그 결과를 받음 */
data class SubTask(val id: String, val task: String, val output: String = "", val after: List<String> = emptyList())

data class SubResult(val id: String, val ok: Boolean, val output: String)

sealed class MainReply {
    /** 지침 블록 없음 → 직접 답변 또는 최종 보고 */
    data class Direct(val text: String) : MainReply()
    data class Delegate(val preface: String, val tasks: List<SubTask>) : MainReply()
    /** 지침 블록처럼 보이지만 쓸 수 없음 → 최종 답으로 넘기지 않고 재작성 요청 */
    data class Invalid(val preface: String, val reason: String) : MainReply()
}

/** 지휘 지시문 작성과 메인 답변 해석 (화면·안드로이드와 분리해 테스트 가능) */
object ConductorProtocol {
    const val OPEN = "[[SUBAGENTS]]"
    const val CLOSE = "[[/SUBAGENTS]]"
    const val REPORT_LIMIT = 6000
    const val REPORT_TOTAL_LIMIT = 20000

    private val markedBlock = Regex("\\[\\[SUBAGENTS]]([\\s\\S]*?)\\[\\[/SUBAGENTS]]", RegexOption.IGNORE_CASE)
    private val legacyBlock = Regex("<<SUBAGENTS>>([\\s\\S]*?)<</SUBAGENTS>>", RegexOption.IGNORE_CASE)
    /** 웹 화면이 <<SUBAGENTS>> 의 태그 부분을 지워 "<>" 만 남긴 형태 */
    private val strippedBlock = Regex("(?m)^[ \\t]*<>[ \\t]*$([\\s\\S]*?)^[ \\t]*<>[ \\t]*$")
    private val openMarker = Regex("\\[\\[SUBAGENTS]]|<<SUBAGENTS>>", RegexOption.IGNORE_CASE)

    fun parse(text: String, validIds: Set<String>): MainReply {
        // 지시문 예시를 따라 쓴 앞부분이 있을 수 있어 마지막 블록을 사용
        val match = markedBlock.findAll(text).lastOrNull()
            ?: legacyBlock.findAll(text).lastOrNull()
            ?: strippedBlock.findAll(text).lastOrNull()?.takeIf { looksLikeTasks(it.groupValues[1]) }
        if (match == null) {
            val open = openMarker.findAll(text).lastOrNull()
            return if (open != null && looksLikeTasks(text.substring(open.range.last + 1))) {
                MainReply.Invalid(text.substring(0, open.range.first).trim(), "지침 블록이 닫히지 않았습니다. $CLOSE 로 끝내야 합니다.")
            } else {
                MainReply.Direct(text.trim())
            }
        }
        val preface = text.substring(0, match.range.first).trim()
        val body = match.groupValues[1]
        val start = body.indexOf('[')
        val end = body.lastIndexOf(']')
        if (start < 0 || end <= start) return MainReply.Invalid(preface, "블록 안에 JSON 배열이 없습니다.")
        val tasks = try {
            // 코드 블록 머리글("json")이나 ``` 는 배열 바깥이라 함께 잘려 나감
            val arr = JSONArray(body.substring(start, end + 1))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val after = o.optJSONArray("after")?.let { a -> (0 until a.length()).map { a.getString(it).trim() } }.orEmpty()
                SubTask(o.optString("id").trim(), o.optString("task").trim(), o.optString("output").trim(), after.filter { it.isNotEmpty() }.distinct())
            }
        } catch (e: Exception) {
            return MainReply.Invalid(preface, "JSON 형식 오류: ${e.message?.take(120) ?: "해석 실패"}")
        }
        val error = validate(tasks, validIds)
        return if (error == null) MainReply.Delegate(preface, tasks) else MainReply.Invalid(preface, error)
    }

    private fun looksLikeTasks(s: String): Boolean = s.contains("\"task\"") && s.contains('[')

    private fun validate(tasks: List<SubTask>, validIds: Set<String>): String? {
        if (tasks.isEmpty()) return "지침이 비어 있습니다."
        val ids = tasks.map { it.id }
        tasks.forEach { t ->
            if (t.id !in validIds) return "없는 서브 id '${t.id}'. 사용할 수 있는 id: ${validIds.joinToString(", ")}"
            if (t.task.isBlank()) return "'${t.id}' 의 task 가 비어 있습니다."
        }
        if (ids.size != ids.toSet().size) return "한 번의 지침에서 같은 서브 id 는 한 번만 쓸 수 있습니다."
        tasks.forEach { t ->
            val unknown = t.after.firstOrNull { it !in ids }
            if (unknown != null) return "'${t.id}' 의 after 에 이번 지침에 없는 id '$unknown' 가 있습니다."
        }
        if (waves(tasks) == null) return "after 가 서로를 기다립니다(순환)."
        return null
    }

    /** after 순서대로 실행 묶음을 만듦. 같은 묶음은 서로 무관. 순환이면 null */
    fun waves(tasks: List<SubTask>): List<List<SubTask>>? {
        val done = mutableSetOf<String>()
        var rest = tasks
        val out = mutableListOf<List<SubTask>>()
        while (rest.isNotEmpty()) {
            val ready = rest.filter { t -> t.after.all { it in done } }
            if (ready.isEmpty()) return null
            out.add(ready)
            done.addAll(ready.map { it.id })
            rest = rest - ready.toSet()
        }
        return out
    }

    /** 생성 중 화면에 보여줄 때 지침 블록(아직 닫히지 않은 것 포함)은 숨김 */
    fun hideBlock(text: String): String {
        var t = text.replace(markedBlock, "").replace(legacyBlock, "")
        val open = openMarker.find(t)
        if (open != null) t = t.substring(0, open.range.first)
        return t.trim()
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

    fun clip(text: String, limit: Int): String =
        if (text.length <= limit) text else text.take(limit) + "\n…(길어서 ${text.length - limit}자 생략됨)"

    /** 사용자 명령마다 메인에게 붙이는 고정 지휘 지시문 */
    fun conductorInstructions(subs: List<SubDef>, sensitivity: String): String = buildString {
        append("너는 이 작업의 지휘자다. 서브에이전트는 앱이 실행한다.\n")
        append("사용 가능한 서브:\n")
        subs.forEach { append("- ${it.id}: ${it.aiName}").append(if (it.role.isBlank()) "" else " — ${it.role}").append("\n") }
        append("\n먼저 요청을 보고 둘 중 하나를 선택해라.\n")
        append("A. 직접 처리 - 대화, 단순 질문, 한 번에 끝낼 수 있는 작은 작업. 바로 답한다. 서브 지침 블록은 쓰지 않는다.\n")
        append("B. 위임 - 나눠서 할 수 있거나 분량이 큰 작업. 필요하면 구조, 뼈대, 기준은 네가 먼저 만든다.\n")
        append("맡길 부분은 지침 블록으로 작성한다. 맡긴 부분의 결과를 네가 대신 만들거나 지어내지 않는다.\n")
        append("지침 블록을 쓴 뒤 응답을 끝낸다. 앱이 서브를 실행하고 결과를 보내 준다.\n")
        append(
            when (sensitivity) {
                "high" -> "위임 기준: 적극적. 아주 작은 작업이 아니면 위임한다.\n"
                "low" -> "위임 기준: 보수적. 꼭 필요한 큰 작업만 위임한다.\n"
                else -> "위임 기준: 보통.\n"
            }
        )
        append("A와 B 모두 네가 가진 자체 서브에이전트 기능은 쓰지 않는다.\n")
        append("서브는 너의 대화를 볼 수 없으므로 task 에 필요한 맥락을 모두 담는다.\n\n")
        append("지침 블록 형식 (첫 줄과 마지막 줄의 표시는 코드 블록 바깥에 그대로 쓴다. after 는 먼저 끝나야 하는 서브 id):\n")
        append("$OPEN\n```json\n[{\"id\":\"${subs.firstOrNull()?.id ?: "s1"}\",\"task\":\"할 일\",\"output\":\"보고 형식\",\"after\":[]}]\n```\n$CLOSE")
    }

    fun userPrompt(instructions: String, context: String, request: String): String =
        listOf(instructions, context.trim(), "[사용자 요청]\n$request").filter { it.isNotBlank() }.joinToString("\n\n")

    fun reportPrompt(tasks: List<SubTask>, results: List<SubResult>, round: Int, maxRounds: Int): String = buildString {
        append("아래는 앱이 실행한 서브의 결과다(${round}/${maxRounds}회). 검토해라.\n")
        val perResult = minOf(REPORT_LIMIT, REPORT_TOTAL_LIMIT / maxOf(1, results.size))
        results.forEach { r ->
            val task = tasks.firstOrNull { it.id == r.id }
            append("\n### ${r.id} (${if (r.ok) "완료" else "실패"})\n")
            if (task != null) append("맡긴 일: ${clip(task.task, 300)}\n")
            append(clip(r.output, perResult)).append("\n")
        }
        append("\n")
        if (round >= maxRounds) {
            append("위임 한도(${maxRounds}회)에 도달했다. 지침 블록을 쓰지 말고, 지금까지의 결과를 검토해 최종 보고를 작성해라. 남은 문제가 있으면 분명히 적어라.")
        } else {
            append("- 작은 수정이면 네가 직접 고쳐서 최종 답을 작성한다.\n")
            append("- 다시 맡겨야 할 만큼 크면 피드백을 담아 지침 블록($OPEN … $CLOSE)을 작성하고 응답을 끝낸다.\n")
            append("- 충분하면 지침 블록 없이 최종 보고를 작성한다.")
        }
    }

    fun rewritePrompt(reason: String): String =
        "[지침 블록 오류] $reason\n$OPEN 와 $CLOSE 사이에 올바른 JSON 배열을 넣어 지침 블록을 다시 작성해라. 위임하지 않을 거면 블록 없이 답해라."

    fun subPrompt(def: SubDef, task: SubTask, dependencies: List<SubResult>, first: Boolean, context: String): String = buildString {
        if (first) {
            append("너는 지휘자의 지시를 받아 일하는 서브에이전트 '${def.id}'다.")
            if (def.role.isNotBlank()) append(" 역할: ${def.role}")
            append("\n맡은 일만 하고, 핵심 결과를 간결하고 정확하게 보고해라. 네가 가진 자체 서브에이전트 기능은 쓰지 않는다.\n")
            if (context.isNotBlank()) append("\n").append(context.trim()).append("\n")
            append("\n")
        }
        append("■ 작업: ").append(task.task).append("\n")
        if (task.output.isNotBlank()) append("■ 보고 형식: ").append(task.output).append("\n")
        dependencies.forEach { d ->
            append("\n■ 먼저 끝난 ${d.id} 의 결과:\n").append(clip(d.output, REPORT_LIMIT)).append("\n")
        }
    }.trim()
}
