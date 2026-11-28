package com.mbta.app.orch

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 앱이 실제 연결(웹·CLI·API)을 담당하는 부분 */
interface ConductorPort {
    /** 메인 대화에 보내고 답변을 받음. shownSoFar 는 생성 중 화면에 함께 보여줄 지금까지의 지휘 기록 */
    suspend fun askMain(prompt: String, shownSoFar: String): Pair<String, String?>
    /** 서브 대화(같은 서브는 계속 같은 대화)에 작업을 보내고 결과를 받음. 실패도 SubResult 로 반환 */
    suspend fun runSub(task: SubTask, dependencies: List<SubResult>, round: Int): SubResult
    /** 이 서브를 다른 서브와 동시에 실행해도 되는지 (웹은 안정성 확인 전까지 하나씩) */
    fun canRunConcurrently(subId: String): Boolean
    fun showProgress(text: String)
}

data class ConductorOutcome(
    /** 채팅창에 남길 지휘 기록과 최종 답 */
    val transcript: String,
    val error: String?,
    /** 앱이 실제로 서브에게 보낸 작업 수 (0이면 메인 직접 처리) */
    val subRunCount: Int,
    val hitLimit: Boolean = false
)

/** 메인 지휘 → 서브 실행 → 메인 검토를 완료 또는 한도까지 반복 */
class Conductor(
    private val subIds: Set<String>,
    private val maxRounds: Int,
    private val port: ConductorPort,
    private val maxRewrites: Int = 2
) {
    suspend fun run(firstPrompt: String): ConductorOutcome {
        val log = mutableListOf<String>()
        fun shown(extra: String = "") = (log + extra).filter { it.isNotBlank() }.joinToString("\n\n")
        var prompt = firstPrompt
        var round = 0
        var rewrites = 0
        var subRuns = 0
        while (true) {
            val (text, error) = port.askMain(prompt, shown())
            currentCoroutineContext().ensureActive()
            if (error != null) return ConductorOutcome(shown(), error, subRuns)
            when (val reply = ConductorProtocol.parse(text, subIds)) {
                is MainReply.Direct -> {
                    log.add(reply.text.ifBlank { "(응답 없음)" })
                    return ConductorOutcome(shown(), null, subRuns)
                }
                is MainReply.Invalid -> {
                    if (rewrites >= maxRewrites) {
                        log.add(reply.preface)
                        return ConductorOutcome(shown(), "메인의 작업 지침을 읽지 못했습니다: ${reply.reason}", subRuns)
                    }
                    rewrites++
                    prompt = ConductorProtocol.rewritePrompt(reply.reason)
                    port.showProgress(shown("⏳ 지침 형식 오류 — 메인에 다시 작성 요청 ($rewrites/$maxRewrites)"))
                }
                is MainReply.Delegate -> {
                    if (reply.preface.isNotBlank()) log.add(reply.preface)
                    val plan = reply.tasks.joinToString("\n") { t ->
                        "▶ ${t.id}: ${ConductorProtocol.clip(t.task, 500)}" + if (t.after.isEmpty()) "" else " (${t.after.joinToString(", ")} 이후)"
                    }
                    if (round >= maxRounds) {
                        log.add(plan)
                        log.add("⚠️ 위임 한도(${maxRounds}회)에 도달해 위 지침은 실행하지 않았습니다. 이어서 하려면 '계속'이라고 보내세요.")
                        return ConductorOutcome(shown(), null, subRuns, hitLimit = true)
                    }
                    round++
                    rewrites = 0
                    log.add("【지침 $round/$maxRounds】\n$plan")
                    port.showProgress(shown("⏳ 서브 작업 중 (위쪽 표시에서 확인)"))
                    val results = LinkedHashMap<String, SubResult>()
                    for (wave in ConductorProtocol.waves(reply.tasks).orEmpty()) {
                        val (runnable, blocked) = wave.partition { t -> t.after.all { results[it]?.ok == true } }
                        // 앞 작업이 실패하면 그 결과가 필요한 작업은 보내지 않음
                        blocked.forEach { results[it.id] = SubResult(it.id, false, "먼저 필요한 작업이 실패해 실행하지 않았습니다.") }
                        val (together, oneByOne) = runnable.partition { port.canRunConcurrently(it.id) }
                        suspend fun runOne(t: SubTask): SubResult {
                            subRuns++
                            return port.runSub(t, t.after.mapNotNull { results[it] }, round)
                        }
                        coroutineScope { together.map { async { runOne(it) } }.awaitAll() }.forEach { results[it.id] = it }
                        oneByOne.forEach {
                            currentCoroutineContext().ensureActive()
                            results[it.id] = runOne(it)
                        }
                        currentCoroutineContext().ensureActive()
                    }
                    val ordered = reply.tasks.mapNotNull { results[it.id] }
                    prompt = ConductorProtocol.reportPrompt(reply.tasks, ordered, round, maxRounds)
                    port.showProgress(shown("⏳ 메인이 서브 결과를 검토 중..."))
                }
            }
        }
    }
}
