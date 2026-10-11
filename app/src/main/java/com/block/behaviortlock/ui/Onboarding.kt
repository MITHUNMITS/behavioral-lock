package com.block.behaviortlock.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.block.behaviortlock.AdminReceiver
import com.block.behaviortlock.LockOverlay

// ---------------------------------------------------------------------------
// Onboarding.kt — the first-run walkthrough.
//
// It runs once, on first launch, and it is deliberately short. The most
// important job it does is collect the two permissions up front, because the
// lock silently does nothing without the overlay permission and that is the
// single most confusing failure the app can have.
//
// Every permission row shows its LIVE state, so the user can see each step
// complete as they grant it. Nothing here can trap the user: "Skip for now"
// is always available and the app still works, it just cannot lock yet.
//
// Layout: only the step body scrolls. The header and the buttons are fixed,
// so "Next" is always reachable no matter how long the step is.
// ---------------------------------------------------------------------------

private const val STEPS = 3

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    onRecordRoutine: () -> Unit
) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }

    // Re-read the permission state every time we come back to the foreground,
    // because the user grants them in system Settings and returns here.
    var overlayGranted by remember { mutableStateOf(LockOverlay.canDraw(context)) }
    var adminGranted by remember { mutableStateOf(AdminReceiver.isAdminActive(context)) }
    LifecycleResumeEffect(Unit) {
        overlayGranted = LockOverlay.canDraw(context)
        adminGranted = AdminReceiver.isAdminActive(context)
        onPauseOrDispose { }
    }

    val lastStep = step == STEPS - 1

    Column(
        Modifier
            .fillMaxSize()
            // Paint our own background rather than relying on the window's.
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp)
            .padding(top = 40.dp, bottom = 20.dp)
    ) {
        // ---- Fixed header: progress dots ----
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(STEPS) { i ->
                val active by animateFloatAsState(
                    targetValue = if (i <= step) 1f else 0.25f,
                    animationSpec = tween(250),
                    label = "dot"
                )
                Box(
                    Modifier
                        .size(if (i == step) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = active))
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ---- Scrolling step body ----
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            when (step) {
                0 -> WelcomeStep()
                1 -> PermissionStep(
                    overlayGranted = overlayGranted,
                    adminGranted = adminGranted,
                    onGrantOverlay = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    },
                    onGrantAdmin = {
                        context.startActivity(
                            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(
                                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                    ComponentName(context, AdminReceiver::class.java)
                                )
                            }
                        )
                    }
                )
                else -> ReadyStep(
                    overlayGranted = overlayGranted,
                    onRecord = onRecordRoutine
                )
            }

            Spacer(Modifier.height(16.dp))
        }

        // The overlay permission is the one thing that makes the lock work at
        // all, so this step cannot be advanced past until it is granted.
        if (step == 1 && !overlayGranted) {
            Text(
                "Grant \"Display over other apps\" to continue — the lock " +
                    "cannot appear without it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(12.dp))
        }

        // ---- Fixed navigation ----
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onFinished) {
                Text("Skip for now", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = { if (lastStep) onFinished() else step += 1 },
                enabled = if (step == 1) overlayGranted else true
            ) {
                Text(if (lastStep) "Go to app" else "Next")
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    OnboardingIcon(Icons.Default.Fingerprint)
    Text(
        "Unlock with how you behave",
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(top = 20.dp)
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "Instead of a PIN you have to remember, record a short routine — tap, " +
            "draw, swipe, shake — and repeat it to unlock. The app checks the " +
            "shape of what you drew, how fast, and the pauses between actions.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(20.dp))
    SectionCard(title = "Your privacy") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrivacyLine("No camera", "never requested")
            PrivacyLine("No microphone", "never requested")
            PrivacyLine("No network", "works entirely offline")
            PrivacyLine("No location", "never requested")
        }
    }
}

@Composable
private fun PermissionStep(
    overlayGranted: Boolean,
    adminGranted: Boolean,
    onGrantOverlay: () -> Unit,
    onGrantAdmin: () -> Unit
) {
    OnboardingIcon(Icons.Default.Shield)
    Text(
        "Two permissions",
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(top = 20.dp)
    )
    Text(
        "Both are needed before the lock can run. Tap each one to grant it; " +
            "the tick appears as soon as it is done.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )

    Spacer(Modifier.height(20.dp))

    SectionCard(title = "Required") {
        Column {
            PermissionRow(
                "Display over other apps",
                "Lets the lock draw over the screen",
                overlayGranted,
                "Grant",
                onGrantOverlay
            )
            Spacer(Modifier.height(14.dp))
            PermissionRow(
                "Device administrator",
                "Lets the app lock the phone and recover it",
                adminGranted,
                "Activate",
                onGrantAdmin
            )
        }
    }
}

@Composable
private fun ReadyStep(
    overlayGranted: Boolean,
    onRecord: () -> Unit
) {
    OnboardingIcon(Icons.Default.Lock)
    Text(
        "Record your routine",
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(top = 20.dp)
    )
    Text(
        if (overlayGranted)
            "One last step. Perform a short sequence of actions in one go — " +
                "for example tap three times, draw a shape, then shake. The " +
                "app works out what each action was."
        else
            "You have not granted the overlay permission yet, so the lock " +
                "cannot run. Go back and grant it, then record your routine.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
    Spacer(Modifier.height(20.dp))
    Button(onClick = onRecord, modifier = Modifier.fillMaxWidth()) {
        Text("Record my routine")
    }
}

// ---- small shared pieces ---------------------------------------------------

@Composable
private fun OnboardingIcon(icon: ImageVector) {
    Box(
        Modifier
            .size(84.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(40.dp)
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    buttonLabel: String,
    onClick: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (granted) GREEN.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (granted) Icons.Default.Check else Icons.Default.Visibility,
                contentDescription = if (granted) "Granted" else "Not granted",
                tint = if (granted) GREEN else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!granted) {
            OutlinedButton(onClick = onClick) { Text(buttonLabel) }
        }
    }
}

@Composable
private fun PrivacyLine(label: String, note: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            "  $label — ",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            note,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private val GREEN = Color(0xFF22C55E)