package com.mbta.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {
    @Test
    fun olderReleaseIsNotAnUpdateForTestBuild() {
        assertFalse(VersionCompare.isNewer("v0.8.67", "0.8.68-test3"))
    }

    @Test
    fun finalReleaseUpdatesItsTestBuild() {
        assertTrue(VersionCompare.isNewer("v0.8.68", "0.8.68-test3"))
        assertFalse(VersionCompare.isNewer("0.8.68-test3", "0.8.68"))
    }

    @Test
    fun comparesNumbers() {
        assertTrue(VersionCompare.isNewer("v0.8.69", "0.8.68-test3"))
        assertTrue(VersionCompare.isNewer("0.9", "0.8.68"))
        assertFalse(VersionCompare.isNewer("v0.8.68", "0.8.68"))
        assertFalse(VersionCompare.isNewer("v0.8.9", "0.8.10"))
    }
}
