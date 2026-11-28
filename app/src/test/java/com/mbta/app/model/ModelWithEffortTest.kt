package com.mbta.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelWithEffortTest {
    private val agy = listOf(
        "Gemini 4 Neon (Low)", "Gemini 4 Neon (Medium)", "Gemini 4 Neon (High)",
        "Claude Sonnet 5.5 (Medium)", "Claude Opus 5.5 (Medium)"
    )

    @Test
    fun appliesEffortToVariantName() {
        assertEquals("Gemini 4 Neon (High)", modelWithEffort("Gemini 4 Neon", "High", agy))
        assertEquals("Gemini 4 Neon (Low)", modelWithEffort("Gemini 4 Neon", "low", agy))
    }

    @Test
    fun keepsModelWhenAlreadyHasEffortOrNoVariant() {
        assertEquals("Gemini 4 Neon (Medium)", modelWithEffort("Gemini 4 Neon (Medium)", "High", agy))
        assertEquals("claude-opus-4-6", modelWithEffort("claude-opus-4-6", "high", listOf("sonnet", "opus")))
        assertEquals("Gemini 4 Neon", modelWithEffort("Gemini 4 Neon", "", agy))
    }

    @Test
    fun noVariantForEffortKeepsOriginal() {
        assertEquals("Claude Opus 5.5", modelWithEffort("Claude Opus 5.5", "High", agy))
    }
}
