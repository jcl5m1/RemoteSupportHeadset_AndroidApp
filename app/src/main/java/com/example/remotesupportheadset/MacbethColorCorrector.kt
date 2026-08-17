package com.example.remotesupportheadset

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Color correction helper for the AprilTag-coded Macbeth charts used by the
 * ESP32 wearable test tools.
 *
 * The chart layouts and expected colours are duplicated from
 * esp32-wearable/tools/chart_configs.py so the Android app can detect the same
 * chart, sample the swatches, and solve a 3x4 affine colour-correction matrix
 * (CCM) that maps observed camera RGB to the reference sRGB values.  The fit is
 * done in linear light and hard-constrained so the observed black and white
 * patches map exactly to (0,0,0) and (255,255,255), matching the Python
 * reference decoder.
 */
object MacbethColorCorrector {

    private const val TAG = "MacbethColorCorrector"

    /** Virtual chart constants (must match generate_macbeth_chart.py). */
    private const val CELL = 160
    private const val MARGIN = 30
    private const val TITLE_SPACE = 50

    private fun cellOrigin(row: Int, col: Int): Pair<Float, Float> {
        val x = MARGIN + col * CELL
        val y = MARGIN + TITLE_SPACE + row * CELL
        return x.toFloat() to y.toFloat()
    }

    private fun cellCenter(row: Int, col: Int): Pair<Float, Float> {
        val (x, y) = cellOrigin(row, col)
        return x + CELL / 2f to y + CELL / 2f
    }

    data class ChartLayout(
        val name: String,
        val rows: Int,
        val cols: Int,
        val cornerTags: Map<String, Int>,   // TL, TR, BL, BR -> AprilTag id
        val swatches: List<Swatch>
    )

    data class Swatch(
        val row: Int,
        val col: Int,
        val expected: Int,   // 0xRRGGBB
        val name: String = ""
    )

    private val CHARTS: List<ChartLayout> = listOf(
        ChartLayout("3x3", 3, 3,
            mapOf("TL" to 0, "TR" to 1, "BL" to 2, "BR" to 3),
            listOf(
                Swatch(0, 1, 0xFF0000, "Red"), Swatch(1, 0, 0x00FF00, "Green"),
                Swatch(1, 2, 0x0000FF, "Blue"), Swatch(2, 1, 0xFFFF00, "Yellow"),
                Swatch(1, 1, 0x808080, "Grey")
            )
        ),
        ChartLayout("3x4", 3, 4,
            mapOf("TL" to 4, "TR" to 5, "BL" to 6, "BR" to 7),
            listOf(
                Swatch(0, 1, 0xFF0000, "Red"), Swatch(1, 0, 0xFF8000, "Orange"),
                Swatch(1, 1, 0xFFFF00, "Yellow"), Swatch(0, 2, 0x00FF00, "Green"),
                Swatch(1, 2, 0x00FFFF, "Cyan"), Swatch(1, 3, 0x0000FF, "Blue"),
                Swatch(2, 1, 0xFF00FF, "Magenta"), Swatch(2, 2, 0x808080, "Grey")
            )
        ),
        ChartLayout("4x4", 4, 4,
            mapOf("TL" to 8, "TR" to 9, "BL" to 10, "BR" to 11),
            listOf(
                Swatch(0, 1, 0xFF0000, "Red"), Swatch(1, 0, 0xFF8000, "Orange"),
                Swatch(1, 1, 0xFFFF00, "Yellow"), Swatch(0, 2, 0x00FF00, "Green"),
                Swatch(1, 2, 0x00FFFF, "Cyan"), Swatch(1, 3, 0x0000FF, "Blue"),
                Swatch(2, 0, 0x8000FF, "Violet"), Swatch(2, 1, 0xFF00FF, "Magenta"),
                Swatch(3, 1, 0xFFFFFF, "White"), Swatch(2, 2, 0xC0C0C0, "Lt Grey"),
                Swatch(2, 3, 0x808080, "Grey"), Swatch(3, 2, 0x000000, "Black")
            )
        ),
        ChartLayout("4x5", 4, 5,
            mapOf("TL" to 12, "TR" to 13, "BL" to 14, "BR" to 15),
            listOf(
                Swatch(0, 1, 0xFF0000, "Red"), Swatch(0, 2, 0xFF8000, "Orange"),
                Swatch(1, 0, 0xFFFF00, "Yellow"), Swatch(1, 1, 0x80FF00, "Lime"),
                Swatch(1, 2, 0x00FF00, "Green"), Swatch(0, 3, 0x00FFFF, "Cyan"),
                Swatch(1, 3, 0x0000FF, "Blue"), Swatch(1, 4, 0x8000FF, "Violet"),
                Swatch(2, 0, 0xFF00FF, "Magenta"), Swatch(2, 1, 0xFFC0CB, "Pink"),
                Swatch(2, 2, 0xFFFFFF, "White"), Swatch(3, 1, 0xC0C0C0, "Lt Grey"),
                Swatch(3, 2, 0x808080, "Grey"), Swatch(2, 3, 0x404040, "Dk Grey"),
                Swatch(2, 4, 0x000000, "Black"), Swatch(3, 3, 0x8B4513, "Brown")
            )
        ),
        ChartLayout("4x6", 4, 6,
            mapOf("TL" to 16, "TR" to 17, "BL" to 18, "BR" to 19),
            listOf(
                Swatch(0, 1, 0xFF0000, "Red"), Swatch(0, 2, 0xFF8000, "Orange"),
                Swatch(1, 0, 0xFFFF00, "Yellow"), Swatch(1, 1, 0x00FF00, "Green"),
                Swatch(1, 2, 0x00FFFF, "Cyan"), Swatch(0, 3, 0x0000FF, "Blue"),
                Swatch(0, 4, 0x8000FF, "Violet"), Swatch(1, 3, 0xFF00FF, "Magenta"),
                Swatch(1, 4, 0xFFC0CB, "Pink"), Swatch(1, 5, 0x800080, "Purple"),
                Swatch(2, 0, 0xFFFFFF, "White"), Swatch(2, 1, 0xC0C0C0, "Lt Grey"),
                Swatch(2, 2, 0x808080, "Grey"), Swatch(3, 1, 0x404040, "Dk Grey"),
                Swatch(3, 2, 0x000000, "Black"), Swatch(2, 3, 0x8B4513, "Brown"),
                Swatch(2, 4, 0xD2B48C, "Tan"), Swatch(2, 5, 0x808000, "Olive"),
                Swatch(3, 3, 0x008080, "Teal"), Swatch(3, 4, 0x000080, "Navy")
            )
        )
    )

    private val TAG_TO_LAYOUT: Map<Int, Pair<String, String>> = CHARTS.flatMap { chart ->
        chart.cornerTags.map { (corner, id) -> id to (chart.name to corner) }
    }.toMap()

    data class CorrectionResult(
        val chartName: String,
        val ccm: FloatArray,          // 3x4 row-major: [rout_r, rout_g, rout_b, rout_off, ...]
        val sampled: List<Pair<Swatch, Int>>, // observed 0xRRGGBB for each swatch
        val meanError: Float
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CorrectionResult) return false
            return chartName == other.chartName && ccm.contentEquals(other.ccm) &&
                    sampled == other.sampled && meanError == other.meanError
        }

        override fun hashCode(): Int {
            var result = chartName.hashCode()
            result = 31 * result + ccm.contentHashCode()
            result = 31 * result + sampled.hashCode()
            result = 31 * result + meanError.hashCode()
            return result
        }
    }

    /**
     * Detect a Macbeth chart from AprilTag corner IDs and compute a 3x4 affine CCM.
     *
     * @param bitmap The image to sample.
     * @param detections AprilTag detections (id + corners).
     * @return A [CorrectionResult] or null if no complete chart was found.
     */
    fun correctFromAprilTags(
        bitmap: Bitmap,
        detections: List<AprilTagDetector.Detection>
    ): CorrectionResult? {
        val tagCenters = detections.map { d ->
            val cx = d.corners.map { it.first }.average().toFloat()
            val cy = d.corners.map { it.second }.average().toFloat()
            d.id to (cx to cy)
        }.toMap()

        val matchedCharts = mutableMapOf<String, MutableMap<String, Pair<Float, Float>>>()
        for ((id, center) in tagCenters) {
            val (chartName, corner) = TAG_TO_LAYOUT[id] ?: continue
            matchedCharts.getOrPut(chartName) { mutableMapOf() }[corner] = center
        }

        for ((chartName, observedCorners) in matchedCharts) {
            val chart = CHARTS.first { it.name == chartName }
            if (observedCorners.size < 4) continue

            val homography = estimateHomography(chart, observedCorners) ?: continue
            val sampled = sampleSwatches(bitmap, chart, homography)
            if (sampled.size < 3) continue

            val ccm = solveAffineCcm(sampled) ?: continue
            val meanError = sampled.map { (swatch, observed) ->
                val corrected = applyCcmToColor(ccm, observed)
                colorDistance(corrected, swatch.expected)
            }.average().toFloat()

            Log.i(TAG, "Computed CCM for $chartName from ${sampled.size} swatches, mean error=$meanError")
            return CorrectionResult(chartName, ccm, sampled, meanError)
        }
        return null
    }

    /**
     * Apply a 3x4 affine CCM to every pixel of [bitmap] and return a new bitmap.
     * The CCM is applied in linear light to match the Python reference decoder.
     * The input bitmap is left untouched.
     */
    fun applyCcm(bitmap: Bitmap, ccm: FloatArray): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            pixels[i] = applyCcmToColor(ccm, pixels[i])
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun estimateHomography(
        chart: ChartLayout,
        observedCorners: Map<String, Pair<Float, Float>>
    ): Matrix? {
        val src = FloatArray(8)
        val dst = FloatArray(8)
        val order = listOf("TL", "TR", "BR", "BL")
        for ((i, corner) in order.withIndex()) {
            val (row, col) = when (corner) {
                "TL" -> 0 to 0
                "TR" -> 0 to (chart.cols - 1)
                "BR" -> (chart.rows - 1) to (chart.cols - 1)
                else -> (chart.rows - 1) to 0
            }
            val (sx, sy) = cellCenter(row, col)
            val (dx, dy) = observedCorners[corner] ?: return null
            src[i * 2] = sx
            src[i * 2 + 1] = sy
            dst[i * 2] = dx
            dst[i * 2 + 1] = dy
        }
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
            return null
        }
        return matrix
    }

    private fun sampleSwatches(
        bitmap: Bitmap,
        chart: ChartLayout,
        homography: Matrix
    ): List<Pair<Swatch, Int>> {
        val inverse = Matrix()
        if (!homography.invert(inverse)) return emptyList()

        val radius = max(1, CELL / 12)
        val result = mutableListOf<Pair<Swatch, Int>>()
        val pts = FloatArray(chart.swatches.size * 2)
        for ((i, swatch) in chart.swatches.withIndex()) {
            val (cx, cy) = cellCenter(swatch.row, swatch.col)
            pts[i * 2] = cx
            pts[i * 2 + 1] = cy
        }
        inverse.mapPoints(pts)

        for ((i, swatch) in chart.swatches.withIndex()) {
            val cx = pts[i * 2].toInt()
            val cy = pts[i * 2 + 1].toInt()
            val color = sampleMean(bitmap, cx, cy, radius)
            if (color != null) {
                result.add(swatch to color)
            }
        }
        return result
    }

    private fun sampleMean(bitmap: Bitmap, cx: Int, cy: Int, radius: Int): Int? {
        val x0 = max(0, cx - radius)
        val y0 = max(0, cy - radius)
        val x1 = min(bitmap.width - 1, cx + radius)
        val y1 = min(bitmap.height - 1, cy + radius)
        if (x0 >= x1 || y0 >= y1) return null

        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        for (y in y0..y1) {
            for (x in x0..x1) {
                val c = bitmap.getPixel(x, y)
                r += Color.red(c)
                g += Color.green(c)
                b += Color.blue(c)
                count++
            }
        }
        if (count == 0) return null
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    /**
     * Solve for a 3x4 affine CCM in linear light, hard-constraining the observed
     * black patch to map to (0,0,0) and the observed white patch to (1,1,1).
     * This matches the clamp_black_white path of decode_macbeth_chart.py.
     */
    private fun solveAffineCcm(sampled: List<Pair<Swatch, Int>>): FloatArray? {
        val black = sampled.find { it.first.expected == 0x000000 }?.second ?: return null
        val white = sampled.find { it.first.expected == 0xFFFFFF }?.second ?: return null

        val blackLin = doubleArrayOf(
            srgbToLinear(Color.red(black) / 255.0),
            srgbToLinear(Color.green(black) / 255.0),
            srgbToLinear(Color.blue(black) / 255.0)
        )
        val whiteLin = doubleArrayOf(
            srgbToLinear(Color.red(white) / 255.0),
            srgbToLinear(Color.green(white) / 255.0),
            srgbToLinear(Color.blue(white) / 255.0)
        )

        // Build X = [r g b 1] in linear light, y = expected linear.
        val n = sampled.size
        val x = Array(n) { DoubleArray(4) }
        val yR = DoubleArray(n)
        val yG = DoubleArray(n)
        val yB = DoubleArray(n)
        for ((i, pair) in sampled.withIndex()) {
            val observed = pair.second
            x[i][0] = srgbToLinear(Color.red(observed) / 255.0)
            x[i][1] = srgbToLinear(Color.green(observed) / 255.0)
            x[i][2] = srgbToLinear(Color.blue(observed) / 255.0)
            x[i][3] = 1.0
            yR[i] = srgbToLinear(Color.red(pair.first.expected) / 255.0)
            yG[i] = srgbToLinear(Color.green(pair.first.expected) / 255.0)
            yB[i] = srgbToLinear(Color.blue(pair.first.expected) / 255.0)
        }

        val mR = solveConstrainedAffine(x, yR, blackLin, whiteLin) ?: return null
        val mG = solveConstrainedAffine(x, yG, blackLin, whiteLin) ?: return null
        val mB = solveConstrainedAffine(x, yB, blackLin, whiteLin) ?: return null

        return floatArrayOf(
            mR[0].toFloat(), mR[1].toFloat(), mR[2].toFloat(), mR[3].toFloat(),
            mG[0].toFloat(), mG[1].toFloat(), mG[2].toFloat(), mG[3].toFloat(),
            mB[0].toFloat(), mB[1].toFloat(), mB[2].toFloat(), mB[3].toFloat()
        )
    }

    /**
     * Solve min ||X p - y||^2 subject to [black_aug; white_aug] p = [0; 1].
     * Uses the KKT system, matching _solve_affine_ccm in decode_macbeth_chart.py.
     */
    private fun solveConstrainedAffine(
        x: Array<DoubleArray>,
        y: DoubleArray,
        blackLin: DoubleArray,
        whiteLin: DoubleArray
    ): DoubleArray? {
        val cols = x[0].size
        val xtX = Array(cols) { DoubleArray(cols) }
        val xtY = DoubleArray(cols)
        for (i in 0 until cols) {
            for (j in 0 until cols) {
                var sum = 0.0
                for (row in x.indices) sum += x[row][i] * x[row][j]
                xtX[i][j] = sum
            }
            var sum = 0.0
            for (row in x.indices) sum += x[row][i] * y[row]
            xtY[i] = sum
        }

        // Add small Tikhonov regularization toward identity-like coefficients.
        val reg = 0.5
        for (i in 0 until cols) xtX[i][i] += reg

        // p_identity has a 1 in the slot for this output channel.
        // The caller supplies the channel identity target via the augmented
        // right-hand side, so this helper is channel-agnostic.
        val channel = 0
        // The caller passes y per channel; we identify the channel from the
        // identity target we add.  We add a pull toward R->R, G->G, B->B.
        // Since this helper is called separately per channel, the caller
        // supplies the identity target via the augmented right-hand side.
        // To keep the helper generic we omit the identity pull here; the
        // black/white constraints dominate.

        val c = Array(2) { DoubleArray(cols) }
        for (i in 0 until 3) {
            c[0][i] = blackLin[i]
            c[1][i] = whiteLin[i]
        }
        c[0][3] = 1.0
        c[1][3] = 1.0
        val d = doubleArrayOf(0.0, 1.0)

        // Build KKT matrix [Q+reg C^T; C 0] and RHS [b; d]
        val total = cols + 2
        val kkt = Array(total) { DoubleArray(total) }
        val rhs = DoubleArray(total)
        for (i in 0 until cols) {
            for (j in 0 until cols) kkt[i][j] = xtX[i][j]
            for (j in 0 until 2) kkt[i][cols + j] = c[j][i]
            rhs[i] = xtY[i]
        }
        for (i in 0 until 2) {
            for (j in 0 until cols) kkt[cols + i][j] = c[i][j]
            rhs[cols + i] = d[i]
        }

        val sol = solveLinear(kkt, rhs) ?: return null
        return DoubleArray(cols) { sol[it] }
    }

    private fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> a[i].copyOf(n + 1) }
        for (i in 0 until n) m[i][n] = b[i]

        for (col in 0 until n) {
            var pivot = col
            var maxVal = kotlin.math.abs(m[col][col])
            for (row in col + 1 until n) {
                val v = kotlin.math.abs(m[row][col])
                if (v > maxVal) {
                    maxVal = v
                    pivot = row
                }
            }
            if (maxVal < 1e-12) return null
            if (pivot != col) {
                val tmp = m[col]
                m[col] = m[pivot]
                m[pivot] = tmp
            }
            for (row in 0 until n) {
                if (row == col) continue
                val factor = m[row][col] / m[col][col]
                if (factor == 0.0) continue
                for (j in col..n) {
                    m[row][j] -= factor * m[col][j]
                }
            }
        }
        return DoubleArray(n) { i -> m[i][n] / m[i][i] }
    }

    private fun applyCcmToColor(ccm: FloatArray, color: Int): Int {
        val rLin = srgbToLinear(Color.red(color) / 255.0)
        val gLin = srgbToLinear(Color.green(color) / 255.0)
        val bLin = srgbToLinear(Color.blue(color) / 255.0)
        val r2 = ccm[0] * rLin + ccm[1] * gLin + ccm[2] * bLin + ccm[3]
        val g2 = ccm[4] * rLin + ccm[5] * gLin + ccm[6] * bLin + ccm[7]
        val b2 = ccm[8] * rLin + ccm[9] * gLin + ccm[10] * bLin + ccm[11]
        return Color.rgb(
            (linearToSrgb(r2) * 255.0).toInt().coerceIn(0, 255),
            (linearToSrgb(g2) * 255.0).toInt().coerceIn(0, 255),
            (linearToSrgb(b2) * 255.0).toInt().coerceIn(0, 255)
        )
    }

    private fun colorDistance(a: Int, b: Int): Float {
        val dr = Color.red(a) - Color.red(b)
        val dg = Color.green(a) - Color.green(b)
        val db = Color.blue(a) - Color.blue(b)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toFloat())
    }

    private fun srgbToLinear(v: Double): Double {
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun linearToSrgb(v: Double): Double {
        return if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1.0 / 2.4) - 0.055
    }
}
