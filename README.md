# subtitle-ocr-android

Pipeline trích **phụ đề cứng (hardsub)** từ video bằng OCR, viết bằng Kotlin cho Android.
Chỉ lấy phụ đề (chưa dịch). Hai chế độ dùng chung một lõi:

| Chế độ | Nguồn | Mục tiêu |
|---|---|---|
| Video có sẵn | `DecoderSource` (MediaCodec) | nhanh gấp x5 – x10 thời lượng video |
| Livestream | `ScreenSource` (MediaProjection) | realtime |

Ý tưởng chính: **OCR là bước đắt nhất, nên chỉ OCR khi thật sự cần**. Mask chữ rẻ tiền chạy trên mọi khung để biết
chữ xuất hiện/biến mất lúc nào; OCR chỉ chạy vài lần cho mỗi câu phụ đề, và chỉ chạy model *recognition*
(không cần detection vì ROI đã do người dùng chọn).

## Luồng chạy

```
FrameSource ─► CueTracker (mask + máy trạng thái, chạy ngay trên thread nguồn)
                  └─ khi cần đọc chữ ─► hàng đợi giới hạn ─► worker OCR (mỗi worker 1 recognizer)
CueTracker đóng cue ─► finalizer (chờ OCR, bỏ phiếu, lọc, gộp) ─► CueListener.onCue
```

Hàng đợi OCR đầy thì thread nguồn bị chặn (backpressure): decoder chậm lại thay vì tràn RAM.

## Cấu trúc repo

```
core/      Kotlin thuần (không phụ thuộc Android) - chạy và test được trên JVM
android/   Hai nguồn khung hình dùng API Android + ví dụ bọc PaddleOCR
```

### core/src/main/kotlin/com/subocr

| File | Chức năng |
|---|---|
| `Models.kt` | Interface và kiểu dữ liệu: `RoiPct` (ROI theo %), `LineRecognizer` (chỗ cắm OCR), `FrameSource`/`FrameSink`, `Cue`, `CueListener`, `PipelineStats` (đo tốc độ so với thời lượng video). |
| `Config.kt` | `PipelineConfig`: mọi ngưỡng, có chú thích. Chỉnh ở đây khi tinh chỉnh chất lượng/tốc độ. |
| `TextMask.kt` | Tạo mask nhị phân "pixel giống chữ" (pixel sáng có viền tối sát cạnh), kiểm tra mask có giống phụ đề không (lọc rác **trước** OCR), so sánh hai mask (`SAME`/`GREW`/`CHANGED`), tách dòng và cắt ảnh cho recognizer. |
| `CueTracker.kt` | Máy trạng thái `IDLE → PENDING → SHOWING → đóng cue`. `start`/`end` lấy từ khung mà **mask** đổi (không phụ thuộc độ trễ OCR). Nhận ra chữ hiện dần, chịu được nhiễu 1 khung (`breakFrames`), lấy mẫu OCR nhiều lần trong một cue để bỏ phiếu. |
| `TextFilter.kt` | Lọc rác sau OCR: confidence thấp, quá ít ký tự, quá nhiều ký hiệu lạ, chuỗi lặp một ký tự, watermark (`blockPatterns`). Có `Similarity` (Levenshtein). |
| `CueMerger.kt` | `Voter`: chọn kết quả tốt nhất giữa các lần OCR. `CueMerger`: gộp cue liền kề có chữ giống nhau (cue bị tách do nền chuyển động). |
| `SubtitlePipeline.kt` | Điều phối: worker pool có backpressure, mỗi worker một recognizer, thread finalizer giữ đúng thứ tự cue. `run()` trả về `PipelineStats`. |
| `SrtWriter.kt` | Xuất danh sách `Cue` ra định dạng SRT. |

### android/src/main/kotlin/com/subocr

| File | Chức năng |
|---|---|
| `DecoderSource.kt` | Video có sẵn: `MediaExtractor` + `MediaCodec` giải mã phần cứng hết công suất, chỉ đọc plane Y của vùng ROI. Có thể bỏ qua khung bằng `stride`. |
| `ScreenSource.kt` | Livestream: bắt màn hình bằng `MediaProjection` + `ImageReader` (~8 fps), cắt ROI, đổi sang luma. Rớt khung khi OCR chậm. |
| `PaddleLineRecognizerExample.kt` | Khung rỗng để nối predictor PaddleOCR recognition của bạn (3 việc cần làm được ghi trong code). |

### core/src/test/kotlin/com/subocr

| File | Chức năng |
|---|---|
| `SyntheticTest.kt` | Test trên video giả 24 giây: cắt cứng giữa hai câu, chữ hiện/mờ dần, cue quá ngắn, khung nhiễu đen, nền nhiễu mạnh, cảnh chói, cue bị đứt 100 ms. Dùng OCR giả nên không cần model. |

## Cách dùng

```kotlin
val cues = mutableListOf<Cue>()
val pipeline = SubtitlePipeline(
    PipelineConfig(workers = 2),
    { PaddleLineRecognizer(modelDir) },          // mỗi worker một predictor
    object : CueListener { override fun onCue(cue: Cue) { cues += cue } },
)
val roi = RoiPct(0.1f, 0.82f, 0.8f, 0.14f)       // ROI người dùng chọn, đổi sang tỉ lệ %

// Video có sẵn
val stats = pipeline.run(DecoderSource(roi) { it.setDataSource(context, uri, null) })
File("out.srt").writeText(SrtWriter.format(cues)); println(stats)   // stats.speed = x lần thời lượng video

// Livestream: chạy trên thread nền, đặt stop = true để dừng.
// onProvisional(...) của CueListener báo chữ ngay khi OCR xong lần đầu (dùng để hiển thị realtime).
pipeline.run(ScreenSource(projection, w, h, dpi, roi), stop)
```

Livestream cần quyền chụp màn hình và foreground service kiểu `mediaProjection` (bắt buộc từ Android 14).

## Chạy test lõi trên JVM

Cần JDK và `kotlinc`:

```bash
kotlinc core/src/main/kotlin/com/subocr/*.kt core/src/test/kotlin/com/subocr/SyntheticTest.kt -d out
kotlin -cp out com.subocr.SyntheticTestKt      # in "ALL OK" nếu đạt
```

## Tinh chỉnh nhanh

| Triệu chứng | Chỉnh |
|---|---|
| Bỏ sót chữ / mask rỗng | hạ `hiThreshold`, tăng `outlineRadius`; chữ tối thì `lightText = false` |
| Nhiều rác ký tự đơn | tăng `minChars` lên 2, tăng `minConf` |
| Một câu bị tách thành nhiều cue (nền động) | tăng `lostRatio` hoặc `breakFrames`; gộp cue sẽ xử lý phần còn lại |
| Hai câu liên tiếp bị gộp thành một | hạ `gainRatio` |
| Mask là nút thắt (ROI rất lớn) | tăng `downscale` hoặc `analysisStride` |
| Watermark/logo bị đọc thành phụ đề | thêm regex vào `blockPatterns` |

## Trạng thái và giới hạn

- Phần `core` đã compile và chạy qua `SyntheticTest` trên JVM. Phần `android/` **chưa được compile** trong repo này (cần Android SDK) - hãy build thử trong project của bạn.
- Chưa đo tốc độ x5–x10 trên điện thoại thật; dùng `PipelineStats.speed` với PaddleOCR thật để kiểm tra.
- Ngưỡng sáng cố định, chưa tự hiệu chỉnh; chữ màu có độ sáng thấp cần chỉnh tay.
- `DecoderSource` chưa xử lý metadata xoay video (`KEY_ROTATION`).
- `ScreenSource` tính ROI trên toàn màn hình; nội dung DRM/`FLAG_SECURE` ra khung đen.
- `CueMerger` giữ cue cuối chờ cue sau, nên cue "cuối cùng" của livestream trễ một cue (dùng `onProvisional` để hiển thị).
