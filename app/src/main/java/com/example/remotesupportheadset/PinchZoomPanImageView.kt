package com.example.remotesupportheadset

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * An ImageView that supports pinch-to-zoom, two-finger pan, drag pan, and
 * double-tap to reset. The image is initially fit to the view center.
 */
class PinchZoomPanImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val baseMatrix = Matrix()
    private val suppMatrix = Matrix()
    private val displayMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private var drawableIntrinsicWidth = 0
    private var drawableIntrinsicHeight = 0

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        drawableIntrinsicWidth = bm?.width ?: 0
        drawableIntrinsicHeight = bm?.height ?: 0
        reset()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    /**
     * Reset the image to fit-center inside the view.
     */
    fun reset() {
        if (drawableIntrinsicWidth == 0 || drawableIntrinsicHeight == 0 || width == 0 || height == 0) {
            imageMatrix = Matrix()
            return
        }
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val drawableWidth = drawableIntrinsicWidth.toFloat()
        val drawableHeight = drawableIntrinsicHeight.toFloat()

        val scale = (viewWidth / drawableWidth).coerceAtMost(viewHeight / drawableHeight)
        val dx = (viewWidth - drawableWidth * scale) / 2f
        val dy = (viewHeight - drawableHeight * scale) / 2f

        baseMatrix.setScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)
        suppMatrix.reset()
        updateMatrix()
    }

    private fun updateMatrix() {
        displayMatrix.set(baseMatrix)
        displayMatrix.postConcat(suppMatrix)
        imageMatrix = displayMatrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (!isDragging && (kotlin.math.abs(dx) > 5 || kotlin.math.abs(dy) > 5)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        suppMatrix.postTranslate(dx, dy)
                        updateMatrix()
                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun getScale(): Float {
        suppMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scale = detector.scaleFactor
            suppMatrix.postScale(scale, scale, detector.focusX, detector.focusY)
            updateMatrix()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            reset()
            return true
        }
    }
}
