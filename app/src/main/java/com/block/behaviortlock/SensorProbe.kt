package com.block.behaviortlock

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.block.behaviortlock.ui.DiagnosticsBus
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// SensorProbe.kt — a lightweight, always-on-while-visible sensor listener that
// exists purely to feed the Diagnostics screen's live readouts and waveforms.
//
// This is separate from Recorder (which does the real gesture capture) so that
// viewing diagnostics never interferes with an in-progress capture.
// ---------------------------------------------------------------------------

class SensorProbe(private val sensorManager: SensorManager) : SensorEventListener {

    private var running = false

    private var accel = FloatArray(3)
    private var gyro = FloatArray(3)

    fun start() {
        if (running) return
        running = true
        val delay = SensorManager.SENSOR_DELAY_UI
        fun reg(type: Int) {
            sensorManager.getDefaultSensor(type)?.let {
                sensorManager.registerListener(this, it, delay, 0)
            }
        }
        reg(Sensor.TYPE_ACCELEROMETER)
        reg(Sensor.TYPE_GYROSCOPE)
        reg(Sensor.TYPE_GAME_ROTATION_VECTOR)
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
        DiagnosticsBus.reset()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accel = event.values.copyOf()
                val mag = sqrt(
                    (accel[0] * accel[0] + accel[1] * accel[1] + accel[2] * accel[2]).toDouble()
                ).toFloat()
                DiagnosticsBus.magAccel = mag
                DiagnosticsBus.push((mag - 9.81f) / 9.81f)
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyro = event.values.copyOf()
                val mag = sqrt(
                    (gyro[0] * gyro[0] + gyro[1] * gyro[1] + gyro[2] * gyro[2]).toDouble()
                ).toFloat()
                DiagnosticsBus.magGyro = mag
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val matrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(matrix, orientation)
                val p = Math.toDegrees(orientation[1].toDouble()).toFloat()
                val r = Math.toDegrees(orientation[2].toDouble()).toFloat()
                DiagnosticsBus.pitch = p
                DiagnosticsBus.roll = r
                DiagnosticsBus.tilt = p
                DiagnosticsBus.push(p / 90f)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}