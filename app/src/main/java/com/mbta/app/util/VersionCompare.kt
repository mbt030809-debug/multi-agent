package com.mbta.app.util

/** "0.8.68", "v0.8.68", "0.8.68-test3" 형식의 버전 비교 */
internal object VersionCompare {
    fun isNewer(remote: String, current: String): Boolean {
        val (r, rPre) = split(remote)
        val (c, cPre) = split(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val diff = r.getOrElse(i) { 0 }.compareTo(c.getOrElse(i) { 0 })
            if (diff != 0) return diff > 0
        }
        // 숫자가 같으면 정식판이 시험판(-test3)보다 새 버전
        return cPre && !rPre
    }

    private fun split(version: String): Pair<List<Int>, Boolean> {
        val v = version.trim().removePrefix("v")
        val numbers = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        return numbers to v.contains('-')
    }
}
