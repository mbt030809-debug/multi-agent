package com.mbta.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRefTest {
    @Test
    fun plainUrlWithExtension() {
        val r = extractFileRefs("받으세요: https://github.com/a/b/releases/download/v1/MbtA-v1.apk 입니다")
        assertEquals(1, r.size)
        assertEquals("MbtA-v1.apk", r[0].name)
    }

    @Test
    fun markdownLinkUsesLabel() {
        val r = extractFileRefs("[📎 앱 설치파일](https://x.com/files/abc.apk)")
        assertEquals(1, r.size)
        assertEquals("앱 설치파일", r[0].name)
        assertEquals("https://x.com/files/abc.apk", r[0].url)
    }

    @Test
    fun localDevicePaths() {
        val r = extractFileRefs("저장: /storage/emulated/0/Download/report.pdf 그리고 ~/mbta-uploads/shot.png")
        assertEquals(listOf("report.pdf", "shot.png"), r.map { it.name })
        assertTrue(r.all { it.path != null })
    }

    @Test
    fun ignoresSourceCodePathsPagesAndCodeFences() {
        assertTrue(extractFileRefs("/home/user/multi-agent/app/src/Main.kt 수정").isEmpty())
        assertTrue(extractFileRefs("https://claude.ai/code/session_01abc").isEmpty())
        assertTrue(extractFileRefs("```\nhttps://a.com/x.zip\n```").isEmpty())
    }

    @Test
    fun dedupesSameLink() {
        val r = extractFileRefs("[a](https://x.com/f.zip) https://x.com/f.zip")
        assertEquals(1, r.size)
    }
}
