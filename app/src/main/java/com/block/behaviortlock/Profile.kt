package com.block.behaviortlock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Profile.kt — saves and loads the enrolled routine.
//
// Storage note (documented honestly for the project report):
// Templates are written to the app's PRIVATE internal storage. On a non-rooted
// phone no other app can read this directory, because Android sandboxes each
// app. It is NOT encrypted at rest — a rooted attacker with physical access
// could read it. The upgrade path is Android Keystore-backed encryption
// (androidx.security-crypto). We start here because it is far easier for a
// beginner to read and audit, and the threat it does not cover is already
// covered by the device's own disk encryption.
// ---------------------------------------------------------------------------

object ProfileStore {

    private const val FILE = "profile.json"

    /** Everything we persist. */
    data class Profile(
        // The enrolled routine: one continuous recording the user performed.
        // Stored as the raw Trace; the app re-derives the detected events from
        // it every time it loads, so there is only ever ONE source of truth.
        val enrolledTrace: Trace? = null,
        /**
         * Additional recordings of the SAME routine.
         *
         * [enrolledTrace] is always kept as the first take, and is still written
         * to disk, so a profile saved by any version of this app can be read by
         * any other. This list is purely additive: with one entry the behaviour
         * is identical to having no list at all.
         *
         * Why more than one: a single recording is one arbitrary sample of how
         * the user happens to behave on one day. Matching against several means
         * accepting anything close to any of their real samples, instead of
         * demanding they reproduce one take exactly.
         */
        val extraTraces: List<Trace> = emptyList(),
        // A short human description like "Tap, Tap, Draw" for the home screen.
        val enrolledSummary: String = "",
        val lockEnabled: Boolean = false,
        val strongMode: Boolean = false,
        val securityLevel: String = "Standard",
        val attemptsBeforePin: Int = 2,
        val lockDelaySeconds: Int = 0,
        val setupDone: Boolean = false,
        // String mode: the whole routine is one unbroken stroke matched as a
        // single shape, rather than a sequence of separate actions.
        val stringMode: Boolean = false
    ) {
        /** The detected events for the enrolled routine, derived on demand. */
        fun enrolledSession(): DetectedSession =
            enrolledTrace?.let { AutoSegmenter.segment(it, stringMode) } ?: DetectedSession()

        /**
         * Every reference recording, the first take first.
         *
         * An enrolment with a single take returns a list of one, so callers
         * never have to special-case the old format.
         */
        fun allTraces(): List<Trace> =
            listOfNotNull(enrolledTrace) + extraTraces

        /** One session per reference recording. */
        fun allSessions(): List<DetectedSession> =
            allTraces().map { AutoSegmenter.segment(it, stringMode) }
    }

    // -- public API ----------------------------------------------------------

    fun load(context: Context): Profile {
        return try {
            val file = java.io.File(context.filesDir, FILE)
            if (!file.exists()) return Profile()
            parseProfile(JSONObject(file.readText()))
        } catch (t: Throwable) {
            // Never crash on a corrupt profile — fall back to empty.
            Profile()
        }
    }

    fun save(context: Context, profile: Profile) {
        try {
            val file = java.io.File(context.filesDir, FILE)
            file.writeText(serializeProfile(profile).toString())
        } catch (_: Throwable) {
            // Persistence failure should not crash the app.
        }
    }

    // -- serialisation -------------------------------------------------------

    private fun serializeProfile(p: Profile): JSONObject {
        val json = JSONObject()
        // Store the enrolled session as a single Trace. The list of detected
        // events is re-derived from it on load, so it can never go stale.
        if (p.enrolledTrace != null) {
            json.put("enrolledTrace", serializeTrace(p.enrolledTrace))
        }
        // Additional takes are additive; the first take above is always written
        // on its own key, so old files keep working unchanged.
        if (p.extraTraces.isNotEmpty()) {
            val arr = JSONArray()
            for (t in p.extraTraces) arr.put(serializeTrace(t))
            json.put("extraTraces", arr)
        }
        json.put("enrolledSummary", p.enrolledSummary)
        json.put("lockEnabled", p.lockEnabled)
        json.put("strongMode", p.strongMode)
        json.put("securityLevel", p.securityLevel)
        json.put("attemptsBeforePin", p.attemptsBeforePin)
        json.put("lockDelaySeconds", p.lockDelaySeconds)
        json.put("setupDone", p.setupDone)
        json.put("stringMode", p.stringMode)
        return json
    }

    private fun parseProfile(json: JSONObject): Profile = Profile(
        enrolledTrace = json.optJSONObject("enrolledTrace")
            ?.let { parseTrace(it, SegmentKind.DRAW) },
        extraTraces = json.optJSONArray("extraTraces")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { parseTrace(it, SegmentKind.DRAW) }
            }
        } ?: emptyList(),
        enrolledSummary = json.optString("enrolledSummary", ""),
        lockEnabled = json.optBoolean("lockEnabled", false),
        strongMode = json.optBoolean("strongMode", false),
        securityLevel = json.optString("securityLevel", "Standard"),
        attemptsBeforePin = json.optInt("attemptsBeforePin", 2),
        lockDelaySeconds = json.optInt("lockDelaySeconds", 0),
        setupDone = json.optBoolean("setupDone", false),
        stringMode = json.optBoolean("stringMode", false)
    )


    private fun serializeTrace(trace: Trace): JSONObject {
        val json = JSONObject()
        json.put("kind", trace.kind.name)

        val ta = JSONArray()
        for (p in trace.touches) {
            val o = JSONObject()
            o.put("t", p.t); o.put("x", p.x); o.put("y", p.y)
            o.put("f", p.fingers); o.put("p", p.pressure); o.put("ph", p.phase.name)
            ta.put(o)
        }
        json.put("touches", ta)

        val ma = JSONArray()
        for (p in trace.motion) {
            val o = JSONObject()
            o.put("t", p.t)
            o.put("ax", p.accelX); o.put("ay", p.accelY); o.put("az", p.accelZ)
            o.put("gx", p.gyroX); o.put("gy", p.gyroY); o.put("gz", p.gyroZ)
            o.put("pi", p.pitch); o.put("ro", p.roll)
            ma.put(o)
        }
        json.put("motion", ma)
        return json
    }

    private fun parseTrace(json: JSONObject, kind: SegmentKind): Trace {
        val touches = mutableListOf<TouchPoint>()
        val ta = json.optJSONArray("touches") ?: JSONArray()
        for (i in 0 until ta.length()) {
            val o = ta.optJSONObject(i) ?: continue
            touches += TouchPoint(
                t = o.optLong("t"),
                x = o.optDouble("x", 0.0).toFloat(),
                y = o.optDouble("y", 0.0).toFloat(),
                fingers = o.optInt("f", 1),
                pressure = o.optDouble("p", 1.0).toFloat(),
                phase = runCatching { TouchPhase.valueOf(o.optString("ph", "MOVE")) }
                    .getOrDefault(TouchPhase.MOVE)
            )
        }

        val motion = mutableListOf<MotionPoint>()
        val ma = json.optJSONArray("motion") ?: JSONArray()
        for (i in 0 until ma.length()) {
            val o = ma.optJSONObject(i) ?: continue
            motion += MotionPoint(
                t = o.optLong("t"),
                accelX = o.optDouble("ax", 0.0).toFloat(),
                accelY = o.optDouble("ay", 0.0).toFloat(),
                accelZ = o.optDouble("az", 0.0).toFloat(),
                gyroX = o.optDouble("gx", 0.0).toFloat(),
                gyroY = o.optDouble("gy", 0.0).toFloat(),
                gyroZ = o.optDouble("gz", 0.0).toFloat(),
                pitch = o.optDouble("pi", 0.0).toFloat(),
                roll = o.optDouble("ro", 0.0).toFloat()
            )
        }

        return Trace(kind, touches, motion)
    }
}
