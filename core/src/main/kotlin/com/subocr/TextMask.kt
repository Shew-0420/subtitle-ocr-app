package com.subocr

import kotlin.math.max

/** Mask nhị phân (bit-packed) của "pixel giống chữ" trong ROI, đã giảm kích thước. */
class TextMask(val w: Int, val h: Int) {
    val bits = LongArray((w * h + 63) ushr 6)
    val rows = IntArray(h)          // số pixel chữ trên mỗi hàng (dùng tách dòng)
    var on = 0
    var minX = 0
    var maxX = 0
    var bands: List<IntRange> = emptyList()   // các dải hàng = các dòng chữ

    fun copyFrom(o: TextMask) {
        System.arraycopy(o.bits, 0, bits, 0, bits.size)
        System.arraycopy(o.rows, 0, rows, 0, rows.size)
        on = o.on; minX = o.minX; maxX = o.maxX; bands = o.bands
    }
}

enum class Rel { SAME, GREW, CHANGED }

/**
 * Tính mask chữ từ luma, kiểm tra tính hợp lệ, và so sánh hai mask.
 * Chữ phụ đề = pixel SÁNG có VIỀN TỐI sát cạnh. Vùng sáng thuần (trời, đèn) không có viền => bị loại.
 */
class MaskBuilder(private val cfg: PipelineConfig, private val roiW: Int, private val roiH: Int) {
    private val ds = cfg.downscale.coerceAtLeast(1)
    val mw = roiW / ds
    val mh = roiH / ds

    fun newMask() = TextMask(mw, mh)

    private fun lum(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return if (cfg.lightText) v else 255 - v
    }

    private fun hasDarkNeighbor(luma: ByteArray, x: Int, y: Int): Boolean {
        val lo = cfg.loThreshold
        for (d in 1..cfg.outlineRadius) {
            if (x - d >= 0 && lum(luma[y * roiW + x - d]) <= lo) return true
            if (x + d < roiW && lum(luma[y * roiW + x + d]) <= lo) return true
            if (y - d >= 0 && lum(luma[(y - d) * roiW + x]) <= lo) return true
            if (y + d < roiH && lum(luma[(y + d) * roiW + x]) <= lo) return true
        }
        return false
    }

    /** Điền mask từ khung ROI (luma roiW x roiH). */
    fun compute(luma: ByteArray, out: TextMask) {
        java.util.Arrays.fill(out.bits, 0L)
        java.util.Arrays.fill(out.rows, 0)
        var on = 0
        var minX = Int.MAX_VALUE
        var maxX = -1
        val hi = cfg.hiThreshold
        for (my in 0 until mh) {
            val y = my * ds
            for (mx in 0 until mw) {
                val x = mx * ds
                if (lum(luma[y * roiW + x]) < hi) continue
                if (!hasDarkNeighbor(luma, x, y)) continue
                val idx = my * mw + mx
                out.bits[idx ushr 6] = out.bits[idx ushr 6] or (1L shl (idx and 63))
                out.rows[my]++
                on++
                if (mx < minX) minX = mx
                if (mx > maxX) maxX = mx
            }
        }
        out.on = on
        out.minX = if (maxX < 0) 0 else minX
        out.maxX = max(maxX, 0)
        out.bands = emptyList()
    }

    /**
     * Mask có giống một dòng phụ đề không? Loại: không có chữ, quá nhiều pixel sáng (cảnh sáng),
     * quá nhiều dòng, dòng quá thấp, hoặc quá hẹp so với chiều cao dòng (đốm sáng/ánh kim loại).
     * Đồng thời điền m.bands (các dòng) dùng cho bước cắt ảnh OCR.
     */
    fun analyze(m: TextMask): Boolean {
        if (m.on == 0) return false
        val fill = m.on.toFloat() / (mw * mh)
        if (fill < cfg.minFill || fill > cfg.maxFill) return false
        val bands = findBands(m.rows)
        if (bands.isEmpty() || bands.size > cfg.maxLines) return false
        val lineH = bands.maxOf { it.last - it.first + 1 }
        val span = m.maxX - m.minX + 1
        if (span < lineH * 0.8f) return false
        m.bands = bands
        return true
    }

    private fun findBands(rows: IntArray): List<IntRange> {
        var maxRow = 0
        for (v in rows) if (v > maxRow) maxRow = v
        if (maxRow < 2) return emptyList()
        val thr = max(2, (maxRow * 0.12f).toInt())
        val minLineH = max(2, cfg.minLineHeightPx / ds)
        val mergeGap = 2
        val out = ArrayList<IntRange>(2)
        var start = -1
        var last = -1
        fun close() {
            if (start >= 0 && last - start + 1 >= minLineH) out += start..last
        }
        for (y in rows.indices) {
            if (rows[y] < thr) continue
            if (start < 0) start = y
            else if (y - last - 1 > mergeGap) { close(); start = y }
            last = y
        }
        close()
        return out
    }

    /**
     * So khung hiện tại với khung tham chiếu bằng "pixel chữ biến mất / xuất hiện thêm".
     * Bền hơn xor thuần: nhiễu nền chuyển động chỉ thêm pixel (GREW nhẹ), không làm mất pixel chữ.
     */
    fun relation(ref: TextMask, cur: TextMask): Rel {
        if (ref.on == 0) return Rel.CHANGED
        var lost = 0
        var gain = 0
        for (i in ref.bits.indices) {
            val a = ref.bits[i]
            val b = cur.bits[i]
            lost += java.lang.Long.bitCount(a and b.inv())
            gain += java.lang.Long.bitCount(b and a.inv())
        }
        val d = ref.on.toFloat()
        return when {
            lost / d > cfg.lostRatio -> Rel.CHANGED
            gain / d > cfg.gainRatio -> Rel.GREW
            else -> Rel.SAME
        }
    }

    /** Cắt từng dòng chữ (theo bands + khoảng ngang của mask) từ luma ROI, có đệm [cfg.cropPadPx]. */
    fun extractLines(luma: ByteArray, m: TextMask): List<GrayImage> {
        val pad = cfg.cropPadPx
        val x0 = (m.minX * ds - pad).coerceAtLeast(0)
        val x1 = ((m.maxX + 1) * ds + pad).coerceAtMost(roiW)
        return m.bands.map { b ->
            val y0 = (b.first * ds - pad).coerceAtLeast(0)
            val y1 = ((b.last + 1) * ds + pad).coerceAtMost(roiH)
            val cw = x1 - x0
            val ch = y1 - y0
            val out = ByteArray(cw * ch)
            for (r in 0 until ch) System.arraycopy(luma, (y0 + r) * roiW + x0, out, r * cw, cw)
            GrayImage(cw, ch, out)
        }
    }
}
