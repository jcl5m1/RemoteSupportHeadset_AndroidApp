package com.example.remotesupportheadset

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * A plain View that displays a Bitmap with pinch-to-zoom, drag pan, and
 * double-tap to reset. It does not rely on ImageView's matrix handling, so
 * pan/zoom state is fully under our control and survives touch release.
 */
class PinchZoomPanImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val drawMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    private var bitmap: Bitmap? = null
    private var bitmapWidth = 0
    private var bitmapHeight = 0

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var isDragging = false

    // Pinch gesture focus tracking so panning works while zooming.
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    // Current transform relative to the base fit-center transform.
    private var currentScale = 1f
    private var currentTransX = 0f
    private var currentTransY = 0f

    init {
        setWillNotDraw(false)
    }

    fun setImageBitmap(bm: Bitmap?) {
        bitmap = bm
        bitmapWidth = bm?.width ?: 0
        bitmapHeight = bm?.height ?: 0
        reset()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    /**
     * Reset the image to fit-center inside the view.
     */
    fun reset() {
        currentScale = 1f
        currentTransX = 0f
        currentTransY = 0f
        updateMatrix()
        invalidate()
    }

    private fun baseScale(): Float {
        if (bitmapWidth == 0 || bitmapHeight == 0 || width == 0 || height == 0) return 1f
        return (width.toFloat() / bitmapWidth).coerceAtMost(height.toFloat() / bitmapHeight)
    }

    private fun updateMatrix() {
        drawMatrix.reset()
        val scale = baseScale() * currentScale
        drawMatrix.setScale(scale, scale)

        val scaledWidth = bitmapWidth * scale
        val scaledHeight = bitmapHeight * scale
        val fitDx = (width - scaledWidth) / 2f
        val fitDy = (height - scaledHeight) / 2f
        drawMatrix.postTranslate(fitDx + currentTransX, fitDy + currentTransY)
    }

    private fun currentTotalScale(): Float {
        return baseScale() * currentScale
    }

    private fun constrainTransform() {
        if (bitmapWidth == 0 || bitmapHeight == 0 || width == 0 || height == 0) return

        val minScale = 1f
        val maxScale = 8f
        currentScale = currentScale.coerceIn(minScale, maxScale)

        val totalScale = currentTotalScale()
        val scaledWidth = bitmapWidth * totalScale
        val scaledHeight = bitmapHeight * totalScale

        // Center the image if it is smaller than the view; otherwise keep it
        // within the view bounds.
        currentTransX = when {
            scaledWidth <= width.toFloat() -> 0f
            else -> currentTransX.coerceIn(width - scaledWidth, 0f)
        }
        currentTransY = when {
            scaledHeight <= height.toFloat() -> 0f
            else -> currentTransY.coerceIn(height - scaledHeight, 0f)
        }
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
                // Remember where the remaining finger is so a lift-and-pan does
                // not jump from the original ACTION_DOWN coordinates.
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
                        currentTransX += dx
                        currentTransY += dy
                        constrainTransform()
                        updateMatrix()
                        invalidate()
                        lastTouchX = x
                        lastTouchY = y
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        if (bmp.isRecycled) return
        canvas.drawBitmap(bmp, drawMatrix, paint)
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val focusX = detector.focusX
            val focusY = detector.focusY
            val scaleFactor = detector.scaleFactor

            val fitDx = (width - bitmapWidth * baseScale()) / 2f
            val fitDy = (height - bitmapHeight * baseScale()) / 2f

            // First translate by the focus-point movement so panning works
            // while the user is pinching.
            currentTransX += focusX - lastFocusX
            currentTransY += focusY - lastFocusY

            // Then scale around the current focus point.
            currentTransX = focusX + scaleFactor * (fitDx + currentTransX - focusX) - fitDx
            currentTransY = focusY + scaleFactor * (fitDy + currentTransY - focusY) - fitDy
            currentScale *= scaleFactor

            lastFocusX = focusX
            lastFocusY = focusY

            constrainTransform()
            updateMatrix()
            invalidate()
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
