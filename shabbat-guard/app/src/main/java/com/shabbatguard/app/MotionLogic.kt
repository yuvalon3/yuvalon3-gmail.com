package com.shabbatguard.app

import kotlin.math.acos
import kotlin.math.sqrt

enum class RingMode { RING, VIBRATE }

data class MotionConfig(
    /** Seconds of (almost) continuous movement that count as "walking". */
    val motionSeconds: Int = 4,
    /** Extra delay after walking was detected, before switching to vibrate. */
    val switchDelaySeconds: Int = 3,
    /** Minutes of complete stillness before going back to ring. */
    val stillMinutes: Int = 5,
    /** Linear acceleration (m/s²) that counts as a step / real movement. */
    val walkThreshold: Float = 1.2f,
    /** Below this linear acceleration (m/s²) a second counts as "lying still". */
    val stillThreshold: Float = 0.12f,
    /** A change in the phone's orientation larger than this (degrees) is movement. */
    val tiltDegrees: Float = 5f,
)

/**
 * Pure decision logic (no Android dependencies, unit-tested on the JVM).
 *
 * Feed accelerometer samples with [onAccel] and call [onTick] once a second.
 * [onTick] returns the new mode when the ringer should change, else null.
 */
class MotionLogic(var config: MotionConfig, startMode: RingMode, nowMs: Long) {

    var mode: RingMode = startMode
        private set

    private val gravity = FloatArray(3)
    private var hasGravity = false
    private var refGravity: FloatArray? = null

    private var bucketMax = 0f
    private var bucketAbove = 0
    private var bucketSamples = 0

    /** Max linear acceleration seen during the last full second. */
    var lastSecondLevel = 0f
        private set
    var lastSecondTilt = 0f
        private set

    /** Seconds of continuous movement so far. */
    var movingRun = 0
        private set
    private var gap = 0

    /** Last time the phone was not lying perfectly still. */
    var lastActivityMs = nowMs
        private set

    var pendingVibrateAtMs: Long? = null
        private set

    fun onAccel(x: Float, y: Float, z: Float) {
        if (!hasGravity) {
            gravity[0] = x; gravity[1] = y; gravity[2] = z
            hasGravity = true
            return
        }
        gravity[0] = ALPHA * gravity[0] + (1 - ALPHA) * x
        gravity[1] = ALPHA * gravity[1] + (1 - ALPHA) * y
        gravity[2] = ALPHA * gravity[2] + (1 - ALPHA) * z
        val lx = x - gravity[0]
        val ly = y - gravity[1]
        val lz = z - gravity[2]
        val mag = sqrt(lx * lx + ly * ly + lz * lz)
        bucketSamples++
        if (mag > bucketMax) bucketMax = mag
        if (mag >= config.walkThreshold) bucketAbove++
    }

    fun onTick(nowMs: Long, blockRing: Boolean = false): RingMode? {
        val moving = bucketAbove >= MIN_SAMPLES_ABOVE
        var still = bucketMax < config.stillThreshold

        // Slow orientation changes (phone shifting in a pocket) are movement too.
        val tilt = if (hasGravity) tiltSinceReference() else 0f
        if (tilt > config.tiltDegrees) still = false

        lastSecondLevel = bucketMax
        lastSecondTilt = tilt
        bucketMax = 0f; bucketAbove = 0; bucketSamples = 0

        if (moving) {
            movingRun++
            gap = 0
        } else {
            gap++
            if (gap > ALLOWED_GAP_SECONDS) movingRun = 0
        }

        if (!still || blockRing) {
            lastActivityMs = nowMs
            if (hasGravity) refGravity = gravity.copyOf()
        }

        when (mode) {
            RingMode.RING -> {
                if (pendingVibrateAtMs == null && movingRun >= config.motionSeconds) {
                    pendingVibrateAtMs = nowMs + config.switchDelaySeconds * 1000L
                }
                val pending = pendingVibrateAtMs
                if (pending != null && nowMs >= pending) {
                    mode = RingMode.VIBRATE
                    pendingVibrateAtMs = null
                    lastActivityMs = nowMs
                    return mode
                }
            }
            RingMode.VIBRATE -> {
                if (nowMs - lastActivityMs >= config.stillMinutes * 60_000L) {
                    mode = RingMode.RING
                    movingRun = 0
                    gap = 0
                    return mode
                }
            }
        }
        return null
    }

    fun stillForMs(nowMs: Long): Long = nowMs - lastActivityMs

    private fun tiltSinceReference(): Float {
        val ref = refGravity ?: run {
            refGravity = gravity.copyOf()
            return 0f
        }
        val dot = ref[0] * gravity[0] + ref[1] * gravity[1] + ref[2] * gravity[2]
        val n1 = sqrt(ref[0] * ref[0] + ref[1] * ref[1] + ref[2] * ref[2])
        val n2 = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
        if (n1 < 0.1f || n2 < 0.1f) return 0f
        val cos = (dot / (n1 * n2)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos.toDouble())).toFloat()
    }

    companion object {
        private const val ALPHA = 0.8f
        private const val MIN_SAMPLES_ABOVE = 2
        private const val ALLOWED_GAP_SECONDS = 1
    }
}
