package com.block.behaviortlock

import kotlin.math.abs
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// AutoSegmenter.kt — works out what the user did, without them declaring it.
//
// The user records one long, continuous session (tap-tap-draw-shake, all in a
// row). This file splits that recording back into individual actions.
//
// The method is deliberately simple rules rather than a model:
//
//   * A contact that lasted a short time and barely moved  -> TAP
//   * A contact that moved quickly in a straight line       -> SWIPE
//   * A contact that moved a lot, or curved                 -> DRAW
//   * A contact that stayed still for a while               -> HOLD
//   * Strong motion with no touch at all                     -> SHAKE
//   * The idle time between one contact and the next        -> the "gap"
//
// The gaps end up being the strongest signal, because people pause
// consistently between actions even when their fingers do not land in exactly
// the same place.
// ---------------------------------------------------------------------------

/** One detected action inside a recorded session. */
data class DetectedEvent(
    val kind: SegmentKind,
    val touches: List<TouchPoint>,
    val motion: List<MotionPoint>,
    /** Idle time in milliseconds before this event started. */
    val gapBeforeMs: Float
) {
    val durationMs: Float
        get() = if (touches.isEmpty()) 0f
        else (touches.last().t - touches.first().t) / 1_000_000f

    /** Total distance the finger travelled, in pixels. */
    val pathLength: Float
        get() {
            var total = 0f
            for (i in 1 until touches.size) {
                total += dist(touches[i - 1], touches[i])
            }
            return total
        }

    /** Straight-line distance from the first touch to the last. */
    val spanLength: Float
        get() = if (touches.size < 2) 0f else dist(touches.first(), touches.last())

    /**
     * 1.0 = perfectly straight movement, lower = curved.
     * A straight swipe has path length close to its span; a drawn circle does not.
     */
    val straightness: Float
        get() = if (pathLength <= 1f) 1f else (spanLength / pathLength).coerceIn(0f, 1f)

    val maxFingers: Int get() = touches.maxOfOrNull { it.fingers } ?: 0

    val shape: Shape get() = ShapeBuilder.build(Trace(kind, touches, motion))

    /** A short label used by the on-screen event markers. */
    val label: String get() = kind.label

    private fun dist(a: TouchPoint, b: TouchPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}

/** A whole recorded session, already split into events. */
data class DetectedSession(
    val events: List<DetectedEvent> = emptyList(),
    val totalDurationMs: Float = 0f,
    /**
     * Every motion sample in the whole recording.
     *
     * A single tap lasts a few milliseconds and the motion sensor samples every
     * ~20 ms, so per-action motion is almost always empty. How the phone was
     * HELD can only be judged across the whole routine, which is what this is
     * for.
     */
    val motion: List<MotionPoint> = emptyList()
) {
    val isEmpty: Boolean get() = events.isEmpty()

    /** How the phone was held over the whole routine, for grip matching. */
    val grip: Shape
        get() = ShapeBuilder.build(Trace(SegmentKind.TAP, emptyList(), motion))

    /** How many events of each kind, for the summary shown to the user. */
    fun summary(): String {
        if (events.isEmpty()) return "nothing recorded"
        return events.joinToString(", ") { it.label }
    }
}

object AutoSegmenter {

    // --- tuning ---
    // A contact shorter than this that ends where it started is a tap.
    private const val TAP_MAX_MS = 260f
    // How far the finger may end up from where it started and still be a tap,
    // in pixels. This is measured as a straight-line span, NOT as total path
    // travelled, so a finger that wobbles while resting stays a tap.
    private const val TAP_MAX_SPAN_PX = 72f
    // A contact longer than this with no movement is a hold.
    private const val HOLD_MIN_MS = 600f
    // Straightness above this means "a swipe, not a drawing".
    private const val SWIPE_STRAIGHTNESS = 0.82f
    // A swipe is also short — a long straight drag is a DRAW.
    private const val SWIPE_MAX_MS = 700f
    // A swipe has to go somewhere. Without this, drifting on the spot and
    // coming back can look "straight" and get called a swipe.
    private const val SWIPE_MIN_SPAN_PX = 180f
    // Linear acceleration above this (m/s^2) counts as vigorous movement.
    // Energy above which a motion sample counts as vigorous. Raised from 5.0 after
    // a real device showed that picking the phone up off a desk clears 5.0 easily.
    private const val SHAKE_ENERGY = 8.0f
    // Rotation rate above this (rad/s) also counts as vigorous movement.
    private const val SHAKE_GYRO = 1.2f
    // A shake must last at least this long to be deliberate rather than a bump.
    private const val SHAKE_MIN_MS = 200f
    // A shake must actually go back and forth at least three times. Lifting a
    // phone is one movement in one direction and never reaches this.
    private const val SHAKE_MIN_REVERSALS = 3
    // Acceleration below this (m/s^2) is treated as zero when counting
    // reversals, so sensor jitter is not mistaken for a direction change.
    private const val SHAKE_DEADZONE = 0.5f
    // A gap longer than this is ignored when scoring rhythm (someone paused).
    private const val GAP_MAX_MS = 4000f

    /**
     * Split a recorded session into detected events.
     *
     * In string mode we do NOT split at all: the whole recording is treated as
     * one continuous stroke and matched as a single shape, like a pattern lock.
     * That is what a user wants when they trace an unbroken line from start to
     * finish rather than performing separate actions.
     */
    fun segment(trace: Trace, stringMode: Boolean = false): DetectedSession {
        if (stringMode) {
            if (trace.touches.isEmpty()) {
                return DetectedSession(emptyList(), trace.durationNs / 1_000_000f, trace.motion)
            }
            return DetectedSession(
                listOf(DetectedEvent(SegmentKind.STRING, trace.touches, trace.motion, 0f)),
                trace.durationNs / 1_000_000f,
                trace.motion
            )
        }
        val contacts = splitIntoContacts(trace.touches)
        // NOTE: we deliberately do NOT return early when there are no contacts.
        // A recording with no finger on the screen at all may still contain a
        // shake, and returning here used to discard shake-only routines.

        val events = mutableListOf<DetectedEvent>()
        var previousEndNs = trace.touches.firstOrNull()?.t ?: 0L

        for (contact in contacts) {
            val kind = classify(contact)
            val gapMs = (contact.first().t - previousEndNs) / 1_000_000f
            // Pull the motion samples that happened during this contact, so a
            // HOLD or DRAW can still be judged on how the phone was moving.
            val motion = motionDuring(trace.motion, contact.first().t, contact.last().t)
            events += DetectedEvent(kind, contact, motion, gapMs.coerceIn(0f, GAP_MAX_MS))
            previousEndNs = contact.last().t
        }

        // Motion-only actions: shaking the phone with no finger on the screen.
        // These are detected from the accelerometer, not from touch, so they
        // are found separately and then merged back into time order.
        val shakes = detectShakes(trace.motion, contacts)
        if (shakes.isNotEmpty()) {
            events += shakes
            events.sortBy { startNsOf(it) }
            // Re-measure every gap against the event that actually came before.
            var prevEnd = events.firstOrNull()?.let { startNsOf(it) } ?: 0L
            val reTimed = events.map { event ->
                val start = startNsOf(event)
                val gap = ((start - prevEnd) / 1_000_000f).coerceIn(0f, GAP_MAX_MS)
                prevEnd = startNsOf(event) + durationNsOf(event)
                DetectedEvent(event.kind, event.touches, event.motion, gap)
            }
            return DetectedSession(reTimed, trace.durationNs / 1_000_000f, trace.motion)
        }

        return DetectedSession(events, trace.durationNs / 1_000_000f, trace.motion)
    }

    private fun startNsOf(event: DetectedEvent): Long =
        event.touches.firstOrNull()?.t ?: event.motion.firstOrNull()?.t ?: 0L

    private fun durationNsOf(event: DetectedEvent): Long =
        ((event.touches.lastOrNull()?.t ?: event.motion.lastOrNull()?.t ?: 0L) - startNsOf(event))

    /**
     * Find periods where the phone was shaken hard while nobody was touching
     * the screen.
     *
     * A shake is a run of motion samples well above the resting magnitude that
     * last long enough to be deliberate. We require it to be touch-free so that
     * moving the phone while drawing is not counted as a separate shake.
     */
    fun detectShakes(
        motion: List<MotionPoint>,
        contacts: List<List<TouchPoint>>
    ): List<DetectedEvent> {
        if (motion.size < 8) return emptyList()

        val out = mutableListOf<DetectedEvent>()
        var i = 0
        while (i < motion.size) {
            if (!isVigorous(motion[i])) { i++; continue }

            // Extend the run while the motion stays vigorous.
            var j = i
            var peakAccel = 0f
            var peakGyro = 0f
            while (j < motion.size && isVigorous(motion[j])) {
                val a = magnitude(motion[j])
                if (a > peakAccel) peakAccel = a
                val g = gyroMagnitude(motion[j])
                if (g > peakGyro) peakGyro = g
                j++
            }

            val window = motion.subList(i, j)
            val durationMs = (window.last().t - window.first().t) / 1_000_000f

            // Too short to be a deliberate shake — just a bump while typing.
            //
            // The reversal test matters as much as the energy. Picking a phone
            // up off a desk produces a long, strong, one-directional movement
            // that satisfies both of the other conditions, and it was being
            // detected as the FIRST ACTION of the routine. Every unlock attempt
            // then failed with "expected Tap, got Shake" — but only when the
            // user was holding the phone, never when it lay flat on the desk.
            // A shake goes one way and then comes back; lifting does not.
            //
            // Strength is judged on EITHER sensor. A shake driven by the wrist
            // rotating the phone produces large rotation rates with only modest
            // linear acceleration, and checking acceleration alone meant such a
            // shake could never be detected no matter how vigorous it was.
            val strongEnough = peakAccel >= SHAKE_ENERGY || peakGyro >= SHAKE_GYRO
            if (durationMs >= SHAKE_MIN_MS &&
                strongEnough &&
                directionReversals(window) >= SHAKE_MIN_REVERSALS
            ) {
                val touchesDuring = contacts.any { contact ->
                    contact.first().t <= window.last().t && contact.last().t >= window.first().t
                }
                if (!touchesDuring) {
                    out += DetectedEvent(
                        SegmentKind.SHAKE,
                        emptyList(),
                        window.toList(),
                        0f
                    )
                }
            }
            i = j
        }
        return out
    }

    /**
     * How many times the movement reversed direction.
     *
     * A deliberate shake is oscillatory: the phone goes one way, comes back,
     * goes the other way. Lifting a phone off a desk is a single movement in
     * one direction, which can be just as strong and just as long. Energy and
     * duration cannot tell those apart; oscillation can.
     *
     * The axis is chosen PER SAMPLE, from whichever one moved most at that
     * moment, rather than fixed for the whole window. A real shake travels
     * across all three axes at once and a fixed axis only ever saw part of it,
     * which made genuine shakes score too few reversals to be detected.
     */
    private fun directionReversals(window: List<MotionPoint>): Int {
        if (window.size < 2) return 0

        var reversals = 0
        var lastSign = 0
        for (m in window) {
            // Ignore samples inside the noise band around zero, otherwise sensor
            // jitter reads as a direction change.
            val ax = abs(m.accelX); val ay = abs(m.accelY); val az = abs(m.accelZ)
            if (ax < SHAKE_DEADZONE && ay < SHAKE_DEADZONE && az < SHAKE_DEADZONE) continue

            val v = when {
                ax >= ay && ax >= az -> m.accelX
                ay >= az -> m.accelY
                else -> m.accelZ
            }
            val sign = if (v > 0f) 1 else -1
            if (lastSign != 0 && sign != lastSign) reversals++
            lastSign = sign
        }
        return reversals
    }

    /** Rotation rate magnitude in rad/s. */
    private fun gyroMagnitude(m: MotionPoint): Float = kotlin.math.sqrt(
        (m.gyroX * m.gyroX + m.gyroY * m.gyroY + m.gyroZ * m.gyroZ).toDouble()
    ).toFloat()

    /** Is this sample moving much more than a phone lying still? */
    private fun isVigorous(m: MotionPoint): Boolean {
        val a = magnitude(m)
        val g = gyroMagnitude(m)
        return a >= SHAKE_ENERGY || g >= SHAKE_GYRO
    }

    /** Linear acceleration magnitude. */
    private fun magnitude(m: MotionPoint): Float = kotlin.math.sqrt(
        (m.accelX * m.accelX + m.accelY * m.accelY + m.accelZ * m.accelZ).toDouble()
    ).toFloat()

    /**
     * Group touch samples into contacts: one group per finger-down to
     * finger-up. Phase markers are used when available; if a trace was
     * recorded without them we fall back to "a new finger count starts a new
     * contact".
     */
    fun splitIntoContacts(touches: List<TouchPoint>): List<List<TouchPoint>> {
        if (touches.isEmpty()) return emptyList()
        val contacts = mutableListOf<List<TouchPoint>>()
        var current = mutableListOf<TouchPoint>()

        for (p in touches) {
            when (p.phase) {
                TouchPhase.DOWN -> {
                    if (current.isNotEmpty()) contacts += current
                    current = mutableListOf(p)
                }
                TouchPhase.UP -> {
                    current += p
                    if (current.isNotEmpty()) contacts += current
                    current = mutableListOf()
                }
                TouchPhase.MOVE -> current += p
            }
        }
        if (current.isNotEmpty()) contacts += current
        return contacts.filter { it.isNotEmpty() }
    }

    /** Decide what kind of action one contact was. */
    fun classify(contact: List<TouchPoint>): SegmentKind {
        if (contact.isEmpty()) return SegmentKind.TAP

        val durationMs = (contact.last().t - contact.first().t) / 1_000_000f

        // Two different measurements, and they must not be confused:
        //
        //   travel - how far the finger went in total. A finger that wobbles
        //             while resting accumulates a lot of this.
        //   span   - how far it ended up from where it started. A real swipe
        //             moves the finger somewhere; a tap does not.
        //
        // Deciding "was this a tap?" on travel meant that merely resting a
        // finger on the screen for half a second turned a tap into a swipe.
        var travel = 0f
        for (i in 1 until contact.size) {
            val dx = contact[i].x - contact[i - 1].x
            val dy = contact[i].y - contact[i - 1].y
            travel += sqrt(dx * dx + dy * dy)
        }
        val spanX = contact.last().x - contact.first().x
        val spanY = contact.last().y - contact.first().y
        val span = sqrt(spanX * spanX + spanY * spanY)

        // Ended up near where it started, and quick -> a tap.
        if (durationMs <= TAP_MAX_MS && span <= TAP_MAX_SPAN_PX) {
            return SegmentKind.TAP
        }
        // Stayed still for a while -> a hold.
        if (durationMs >= HOLD_MIN_MS && span <= TAP_MAX_SPAN_PX * 1.5f) {
            return SegmentKind.HOLD
        }
        // Quick, straight, and actually went somewhere -> a swipe.
        if (durationMs <= SWIPE_MAX_MS &&
            span >= SWIPE_MIN_SPAN_PX &&
            shapeStraightness(contact) >= SWIPE_STRAIGHTNESS
        ) {
            return SegmentKind.SWIPE
        }
        // Everything else is a drawing.
        return SegmentKind.DRAW
    }

    /** Straight-line distance divided by the distance actually travelled. */
    private fun shapeStraightness(contact: List<TouchPoint>): Float {
        if (contact.size < 2) return 1f
        var path = 0f
        for (i in 1 until contact.size) {
            val dx = contact[i].x - contact[i - 1].x
            val dy = contact[i].y - contact[i - 1].y
            path += sqrt(dx * dx + dy * dy)
        }
        if (path <= 1f) return 1f
        val sx = contact.last().x - contact.first().x
        val sy = contact.last().y - contact.first().y
        val span = sqrt(sx * sx + sy * sy)
        return (span / path).coerceIn(0f, 1f)
    }

    /** Motion samples that fall inside a time window. */
    private fun motionDuring(
        motion: List<MotionPoint>,
        startNs: Long,
        endNs: Long
    ): List<MotionPoint> = motion.filter { it.t in startNs..endNs }

    /**
     * Did the phone get shaken at some point? Used as an extra signal when the
     * user has a routine that includes shaking.
     */
    fun hasShake(motion: List<MotionPoint>): Boolean {
        if (motion.size < 3) return false
        for (m in motion) {
            val mag = sqrt(
                (m.accelX * m.accelX + m.accelY * m.accelY + m.accelZ * m.accelZ).toDouble()
            )
            if (mag > SHAKE_ENERGY) return true
        }
        return false
    }
}