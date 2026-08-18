package com.example.remotesupportheadset

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * ONNX Runtime wrapper around a YOLOv8n detection model.
 *
 * The bundled model is trained on COCO; this wrapper filters results to the
 * "person" class (index 0) and runs lightweight IoU-based NMS.
 *
 * Input:  RGB image letterboxed to 320×320, normalized to [0, 1], NCHW.
 * Output: Normalized [0, 1] person bounding boxes with confidence scores.
 */
class YoloPersonDetector(context: Context) {

    data class Detection(
        val label: String,
        val confidence: Float,
        /** Normalized bounding box [left, top, right, bottom] in [0, 1]. */
        val rect: RectF
    )

    private data class LetterboxResult(
        val bitmap: Bitmap,
        val padLeft: Float,
        val padTop: Float,
        val scale: Float,
        val srcWidth: Int,
        val srcHeight: Int
    )

    companion object {
        private const val TAG = "YoloPersonDetector"
        private const val MODEL_NAME = "yolov8n-person-320.onnx"
        private const val INPUT_SIZE = 320
        private const val NUM_CLASSES = 80
        private const val PERSON_CLASS_ID = 0
        private const val CONFIDENCE_THRESHOLD = 0.35f
        private const val IOU_THRESHOLD = 0.45f
        private const val PAD_COLOR = 114
        /**
         * The exported ONNX model already applies sigmoid to class scores, so we
         * use the raw output values directly as probabilities.
         */
        private const val APPLY_SIGMOID_TO_SCORES = false
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        val modelFile = copyModelFromAssets(context, MODEL_NAME)
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelFile.absolutePath, options)
        inputName = session.inputNames.iterator().next()
        Log.d(TAG, "ONNX session created: input=$inputName")
    }

    private fun copyModelFromAssets(context: Context, name: String): File {
        val outFile = File(context.cacheDir, name)
        if (!outFile.exists()) {
            context.assets.open(name).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return outFile
    }

    /**
     * Run person detection on [bitmap].
     *
     * @param bitmap Any size/colour bitmap. It is letterboxed to 320×320.
     * @return List of person detections in normalized [0, 1] coordinates.
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        val start = System.currentTimeMillis()

        val lb = letterbox(bitmap, INPUT_SIZE)
        val inputTensor = prepareInputTensor(lb.bitmap)
        lb.bitmap.recycle()

        val results = session.run(mapOf(inputName to inputTensor))
        val output = results[0].value as Array<Array<FloatArray>> // [1][84][2100]
        results.close()
        inputTensor.close()

        val rawDetections = decodeDetections(output[0], lb)
        val filtered = nms(rawDetections)

        Log.d(TAG, "Detection took ${System.currentTimeMillis() - start}ms, " +
                "raw=${rawDetections.size}, after NMS=${filtered.size}")
        return filtered
    }

    /**
     * Convenience overload that rescales the returned boxes from the model's
     * 320×320 input space back to the original [bitmap] pixel size.
     */
    fun detect(bitmap: Bitmap, sourceWidth: Int, sourceHeight: Int): List<Detection> {
        return detect(bitmap).map { d ->
            d.copy(rect = RectF(
                d.rect.left * sourceWidth,
                d.rect.top * sourceHeight,
                d.rect.right * sourceWidth,
                d.rect.bottom * sourceHeight
            ))
        }
    }

    /**
     * Draw the given detections on a mutable copy of [bitmap].
     */
    fun drawDetections(bitmap: Bitmap, detections: List<Detection>): Bitmap {
        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val strokeWidth = max(2f, mutable.width / 320f)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            textSize = max(16f, mutable.width / 20f)
            style = Paint.Style.FILL
        }
        val textOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = textPaint.textSize
            style = Paint.Style.STROKE
            this.strokeWidth = textPaint.textSize / 8f
        }

        val width = mutable.width.toFloat()
        val height = mutable.height.toFloat()
        for (d in detections) {
            val left = d.rect.left * width
            val top = d.rect.top * height
            val right = d.rect.right * width
            val bottom = d.rect.bottom * height
            canvas.drawRect(left, top, right, bottom, boxPaint)
            val label = "${d.label} ${(d.confidence * 100).toInt()}%"
            canvas.drawText(label, left + 4f, max(top + textPaint.textSize, textPaint.textSize), textOutlinePaint)
            canvas.drawText(label, left + 4f, max(top + textPaint.textSize, textPaint.textSize), textPaint)
        }
        return mutable
    }

    fun close() {
        try {
            session.close()
            env.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ONNX session", e)
        }
    }

    private fun letterbox(src: Bitmap, targetSize: Int): LetterboxResult {
        val scale = min(targetSize.toFloat() / src.width, targetSize.toFloat() / src.height)
        val newW = (src.width * scale).roundToInt()
        val newH = (src.height * scale).roundToInt()
        val padX = (targetSize - newW) / 2f
        val padY = (targetSize - newH) / 2f

        val dst = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dst)
        canvas.drawColor(Color.rgb(PAD_COLOR, PAD_COLOR, PAD_COLOR))
        val scaled = Bitmap.createScaledBitmap(src, newW, newH, true)
        canvas.drawBitmap(scaled, padX, padY, null)
        scaled.recycle()
        return LetterboxResult(dst, padX, padY, scale, src.width, src.height)
    }

    private fun prepareInputTensor(bitmap: Bitmap): OnnxTensor {
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val buffer = FloatBuffer.allocate(1 * 3 * INPUT_SIZE * INPUT_SIZE)
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val px = pixels[y * INPUT_SIZE + x]
                buffer.put((Color.red(px) and 0xFF) / 255f)
            }
        }
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val px = pixels[y * INPUT_SIZE + x]
                buffer.put((Color.green(px) and 0xFF) / 255f)
            }
        }
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val px = pixels[y * INPUT_SIZE + x]
                buffer.put((Color.blue(px) and 0xFF) / 255f)
            }
        }
        buffer.rewind()
        return OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()))
    }

    private fun decodeDetections(
        output: Array<FloatArray>,
        lb: LetterboxResult
    ): List<Detection> {
        val numAnchors = output[0].size
        val detections = mutableListOf<Detection>()

        for (i in 0 until numAnchors) {
            val cx = output[0][i]
            val cy = output[1][i]
            val w = output[2][i]
            val h = output[3][i]

            var bestScore = 0f
            var bestClass = -1
            for (c in 0 until NUM_CLASSES) {
                val rawScore = output[4 + c][i]
                val score = if (APPLY_SIGMOID_TO_SCORES) sigmoid(rawScore) else rawScore
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }

            if (bestClass != PERSON_CLASS_ID || bestScore < CONFIDENCE_THRESHOLD) continue

            // Map boxes from letterboxed 320×320 back to normalized [0, 1] of the
            // original bitmap (before letterboxing).
            val x1 = (cx - w / 2f - lb.padLeft) / lb.scale
            val y1 = (cy - h / 2f - lb.padTop) / lb.scale
            val x2 = (cx + w / 2f - lb.padLeft) / lb.scale
            val y2 = (cy + h / 2f - lb.padTop) / lb.scale

            val normLeft = (x1 / lb.srcWidth).coerceIn(0f, 1f)
            val normTop = (y1 / lb.srcHeight).coerceIn(0f, 1f)
            val normRight = (x2 / lb.srcWidth).coerceIn(0f, 1f)
            val normBottom = (y2 / lb.srcHeight).coerceIn(0f, 1f)

            detections.add(Detection("person", bestScore, RectF(normLeft, normTop, normRight, normBottom)))
        }
        return detections
    }

    private fun nms(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        val sorted = detections.sortedByDescending { it.confidence }
        val kept = mutableListOf<Detection>()
        val suppressed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (suppressed[i]) continue
            kept.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                if (iou(sorted[i].rect, sorted[j].rect) > IOU_THRESHOLD) {
                    suppressed[j] = true
                }
            }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - inter
        return if (union > 0f) inter / union else 0f
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))
}
