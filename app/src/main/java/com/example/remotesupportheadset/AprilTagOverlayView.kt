package com.example.remotesupportheadset

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View


/**
 * Transparent overlay that draws AprilTag detections on top of the live preview.
 *
 * The caller updates [detections] with corners in the view's coordinate space and
 * calls [invalidate] to redraw.
 */
class AprilTagOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Detection(
        val id: Int,
        val corners: List<Pair<Float, Float>>
    )

    data class YoloDetection(
        val label: String,
        val confidence: Float,
        val rect: RectF
    )

    var detections: List<Detection> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var yoloDetections: List<YoloDetection> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val textFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        textSize = 28f
        style = Paint.Style.FILL
    }

    private val textOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 28f
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val yoloBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val yoloBoxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 80, 80, 80)
        style = Paint.Style.FILL
    }

    private val yoloTextFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        textSize = 32f
        style = Paint.Style.FILL
    }

    private val yoloTextOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 32f
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawAprilTags(canvas)
        drawYoloDetections(canvas)
    }

    private fun drawAprilTags(canvas: Canvas) {
        for (d in detections) {
            if (d.corners.size < 4) continue
            val path = Path().apply {
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
            canvas.drawText(label, cx, cy, textFillPaint)
        }
    }

    private fun drawYoloDetections(canvas: Canvas) {
        for (d in yoloDetections) {
            // [d.rect] is already in this view's coordinate space (same convention
            // as the AprilTag corners), so draw it directly without re-scaling.
            // Fill the box with a translucent gray to obscure the detected person,
            // then draw the yellow outline and label on top.
            canvas.drawRect(d.rect, yoloBoxFillPaint)
            canvas.drawRect(d.rect, yoloBoxPaint)
            val label = "${d.label} ${(d.confidence * 100).toInt()}%"
            val baseline = (d.rect.top + yoloTextFillPaint.textSize).coerceAtLeast(yoloTextFillPaint.textSize)
            canvas.drawText(label, d.rect.left + 6f, baseline, yoloTextOutlinePaint)
            canvas.drawText(label, d.rect.left + 6f, baseline, yoloTextFillPaint)
        }
    }
}
