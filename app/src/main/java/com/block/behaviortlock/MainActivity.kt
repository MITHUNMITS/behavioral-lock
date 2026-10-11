package com.block.behaviortlock

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.block.behaviortlock.ui.ActionCard
import com.block.behaviortlock.ui.BlockTheme
import com.block.behaviortlock.ui.DeviceStatusContent
import com.block.behaviortlock.ui.HoldPreviewRow
import com.block.behaviortlock.ui.OnboardingScreen
import com.block.behaviortlock.ui.SplashScreen
import com.block.behaviortlock.ui.DiagnosticsContent
import com.block.behaviortlock.ui.EnrolReviewScreen
import com.block.behaviortlock.ui.TestRoutineScreen
import com.block.behaviortlock.ui.RecorderMode
import com.block.behaviortlock.ui.RecorderScreen
import com.block.behaviortlock.ui.SectionCard
import com.block.behaviortlock.ui.SegmentIconRow
import com.block.behaviortlock.ui.RoutineTimeline
import com.block.behaviortlock.ui.StatusPill

// Launch phases: splash -> onboarding (first run) -> app.
private const val PHASE_SPLASH = 0
private const val PHASE_ONBOARDING = 1
private const val PHASE_APP = 2

// ---------------------------------------------------------------------------
// MainActivity.kt â€” the app's home and settings screens.
//
// One Activity with a simple screen enum instead of a navigation library. For
// an app this small that is far easier to follow than Navigation Compose, and
// there are no nested sub-screens to get lost in.
// ---------------------------------------------------------------------------

class MainActivity : ComponentActivity() {
    companion object {
        /** Set by LockService when the lock could not run for want of a permission. */
        const val EXTRA_SHOW_PERMISSION_PROMPT = "show_permission_prompt"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BlockTheme {
                AppRoot()
            }
        }
    }
}

/** One decimal place, for angles shown to the user. */
private fun fmt1(v: Float): String = String.format("%.1f", v)

/** The screens in this app. Flat by design - no screen has a sub-screen. */
enum class Screen { HOME, RECORD, SETTINGS, DIAGNOSTICS, STATUS, SETUP }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    var setupDone by remember { mutableStateOf(vm.setupDone) }

    val context = LocalContext.current
    val sensorManager = remember {
        context.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
    }
    val recorder = remember { Recorder(sensorManager) }
    val probe = remember { SensorProbe(sensorManager) }

    // Launch flow: splash -> onboarding (first run only) -> the app.
    //
    // `phase` only ever moves forward, so rotating the device or returning
    // from system Settings never dumps the user back onto the splash screen.
    var phase by remember {
        mutableIntStateOf(
            if (vm.setupDone) PHASE_APP else PHASE_SPLASH
        )
    }

    if (phase == PHASE_SPLASH) {
        SplashScreen { phase = PHASE_ONBOARDING }
        return
    }

    if (phase == PHASE_ONBOARDING) {
        OnboardingScreen(
            onFinished = {
                vm.completeSetup()
                phase = PHASE_APP
            },
            onRecordRoutine = {
                vm.completeSetup()
                phase = PHASE_APP
                screen = Screen.RECORD
            }
        )
        return
    }

    // Run the live sensor probe only while the Diagnostics screen is open.
    DisposableEffect(screen) {
        if (screen == Screen.DIAGNOSTICS) probe.start() else probe.stop()
        onDispose { probe.stop() }
    }

    // The recorder is a full-screen experience — no app bar, no title.
    if (screen == Screen.RECORD) {
        when (screen) {
            Screen.RECORD -> RecorderRoute(vm, recorder) { screen = Screen.HOME }
            else -> Unit
        }
        return
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        when (screen) {
                            Screen.HOME -> "Behavioural Lock"
                            Screen.RECORD -> "Set up routine"
                            Screen.SETTINGS -> "Settings"
                            Screen.DIAGNOSTICS -> "Diagnostics"
                            Screen.STATUS -> "Device status"
                            Screen.SETUP -> "Welcome"
                        }
                    )
                },
                navigationIcon = {
                    if (screen != Screen.HOME) {
                        IconButton(onClick = { screen = Screen.HOME }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (screen) {
                Screen.HOME -> HomeContent(vm) { screen = it }
                Screen.RECORD -> RecorderRoute(vm, recorder) { screen = Screen.HOME }
                Screen.SETTINGS -> SettingsContent(vm)
                Screen.DIAGNOSTICS -> DiagnosticsContent(recorder)
                Screen.STATUS -> DeviceStatusContent(recorder)
                Screen.SETUP -> SetupContent(vm) { screen = Screen.HOME }
            }
        }
    }
}

@Composable
private fun HomeContent(vm: AppViewModel, navigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val routineUsable = vm.isRoutineUsable()
    val problem = vm.routineProblem()
    // Re-checked whenever the app returns to the foreground, so granting the
    // permission in system Settings is reflected immediately.
    var overlayGranted by remember { mutableStateOf(LockOverlay.canDraw(context)) }
    LifecycleResumeEffect(Unit) {
        overlayGranted = LockOverlay.canDraw(context)
        onPauseOrDispose { }
    }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionCard(
                title = "Lock status",
                subtitle = if (vm.lockEnabled) "The phone locks when the screen turns off"
                else "The lock is currently off"
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = if (vm.lockEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (vm.lockEnabled) "  Active" else "  Off",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = vm.lockEnabled,
                            onCheckedChange = { vm.updateLockEnabled(it) },
                            enabled = vm.lockEnabled || routineUsable
                        )
                    }
                    if (!routineUsable && !vm.lockEnabled && problem != null) {
                        Text(
                            problem,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }
        }

        // A permanent, unmissable warning when the lock is switched on but
        // cannot actually run. Without this the lock just silently never
        // appears, which looks exactly like a broken app.
        if (vm.lockEnabled && !overlayGranted) {
            item {
                SectionCard(title = "Lock cannot run yet") {
                    Column {
                        Text(
                            "The lock needs permission to draw over other apps. " +
                                "Without it there is nothing to show when the " +
                                "screen turns on.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        androidx.compose.material3.TextButton(
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}")
                                    )
                                )
                            },
                            modifier = Modifier.padding(top = 6.dp)
                        ) {
                            Text("Grant permission")
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionCard(
                    icon = Icons.Default.Fingerprint,
                    title = "Set up routine",
                    subtitle = "Choose your gestures",
                    modifier = Modifier.weight(1f),
                    onClick = { navigate(Screen.RECORD) }
                )
                ActionCard(
                    icon = Icons.Default.PlayArrow,
                    title = "Lock now",
                    subtitle = if (overlayGranted) "Test your lock" else "Permission needed",
                    enabled = vm.lockEnabled && routineUsable,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        // If the overlay permission is missing we cannot draw the
                        // lock at all. Send the user straight to the screen that
                        // grants it instead of appearing to do nothing.
                        if (!overlayGranted) {
                            context.startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                            )
                        } else {
                            LockService.showLockNow(context)
                        }
                    }
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionCard(
                    icon = Icons.Default.Settings,
                    title = "Settings",
                    subtitle = "Security and options",
                    modifier = Modifier.weight(1f),
                    onClick = { navigate(Screen.SETTINGS) }
                )
                ActionCard(
                    icon = Icons.Default.Shield,
                    title = "Device status",
                    subtitle = "Permissions",
                    modifier = Modifier.weight(1f),
                    onClick = { navigate(Screen.STATUS) }
                )
            }
        }

        item {
            SectionCard(
                title = "Current routine",
                subtitle = if (vm.enrolledTrace == null) "No routine set up yet"
                else "${vm.enrolledEvents.size} action(s) detected"
            ) {
                if (vm.enrolledEvents.isEmpty()) {
                    Text(
                        "No routine set up yet. Add one to start.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else {
                    Column {
                        // Icons instead of plain text, so the routine is scannable.
                        SegmentIconRow(vm.enrolledEvents)
                        Spacer(Modifier.height(16.dp))
                        // A timeline showing when each action happened, and how
                        // long the pause before it was.
                        RoutineTimeline(vm.enrolledEvents)
                        Spacer(Modifier.height(16.dp))

                        // Show how the phone should be held, drawn at the angle the routine was
                        // actually enrolled at, plus whether the factor is live.
                        when (val grip = vm.enrolledGrip) {
                            is AppViewModel.GripInfo.None -> HoldPreviewRow(
                                pitchDeg = null, rollDeg = null, wobbleDeg = null
                            )
                            is AppViewModel.GripInfo.Enrolled -> HoldPreviewRow(
                                pitchDeg = grip.pitch,
                                rollDeg = grip.roll,
                                wobbleDeg = grip.jitter
                            )
                        }

                        // Make the fix one tap away rather than something the
                        // user has to work out on their own.
                        if (vm.enrolledGrip is AppViewModel.GripInfo.None) {
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { navigate(Screen.RECORD) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Re-record while holding the phone")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupContent(vm: AppViewModel, done: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Unlock your phone the way you actually do things.",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            "Behavioural Lock records how you hold your phone and the exact way " +
                "you tap, swipe and draw, then only lets you in when you repeat " +
                "that routine. It never uses your camera, microphone or network.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SectionCard(title = "Three steps") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1.  Allow the app to lock your phone", style = MaterialTheme.typography.bodyLarge)
                Text("2.  Record your routine (3 takes per step)", style = MaterialTheme.typography.bodyLarge)
                Text("3.  Turn the lock on", style = MaterialTheme.typography.bodyLarge)
            }
        }
        androidx.compose.material3.Button(
            onClick = { vm.completeSetup(); done() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Get started")
        }
    }
}

/**
 * The enrolled hold as (pitch, roll, wobble), or null when the routine was
 * recorded flat and there is no usable hold reference.
 */
private fun gripOf(vm: AppViewModel): Triple<Float, Float, Float>? =
    (vm.enrolledGrip as? AppViewModel.GripInfo.Enrolled)?.let {
        Triple(it.pitch, it.roll, it.jitter)
    }

/**
 * The enrolment route: shows the full-screen recorder, saves what was
 * recorded, and then lets the user test it straight away.
 *
 * "Record once, then test it" - because an enrolment that was never tested is
 * the single most common reason a user thinks the lock is broken.
 */
@Composable
private fun RecorderRoute(
    vm: AppViewModel,
    recorder: com.block.behaviortlock.Recorder,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    var stage by remember { mutableStateOf(0) }   // 0 = record, 1 = review, 2 = test
    var testResult by remember { mutableStateOf<SessionResult?>(null) }

    /**
     * True while recording an ADDITIONAL take rather than the first one.
     *
     * The first take replaces the routine entirely; later ones are added to it,
     * so a user who just wants to change their routine must not have three
     * stale takes carried along with it.
     */
    var addingTake by remember { mutableStateOf(false) }
    var takeMessage by remember { mutableStateOf<String?>(null) }

    /**
     * Bumped after every saved take, which recreates the recorder screen.
     *
     * The recorder keeps its own state (running / stopped / countdown). After
     * the FIRST take the stage changes to the review screen, so the recorder is
     * disposed and comes back fresh. After takes two and three the stage stays
     * on the recorder, so it was never disposed and was left showing
     * "Recording stopped" from the take that had just been saved.
     */
    var recorderEpoch by remember { mutableIntStateOf(0) }

    // Every return to the recording screen wipes the previous attempt, so an
    // old drawing can never linger on the canvas.
    fun backToRecording() {
        recorder.clear()
        testResult = null
        stage = 0
    }

    when (stage) {
        // key() recreates the recorder screen whenever a take is saved, so it
        // never carries "Recording stopped" over from the previous attempt.
        0 -> key(recorderEpoch) { RecorderScreen(
            recorder = recorder,
            mode = RecorderMode.ENROLL,
            enrolled = emptyList(),
            promptTitle = if (addingTake)
                "Take ${vm.totalTakes() + 1} of ${AppViewModel.MAX_TAKES}"
            else "Enroll now",
            promptBody = if (addingTake)
                "Perform the same routine again, in the same order.\n" +
                    "Doing it a few times teaches the lock your normal variation."
            else "Perform your whole routine in one go —\ntap, draw, swipe, shake, whatever you like.",
            onFinished = { trace ->
                if (trace.touches.isEmpty()) {
                    recorder.clear()
                    recorderEpoch++
                    return@RecorderScreen
                }
                if (addingTake) {
                    when (val r = vm.addTake(trace)) {
                        is AppViewModel.Result.Ok -> {
                            takeMessage = "Take ${r.count} of ${AppViewModel.MAX_TAKES} saved."
                        }
                        is AppViewModel.Result.Failed -> takeMessage = r.why
                    }
                    recorder.clear()
                    recorderEpoch++
                    // Straight on to the next take. Three recordings are
                    // mandatory, so there is no decision to make between them
                    // and no chance to stop at one and think it is done.
                    if (vm.takeCount < AppViewModel.MAX_TAKES) {
                        addingTake = true
                        stage = 0
                    } else {
                        addingTake = false
                        stage = 1   // 3 of 3: the summary
                    }
                } else {
                    vm.enroll(trace)
                    recorder.clear()
                    recorderEpoch++
                    // Chain into take 2 exactly as takes 2 and 3 chain into each
                    // other. The first take used to stop at the summary, which
                    // meant it alone needed a manual tap to reach take 2.
                    if (vm.takeCount < AppViewModel.MAX_TAKES) {
                        addingTake = true
                        stage = 0
                    } else {
                        addingTake = false
                        stage = 1
                    }
                }
            },
            onCancel = onExit
        ) }

        1 -> EnrolReviewScreen(
            summary = vm.enrolledSummary,
            events = vm.enrolledEvents,
            holdPitch = gripOf(vm)?.first,
            holdRoll = gripOf(vm)?.second,
            holdWobble = gripOf(vm)?.third,
            takeCount = vm.takeCount,
            maxTakes = AppViewModel.MAX_TAKES,
            recommendedTakes = AppViewModel.RECOMMENDED_TAKES,
            onAddTake = {
                if (vm.takeCount < AppViewModel.MAX_TAKES) {
                    recorder.clear()
                    addingTake = true
                    takeMessage = null
                    stage = 0
                }
            },
            infoMessage = takeMessage,
            onRemoveTake = { vm.removeTake(it) },
            onRecordAgain = { backToRecording() },
            onTest = {
                // The enrolment recording is no longer needed; free it so the
                // test screen starts from a clean buffer.
                recorder.clear()
                stage = 2
            },
            onDone = { LockService.stop(context) }
        )

        else -> {
            // Tell the recorder what to score against before showing the screen.
            LaunchedEffect(Unit) {
                recorder.enrolledSession = vm.enrolledTrace
                    ?.let { AutoSegmenter.segment(it) } ?: DetectedSession()
            }
            TestRoutineScreen(
                recorder = recorder,
                enrolled = vm.enrolledEvents,
                result = testResult,
                onResult = { testResult = it },
                onBack = { stage = 1; testResult = null },
                onFinish = onExit
            )
        }
    }
}
@Composable
private fun SettingsContent(vm: AppViewModel) {
    val context = LocalContext.current
    val isOwner = remember { AdminReceiver.isDeviceOwner(context) }
    val isAdmin = remember { AdminReceiver.isAdminActive(context) }
    val packageName = context.packageName
    var overlayGranted by remember { mutableStateOf(LockOverlay.canDraw(context)) }
    // Re-check when the user returns from the system permission screen.
    LifecycleResumeEffect(Unit) {
        overlayGranted = LockOverlay.canDraw(context)
        onPauseOrDispose { }
    }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionCard(title = "Lock", subtitle = "How the phone locks") {
                Column {
                    SettingSwitch(
                        "Enable behavioural lock",
                        "Lock the phone when the screen turns off",
                        vm.lockEnabled
                    ) { vm.updateLockEnabled(it) }

                    HorizontalDivider(Modifier.padding(vertical = 10.dp))

                    SettingSwitch(
                        "Strong mode",
                        if (isOwner) "Disable the system lock entirely"
                        else "Needs device-owner setup via USB â€” see README",
                        vm.strongMode,
                        enabled = isAdmin
                    ) { vm.updateStrongMode(it) }
                }
            }
        }

        item {
            SectionCard(title = "Security", subtitle = "How strict the match is") {
                Column {
                    Text("Security level", style = MaterialTheme.typography.titleMedium)
                    listOf("Relaxed", "Standard", "Strict").forEach { level ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = vm.securityLevel == level,
                                onClick = { vm.updateSecurityLevel(level) }
                            )
                            Text("  $level", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    SettingSwitch(
                        "Lock immediately on screen off",
                        "Off = ${vm.lockDelaySeconds}s delay",
                        vm.lockDelaySeconds == 0
                    ) { on ->
                        vm.updateLockDelay(if (on) 0 else 30)
                    }
                }
            }
        }

        item {
            SectionCard(title = "Routine", subtitle = "How your gesture is recorded") {
                Column {
                    SettingSwitch(
                        "String mode",
                        "Treat the whole recording as one unbroken stroke, " +
                            "like a pattern lock",
                        vm.stringMode
                    ) { vm.updateStringMode(it) }
                    if (vm.stringMode) {
                        Text(
                            "Changing this clears your saved routine, so record a new one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = "Saved routine") {
                Column {
                    Text(
                        if (vm.enrolledTrace == null) "No routine set up"
                        else vm.enrolledSummary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Say plainly whether the "hold it the same way" factor is
                    // actually active. A routine enrolled with the phone flat on
                    // a desk has no hold reference, and without this the user
                    // has no way to tell that from a broken feature.
                    if (vm.enrolledTrace != null) {
                        Spacer(Modifier.height(10.dp))
                        when (val grip = vm.enrolledGrip) {
                            is AppViewModel.GripInfo.None -> {
                                Text(
                                    "Hold angle: NOT ENROLLED — the routine was " +
                                        "recorded with the phone lying flat, so the " +
                                        "angle you hold it at is not checked. Re-record " +
                                        "it while holding the phone.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            is AppViewModel.GripInfo.Enrolled -> {
                                Text(
                                    "Hold angle: tilt ${String.format("%.0f", grip.pitch)}° / " +
                                        "${String.format("%.0f", grip.roll)}°, " +
                                        "wobble ±${String.format("%.1f", grip.jitter)}° " +
                                        "(checked within 20°)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Device status") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusLine("Device admin", if (isAdmin) "granted" else "not granted")
                    StatusLine("Device owner (strong mode)", if (isOwner) "active" else "not active")
                    StatusLine("App version", "1.0")
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        StatusPill(
            value,
            if (value == "granted" || value == "active") MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
