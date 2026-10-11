package com.block.behaviortlock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import com.block.behaviortlock.Recorder

// ---------------------------------------------------------------------------
// Diagnostics.kt — live sensor readouts.
//
// This is the single most useful screen in the app. It answers one question:
// "what can this phone actually measure?" Every number updates in real time
// from the sensors, and the availability rows tell us at a glance which
// features will work and which will be skipped.
// ---------------------------------------------------------------------------

@Composable
fun DiagnosticsContent(recorder: Recorder) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        IntroCard()

        SectionCard(
            title = "Sensor availability",
            subtitle = "Determines which factors the lock can score"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SensorAvailability("Accelerometer", recorder.hasAccelerometer, "How the phone moves and is held")
                    SensorAvailability("Gravity", recorder.hasGravity, "Device tilt (portrait / landscape)")
                    SensorAvailability("Rotation vector", recorder.hasRotationVector, "Gyro orientation, drift-free")
                    SensorAvailability("Gyroscope", recorder.hasGyroscope, "Rotation speed")
                    SensorAvailability("Linear acceleration", recorder.hasLinearAcceleration, "Pure motion for shake detection")
                }
        }

        SectionCard(
            title = "Live motion",
            subtitle = "Waveforms update as you move the phone"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                WaveBlock(
                    label = "Accelerometer magnitude",
                    color = MaterialTheme.colorScheme.primary
                ) { DiagnosticsBus.magAccel }
                WaveBlock(
                    label = "Rotation rate magnitude",
                    color = MaterialTheme.colorScheme.tertiary
                ) { DiagnosticsBus.magGyro }
                WaveBlock(
                    label = "Tilt (pitch / roll)",
                    color = MaterialTheme.colorScheme.secondary
                ) { DiagnosticsBus.tilt }
            }
        }

        SectionCard(
            title = "Live values"
        ) {
            ReadoutRow {
                Readout("Tilt X", String.format("%.1f°", DiagnosticsBus.pitch))
                Readout("Tilt Y", String.format("%.1f°", DiagnosticsBus.roll))
                Readout("Motion", String.format("%.2f", DiagnosticsBus.magAccel))
            }
        }
    }
}

@Composable
private fun IntroCard() {
    SectionCard(title = "Diagnostics", subtitle = "Check this phone's capabilities") {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 10.dp)
            )
            Text(
                "Not sure why the lock isn't recognising you? Start here. " +
                    "If a factor is unavailable the lock skips it automatically rather than failing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SensorAvailability(name: String, available: Boolean, note: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (available) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = if (available) "Available" else "Not available",
            tint = if (available) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(end = 10.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            if (available) "yes" else "no",
            style = MaterialTheme.typography.titleMedium,
            color = if (available) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )
    }
}

/** A labelled scrolling waveform. */
@Composable
private fun WaveBlock(
    label: String,
    color: Color,
    sample: () -> Float
) {
    Column {
        WaveformLabel(label)
        Box(
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .height(70.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                )
        ) {
            LiveWaveform(color = color, sample = sample)
        }
    }
}

/** A waveform that redraws from the shared sample buffer each frame. */
@Composable
private fun LiveWaveform(color: Color, sample: () -> Float) {
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val points = DiagnosticsBus.samples
        if (points.size < 2) return@Canvas
        val midY = size.height / 2f
        val path = Path()
        val stepX = size.width / (points.size - 1).coerceAtLeast(1)
        points.forEachIndexed { index, v ->
            val x = index * stepX
            val y = midY - (v.coerceIn(-1f, 1f) * (size.height / 2.4f))
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
        // A live dot at the end so it reads as "streaming".
        drawCircle(color, radius = 4f, center = Offset(size.width - 2f, midY - sample().coerceIn(-1f, 1f) * (size.height / 2.4f)))
    }
}