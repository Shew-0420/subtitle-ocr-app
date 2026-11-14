package com.subocr

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Nguồn cho LIVESTREAM: bắt màn hình bằng MediaProjection, cắt ROI, đổi sang luma.
 * Yêu cầu bên ngoài: người dùng đã cấp quyền chụp màn hình và app đang chạy foreground service
 * kiểu "mediaProjection" (bắt buộc từ Android 14). Nội dung FLAG_SECURE/DRM sẽ ra khung đen.
 *
 * ROI tính theo toàn màn hình (screenW x screenH), nên nếu video chỉ chiếm một phần màn hình,
 * hãy quy đổi ROI sang tỉ lệ so với toàn màn hình trước khi truyền vào.
 */
class ScreenSource(
    private val projection: MediaProjection,
    private val screenW: Int,
    private val screenH: Int,
    private val densityDpi: Int,
    private val roi: RoiPct,
    private val fps: Int = 8,
) : FrameSource {

    override val durationUs: Long = -1L

    override fun run(sink: FrameSink, stop: AtomicBoolean) {
        val thread = HandlerThread("subocr-screen").also { it.start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        val callback = object : MediaProjection.Callback() {
            override fun onStop() { stop.set(true) }
        }
        projection.registerCallback(callback, handler)   // Android 14+: phải đăng ký trước khi tạo VirtualDisplay
        val display = projection.createVirtualDisplay(
            "subocr", screenW, screenH, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
        )

        val r = roi.toPx(screenW, screenH)
        val buf = ByteArray(r[2] * r[3])
        val minGapNs = 1_000_000_000L / fps
        var lastNs = 0L

        reader.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtimeNanos()
                if (stop.get() || now - lastNs < minGapNs) return@setOnImageAvailableListener
                lastNs = now
                val p = img.planes[0]
                val bb = p.buffer
                val rs = p.rowStride
                val ps = p.pixelStride
                for (y in 0 until r[3]) {
                    var o = (r[1] + y) * rs + r[0] * ps
                    var d = y * r[2]
                    for (x in 0 until r[2]) {
                        val R = bb.get(o).toInt() and 0xFF
                        val G = bb.get(o + 1).toInt() and 0xFF
                        val B = bb.get(o + 2).toInt() and 0xFF
                        buf[d++] = ((77 * R + 150 * G + 29 * B) shr 8).toByte()
                        o += ps
                    }
                }
                sink.onFrame(now / 1000, buf, r[2], r[3])   // chặn ở đây nếu hàng đợi OCR đầy => rớt khung, không tràn RAM
            } finally {
                img.close()
            }
        }, handler)

        try {
            while (!stop.get()) Thread.sleep(50)
        } finally {
            reader.setOnImageAvailableListener(null, null)
            thread.quitSafely()
            thread.join()                       // đảm bảo không còn onFrame nào chạy sau khi run() trả về
            runCatching { display.release() }
            runCatching { projection.unregisterCallback(callback) }
            runCatching { reader.close() }
        }
    }
}
