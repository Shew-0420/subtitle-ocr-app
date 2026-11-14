package com.subocr

data class PipelineConfig(
    // ---- Phân tích khung hình (mask chữ) ----
    /** Chỉ phân tích mỗi N khung (video có sẵn). 1 = mọi khung. Tăng lên 2-3 nếu mask là nút thắt. */
    val analysisStride: Int = 1,
    /** Giảm kích thước mask đi N lần (2 = còn 1/4 số pixel). */
    val downscale: Int = 2,
    /** true = chữ sáng trên nền tối/viền tối (phổ biến). false = chữ tối. */
    val lightText: Boolean = true,
    /** Pixel có độ sáng >= ngưỡng này mới là ứng viên chữ. */
    val hiThreshold: Int = 190,
    /** Viền chữ: phải có pixel tối <= ngưỡng này trong bán kính [outlineRadius] px. */
    val loThreshold: Int = 100,
    val outlineRadius: Int = 3,

    // ---- Kiểm tra mask hợp lệ (lọc rác TRƯỚC khi OCR) ----
    val minFill: Float = 0.003f,
    val maxFill: Float = 0.30f,
    val minLineHeightPx: Int = 14,
    val maxLines: Int = 3,

    // ---- Theo dõi cue ----
    /** Nếu > tỉ lệ này số pixel chữ của khung tham chiếu biến mất => đổi chữ. */
    val lostRatio: Float = 0.35f,
    /** Nếu số pixel chữ mới xuất hiện > tỉ lệ này so với tham chiếu => chữ đang hiện dần / thêm dòng. */
    val gainRatio: Float = 0.40f,
    /** Số khung liên tiếp mask ổn định trước khi xác nhận cue và OCR. */
    val stableFrames: Int = 2,
    /** Số khung liên tiếp "khác" mới đóng cue (chống nhiễu 1 khung). */
    val breakFrames: Int = 2,
    /** Khoảng cách giữa các lần OCR trong cùng một cue (để bỏ phiếu). */
    val sampleGapMs: Long = 500,
    val maxSamples: Int = 3,
    val minCueMs: Long = 250,

    // ---- Lọc kết quả OCR ----
    val minConf: Float = 0.60f,
    /** Số ký tự nội dung tối thiểu (chữ/số/CJK). Tăng lên 2 nếu nhiều rác 1 ký tự. */
    val minChars: Int = 1,
    val maxJunkRatio: Float = 0.35f,
    /** Regex loại bỏ watermark/logo cố định, ví dụ Regex("bilibili|Vietsub"). */
    val blockPatterns: List<Regex> = emptyList(),

    // ---- Gộp cue ----
    val mergeSim: Float = 0.85f,
    val mergeGapMs: Long = 300,

    // ---- Luồng ----
    val workers: Int = 2,
    val queueCap: Int = 8,
    val cropPadPx: Int = 6,
) {
    val sampleGapUs get() = sampleGapMs * 1000
    val minCueUs get() = minCueMs * 1000
    val mergeGapUs get() = mergeGapMs * 1000
}
