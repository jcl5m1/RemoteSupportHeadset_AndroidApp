package com.example.remotesupportheadset

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * An ImageView that supports pinch-to-zoom, two-finger pan, drag pan, and
 * double-tap to reset. The image is initially fit to the view center and keeps
 * the pan/zoom state reached by the user until it is explicitly reset.
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
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
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
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger joined the gesture; update the active pointer so a
                // subsequent single-finger pan starts from the right coordinates.
                val index = event.actionIndex
                activePointerId = event.getPointerId(index)
                lastTouchX = event.getX(index)
                lastTouchY = event.getY(index)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex < 0) return true
                    val x = event.getX(pointerIndex)
                    val y = event.getY(pointerIndex)
                    val dx = x - lastTouchX
                    val dy = y - lastTouchY
                    if (!isDragging && (kotlin.math.abs(dx) > 5 || kotlin.math.abs(dy) > 5)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        suppMatrix.postTranslate(dx, dy)
                        constrainMatrix()
                        updateMatrix()
                        lastTouchX = x
                        lastTouchY = y
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // When a finger is lifted, switch to the remaining finger so the
                // next ACTION_MOVE does not jump from the old pointer position.
                val pointerIndex = event.actionIndex
                if (event.getPointerId(pointerIndex) == activePointerId) {
                    val newPointerIndex = if (pointerIndex == 0) 1 else 0
                    activePointerId = event.getPointerId(newPointerIndex)
                    lastTouchX = event.getX(newPointerIndex)
                    lastTouchY = event.getY(newPointerIndex)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun getScale(): Float {
        displayMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    /**
     * Clamp the current matrix so the image stays within the view bounds and the
     * scale never drops below the initial fit scale or exceeds 8x.
     */
    private fun constrainMatrix() {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0 || drawableIntrinsicWidth == 0 || drawableIntrinsicHeight == 0) return

        val baseScale = (viewWidth / drawableIntrinsicWidth).coerceAtMost(viewHeight / drawableIntrinsicHeight)
        val currentScale = getScale()
        val minScale = baseScale
        val maxScale = baseScale * 8f

        // Clamp scale around the view center.
        if (currentScale < minScale || currentScale > maxScale) {
            val targetScale = currentScale.coerceIn(minScale, maxScale)
            val factor = targetScale / currentScale
            suppMatrix.postScale(factor, factor, viewWidth / 2f, viewHeight / 2f)
        }

        displayMatrix.set(baseMatrix)
        displayMatrix.postConcat(suppMatrix)
        displayMatrix.getValues(matrixValues)

        val scaledWidth = drawableIntrinsicWidth * matrixValues[Matrix.MSCALE_X]
        val scaledHeight = drawableIntrinsicHeight * matrixValues[Matrix.MSCALE_Y]
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]

        val dx = if (scaledWidth <= viewWidth) {
            (viewWidth - scaledWidth) / 2f - transX
        } else {
            transX.coerceIn(viewWidth - scaledWidth, 0f) - transX
        }
        val dy = if (scaledHeight <= viewHeight) {
            (viewHeight - scaledHeight) / 2f - transY
        } else {
            transY.coerceIn(viewHeight - scaledHeight, 0f) - transY
        }

        if (dx != 0f || dy != 0f) {
            suppMatrix.postTranslate(dx, dy)
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scale = detector.scaleFactor
            suppMatrix.postScale(scale, scale, detector.focusX, detector.focusY)
            constrainMatrix()
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
