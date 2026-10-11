package com.block.behaviortlock

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.block.behaviortlock.ui.BlockTheme
import com.block.behaviortlock.ui.RecorderMode
import com.block.behaviortlock.ui.RecorderScreen
import com.block.behaviortlock.ui.ScoreBar

// ---------------------------------------------------------------------------
// LockActivity.kt — the actual phone lock screen.
//
// It shows the SAME animated recorder used during enrolment, so unlocking
// feels familiar rather than like a different app. Each action turns green as
// it matches, but the phone only opens once the WHOLE routine is done.
//
// Safety properties, all enforced in code:
//   * FLAG_SECURE blocks screenshots and screen recording.
//   * After N failed attempts the system PIN path is offered.
//   * After MAX_LOCK_SECONDS the lock releases itself, so the app can never
//     permanently lock anyone out.
// ---------------------------------------------------------------------------

/** The lock releases itself after this long, so the user is never stranded. */
private const val MAX_LOCK_SECONDS = 45

/** How many tries before we offer the system PIN. */
private const val MAX_ATTEMPTS = 2

class LockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Block screenshots / screen recording / recent-apps preview.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        val profile = ProfileStore.load(this)
        val enrolledSession = profile.enrolledSession()

        setContent {
            BlockTheme {
                LockScreenContent(
                    enrolled = enrolledSession,
                    attemptsBeforePin = profile.attemptsBeforePin.coerceAtLeast(1),
                    onUnlocked = { finish() },
                    onGiveUp = { releasePhone() }
                )
            }
        }
    }

    /**
     * Hand the phone back to the user.
     *
     * If we are a device admin we can restore the real system lock. If we are
     * not, there is no system lock underneath us, so the only safe thing to do
     * is turn our lock off completely and return to the app.
     */
    private fun releasePhone() {
        val admin = AdminReceiver.isAdminActive(this)
        if (admin) {
            Watchdog.restoreSystemLock(this)
            finishAndRemoveTask()
        } else {
            val current = ProfileStore.load(this)
            ProfileStore.save(this, current.copy(lockEnabled = false))
            LockService.stop(this)
            finishAndRemoveTask()
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
        }
    }
}

@Composable
private fun LockScreenContent(
    enrolled: DetectedSession,
    attemptsBeforePin: Int,
    onUnlocked: () -> Unit,
    onGiveUp: () -> Unit
) {
    val context = LocalContext.current
    val sensorManager = remember {
        context.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
    }
    val recorder = remember { Recorder(sensorManager) }

    var unlocked by remember { mutableStateOf(false) }
    var attempts by remember { mutableIntStateOf(0) }
    var showPin by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<SessionResult?>(null) }
    var elapsed by remember { mutableIntStateOf(0) }

    // The routine this recorder scores against.
    LaunchedEffect(Unit) { recorder.enrolledSession = enrolled }

    // SAFETY: never hold the phone longer than this.
    LaunchedEffect(Unit) {
        while (elapsed < MAX_LOCK_SECONDS && !unlocked) {
            kotlinx.coroutines.delay(1000)
            elapsed += 1
        }
        if (!unlocked) onGiveUp()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AnimatedVisibility(visible = unlocked, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(76.dp)
                    )
                    Text(
                        "Unlocked",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }

        if (!unlocked && !showPin) {
            if (enrolled.isEmpty) {
                NoRoutineNotice(onGiveUp)
            } else {
                RecorderScreen(
                    recorder = recorder,
                    mode = RecorderMode.UNLOCK,
                    enrolled = enrolled.events,
                    onFinished = { trace ->
                        val scored = SessionScorer.compare(
                            enrolled,
                            AutoSegmenter.segment(trace)
                        )
                        lastResult = scored
                        if (scored.passed) {
                            unlocked = true
                            Watchdog.disarm(context)
                        } else {
                            attempts += 1
                            if (attempts >= attemptsBeforePin) showPin = true
                        }
                    },
                    onCancel = { showPin = true }
                )
            }
        }

        if (showPin) {
            TooManyAttempts(
                attempts = attempts,
                onUseSystemPin = onGiveUp,
                onRetry = {
                    attempts = 0
                    showPin = false
                    lastResult = null
                }
            )
        }
    }
}

/** Shown when the phone locks but no routine has been enrolled. */
@Composable
private fun NoRoutineNotice(onGiveUp: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Text(
            "No routine set up",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            "The lock is on but there is nothing to match against.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        Button(onClick = onGiveUp, modifier = Modifier.padding(top = 24.dp)) {
            Text("Open the app")
        }
    }
}

/** Offered after too many failed attempts. */
@Composable
private fun TooManyAttempts(
    attempts: Int,
    onUseSystemPin: () -> Unit,
    onRetry: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )
        Text(
            "That didn't match",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            "You have used $attempts attempt(s). You can try again or use your " +
                "normal phone PIN.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        Button(onClick = onUseSystemPin, modifier = Modifier.padding(top = 24.dp)) {
            Text("Use system PIN")
        }
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
            Text("Try again")
        }
    }
}