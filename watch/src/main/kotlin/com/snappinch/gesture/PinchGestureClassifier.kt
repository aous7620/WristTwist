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

        private const val MIN_TWIST_SPEED = 5.8f
        private const val MAX_TWIST_SPEED = 35.0f

        private const val MIN_INTERVAL_MS = 70L
        private const val MIN_TOTAL_TIME_MS = 140L
        private const val MAX_TOTAL_TIME_MS = 1120L
        private const val COOLDOWN_MS = 600L
        private const val DEBOUNCE_MS = 45L

        private const val BASELINE_WINDOW = 20
        private const val SPIKE_RATIO = 2.08f

        private const val MIN_PRIMARY_AXIS_SPEED = 5.0f
        private const val MIN_AXIS_DOMINANCE_RATIO = 1.45f
        private const val MAX_OFF_AXIS_FRACTION = 0.7f
    }

    private data class TwistEvent(
        val timestamp: Long,
        val rotationSpeed: Float,
        val direction: Int,
        val axis: Int
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
    private var sequenceAxis: Int? = null

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

    fun processSample(timestamp: Long): Int? {
        sampleCount++

        val gyroMag = currentGyroMag
        val baseline = calculateGyroBaseline()
        val axisDirection = getQualifiedAxisDirection()

        if (DEBUG_LOGS && sampleCount % STATUS_LOG_INTERVAL == 0) {
            Log.d(
                TAG,
                "Status: gyroMag=%.1f, base=%.1f, axisOk=%s, seq=%d, twists=%d".format(
                    gyroMag,
                    baseline,
                    axisDirection != null,
                    twistSequence.size,
                    totalTwists
                )
            )
        }

        if (timestamp - lastTriggerTime < COOLDOWN_MS) {
            return null
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
            return null
        }

        val isSpike =
            gyroMag > MIN_TWIST_SPEED &&
                gyroMag < MAX_TWIST_SPEED &&
                gyroMag > baseline * SPIKE_RATIO
        if (!isSpike || axisDirection == null) {
            return null
        }

        val (axis, direction) = axisDirection
        return processTwist(TwistEvent(timestamp, gyroMag, direction, axis), timestamp)
    }

    private fun processTwist(twist: TwistEvent, currentTime: Long): Int? {
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
            sequenceAxis = twist.axis
            twistSequence.add(twist)
            if (DEBUG_LOGS) {
                Log.i(TAG, "TWIST 1/3 (dir=%d, axis=%d)".format(twist.direction, twist.axis))
            }
            return null
        }

        val lockedAxis = sequenceAxis
        if (lockedAxis != null && twist.axis != lockedAxis) {
            if (DEBUG_LOGS) {
                Log.d(TAG, "Axis changed (%d -> %d) - ignoring sample".format(lockedAxis, twist.axis))
            }
            return null
        }

        val lastTwist = twistSequence.last()
        val interval = twist.timestamp - lastTwist.timestamp

        if (interval < MIN_INTERVAL_MS) {
            if (DEBUG_LOGS) {
                Log.d(TAG, "Too fast (%dms) - same motion".format(interval))
            }
            if (twist.direction == lastTwist.direction && twist.rotationSpeed > lastTwist.rotationSpeed) {
                twistSequence[twistSequence.lastIndex] = twist
            }
            return null
        }

        if (twist.direction == lastTwist.direction) {
            if (DEBUG_LOGS) {
                Log.d(TAG, "Same direction - restarting with this as first")
            }
            twistSequence.clear()
            sequenceAxis = twist.axis
            twistSequence.add(twist)
            if (DEBUG_LOGS) {
                Log.i(TAG, "TWIST 1/3 (dir=%d) [restart]".format(twist.direction))
            }
            return null
        }

        twistSequence.add(twist)

        return when (twistSequence.size) {
            2 -> {
                if (DEBUG_LOGS) {
                    Log.i(TAG, "TWIST 2/3 (dir=%d)".format(twist.direction))
                }
                null
            }

            3 -> {
                val t1 = twistSequence[0]
                val t2 = twistSequence[1]
                val t3 = twistSequence[2]
                val totalTime = t3.timestamp - t1.timestamp
                if (totalTime < MIN_TOTAL_TIME_MS) {
                    if (DEBUG_LOGS) {
                        Log.d(TAG, "Sequence too fast (%dms) - ignoring".format(totalTime))
                    }
                    twistSequence.clear()
                    sequenceAxis = null
                    return null
                }

                if (t1.direction == t3.direction && t2.direction != t1.direction) {
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
                    sequenceAxis = null
                    t1.direction
                } else {
                    Log.w(TAG, "Pattern mismatch, resetting")
                    twistSequence.clear()
                    sequenceAxis = null
                    null
                }
            }

            else -> {
                twistSequence.clear()
                sequenceAxis = null
                null
            }
        }
    }

    private fun calculateGyroBaseline(): Float {
        if (recentGyro.size < 3) return 2f
        val sorted = recentGyro.sorted()
        return sorted[sorted.size / 2]
    }

    private fun getQualifiedAxisDirection(): Pair<Int, Int>? {
        val absX = abs(currentGyroX)
        val absY = abs(currentGyroY)
        val absZ = abs(currentGyroZ)
        val axis = when {
            absX >= absY && absX >= absZ -> 0
            absY >= absX && absY >= absZ -> 1
            else -> 2
        }

        val primary = when (axis) {
            0 -> absX
            1 -> absY
            else -> absZ
        }
        if (primary < MIN_PRIMARY_AXIS_SPEED) {
            return null
        }

        val (off1, off2) = when (axis) {
            0 -> absY to absZ
            1 -> absX to absZ
            else -> absX to absY
        }
        val offAxisMax = max(off1, off2)
        if (offAxisMax > primary * MAX_OFF_AXIS_FRACTION) {
            return null
        }

        val offAxisSum = off1 + off2 + 0.001f
        val dominanceRatio = primary / offAxisSum
        if (dominanceRatio < MIN_AXIS_DOMINANCE_RATIO) {
            return null
        }

        val direction = when (axis) {
            0 -> if (currentGyroX >= 0f) 1 else -1
            1 -> if (currentGyroY >= 0f) 1 else -1
            else -> if (currentGyroZ >= 0f) 1 else -1
        }
        return axis to direction
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
        sequenceAxis = null
    }
}

