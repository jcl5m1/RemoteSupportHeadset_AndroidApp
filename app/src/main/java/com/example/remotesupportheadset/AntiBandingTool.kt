package com.example.remotesupportheadset

import android.app.Activity
import android.graphics.Bitmap
import android.util.Log
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import java.util.concurrent.CountDownLatch

/**
 * Stand-alone analysis tool that sweeps the ESP32-P4 CSI exposure time and
 * finds the value that minimizes horizontal rolling-shutter banding on a
 * uniform surface (e.g. a white wall).
 *
 * This is intentionally **not** wired into the normal DualCameraActivity UI.
 * It is kept as a callable analysis utility for experiments; call [start]
 * from a debug/test path when needed.
 */
class AntiBandingTool(
    private val activity: Activity,
    private val textureCamera: AspectRatioTextureView,
    private val cdcCommandHelper: CdcCommandHelper
) {

    data class Result(
        val flickerHz: Int,
        val esp32Us: Float,
        val androidUs: Int,
        val androidMetric: Float,
        val androidMean: Float,
        val diffUs: Int
    )

    var onLog: ((String) -> Unit)? = null
    var onProgress: ((String) -> Unit)? = null
    var onResult: ((Result) -> Unit)? = null

    @Volatile
    private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean
        get() = running

    fun start() {
        if (running) return
        running = true
        log("Anti-banding servo starting...")
        thread = Thread({ runServo() }, "AntiBandingTool").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(1000)
        thread = null
        cdcCommandHelper.close()
        log("Anti-banding servo stopped.")
    }

    private fun runServo() {
        var espComputedUs = 0f
        var flickerHz = 0

        try {
            val cdcOk = cdcCommandHelper.open()
            if (!cdcOk) {
                log("CDC open failed; servo will measure only (set exposure manually).")
            } else {
                cdcCommandHelper.disableAutoExposure()
                Thread.sleep(100)
            }

            // Step 1: capture the ESP32's own anti-banding exposure when AE is on.
            if (cdcOk) {
                cdcCommandHelper.enableAutoExposure()
                log("Waiting for ESP32 AE to converge...")
                Thread.sleep(1500)
                val resp = cdcCommandHelper.queryExposureUs()
                val us = parseExpUs(resp)
                val flicker = parseFlickerHz(resp)
                if (us != null) {
                    espComputedUs = us
                    log("ESP32 self-computed exposure: ${us.toInt()} us")
                } else {
                    log("ESP32 exposure query failed: $resp")
                }
                if (flicker != null) {
                    flickerHz = flicker
                    log("ESP32 flicker detection: ${flicker} Hz")
                }
                cdcCommandHelper.disableAutoExposure()
                Thread.sleep(200)
            }

            // Step 2: coarse sweep across both 50 Hz and 60 Hz flicker-null ranges.
            val coarseStart = 7000
            val coarseEnd = 26000
            val coarseStep = 800
            val coarseResults = mutableListOf<Pair<Int, Float>>()

            log("Coarse sweep $coarseStart..$coarseEnd us step $coarseStep")
            for (us in coarseStart..coarseEnd step coarseStep) {
                if (!running) break
                val metric = measureBandingAt(us, cdcOk) ?: continue
                coarseResults.add(us to metric)
                updateProgress("coarse us=$us metric=%.4f".format(metric))
            }

            if (coarseResults.isEmpty()) {
                log("No valid measurements; aborting.")
                return
            }

            val coarseBest = coarseResults.minByOrNull { it.second }
            if (coarseBest == null) {
                log("Could not determine coarse best; aborting.")
                return
            }
            log("Coarse best: ${coarseBest.first} us metric=${coarseBest.second}")

            // Step 3: fine sweep around the coarse best.
            val fineRadius = 1000
            val fineStep = 100
            val fineStart = (coarseBest.first - fineRadius).coerceAtLeast(2000)
            val fineEnd = (coarseBest.first + fineRadius).coerceAtMost(40000)
            val fineResults = mutableListOf<Pair<Int, Float>>()

            log("Fine sweep $fineStart..$fineEnd us step $fineStep")
            for (us in fineStart..fineEnd step fineStep) {
                if (!running) break
                val metric = measureBandingAt(us, cdcOk) ?: continue
                fineResults.add(us to metric)
                updateProgress("fine us=$us metric=%.4f".format(metric))
            }

            val finalBest = (fineResults + coarseBest).minByOrNull { it.second }
            if (finalBest != null) {
                if (cdcOk) {
                    measureBandingAt(finalBest.first, true)
                }
                val meanAtBest = measureMeanIntensity(finalBest.first, cdcOk)
                val diff = finalBest.first - espComputedUs
                val summary = buildString {
                    appendLine("Anti-banding done.")
                    appendLine("ESP32 flicker:  ${flickerHz} Hz")
                    appendLine("ESP32 computed: ${espComputedUs.toInt()} us")
                    appendLine("Android servo:  ${finalBest.first} us")
                    appendLine("Min metric:     %.4f".format(finalBest.second))
                    appendLine("Mean intensity: %.1f".format(meanAtBest))
                    appendLine("Difference:     ${diff.toInt()} us")
                }
                log(summary)
                Log.i(
                    "AntiBandResult",
                    "FLICKER_HZ=${flickerHz} " +
                            "ESP32_US=${espComputedUs.toInt()} " +
                            "ANDROID_US=${finalBest.first} " +
                            "ANDROID_METRIC=%.6f ".format(finalBest.second) +
                            "ANDROID_MEAN=%.1f ".format(meanAtBest) +
                            "DIFF_US=${diff.toInt()}"
                )
                onResult?.invoke(
                    Result(
                        flickerHz = flickerHz,
                        esp32Us = espComputedUs,
                        androidUs = finalBest.first,
                        androidMetric = finalBest.second,
                        androidMean = meanAtBest,
                        diffUs = diff.toInt()
                    )
                )
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Anti-banding servo interrupted", e)
        } catch (e: Exception) {
            Log.e(TAG, "Anti-banding servo failed", e)
            log("Anti-banding failed: ${e.message}")
        } finally {
            running = false
            cdcCommandHelper.close()
            log("Anti-banding servo finished.")
        }
    }

    private fun measureBandingAt(us: Int, canCommand: Boolean): Float? {
        if (canCommand) {
            cdcCommandHelper.setExposureUs(us)
            Thread.sleep(180)
        }

        var sumMetric = 0f
        var samples = 0
        repeat(3) {
            if (!running) return null
            val bmp = captureBitmapForAnalysis() ?: return null
            try {
                val result = BandingAnalyzer.analyze(bmp)
                // Reject heavily-saturated frames: when the wall is clipped the
                // sine-wave variation is suppressed and the metric falsely reads
                // zero even though the exposure is not a true flicker null.
                if (result.meanIntensity > 10f &&
                    result.meanIntensity < 235f &&
                    result.maxIntensity < 250f
                ) {
                    sumMetric += result.metric
                    samples++
                }
            } finally {
                bmp.recycle()
            }
            Thread.sleep(60)
        }

        val metric = if (samples > 0) sumMetric / samples else null
        if (metric != null) {
            Log.v("AntiBandSweep", "us=$us metric=%.6f samples=$samples".format(metric))
        }
        return metric
    }

    private fun measureMeanIntensity(us: Int, canCommand: Boolean): Float {
        if (canCommand) {
            cdcCommandHelper.setExposureUs(us)
            Thread.sleep(180)
        }
        val bmp = captureBitmapForAnalysis() ?: return 0f
        return try {
            BandingAnalyzer.analyze(bmp).meanIntensity
        } finally {
            bmp.recycle()
        }
    }

    private fun captureBitmapForAnalysis(): Bitmap? {
        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null
        activity.runOnUiThread {
            try {
                bmp = textureCamera.bitmap
            } catch (e: Exception) {
                Log.w(TAG, "Failed to grab preview bitmap", e)
            }
            latch.countDown()
        }
        latch.await()
        return bmp
    }

    private fun parseExpUs(response: String?): Float? {
        if (response == null) return null
        val match = Regex("exp_us=([0-9.]+)").find(response) ?: return null
        return match.groupValues[1].toFloatOrNull()
    }

    private fun parseFlickerHz(response: String?): Int? {
        if (response == null) return null
        val match = Regex("flicker=([0-9]+)Hz").find(response) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun log(message: String) {
        Log.i(TAG, "Anti-banding: $message")
        activity.runOnUiThread { onLog?.invoke(message) }
    }

    private fun updateProgress(status: String) {
        activity.runOnUiThread { onProgress?.invoke(status) }
    }

    companion object {
        private const val TAG = "AntiBandingTool"
    }
}
