package com.mbta.app.orch

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConductorTest {
    private val ids = setOf("s1", "s2")
    private fun block(json: String) = "${ConductorProtocol.OPEN}\n$json\n${ConductorProtocol.CLOSE}"
    private val two = """[{"id":"s1","task":"조사"},{"id":"s2","task":"작성","after":["s1"]}]"""

    @Test
    fun parsesBlockWithCodeHeaderAndTrailingUiText() {
        val r = ConductorProtocol.parse("뼈대\n\n" + block("JSON\n$two") + "\n\n8분 전", ids) as MainReply.Delegate
        assertEquals("뼈대", r.preface)
        assertEquals(listOf("s1", "s2"), r.tasks.map { it.id })
        assertEquals(listOf("s1"), r.tasks[1].after)
    }

    @Test
    fun parsesMarkersStrippedByWebPage() {
        val r = ConductorProtocol.parse("대신 이렇게 합니다.\n\n<>\n$two\n<>", ids)
        assertTrue(r is MainReply.Delegate)
    }

    @Test
    fun plainAnswerIsDirect() {
        assertTrue(ConductorProtocol.parse("42 입니다. <> 는 비교 연산자입니다.", ids) is MainReply.Direct)
        assertTrue(ConductorProtocol.parse("[[SUBAGENTS]] 표시는 쓰지 않았습니다.", ids) is MainReply.Direct)
    }

    @Test
    fun brokenBlocksAreNotTreatedAsFinalAnswer() {
        listOf(
            block("[{id:}]"),
            block("""[{"id":"s9","task":"x"}]"""),
            block("""[{"id":"s1","task":"x"},{"id":"s1","task":"y"}]"""),
            block("""[{"id":"s1","task":"x","after":["s2"]},{"id":"s2","task":"y","after":["s1"]}]"""),
            block("""[{"id":"s1","task":"x","after":["s2"]}]"""),
            "${ConductorProtocol.OPEN}\n[{\"id\":\"s1\",\"task\":\"x\"}"
        ).forEach { assertTrue(it, ConductorProtocol.parse(it, ids) is MainReply.Invalid) }
    }

    @Test
    fun detectsModelMismatchByFamily() {
        assertTrue(ConductorProtocol.modelMismatch("opus", "claude-sonnet-5-5"))
        assertTrue(!ConductorProtocol.modelMismatch("sonnet", "claude-sonnet-5-5"))
        assertTrue(!ConductorProtocol.modelMismatch("", "claude-sonnet-5-5"))
    }

    @Test
    fun instructionsExampleUsesConfiguredIdAndLimitsReportSize() {
        val text = ConductorProtocol.conductorInstructions(listOf(SubDef("s3", "Gemini", "검토")), "medium")
        assertTrue(text.contains("- s3: Gemini — 검토") && text.contains("\"id\":\"s3\""))
        val long = List(5) { SubResult("s${it + 1}", true, "가".repeat(50_000)) }
        assertTrue(ConductorProtocol.reportPrompt(emptyList(), long, 1, 3).length < ConductorProtocol.REPORT_TOTAL_LIMIT + 2_000)
    }

    @Test
    fun hideBlockAlsoHidesUnfinishedBlock() {
        assertEquals("앞", ConductorProtocol.hideBlock("앞\n${ConductorProtocol.OPEN}\n[{\"id\""))
        assertEquals("앞", ConductorProtocol.hideBlock("앞\n" + block(two)))
    }

    private class FakePort(val mainReplies: List<String>, val failing: Set<String> = emptySet()) : ConductorPort {
        val mainPrompts = mutableListOf<String>()
        val subCalls = mutableListOf<Pair<String, List<String>>>()
        override suspend fun askMain(prompt: String, shownSoFar: String): Pair<String, String?> {
            mainPrompts.add(prompt)
            return mainReplies[mainPrompts.size - 1] to null
        }
        override suspend fun runSub(task: SubTask, dependencies: List<SubResult>, round: Int): SubResult {
            subCalls.add(task.id to dependencies.map { it.output })
            return SubResult(task.id, task.id !in failing, "결과-${task.id}-$round")
        }
        override fun canRunConcurrently(subId: String) = false
        override fun showProgress(text: String) {}
    }

    @Test
    fun smallTaskRunsNoSubs() = runBlocking {
        val port = FakePort(listOf("42"))
        val out = Conductor(ids, 3, port).run("질문")
        assertEquals(0, out.subRunCount)
        assertEquals("42", out.transcript)
        assertNull(out.error)
    }

    @Test
    fun delegatesPassesDependencyReviewsAndRedelegates() = runBlocking {
        val port = FakePort(listOf("틀\n" + block(two), "다시\n" + block("""[{"id":"s2","task":"수정"}]"""), "최종 보고"))
        val out = Conductor(ids, 3, port).run("큰 작업")
        assertEquals(listOf("s1", "s2", "s2"), port.subCalls.map { it.first })
        assertEquals(listOf("결과-s1-1"), port.subCalls[1].second)
        assertTrue(port.mainPrompts[1].contains("결과-s2-1"))
        assertEquals(3, out.subRunCount)
        assertTrue(out.transcript.contains("틀") && out.transcript.endsWith("최종 보고"))
        assertTrue(!out.transcript.contains("결과-s1-1"))
    }

    @Test
    fun stopsAtRoundLimit() = runBlocking {
        val again = block("""[{"id":"s1","task":"또"}]""")
        val port = FakePort(listOf(again, again, again))
        val out = Conductor(ids, 2, port).run("x")
        assertTrue(out.hitLimit)
        assertEquals(2, out.subRunCount)
        assertTrue(port.mainPrompts[2].contains("위임 한도"))
    }

    @Test
    fun asksRewriteThenFailsLoudly() = runBlocking {
        val bad = block("[{id:}]")
        val port = FakePort(listOf(bad, bad, bad))
        val out = Conductor(ids, 3, port).run("x")
        assertNotNull(out.error)
        assertEquals(3, port.mainPrompts.size)
        assertEquals(0, out.subRunCount)
    }

    @Test
    fun skipsDependentTaskWhenDependencyFails() = runBlocking {
        val port = FakePort(listOf(block(two), "보고"), failing = setOf("s1"))
        val out = Conductor(ids, 3, port).run("x")
        assertEquals(listOf("s1"), port.subCalls.map { it.first })
        assertEquals(1, out.subRunCount)
        assertTrue(port.mainPrompts[1].contains("실행하지 않았습니다"))
    }
}
