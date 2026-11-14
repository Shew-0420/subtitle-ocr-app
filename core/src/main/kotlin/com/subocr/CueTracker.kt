package com.subocr

import java.util.concurrent.Future

class SampleResult(val text: String, val conf: Float, val ptsUs: Long)

class PendingCue(val startUs: Long, val endUs: Long, val samples: List<Future<SampleResult?>>)

/** Gửi một khung lên hàng đợi OCR (cắt ảnh ngay trên thread gọi, OCR chạy ở worker). */
fun interface Sampler {
    fun submit(index: Int, cueStartUs: Long, ptsUs: Long, luma: ByteArray, mask: TextMask, builder: MaskBuilder): Future<SampleResult?>
}

/**
 * Máy trạng thái cue, chạy tuần tự theo khung. Thời gian start/end lấy từ MASK (khung chữ xuất hiện/biến mất),
 * không phụ thuộc độ trễ OCR.
 *
 *   IDLE ──mask hợp lệ──► PENDING ──ổn định N khung──► SHOWING ──khác M khung liên tiếp──► đóng cue
 */
class CueTracker(
    private val cfg: PipelineConfig,
    roiW: Int,
    roiH: Int,
    private val sampler: Sampler,
    private val onClosed: (PendingCue) -> Unit,
) {
    private enum class S { IDLE, PENDING, SHOWING }

    private val mb = MaskBuilder(cfg, roiW, roiH)
    private val cur = mb.newMask()
    private val ref = mb.newMask()

    private var state = S.IDLE
    private var chainStartUs = 0L      // thời điểm chữ bắt đầu xuất hiện (giữ nguyên khi chữ hiện dần)
    private var stable = 0
    private var cueStartUs = 0L
    private var breakCount = 0
    private var breakUs = 0L           // thời điểm khung "khác" đầu tiên = end của cue
    private var samples = ArrayList<Future<SampleResult?>>()
    private var lastSampleUs = 0L
    private var lastPtsUs = 0L
    private var lastDeltaUs = 33_000L

    fun onFrame(ptsUs: Long, luma: ByteArray) {
        if (lastPtsUs != 0L && ptsUs > lastPtsUs) lastDeltaUs = ptsUs - lastPtsUs
        lastPtsUs = ptsUs

        mb.compute(luma, cur)
        val valid = mb.analyze(cur)

        when (state) {
            S.IDLE -> if (valid) startChain(ptsUs)

            S.PENDING -> {
                if (!valid) { state = S.IDLE; return }
                when (mb.relation(ref, cur)) {
                    Rel.SAME -> {
                        stable++
                        if (cur.on > ref.on) ref.copyFrom(cur)
                        if (stable >= cfg.stableFrames) confirm(ptsUs, luma)
                    }
                    Rel.GREW -> { ref.copyFrom(cur); stable = 1 }   // chữ đang hiện dần: giữ chainStart
                    Rel.CHANGED -> startChain(ptsUs)
                }
            }

            S.SHOWING -> {
                val rel = if (valid) mb.relation(ref, cur) else Rel.CHANGED
                if (rel == Rel.SAME) {
                    breakCount = 0
                    if (samples.size < cfg.maxSamples && ptsUs - lastSampleUs >= cfg.sampleGapUs) sample(ptsUs, luma)
                } else {
                    if (breakCount == 0) breakUs = ptsUs
                    breakCount++
                    if (breakCount >= cfg.breakFrames) {
                        close(breakUs)
                        if (valid) startChain(breakUs) else state = S.IDLE
                    }
                }
            }
        }
    }

    /** Hết dữ liệu: đóng cue đang hiển thị (nếu có). */
    fun flush() {
        if (state == S.SHOWING) close(if (breakCount > 0) breakUs else lastPtsUs + lastDeltaUs)
        state = S.IDLE
    }

    /** Bắt đầu chuỗi chữ mới; [startUs] là thời điểm chữ bắt đầu xuất hiện (có thể sớm hơn khung hiện tại). */
    private fun startChain(startUs: Long) {
        ref.copyFrom(cur)
        chainStartUs = startUs
        stable = 1
        breakCount = 0
        state = S.PENDING
    }

    private fun confirm(ptsUs: Long, luma: ByteArray) {
        state = S.SHOWING
        cueStartUs = chainStartUs
        breakCount = 0
        samples = ArrayList()
        sample(ptsUs, luma)
    }

    private fun sample(ptsUs: Long, luma: ByteArray) {
        samples += sampler.submit(samples.size, cueStartUs, ptsUs, luma, cur, mb)
        lastSampleUs = ptsUs
    }

    private fun close(endUs: Long) {
        onClosed(PendingCue(cueStartUs, endUs, samples))
        samples = ArrayList()
        breakCount = 0
    }
}
