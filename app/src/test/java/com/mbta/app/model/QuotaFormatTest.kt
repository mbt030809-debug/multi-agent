package com.mbta.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaFormatTest {
    @Test
    fun formatsRemainingTime() {
        assertEquals("12분", formatRemaining(12 * 60_000L))
        assertEquals("2시간 15분", formatRemaining((2 * 60 + 15) * 60_000L))
        assertEquals("3시간", formatRemaining(3 * 3_600_000L))
        assertEquals("3일 4시간", formatRemaining((3 * 24 + 4) * 3_600_000L))
        assertEquals("1일", formatRemaining(24 * 3_600_000L))
    }

    @Test
    fun resetSummary() {
        val now = 1_000_000_000_000L
        assertEquals("", formatResetSummary(0L, now))
        assertEquals("곧 초기화", formatResetSummary(now - 1000L, now))
        val s = formatResetSummary(now + 2 * 3_600_000L, now)
        assertTrue(s.startsWith("2시간 후 · "))
    }
}
