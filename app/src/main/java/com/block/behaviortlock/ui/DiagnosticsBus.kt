package com.block.behaviortlock.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// DiagnosticsBus.kt — a tiny bridge that lets the Diagnostics screen show live
// sensor values without owning the sensor registration itself.
//
// MainActivity registers a lightweight SensorProbe (always-on while the
// Diagnostics screen is open). The probe writes the latest values here; the
// Compose screen just reads them each frame.
// ---------------------------------------------------------------------------

object DiagnosticsBus {
    /** Scrolling buffer for the waveform (normalised -1..1). */
    val samples = mutableStateListOf<Float>().apply {
        repeat(64) { add(0f) }
    }

    var magAccel by mutableFloatStateOf(0f)
    var magGyro by mutableFloatStateOf(0f)
    var pitch by mutableFloatStateOf(0f)
    var roll by mutableFloatStateOf(0f)
    var tilt by mutableFloatStateOf(0f)

    /** Push a new normalised sample onto the scrolling waveform. */
    fun push(value: Float) {
        samples.removeAt(0)
        samples.add(value.coerceIn(-1f, 1f))
    }

    fun reset() {
        for (i in samples.indices) samples[i] = 0f
        magAccel = 0f; magGyro = 0f; pitch = 0f; roll = 0f; tilt = 0f
    }
}