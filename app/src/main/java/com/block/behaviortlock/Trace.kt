package com.block.behaviortlock

// ---------------------------------------------------------------------------
// Trace.kt — what a recorded gesture looks like.
//
// Everything the app measures ends up in one flat class. Touch data and motion
// data are two parallel lists on the SAME timeline, which is what lets us
// compare them (e.g. "did the phone move when the finger moved?").
// ---------------------------------------------------------------------------

/** The kinds of segment a routine is built from. */
enum class SegmentKind(val label: String, val hint: String) {
    TAP("Tap", "Tap the screen a set number of times, in a steady rhythm."),
    HOLD("Hold", "Press and hold the screen. You can rotate the phone while holding."),
    SWIPE("Swipe", "Drag quickly in one direction."),
    DRAW("Draw", "Draw any shape: a circle, a cross, or your signature."),
    SHAKE("Shake", "Shake the phone. Touch is not required for this one."),
    STRING("String", "One unbroken stroke, like a pattern lock. Matched as a single shape.");

    companion object {
        fun fromName(name: String?): SegmentKind =
            entries.firstOrNull { it.name == name } ?: TAP
    }
}

/**
 * Where a touch sample sits inside one contact (one finger-down to finger-up).
 *
 * This is what lets the app split a long, continuous recording into separate
 * actions without the user having to declare them.
 */
enum class TouchPhase { DOWN, MOVE, UP }

/** One touch sample. Times are nanoseconds on the shared boot-relative clock. */
data class TouchPoint(
    val t: Long,          // nanoseconds since capture start
    val x: Float,         // screen pixels
    val y: Float,
    val fingers: Int,     // how many fingers were down at this moment
    val pressure: Float,  // 1.0 on phones that do not report real pressure
    val phase: TouchPhase = TouchPhase.MOVE
)

/** One motion sample, already reduced to what we actually score. */
data class MotionPoint(
    val t: Long,          // nanoseconds since capture start
    val accelX: Float,    // linear acceleration (gravity removed), m/s^2
    val accelY: Float,
    val accelZ: Float,
    val gyroX: Float,     // rotation rate, rad/s
    val gyroY: Float,
    val gyroZ: Float,
    val pitch: Float,     // device attitude in degrees (from the gravity vector)
    val roll: Float
)

/** A recorded gesture: the touch stream plus the motion stream, in sync. */
data class Trace(
    val kind: SegmentKind,
    val touches: List<TouchPoint> = emptyList(),
    val motion: List<MotionPoint> = emptyList()
) {
    /** Wall-clock duration of the capture, in nanoseconds. */
    val durationNs: Long
        get() {
            val end = touches.lastOrNull()?.t ?: motion.lastOrNull()?.t ?: 0L
            val start = touches.firstOrNull()?.t ?: motion.firstOrNull()?.t ?: 0L
            return end - start
        }

    val isEmpty: Boolean get() = touches.isEmpty() && motion.isEmpty()

    /** Highest number of fingers seen at once. 0 for motion-only segments. */
    val maxFingers: Int get() = touches.maxOfOrNull { it.fingers } ?: 0

    companion object {
        fun empty(kind: SegmentKind = SegmentKind.TAP) = Trace(kind)
    }
}

/** A segment in the user's routine: what it is, plus how many recordings we have. */
data class Segment(
    val kind: SegmentKind,
    val parameter: Int = 0,          // TAP count, SWIPE direction code, HOLD ms/1000
    val templates: List<Trace> = emptyList()
) {
    val summary: String
        get() = when (kind) {
            SegmentKind.TAP -> if (parameter > 0) "Tap x$parameter" else "Tap"
            SegmentKind.HOLD -> if (parameter > 0) "Hold ${parameter}s" else "Hold"
            SegmentKind.SWIPE -> when (parameter) {
                0 -> "Swipe up"; 1 -> "Swipe down"
                2 -> "Swipe left"; else -> "Swipe right"
            }
            SegmentKind.DRAW -> "Draw shape"
            SegmentKind.SHAKE -> "Shake"
            SegmentKind.STRING -> "String"
        }
}

/** The full routine: an ordered list of segments. */
data class Routine(
    val segments: List<Segment> = emptyList()
) {
    val isEmpty: Boolean get() = segments.isEmpty()

    val summary: String
        get() = if (segments.isEmpty()) "No routine yet"
        else segments.joinToString("  ->  ") { it.summary }
}