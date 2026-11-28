package com.mbta.app.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleWebViewPolicyTest {
    @Test
    fun checkIntervalDependsOnViewing() {
        assertEquals(3_000L, IdleWebViewPolicy.checkIntervalMs(viewing = true))
        assertEquals(15_000L, IdleWebViewPolicy.checkIntervalMs(viewing = false))
    }

    @Test
    fun sleepsOnlyWhenUnusedAndNotBusy() {
        val idle = IdleWebViewPolicy.IDLE_MS
        assertTrue(IdleWebViewPolicy.shouldSleep(keepAlive = false, busy = false, idleMs = idle))
        assertFalse(IdleWebViewPolicy.shouldSleep(keepAlive = false, busy = false, idleMs = idle - 1))
        assertFalse(IdleWebViewPolicy.shouldSleep(keepAlive = true, busy = false, idleMs = idle * 10))
        assertFalse(IdleWebViewPolicy.shouldSleep(keepAlive = false, busy = true, idleMs = idle * 10))
    }
}
