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

    /**
     * Motion samples that are either still, shaking hard, or being LIFTED.
     *
     * A real shake oscillates. The previous version of this fixture used one
     * constant value, which is not a shake at all -- it is exactly what happens
     * when someone picks a phone up off a desk, and it was letting a
     * non-shake pass as a shake.
     */
    private fun motion(startNs: Long, count: Int, shaking: Boolean, spacingNs: Long = 20_000_000L):
        List<MotionPoint> = (0 until count).map { i ->
        // Square-ish oscillation: +12, +12, +12, -12, -12, -12, ... A deliberate
        // shake changes direction several times a second.
        val v = when {
            !shaking -> 0.2f
            i % 6 < 3 -> 12f
            else -> -12f
        }
        MotionPoint(
            t = startNs + i * spacingNs,
            accelX = v, accelY = -v, accelZ = 0f,
            gyroX = if (shaking) 3f else 0.01f,
            gyroY = 0f, gyroZ = 0f,
            pitch = 0f, roll = 0f
        )
    }

    /**
     * A sustained, strong, ONE-DIRECTION movement: someone picking the phone up
     * off a desk and holding it. Same energy and duration as a shake, but it
     * never reverses direction.
     */
    private fun lifting(startNs: Long, count: Int, spacingNs: Long = 20_000_000L):
        List<MotionPoint> = (0 until count).map { i ->
        MotionPoint(
            t = startNs + i * spacingNs,
            accelX = 11f, accelY = 3f, accelZ = 0f,
            gyroX = 0.4f, gyroY = 0.1f, gyroZ = 0f,
            pitch = 0f, roll = 0f
        )
    }

    @Test
    fun `picking the phone up off a desk is NOT a shake`() {
        // REAL DEVICE REGRESSION. A routine enrolled with the phone flat on a
        // desk failed every time the user lifted the phone to unlock with it,
        // and worked every time it was left lying flat. The lift was long and
        // strong enough to pass the old energy and duration checks, so it was
        // segmented as a SHAKE and the sequence became "Shake, Tap, Tap...".
        // A lift goes one way. A shake goes back and forth.
        val m = lifting(0L, count = 40)
        val session = AutoSegmenter.segment(Trace(SegmentKind.DRAW, emptyList(), m))
        assertTrue(
            "lifting the phone must not be segmented as a shake, got " +
                session.events.joinToString { it.kind.name },
            session.events.none { it.kind == SegmentKind.SHAKE }
        )
    }

    @Test
    fun `a wrist-driven shake is detected even with weak linear acceleration`() {
        // REAL DEVICE BUG. "Shake not working."
        //
    // Strength was judged on linear acceleration ALONE, while a run of the
    // window was extended by EITHER sensor. So a shake driven by the wrist
    // rotating the phone -- high rotation rate, modest acceleration -- could
    // extend the window but could never satisfy the strength test. No amount
    // of vigour could ever make such a shake detectable.
    //
    // The oscillation here travels across all three axes, which is what a real
    // shake does and what a single fixed axis used to miss.
    val m = (0 until 40).map { i ->
        val v = if (i % 5 < 3) 3.5f else -3.5f   // below SHAKE_ENERGY on purpose
        MotionPoint(
            t = i * 20_000_000L,
            accelX = v, accelY = v * 0.8f, accelZ = v * 0.5f,
            gyroX = if (i % 5 < 3) 2.5f else -2.5f,
            gyroY = 0.4f, gyroZ = -0.3f,
            pitch = 0f, roll = 0f
        )
    }
    val session = AutoSegmenter.segment(Trace(SegmentKind.DRAW, emptyList(), m))
    assertTrue(
        "a wrist-driven shake must be detected, got " +
            session.events.joinToString { it.kind.name },
        session.events.any { it.kind == SegmentKind.SHAKE }
    )
}

@Test
    fun `a real shake is still detected after the phone is picked up`() {
        // The fix must not break the feature: an actual shake still counts.
        val lift = lifting(0L, count = 12)
        val shake = motion(12 * 20_000_000L, count = 40, shaking = true)
        val session = AutoSegmenter.segment(
            Trace(SegmentKind.DRAW, emptyList(), lift + shake)
        )
        assertEquals(
            "one shake should still be detected, got " +
                session.events.joinToString { it.kind.name },
            1,
            session.events.count { it.kind == SegmentKind.SHAKE }
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