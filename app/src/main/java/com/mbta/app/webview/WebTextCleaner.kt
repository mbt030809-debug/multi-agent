package com.mbta.app.webview

/** 웹 화면에서 긁어온 텍스트 정리: 아이콘 글꼴 글자, 낱자 한자 줄, 시간 표시("지금", "3분 전"), 연속 중복 줄 제거 */
internal object WebTextCleaner {
    private val puaRegex = Regex("[\\uE000-\\uF8FF]")
    private val loneIdeographLine = Regex("^\\s*[\\u4E00-\\u9FFF]\\s*$")
    private val timeLabelLine = Regex("^\\s*(지금|방금|방금 전|just now|now|\\d+\\s*(초|분|시간|일)\\s*전|\\d+\\s*(s|m|h|d|min|mins|hr|hrs)\\s*ago)\\s*$", RegexOption.IGNORE_CASE)

    private val statusLine = Regex("^\\s*(세션\\s*초기화됨|session\\s*(initialized|started)|작업\\s*중\\.{0,3}|working\\.{0,3})\\s*[>›]?\\s*$", RegexOption.IGNORE_CASE)

    /** 앞 블록이 뒤 블록의 앞부분(스트리밍 중간 상태)이면 앞 블록 제거 */
    private fun dropPrefixBlocks(text: String): String {
        val blocks = text.split(Regex("\\n{2,}"))
        if (blocks.size < 2) return text
        val keep = blocks.filterIndexed { i, b ->
            val t = b.trim()
            t.isEmpty() || blocks.drop(i + 1).none { o -> val ot = o.trim(); ot.length > t.length && ot.startsWith(t) }
        }
        return keep.joinToString("\n\n")
    }

    /** final=false(스트리밍 중)는 진행 상태·중간 내용을 그대로 보여주고, final=true(최종 답)에서만 상태 줄/중간본을 제거해 대체 */
    fun clean(raw: String, final: Boolean = true): String {
        val lines = raw.replace(puaRegex, "").lines()
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            if (loneIdeographLine.matches(line) || timeLabelLine.matches(line) || (final && statusLine.matches(line))) continue
            // 바로 앞의 (비어 있지 않은) 줄과 똑같은 줄은 한 번만 (사이에 빈 줄이 있어도)
            if (line.trim().length >= 8 && !line.trim().startsWith("|")) {
                val lastNonBlank = out.lastOrNull { it.isNotBlank() }
                if (lastNonBlank != null && lastNonBlank.trim() == line.trim()) {
                    while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.size - 1)
                    continue
                }
            }
            out.add(line)
        }
        val joined = out.joinToString("\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        return if (final) dropPrefixBlocks(joined).trim() else joined
    }
}
