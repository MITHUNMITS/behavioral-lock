package com.block.behaviortlock

// ---------------------------------------------------------------------------
// MultiTakeScorer.kt — match an attempt against SEVERAL recordings of the same
// routine, instead of only one.
//
// Why this exists
// ---------------
// One enrolment is one arbitrary sample of how the user happened to behave on
// one day. Demanding they reproduce it exactly is what caused the repeated
// "sometimes it works, sometimes it doesn't" behaviour in testing, and the
// cure was always to widen a tolerance until the owner stopped being rejected.
//
// Recording the routine three times and accepting anything close to ANY of the
// takes does the same job from the other direction: instead of guessing how
// much a person varies, it measures their actual range and accepts inside it.
//
// The honest cost
// --------------
// This genuinely lowers the bar. An intruder now needs to match one of three
// references rather than one of one. They still do not know any of them, so it
// is not a break, but it IS a real reduction in margin and it grows with the
// number of takes. That is the same trade as widening a tolerance - only here
// it is based on the user's own measurements rather than on a guess.
//
// Both outcomes are logged for every attempt (ANY-of-N, and how many takes
// matched), so moving to a stricter "2 of 3" rule later becomes a decision
// made from data rather than from taste.
// ---------------------------------------------------------------------------

object MultiTakeScorer {

    /**
     * Compare one attempt against every reference, and keep the best result.
     *
     * "Best" is judged by whether the routine would have been accepted, first;
     * only then by the score. A take that fails on one factor at 95% must never
     * be chosen over one that passes every factor at 61%.
     */
    fun bestOf(
        references: List<DetectedSession>,
        attempt: DetectedSession
    ): MatchOutcome {
        if (references.isEmpty()) {
            val empty = SessionScorer.compare(DetectedSession(), attempt)
            return MatchOutcome(
                best = empty, bestIndex = 0, results = listOf(empty), passedCount = 0
            )
        }

        val results = references.map { SessionScorer.compare(it, attempt) }
        val best = results.minWith(
            // Fewer = better: passes rank above failures, then higher score wins.
            compareBy<SessionResult> { if (it.passed) 0 else 1 }
                .thenByDescending { it.totalScore }
        )

        // How many takes the attempt satisfied, for measuring a stricter rule
        // later without changing anything else.
        val passed = results.count { it.passed }

        return MatchOutcome(best = best, bestIndex = results.indexOf(best), results = results, passedCount = passed)
    }

    /**
     * The result of matching against several takes.
     */
    data class MatchOutcome(
        /** The best result, and the one to report to the user. */
        val best: SessionResult,
        /** Which take produced it, for display. */
        val bestIndex: Int,
        /** Every take's result, for diagnostics. */
        val results: List<SessionResult>,
        /** How many takes the attempt satisfied. */
        val passedCount: Int
    ) {
        val passed: Boolean get() = best.passed
        val totalScore: Float get() = best.totalScore
        val headline: String get() = best.headline
    }
}