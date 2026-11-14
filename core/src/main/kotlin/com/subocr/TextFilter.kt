package com.subocr

import kotlin.math.max

object TextFilter {
    private const val COMMON_PUNCT = ".,!?;:'\"-…—~·。，！？、；：「」『』（）《》“”‘’()[]♪"

    /** Bỏ ký tự điều khiển / zero-width, gộp khoảng trắng. */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        var lastSpace = true
        for (c in s) {
            when {
                c in '\u200B'..'\u200F' || c == '\uFEFF' || (c.isISOControl() && !c.isWhitespace()) -> {}
                c.isWhitespace() -> if (!lastSpace) { sb.append(' '); lastSpace = true }
                else -> { sb.append(c); lastSpace = false }
            }
        }
        return sb.toString().trim()
    }

    /** true nếu dòng chữ là phụ đề hợp lệ, false nếu là rác OCR. */
    fun accept(text: String, conf: Float, cfg: PipelineConfig): Boolean {
        if (text.isEmpty() || conf < cfg.minConf) return false
        var content = 0
        var junk = 0
        for (c in text) {
            when {
                c.isLetterOrDigit() -> content++
                c.isWhitespace() || c in COMMON_PUNCT -> {}
                else -> junk++
            }
        }
        if (content < cfg.minChars) return false
        if (junk.toFloat() / text.length > cfg.maxJunkRatio) return false
        if (isRepeated(text)) return false
        if (cfg.blockPatterns.any { it.containsMatchIn(text) }) return false
        return true
    }

    /** "一一一一", "||||", "....": một ký tự chiếm >= 70% chuỗi dài >= 4. */
    private fun isRepeated(text: String): Boolean {
        val t = text.filterNot { it.isWhitespace() }
        if (t.length < 4) return false
        val counts = HashMap<Char, Int>()
        var best = 0
        for (c in t) best = max(best, counts.merge(c, 1, Int::plus)!!)
        return best >= t.length * 0.7f
    }
}

object Similarity {
    /** 1.0 = giống hệt, 0.0 = hoàn toàn khác (Levenshtein, bỏ khoảng trắng). */
    fun ratio(a0: String, b0: String): Float {
        val a = a0.filterNot { it.isWhitespace() }
        val b = b0.filterNot { it.isWhitespace() }
        val m = max(a.length, b.length)
        if (m == 0) return 1f
        return 1f - levenshtein(a, b).toFloat() / m
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
