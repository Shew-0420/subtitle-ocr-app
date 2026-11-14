package com.subocr

/**
 * VÍ DỤ khung bọc PaddleOCR của bạn. Thay phần TODO bằng lời gọi predictor recognition sẵn có
 * (Paddle Lite / ONNX / NCNN). Mỗi worker thread sẽ gọi factory một lần => mỗi thread một predictor riêng.
 *
 *   val pipeline = SubtitlePipeline(cfg, { PaddleLineRecognizer(modelDir) }, listener)
 */
class PaddleLineRecognizer(private val modelDir: String) : LineRecognizer {
    // private val predictor = TODO("khởi tạo predictor recognition (đã quantize int8)")

    override fun recognize(img: GrayImage): RecResult {
        // 1) Đổi img.pixels (xám 8-bit, img.width x img.height) sang tensor đầu vào của model:
        //    resize về chiều cao cố định (ví dụ 48), giữ tỉ lệ, chuẩn hóa, nhân bản 3 kênh nếu model cần RGB.
        // 2) Chạy predictor, giải mã CTC ra chuỗi + độ tin cậy trung bình.
        // 3) return RecResult(text, confidence)
        TODO("nối vào predictor PaddleOCR recognition của bạn")
    }

    override fun close() {
        // predictor.release()
    }
}
