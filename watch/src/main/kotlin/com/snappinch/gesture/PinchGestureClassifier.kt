package com.snappinch.gesture

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class PinchGestureClassifier {

    companion object {
        private const val TAG = "TwistDetector"
        private const val DEBUG_LOGS = false
        private const val STATUS_LOG_INTERVAL = 200

        private const val MIN_TWIST_SPEED = 5.5f
        private const val MAX_TWIST_SPEED = 35.0f

        private const val MIN_INTERVAL_MS = 100L
        private const val MAX_TOTAL_TIME_MS = 1200L
        private const val COOLDOWN_MS = 1000L
        private const val DEBOUNCE_MS = 50L

        private const val BASELINE_WINDOW = 20
        private const val SPIKE_RATIO = 2.0f

        private const val MIN_PRIMARY_AXIS_SPEED = 4.2f
        private const val MIN_AXIS_DOMINANCE_RATIO = 1.35f
        private const val MAX_OFF_AXIS_FRACTION = 0.8f
    }

    private data class TwistEvent(
        val timestamp: Long,
        val rotationSpeed: Float,
        val direction: Int
    )

    private val twistSequence = mutableListOf<TwistEvent>()
    private var lastTriggerTime: Long = 0L
    private var lastTwistTime: Long = 0L

    private val recentGyro = ArrayDeque<Float>(BASELINE_WINDOW)

    private var currentGyroX = 0f
    private var currentGyroY = 0f
    private var currentGyroZ = 0f
    private var currentGyroMag = 0f

    private var sampleCount = 0
    private var totalTwists = 0

    fun updateGyroscope(x: Float, y: Float, z: Float) {
        currentGyroX = x
        currentGyroY = y
        currentGyroZ = z
        currentGyroMag = sqrt(x * x + y * y + z * z)

        if (currentGyroMag < MIN_TWIST_SPEED) {
            recentGyro.addLast(currentGyroMag)
            if (recentGyro.size > BASELINE_WINDOW) {
                recentGyro.removeFirst()
            }
        }
    }

    fun processSample(timestamp: Long): Boolean {
        sampleCount++

        val gyroMag = currentGyroMag
        val baseline = calculateGyroBaseline()
        val axisQualified = isTwistAxisQualified()

        if (DEBUG_LOGS && sampleCount % STATUS_LOG_INTERVAL == 0) {
            Log.d(
                TAG,
                "Status: gyroMag=%.1f, base=%.1f, axisOk=%s, seq=%d, twists=%d".format(
                    gyroMag,
                    baseline,
                    axisQualified,
                    twistSequence.size,
                    totalTwists
                )
            )
        }

        if (timestamp - lastTriggerTime < COOLDOWN_MS) {
            return false
        }

        if (twistSequence.isNotEmpty()) {
            val elapsed = timestamp - twistSequence.first().timestamp
            if (elapsed > MAX_TOTAL_TIME_MS) {
                if (DEBUG_LOGS) {
                    Log.d(TAG, "Sequence expired (%dms)".format(elapsed))
                }
                twistSequence.clear()
            }
        }

        if (timestamp - lastTwistTime < DEBOUNCE_MS) {
            return false
        }

        val isSpike =
            gyroMag > MIN_TWIST_SPEED &&
                gyroMag < MAX_TWIST_SPEED &&
                gyroMag > baseline * SPIKE_RATIO
        if (!isSpike || !axisQualified) {
            return false
        }

        val direction = if (currentGyroX >= 0) 1 else -1
        return processTwist(TwistEvent(timestamp, gyroMag, direction), timestamp)
    }

    private fun processTwist(twist: TwistEvent, currentTime: Long): Boolean {
        totalTwists++
        lastTwistTime = currentTime

        if (DEBUG_LOGS) {
            Log.d(
                TAG,
                "TWIST #%d: speed=%.1f, dir=%d, seq=%d".format(
                    totalTwists,
                    twist.rotationSpeed,
                    twist.direction,
                    twistSequence.size
                )
            )
        }

        if (twistSequence.isEmpty()) {
            twistSequence.add(twist)
            if (DEBUG_LOGS) {
                Log.i(TAG, "TWIST 1/3 (dir=%d)".format(twist.direction))
            }
            return false
        }

        val lastTwist = twistSequence.last()
        val interval = twist.timestamp - lastTwist.timestamp

        if (interval < MIN_INTERVAL_MS) {
            if (DEBUG_LOGS) {
                Log.d(TAG, "Too fast (%dms) - same motion".format(interval))
            }
            if (twist.rotationSpeed > lastTwist.rotationSpeed) {
                twistSequence[twistSequence.lastIndex] = twist
            }
            return false
        }

        if (twist.direction == lastTwist.direction) {
            if (DEBUG_LOGS) {
                Log.d(TAG, "Same direction - restarting with this as first")
            }
            twistSequence.clear()
            twistSequence.add(twist)
            if (DEBUG_LOGS) {
                Log.i(TAG, "TWIST 1/3 (dir=%d) [restart]".format(twist.direction))
            }
            return false
        }

        twistSequence.add(twist)

        return when (twistSequence.size) {
            2 -> {
                if (DEBUG_LOGS) {
                    Log.i(TAG, "TWIST 2/3 (dir=%d)".format(twist.direction))
                }
                false
            }

            3 -> {
                val t1 = twistSequence[0]
                val t2 = twistSequence[1]
                val t3 = twistSequence[2]

                if (t1.direction == t3.direction && t2.direction != t1.direction) {
                    val totalTime = t3.timestamp - t1.timestamp
                    Log.i(
                        TAG,
                        "TRIPLE TWIST SUCCESS! pattern=%d->%d->%d, time=%dms".format(
                            t1.direction,
                            t2.direction,
                            t3.direction,
                            totalTime
                        )
                    )
                    lastTriggerTime = currentTime
                    twistSequence.clear()
                    true
                } else {
                    Log.w(TAG, "Pattern mismatch, resetting")
                    twistSequence.clear()
                    false
                }
            }

            else -> {
                twistSequence.clear()
                false
            }
        }
    }

    private fun calculateGyroBaseline(): Float {
        if (recentGyro.size < 3) return 2f
        val sorted = recentGyro.sorted()
        return sorted[sorted.size / 2]
    }

    private fun isTwistAxisQualified(): Boolean {
        val absX = abs(currentGyroX)
        val absY = abs(currentGyroY)
        val absZ = abs(currentGyroZ)
        if (absX < MIN_PRIMARY_AXIS_SPEED) {
            return false
        }

        val offAxisMax = max(absY, absZ)
        if (offAxisMax > absX * MAX_OFF_AXIS_FRACTION) {
            return false
        }

        val offAxisSum = absY + absZ + 0.001f
        val dominanceRatio = absX / offAxisSum
        return dominanceRatio >= MIN_AXIS_DOMINANCE_RATIO
    }

    fun reset() {
        recentGyro.clear()
        twistSequence.clear()
        currentGyroX = 0f
        currentGyroY = 0f
        currentGyroZ = 0f
        currentGyroMag = 0f
        sampleCount = 0
        totalTwists = 0
        lastTwistTime = 0L
    }
}
