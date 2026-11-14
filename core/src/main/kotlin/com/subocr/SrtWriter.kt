package com.subocr

object SrtWriter {
    fun timestamp(us: Long): String {
        val ms = (us / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }

    fun format(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n')
            append(timestamp(c.startUs)).append(" --> ").append(timestamp(maxOf(c.endUs, c.startUs + 1000))).append('\n')
            append(c.text).append("\n\n")
        }
    }
}
