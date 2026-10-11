package com.block.behaviortlock

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

// ---------------------------------------------------------------------------
// AppViewModel.kt — holds the app's state: the one enrolled recording, whether
// the lock is on, and the result of the most recent unlock attempt.
//
// The model is deliberately simple now: instead of a list of named segments,
// there is ONE enrolled session that the user recorded in a single go. The app
// works out what was in it automatically.
// ---------------------------------------------------------------------------

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private var profile = ProfileStore.load(app)

    // --- state exposed to the UI ---
    var enrolledTrace by mutableStateOf(profile.enrolledTrace)
        private set
    var enrolledSummary by mutableStateOf(profile.enrolledSummary)
        private set
    /** Additional recordings of the same routine, beyond the first. */
    var extraTraces by mutableStateOf(profile.extraTraces)
        private set
    var lockEnabled by mutableStateOf(profile.lockEnabled)
        private set
    var strongMode by mutableStateOf(profile.strongMode)
        private set
    var setupDone by mutableStateOf(profile.setupDone)
        private set
    var securityLevel by mutableStateOf(profile.securityLevel)
        private set
    var attemptsBeforePin by mutableStateOf(profile.attemptsBeforePin)
        private set
    var lockDelaySeconds by mutableStateOf(profile.lockDelaySeconds)
        private set
    var stringMode by mutableStateOf(profile.stringMode)
        private set

    /** The most recent unlock attempt, shown on the lock screen. */
    var lastResult by mutableStateOf<SessionResult?>(null)
        private set

    /** The detected actions inside the enrolled routine, for the summary card. */
    val enrolledEvents: List<DetectedEvent>
        get() = enrolledTrace?.let { AutoSegmenter.segment(it).events } ?: emptyList()

    /**
     * How many recordings the user has made. Three is recommended: it is the
     * first point where the app can see their actual range rather than one
     * arbitrary sample, and where a single odd take can be ignored.
     */
    val takeCount: Int get() = totalTakes()

    companion object {
        /**
         * Exactly three recordings, no more and no fewer.
         *
         * Three is the first point at which the app can see the user's actual
         * range rather than one arbitrary sample, and where a single odd take
         * can be ignored. More than three widens what the lock accepts without
         * adding meaningful reliability — and widening the acceptance envelope
         * is a real security cost, so it should not be offered casually.
         */
        const val MAX_TAKES = 3

        /** The enrolment flow always collects exactly this many. */
        const val RECOMMENDED_TAKES = 3
    }

    /**
     * How the phone was held during enrolment, and whether that is usable.
     *
     * This is shown on the home screen because the alternative is a silent
     * trap: a routine enrolled with the phone lying flat has no hold reference,
     * so the "hold it the same way" factor quietly does nothing, and changing
     * the angle at unlock time makes no difference at all. The user has no way
     * to tell that from a broken feature, so the app says it outright.
     */
    val enrolledGrip: GripInfo
        get() {
            val trace = enrolledTrace ?: return GripInfo.None
            val grip = ShapeBuilder.build(Trace(SegmentKind.TAP, emptyList(), trace.motion))
            return if (grip.hasGrip) {
                GripInfo.Enrolled(grip.meanPitch, grip.meanRoll, grip.tiltJitterDeg)
            } else {
                GripInfo.None
            }
        }

    /** Where the hold factor currently stands. */
    sealed interface GripInfo {
        /** Nothing usable was recorded — re-enrol while actually holding the phone. */
        data object None : GripInfo

        /** A real hold reference exists and is being enforced. */
        data class Enrolled(val pitch: Float, val roll: Float, val jitter: Float) : GripInfo
    }

    private fun persist() {
        profile = profile.copy(
            enrolledTrace = enrolledTrace,
            extraTraces = extraTraces,
            enrolledSummary = enrolledSummary,
            lockEnabled = lockEnabled,
            strongMode = strongMode,
            setupDone = setupDone,
            securityLevel = securityLevel,
            attemptsBeforePin = attemptsBeforePin,
            lockDelaySeconds = lockDelaySeconds,
            stringMode = stringMode
        )
        ProfileStore.save(getApplication(), profile)
    }

    // -- enrolment -----------------------------------------------------------

    /** Save a newly recorded session as the user's routine. Replaces everything. */
    fun enroll(trace: Trace) {
        val session = AutoSegmenter.segment(trace, stringMode)
        enrolledTrace = trace
        extraTraces = emptyList()
        enrolledSummary = session.summary()
        persist()
    }

    /**
     * Add another recording of the same routine.
     *
     * Takes are held to [MAX_TAKES], and a take that is not the same routine as
     * the first one is refused: three different routines are three different
     * locks, not three samples of one.
     *
     * Returns false, with a reason, if it could not be added.
     */
    fun addTake(trace: Trace): Result {
        val session = AutoSegmenter.segment(trace, stringMode)
        if (session.events.size < 2 && !stringMode) {
            return Result.Failed("That recording was too short to be a valid take.")
        }
        if (session.summary() != enrolledSummary) {
            return Result.Failed(
                "That is a different routine (\"${session.summary()}\"). " +
                    "Each take must be the same actions in the same order."
            )
        }
        if (enrolledTrace == null) {
            // No first take yet, so this one becomes it.
            enroll(trace)
            return Result.Ok(1)
        }
        if (totalTakes() >= MAX_TAKES) {
            return Result.Failed("You already have $MAX_TAKES takes. That is the limit.")
        }
        extraTraces = extraTraces + trace
        persist()
        return Result.Ok(totalTakes())
    }

    /** Discard one take. Index 0 is the first one. */
    fun removeTake(index: Int) {
        val all = (listOfNotNull(enrolledTrace) + extraTraces).toMutableList()
        if (index !in all.indices) return
        all.removeAt(index)
        // The first take always lives in enrolledTrace, so promote the next one
        // into its place rather than leaving a gap.
        enrolledTrace = all.firstOrNull()
        extraTraces = all.drop(1)
        enrolledSummary = enrolledTrace?.let {
            AutoSegmenter.segment(it, stringMode).summary()
        } ?: ""
        persist()
    }

    /** Outcome of trying to add a take. */
    sealed interface Result {
        data class Ok(val count: Int) : Result
        data class Failed(val why: String) : Result
    }

    /** How many takes the user has recorded. */
    fun totalTakes(): Int = if (enrolledTrace == null) 0 else 1 + extraTraces.size

    fun clearEnrolled() {
        enrolledTrace = null
        extraTraces = emptyList()
        enrolledSummary = ""
        persist()
    }

    // -- usability checks -----------------------------------------------------

    /**
     * True when the enrolled routine is good enough to lock with.
     *
     * We insist on at least two distinct actions. A routine of a single tap is
     * trivially guessable and would give a false sense of security, so we
     * refuse to let the user lock the phone with one.
     */
    fun isRoutineUsable(): Boolean {
        // A string is one continuous stroke that is hard to guess, so a single
        // event is meaningful there. Other modes need at least two actions,
        // because one tap on its own would be trivially guessable.
        val trace = enrolledTrace
        if (stringMode) return trace != null && trace.touches.size > 20
        return enrolledEvents.size >= 2
    }

    /** Why the lock cannot be turned on yet — shown in red on the home screen. */
    fun routineProblem(): String? {
        if (enrolledTrace == null) {
            return "Record your routine first. Tap \"Set up routine\" and perform " +
                "a short sequence of actions."
        }
        if (stringMode) {
            return if (enrolledTrace?.touches?.size ?: 0 <= 20)
                "That string is too short. Draw a longer stroke so it is secure."
            else null
        }
        val events = enrolledEvents
        if (events.size < 2) {
            return "Your routine only has ${events.size} action. Do at least two " +
                "different actions (for example tap, then draw) so it is secure."
        }
        return null
    }

    // -- settings -------------------------------------------------------------

    fun updateLockEnabled(enabled: Boolean) {
        // Never let the lock engage with a routine that cannot be matched.
        if (enabled && !isRoutineUsable()) {
            lockEnabled = false
            LockService.stop(getApplication())
            persist()
            return
        }
        lockEnabled = enabled
        if (!enabled) {
            // Turning the lock off must always give the phone back to the user.
            strongMode = false
            Watchdog.restoreSystemLock(getApplication())
            LockService.stop(getApplication())
        } else {
            LockService.start(getApplication())
        }
        persist()
    }

    fun updateStrongMode(enabled: Boolean) {
        if (enabled) {
            strongMode = AdminReceiver.enableStrongMode(getApplication())
        } else {
            Watchdog.restoreSystemLock(getApplication())
            strongMode = false
        }
        persist()
    }

    fun updateSecurityLevel(level: String) {
        securityLevel = level
        persist()
    }

    fun updateStringMode(enabled: Boolean) {
        // Changing this changes how a routine is read, so the old enrolment is
        // no longer valid and must be discarded rather than silently mismatched.
        stringMode = enabled
        enrolledTrace = null
        enrolledSummary = ""
        persist()
    }

    fun updateLockDelay(seconds: Int) {
        lockDelaySeconds = seconds
        persist()
    }

    fun completeSetup() {
        setupDone = true
        persist()
    }

    // -- unlock ---------------------------------------------------------------

    /** Score a fresh attempt against the enrolled routine. */
    fun verify(attempt: Trace): SessionResult {
        val enrolled = enrolledTrace?.let { AutoSegmenter.segment(it, stringMode) } ?: DetectedSession()
        val attempted = AutoSegmenter.segment(attempt, stringMode)
        val result = SessionScorer.compare(enrolled, attempted)
        lastResult = result
        return result
    }

    fun clearResult() {
        lastResult = null
    }
}