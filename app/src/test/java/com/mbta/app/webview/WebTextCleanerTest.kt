package com.mbta.app.webview

import org.junit.Assert.assertEquals
import org.junit.Test

class WebTextCleanerTest {
    @Test
    fun removesTimeLabelsAndDuplicateLines() {
        val raw = "서브 3개를 실행했습니다.\n실행됨 하위 에이전트 3개\n\n실행됨 하위 에이전트 3개\n\n지금\n결과 보고"
        assertEquals("서브 3개를 실행했습니다.\n실행됨 하위 에이전트 3개\n\n결과 보고", WebTextCleaner.clean(raw))
    }

    @Test
    fun removesIconGlyphsAndLoneIdeographs() {
        assertEquals("완료", WebTextCleaner.clean("\n箭\n완료\n坟"))
    }

    @Test
    fun keepsNormalTextAndRelativeTimeInsideSentences() {
        val raw = "3분 전에 시작했고 지금 끝났습니다.\n- 항목"
        assertEquals(raw, WebTextCleaner.clean(raw))
    }

    @Test
    fun removesRelativeTimeLines() {
        assertEquals("답변", WebTextCleaner.clean("3분 전\n답변\n방금"))
    }

    @Test
    fun keepsShortRepeatedCodeLines() {
        val raw = "fun a() {\n    if (x) {\n    }\n}\n}"
        assertEquals(raw, WebTextCleaner.clean(raw))
    }
}
