package com.block.behaviortlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------
// QA pass over auto-segmentation: shake detection and string mode.
//
// These are the two paths that had no coverage at all, and both had real
// bugs: SHAKE was defined but never produced by the segmenter, and string mode
// was not threaded through to the scorer.
// ---------------------------------------------------------------------------

class SegmenterQATest {

    // -- helpers --------------------------------------------------------------

    private fun tap(startNs: Long, durationMs: Long = 100): List<TouchPoint> {
        val steps = (durationMs / 16).toInt().coerceAtLeast(2)
        return (0..steps).map { i ->
            TouchPoint(
                startNs + i * 16_000_000L, 500f, 900f, 1, 1f,
                if (i == 0) TouchPhase.DOWN
                else if (i == steps) TouchPhase.UP else TouchPhase.MOVE
            )
        }
    }

    /** A motion sample that is either still or shaking hard. */
    private fun motion(startNs: Long, count: Int, shaking: Boolean, spacingNs: Long = 20_000_000L):
        List<MotionPoint> = (0 until count).map { i ->
        val v = if (shaking) 12f else 0.2f
        MotionPoint(
            t = startNs + i * spacingNs,
            accelX = v, accelY = if (shaking) -v else v, accelZ = 0f,
            gyroX = if (shaking) 3f else 0.01f,
            gyroY = 0f, gyroZ = 0f,
            pitch = 0f, roll = 0f
        )
    }

    // -- shake detection ------------------------------------------------------

    @Test
    fun `a vigorous shake with no touch is detected`() {
        val m = motion(0L, count = 40, shaking = true)
        val trace = Trace(SegmentKind.DRAW, emptyList(), m)
        val session = AutoSegmenter.segment(trace)

        assertEquals("a shake should produce one event", 1, session.events.size)
        assertEquals(SegmentKind.SHAKE, session.events[0].kind)
    }

    @Test
    fun `a still phone produces no events`() {
        val m = motion(0L, count = 60, shaking = false)
        val session = AutoSegmenter.segment(Trace(SegmentKind.DRAW, emptyList(), m))
        assertTrue("a still phone must not produce actions", session.events.isEmpty())
    }

    @Test
    fun `a short bump is not treated as a shake`() {
        // Only 3 samples of violence, far below the minimum duration.
        val m = motion(0L, count = 3, shaking = true)
        val session = AutoSegmenter.segment(Trace(SegmentKind.DRAW, emptyList(), m))
        assertTrue("a brief bump must not count as a shake", session.events.isEmpty())
    }

    @Test
    fun `moving the phone while touching does not count as a shake`() {
        // Vigorous motion, but a finger is on the screen throughout.
        val m = motion(0L, count = 40, shaking = true)
        val session = AutoSegmenter.segment(
            Trace(SegmentKind.DRAW, tap(0L, durationMs = 800), m)
        )
        assertTrue(
            "shake detection must ignore motion that happens during a touch",
            session.events.none { it.kind == SegmentKind.SHAKE }
        )
    }

    @Test
    fun `a tap then a shake appear in time order`() {
        val touches = tap(0L)
        // Shake starts well after the tap finished.
        val m = motion(900_000_000L, count = 40, shaking = true)
        val session = AutoSegmenter.segment(Trace(SegmentKind.DRAW, touches, m))

        assertEquals(2, session.events.size)
        assertEquals(SegmentKind.TAP, session.events[0].kind)
        assertEquals(SegmentKind.SHAKE, session.events[1].kind)
        // The gap before the shake must be positive and sensible.
        assertTrue(
            "gap before shake should be positive, was ${session.events[1].gapBeforeMs}",
            session.events[1].gapBeforeMs > 0f
        )
    }

    // -- string mode ----------------------------------------------------------

    @Test
    fun `string mode returns the whole recording as one event`() {
        val pts = (0 until 120).map { i ->
            val a = i / 120.0 * 2 * Math.PI
            TouchPoint(
                i * 16_000_000L,
                (500f + 300f * cos(a)).toFloat(),
                (900f + 300f * sin(a)).toFloat(),
                1, 1f,
                if (i == 0) TouchPhase.DOWN else TouchPhase.MOVE
            )
        }
        val session = AutoSegmenter.segment(Trace(SegmentKind.DRAW, pts, emptyList()), stringMode = true)

        assertEquals("string mode must not split", 1, session.events.size)
        assertEquals(SegmentKind.STRING, session.events[0].kind)
    }

    @Test
    fun `the same recording splits differently without string mode`() {
        // Three separate taps.
        val touches = tap(0L) + tap(500_000_000L) + tap(1_000_000_000L)
        val normal = AutoSegmenter.segment(Trace(SegmentKind.DRAW, touches, emptyList()))
        val asString = AutoSegmenter.segment(
            Trace(SegmentKind.DRAW, touches, emptyList()), stringMode = true
        )
        assertEquals(3, normal.events.size)
        assertEquals(1, asString.events.size)
    }

    @Test
    fun `a string mode routine matches itself`() {
        val pts = (0 until 120).map { i ->
            val a = i / 120.0 * 2 * Math.PI
            TouchPoint(
                i * 16_000_000L,
                (500f + 300f * cos(a)).toFloat(),
                (900f + 300f * sin(a)).toFloat(),
                1, 1f,
                if (i == 0) TouchPhase.DOWN else TouchPhase.MOVE
            )
        }
        val enrolled = AutoSegmenter.segment(Trace(SegmentKind.DRAW, pts, emptyList()), stringMode = true)
        val result = SessionScorer.compare(enrolled, enrolled)
        assertTrue("an identical string must unlock, got ${result.headline}", result.passed)
    }

    @Test
    fun `an empty recording produces nothing`() {
        val session = AutoSegmenter.segment(Trace(SegmentKind.DRAW, emptyList(), emptyList()))
        assertTrue(session.isEmpty)
        assertTrue(!SessionScorer.compare(session, session).passed)
    }

    @Test
    fun `a recording with motion only and no touch still segments`() {
        val m = motion(0L, 40, shaking = true)
        val session = AutoSegmenter.segment(Trace(SegmentKind.DRAW, emptyList(), m))
        assertTrue("motion-only recordings must not be dropped", !session.isEmpty)
    }
}