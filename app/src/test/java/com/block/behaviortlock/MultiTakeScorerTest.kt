package com.block.behaviortlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// ---------------------------------------------------------------------------
// Multi-take enrolment: match an attempt against several recordings of the same
// routine.
//
// The point of these tests is twofold. First, that more takes genuinely widens
// what the lock accepts -- that is the whole feature. Second, and more
// important, that ONE take behaves EXACTLY as it always did, because that is
// what keeps every existing enrolment and every existing test valid.
// ---------------------------------------------------------------------------

class MultiTakeScorerTest {

    /** A session of taps with the given gaps, holding the phone at a tilt. */
    private fun take(gaps: LongArray, pitch: Float = 25f, wobble: Float = 1.5f): DetectedSession {
        val events = gaps.mapIndexed { i, gap ->
            DetectedEvent(
                SegmentKind.TAP,
                listOf(
                    TouchPoint(0L, 500f, 1000f, 1, 1f, TouchPhase.DOWN),
                    TouchPoint(16_000_000L, 502f, 1002f, 1, 1f, TouchPhase.UP)
                ),
                emptyList(),
                gap.toFloat()
            )
        }
        val motion = (0 until 40).map { i ->
            MotionPoint(
                t = i * 20_000_000L, accelX = 0f, accelY = 0f, accelZ = 0f,
                gyroX = 0f, gyroY = 0f, gyroZ = 0f,
                pitch = pitch + wobble * kotlin.math.sin(i.toFloat()),
                roll = -5f
            )
        }
        return DetectedSession(events, 0f, motion)
    }

    private fun rapid() = take(longArrayOf(0, 150, 150, 150, 150, 150, 150, 150, 150))
    private fun steady() = take(longArrayOf(0, 260, 260, 260, 260, 260, 260, 260, 260))
    private fun lazy() = take(longArrayOf(0, 420, 420, 420, 420, 420, 420, 420, 420))

    @Test
    fun `a single take behaves exactly like the old single-reference scoring`() {
        // THE COMPATIBILITY GUARANTEE. With one reference, bestOf must return
        // precisely what SessionScorer.compare would have returned, so no
        // existing enrolment changes behaviour at all.
        val only = steady()
        val direct = SessionScorer.compare(only, lazy())
        val viaMulti = MultiTakeScorer.bestOf(listOf(only), lazy()).best

        assertEquals(direct.passed, viaMulti.passed)
        assertEquals(direct.totalScore, viaMulti.totalScore, 0.0001f)
        assertEquals(direct.headline, viaMulti.headline)
        assertEquals(direct.rhythmScore, viaMulti.rhythmScore, 0.0001f)
    }

    @Test
    fun `a single take that passes still passes`() {
        val result = MultiTakeScorer.bestOf(listOf(steady()), steady())
        assertTrue(result.passed)
    }

    @Test
    fun `more takes widen what the lock accepts`() {
        // The core claim. An attempt that matches none of a single reference is
        // accepted once references covering its tempo exist.
        val attempt = lazy()

        val one = MultiTakeScorer.bestOf(listOf(steady()), attempt)
        val three = MultiTakeScorer.bestOf(listOf(steady(), rapid(), take(
            longArrayOf(0, 400, 400, 400, 400, 400, 400, 400, 400)
        )), attempt)

        assertTrue("this test is only meaningful if one take refuses",
            !one.best.passed)
        assertTrue("more takes must accept more", three.passed)
    }

    @Test
    fun `a passing take wins even when a failing take scores higher`() {
        // The best result must rank PASSING above FAILING, not just by score.
        // Otherwise a take that fails the exact count rule could outrank one
        // that genuinely matches.
        //
        // Three references against a nine-action steady attempt:
        //   [0] right count, hopeless rhythm -> fails, low score
        //   [1] ten actions                -> fails the count rule, HIGH score
        //   [2] exact match                -> PASSES
        // Take 1 outscores take 0, and is closer to passing than take 0 is. The
        // correct answer is still take 2.
        val attempt = steady()
        val wrongRhythm = take(longArrayOf(0, 900, 900, 900, 900, 900, 900, 900, 900))
        val tooMany = DetectedSession(
            steady().events + DetectedEvent(
                SegmentKind.TAP,
                listOf(
                    TouchPoint(0L, 500f, 1000f, 1, 1f, TouchPhase.DOWN),
                    TouchPoint(16_000_000L, 502f, 1002f, 1, 1f, TouchPhase.UP)
                ),
                emptyList(),
                260f
            ),
            0f,
            steady().motion
        )

        val outcome = MultiTakeScorer.bestOf(
            listOf(wrongRhythm, tooMany, steady()),
            attempt
        )

        // Precondition: the failing take really does score higher than the
        // passing one's nearest failure, so this test is testing the ranking.
        val failingCountScore = outcome.results[1].totalScore
        val failingRhythmScore = outcome.results[0].totalScore
        assertTrue(
            "test setup: the count failure should outscore the rhythm failure " +
                "($failingCountScore vs $failingRhythmScore)",
            failingCountScore > failingRhythmScore
        )

        assertTrue("the passing take must be chosen", outcome.best.passed)
        assertEquals(
            "and it must be the third take, not the higher-scoring failure",
            2, outcome.bestIndex
        )
    }

    @Test
    fun `the report says how many takes matched`() {
        // This number is what makes a stricter "2 of 3" rule a decision made
        // from data rather than from taste, so it has to be right.
        val outcome = MultiTakeScorer.bestOf(listOf(steady(), rapid()), steady())
        assertEquals(1, outcome.passedCount)
        assertEquals(2, outcome.results.size)
    }

    @Test
    fun `an attempt matching several takes reports it`() {
        val outcome = MultiTakeScorer.bestOf(listOf(steady(), rapid(), steady()), steady())
        assertTrue("steady should match the two steady takes", outcome.passedCount >= 2)
    }

    @Test
    fun `no references at all never unlocks`() {
        val outcome = MultiTakeScorer.bestOf(emptyList(), steady())
        assertTrue(!outcome.passed)
    }

    @Test
    fun `extra actions still never unlock, even with many takes`() {
        // SECURITY. More takes must not become a way to slip past the exact
        // count rule -- every take has the same number of actions as the
        // routine, so none of them can match an over-long attempt.
        val tooMany = DetectedSession(
            (steady().events + DetectedEvent(
                SegmentKind.TAP,
                listOf(
                    TouchPoint(0L, 500f, 1000f, 1, 1f, TouchPhase.DOWN),
                    TouchPoint(16_000_000L, 502f, 1002f, 1, 1f, TouchPhase.UP)
                ),
                emptyList(),
                260f
            )).toList(),
            0f,
            steady().motion
        )
        val outcome = MultiTakeScorer.bestOf(
            listOf(steady(), rapid(), lazy()),
            tooMany
        )
        assertTrue("doing extra actions must never unlock", !outcome.passed)
        assertTrue(!outcome.best.countOk)
    }

    @Test
    fun `a genuinely wrong routine still fails with three takes`() {
        // More takes must not turn into a weaker lock for a stranger. An
        // attempt with the right count and tempo but the wrong hold must be
        // refused however many references exist.
        val references = listOf(
            take(longArrayOf(0, 260, 260, 260, 260, 260, 260, 260, 260), pitch = 40f),
            take(longArrayOf(0, 265, 265, 265, 265, 265, 265, 265, 265), pitch = 41f),
            take(longArrayOf(0, 255, 255, 255, 255, 255, 255, 255, 255), pitch = 39f)
        )
        // Held almost flat: a different hold, correctly refused.
        val wrongHold = take(
            longArrayOf(0, 260, 260, 260, 260, 260, 260, 260, 260), pitch = 2f
        )
        val outcome = MultiTakeScorer.bestOf(references, wrongHold)
        assertTrue("a different hold must still be refused", !outcome.passed)
        assertTrue("and reported as such", !outcome.best.gripOk)
    }
}