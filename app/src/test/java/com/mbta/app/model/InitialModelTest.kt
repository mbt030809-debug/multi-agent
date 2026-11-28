package com.mbta.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class InitialModelTest {
    @Test
    fun webConnectionStartsWithoutModel() {
        val web = AiConnection(id = "g", name = "Gemini", kind = ConnectionKind.GEMINI_WEB, model = "Gemini 2.5 Pro")
        assertEquals("", initialModelFor(web))
        assertEquals("웹 기본", unsetModelLabel(web))
    }

    @Test
    fun apiConnectionKeepsItsModel() {
        val api = AiConnection(id = "a", name = "API", kind = ConnectionKind.CLAUDE_API, model = "claude-haiku-5-5")
        assertEquals("claude-haiku-5-5", initialModelFor(api))
        assertEquals("기본", unsetModelLabel(api))
    }
}
