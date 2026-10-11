package com.block.behaviortlock

// ---------------------------------------------------------------------------
// RoutineScorer.kt — scores a whole routine attempt, segment by segment.
// ---------------------------------------------------------------------------

object RoutineScorer {

    /**
     * Score a full unlock attempt against the enrolled routine.
     *
     * An attempt is a list of traces in the same order as the routine's
     * segments. A segment passes only if none of its factors failed.
     * The whole routine passes only if every segment passes.
     */
    fun score(routine: Routine, attempts: List<Trace>): RoutineResult {
        if (routine.isEmpty) {
            return RoutineResult(emptyList(), 0f, false)
        }
        if (attempts.size != routine.segments.size) {
            // Mismatched attempt: fail cleanly rather than crash.
            val results = routine.segments.map { seg ->
                SegmentResult(seg.kind, listOf(
                    Factor("Input", 0f, Factor.Status.FAIL, "Missing segment input")
                ), 0f, false)
            }
            return RoutineResult(results, 0f, false)
        }

        val results = routine.segments.mapIndexed { index, segment ->
            Scorer.score(segment.kind, segment.templates, attempts[index])
        }
        val total = results.map { it.total }.average().toFloat()
        val passed = results.all { it.passed }
        return RoutineResult(results, total, passed)
    }
}