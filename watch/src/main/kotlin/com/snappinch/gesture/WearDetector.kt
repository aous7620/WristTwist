package com.snappinch.gesture

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

class WearDetector : SensorEventListener {

    companion object {
        private const val TAG = "WearDetector"
        private const val STATIONARY_DURATION_MS = 30000L
        private const val MOVEMENT_THRESHOLD = 0.1f
    }

    interface WearStateListener {
        fun onWearStateChanged(isWorn: Boolean)
    }

    private var listener: WearStateListener? = null
    private var sensorManager: SensorManager? = null
    private var offBodySensor: Sensor? = null
    private var hasOffBodySensor = false
    
    private var isWorn = true
    private var lastMotionTime = System.currentTimeMillis()

    fun setListener(listener: WearStateListener) {
        this.listener = listener
    }

    fun start(sensorManager: SensorManager) {
        this.sensorManager = sensorManager
        
        offBodySensor = sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        hasOffBodySensor = offBodySensor != null
        if (hasOffBodySensor) {
            sensorManager.registerListener(this, offBodySensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "Using OFF_BODY_DETECT sensor")
        } else {
            Log.w(TAG, "OFF_BODY_DETECT not available - disabling no-motion off-wrist fallback")
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    fun processAccelerometer(x: Float, y: Float, z: Float) {
        val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)
        
        if (magnitude > MOVEMENT_THRESHOLD) {
            lastMotionTime = System.currentTimeMillis()
            if (!isWorn) {
                updateWornState(true)
            }
        } else if (!hasOffBodySensor) {
            val stationaryTime = System.currentTimeMillis() - lastMotionTime
            if (stationaryTime > STATIONARY_DURATION_MS && isWorn) {
                Log.i(TAG, "No motion for ${stationaryTime/1000}s - marking as not worn")
                updateWornState(false)
            }
        }
    }

    private fun updateWornState(worn: Boolean) {
        if (isWorn != worn) {
            isWorn = worn
            Log.i(TAG, "Wear state: ${if (worn) "WORN" else "OFF WRIST"}")
            listener?.onWearStateChanged(worn)
        }
    }

    fun isCurrentlyWorn(): Boolean = isWorn

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) {
            val onBody = event.values[0] > 0.5f
            Log.d(TAG, "Off-body sensor: ${if (onBody) "ON" else "OFF"}")
            updateWornState(onBody)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
