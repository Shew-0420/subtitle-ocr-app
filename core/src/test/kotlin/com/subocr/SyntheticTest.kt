package com.subocr

import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

const val W = 640
const val H = 80
const val FPS = 30

/** Kịch bản: (startSec, endSec, glyphCount, fadeSec, noise) */
data class Ev(val s: Double, val e: Double, val n: Int, val fade: Double = 0.0, val noise: Double = 0.0)

val events = listOf(
    Ev(1.0, 3.0, 4),              // A
    Ev(3.0, 5.0, 6),              // B (cắt cứng ngay sau A)
    Ev(6.0, 8.0, 5, fade = 0.3),  // C (hiện dần / mờ dần)
    Ev(9.0, 9.2, 3),              // D (quá ngắn, phải bị loại)
    Ev(10.0, 12.0, 7),            // E (có 1 khung nhiễu đen giữa chừng)
    Ev(13.0, 16.0, 8, noise = 0.01), // F (nền nhiễu mạnh)
    Ev(20.0, 21.0, 5),            // H1
    Ev(21.1, 22.0, 5),            // H2 (cách H1 ~100ms => phải gộp)
)

fun drawFrame(buf: ByteArray, t: Double, frame: Int, rnd: Random) {
    val ev = events.firstOrNull { t >= it.s && t < it.e }
    val noiseP = ev?.noise ?: 0.0
    // nền chuyển động
    for (y in 0 until H) for (x in 0 until W) {
        val v = 110 + 90 * sin((x + frame * 7) / 17.0) * sin((y + frame * 3) / 9.0)
        buf[y * W + x] = v.toInt().coerceIn(0, 255).toByte()
    }
    // cảnh quá sáng 17.0-17.5: sọc sáng/tối dày đặc
    if (t >= 17.0 && t < 17.5) {
        for (y in 0 until H) for (x in 0 until W) buf[y * W + x] = if ((x / 3) % 2 == 0) 235.toByte() else 20
    }
    // nhiễu muối (cặp sáng-tối)
    if (noiseP > 0) for (y in 1 until H - 1) for (x in 1 until W - 1) if (rnd.nextDouble() < noiseP) {
        buf[y * W + x] = 255.toByte(); buf[y * W + x + 1] = 0
    }
    if (ev != null) {
        // 1 khung nhiễu đen giữa chừng của E
        if (ev.s == 10.0 && frame == 11 * FPS) { java.util.Arrays.fill(buf, 0.toByte()); return }
        var bright = 230
        if (ev.fade > 0) {
            val a = minOf((t - ev.s) / ev.fade, (ev.e - t) / ev.fade).coerceIn(0.0, 1.0)
            bright = (60 + 170 * a).toInt()
        }
        for (i in 0 until ev.n) {
            val x0 = 40 + i * 20
            for (y in 17 until 53) for (x in x0 - 3 until x0 + 17) buf[y * W + x] = 10   // viền tối
            for (y in 20 until 50) for (x in x0 until x0 + 14) buf[y * W + x] = bright.toByte()
        }
    }
}

class FakeSource(val seconds: Int) : FrameSource {
    override val durationUs = seconds * 1_000_000L
    override fun run(sink: FrameSink, stop: AtomicBoolean) {
        val buf = ByteArray(W * H)
        val rnd = Random(1)
        for (f in 0 until seconds * FPS) {
            if (stop.get()) break
            val t = f.toDouble() / FPS
            drawFrame(buf, t, f, rnd)
            sink.onFrame((t * 1_000_000).toLong(), buf, W, H)
        }
    }
}

/** Đếm số khối sáng ở hàng giữa của ảnh cắt => "chữ" giả = "字幕<n>". */
class FakeRecognizer(val delayMs: Long) : LineRecognizer {
    override fun recognize(img: GrayImage): RecResult {
        if (delayMs > 0) Thread.sleep(delayMs)
        val row = img.height / 2
        var runs = 0; var inRun = false
        for (x in 0 until img.width) {
            val b = (img.pixels[row * img.width + x].toInt() and 0xFF) >= 180
            if (b && !inRun) runs++
            inRun = b
        }
        return RecResult("字幕$runs", 0.92f)
    }
}

fun main() {
    val cfg = PipelineConfig(workers = 2, queueCap = 2)
    val out = ArrayList<Cue>()
    val provisional = ArrayList<String>()
    val pipe = SubtitlePipeline(cfg, { FakeRecognizer(15) }, object : CueListener {
        override fun onProvisional(startUs: Long, text: String) { synchronized(provisional) { provisional += "%.2f:%s".format(startUs / 1e6, text) } }
        override fun onCue(cue: Cue) { out += cue }
    })
    val stats = pipe.run(FakeSource(24))
    println(stats)
    println(SrtWriter.format(out))
    println("provisional: " + provisional.sorted())
    val expected = listOf(
        Triple(1.0, 3.0, "字幕4"), Triple(3.0, 5.0, "字幕6"), Triple(6.23, 7.77, "字幕5"),
        Triple(10.0, 12.0, "字幕7"), Triple(13.0, 16.0, "*"), Triple(20.0, 22.0, "字幕5"),
    )
    var ok = out.size == expected.size
    println("cues=${out.size} expected=${expected.size}")
    for ((i, e) in expected.withIndex()) {
        val c = out.getOrNull(i) ?: run { ok = false; null } ?: continue
        val ds = c.startUs / 1e6 - e.first; val de = c.endUs / 1e6 - e.second
        val good = (e.third == "*" || c.text == e.third) && kotlin.math.abs(ds) <= 0.12 && kotlin.math.abs(de) <= 0.12
        println("  cue$i ${if (good) "OK " else "BAD"} dStart=%.3f dEnd=%.3f text=%s".format(ds, de, c.text))
        if (!good) ok = false
    }
    println(if (ok) "ALL OK" else "FAILED")
}
