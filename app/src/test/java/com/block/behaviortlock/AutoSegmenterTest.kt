package com.block.behaviortlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// ---------------------------------------------------------------------------
// Tests for auto-segmentation: can the app tell what the user did without
// them declaring it? These run on the JVM with no device needed.
// ---------------------------------------------------------------------------

class AutoSegmenterTest {

    /** Build one contact (finger down -> moves -> up). */
    private fun contact(
        startNs: Long,
        durationMs: Long,
        travelPx: Float,
        curved: Boolean = false
    ): List<TouchPoint> {
        val steps = (durationMs / 16).coerceAtLeast(2).toInt()
        val out = mutableListOf<TouchPoint>()
        for (i in 0..steps) {
            val fraction = i.toFloat() / steps
            val t = startNs + i * 16_000_000L
            val x = if (curved) 500f + 200f * Math.sin((fraction * Math.PI).toDouble()).toFloat()
                    else 500f + travelPx * fraction
            val y = 1000f + (if (curved) 200f * fraction else 0f)
            val phase = when (i) {
                0 -> TouchPhase.DOWN
                steps -> TouchPhase.UP
                else -> TouchPhase.MOVE
            }
            out += TouchPoint(t, x, y, 1, 1f, phase)
        }
        return out
    }

    @Test
    fun `a finger that wobbles but returns is still a tap`() {
        // REAL DEVICE REGRESSION. On a POCO M2 Pro a routine of nine plain taps
        // was classified once as "swipe, tap, tap, ..." because the first
        // contact travelled more than the old 48px path limit while the finger
        // merely rested. Path length is the wrong measurement: wobbling adds up
        // distance without going anywhere. What matters is how far the finger
        // ended up from where it started.
        val wobble = mutableListOf<TouchPoint>()
        var x = 500f
        val xs = floatArrayOf(500f, 530f, 498f, 522f, 505f, 515f, 502f, 500f)
        for (i in xs.indices) {
            val phase = when (i) {
                0 -> TouchPhase.DOWN
                xs.size - 1 -> TouchPhase.UP
                else -> TouchPhase.MOVE
            }
            wobble += TouchPoint(i * 16_000_000L, xs[i], 1000f, 1, 1f, phase)
        }
        assertEquals(SegmentKind.TAP, AutoSegmenter.classify(wobble))
    }

    @Test
    fun `a genuine swipe is still a swipe`() {
        val c = contact(0L, 300, 600f)
        assertEquals(SegmentKind.SWIPE, AutoSegmenter.classify(c))
    }

    @Test
    fun `a quick barely-moving contact is a tap`() {
        val c = contact(0L, 120, 10f)
        assertEquals(SegmentKind.TAP, AutoSegmenter.classify(c))
    }

    @Test
    fun `a fast straight movement is a swipe`() {
        // 300ms, travelling 600px in a straight line
        val c = contact(0L, 300, 600f)
        assertEquals(SegmentKind.SWIPE, AutoSegmenter.classify(c))
    }

    @Test
    fun `a curved movement is a draw`() {
        val c = contact(0L, 800, 400f, curved = true)
        assertEquals(SegmentKind.DRAW, AutoSegmenter.classify(c))
    }

    @Test
    fun `a long still contact is a hold`() {
        val c = contact(0L, 1200, 3f)
        assertEquals(SegmentKind.HOLD, AutoSegmenter.classify(c))
    }

    @Test
    fun `contacts are split at down and up markers`() {
        val touches = contact(0L, 100, 5f) +
                // a gap
                contact(500_000_000L, 100, 5f)
        val contacts = AutoSegmenter.splitIntoContacts(touches)
        assertEquals(2, contacts.size)
    }

    @Test
    fun `gaps between actions are measured`() {
        // tap at 0ms, tap starting at 600ms -> second gap ~500ms
        val touches = contact(0L, 100, 5f) + contact(600_000_000L, 100, 5f)
        val session = AutoSegmenter.segment(Trace(SegmentKind.DRAW, touches, emptyList()))
        assertEquals(2, session.events.size)
        assertTrue("gap should be about 500ms, was ${session.events[1].gapBeforeMs}",
            session.events[1].gapBeforeMs in 400f..600f)
    }

    @Test
    fun `a full session of taps draw and swipe is segmented in order`() {
        val touches = contact(0L, 120, 8f) +              // tap
                contact(400_000_000L, 120, 8f) +           // tap
                contact(900_000_000L, 800, 400f, true) +   // draw
                contact(2_000_000_000L, 300, 700f)         // swipe
        val session = AutoSegmenter.segment(Trace(SegmentKind.DRAW, touches, emptyList()))
        assertEquals(4, session.events.size)
        assertEquals(SegmentKind.TAP, session.events[0].kind)
        assertEquals(SegmentKind.TAP, session.events[1].kind)
        assertEquals(SegmentKind.DRAW, session.events[2].kind)
        assertEquals(SegmentKind.SWIPE, session.events[3].kind)
    }
}