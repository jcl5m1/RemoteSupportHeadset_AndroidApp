package com.example.remotesupportheadset

import android.os.SystemClock
import android.util.Log
import kotlin.math.hypot

/**
 * Temporal stability filter for AprilTag detections.
 *
 * AprilTag 16h5 has a small tag space, so the detector frequently reports
 * false positives on texture, noise, and UI elements.  This tracker keeps a
 * short history of detections and only promotes tags that appear in several
 * consecutive frames at roughly the same position.
 */
class AprilTagTracker(
    private val minFrames: Int = 3,
    private val windowFrames: Int = 5,
    var maxPositionJumpPx: Float = 24f,
    private val maxAgeMs: Long = 1200L
) {

    data class Observation(
        val id: Int,
        val centerX: Float,
        val centerY: Float,
        val timestampMs: Long
    )

    private val history = mutableMapOf<Int, MutableList<Observation>>()

    /**
     * Update the tracker with the latest frame of detections and return only
     * the tags that are temporally stable.
     */
    fun update(detections: List<AprilTagDetector.Detection>): List<AprilTagDetector.Detection> {
        val now = SystemClock.elapsedRealtime()

        // Add new observations.
        val seenIds = mutableSetOf<Int>()
        for (d in detections) {
            val cx = d.corners.map { it.first }.average().toFloat()
            val cy = d.corners.map { it.second }.average().toFloat()
            val list = history.getOrPut(d.id) { mutableListOf() }
            list.add(Observation(d.id, cx, cy, now))
            seenIds.add(d.id)
        }

        // Prune old observations and IDs with no recent sightings.
        val iterator = history.iterator()
        while (iterator.hasNext()) {
            val (_, list) = iterator.next()
            list.removeAll { now - it.timestampMs > maxAgeMs }
            // Keep at most windowFrames recent observations per tag.
            while (list.size > windowFrames) {
                list.removeAt(0)
            }
            if (list.isEmpty()) {
                iterator.remove()
            }
        }

        // Promote detections that have enough recent, spatially consistent observations.
        val stable = mutableListOf<AprilTagDetector.Detection>()
        for (d in detections) {
            val observations = history[d.id] ?: continue
            if (observations.size < minFrames) continue
            if (isSpatiallyConsistent(observations)) {
                stable.add(d)
            }
        }

        if (stable.size != detections.size) {
            Log.d(TAG, "AprilTag stability filter: ${detections.size} raw -> ${stable.size} stable")
        }
        return stable
    }

    /**
     * Return true if the recent observations for a tag all lie within
     * [maxPositionJumpPx] of their centroid.  This rejects tags that pop up
     * at random locations for a single frame.
     */
    private fun isSpatiallyConsistent(observations: List<Observation>): Boolean {
        if (observations.isEmpty()) return false
        val cx = observations.map { it.centerX }.average().toFloat()
        val cy = observations.map { it.centerY }.average().toFloat()
        return observations.all {
            hypot(it.centerX - cx, it.centerY - cy) <= maxPositionJumpPx
        }
    }

    /** Reset all tracked state (e.g. after a camera switch or disconnection). */
    fun reset() {
        history.clear()
    }

    companion object {
        private const val TAG = "AprilTagTracker"
    }
}
