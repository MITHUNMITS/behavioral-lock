package com.block.behaviortlock

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.MotionEvent

// ---------------------------------------------------------------------------
// Recorder.kt — records sensors AND touches onto one shared timeline.
//
// Two things make this work:
//
// 1. SensorEvent.timestamp and MotionEvent.eventTime live on the SAME clock
//    (both are nanoseconds / milliseconds since boot). We normalise both to
//    "nanoseconds since capture start" at the very first sample, so every
//    touch and every motion sample can be lined up.
//
// 2. We only run the sensors during the capture window (a few seconds), not
//    continuously. That keeps battery use to roughly 3-4 mA during an unlock
//    and means we never passively profile how the phone is being held.
// ---------------------------------------------------------------------------

class Recorder(private val sensorManager: SensorManager) : SensorEventListener {

    // Shared clock: nanoseconds since the capture started.
    private var startNanos = 0L
    private var clockSet = false

    // Raw capture buffers.
    private val touchBuffer = mutableListOf<TouchPoint>()
    private val motionBuffer = mutableListOf<MotionPoint>()

    // Latest raw sensor values, used to fill in motion samples.
    private var latestAccel = FloatArray(3)
    private var latestGyro = FloatArray(3)
    private var gravity = FloatArray(3)
    private var haveGravity = false
    private var latestPitch = 0f
    private var latestRoll = 0f

    private var recording = false
    private var currentKind: SegmentKind = SegmentKind.TAP

    /**
     * Screen size in pixels, set at the start of each capture. The recorder
     * needs this to turn raw touch coordinates into screen fractions so the UI
     * can draw markers at the right place on any device.
     */
    @Volatile var screenWidth: Float = 1080f
    @Volatile var screenHeight: Float = 2400f

    /**
     * A read-only view of what has been recorded so far.
     *
     * The recorder screen polls this a few times a second while recording so
     * it can show live trails and mark each action as soon as it completes.
     */
    val liveTouchBuffer: List<TouchPoint> get() = synchronized(touchBuffer) { touchBuffer.toList() }
    val liveMotionBuffer: List<MotionPoint> get() = synchronized(motionBuffer) { motionBuffer.toList() }

    /**
     * The routine this recorder should score new attempts against.
     *
     * Set once before showing the unlock or test screen. Kept here so the
     * recorder screen does not need to be passed a second copy of the data.
     */
    @Volatile var enrolledSession: DetectedSession = DetectedSession()

    /**
     * A short rolling window of motion energy, normalised to roughly -1..1.
     *
     * The recorder screen reads this to draw the live waveform, so the user
     * can see that the phone's movement is genuinely being measured. Values
     * are centred on "still" (0) and grow with movement in either direction.
     */
    private val energyWindow = ArrayDeque<Float>()

    /** Push the current motion energy into the rolling window. */
    private fun pushEnergy(value: Float) {
        synchronized(energyWindow) {
            energyWindow.addLast(value)
            while (energyWindow.size > ENERGY_WINDOW_SIZE) energyWindow.removeFirst()
        }
    }

    /** A snapshot of the rolling window, oldest first. */
    fun energyTrace(): List<Float> =
        synchronized(energyWindow) { energyWindow.toList() }

    companion object {
        /** How many samples the live waveform shows. */
        const val ENERGY_WINDOW_SIZE = 96
    }

    /** True while a capture is in progress. */
    val isRecording: Boolean get() = recording

    // -- availability --------------------------------------------------------

    val hasAccelerometer: Boolean
        get() = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    val hasGyroscope: Boolean
        get() = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

    val hasGravity: Boolean
        get() = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) != null

    val hasRotationVector: Boolean
        get() = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null

    val hasLinearAcceleration: Boolean
        get() = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null

    /** Which of the five segment types this phone can fully support. */
    fun supportedSegments(): Set<SegmentKind> {
        val set = mutableSetOf<SegmentKind>()
        set += SegmentKind.TAP
        set += SegmentKind.HOLD
        set += SegmentKind.SWIPE
        set += SegmentKind.DRAW
        if (hasLinearAcceleration || hasAccelerometer) set += SegmentKind.SHAKE
        return set
    }

    // -- recording lifecycle --------------------------------------------------

    fun start(kind: SegmentKind) {
        synchronized(touchBuffer) { touchBuffer.clear() }
        synchronized(motionBuffer) { motionBuffer.clear() }
        synchronized(energyWindow) { energyWindow.clear() }
        startNanos = SystemClock.elapsedRealtimeNanos()
        clockSet = true
        currentKind = kind
        recording = true
        haveGravity = false

        // SENSOR_DELAY_GAME (~20ms) is a good balance of accuracy and battery.
        // maxReportLatency = 0 delivers samples immediately instead of batching
        // them, which matters because a gesture can be over in under a second.
        val delay = SensorManager.SENSOR_DELAY_GAME
        fun reg(type: Int) {
            sensorManager.getDefaultSensor(type)?.let {
                sensorManager.registerListener(this, it, delay, 0)
            }
        }
        reg(Sensor.TYPE_ACCELEROMETER)
        reg(Sensor.TYPE_LINEAR_ACCELERATION)
        reg(Sensor.TYPE_GRAVITY)
        reg(Sensor.TYPE_GAME_ROTATION_VECTOR)
        reg(Sensor.TYPE_GYROSCOPE)
    }

    /** Stop recording and return the captured trace. */
    fun stop(): Trace {
        recording = false
        sensorManager.unregisterListener(this)
        return Trace(currentKind, touchBuffer.toList(), motionBuffer.toList())
    }

    /**
     * Throw away everything recorded so far without producing a Trace.
     *
     * Used when the user presses "Record again", so the previous attempt's
     * trail and detected actions do not linger on screen.
     */
    fun clear() {
        recording = false
        sensorManager.unregisterListener(this)
        synchronized(touchBuffer) { touchBuffer.clear() }
        synchronized(motionBuffer) { motionBuffer.clear() }
        synchronized(energyWindow) { energyWindow.clear() }
    }

    fun cancel() {
        recording = false
        sensorManager.unregisterListener(this)
        touchBuffer.clear()
        motionBuffer.clear()
    }

    // -- clock ---------------------------------------------------------------

    /** Convert any sensor/touch timestamp into "ns since capture start". */
    private fun toRelative(timestampNanos: Long): Long =
        if (!clockSet) 0L else (timestampNanos - startNanos).coerceAtLeast(0L)

    // -- touch input ----------------------------------------------------------

    /**
     * Record one touch sample directly.
     *
     * Called from the Compose capture surface, which gives us the pointer
     * position and the event uptime in milliseconds. Compose does not expose
     * MotionEvent's historical sub-frame samples, so the tap/drag rate here is
     * the pointer dispatch rate (typically 60-120Hz), which is plenty for the
     * rhythm and shape signals we score.
     *
     * @param uptimeMillis event time in milliseconds since boot
     * @param x screen x in pixels
     * @param y screen y in pixels
     * @param pressure touch pressure, 1.0 on phones without force touch
     */
    fun addTouch(uptimeMillis: Long, x: Float, y: Float, pressure: Float = 1f) {
        if (!recording || !clockSet) return
        val t = toRelative(uptimeMillis * 1_000_000L)
        val fingers = currentFingers.coerceAtLeast(1)
        synchronized(touchBuffer) { touchBuffer += TouchPoint(t, x, y, fingers, pressure) }
    }

    /** Track how many fingers are currently down, used by addTouch. */
    @Volatile
    var currentFingers: Int = 1
        set(value) { field = value.coerceAtLeast(1) }

    /**
     * Record the START of a contact (a finger going down).
     *
     * The DOWN/UP markers are what let AutoSegmenter split one long,
     * continuous recording back into separate actions later on.
     */
    fun onDown(uptimeMillis: Long, x: Float, y: Float, fingers: Int = 1) {
        if (!recording || !clockSet) return
        val t = toRelative(uptimeMillis * 1_000_000L)
        currentFingers = fingers
        synchronized(touchBuffer) { touchBuffer += TouchPoint(t, x, y, fingers, currentPressure, TouchPhase.DOWN) }
    }

    /** Record the END of a contact (a finger lifting). */
    fun onUp(uptimeMillis: Long, x: Float, y: Float, fingers: Int = 1) {
        if (!recording || !clockSet) return
        val t = toRelative(uptimeMillis * 1_000_000L)
        synchronized(touchBuffer) { touchBuffer += TouchPoint(t, x, y, fingers, currentPressure, TouchPhase.UP) }
    }

    /** The pressure of the most recent sample, reused for DOWN/UP markers. */
    @Volatile var currentPressure: Float = 1f

    /**
     * Feed every MotionEvent here. We keep ACTION_MOVE samples plus the
     * historical sub-frame samples Android provides, which gives us a higher
     * effective sampling rate than the dispatch rate.
     */
    /**
     * Feed a MotionEvent here (optional path).
     *
     * The recorder screen normally calls addTouch / onDown / onUp directly,
     * because Compose gives us pointer positions without a MotionEvent. This
     * method exists so a plain Android View-based capture surface could also
     * feed the recorder. We keep the historical sub-frame samples because they
     * give a higher effective sampling rate than the dispatch rate.
     */
    fun onTouchEvent(event: MotionEvent) {
        if (!recording || !clockSet) return

        val fingers = event.pointerCount.coerceAtLeast(1)
        fun add(x: Float, y: Float, phase: TouchPhase, pressure: Float) {
            val t = toRelative(event.eventTime * 1_000_000L)
            synchronized(touchBuffer) {
                touchBuffer += TouchPoint(t, x, y, fingers, pressure, phase)
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex.coerceIn(0, fingers - 1)
                add(event.getX(idx), event.getY(idx), TouchPhase.DOWN, event.getPressure(idx))
            }

            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until event.historySize) {
                    val t = toRelative(event.getHistoricalEventTime(h) * 1_000_000L)
                    synchronized(touchBuffer) {
                        touchBuffer += TouchPoint(
                            t,
                            event.getHistoricalX(0, h),
                            event.getHistoricalY(0, h),
                            fingers,
                            event.getPressure(0),
                            TouchPhase.MOVE
                        )
                    }
                }
                add(event.x, event.y, TouchPhase.MOVE, event.getPressure(0))
            }

            MotionEvent.ACTION_UP -> {
                add(event.x, event.y, TouchPhase.UP, event.getPressure(0))
            }
            // ACTION_CANCEL and secondary up/down are ignored for simplicity.
        }
    }

    // -- sensor input ---------------------------------------------------------

    override fun onSensorChanged(event: SensorEvent) {
        if (!recording || !clockSet) return
        val t = toRelative(event.timestamp)

        // Update the latest raw values first, whichever sensor reported.
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> latestAccel = event.values.copyOf()
            Sensor.TYPE_GRAVITY -> { gravity = event.values.copyOf(); haveGravity = true }
            Sensor.TYPE_GYROSCOPE -> latestGyro = event.values.copyOf()
        }

        // Attitude (how the phone is being HELD) comes from the rotation vector.
        val carriesAttitude = event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR ||
            event.sensor.type == Sensor.TYPE_ROTATION_VECTOR
        val pitch: Float; val roll: Float
        if (carriesAttitude) {
            val a = attitudeFromRotationVector(event.values)
            pitch = a.first
            roll = a.second
            // Remember it. Sensors in a batch share a timestamp, so the sample
            // that actually gets stored is often written by a different sensor
            // -- without caching this, every stored sample carried pitch = 0 and
            // the "how the phone is held" factor could never be measured.
            latestPitch = pitch
            latestRoll = roll
        } else {
            pitch = latestPitch
            roll = latestRoll
        }

        // One stored sample per timestamp, so a batch of sensors firing together does
        // not produce duplicates. But if this event carries attitude and the
        // stored sample does not, the attitude is folded in rather than dropped
        // -- otherwise whichever sensor happened to fire first won, and on this
        // phone that was always the accelerometer, so every stored sample had a
        // pitch and roll of zero.
        val last = synchronized(motionBuffer) { motionBuffer.lastOrNull() }
        if (last != null && last.t == t) {
            if (carriesAttitude && (last.pitch == 0f && last.roll == 0f)) {
                synchronized(motionBuffer) {
                    motionBuffer[motionBuffer.lastIndex] = last.copy(pitch = pitch, roll = roll)
                }
            }
            return
        }
        val sample = buildMotion(t, pitch, roll)
        synchronized(motionBuffer) { motionBuffer += sample }

        // Feed the live waveform. We centre on 9.81 (gravity on a still phone)
        // and scale so a gentle movement is clearly visible but a hard shake
        // still fits inside the strip.
        val mag = kotlin.math.sqrt(
            (sample.accelX * sample.accelX +
                sample.accelY * sample.accelY +
                sample.accelZ * sample.accelZ).toDouble()
        ).toFloat()
        val gyroMag = kotlin.math.sqrt(
            (sample.gyroX * sample.gyroX +
                sample.gyroY * sample.gyroY +
                sample.gyroZ * sample.gyroZ).toDouble()
        ).toFloat()
        // Centre on zero. When the gravity sensor is available we subtract
        // gravity, so a still phone reads ~0; when it is not, a still phone
        // reads ~9.81 because gravity is still in the raw value. Centring on the
        // wrong baseline makes the waveform sit permanently off-screen.
        val baseline = if (haveGravity) 0f else 9.81f
        pushEnergy(((mag - baseline) / 9.81f) + gyroMag / 6f)
    }

    private fun buildMotion(t: Long, pitch: Float, roll: Float): MotionPoint {
        // Use linear acceleration when available, else subtract nothing and
        // use the raw accelerometer (it still carries a usable magnitude).
        val a = if (haveGravity) {
            FloatArray(3) { latestAccel[it] - gravity[it] }
        } else {
            latestAccel
        }
        return MotionPoint(
            t = t,
            accelX = a[0], accelY = a[1], accelZ = a[2],
            gyroX = latestGyro[0], gyroY = latestGyro[1], gyroZ = latestGyro[2],
            pitch = pitch, roll = roll
        )
    }

    /** Convert a rotation vector into pitch/roll in degrees. */
    private fun attitudeFromRotationVector(values: FloatArray): Pair<Float, Float> {
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientation)
        // orientation[1] = pitch (radians), orientation[2] = roll (radians)
        val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
        val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
        return pitch to roll
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used — we do not need sensor accuracy callbacks for this app.
    }
}
