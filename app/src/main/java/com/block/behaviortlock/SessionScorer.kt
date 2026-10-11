package com.block.behaviortlock

import kotlin.math.abs

// ---------------------------------------------------------------------------
// SessionScorer.kt — compares one whole recorded session against another.
//
// This replaced the old per-segment scorer, because the user no longer picks
// segment types: they perform everything in one continuous recording and the
// app works out what they did.
//
// Three things are compared, in order of importance:
//
//   1. The SEQUENCE — how many actions, and of what kind. If the enrolled
//      routine was "3 taps, a draw, a shake" and the attempt is "3 taps, a
//      swipe", it fails regardless of how well the taps matched.
//   2. The GAPS — the pauses between actions. These are the strongest signal,
//      because people pause the same way even when their fingers land in
//      slightly different places.
//   3. The individual actions — shape, speed and pressure for each one.
//
// Unlocking requires the WHOLE routine to match, never just a prefix.
// ---------------------------------------------------------------------------

/** Result of matching one action. */
data class EventMatch(
    val kind: SegmentKind,
    val score: Float,
    val matched: Boolean,
    val note: String
)

/** The result of comparing a whole attempt against the enrolled routine. */
data class SessionResult(
    val events: List<EventMatch>,
    val sequenceOk: Boolean,
    val rhythmScore: Float,
    /** Whether the pauses between actions were close enough. A pass condition. */
    val rhythmOk: Boolean = true,
    /**
     * Whether the phone was held the way it was held during enrolment.
     * A pass condition, but SKIPPED (see [gripMeasured]) rather than failed
     * when the phone reports no usable attitude.
     */
    val gripOk: Boolean = true,
    /** False when there is no attitude to compare, e.g. the phone lay flat. */
    val gripMeasured: Boolean = false,
    /** How close the hold was, 0-100. 100 when grip could not be measured. */
    val gripScore: Float = 100f,
    /** Whether the attempt had exactly as many actions as the routine. */
    val countOk: Boolean = true,
    val completed: Boolean,      // did the user finish the whole routine?
    val totalScore: Float
) {
    val passed: Boolean
        get() = sequenceOk && countOk && rhythmOk && gripOk && completed &&
            events.all { it.matched }

    /** A short human summary for the lock screen. */
    val headline: String
        get() = when {
            passed -> "Unlocked"
            !countOk -> "Wrong number of actions"
            !completed -> "Routine not finished"
            !sequenceOk -> "Wrong sequence of actions"
            !rhythmOk -> "Pauses between actions were too different"
            !gripOk -> "Phone held at a different angle"
            else -> events.firstOrNull { !it.matched }?.let { "Mismatch: ${it.note}" }
                ?: "Did not match"
        }
}

// Shape-based actions are judged on their outline, position and size rather
// than on a sequence of separate taps.
private fun SegmentKind.isShapeBased(): Boolean =
    this == SegmentKind.DRAW || this == SegmentKind.SWIPE || this == SegmentKind.STRING

object SessionScorer {

    // The same tolerance philosophy as the old scorer: a behavioural lock that
    // rejects its owner often is worse than useless.
    private const val GAP_TOLERANCE = 0.55f        // 55% of the reference gap
    private const val DURATION_TOLERANCE = 0.50f
    private const val SPEED_TOLERANCE = 0.65f
    private const val SHAPE_MIN = 62f
    private const val POSITION_MIN = 55f
    private const val RHYTHM_MIN = 40f

    // --- Rhythm tuning -------------------------------------------------------
    //
    // Measuring the pauses between actions is the right idea, but comparing
    // every gap to the millisecond is not, and a real enrolment proves it: a
    // routine recorded on a POCO M2 Pro had gaps of
    //     238, 306, 1642, 248, 303, 1800, 271, 334 ms
    // produced by one person in one go. Asking that same person to land every
    // one of those inside +/-44% next time is not a security control, it is a
    // coin toss. Two changes fix it without weakening the idea:
    //
    //   1. SHARED_TOLERANCE - a gap is also allowed to differ by this much of
    //      the ROUTINE's median gap, so the overall pace of the routine lends
    //      tolerance to its tightest gaps.
    //   2. MEDIAN, not mean - the routine's long pauses are the ones the owner
    //      is least able to repeat exactly. Taking the median means a couple of
    //      unrepeatable gaps can no longer veto the whole routine, while a
    //      genuinely different rhythm (most gaps wrong) still fails.
    //
    // TEMPO_MIN/MAX then reject a wholesale change of pace outright, so this
    // is still a real security control: "you performed the routine, but at a
    // broadly similar pace."
    private const val SHARED_TOLERANCE = 0.35f     // 35% of the routine's median gap
    private const val TEMPO_MIN = 0.45f            // attempt pace vs enrolled pace
    private const val TEMPO_MAX = 2.20f
    /** Floor on any gap tolerance, so the score can never divide by zero. */
    private const val MIN_GAP_TOLERANCE_MS = 20f

    // --- Grip tuning ----------------------------------------------------------
    //
    // These numbers come from 25 real unlock attempts recorded on a POCO M2
    // Pro, not from guesswork. Every threshold below was set from the spread
    // that the same person actually produced.
    //
    // ANGLE is the pass condition. It is the factor that separates "the owner
    // holding the phone the way they always do" from "someone reaching for it",
    // so it is what should be able to refuse an unlock.
    //
    // Measured self-variation for one person across attempts: up to 20 degrees
    // from their enrolled angle. A 15 degree tolerance was inside that spread,
    // which is why the lock felt random. 20 degrees covers the observed range
    // while still rejecting a genuinely different hold.
    private const val GRIP_ANGLE_TOLERANCE_DEG = 20f
    private const val GRIP_ANGLE_MIN = 45f
    //
    // STEADINESS is measured, reported, and folded into the score -- but it is
    // NOT a pass condition.
    //
    // Across those 25 attempts, hand tremor measured 0.9 to 13.7 degrees while
    // a phone was being held, and 0.5 to 3.3 while it was held still for the
    // enrolment. Those ranges overlap almost completely, so steadiness cannot
    // tell the owner from a stranger -- it only adds another way to reject the
    // owner at random. It is kept because it is genuinely informative to show
    // the user, not because it should be able to block them.
    private const val GRIP_STEADY_TOLERANCE = 0.60f
    private const val GRIP_STEADY_MIN = 40f

    /** Middle value of a list. Used instead of the mean so one odd gap cannot
     *  dominate the score. */
    private fun medianOf(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    /**
     * Compare a completed attempt against the enrolled routine.
     *
     * @param enrolled the session saved during enrolment
     * @param attempt  the session just recorded
     */
    fun compare(enrolled: DetectedSession, attempt: DetectedSession): SessionResult {
        if (enrolled.isEmpty) {
            return SessionResult(
                events = emptyList(),
                sequenceOk = false,
                rhythmScore = 0f,
                rhythmOk = false,
                countOk = true,
                completed = false,
                totalScore = 0f
            )
        }

        // --- 1. Sequence -------------------------------------------------------
        // The action COUNT must match exactly.
        //
        // This is a security rule, not a usability one. If the enrolled routine
        // is "tap, tap, tap" and someone performs six taps, the routine has NOT
        // been matched. Allowing extra trailing actions would mean knowing only
        // part of the pattern unlocks the phone, so the counts must be equal
        // before anything else is considered.
        val expected = enrolled.events
        val actual = attempt.events
        val countOk = actual.size == expected.size
        val completed = actual.size >= expected.size
        val comparable = minOf(expected.size, actual.size)

        var sequenceOk = countOk
        for (i in 0 until comparable) {
            if (expected[i].kind != actual[i].kind) {
                sequenceOk = false
                break
            }
        }
        if (actual.size < expected.size) sequenceOk = false

        // --- 2. Rhythm (the gaps) ---------------------------------------------
        // The pauses between actions are the strongest signal this app has, so
        // rhythm is a PASS CONDITION and not merely part of the display score.
        val rhythm = rhythm(enrolled, attempt)
        val rhythmOk = rhythm.score >= RHYTHM_MIN && rhythm.tempoOk

        // --- 2b. Grip: how the phone was being held ---------------------------
        val grip = grip(enrolled, attempt)
        val gripOk = grip.ok

        // --- 3. Match each action ---------------------------------------------
        val matches = mutableListOf<EventMatch>()
        for (i in 0 until expected.size) {
            if (i >= actual.size) {
                matches += EventMatch(
                    expected[i].kind, 0f, false, "${expected[i].label} — missing"
                )
            } else {
                matches += matchEvent(expected[i], actual[i])
            }
        }

        // --- Overall score ------------------------------------------------------
        val actionScores = matches.map { it.score }
        val actionAvg = if (actionScores.isEmpty()) 0f else actionScores.average().toFloat()
        val rhythmWeight = if (enrolled.events.size > 1) 0.4f else 0f
        val total = actionAvg * (1 - rhythmWeight) + rhythm.score * rhythmWeight

        return SessionResult(
            events = matches,
            sequenceOk = sequenceOk,
            rhythmScore = rhythm.score,
            rhythmOk = rhythmOk,
            gripOk = gripOk,
            gripMeasured = grip.measured,
            gripScore = grip.score,
            countOk = countOk,
            completed = completed,
            totalScore = total.coerceIn(0f, 100f)
        )
    }

    /**
     * The outcome of comparing how the phone was held.
     *
     * [measured] is false when there is nothing to compare, and the factor is
     * then skipped rather than failed — the same rule the app already follows
     * for pressure.
     */
    private data class Grip(val ok: Boolean, val measured: Boolean, val score: Float)

    /**
     * Compare the angle and the steadiness of the hold.
     *
     * Two things are checked, both over the WHOLE routine rather than per
     * action, because a tap lasts a few milliseconds while the motion sensor
     * samples about every 20 ms — there is simply no attitude data inside a
     * single tap.
     *
     *   1. ANGLE — was the phone held at roughly the same tilt?
     *   2. STEADINESS — was it held still? Someone doing a deliberate unlock
     *      holds the phone steady; someone absent-mindedly poking at it moves it
     *      around. This is the part that carries the signal.
     *
     * If either recording has no usable attitude — no samples, or a phone lying
     * flat where pitch and roll are both ~0 — the factor is skipped. Measuring
     * nothing must never be able to fail a match.
     */
    /** Only the ANGLE may block here. Steadiness is reported, never enforced. */
    private fun grip(enrolled: DetectedSession, attempt: DetectedSession): Grip {
        val a = enrolled.grip
        val b = attempt.grip

        // Only the ENROLMENT decides whether there is a hold reference at all.
        // A routine recorded flat on a desk cannot be reproduced by a hand, so
        // grip is skipped rather than turned into "you must unlock lying flat".
        //
        // The attempt is a different matter. If the phone was held when the
        // routine was enrolled and is now lying flat on a table, that is a
        // genuine 20-degree difference and it is exactly what this factor is
        // for. Treating a flat ATTEMPT as "unmeasurable" let it bypass grip
        // completely, which is the opposite of what was intended.
        if (!a.hasGrip) return Grip(true, false, 100f)
        if (!b.hasAttitude) return Grip(true, false, 100f)

        val angleDiff = maxOf(
            angleBetween(a.meanPitch, b.meanPitch),
            angleBetween(a.meanRoll, b.meanRoll)
        )
        val angleScore = closeness(angleDiff, 0f, GRIP_ANGLE_TOLERANCE_DEG)

        val steadyScore = closeness(
            b.tiltJitterDeg, a.tiltJitterDeg,
            (a.tiltJitterDeg * GRIP_STEADY_TOLERANCE).coerceAtLeast(4f)
        )

        val score = (angleScore + steadyScore) / 2f
        // Only the ANGLE may block. Steadiness is reported, never enforced --
        // see the note on GRIP_STEADY_MIN for why.
        val ok = angleScore >= GRIP_ANGLE_MIN
        return Grip(ok, true, score)
    }

    /** Shortest signed difference between two angles, in degrees. */
    private fun angleBetween(a: Float, b: Float): Float {
        if (a.isNaN() || b.isNaN()) return Float.NaN
        var d = abs(a - b) % 360f
        if (d > 180f) d = 360f - d
        return d
    }

    /** How close the pauses between actions were. */
    private data class Rhythm(val score: Float, val tempoOk: Boolean)

    /**
     * Compare the pause lengths between actions.
     *
     * The first event is skipped: nothing happens before it, so its gap is
     * always zero and carries no information about the rhythm.
     */
    private fun rhythm(enrolled: DetectedSession, attempt: DetectedSession): Rhythm {
        val a = enrolled.events.drop(1).map { it.gapBeforeMs }
        val b = attempt.events.drop(1).map { it.gapBeforeMs }
        if (a.size < 2 || b.size < 2) return Rhythm(100f, true)  // nothing to compare

        val n = minOf(a.size, b.size)

        // The routine's own pace lends tolerance to its tightest gaps: a tap
        // that follows a 1.6 second pause is not judged to the millisecond.
        val routinePace = medianOf(a)

        // If the routine was performed with no measurable pause at all, there is
        // no rhythm to compare against. Measuring nothing must never be able to
        // fail a match -- and dividing by a zero tolerance here would produce
        // NaN, which fails every comparison it touches.
        if (routinePace <= 0f) return Rhythm(100f, true)

        val perGap = FloatArray(n)
        for (i in 0 until n) {
            val tol = (a[i] * GAP_TOLERANCE)
                .coerceAtLeast(routinePace * SHARED_TOLERANCE)
                .coerceAtLeast(MIN_GAP_TOLERANCE_MS)
            val diff = abs(b[i] - a[i])
            perGap[i] = (100f * (1f - diff / tol)).coerceIn(0f, 100f)
        }
        val score = medianOf(perGap.toList())

        // A wholesale change of pace is not the same behaviour, whatever the
        // individual gaps happen to look like.
        val attemptPace = medianOf(b.take(n))
        val ratio = if (routinePace <= 0f) 1f else attemptPace / routinePace
        val tempoOk = ratio in TEMPO_MIN..TEMPO_MAX

        return Rhythm(score, tempoOk)
    }

    /** Compare one action against its enrolled counterpart. */
    private fun matchEvent(enrolled: DetectedEvent, attempt: DetectedEvent): EventMatch {
        // Wrong kind of action.
        if (enrolled.kind != attempt.kind) {
            return EventMatch(
                attempt.kind, 0f, false,
                "expected ${enrolled.label}, got ${attempt.label}"
            )
        }

        // HOLD and SHAKE are judged on motion; the rest on the touch itself.
        if (enrolled.kind == SegmentKind.SHAKE) {
            val expectedEnergy = enrolled.shape.motionEnergy
            val actualEnergy = attempt.shape.motionEnergy
            val tol = (expectedEnergy * 0.8f).coerceAtLeast(1.5f)
            val score = closeness(actualEnergy, expectedEnergy, tol)
            return EventMatch(
                attempt.kind, score, score >= 50f,
                "shake energy ${fmt(actualEnergy)} vs ${fmt(expectedEnergy)}"
            )
        }

        val eShape = enrolled.shape
        val aShape = attempt.shape

        // Shape — the strongest signal for DRAW and SWIPE.
        var shapeScore = 100f
        var positionScore = 100f
        if (enrolled.kind.isShapeBased()) {
            shapeScore = Scorer.shapeSimilarity(eShape.dots, aShape.dots)
            // Where and how big it was drawn. Without this, the same outline
            // drawn in a different place or at a different size still matches,
            // because the dots themselves are normalised.
            positionScore = Scorer.positionScore(eShape, aShape)
        } else if (enrolled.kind == SegmentKind.TAP) {
            // Where a person taps is part of how they tap, so it counts towards
            // the score -- but it does not block, because the exact spot varies
            // naturally between attempts.
            positionScore = Scorer.positionScore(eShape, aShape)
        }

        // Timing.
        //
        // How long the finger was down is only a real signal for actions where
        // duration IS the gesture -- a hold, or a drawn shape. A tap lasts a few
        // milliseconds, which is the same order as the touchscreen's own
        // sampling interval, so a "too fast or too slow" failure on a tap is
        // measuring the hardware rather than the person. Real lock attempts on
        // a POCO M2 Pro were refused 7 times out of 9 for exactly that.
        //
        // So: the duration still counts towards the score for every action, but
        // it only BLOCKS where it means something.
        val durationMatters = when (enrolled.kind) {
            SegmentKind.HOLD -> true      // holding longer is the whole gesture
            SegmentKind.DRAW,
            SegmentKind.SWIPE,
            SegmentKind.STRING -> true    // drawing slowly is characteristic
            SegmentKind.TAP -> false      // sensor noise, not behaviour
        }
        val durTol = (eShape.durationMs * DURATION_TOLERANCE).coerceAtLeast(80f)
        val durationScore = closeness(aShape.durationMs, eShape.durationMs, durTol)

        // Speed.
        val spdTol = (eShape.peakSpeed * SPEED_TOLERANCE).coerceAtLeast(0.05f)
        val speedScore = closeness(aShape.peakSpeed, eShape.peakSpeed, spdTol)

        // Pressure, only when the phone actually reports it.
        var pressureScore = 100f
        if (eShape.hasPressure) {
            val pTol = ((eShape.meanPressure + eShape.peakPressure) / 2f * 0.5f).coerceAtLeast(0.05f)
            pressureScore = closeness(aShape.meanPressure, eShape.meanPressure, pTol)
        }

        // Finger count for taps.
        var fingerScore = 100f
        if (enrolled.maxFingers > 0 && enrolled.kind == SegmentKind.TAP) {
            fingerScore = if (attempt.maxFingers == enrolled.maxFingers) 100f else 0f
        }

        // Weights differ by action type: for a DRAW the shape dominates; for a
        // TAP the rhythm and count dominate.
        val shapeWeight: Float
        val otherWeight: Float
        when (enrolled.kind) {
            SegmentKind.DRAW -> { shapeWeight = 0.45f; otherWeight = 0.55f }
            SegmentKind.STRING -> { shapeWeight = 0.60f; otherWeight = 0.40f }
            SegmentKind.SWIPE -> { shapeWeight = 0.30f; otherWeight = 0.70f }
            SegmentKind.HOLD -> { shapeWeight = 0.0f; otherWeight = 1.0f }
            else -> { shapeWeight = 0.15f; otherWeight = 0.85f }
        }

        val other = if (enrolled.kind.isShapeBased())
            (durationScore + speedScore + pressureScore + fingerScore) / 4f
        else
            // Position is included for the non-shape actions (taps) so the
            // display score reflects where the actions were performed.
            (durationScore + speedScore + pressureScore + fingerScore + positionScore) / 5f
        val total = (shapeScore * shapeWeight + other * otherWeight).coerceIn(0f, 100f)

        // A draw/swipe must resemble the enrolled path, AND be drawn in roughly
        // the same place at roughly the same size.
        val shapeOk = if (enrolled.kind.isShapeBased()) {
            shapeScore >= SHAPE_MIN && positionScore >= POSITION_MIN
        } else true

        // Duration only blocks where it is meaningful (see above).
        val timingOk = !durationMatters || durationScore >= 45f

        val matched = shapeOk && timingOk && fingerScore >= 80f

        val note = when {
            shapeOk.not() && shapeScore < SHAPE_MIN -> "shape too different"
            shapeOk.not() -> "drawn in a different place or size"
            !timingOk -> "too fast or too slow"
            fingerScore < 80f -> "wrong number of fingers"
            else -> "${attempt.label} matched"
        }

        return EventMatch(attempt.kind, total, matched, note)
    }

    private fun closeness(value: Float, target: Float, tolerance: Float): Float {
        if (target.isNaN() || value.isNaN()) return 0f
        val t = if (tolerance <= 0f) 0.0001f else tolerance
        return (100f * (1f - abs(value - target) / t)).coerceIn(0f, 100f)
    }

    private fun fmt(v: Float): String =
        if (v.isNaN()) "n/a" else String.format("%.1f", v)
}
