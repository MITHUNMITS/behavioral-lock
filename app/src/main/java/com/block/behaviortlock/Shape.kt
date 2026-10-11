package com.block.behaviortlock

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Shape.kt — turns a recorded Trace into a fixed-size summary we can compare.
//
// The idea is deliberately simple so it can be explained in one sentence:
//   "Shrink every gesture down to 64 dots and measure how far apart the dots are."
//
// We do NOT compare raw traces. Raw traces never line up: a fast swipe and a
// slow swipe produce completely different point timings. Shrinking to a fixed
// number of dots removes timing and scale, and we score speed separately.
// ---------------------------------------------------------------------------

/** How many dots every shape is reduced to. */
const val SHAPE_POINTS = 64

/** A gesture reduced to a comparable form. */
data class Shape(
    /** 64 evenly spaced (x,y) points, normalised to the unit square. */
    val dots: List<Pair<Float, Float>>,
    // Where the shape was drawn, as a fraction of the screen (0 = left/top
    // edge, 1 = right/bottom edge). Keeping this stops the same shape drawn
    // somewhere else from matching.
    val centreX: Float = 0.5f,
    val centreY: Float = 0.5f,
    // How big the shape was, as a fraction of the screen. A small M and a
    // huge M are not the same gesture.
    val widthFraction: Float = 0f,
    val heightFraction: Float = 0f,
    /** Duration of the gesture in milliseconds. */
    val durationMs: Float,
    /** Mean gap between touch samples in milliseconds (rhythm). */
    val meanGapMs: Float,
    /** Standard deviation of the gaps — how consistent the rhythm is. */
    val gapDeviationMs: Float,
    /** Peak speed in screen-widths per second. */
    val peakSpeed: Float,
    /** Mean pressure, or NaN when the phone reports none. */
    val meanPressure: Float,
    /** Peak pressure, or NaN when the phone reports none. */
    val peakPressure: Float,
    /** Most fingers used at once. */
    val maxFingers: Int,
    /** Mean motion energy (m/s^2) — how much the phone moved. */
    val motionEnergy: Float,
    /** Total change in device attitude in degrees — how much it was rotated. */
    val rotationDeg: Float,
    /** Root-mean-square acceleration during the gesture — hand steadiness. */
    val steadiness: Float,
    /**
     * Average tilt of the device in degrees — how the phone was being HELD.
     *
     * This is the difference between "the phone moved while you did that" and
     * "you were holding the phone at that angle while you did that". Someone
     * unlocking a phone holds it in a habitual way; a stranger reaching for it
     * does not, and that habit is part of the behaviour.
     */
    val meanPitch: Float = Float.NaN,
    val meanRoll: Float = Float.NaN,
    /**
     * How much the tilt wobbled, in degrees — steadiness of the HOLD.
     * Large means the phone was being moved around; small means it was held
     * steady, which is what a deliberate unlock looks like.
     */
    val tiltJitterDeg: Float = Float.NaN,
    /**
     * Mean rotation rate in rad/s — how fast the phone was being turned.
     *
     * This is the gyroscope's contribution. It is a different measurement from
     * [tiltJitterDeg] (which is derived from the gravity vector): tilt jitter
     * says "the phone is not pointing where it was", this says "the phone is
     * actively being turned right now". Reported so the hold can be judged on
     * both, but deliberately not a pass condition of its own — it largely
     * repeats what tilt jitter already says, and adding a second blocking test
     * on the same behaviour only makes the lock reject its owner more often.
     */
    val meanGyro: Float = Float.NaN
) {
    /**
     * True when this recording carries a usable reference for how the phone is
     * held.
     *
     * A phone lying flat on a desk reports pitch and roll near zero. That is
     * not a weak measurement, it is a *meaningless* one: it says nothing about
     * how a person holds a phone, and a desk recording cannot be reproduced by
     * anyone holding the device. Treating "flat" as measurable produced a
     * deadlock — enrolled flat (tilt jitter 0.5°), then any attempt made with
     * the phone in a hand (jitter 3–13°) failed on steadiness every time.
     *
     * So a routine is only grip-checked if it was enrolled with the phone
     * genuinely tilted, i.e. actually being held.
     */
    val hasGrip: Boolean
        get() = hasAttitude && !isFlat

    /**
     * True when the phone reported attitude at all.
     *
     * This is the ONLY condition that means "cannot be measured". A phone lying
     * flat DOES have a perfectly good measurement — pitch and roll near zero —
     * and that measurement is meaningful: it says the phone is lying on a
     * table rather than being held.
     */
    val hasAttitude: Boolean
        get() = !meanPitch.isNaN() && !meanRoll.isNaN() && !tiltJitterDeg.isNaN()

    /**
     * True when the phone is lying flat rather than being held.
     *
     * Only the ENROLMENT uses this. A routine recorded flat on a desk cannot
     * describe how a person holds a phone, and demanding that the owner also
     * unlock lying flat would mean putting the phone on a table to open it. So
     * a flat enrolment means "no hold reference", and grip is skipped.
     *
     * A flat ATTEMPT is different: it is a real observation, and against a
     * routine that was enrolled while held it is exactly the difference that
     * should be refused.
     */
    val isFlat: Boolean
        get() = abs(meanPitch) <= 10f && abs(meanRoll) <= 10f

    /** True when this phone actually reports real pressure values.
     *
     * A phone with no force touch reports a constant 1.0. A phone with real
     * force touch reports values that VARY across the touch and often exceed
     * 1.0. So we treat pressure as available only when we see variation or a
     * peak above the nominal 1.0 baseline.
     */
    val hasPressure: Boolean
        get() {
            if (meanPressure.isNaN() || peakPressure.isNaN()) return false
            // Available if the peak exceeds the 1.0 baseline, or if the average
            // is meaningfully below it (some phones report 0..1 scaled values).
            return peakPressure > 1.05f || meanPressure < 0.95f
        }
}

object ShapeBuilder {

    /** Reduce a trace to its comparable [Shape]. */
    fun build(trace: Trace): Shape {
        val touches = trace.touches
        val motion = trace.motion

        val raw = touches.map { it.x to it.y }
        val box = boundingBox(raw)

        return Shape(
            dots = normalise(resample(raw)),
            centreX = box.centreX,
            centreY = box.centreY,
            widthFraction = box.width,
            heightFraction = box.height,
            durationMs = trace.durationNs / 1_000_000f,
            meanGapMs = meanGap(touches),
            gapDeviationMs = gapDeviation(touches),
            peakSpeed = peakSpeed(touches),
            meanPressure = meanPressure(touches),
            peakPressure = peakPressure(touches),
            maxFingers = trace.maxFingers,
            motionEnergy = motionEnergy(motion),
            rotationDeg = rotationDeg(motion),
            steadiness = steadiness(motion),
            meanPitch = meanPitch(motion),
            meanRoll = meanRoll(motion),
            tiltJitterDeg = tiltJitter(motion),
            meanGyro = meanGyro(motion)
        )
    }

    // -- geometry ------------------------------------------------------------

    /**
     * Centre and size of a drawing, as fractions of a 1080x2400 screen.
     *
     * We record where the shape was drawn and how large it was, because
     * normalising the dots alone throws that information away. Without this,
     * an "M" drawn anywhere on the screen at any size reduces to identical
     * dots and matches anything else of the same outline.
     */
    private fun boundingBox(points: List<Pair<Float, Float>>): BoxInfo {
        if (points.isEmpty()) return BoxInfo(0.5f, 0.5f, 0f, 0f)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for ((x, y) in points) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        return BoxInfo(
            centreX = ((minX + maxX) / 2f / SCREEN_W).coerceIn(0f, 1f),
            centreY = ((minY + maxY) / 2f / SCREEN_H).coerceIn(0f, 1f),
            width = ((maxX - minX) / SCREEN_W).coerceIn(0f, 1f),
            height = ((maxY - minY) / SCREEN_H).coerceIn(0f, 1f)
        )
    }

    /** Nominal screen size used to turn pixels into screen fractions. */
    const val SCREEN_W = 1080f
    const val SCREEN_H = 2400f

    data class BoxInfo(
        val centreX: Float,
        val centreY: Float,
        val width: Float,
        val height: Float
    )

    // -- shape ---------------------------------------------------------------

    /** Evenly resample a polyline to [SHAPE_POINTS] points by arc length. */
    private fun resample(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (points.isEmpty()) return List(SHAPE_POINTS) { 0.5f to 0.5f }
        if (points.size == 1) return List(SHAPE_POINTS) { points[0] }

        // Total length of the path.
        var total = 0f
        for (i in 1 until points.size) {
            total += distance(points[i - 1], points[i])
        }
        if (total <= 0.0001f) return List(SHAPE_POINTS) { points[0] }

        val step = total / (SHAPE_POINTS - 1)
        val out = ArrayList<Pair<Float, Float>>(SHAPE_POINTS)
        out += points[0]

        var travelled = 0f
        var carried = points[0]
        outer@ for (i in 1 until points.size) {
            val segment = distance(points[i - 1], points[i])
            // Emit every dot that falls inside this segment, carrying the
            // leftover distance forward to the next one.
            var remaining = segment
            var from = points[i - 1]
            while (travelled + remaining >= step && out.size < SHAPE_POINTS) {
                val need = step - travelled
                val t = if (remaining <= 0f) 0f else need / remaining
                val point = lerp(from, points[i], t)
                out += point
                travelled = 0f
                from = point
                remaining -= need
            }
            travelled += remaining
            carried = points[i]
            if (out.size >= SHAPE_POINTS) break@outer
        }
        // Pad or trim to exactly SHAPE_POINTS.
        while (out.size < SHAPE_POINTS) out += points.last()
        return out.take(SHAPE_POINTS)
    }

    /** Scale points into the unit square (0..1) using the bounding box. */
    private fun normalise(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (points.isEmpty()) return List(SHAPE_POINTS) { 0.5f to 0.5f }
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for ((x, y) in points) {
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        val w = (maxX - minX).coerceAtLeast(1f)
        val h = (maxY - minY).coerceAtLeast(1f)
        return points.map { (x, y) -> ((x - minX) / w) to ((y - minY) / h) }
    }

    // -- rhythm / timing -----------------------------------------------------

    private fun meanGap(touches: List<TouchPoint>): Float {
        if (touches.size < 2) return 0f
        var sum = 0L
        for (i in 1 until touches.size) sum += (touches[i].t - touches[i - 1].t)
        return sum / (touches.size - 1) / 1_000_000f
    }

    private fun gapDeviation(touches: List<TouchPoint>): Float {
        if (touches.size < 3) return 0f
        val gaps = FloatArray(touches.size - 1)
        for (i in 1 until touches.size) {
            gaps[i - 1] = (touches[i].t - touches[i - 1].t) / 1_000_000f
        }
        val mean = gaps.average().toFloat()
        var sum = 0.0
        for (g in gaps) { val d = g - mean; sum += d * d }
        return sqrt(sum / gaps.size).toFloat()
    }

    private fun peakSpeed(touches: List<TouchPoint>): Float {
        if (touches.size < 2) return 0f
        // Nominal screen width in pixels, used to express speed as
        // "screen-widths per second" so it is resolution independent.
        val screenWidth = 1080f
        var peak = 0f
        for (i in 1 until touches.size) {
            val dt = (touches[i].t - touches[i - 1].t) / 1_000_000f
            if (dt <= 0f) continue
            val d = distance(touches[i - 1].x to touches[i - 1].y,
                             touches[i].x to touches[i].y)
            val speed = (d / screenWidth) / (dt / 1000f)
            if (speed > peak) peak = speed
        }
        return peak
    }

    // -- pressure ------------------------------------------------------------

    private fun meanPressure(touches: List<TouchPoint>): Float {
        if (touches.isEmpty()) return Float.NaN
        return touches.map { it.pressure }.average().toFloat()
    }

    private fun peakPressure(touches: List<TouchPoint>): Float {
        if (touches.isEmpty()) return Float.NaN
        return touches.maxOf { it.pressure }
    }

    // -- motion --------------------------------------------------------------

    private fun motionEnergy(motion: List<MotionPoint>): Float {
        if (motion.isEmpty()) return 0f
        var sum = 0.0
        for (m in motion) {
            val mag = sqrt(
                (m.accelX * m.accelX + m.accelY * m.accelY + m.accelZ * m.accelZ).toDouble()
            )
            sum += mag * mag
        }
        return sqrt(sum / motion.size).toFloat()
    }

    private fun steadiness(motion: List<MotionPoint>): Float {
        if (motion.size < 2) return 0f
        val mean = motionEnergy(motion)
        var sum = 0.0
        for (m in motion) {
            val mag = sqrt(
                (m.accelX * m.accelX + m.accelY * m.accelY + m.accelZ * m.accelZ).toDouble()
            )
            val d = mag - mean
            sum += d * d
        }
        return sqrt(sum / motion.size).toFloat()
    }

    private fun rotationDeg(motion: List<MotionPoint>): Float {
        if (motion.size < 2) return 0f
        var dPitch = 0f; var dRoll = 0f
        for (i in 1 until motion.size) {
            dPitch += motion[i].pitch - motion[i - 1].pitch
            dRoll += motion[i].roll - motion[i - 1].roll
        }
        return sqrt((dPitch * dPitch + dRoll * dRoll).toDouble()).toFloat()
    }

    // -- grip (how the phone is being held) -----------------------------------

    /**
     * Average tilt of the device.
     *
     * Returns NaN when there are no attitude samples at all, which is how the
     * scorer knows to skip the factor instead of scoring nonsense. Deliberate
     * hands do not move smoothly -- the measured samples are a few degrees
     * either side of true even when someone is holding perfectly still -- so the
     * circular mean is used rather than an arithmetic average.
     */
    private fun meanTilt(motion: List<MotionPoint>, pitch: Boolean): Float {
        if (motion.isEmpty()) return Float.NaN
        var sinSum = 0.0
        var cosSum = 0.0
        for (m in motion) {
            val deg = if (pitch) m.pitch else m.roll
            if (deg.isNaN()) continue
            val rad = Math.toRadians(deg.toDouble())
            sinSum += Math.sin(rad)
            cosSum += Math.cos(rad)
        }
        if (sinSum == 0.0 && cosSum == 0.0) return Float.NaN
        return Math.toDegrees(Math.atan2(sinSum, cosSum)).toFloat()
    }

    private fun meanPitch(motion: List<MotionPoint>): Float = meanTilt(motion, true)

    private fun meanRoll(motion: List<MotionPoint>): Float = meanTilt(motion, false)

    /**
     * How far the tilt wandered from its average, in degrees.
     *
     * This is the steadiness of the HOLD. Someone concentrating on a deliberate
     * unlock holds the phone still; someone poking at it moves it around.
     */
    private fun tiltJitter(motion: List<MotionPoint>): Float {
        if (motion.size < 2) return Float.NaN
        val meanP = meanTilt(motion, true)
        val meanR = meanTilt(motion, false)
        if (meanP.isNaN() || meanR.isNaN()) return Float.NaN
        var sum = 0.0
        for (m in motion) {
            if (m.pitch.isNaN() || m.roll.isNaN()) continue
            val dPitch = m.pitch - meanP
            val dRoll = m.roll - meanR
            sum += (dPitch * dPitch + dRoll * dRoll).toDouble()
        }
        return sqrt(sum / motion.size).toFloat()
    }

    /** Mean rotation rate in rad/s. NaN when the phone reported no gyro. */
    private fun meanGyro(motion: List<MotionPoint>): Float {
        if (motion.isEmpty()) return Float.NaN
        var sum = 0.0
        var n = 0
        for (m in motion) {
            val mag = sqrt(
                (m.gyroX * m.gyroX + m.gyroY * m.gyroY + m.gyroZ * m.gyroZ).toDouble()
            )
            if (mag.isNaN()) continue
            sum += mag
            n++
        }
        return if (n == 0) Float.NaN else (sum / n).toFloat()
    }

    // -- helpers -------------------------------------------------------------

    private fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
        val dx = a.first - b.first
        val dy = a.second - b.second
        return sqrt(dx * dx + dy * dy)
    }

    private fun lerp(a: Pair<Float, Float>, b: Pair<Float, Float>, t: Float) =
        (a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t)
}