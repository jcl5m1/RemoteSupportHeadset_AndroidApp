package com.example.remotesupportheadset

import android.graphics.Bitmap
import kotlin.math.sqrt

/**
 * Analyzes a vertical slice of a preview frame for horizontal banding.
 *
 * The camera is assumed to be pointed at a uniform surface (e.g. a white wall).
 * Under flickering artificial light, rolling-shutter banding appears as
 * horizontal bright/dark stripes. A vertical slice through the image therefore
 * looks like a noisy sine wave. The banding metric is the normalized standard
 * deviation of that vertical profile: lower values mean a more uniform image.
 */
object BandingAnalyzer {

    data class Result(
        val metric: Float,       // normalized std-dev of the vertical profile (lower is better)
        val meanIntensity: Float, // average intensity in the slice
        val minIntensity: Float,
        val maxIntensity: Float,
        val profile: FloatArray  // one intensity sample per row
    )

    /**
     * Extract a thin vertical slice near the horizontal center of [bitmap],
     * average the slice horizontally, and compute a banding metric.
     *
     * @param sliceWidthPx total width of the slice in pixels (will be centered)
     */
    @JvmStatic
    fun analyze(
        bitmap: Bitmap,
        sliceWidthPx: Int = 32
    ): Result {
        val width = bitmap.width
        val height = bitmap.height
        val halfSlice = sliceWidthPx / 2
        val centerX = width / 2
        val left = (centerX - halfSlice).coerceIn(0, width - 1)
        val right = (centerX + halfSlice).coerceIn(0, width - 1)
        val actualWidth = right - left + 1

        val profile = FloatArray(height)
        var total = 0.0
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE

        val pixels = IntArray(actualWidth)
        for (y in 0 until height) {
            bitmap.getPixels(pixels, 0, actualWidth, left, y, actualWidth, 1)
            var rowSum = 0.0
            for (color in pixels) {
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                // Luminance in [0, 255]
                val yLuma = (0.299f * r + 0.587f * g + 0.114f * b)
                rowSum += yLuma
            }
            val avg = (rowSum / actualWidth).toFloat()
            profile[y] = avg
            total += avg
            if (avg < min) min = avg
            if (avg > max) max = avg
        }

        val mean = (total / height).toFloat()

        // Compute normalized standard deviation (coefficient of variation).
        var sumSq = 0.0
        for (v in profile) {
            val d = v - mean
            sumSq += d * d
        }
        val std = sqrt(sumSq / height).toFloat()
        val metric = if (mean > 1f) std / mean else std

        return Result(
            metric = metric,
            meanIntensity = mean,
            minIntensity = min,
            maxIntensity = max,
            profile = profile
        )
    }
}
