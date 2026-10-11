package com.block.behaviortlock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.block.behaviortlock.AutoSegmenter
import com.block.behaviortlock.DetectedEvent
import com.block.behaviortlock.Recorder
import com.block.behaviortlock.SegmentKind
import com.block.behaviortlock.SessionResult
import com.block.behaviortlock.SessionScorer
import com.block.behaviortlock.Trace

// ---------------------------------------------------------------------------
// EnrolFlow.kt — the screens that come after recording.
//
// The rule here is "record once, then test it". An enrolment that has never
// been tested is the most common reason someone thinks the lock is broken, so
// the app always makes testing the very next thing to do.
// ---------------------------------------------------------------------------

private val MatchGreen = androidx.compose.ui.graphics.Color(0xFF22C55E)

/** Step 1: show what the app detected, and offer Record again / Test. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnrolReviewScreen(
    summary: String,
    events: List<DetectedEvent>,
    /**
     * The tilt the routine was recorded at, or null if it was recorded flat.
     *
     * Warned about HERE, at enrolment time, because a flat recording silently
     * disables the "hold it the same way" factor and the user has no way to
     * tell that from a broken feature. Finding out about it days later, on the
     * home screen, is too late.
     */
    holdPitch: Float? = null,
    holdRoll: Float? = null,
    holdWobble: Float? = null,
    /** How many recordings of the routine the user has made. */
    takeCount: Int = 1,
    maxTakes: Int = 5,
    recommendedTakes: Int = 3,
    /** Transient feedback from the last saved or rejected take. */
    infoMessage: String? = null,
    onAddTake: () -> Unit = {},
    onRemoveTake: (Int) -> Unit = {},
    onRecordAgain: () -> Unit,
    onTest: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("Routine recorded", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "The app worked out these actions from one recording:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        SectionCard(title = "Detected actions", subtitle = "$summary") {
            Column {
                SegmentIconRow(events, iconSize = 40.dp)
                Spacer(Modifier.height(16.dp))
                RoutineTimeline(events)
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    events.forEachIndexed { i, event -> DetectedRow(i, event) }
                }
            }
        }

        SectionCard(
            title = "Recordings",
            subtitle = "$takeCount of $maxTakes"
        ) {
            Column {
                if (infoMessage != null) {
                    Text(
                        infoMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    if (takeCount < recommendedTakes)
                        "One recording is a single sample of how you happen to " +
                            "behave today. Record $recommendedTakes and the lock " +
                            "learns your normal variation, so it stops rejecting " +
                            "you on a slightly different day."
                    else
                        "Good — this routine now covers your normal variation. " +
                            "More takes widen what the lock accepts, so there " +
                            "is a limit of $maxTakes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(maxTakes) { i ->
                        val filled = i < takeCount
                        Box(
                            Modifier
                                .size(if (filled) 14.dp else 10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (filled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                        )
                    }
                }
                if (takeCount < maxTakes) {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onAddTake,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Record take ${takeCount + 1}")
                    }
                }
                if (takeCount > 1) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Delete a bad take:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    // Wraps: a plain Row overflows once there are several
                    // takes, which pushed the rest of the screen out of reach.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        repeat(takeCount) { i ->
                            OutlinedButton(
                                onClick = { onRemoveTake(i) },
                                contentPadding = PaddingValues(
                                    horizontal = 14.dp,
                                    vertical = 6.dp
                                )
                            ) {
                                Text("Take ${i + 1}")
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "To start over completely, use \"Record again\" at the " +
                            "top instead — that replaces all of them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        if (holdPitch == null || holdRoll == null || holdWobble == null) {
            Spacer(Modifier.height(14.dp))
            SectionCard(title = "Recorded lying flat") {
                Column {
                    HoldPreviewRow(
                        pitchDeg = null, rollDeg = null, wobbleDeg = null
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onRecordAgain,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Re-record while holding the phone")
                    }
                }
            }
        }

        if (events.size < 2) {
            Spacer(Modifier.height(14.dp))
            SectionCard(title = "Add more actions") {
                Text(
                    "This routine has only ${events.size} action. Do at least two " +
                        "different actions — for example tap three times, then draw " +
                        "a shape — so the lock is actually secure.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        if (summary.split(" ").size > 15) {
            Spacer(Modifier.height(14.dp))
            SectionCard(title = "That's a long routine") {
                Text(
                    "This routine has a lot of actions. A shorter one is quicker to " +
                        "perform every day. Consider re-recording something shorter.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onTest, modifier = Modifier.fillMaxWidth()) {
            Text("Test my routine")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onRecordAgain, modifier = Modifier.fillMaxWidth()) {
            Text("Record it again")
        }
    }
}

/** One detected action, with its key numbers. */
@Composable
private fun DetectedRow(index: Int, event: DetectedEvent) {
    val shape = event.shape
    Row(verticalAlignment = Alignment.CenterVertically) {
        SegmentIconBadge(event.kind, 34.dp)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(event.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                buildString {
                    append("${shape.durationMs.toInt()}ms")
                    if (event.gapBeforeMs > 60) append("  ·  gap ${event.gapBeforeMs.toInt()}ms")
                    if (shape.peakSpeed > 0.05f) append("  ·  speed ${"%.2f".format(shape.peakSpeed)}")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Step 2: the user immediately tries their routine on the same animated
 * recorder they will meet at unlock time.
 */
@Composable
fun TestRoutineScreen(
    recorder: Recorder,
    enrolled: List<DetectedEvent>,
    result: SessionResult?,
    onResult: (SessionResult) -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    // The sheet's result is owned by this screen. The parent's `result` is only
    // an initial value: once the user dismisses the sheet we must NOT fall back
    // to it, otherwise "Try again" silently does nothing.
    var dismissed by remember { mutableStateOf(false) }
    var localResult by remember { mutableStateOf<SessionResult?>(null) }
    val shownResult = if (dismissed) null else (localResult ?: result)

    // Bumping this rebuilds the recorder screen from scratch, clearing its
    // detected actions, matched ticks and stopped flag.
    var attempt by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize()) {
        key(attempt) {
            RecorderScreen(
                recorder = recorder,
                mode = RecorderMode.UNLOCK,
                enrolled = enrolled,
                onFinished = { trace ->
                    val scored = SessionScorer.compare(
                        recorder.enrolledSession,
                        AutoSegmenter.segment(trace)
                    )
                    localResult = scored
                    dismissed = false
                    onResult(scored)
                },
                onCancel = onBack
            )
        }

        if (shownResult != null) {
            TestResultSheet(
                result = shownResult,
                onRetry = {
                    // Wipe the old recording, forget the old result, and force
                    // the recorder screen to rebuild.
                    recorder.clear()
                    localResult = null
                    dismissed = true
                    attempt += 1
                },
                onDone = onFinish
            )
        }
    }
}

/** The pass/fail panel shown after testing. */
@Composable
private fun TestResultSheet(
    result: SessionResult,
    onRetry: () -> Unit,
    onDone: () -> Unit
) {
    val color = if (result.passed) MatchGreen else MaterialTheme.colorScheme.error
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.97f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // Scrollable, because a long routine produces a long list of
                // actions and the buttons must always be reachable.
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (result.passed) "It works!" else "Not quite",
                style = MaterialTheme.typography.headlineMedium,
                color = color
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Overall match: ${result.totalScore.toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(22.dp))

            SectionCard(title = "Action by action") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    result.events.forEach { match ->
                        Column {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(match.kind.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (match.matched) "matched" else "off",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (match.matched) MatchGreen
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(Modifier.height(5.dp))
                            ScoreBar(
                                progress = match.score / 100f,
                                color = if (match.matched) MatchGreen
                                else MaterialTheme.colorScheme.error
                            )
                            Text(
                                match.note,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(if (result.passed) "Done" else "Back to review")
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("Try again")
            }
        }
    }
}