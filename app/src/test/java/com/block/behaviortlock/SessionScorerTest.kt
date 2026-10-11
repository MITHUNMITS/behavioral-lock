package com.block.behaviortlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// ---------------------------------------------------------------------------
// Tests for comparing a whole unlock attempt against the enrolled routine.
// ---------------------------------------------------------------------------

class SessionScorerTest {

    /** Build a session: a list of (kind, durationMs, gapBeforeMs). */
    private fun session(vararg parts: Triple<SegmentKind, Long, Long>): DetectedSession {
        val events = mutableListOf<DetectedEvent>()
        for ((kind, durationMs, gapMs) in parts) {
            val steps = (durationMs / 16).coerceAtLeast(2).toInt()
            val touches = mutableListOf<TouchPoint>()
            for (i in 0..steps) {
                val fraction = i.toFloat() / steps
                val x = if (kind == SegmentKind.DRAW)
                    500f + 150f * Math.sin((fraction * Math.PI).toDouble()).toFloat()
                else 500f + (if (kind == SegmentKind.SWIPE) 600f * fraction else 5f * fraction)
                val phase = when (i) {
                    0 -> TouchPhase.DOWN
                    steps -> TouchPhase.UP
                    else -> TouchPhase.MOVE
                }
                touches += TouchPoint(
                    i * 16_000_000L, x, 1000f, 1, 1f, phase
                )
            }
            events += DetectedEvent(kind, touches, emptyList(), gapMs.toFloat())
        }
        return DetectedSession(events, 0f)
    }

    @Test
    fun `identical sessions pass`() {
        val enrolled = session(
            Triple(SegmentKind.TAP, 120L, 0L),
            Triple(SegmentKind.TAP, 130L, 400L),
            Triple(SegmentKind.DRAW, 800L, 500L)
        )
        val attempt = session(
            Triple(SegmentKind.TAP, 125L, 0L),
            Triple(SegmentKind.TAP, 135L, 410L),
            Triple(SegmentKind.DRAW, 820L, 495L)
        )
        val result = SessionScorer.compare(enrolled, attempt)
        assertTrue("expected pass, got ${result.headline} (${result.totalScore})", result.passed)
    }

    @Test
    fun `a different sequence of actions fails`() {
        val enrolled = session(
            Triple(SegmentKind.TAP, 120L, 0L),
            Triple(SegmentKind.DRAW, 800L, 400L)
        )
        // same actions, but in the wrong order
        val attempt = session(
            Triple(SegmentKind.DRAW, 800L, 0L),
            Triple(SegmentKind.TAP, 120L, 400L)
        )
        val result = SessionScorer.compare(enrolled, attempt)
        assertTrue("expected fail", !result.passed)
    }

    @Test
    fun `an incomplete routine does not pass`() {
        val enrolled = session(
            Triple(SegmentKind.TAP, 120L, 0L),
            Triple(SegmentKind.TAP, 120L, 400L),
            Triple(SegmentKind.DRAW, 800L, 500L)
        )
        // only two of the three actions
        val attempt = session(
            Triple(SegmentKind.TAP, 120L, 0L),
            Triple(SegmentKind.TAP, 120L, 400L)
        )
        val result = SessionScorer.compare(enrolled, attempt)
        assertTrue("a prefix must never unlock", !result.passed)
        assertTrue(!result.completed)
    }

    @Test
    fun `missing a whole action fails`() {
        val enrolled = session(
            Triple(SegmentKind.TAP, 120L, 0L),
            Triple(SegmentKind.SWIPE, 300L, 400L)
        )
        val attempt = session(Triple(SegmentKind.TAP, 120L, 0L))
        val result = SessionScorer.compare(enrolled, attempt)
        assertTrue(!result.passed)
    }

    @Test
    fun `a much slower rhythm is penalised`() {
        val enrolled = session(
            Triple(SegmentKind.TAP, 120L, 0L),
            Triple(SegmentKind.TAP, 120L, 300L),
            Triple(SegmentKind.TAP, 120L, 300L)
        )
        // same taps but with a very different pause
        val attempt = session(
            Triple(SegmentKind.TAP, 120L, 0L),
            Triple(SegmentKind.TAP, 120L, 2500L),
            Triple(SegmentKind.TAP, 120L, 2500L)
        )
        val slow = SessionScorer.compare(enrolled, attempt)
        val good = SessionScorer.compare(enrolled, enrolled)
        assertTrue("a very different rhythm should score lower",
            slow.totalScore < good.totalScore)
    }

    @Test
    fun `an empty enrolled session always fails`() {
        val result = SessionScorer.compare(DetectedSession(), session(
            Triple(SegmentKind.TAP, 120L, 0L)
        ))
        assertTrue(!result.passed)
    }
    @Test
    fun `extra actions must NOT unlock - a partial routine is not the routine`() {
        // SECURITY: enrolled "tap, tap, tap", someone performs six taps.
        // Only the first three used to be compared, so this wrongly passed.
        val enrolled = session(
            Triple(SegmentKind.TAP, 120L, 0L),
            Triple(SegmentKind.TAP, 120L, 300L),
            Triple(SegmentKind.TAP, 120L, 300L)
        )
        val attempt = session(
            Triple(SegmentKind.TAP, 120L, 0L),
            Triple(SegmentKind.TAP, 120L, 300L),
            Triple(SegmentKind.TAP, 120L, 300L),
            Triple(SegmentKind.TAP, 120L, 300L),
            Triple(SegmentKind.TAP, 120L, 300L),
            Triple(SegmentKind.TAP, 120L, 300L)
        )
        val result = SessionScorer.compare(enrolled, attempt)
        assertTrue("doing extra actions must never unlock", !result.passed)
        assertTrue("count mismatch must be reported", !result.countOk)
    }

    @Test
    fun `too many actions with the right kinds still fails`() {
        // SECURITY: same kinds, wrong count.
        val enrolled = session(
            Triple(SegmentKind.TAP, 120L, 0L),
            Triple(SegmentKind.DRAW, 800L, 300L)
        )
        val attempt = session(
            Triple(SegmentKind.TAP, 120L, 0L),
            Triple(SegmentKind.DRAW, 800L, 300L),
            Triple(SegmentKind.DRAW, 800L, 300L)
        )
        val result = SessionScorer.compare(enrolled, attempt)
        assertTrue(!result.passed)
        assertTrue(!result.countOk)
    }

    @Test
    fun `a very different pause pattern must fail even when every action matches`() {
        // SECURITY: identical taps, but the rhythm is completely different.
        // Rhythm used to be display-only and could not block an unlock.
        val enrolled = session(
            Triple(SegmentKind.TAP, 120L, 0L),
            Triple(SegmentKind.TAP, 120L, 250L),
            Triple(SegmentKind.TAP, 120L, 250L)
        )
        val attempt = session(
            Triple(SegmentKind.TAP, 120L, 0L),
            Triple(SegmentKind.TAP, 120L, 3800L),
            Triple(SegmentKind.TAP, 120L, 3900L)
        )
        val result = SessionScorer.compare(enrolled, attempt)
        assertTrue("a different rhythm must not unlock", !result.passed)
        assertTrue("rhythm mismatch must be reported", !result.rhythmOk)
    }

    @Test
    fun `headline explains an action count problem`() {
        val enrolled = session(
            Triple(SegmentKind.TAP, 120L, 0L),
            Triple(SegmentKind.TAP, 120L, 300L)
        )
        val attempt = session(
            Triple(SegmentKind.TAP, 120L, 0L),
            Triple(SegmentKind.TAP, 120L, 300L),
            Triple(SegmentKind.TAP, 120L, 300L)
        )
        val result = SessionScorer.compare(enrolled, attempt)
        assertEquals("Wrong number of actions", result.headline)
    }

    // -----------------------------------------------------------------------
    // REAL DEVICE REGRESSION
    //
    // Routine recorded on the POCO M2 Pro (profile.json): nine taps with gaps
    //   238, 306, 1642, 248, 303, 1800, 271, 334 ms
    // i.e. mostly ~250-330 ms, with two long pauses. The user then performs the
    // same nine taps at a steady ~270 ms and the lock refuses to open.
    //
    // That is not the user failing to behave like themselves — it is the
    // scorer demanding a pause pattern to the millisecond, which no human can
    // reproduce twice. The enrolment itself is the proof: the same person
    // produced 248 ms and 1800 ms inside one routine.
    // -----------------------------------------------------------------------

    private fun nineTaps(vararg gaps: Long): DetectedSession {
        val events = gaps.map { gap ->
            val touches = listOf(
                TouchPoint(0L, 500f, 1000f, 1, 1f, TouchPhase.DOWN),
                TouchPoint(16_000_000L, 502f, 1002f, 1, 1f, TouchPhase.UP)
            )
            DetectedEvent(SegmentKind.TAP, touches, emptyList(), gap.toFloat())
        }
        return DetectedSession(events, 0f)
    }

    @Test
    fun `the same routine at a steadier pace must still unlock`() {
        val enrolled = nineTaps(0, 238, 306, 1642, 248, 303, 1800, 271, 334)
        // The user taps nine times, evenly, slightly quicker than the recording.
        val attempt = nineTaps(0, 270, 270, 270, 270, 270, 270, 270, 270)
        val result = SessionScorer.compare(enrolled, attempt)
        assertTrue(
            "nine of the user's own taps must unlock, got '${result.headline}' " +
                "(rhythm=${result.rhythmScore}, count=${result.countOk}, " +
                "sequence=${result.sequenceOk})",
            result.passed
        )
    }

    @Test
    fun `tapping noticeably slower than enrolled still unlocks`() {
        val enrolled = nineTaps(0, 238, 306, 1642, 248, 303, 1800, 271, 334)
        // Same nine taps, but the user is taking their time about it.
        val attempt = nineTaps(0, 320, 320, 320, 320, 320, 320, 320, 320)
        val result = SessionScorer.compare(enrolled, attempt)
        assertTrue(
            "a modestly different tempo must unlock, got '${result.headline}' " +
                "(rhythm=${result.rhythmScore})",
            result.passed
        )
    }

    @Test
    fun `a completely different pace still fails`() {
        val enrolled = nineTaps(0, 238, 306, 1642, 248, 303, 1800, 271, 334)
        // Deliberately slow and machine-like, roughly three times the routine's
        // own pace. This is NOT the owner behaving like themselves.
        val attempt = nineTaps(0, 900, 900, 900, 900, 900, 900, 900, 900)
        val result = SessionScorer.compare(enrolled, attempt)
        assertTrue("a wholesale change of pace must not unlock", !result.passed)
        assertTrue("the rhythm failure must be reported", !result.rhythmOk)
    }

    @Test
    fun `a tap that lingers a little must still match`() {
        // REAL DEVICE REGRESSION.
        // Nine taps enrolled with hold times of 2-26 ms -- the same order as the
        // touchscreen's ~20 ms sampling interval, so that number is hardware
        // noise rather than behaviour. Attempts were refused 7 times out of 9
        // with "too fast or too slow" purely because a tap lasted 40 ms
        // instead of 10 ms. Tap duration must not block a match.
        val enrolled = session(*Array(9) { i ->
            Triple(SegmentKind.TAP, 12L, if (i == 0) 0L else 250L)
        })
        val attempt = session(*Array(9) { i ->
            Triple(SegmentKind.TAP, if (i == 0) 58L else 14L, if (i == 0) 0L else 260L)
        })
        val result = SessionScorer.compare(enrolled, attempt)
        assertTrue(
            "a single slow tap must not fail the routine, got '${result.headline}'",
            result.passed
        )
    }

@Test
    fun `a routine with no measurable pauses must not be blocked on rhythm`() {
        // Every gap is zero, so there is no rhythm to compare. Measuring
        // nothing must never fail a match -- and the tolerance maths must not
        // divide by zero, which would produce NaN and fail everything.
        val enrolled = session(*Array(9) { Triple(SegmentKind.TAP, 12L, 0L) })
        val attempt = session(*Array(9) { i ->
            Triple(SegmentKind.TAP, 14L, if (i == 0) 0L else 240L)
        })
        val result = SessionScorer.compare(enrolled, attempt)
        assertTrue("an unmeasurable rhythm must not block", result.rhythmOk)
        assertTrue("got '${result.headline}'", result.passed)
    }

    // -----------------------------------------------------------------------
// GRIP - how the phone is being HELD, not just what the finger does.
    //
    // The app promised to recognise "how they hold the phone". Attitude was
    // captured all along (pitch/roll on every motion sample) but nothing ever
    // scored it, so holding the phone like the owner does had no effect on
    // whether the phone opened.
    //
    // It is measured over the WHOLE routine, not per action: a tap lasts a few
    // milliseconds while the motion sensor samples about every 20 ms, so there
    // is no attitude data inside an individual tap at all.
    // -----------------------------------------------------------------------

    /** Motion samples holding the phone at a tilt, with a given amount of wobble. */
    private fun heldAt(pitch: Float, roll: Float, wobbleDeg: Float, samples: Int = 40):
        List<MotionPoint> =
        (0 until samples).map { i ->
            val phase = i.toFloat() / samples
            MotionPoint(
                t = i * 20_000_000L,
                accelX = wobbleDeg * 0.1f, accelY = 0f, accelZ = 0f,
                gyroX = 0f, gyroY = 0f, gyroZ = 0f,
                pitch = pitch + wobbleDeg * kotlin.math.sin(phase * 6.28f),
                roll = roll + wobbleDeg * kotlin.math.cos(phase * 6.28f)
            )
        }

    private fun tapsHeldAt(
        pitch: Float, roll: Float, wobbleDeg: Float, gaps: LongArray
    ): DetectedSession {
        val events = gaps.map { gap ->
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
        return DetectedSession(events, 0f, heldAt(pitch, roll, wobbleDeg))
    }

    @Test
    fun `held the same way as enrolment unlocks`() {
        val enrolled = tapsHeldAt(28f, -12f, 1.5f, longArrayOf(0, 250, 250, 250))
        val attempt = tapsHeldAt(31f, -10f, 1.8f, longArrayOf(0, 260, 260, 260))
        val result = SessionScorer.compare(enrolled, attempt)
        assertTrue("got '${result.headline}'", result.passed)
        assertTrue("grip should have been measured", result.gripMeasured)
        assertTrue(result.gripOk)
    }

    @Test
    fun `held at a clearly different angle must fail`() {
        // Same taps, same rhythm, same everything else -- but the phone is
        // tilted the other way, as it would be in the other hand.
        val enrolled = tapsHeldAt(35f, -20f, 1.2f, longArrayOf(0, 250, 250, 250))
        val attempt = tapsHeldAt(-30f, 15f, 1.0f, longArrayOf(0, 255, 255, 255))
        val result = SessionScorer.compare(enrolled, attempt)
        assertTrue("a different hold must not unlock", !result.passed)
        assertTrue("the grip failure must be reported", !result.gripOk)
        assertEquals("Phone held at a different angle", result.headline)
    }

    @Test
    fun `the same angle but a much shakier hold must still unlock`() {
        // DESIGN CHANGE, driven by 25 real unlock attempts on a POCO M2 Pro.
        //
        // Hand tremor measured 0.9-13.7 degrees while the phone was held, and
        // 0.5-3.3 while it was held still for an enrolment. Those ranges
        // overlap almost completely, so steadiness cannot tell the owner from
        // a stranger - it only rejected the owner at random. It is still
        // measured, reported and scored; it just may not block.
        val enrolled = tapsHeldAt(20f, -8f, 1.0f, longArrayOf(0, 250, 250, 250))
        val attempt = tapsHeldAt(20f, -8f, 14f, longArrayOf(0, 250, 250, 250))
        val result = SessionScorer.compare(enrolled, attempt)
        assertTrue("a shakier hold must not block the owner", result.gripOk)
        assertTrue("got '${result.headline}'", result.passed)
    }

    @Test
    fun `a flat attempt against a held routine must fail`() {
    // REAL DEVICE REGRESSION. A routine enrolled while the phone was held in
    // the hand unlocked fine with the phone lying flat on a table. The "flat"
    // rule was applied to both sides, so a flat ATTEMPT was treated as
    // unmeasurable and skipped grip entirely -- the exact opposite of the
    // intent. A flat attempt is a real, measurable observation, and against a
    // held reference it is a genuine difference.
    val enrolled = tapsHeldAt(-21f, 4.3f, 1.5f, longArrayOf(0, 250, 250, 250))
    val attempt = tapsHeldAt(-0.6f, 4.0f, 0.4f, longArrayOf(0, 250, 250, 250))
    val result = SessionScorer.compare(enrolled, attempt)
    assertTrue("the attempt is measurable, so grip must be checked", result.gripMeasured)
    assertTrue("lying flat is not holding it the same way", !result.gripOk)
    assertTrue("got '${result.headline}'", !result.passed)
}

@Test
    fun `a routine enrolled flat on a desk never blocks on grip`() {
        // REAL DEVICE REGRESSION. The phone was enrolled lying flat on a desk
        // (tilt jitter 0.5 degrees, the physical minimum) and then every unlock
        // attempt made with the phone in a hand failed, because hand tremor is
        // always higher than that of a phone resting on a table. A desk
        // recording is not a description of how anyone holds a phone, so it
        // must not be treated as one.
        val enrolled = tapsHeldAt(0.5f, 4.7f, 0.5f, longArrayOf(0, 250, 250, 250))
        val attempt = tapsHeldAt(-4.6f, 5.7f, 3.1f, longArrayOf(0, 250, 250, 250))
        val result = SessionScorer.compare(enrolled, attempt)
        assertTrue("a desk enrolment is not a hold reference", !result.gripMeasured)
        assertTrue("got '${result.headline}'", result.passed)
    }

    @Test
    fun `grip is skipped when the phone lay flat and cannot be measured`() {
        // Measuring nothing must never fail a match, exactly as pressure is
        // skipped when the hardware reports none.
        val enrolled = tapsHeldAt(0f, 0f, 0.2f, longArrayOf(0, 250, 250, 250))
        val attempt = tapsHeldAt(0f, 0f, 0.2f, longArrayOf(0, 250, 250, 250))
        val result = SessionScorer.compare(enrolled, attempt)
        assertTrue("flat-on-table is not measurable, so must be skipped", !result.gripMeasured)
        assertTrue("a skipped factor must never block", result.gripOk)
        assertTrue("got '${result.headline}'", result.passed)
    }

    @Test
    fun `grip is skipped when the recording has no motion at all`() {
        val enrolled = nineTaps(0, 250, 250, 250, 250, 250, 250, 250)
        val attempt = nineTaps(0, 255, 255, 255, 255, 255, 255, 255)
        val result = SessionScorer.compare(enrolled, attempt)
        assertTrue("no motion samples means no grip to check", !result.gripMeasured)
        assertTrue("got '${result.headline}'", result.passed)
    }

    @Test
    fun `gyro is measured and reported`() {
        // The gyroscope is registered and read, and must actually reach the
        // shape. It is deliberately NOT a pass condition of its own -- tilt
        // jitter already covers "the phone was being moved about", and a second
        // blocking test on the same behaviour only rejects the owner more.
        val still = DetectedSession(
            nineTaps(0, 250, 250, 250, 250, 250, 250, 250).events,
            0f,
            (0 until 30).map {
                MotionPoint(it * 20_000_000L, 0f, 0f, 0f, 0.02f, 0.02f, 0.02f, 20f, -5f)
            }
        )
        val gripped = still.copy(
            motion = (0 until 30).map {
                MotionPoint(it * 20_000_000L, 0f, 0f, 0f, 0.9f, 0.9f, 0.9f, 20f, -5f)
            }
        )
        val a = still.grip.meanGyro
        val b = gripped.grip.meanGyro
        assertTrue("gyro must be measured, got $a", !a.isNaN())
        assertTrue("a turned phone must read a higher rate than a still one",
            b > a)
    }

    @Test
    fun `a hold that is far too short must still fail`() {
        // The counterpart to the test above: duration DOES matter for a hold,
        // because holding longer is the gesture itself.
        val enrolled = session(Triple(SegmentKind.HOLD, 2000L, 0L))
        val attempt = session(Triple(SegmentKind.HOLD, 300L, 0L))
        val result = SessionScorer.compare(enrolled, attempt)
        assertTrue("a much shorter hold is a different gesture", !result.passed)
    }

    @Test
    fun `two unrepeatable long pauses no longer veto the whole routine`() {
        // The routine's own long pauses are the gaps the owner is least able to
        // repeat. They must still count for something, but they must not be
        // able to fail the routine on their own.
        val enrolled = nineTaps(0, 300, 1600, 300, 300, 1800, 300, 300, 300)
        // Everything right except the two long pauses, which came out short.
        val attempt = nineTaps(0, 300, 320, 300, 300, 330, 300, 300, 300)
        val result = SessionScorer.compare(enrolled, attempt)
        assertTrue(
            "one or two missed pauses must not lock the owner out, " +
                "got '${result.headline}' (rhythm=${result.rhythmScore})",
            result.passed
        )
    }
}
