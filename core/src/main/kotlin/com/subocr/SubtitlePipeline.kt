package com.subocr

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Điều phối toàn bộ:
 *   FrameSource ─► CueTracker (mask, trạng thái, inline trên thread nguồn)
 *                     └─ khi cần đọc chữ ─► hàng đợi giới hạn ─► N worker OCR (mỗi worker 1 recognizer)
 *   CueTracker đóng cue ─► finalizer (chờ OCR, bỏ phiếu, lọc, gộp) ─► CueListener.onCue (đúng thứ tự)
 *
 * Hàng đợi OCR đầy thì thread nguồn bị chặn (backpressure): decoder tự chậm lại thay vì tràn RAM.
 */
class SubtitlePipeline(
    private val cfg: PipelineConfig,
    private val recognizerFactory: () -> LineRecognizer,
    private val listener: CueListener,
) {
    private object End

    fun run(source: FrameSource, stop: AtomicBoolean = AtomicBoolean(false)): PipelineStats {
        val t0 = System.nanoTime()
        val frames = AtomicInteger()
        val ocrCalls = AtomicInteger()
        val cuesOut = AtomicInteger()

        val recognizers = ConcurrentLinkedQueue<LineRecognizer>()
        val local = ThreadLocal.withInitial<LineRecognizer> { recognizerFactory().also { recognizers.add(it) } }

        val pool = ThreadPoolExecutor(
            cfg.workers, cfg.workers, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(cfg.queueCap),
            RejectedExecutionHandler { r, ex ->
                // Hàng đợi đầy: chặn thread nguồn cho tới khi có chỗ (backpressure).
                if (!ex.isShutdown) try { ex.queue.put(r) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            },
        )

        val sampler = Sampler { index, cueStartUs, ptsUs, luma, mask, builder ->
            val lines = builder.extractLines(luma, mask)          // copy ngay: luma sẽ bị nguồn ghi đè
            pool.submit(Callable<SampleResult?> {
                val rec = local.get()!!
                val kept = ArrayList<RecResult>(lines.size)
                for (img in lines) {
                    ocrCalls.incrementAndGet()
                    val r = rec.recognize(img)
                    val t = TextFilter.normalize(r.text)
                    if (TextFilter.accept(t, r.confidence, cfg)) kept += RecResult(t, r.confidence)
                }
                if (kept.isEmpty()) null
                else {
                    val res = SampleResult(
                        kept.joinToString("\n") { it.text },
                        kept.map { it.confidence }.average().toFloat(),
                        ptsUs,
                    )
                    if (index == 0) listener.onProvisional(cueStartUs, res.text)
                    res
                }
            }) as Future<SampleResult?>
        }

        // Finalizer: nhận cue theo thứ tự, chờ kết quả OCR của nó, bỏ phiếu, lọc, gộp, phát ra.
        val pending = LinkedBlockingQueue<Any>()
        val merger = CueMerger(cfg) { cuesOut.incrementAndGet(); listener.onCue(it) }
        val finalizer = Thread({
            while (true) {
                val item = pending.take()
                if (item === End) break
                val pc = item as PendingCue
                if (pc.endUs - pc.startUs < cfg.minCueUs) continue
                val results = pc.samples.mapNotNull { try { it.get() } catch (_: Exception) { null } }
                val best = Voter.pick(results, cfg.mergeSim) ?: continue
                merger.add(Cue(pc.startUs, pc.endUs, best.text, best.conf))
            }
            merger.flush()
        }, "cue-finalizer").apply { start() }

        var tracker: CueTracker? = null
        var curW = 0
        var curH = 0
        var firstPts = -1L
        var lastPts = 0L

        val sink = FrameSink { pts, luma, w, h ->
            if (tracker == null || w != curW || h != curH) {      // ROI đổi kích thước => dựng lại
                tracker?.flush()
                tracker = CueTracker(cfg, w, h, sampler) { pending.put(it) }
                curW = w; curH = h
            }
            tracker!!.onFrame(pts, luma)
            frames.incrementAndGet()
            if (firstPts < 0) firstPts = pts
            lastPts = pts
        }

        try {
            source.run(sink, stop)
        } finally {
            tracker?.flush()
            pending.put(End)
            finalizer.join()
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
            recognizers.forEach { runCatching { it.close() } }
        }

        val wallMs = (System.nanoTime() - t0) / 1_000_000
        return PipelineStats(frames.get(), ocrCalls.get(), cuesOut.get(), wallMs, if (firstPts < 0) 0 else lastPts - firstPts)
    }
}
