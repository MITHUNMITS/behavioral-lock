package com.block.behaviortlock.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.block.behaviortlock.DetectedEvent
import com.block.behaviortlock.SegmentKind

// ---------------------------------------------------------------------------
// RoutineVisuals.kt — icons and a timeline for the detected actions.
//
// Instead of reading "Draw, Tap, Tap" as plain text, the app shows a row of
// icons and a timeline strip showing WHEN each action happened and how long the
// pause before it was. The gaps are the strongest signal the scorer uses, so
// showing them makes the whole idea visible instead of hidden.
// ---------------------------------------------------------------------------

/** The icon that represents each kind of action. */
fun SegmentKind.icon(): ImageVector = when (this) {
    SegmentKind.TAP -> Icons.Default.TouchApp
    SegmentKind.HOLD -> Icons.Default.PanTool
    SegmentKind.SWIPE -> Icons.Default.Swipe
    SegmentKind.DRAW -> Icons.Default.Brush
    SegmentKind.SHAKE -> Icons.Default.Vibration
    SegmentKind.STRING -> Icons.Default.Timeline
}

/** The colour used for each kind of action. */
fun SegmentKind.tint(): Color = when (this) {
    SegmentKind.TAP -> Color(0xFF38BDF8)
    SegmentKind.HOLD -> Color(0xFFA78BFA)
    SegmentKind.SWIPE -> Color(0xFF34D399)
    SegmentKind.DRAW -> Color(0xFFFBBF24)
    SegmentKind.SHAKE -> Color(0xFFFB7185)
    SegmentKind.STRING -> Color(0xFFC084FC)
}

/** A single icon badge for one action. */
@Composable
fun SegmentIconBadge(
    kind: SegmentKind,
    size: Dp = 34.dp,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false
) {
    val tint = if (dimmed) kind.tint().copy(alpha = 0.35f) else kind.tint()
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3))
            .background(kind.tint().copy(alpha = if (dimmed) 0.08f else 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            kind.icon(),
            contentDescription = kind.label,
            tint = tint,
            modifier = Modifier.size(size * 0.55f)
        )
    }
}

/** A horizontal row of icons — one per action, in order. */
@Composable
fun SegmentIconRow(
    events: List<DetectedEvent>,
    modifier: Modifier = Modifier,
    iconSize: Dp = 34.dp
) {
    if (events.isEmpty()) return
    // Scrollable, so a long routine never overflows the card or gets clipped
    // mid-row. A routine can legitimately hold a dozen or more actions.
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        events.forEach { event ->
            SegmentIconBadge(event.kind, iconSize)
        }
    }
}

/**
 * A timeline strip of the routine.
 *
 * Each action is drawn as a coloured block positioned by when it happened, so
 * a long pause is visibly longer than a short one. This makes the rhythm — the
 * signal the lock actually depends on — something the user can see and correct.
 */
@Composable
fun RoutineTimeline(
    events: List<DetectedEvent>,
    modifier: Modifier = Modifier
) {
    if (events.isEmpty()) return

    // Total length of the routine, including the gaps between actions.
    val total = remember_total(events)
    if (total <= 0f) return

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
        ) {
            events.forEachIndexed { index, event ->
                val gapFraction = (event.gapBeforeMs / total).coerceIn(0f, 1f)
                val actionFraction = (event.durationMs / total).coerceAtLeast(0.02f)

                // Gap block — the pause before this action.
                if (index > 0 && gapFraction > 0.01f) {
                    Box(
                        Modifier
                            .weight(gapFraction)
                            .fillMaxHeight()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }

                // Action block.
                Box(
                    Modifier
                        .weight(actionFraction)
                        .fillMaxHeight()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(event.kind.tint().copy(alpha = 0.75f))
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // Legend under the strip.
        // FlowRow so long legends wrap onto the next line instead of each
        // label being squeezed into an unreadable column.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            events.forEach { event ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(event.kind.tint())
                    )
                    Text(
                        " ${event.label} · ${event.gapBeforeMs.toInt()}ms",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}


/** Total duration of the routine including gaps. */
private fun remember_total(events: List<DetectedEvent>): Float {
    val gaps = events.drop(1).sumOf { it.gapBeforeMs.toDouble() }.toFloat()
    val actions = events.sumOf { it.durationMs.toDouble() }.toFloat()
    return gaps + actions
}