package com.mbta.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentProtocolTest {
    @Test
    fun parsesModelField() {
        val r = SubagentProtocol.parse("""분석 끝
<<SUBAGENTS>>
[{"role":"조사","task":"README.md 읽고 요약","model":"haiku"},{"role":"검토","task":"build.gradle.kts 확인"}]
<</SUBAGENTS>>""")
        assertNull(r.error)
        assertEquals(2, r.requests.size)
        assertEquals("haiku", r.requests[0].model)
        assertEquals("", r.requests[1].model)
    }

    @Test
    fun invalidJsonReturnsError() {
        val r = SubagentProtocol.parse("<<SUBAGENTS>>[{role:}<</SUBAGENTS>>")
        assertTrue(r.hasBlock)
        assertNotNull(r.error)
        assertTrue(r.requests.isEmpty())
    }

    @Test
    fun noBlockIsNotAnError() {
        val r = SubagentProtocol.parse("그냥 답변")
        assertFalse(r.hasBlock)
        assertNull(r.error)
    }

    @Test
    fun resolveModelMatchesAliasOrFallsBack() {
        val models = listOf("claude-sonnet-5-5", "claude-haiku-5-5")
        assertEquals("claude-haiku-5-5", SubagentProtocol.resolveModel("haiku", models, "claude-sonnet-5-5"))
        assertEquals("claude-sonnet-5-5", SubagentProtocol.resolveModel("", models, "claude-sonnet-5-5"))
        assertEquals("default", SubagentProtocol.resolveModel("gpt-9", models, "default"))
    }

    @Test
    fun overlappingWritesAreSerialized() {
        val reqs = listOf(
            SubagentRequest("A", "MainScreen.kt 수정해서 버튼 추가"),
            SubagentRequest("B", "MainScreen.kt 에서 색상 변경"),
            SubagentRequest("C", "README.md 읽고 요약")
        )
        val batches = SubagentProtocol.batches(reqs, 3)
        assertEquals(2, batches.size)
        assertTrue(batches[0].contains(0))
        assertTrue(batches[1].contains(1))
        assertTrue(batches[0].contains(2))
    }

    @Test
    fun readOnlyTasksStayParallelAndCapApplies() {
        val reqs = (1..4).map { SubagentRequest("R$it", "Foo.kt 읽고 설명") }
        val batches = SubagentProtocol.batches(reqs, 3)
        assertEquals(listOf(3, 1), batches.map { it.size })
    }

    @Test
    fun detectsModelMismatch() {
        assertTrue(SubagentProtocol.modelMismatch("opus", "claude-sonnet-5-5"))
        assertFalse(SubagentProtocol.modelMismatch("sonnet", "claude-sonnet-5-5"))
        assertFalse(SubagentProtocol.modelMismatch("", "claude-sonnet-5-5"))
    }
}
