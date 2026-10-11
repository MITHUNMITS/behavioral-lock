package com.block.behaviortlock.ui

import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.block.behaviortlock.AutoSegmenter
import com.block.behaviortlock.DetectedSession
import com.block.behaviortlock.DetectedEvent
import com.block.behaviortlock.Recorder
import com.block.behaviortlock.SegmentKind
import com.block.behaviortlock.SessionScorer
import com.block.behaviortlock.Trace
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// RecorderScreen.kt — the full-screen animated recorder.
//
// One screen serves BOTH enrolment and unlocking.
//
//   ENROLL — a big pulsing "Enroll now" button starts a recording. The user
//   performs their whole routine in one go: tap, tap, draw, shake, whatever
//   they like. The app detects each action automatically and drops a marker on
//   it. Recording stops on its own at MAX_RECORD_SECONDS, or early with Stop.
//
//   UNLOCK — the same screen. Each marker turns green the moment that action
//   matches the enrolled one, so the user sees the check happen in real time.
//   The phone only opens once the WHOLE routine has been performed.
//
// The idea the user has to learn is just one thing: do your actions one after
// another with a natural pause between them. Those pauses are the rhythm.
// ---------------------------------------------------------------------------

/** Longest recording allowed, in seconds. */
const val MAX_RECORD_SECONDS = 30

enum class RecorderMode { ENROLL, UNLOCK }

/** Green used for a matched action. */
private val MatchGreen = Color(0xFF22C55E)

@Composable
fun RecorderScreen(
    recorder: Recorder,
    mode: RecorderMode,
    enrolled: List<DetectedEvent>,
    onFinished: (Trace) -> Unit,
    onCancel: () -> Unit,
    // Overridable so an extra take can say which take it is and why.
    promptTitle: String? = null,
    promptBody: String? = null
) {
    var running by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(MAX_RECORD_SECONDS) }
    var stopped by remember { mutableStateOf(false) }

    // Deadline for this recording, in wall-clock milliseconds.
    //
    // The countdown is derived from this rather than decremented in a loop.
    // A decremented counter goes stale the moment the effect is re-entered
    // holding a leftover value, which is what made "Record again" resume at 0
    // instead of a full 30 seconds.
    var recordDeadline by remember { mutableLongStateOf(0L) }

    // Live state, refreshed a few times a second while recording.
    var detected by remember { mutableStateOf<List<DetectedEvent>>(emptyList()) }
    var matched by remember { mutableStateOf<List<Boolean>>(emptyList()) }
    var bestScore by remember { mutableIntStateOf(0) }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    /** Start a fresh capture, discarding anything recorded so far. */
    fun startRecording() {
        recorder.clear()
        detected = emptyList()
        matched = emptyList()
        secondsLeft = MAX_RECORD_SECONDS
        recordDeadline = SystemClock.elapsedRealtime() + MAX_RECORD_SECONDS * 1000L
        stopped = false
        recorder.start(SegmentKind.DRAW)
        running = true
    }

    /**
     * Stop the capture and hand it back.
     *
     * If nothing at all was captured we immediately start again instead of
     * handing over an empty recording. Without this, pressing Stop with no
     * gesture, then pressing Continue, would do nothing at all -- the parent
     * discards the empty trace and we would be stuck on a dead screen.
     */
    fun stop() {
        if (!running) return
        val trace = recorder.stop()
        running = false
        stopped = true
        if (trace.touches.isEmpty() && trace.motion.isEmpty()) {
            startRecording()
            return
        }
        onFinished(trace)
    }

    // While recording, refresh the detected actions and, in unlock mode, score
    // them against the enrolled routine so the ticks turn green live.
    //
    // When NOT recording we force the counters to zero. Without this, a retry
    // would briefly show the previous attempt's action count before the buffer
    // was cleared, which reads as "the counter is not resetting".
    LaunchedEffect(running) {
        if (!running) {
            detected = emptyList()
            matched = emptyList()
            return@LaunchedEffect
        }
        while (running) {
            val live = recorder.liveTouchBuffer
            if (live.isNotEmpty()) {
                val session = AutoSegmenter.segment(Trace(SegmentKind.DRAW, live, emptyList()))
                detected = session.events
                if (mode == RecorderMode.UNLOCK && enrolled.isNotEmpty()) {
                    val res = SessionScorer.compare(
                        DetectedSession(enrolled, 0f),
                        DetectedSession(session.events, 0f)
                    )
                    matched = res.events.map { it.matched }
                    bestScore = res.totalScore.toInt()
                }
            }
            delay(120)
        }
    }

    // Auto-stop at the time limit. The remaining time is always recomputed
    // from the deadline, so pressing "Record again" genuinely restarts at a
    // full 30 seconds instead of resuming from a stale counter.
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (running) {
            // Safety net: every recording must have a deadline. If one somehow
            // started without going through startRecording(), give it a full
            // window from now. Without this, a missing deadline reads as "long
            // expired" and the recording stops the instant it begins.
            if (recordDeadline <= 0L) {
                recordDeadline = SystemClock.elapsedRealtime() + MAX_RECORD_SECONDS * 1000L
            }
            val remaining =
                ((recordDeadline - SystemClock.elapsedRealtime()) / 1000L).toInt()
            secondsLeft = remaining.coerceAtLeast(0)
            if (remaining <= 0) {
                stop()
                break
            }
            delay(250)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
    ) {
        // ---- Capture layer: also draws the live finger trail ----
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(mode, running) {
                    if (!running) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        recorder.onDown(
                            android.os.SystemClock.uptimeMillis(),
                            down.position.x, down.position.y, 1
                        )
                        do {
                            val event = awaitPointerEvent()
                            val now = android.os.SystemClock.uptimeMillis()
                            val live = event.changes.count { it.pressed }
                            for (change in event.changes) {
                                if (change.pressed) {
                                    recorder.addTouch(
                                        now, change.position.x, change.position.y
                                    )
                                    change.consume()
                                }
                            }
                            recorder.currentFingers = live.coerceAtLeast(1)
                        } while (event.changes.any { it.pressed })
                        recorder.onUp(
                            android.os.SystemClock.uptimeMillis(),
                            down.position.x, down.position.y, 1
                        )
                    }
                }
        ) {
            LiveTrails(recorder, running)
            EventMarkers(
                detected = detected,
                matched = matched,
                screenW = recorder.screenWidth,
                screenH = recorder.screenHeight,
                modifier = Modifier.fillMaxSize()
            )
        }

        // ---- Foreground UI, anchored to the bottom ----
        // Everything here sits in a bottom band so it never covers the area
        // the user is drawing in. The drawing surface keeps the whole screen.
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom
        ) {
            // Top bar (Cancel / countdown / Continue)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        if (running) return@TextButton
                        if (!stopped) {
                            onCancel()
                        } else if (detected.isEmpty()) {
                            // Nothing was captured, so there is nothing to hand
                            // over. Start again rather than dead-ending.
                            startRecording()
                        } else {
                            onFinished(
                                Trace(
                                    SegmentKind.DRAW,
                                    recorder.liveTouchBuffer,
                                    recorder.liveMotionBuffer
                                )
                            )
                        }
                    }
                ) {
                    Text(
                        when {
                            running -> ""
                            stopped && detected.isEmpty() -> "Record again"
                            stopped -> "Continue"
                            mode == RecorderMode.ENROLL -> "Cancel"
                            else -> "Back"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (running) CountdownPill(secondsLeft) else Spacer(Modifier.size(1.dp))
            }

            // Flexible gap keeps the controls pinned to the bottom.
            Spacer(Modifier.weight(1f))

            // Bottom band: a solid panel so controls are readable and, more
            // importantly, so nothing floats over the drawing area.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                if (!running && !stopped) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StartButton(
                            pulseScale,
                            promptTitle ?: if (mode == RecorderMode.ENROLL) "Enroll now" else "Unlock"
                        ) {
                            // Must go through startRecording(): it is the only
                            // place that sets recordDeadline. Starting the
                            // recorder by hand here left the deadline at 0, so
                            // the countdown effect read it as long expired and
                            // stopped the recording the instant it began.
                            startRecording()
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            promptBody ?: if (mode == RecorderMode.ENROLL)
                                "Perform your whole routine in one go —\ntap, draw, swipe, shake, whatever you like."
                            else
                                "Perform the same routine, in the same order,\nwith the same pauses.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        if (mode == RecorderMode.UNLOCK && enrolled.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            EnrolledPreview(enrolled)
                        }
                    }
                } else if (running) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PulsingDot(MaterialTheme.colorScheme.primary)
                            Text(
                                "Recording — ${detected.size} action(s)",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        StopButton { stop() }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Recording stopped", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${detected.size} action(s) captured",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }


            // Live motion waveform: shows the phone movement being measured
            // right now, so the recording feels alive and verifiable.
            if (running) {
                Spacer(Modifier.height(10.dp))
                LiveMotionStrip(recorder)
            }

            // Live action chips, just above the bottom band.
            if (running && detected.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ActionChips(detected, matched)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------

@Composable
private fun StartButton(scale: Float, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size((160 * scale).dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.secondary
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(52.dp)
            )
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(78.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.errorContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Stop,
            contentDescription = "Stop recording",
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(38.dp)
        )
    }
}

@Composable
private fun CountdownPill(seconds: Int) {
    val urgent = seconds <= 5
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (urgent) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            "${seconds}s",
            style = MaterialTheme.typography.titleMedium,
            color = if (urgent) MaterialTheme.colorScheme.onError
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EnrolledPreview(enrolled: List<DetectedEvent>) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Your routine:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            enrolled.forEach {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        it.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionChips(detected: List<DetectedEvent>, matched: List<Boolean>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        detected.forEachIndexed { i, event ->
            val isMatch = matched.getOrNull(i) ?: false
            val bg = when {
                matched.isEmpty() -> MaterialTheme.colorScheme.secondaryContainer
                isMatch -> MatchGreen.copy(alpha = 0.28f)
                else -> MaterialTheme.colorScheme.errorContainer
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(event.label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * A live scrolling waveform of the phone's movement.
 *
 * It sits at the bottom of the screen while recording so the user can see the
 * sensors are genuinely being read — flat when the phone is still, spiky when
 * it is moved or shaken. This is the same motion data that feeds the grip and
 * shake scores, so the display is a real view of the measurement rather than
 * decoration.
 */
@Composable
private fun LiveMotionStrip(recorder: Recorder) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant

    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(track.copy(alpha = 0.35f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val trace = recorder.energyTrace()
            val midY = size.height / 2f

            // Centre line, so "flat" is visually obvious.
            drawLine(
                color = track,
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 1f
            )
            if (trace.size < 2) return@Canvas

            val stepX = size.width / (Recorder.ENERGY_WINDOW_SIZE - 1).coerceAtLeast(1)
            val path = Path()
            trace.forEachIndexed { index, value ->
                val x = index * stepX
                // Symmetric around the centre line: movement in either
                // direction shows as a deflection away from stillness.
                val y = midY - (value.coerceIn(-1f, 1f) * (size.height / 2.2f))
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, accent, style = Stroke(width = 2.5f))
        }
    }
}
@Composable
private fun LiveTrails(recorder: Recorder, running: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        // Only draw while recording. Otherwise a finished attempt's
        // trail stays painted on the canvas and looks like stale art.
        if (!running) return@Canvas
        val live = recorder.liveTouchBuffer
        if (live.size < 2) return@Canvas
        val w = recorder.screenWidth.coerceAtLeast(1f)
        val h = recorder.screenHeight.coerceAtLeast(1f)
        val path = Path()
        live.forEachIndexed { i, p ->
            val x = p.x / w * size.width
            val y = p.y / h * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, primary, style = Stroke(width = 7f))
    }
}

@Composable
private fun EventMarkers(
    detected: List<DetectedEvent>,
    matched: List<Boolean>,
    screenW: Float,
    screenH: Float,
    modifier: Modifier = Modifier
) {
    val pending = MaterialTheme.colorScheme.secondary
    val errorColor = MaterialTheme.colorScheme.error
    Canvas(modifier) {
        val w = screenW.coerceAtLeast(1f)
        val h = screenH.coerceAtLeast(1f)
        detected.forEachIndexed { i, event ->
            val touch = event.touches.firstOrNull() ?: return@forEachIndexed
            val isMatch = matched.getOrNull(i) ?: false
            val color = when {
                matched.isEmpty() -> pending
                isMatch -> MatchGreen
                else -> errorColor
            }
            drawCircle(
                color = color,
                radius = 20f,
                center = Offset(
                    (touch.x / w).coerceIn(0f, 1f) * size.width,
                    (touch.y / h).coerceIn(0f, 1f) * size.height
                )
            )
        }
    }
}
