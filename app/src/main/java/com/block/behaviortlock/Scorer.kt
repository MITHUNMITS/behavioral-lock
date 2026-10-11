package com.block.behaviortlock

import kotlin.math.abs
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Scorer.kt — compares a fresh recording against the stored templates.
//
// Deliberately NOT machine learning. This is measurement: we have a known
// reference and a new sample, so we compute how close they are. Every factor
// is reported separately with a reason, which is what makes the lock screen
// able to tell the user *why* it failed.
//
// Design rules:
//   * A factor that the phone cannot measure is SKIPPED, never failed, and the
//     remaining factors are re-weighted automatically.
//   * Every score is 0..100 so the UI can draw a simple progress bar.
// ---------------------------------------------------------------------------

/** How close two values are, as a 0..100 score. Tolerance grows with the value. */
private fun closeness(value: Float, target: Float, tolerance: Float): Float {
    if (target.isNaN() || value.isNaN()) return 0f
    val diff = abs(value - target)
    val t = if (tolerance <= 0f) 0.0001f else tolerance
    return (100f * (1f - diff / t)).coerceIn(0f, 100f)
}

/** One scored factor and why it got that score. */
data class Factor(
    val name: String,
    val score: Float,
    val status: Status,
    val detail: String
) {
    enum class Status { PASS, FAIL, SKIPPED }

    val isBlocking: Boolean get() = status == Status.FAIL
}

/** The result of scoring one segment attempt. */
data class SegmentResult(
    val kind: SegmentKind,
    val factors: List<Factor>,
    val total: Float,
    val passed: Boolean
) {
    /** A one-line summary for the lock screen. */
    val headline: String
        get() {
            val failed = factors.filter { it.isBlocking }
            return when {
                passed -> "Matched"
                failed.isEmpty() -> "Matched"
                else -> failed.joinToString(", ") { it.name }
            }
        }
}

/** The result of scoring a whole routine. */
data class RoutineResult(
    val segments: List<SegmentResult>,
    val total: Float,
    val passed: Boolean
) {
    val allFactors: List<Factor> get() = segments.flatMap { it.factors }
}

object Scorer {

    // Tuning constants. These are deliberately generous — a behavioural lock
    // that rejects the owner more than a few times a week is worse than useless.
    private const val DURATION_TOLERANCE = 0.45f      // 45% of the reference
    private const val GAP_TOLERANCE = 0.60f           // rhythm is the softest signal
    private const val RHYTHM_TOLERANCE = 0.80f        // consistency matters less
    private const val SPEED_TOLERANCE = 0.70f
    private const val PRESSURE_TOLERANCE = 0.50f
    private const val STEADINESS_TOLERANCE = 0.90f
    private const val MOTION_TOLERANCE = 1.00f
    private const val ROTATION_TOLERANCE = 45f        // degrees, absolute
    private const val FINGER_TOLERANCE = 0f           // exact match, count only

    /** Score one attempt against the templates recorded during enrolment. */
    fun score(kind: SegmentKind, templates: List<Trace>, attempt: Trace): SegmentResult {
        if (templates.isEmpty()) {
            return SegmentResult(kind, listOf(
                Factor("Template", 0f, Factor.Status.FAIL, "No reference recorded yet")
            ), 0f, false)
        }

        // Score against every enrolled template, keep the best result.
        // (People repeat their own gesture a little differently each time.)
        val best = templates
            .map { scoreAgainst(kind, ShapeBuilder.build(it), ShapeBuilder.build(attempt)) }
            .maxByOrNull { it.total }!!
        return best
    }

    /** Compare one attempt against one specific reference. */
    fun scoreAgainst(kind: SegmentKind, reference: Shape, attempt: Shape): SegmentResult {
        val factors = mutableListOf<Factor>()

        // --- Duration -------------------------------------------------------
        val durTol = (reference.durationMs * DURATION_TOLERANCE).coerceAtLeast(120f)
        val durScore = closeness(attempt.durationMs, reference.durationMs, durTol)
        factors += Factor(
            "Timing", durScore,
            status(durScore, 60f),
            "${attempt.durationMs.toInt()}ms (enrolled ${reference.durationMs.toInt()}ms)"
        )

        // --- Shape (only for gestures that have a path) ---------------------
        if (kind == SegmentKind.DRAW || kind == SegmentKind.SWIPE) {
            val shapeScore = shapeSimilarity(reference.dots, attempt.dots)
            factors += Factor(
                "Shape", shapeScore,
                status(shapeScore, 55f),
                "${(100 - shapeScore).toInt()}% deviation from enrolled path"
            )
        }

        // --- Speed ----------------------------------------------------------
        val spdTol = (reference.peakSpeed * SPEED_TOLERANCE).coerceAtLeast(0.05f)
        val spdScore = closeness(attempt.peakSpeed, reference.peakSpeed, spdTol)
        factors += Factor(
            "Speed", spdScore,
            status(spdScore, 55f),
            "peak ${fmt(attempt.peakSpeed)} vs ${fmt(reference.peakSpeed)} screen-widths/s"
        )

        // --- Rhythm ---------------------------------------------------------
        if (kind == SegmentKind.TAP) {
            val gapTol = (reference.meanGapMs * GAP_TOLERANCE).coerceAtLeast(40f)
            val gapScore = closeness(attempt.meanGapMs, reference.meanGapMs, gapTol)
            factors += Factor(
                "Rhythm", gapScore,
                status(gapScore, 50f),
                "mean gap ${attempt.meanGapMs.toInt()}ms (enrolled ${reference.meanGapMs.toInt()}ms)"
            )

            val devTol = (reference.gapDeviationMs * RHYTHM_TOLERANCE).coerceAtLeast(25f)
            val devScore = closeness(attempt.gapDeviationMs, reference.gapDeviationMs, devTol)
            factors += Factor(
                "Consistency", devScore,
                status(devScore, 45f),
                "spread ${attempt.gapDeviationMs.toInt()}ms (enrolled ${reference.gapDeviationMs.toInt()}ms)"
            )
        }

        // --- Finger count ---------------------------------------------------
        if (reference.maxFingers > 0) {
            val fingerScore = if (attempt.maxFingers == reference.maxFingers) 100f else 0f
            factors += Factor(
                "Fingers", fingerScore,
                status(fingerScore, 80f),
                "${attempt.maxFingers} finger(s), enrolled ${reference.maxFingers}"
            )
        }

        // --- Pressure (skipped when the phone reports none) ------------------
        if (reference.hasPressure) {
            val pTol = ((reference.meanPressure + reference.peakPressure) / 2f * PRESSURE_TOLERANCE)
                .coerceAtLeast(0.05f)
            val pScore = closeness(attempt.meanPressure, reference.meanPressure, pTol)
            factors += Factor(
                "Pressure", pScore,
                status(pScore, 50f),
                "mean ${fmt(attempt.meanPressure)} vs ${fmt(reference.meanPressure)}"
            )
        } else {
            factors += Factor("Pressure", 0f, Factor.Status.SKIPPED, "Phone does not report pressure")
        }

        // --- Motion / grip ---------------------------------------------------
        if (reference.motionEnergy > 0.05f) {
            val mTol = (reference.motionEnergy * MOTION_TOLERANCE).coerceAtLeast(0.3f)
            val mScore = closeness(attempt.motionEnergy, reference.motionEnergy, mTol)
            factors += Factor(
                "Grip", mScore,
                status(mScore, 50f),
                "motion ${fmt(attempt.motionEnergy)} vs ${fmt(reference.motionEnergy)} m/s²"
            )
        } else {
            factors += Factor("Grip", 0f, Factor.Status.SKIPPED, "Phone is too still to measure grip")
        }

        // --- Rotation (HOLD) --------------------------------------------------
        if (kind == SegmentKind.HOLD && reference.rotationDeg > 5f) {
            val rScore = closeness(attempt.rotationDeg, reference.rotationDeg, ROTATION_TOLERANCE)
            factors += Factor(
                "Rotation", rScore,
                status(rScore, 55f),
                "${attempt.rotationDeg.toInt()}° turned, enrolled ${reference.rotationDeg.toInt()}°"
            )
        }

        // --- Compute the weighted total over the factors that actually apply ---
        val scored = factors.filter { it.status != Factor.Status.SKIPPED }
        val total = if (scored.isEmpty()) 0f else scored.map { it.score }.average().toFloat()
        val passed = factors.none { it.isBlocking }

        return SegmentResult(kind, factors, total, passed)
    }

    /**
     * How similar two 64-dot shapes are, 100 = identical.
     *
     * We try every possible starting point of the reference and keep the best
     * match. A signature drawn right-to-left reduces to different dots than the
     * same signature drawn left-to-right, even though the motion is identical.
     * Trying all 64 rotations costs 4096 comparisons — still well under a
     * millisecond — and removes the need for any rotation maths.
     */
    fun shapeSimilarity(reference: List<Pair<Float, Float>>, attempt: List<Pair<Float, Float>>): Float {
        if (reference.isEmpty() || attempt.isEmpty()) return 0f
        val n = minOf(reference.size, attempt.size)
        var best = Float.MAX_VALUE

        for (shift in 0 until n) {
            var total = 0f
            for (i in 0 until n) {
                val r = reference[(i + shift) % n]
                val a = attempt[i]
                val dx = (r.first - a.first).toDouble()
                val dy = (r.second - a.second).toDouble()
                total += (dx * dx + dy * dy).toFloat()
            }
            val mean = total / n
            if (mean < best) best = mean
        }

        // RMS distance in the unit square. 0.0 is a perfect match; past about
        // 0.06 the paths are visibly different, so the score falls away sharply
        // rather than letting a loosely-aligned shape be rescued by the best
        // of 64 starting points.
        val rms = sqrt(best.toDouble()).toFloat()
        val score = when {
            rms <= 0.06f -> 100f - (rms / 0.06f) * 12f            // 100 -> 88
            rms <= 0.15f -> 88f - ((rms - 0.06f) / 0.09f) * 30f   //  88 -> 58
            else -> (58f * (1f - (rms - 0.15f) / 0.35f)).coerceAtLeast(0f)
        }
        return score.coerceIn(0f, 100f)
    }

    /**
     * Compare WHERE and HOW BIG a shape was drawn.
     *
     * Normalising the dots deliberately throws away position and size, so an
     * identical outline drawn somewhere else would still match. This puts that
     * information back: the centre of the drawing must be close, and its size
     * must be in the same ballpark.
     */
    fun positionScore(reference: Shape, attempt: Shape): Float {
        val centreTol = 0.12f   // 12% of the screen
        val dx = (reference.centreX - attempt.centreX).toDouble()
        val dy = (reference.centreY - attempt.centreY).toDouble()
        val centreDiff = sqrt(dx * dx + dy * dy).toFloat()
        val centreScore = (100f * (1f - centreDiff / centreTol)).coerceIn(0f, 100f)

        fun sizeScore(ref: Float, act: Float): Float {
            val tol = (ref * 0.45f).coerceAtLeast(0.06f)
            return (100f * (1f - abs(act - ref) / tol)).coerceIn(0f, 100f)
        }
        val size = (sizeScore(reference.widthFraction, attempt.widthFraction) +
            sizeScore(reference.heightFraction, attempt.heightFraction)) / 2f

        return centreScore * 0.6f + size * 0.4f
    }

    /** A factor fails only if it scored below its own minimum. */
    private fun status(score: Float, minimum: Float): Factor.Status =
        if (score >= minimum) Factor.Status.PASS else Factor.Status.FAIL

    private fun fmt(v: Float): String =
        if (v.isNaN()) "n/a" else String.format("%.2f", v)
}
