package com.block.behaviortlock

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Button
import android.widget.LinearLayout

// ---------------------------------------------------------------------------
// LockOverlay.kt — a full-screen lock drawn ON TOP of everything.
//
// Why this works where LockActivity did not:
//
//   A third-party app cannot dismiss the Android system lock screen unless it
//   is a device owner. Attempting to replace the keyguard therefore always
//   leaves the phone locked. This file avoids that entirely: it never touches
//   the keyguard at all. It simply draws a full-screen window over whatever
//   the user was looking at, swallows all touches, and removes itself the
//   moment the gesture matches.
//
//   This is the same technique used by every app-lock product on the Play
//   Store, and it needs no root and no factory reset.
// ---------------------------------------------------------------------------

object LockOverlay {

    /** Longest the lock stays up before releasing itself. */
    private const val MAX_SECONDS = 45

    /**
     * Attempts before the lock offers the system PIN.
     *
     * This is only a nudge: "Try again" is offered on that panel too, and the
     * time limit releases the lock on its own regardless, so a wrong guess can
     * never leave anyone locked out.
     */
    private const val MAX_ATTEMPTS = 3

    /**
     * How long the routine must stop changing before it is judged, in ms.
     *
     * Long enough for a long press to finish resolving and for a lightly
     * bouncing finger not to split a tap into two contacts, short enough that
     * the user still sees an answer straight away.
     */
    private const val SETTLE_MS = 700L
    private const val TAG = "BHT/Overlay"

    /** Where the last-attempt report is appended. Readable via `adb run-as`. */
    private const val REPORT_FILE = "last-attempt.txt"

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var recorder: Recorder? = null
    private var enrolled: DetectedSession = DetectedSession()

    /**
     * Every recorded take of the routine, the first one first.
     *
     * The attempt is matched against all of them and the best result wins. See
     * [MultiTakeScorer] for why, and for the security cost this trades away.
     */
    private var references: List<DetectedSession> = emptyList()

    private val handler = Handler(Looper.getMainLooper())

    private var running = false
    private var secondsLeft = MAX_SECONDS
    private var attempts = 0

    private var detected: List<DetectedEvent> = emptyList()

    /**
     * Every motion sample from the attempt in progress.
     *
     * How the phone was HELD has to be judged across the whole routine, because
     * a single tap is shorter than the motion sensor's sampling interval.
     */
    private var detectedMotion: List<MotionPoint> = emptyList()

    /** Settle-before-judging state. See [SETTLE_MS]. */
    private var settleAtElapsedMs = 0L
    private var settleEventCount = -1

    /** The tilt and steadiness of the phone during the current attempt. */
    private val detectedGrip: Shape
        get() = ShapeBuilder.build(Trace(SegmentKind.TAP, emptyList(), detectedMotion))
    private var matched: List<Boolean> = emptyList()
    private var stringMode: Boolean = false
    private var judged: Boolean = false

    // Absolute deadline for the safety release.
    //
    // Counting down a variable is not enough: if the main looper stalls (screen
    // off, heavy load, doze) the handler stops firing and a countdown simply
    // stops, leaving the overlay stuck on screen. Measuring against the wall
    // clock means the lock always comes down on time no matter what.
    private var releaseAtElapsedMs: Long = 0L

    /** True when the overlay is currently covering the screen. */
    val isShowing: Boolean get() = rootView != null

    /** Can we draw over other apps? The user must grant this in Settings. */
    fun canDraw(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    /**
     * Show the lock. Does nothing if it is already showing or the overlay
     * permission has not been granted.
     */
    fun show(context: Context) {
        if (rootView != null) return
        if (!canDraw(context)) return

        val profile = ProfileStore.load(context)
        val session = profile.enrolledSession()
        if (session.isEmpty) return

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val rec = Recorder(sm)

        // Tell the recorder the real screen size so drawn coordinates map
        // correctly on any device rather than assuming 1080x2400.
        rec.screenWidth = context.resources.displayMetrics.widthPixels.toFloat()
        rec.screenHeight = context.resources.displayMetrics.heightPixels.toFloat()

        enrolled = session

        // Every take of the routine, so an attempt can be matched against the
        // user's whole recorded range rather than one arbitrary sample. With a
        // single take this is a list of one and behaves exactly as before.
        val takes = profile.allSessions()
        references = takes.ifEmpty { listOf(session) }

        recorder = rec
        running = false
        secondsLeft = MAX_SECONDS
        attempts = 0
        detected = emptyList()
        matched = emptyList()
        stringMode = profile.stringMode
        judged = false

        val view = buildView(context, rec)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            // NOT_FOCUSABLE so we never steal the keyboard or block the power
            // button. We do still receive every touch.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            wm.addView(view, params)
            windowManager = wm
            rootView = view
            Log.d(TAG, "Overlay shown, releases in ${MAX_SECONDS}s")
            startRecording(rec)
        } catch (t: Throwable) {
            // If the window cannot be added, never leave the phone in a state
            // where something is drawn but unremovable.
            teardown()
        }
    }

    /** Hide the lock and hand the phone back. */
    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        recorder?.cancel()
        teardown()
    }

    private fun teardown() {
        Log.d(TAG, "Overlay released")
        val view = rootView
        val wm = windowManager
        if (view != null && wm != null) {
            try { wm.removeView(view) } catch (_: Throwable) { }
        }
        rootView = null
        windowManager = null
        recorder = null
        running = false
        releaseAtElapsedMs = 0L
    }

    // -- recording + scoring ---------------------------------------------------

    private fun startRecording(rec: Recorder) {
        rec.clear()
        rec.start(SegmentKind.DRAW)
        running = true
        judged = false

        // Forget the previous attempt. Without this, "Try again" judges the
        // events from the attempt that just failed, on the very next tick, so
        // the failure panel reappears instantly and retrying is impossible.
        detected = emptyList()
        detectedMotion = emptyList()
        matched = emptyList()
        settleAtElapsedMs = 0L
        settleEventCount = -1

        secondsLeft = MAX_SECONDS
        releaseAtElapsedMs = SystemClock.elapsedRealtime() + MAX_SECONDS * 1000L

        // One heartbeat, not one per retry. Posting a new one on every attempt
        // left several loops running at once, each able to judge.
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    /**
     * The overlay heartbeat.
     *
     * It keeps beating even after recording stops, so the safety release still
     * fires while a failure panel is on screen.
     */
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isShowing) return
            tick()
            handler.postDelayed(this, 100)
        }
    }

    private fun tick() {
        // SAFETY FIRST: release against the wall clock, before anything else.
        // This check must never depend on the recorder or on scoring.
        if (SystemClock.elapsedRealtime() >= releaseAtElapsedMs) {
            dismiss()
            return
        }
        val rec = recorder
        secondsLeft = ((releaseAtElapsedMs - SystemClock.elapsedRealtime()) / 1000L)
            .toInt().coerceAtLeast(0)
        if (rec == null || !running) {
            rootView?.invalidate()
            return
        }

        val live = rec.liveTouchBuffer
        val motion = rec.liveMotionBuffer
        if (live.isNotEmpty() || motion.isNotEmpty()) {
            // Pass the motion through and honour string mode, otherwise shakes
            // are invisible and a string routine never compares like-for-like.
            val session = AutoSegmenter.segment(
                Trace(SegmentKind.DRAW, live, motion),
                stringMode
            )
            detected = session.events
            detectedMotion = session.motion
            if (!enrolled.isEmpty) {
                val res = MultiTakeScorer.bestOf(
                    references,
                    DetectedSession(session.events, 0f, session.motion)
                ).best
                matched = res.events.map { it.matched }
            }
        }
        rootView?.invalidate()

        // Once the routine looks complete, wait for it to SETTLE before judging.
        //
        // Judging the instant the action count is reached was fragile. A finger
        // that lifts even slightly splits one tap into two contacts, which
        // reaches the expected count early and judges a half-finished routine —
        // a routine ending in a long press then failed with "expected Hold,
        // got Tap" because the judge ran while the press was still resolving.
        //
        // Waiting until nothing new has arrived for a moment means the whole
        // routine is always judged. It does not weaken anything: extra actions
        // that arrive during the settle window are still counted, and the
        // 45-second release still applies.
        if (!enrolled.isEmpty && !judged && detected.size >= enrolled.events.size) {
            val now = SystemClock.elapsedRealtime()
            if (detected.size != settleEventCount) {
                settleEventCount = detected.size
                settleAtElapsedMs = now + SETTLE_MS
            }
            if (now < settleAtElapsedMs) return

            judged = true
            // The motion has to come along, or the grip factor is skipped and
            // how the phone was held never affects the result.
            val outcome = MultiTakeScorer.bestOf(
                references,
                DetectedSession(detected, 0f, detectedMotion)
            )
            val res = outcome.best
            if (res.passed) {
                finishAttempt()
            } else {
                // Tell the user straight away. Silently waiting for the
                // timeout looked identical to "the lock is broken".
                showFailure(res)
                logResult(res, detected, outcome)
            }
            return
        }
    }

    /**
     * Print exactly why an attempt was refused.
     *
     * The lock screen only has room for one line, which is not enough to debug
     * a failure from a report or over adb. This prints every factor so the real
     * numbers can be read back with `adb logcat -s BHT/Overlay`.
     */
    private fun logResult(
        res: SessionResult,
        detected: List<DetectedEvent>,
        outcome: MultiTakeScorer.MatchOutcome
    ) {
        Log.d(
            TAG,
            "Takes: matched ${outcome.passedCount}/${outcome.results.size}, " +
                "best was take #${outcome.bestIndex + 1} at ${res.totalScore.toInt()}%"
        )
        outcome.results.forEachIndexed { i, r ->
            Log.d(
                TAG,
                "  take[${i + 1}] passed=${r.passed} score=${r.totalScore.toInt()} " +
                    "countOk=${r.countOk} seqOk=${r.sequenceOk} rhythmOk=${r.rhythmOk} " +
                    "gripOk=${r.gripOk} headline='${r.headline}'"
            )
        }
        Log.d(
            TAG,
            "REFUSED headline='${res.headline}' passed=${res.passed} " +
                "countOk=${res.countOk} seqOk=${res.sequenceOk} " +
                "completed=${res.completed} rhythmOk=${res.rhythmOk} " +
                "rhythm=${res.rhythmScore} total=${res.totalScore}"
        )
        Log.d(
            TAG,
            if (res.gripMeasured)
                "  grip ok=${res.gripOk} score=${res.gripScore.toInt()} " +
                    "enrolled tilt=${fmtDeg(enrolled.grip.meanPitch)}/" +
                    "${fmtDeg(enrolled.grip.meanRoll)} jitter=${fmtDeg(enrolled.grip.tiltJitterDeg)} " +
                    "| attempt tilt=${fmtDeg(detectedGrip.meanPitch)}/" +
                    "${fmtDeg(detectedGrip.meanRoll)} jitter=${fmtDeg(detectedGrip.tiltJitterDeg)}"
            else "  grip SKIPPED (phone flat or no attitude reported) " +
                "enrolledTilt=${fmtDeg(enrolled.grip.meanPitch)}/${fmtDeg(enrolled.grip.meanRoll)} " +
                "attemptTilt=${fmtDeg(detectedGrip.meanPitch)}/${fmtDeg(detectedGrip.meanRoll)} " +
                "samples=${detectedMotion.size}"
        )
        Log.d(
            TAG,
            "  gyro mean rad/s enrolled=${fmtDeg(enrolled.grip.meanGyro)} " +
                "attempt=${fmtDeg(detectedGrip.meanGyro)} " +
                "motionSamples=${detectedMotion.size}"
        )
        Log.d(TAG, "  enrolled kinds: ${enrolled.events.joinToString { it.kind.name }}")
        Log.d(TAG, "  enrolled gaps : ${enrolled.events.joinToString { it.gapBeforeMs.toInt().toString() }}")
        Log.d(TAG, "  detected kinds: ${detected.joinToString { it.kind.name }}")
        Log.d(TAG, "  detected gaps : ${detected.joinToString { it.gapBeforeMs.toInt().toString() }}")
        res.events.forEachIndexed { i, m ->
            Log.d(TAG, "  event[$i] ${m.kind.name} score=${m.score.toInt()} matched=${m.matched} note='${m.note}'")
        }
        Log.d(
            TAG,
            "  detected durations(ms): " +
                detected.joinToString { "${it.kind.name}:${it.durationMs.toInt()}" }
        )
        Log.d(
            TAG,
            "  enrolled durations(ms): " +
                enrolled.events.joinToString { "${it.kind.name}:${it.durationMs.toInt()}" }
        )

        // Some Android skins (MIUI among them) hide an app's log output
        // completely, so the numbers are also written to the app's own storage.
        // That way a failure can always be read back after the fact.
        runCatching {
            appContext?.openFileOutput(REPORT_FILE, Context.MODE_APPEND)?.use { out ->
                out.write(buildString {
                    appendLine("---- attempt ----")
                    appendLine("headline=${res.headline} passed=${res.passed}")
                    appendLine(
                        "takes matched ${outcome.passedCount}/${outcome.results.size} " +
                            "best=#${outcome.bestIndex + 1} score=${res.totalScore.toInt()}"
                    )
                    outcome.results.forEachIndexed { i, r ->
                        appendLine(
                            "  take[${i + 1}] passed=${r.passed} score=${r.totalScore.toInt()} " +
                                "countOk=${r.countOk} seqOk=${r.sequenceOk} rhythmOk=${r.rhythmOk} " +
                                "gripOk=${r.gripOk} headline='${r.headline}'"
                        )
                    }
                    appendLine(
                        "countOk=${res.countOk} seqOk=${res.sequenceOk} " +
                            "completed=${res.completed} rhythmOk=${res.rhythmOk} " +
                            "rhythm=${res.rhythmScore} total=${res.totalScore}"
                    )
                    appendLine(
                        if (res.gripMeasured)
                            "grip ok=${res.gripOk} score=${res.gripScore.toInt()} " +
                                "enrolled tilt=${fmtDeg(enrolled.grip.meanPitch)}/" +
                                "${fmtDeg(enrolled.grip.meanRoll)} " +
                                "jitter=${fmtDeg(enrolled.grip.tiltJitterDeg)} | " +
                                "attempt tilt=${fmtDeg(detectedGrip.meanPitch)}/" +
                                "${fmtDeg(detectedGrip.meanRoll)} " +
                                "jitter=${fmtDeg(detectedGrip.tiltJitterDeg)}"
                        else "grip SKIPPED (phone flat or no attitude) " +
                            "enrolledTilt=${fmtDeg(enrolled.grip.meanPitch)}/" +
                            "${fmtDeg(enrolled.grip.meanRoll)} attemptTilt=" +
                            "${fmtDeg(detectedGrip.meanPitch)}/" +
                            "${fmtDeg(detectedGrip.meanRoll)} " +
                            "motionSamples=${detectedMotion.size}"
                    )
                    appendLine(
                        "gyro mean rad/s enrolled=${fmtDeg(enrolled.grip.meanGyro)} " +
                            "attempt=${fmtDeg(detectedGrip.meanGyro)}"
                    )
                    appendLine("enrolled kinds: ${enrolled.events.joinToString { it.kind.name }}")
                    appendLine("enrolled gaps : ${enrolled.events.joinToString { it.gapBeforeMs.toInt().toString() }}")
                    appendLine("detected kinds: ${detected.joinToString { it.kind.name }}")
                    appendLine("detected gaps : ${detected.joinToString { it.gapBeforeMs.toInt().toString() }}")
                    appendLine(
                        "detected durations: " +
                            detected.joinToString { "${it.kind.name}:${it.durationMs.toInt()}" }
                    )
                    appendLine(
                        "enrolled durations: " +
                            enrolled.events.joinToString { "${it.kind.name}:${it.durationMs.toInt()}" }
                    )
                    res.events.forEachIndexed { i, m ->
                        appendLine(
                            "event[$i] ${m.kind.name} score=${m.score.toInt()} " +
                                "matched=${m.matched} note='${m.note}'"
                        )
                    }
                }.toByteArray())
            }
        }
    }

    /** Format an angle for the report, tolerating NaN. */
    private fun fmtDeg(v: Float): String =
        if (v.isNaN()) "n/a" else String.format("%.1f", v)


    /** The routine matched: stop everything and let the user in. */
    private fun finishAttempt() {
        running = false
        recorder?.stop()
        appContext?.let { Watchdog.disarm(it) }
        dismiss()
    }

    /**
     * Shown as soon as the routine has been performed but did not match.
     *
     * Without this the user performed their whole routine, nothing happened,
     * and the screen just kept counting down -- which is indistinguishable
     * from the lock being broken.
     */
    private fun showFailure(result: SessionResult) {
        running = false
        attempts += 1

        // After enough failures the only fair exit is the real phone lock.
        // Without this, "Try again" could be pressed forever.
        if (attempts >= MAX_ATTEMPTS) {
            showPinOption()
            return
        }

        val ctx = appContext ?: return
        val container = rootView as? FrameLayout ?: return

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 28), dp(ctx, 28), dp(ctx, 28), dp(ctx, 28))
            setBackgroundColor(Color.parseColor("#F20B1220"))
        }
        panel.addView(TextView(ctx).apply {
            text = "Didn't match"
            setTextColor(RED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
        })
        panel.addView(TextView(ctx).apply {
            text = result.headline
            setTextColor(Color.LTGRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 20))
        })
        panel.addView(Button(ctx).apply {
            text = "Try again"
            setOnClickListener {
                removePanel(container)
                recorder?.let { startRecording(it) }
            }
        })
        panel.addView(Button(ctx).apply {
            text = "Use system PIN"
            setOnClickListener {
                val c = appContext ?: return@setOnClickListener
                ProfileStore.save(c, ProfileStore.load(c).copy(lockEnabled = false))
                dismiss()
            }
        })

        container.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER }
        )
    }

    /** Remove any result panel we previously added. */
    private fun removePanel(container: FrameLayout) {
        for (i in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(i)
            if (child is LinearLayout) container.removeViewAt(i)
        }
    }

    /**
     * After too many failures.
     *
     * This is a nudge, not a dead end. The owner is always allowed to try again
     * -- the lock can never trap anyone, because the time limit releases it
     * whether or not any button is pressed. So "Try again" comes back here
     * too, and it starts a fresh set of attempts.
     */
    private fun showPinOption() {
        val ctx = appContext ?: return
        val container = rootView as? FrameLayout ?: return

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 28), dp(ctx, 28), dp(ctx, 28), dp(ctx, 28))
            setBackgroundColor(Color.parseColor("#F20B1220"))
        }
        panel.addView(TextView(ctx).apply {
            text = "Too many attempts"
            setTextColor(RED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
        })
        panel.addView(TextView(ctx).apply {
            text = "Take your time. This screen closes on its own in " +
                "${MAX_SECONDS} seconds, so you can never be locked out."
            setTextColor(Color.LTGRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 24))
        })
        panel.addView(Button(ctx).apply {
            text = "Try again"
            setOnClickListener {
                val rec = recorder ?: return@setOnClickListener
                removePanel(container)
                // A fresh set of attempts, and a fresh full time limit.
                attempts = 0
                startRecording(rec)
            }
        })
        panel.addView(Button(ctx).apply {
            text = "Use system PIN instead"
            setOnClickListener {
                val c = appContext ?: return@setOnClickListener
                ProfileStore.save(c, ProfileStore.load(c).copy(lockEnabled = false))
                dismiss()
            }
        })

        container.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER }
        )
    }

    private var appContext: Context? = null

    // -- the view --------------------------------------------------------------

    @SuppressLint("ViewConstructor")
    private fun buildView(context: Context, rec: Recorder): View {
        appContext = context.applicationContext

        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#F00B1220"))
            isClickable = true
            isFocusable = true
        }

        val canvas = LockCanvasView(context)
        root.addView(
            canvas,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Every touch goes to the recorder; the overlay consumes them all, so
        // nothing underneath can be tapped.
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (running) rec.onTouchEvent(event)
                    canvas.invalidate()
                }
            }
            true
        }

        return root
    }

    /** Draws the title, countdown, live trail and action markers. */
    @SuppressLint("ViewConstructor")
    private class LockCanvasView(context: Context) : View(context) {
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 42f; typeface = Typeface.DEFAULT_BOLD
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY; textSize = 30f
        }
        private val timerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 52f; typeface = Typeface.DEFAULT_BOLD
        }
        private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8"); style = Paint.Style.STROKE; strokeWidth = 10f
        }
        private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FBBF24") }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            canvas.drawText("Behavioural Lock", 60f, 160f, titlePaint)
            canvas.drawText("Perform your routine", 60f, 220f, subPaint)

            // Countdown, top right.
            if (running) {
                canvas.drawText("${secondsLeft}s", w - 180f, 160f, timerPaint)
            }

            // The live finger trail.
            val rec = recorder
            if (running && rec != null) {
                val live = rec.liveTouchBuffer
                if (live.size > 1) {
                    val path = Path()
                    live.forEachIndexed { i, p ->
                        val x = p.x / 1080f * w
                        val y = p.y / 2400f * h
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    canvas.drawPath(path, trailPaint)
                }

                // One marker per detected action: green once matched.
                detected.forEachIndexed { i, _ ->
                    val isMatch = matched.getOrNull(i) ?: false
                    markerPaint.color = when {
                        matched.isEmpty() -> Color.parseColor("#7DD3FC")
                        isMatch -> Color.parseColor("#22C55E")
                        else -> Color.parseColor("#EF4444")
                    }
                    val cx = 80f + i * 70f
                    canvas.drawCircle(cx, h - 140f, 26f, markerPaint)
                }

                if (detected.isNotEmpty()) {
                    canvas.drawText(
                        "${detected.size} action(s) detected",
                        60f, h - 200f, subPaint
                    )
                }
            }
        }
    }

    /** Convert dp to pixels for this context. */
    private fun dp(context: Context, value: Int): Int {
        val d = context.resources.displayMetrics.density
        return (value * d).toInt()
    }

    private val GREEN = Color.parseColor("#22C55E")
    private val RED = Color.parseColor("#EF4444")
}
