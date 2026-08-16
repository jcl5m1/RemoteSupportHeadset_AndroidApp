package com.example.remotesupportheadset

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log

/**
 * Lightweight AprilTag 16h5 detector backed by the AprilTag3 native library.
 *
 * The detector is single-shot and stateless: feed it a [Bitmap] and it returns
 * a list of detections plus an annotated copy of the input image.
 */
class AprilTagDetector {

    data class Detection(
        val id: Int,
        val corners: List<Pair<Float, Float>>
    ) {
        /** JNI helper constructor: corners ordered counter-clockwise. */
        constructor(
            id: Int,
            x0: Float, y0: Float,
            x1: Float, y1: Float,
            x2: Float, y2: Float,
            x3: Float, y3: Float
        ) : this(
            id,
            listOf(x0 to y0, x1 to y1, x2 to y2, x3 to y3)
        )
    }

    companion object {
        private const val TAG = "AprilTagDetector"

        init {
            System.loadLibrary("apriltag_jni")
        }
    }

    /**
     * Detect AprilTag 16h5 markers in [bitmap].
     * Returns the list of detections and, optionally, an annotated bitmap.
     */
    fun detect(bitmap: Bitmap, annotate: Boolean = true): Pair<List<Detection>, Bitmap?> {
        val detections = nativeDetect(bitmap).toList()
        val annotated = if (annotate && detections.isNotEmpty()) {
            drawDetections(bitmap, detections)
        } else null
        return detections to annotated
    }

    private external fun nativeDetect(bitmap: Bitmap): Array<Detection>

    /**
     * Draw tag outlines and IDs on a copy of [bitmap].
     */
    fun drawDetections(bitmap: Bitmap, detections: List<Detection>): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
            textSize = 32f
            style = Paint.Style.FILL
        }
        val textOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 32f
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        for (d in detections) {
            val path = android.graphics.Path().apply {
                moveTo(d.corners[0].first, d.corners[0].second)
                lineTo(d.corners[1].first, d.corners[1].second)
                lineTo(d.corners[2].first, d.corners[2].second)
                lineTo(d.corners[3].first, d.corners[3].second)
                close()
            }
            canvas.drawPath(path, linePaint)

            val cx = d.corners.map { it.first }.average().toFloat()
            val cy = d.corners.map { it.second }.average().toFloat()
            val label = "id=${d.id}"
            canvas.drawText(label, cx, cy, textOutlinePaint)
            canvas.drawText(label, cx, cy, textPaint)
        }
        return out
    }
}
