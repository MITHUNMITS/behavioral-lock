package com.block.behaviortlock.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------
// HoldPreview.kt — a small drawing of the phone, tilted the way it was held
// during enrolment.
//
// This exists because "how should I hold it?" is genuinely hard to say in
// words. If the routine was recorded with the phone tilted 58 degrees towards
// you, showing that tilt is far clearer than printing "-57.8 degrees" and
// hoping the reader can picture it.
//
// It is a deliberate simplification, not a 3D engine: pitch foreshortens the
// phone, roll spins it, and a thin offset panel gives it an edge. The exact
// projection does not matter — what matters is that it matches the number
// printed underneath it.
// ---------------------------------------------------------------------------

/** How large the drawing is, in dp. */
private const val PREVIEW_SIZE = 132

@Composable
fun HoldPreview(
    pitchDeg: Float,
    rollDeg: Float,
    wobbleDeg: Float,
    modifier: Modifier = Modifier
) {
    // A very slow breath, scaled to how unsteady the recorded hold actually
    // was. A steady hold looks almost still; a shaky one visibly sways. It is
    // a hint, not a replay of the recording.
    val phase by rememberInfiniteTransition(label = "holdBreath").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phase"
    )
    val sway = sin(phase * PI).toFloat() * wobbleDeg.coerceIn(0f, 12f) * 0.5f

    val body = MaterialTheme.colorScheme.primary
    val edge = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val screen = Color(0xFF0B1220)
    val dot = MaterialTheme.colorScheme.secondary

    Canvas(modifier.size(PREVIEW_SIZE.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f

        val bodyW = w * 0.44f
        val bodyH = h * 0.78f

        // Tilting the phone away from the viewer foreshortens it; tilting it
        // towards them would lengthen it, which would look wrong, so it is
        // clamped rather than allowed to grow.
        val pitchRad = Math.toRadians((pitchDeg + sway).toDouble())
        val squash = cos(pitchRad).toFloat().coerceIn(0.32f, 1f)

        rotate(degrees = rollDeg + sway * 0.4f, pivot = Offset(cx, cy)) {
            scale(scaleX = 1f, scaleY = squash, pivot = Offset(cx, cy)) {
                val depth = w * 0.055f
                val left = cx - bodyW / 2f
                val top = cy - bodyH / 2f
                val bodySize = Size(bodyW, bodyH)
                val corner = CornerRadius(bodyW * 0.13f, bodyW * 0.13f)

                // The far edge, offset up and right, so the phone reads as a
                // solid object rather than a flat rectangle.
                drawRoundRect(
                    color = edge,
                    topLeft = Offset(left + depth, top - depth * 0.55f),
                    size = bodySize,
                    cornerRadius = corner
                )

                drawRoundRect(
                    color = body,
                    topLeft = Offset(left, top),
                    size = bodySize,
                    cornerRadius = corner
                )

                val inset = bodyW * 0.085f
                drawRoundRect(
                    color = screen,
                    topLeft = Offset(left + inset, top + inset),
                    size = Size(bodyW - inset * 2f, bodyH - inset * 2f),
                    cornerRadius = CornerRadius(bodyW * 0.07f, bodyW * 0.07f)
                )

                // Three tap marks on the screen: "this is where the routine
                // happens", and a visual cue that the pattern runs top to bottom.
                val dotR = bodyW * 0.075f
                val dotX = cx
                for (i in 0 until 3) {
                    val y = top + bodyH * (0.30f + i * 0.18f)
                    drawCircle(
                        color = dot.copy(alpha = 0.85f - i * 0.2f),
                        radius = dotR,
                        center = Offset(dotX, y)
                    )
                }

                // Speaker slot, so it reads unmistakably as a phone.
                drawRoundRect(
                    color = screen.copy(alpha = 0.9f),
                    topLeft = Offset(cx - bodyW * 0.13f, top + inset * 0.42f),
                    size = Size(bodyW * 0.26f, bodyH * 0.012f),
                    cornerRadius = CornerRadius(bodyH * 0.006f, bodyH * 0.006f)
                )
            }
        }
    }
}

/**
 * The drawing plus the numbers, side by side.
 *
 * The caption always states what the angle factor is currently doing, because
 * a routine enrolled flat has no hold reference and that must never be a
 * silent surprise.
 */
@Composable
fun HoldPreviewRow(
    pitchDeg: Float?,
    rollDeg: Float?,
    wobbleDeg: Float?,
    modifier: Modifier = Modifier
) {
    val enrolled = pitchDeg != null && rollDeg != null && wobbleDeg != null
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (enrolled) {
            HoldPreview(pitchDeg!!, rollDeg!!, wobbleDeg!!)
        } else {
            HoldPreview(pitchDeg = 0f, rollDeg = 0f, wobbleDeg = 1f)
        }

        Spacer(Modifier.width(14.dp))

        Column {
            if (enrolled) {
                Text(
                    "Hold it like this",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tilt ${fmt1(pitchDeg!!)}° pitch, ${fmt1(rollDeg!!)}° roll.\n" +
                        "Checked within 20° when unlocking.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (wobbleDeg!! > 4f) "Recorded hold was shaky — try to keep it steadier."
                    else "Recorded hold was steady.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Hold angle not enrolled",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "This routine was recorded with the phone lying flat, so the " +
                        "angle is not checked. Re-record it while holding the " +
                        "phone to turn this on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}

private fun fmt1(v: Float): String = String.format("%.1f", v)