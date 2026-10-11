package com.block.behaviortlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// ---------------------------------------------------------------------------
// Tests for the pure-Kotlin matching logic. These run on the JVM with no
// device or emulator, which makes iterating on scoring fast.
// ---------------------------------------------------------------------------

class ScorerTest {

    /** Build a synthetic tap trace with a given number of taps and gap. */
    private fun tapTrace(count: Int, gapMs: Long): Trace {
        val touches = mutableListOf<TouchPoint>()
        for (i in 0 until count) {
            val t = i * gapMs * 1_000_000L
            touches += TouchPoint(t, 500f, 800f, 1, 1f)
        }
        return Trace(SegmentKind.TAP, touches, emptyList())
    }

    @Test
    fun `identical tap rhythms score highly and pass`() {
        val template = tapTrace(5, 300)
        val attempt = tapTrace(5, 305)   // nearly identical rhythm

        val result = Scorer.score(SegmentKind.TAP, listOf(template), attempt)
        assertTrue("expected PASS but factors were ${result.factors}", result.passed)
        assertTrue("expected a high total, got ${result.total}", result.total > 70f)
    }

    @Test
    fun `very different tap counts fail`() {
        val template = tapTrace(5, 300)
        val attempt = tapTrace(2, 900)   // fewer taps, much slower

        val result = Scorer.score(SegmentKind.TAP, listOf(template), attempt)
        assertTrue("expected FAIL", !result.passed)
    }

    @Test
    fun `identical shapes get a high shape score`() {
        val dots = (0 until SHAPE_POINTS).map { i ->
            val x = (i.toFloat() / SHAPE_POINTS)
            val y = kotlin.math.sin(i.toDouble()).toFloat() * 0.5f + 0.5f
            x to y
        }
        val score = Scorer.shapeSimilarity(dots, dots)
        assertEquals("identical shapes should score ~100", 100f, score, 1f)
    }

    @Test
    fun `identical shape at a different start point still scores high`() {
        val dots = (0 until SHAPE_POINTS).map { i ->
            val x = (i.toFloat() / SHAPE_POINTS)
            val y = kotlin.math.cos(i.toDouble()).toFloat() * 0.5f + 0.5f
            x to y
        }
        val rotated = dots.drop(20) + dots.take(20)
        val score = Scorer.shapeSimilarity(dots, rotated)
        assertTrue("rotated start should still score high, got $score", score > 70f)
    }

    @Test
    fun `completely different shapes score low`() {
        val horizontal = (0 until SHAPE_POINTS).map { i -> (i.toFloat() / SHAPE_POINTS) to 0.5f }
        val vertical = (0 until SHAPE_POINTS).map { i -> 0.5f to (i.toFloat() / SHAPE_POINTS) }
        val score = Scorer.shapeSimilarity(horizontal, vertical)
        assertTrue("different shapes should score low, got $score", score < 50f)
    }

    @Test
    fun `no templates always fails`() {
        val result = Scorer.score(SegmentKind.TAP, emptyList(), tapTrace(3, 200))
        assertTrue(!result.passed)
    }
}