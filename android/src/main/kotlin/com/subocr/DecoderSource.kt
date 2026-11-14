package com.subocr

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Nguồn cho VIDEO CÓ SẴN: MediaExtractor + MediaCodec (giải mã phần cứng) chạy hết tốc độ,
 * chỉ đọc plane Y (luma) của vùng ROI, không đổi màu, không vẽ lên Surface.
 *
 * @param configure gọi để trỏ extractor tới nguồn, ví dụ { it.setDataSource(context, uri, null) }
 * @param stride chỉ phân tích mỗi [stride] khung (khung còn lại vẫn giải mã nhưng bỏ qua, không đọc ra).
 */
class DecoderSource(
    private val roi: RoiPct,
    private val stride: Int = 1,
    private val configure: (MediaExtractor) -> Unit,
) : FrameSource {

    override var durationUs: Long = -1L
        private set

    override fun run(sink: FrameSink, stop: AtomicBoolean) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            configure(extractor)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("Không có video track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION)) durationUs = format.getLong(MediaFormat.KEY_DURATION)

            // Gợi ý cho bộ giải mã: chạy hết công suất, không cần realtime.
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            format.setInteger(MediaFormat.KEY_PRIORITY, 1)

            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            pump(extractor, c, sink, stop)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun pump(extractor: MediaExtractor, codec: MediaCodec, sink: FrameSink, stop: AtomicBoolean) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var index = 0L
        var buf: ByteArray? = null
        var roiPx: IntArray? = null

        while (!outputDone && !stop.get()) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(TIMEOUT_US)
                if (i >= 0) {
                    val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (o < 0) continue                                   // chưa có khung / đổi format
            if (info.size > 0 && index % stride == 0L) {
                val image = codec.getOutputImage(o)
                if (image != null) {
                    try {
                        val crop = image.cropRect
                        if (roiPx == null) {
                            roiPx = roi.toPx(crop.width(), crop.height())
                            buf = ByteArray(roiPx[2] * roiPx[3])
                        }
                        readLuma(image, crop.left + roiPx[0], crop.top + roiPx[1], roiPx[2], roiPx[3], buf!!)
                    } finally {
                        image.close()
                    }
                    sink.onFrame(info.presentationTimeUs, buf!!, roiPx!![2], roiPx[3])
                }
            }
            codec.releaseOutputBuffer(o, false)
            index++
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
        }
    }

    /** Chép vùng (x0,y0,w,h) của plane Y vào dst (w*h byte). */
    private fun readLuma(image: Image, x0: Int, y0: Int, w: Int, h: Int, dst: ByteArray) {
        val plane = image.planes[0]
        val bb = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        if (ps == 1) {
            for (y in 0 until h) {
                bb.position((y0 + y) * rs + x0)
                bb.get(dst, y * w, w)
            }
        } else {
            for (y in 0 until h) for (x in 0 until w) dst[y * w + x] = bb.get((y0 + y) * rs + (x0 + x) * ps)
        }
    }

    private companion object { const val TIMEOUT_US = 10_000L }
}
