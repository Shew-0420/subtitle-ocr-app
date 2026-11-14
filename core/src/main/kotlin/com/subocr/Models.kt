package com.subocr

import java.util.concurrent.atomic.AtomicBoolean

/** ROI theo tỉ lệ 0..1 của khung hình (lưu theo %, không theo pixel, để không lệch khi đổi độ phân giải/xoay). */
data class RoiPct(val left: Float, val top: Float, val width: Float, val height: Float) {
    init {
        require(width > 0f && height > 0f && left >= 0f && top >= 0f) { "ROI không hợp lệ" }
    }

    /** Trả về [x, y, w, h] theo pixel của khung có kích thước frameW x frameH. */
    fun toPx(frameW: Int, frameH: Int): IntArray {
        val x = (left * frameW).toInt().coerceIn(0, frameW - 2)
        val y = (top * frameH).toInt().coerceIn(0, frameH - 2)
        val w = (width * frameW).toInt().coerceIn(2, frameW - x)
        val h = (height * frameH).toInt().coerceIn(2, frameH - y)
        return intArrayOf(x, y, w, h)
    }
}

/** Ảnh xám 8-bit (một dòng chữ đã cắt) đưa vào model nhận dạng. */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray)

data class RecResult(val text: String, val confidence: Float)

/**
 * Bọc PaddleOCR của bạn ở đây. Chỉ cần model RECOGNITION (không cần detection) vì ROI đã cắt sẵn.
 * Mỗi worker thread nhận một instance riêng (factory được gọi theo thread), nên không cần thread-safe.
 */
interface LineRecognizer : AutoCloseable {
    fun recognize(img: GrayImage): RecResult
    override fun close() {}
}

data class Cue(val startUs: Long, val endUs: Long, val text: String, val confidence: Float)

interface CueListener {
    /** Gọi (trên thread OCR) ngay khi có kết quả đầu tiên của một cue đang hiển thị: dùng cho livestream. */
    fun onProvisional(startUs: Long, text: String) {}

    /** Cue cuối cùng, đã bỏ phiếu + lọc + gộp, theo đúng thứ tự thời gian (gọi trên thread finalizer). */
    fun onCue(cue: Cue)
}

/** Nhận khung đã cắt ROI (luma 8-bit). Buffer được nguồn tái sử dụng, nên KHÔNG giữ lại sau khi trả về. */
fun interface FrameSink {
    fun onFrame(ptsUs: Long, luma: ByteArray, width: Int, height: Int)
}

interface FrameSource {
    /** Thời lượng video (µs), -1 nếu không biết (livestream). */
    val durationUs: Long

    /** Chạy chặn (blocking) cho tới khi hết video hoặc stop = true. */
    fun run(sink: FrameSink, stop: AtomicBoolean)
}

data class PipelineStats(
    val framesAnalyzed: Int,
    val ocrCalls: Int,
    val cues: Int,
    val wallMs: Long,
    val mediaUs: Long,
) {
    /** Tốc độ so với thời lượng video: 5.0 nghĩa là nhanh gấp 5 lần video. */
    val speed: Double get() = if (wallMs <= 0) 0.0 else (mediaUs / 1000.0) / wallMs
    override fun toString() =
        "frames=$framesAnalyzed ocr=$ocrCalls cues=$cues wall=${wallMs}ms media=${mediaUs / 1000}ms speed=${"%.1f".format(speed)}x"
}
