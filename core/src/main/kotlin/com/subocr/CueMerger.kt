package com.subocr

import kotlin.math.max

/** Bỏ phiếu giữa các lần OCR của cùng một cue: chọn cụm kết quả giống nhau có tổng confidence cao nhất. */
object Voter {
    fun pick(rs: List<SampleResult>, sim: Float): SampleResult? {
        if (rs.isEmpty()) return null
        if (rs.size == 1) return rs[0]
        var best: SampleResult? = null
        var bestScore = -1f
        for (c in rs) {
            var score = c.conf * 0.01f
            for (o in rs) if (Similarity.ratio(c.text, o.text) >= sim) score += o.conf
            if (score > bestScore) { best = c; bestScore = score }
        }
        return best
    }
}

/**
 * Gộp cue liền kề có chữ gần như giống nhau và khoảng hở nhỏ (cue bị tách do nhiễu nền chuyển động).
 * Giữ lại một cue "đang chờ"; cue chỉ được phát ra khi biết chắc không còn gộp tiếp.
 */
class CueMerger(private val cfg: PipelineConfig, private val out: (Cue) -> Unit) {
    private var held: Cue? = null

    fun add(c: Cue) {
        val h = held
        if (h == null) { held = c; return }
        val gap = c.startUs - h.endUs
        if (gap <= cfg.mergeGapUs && Similarity.ratio(h.text, c.text) >= cfg.mergeSim) {
            val better = if (c.confidence > h.confidence) c else h
            held = Cue(h.startUs, max(h.endUs, c.endUs), better.text, better.confidence)
        } else {
            out(h)
            held = c
        }
    }

    fun flush() {
        held?.let(out)
        held = null
    }
}
