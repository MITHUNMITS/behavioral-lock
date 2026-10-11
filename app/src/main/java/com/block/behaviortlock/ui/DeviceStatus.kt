package com.block.behaviortlock.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.block.behaviortlock.AdminReceiver
import com.block.behaviortlock.LockOverlay

// ---------------------------------------------------------------------------
// DeviceStatus.kt — one screen that answers "why is the lock not working?".
//
// Every permission the app needs is listed with its current state and a button
// to fix it. This exists because the lock silently refusing to appear (because
// the overlay permission was missing) was the single most confusing failure.
// Now the user can always see WHY, and fix it in two taps.
// ---------------------------------------------------------------------------

/** One permission/status row and its state. */
data class StatusItem(
    val title: String,
    val explanation: String,
    val granted: Boolean,
    val required: Boolean,
    val fixLabel: String = "Grant",
    val fix: (() -> Unit)? = null,
    val optional: String? = null
)

@Composable
fun DeviceStatusContent(recorder: com.block.behaviortlock.Recorder) {
    val context = LocalContext.current

    val adminActive = AdminReceiver.isAdminActive(context)
    val deviceOwner = AdminReceiver.isDeviceOwner(context)
    val overlay = LockOverlay.canDraw(context)

    val batterySaverIgnored = run {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    // Does the phone have its own PIN/pattern set? Without one the user has no
    // fallback at all, which matters a lot.
    val keyguardSecure = run {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        km.isDeviceSecure
    }

    val items = listOf(
        StatusItem(
            title = "Display over other apps",
            explanation = "Lets the behavioural lock draw over the screen. " +
                "Without this the lock cannot appear at all.",
            granted = overlay,
            required = true,
            fixLabel = "Open settings",
            fix = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
        ),
        StatusItem(
            title = "Device administrator",
            explanation = "Allows the app to lock the phone and restore the " +
                "system lock if anything goes wrong.",
            granted = adminActive,
            required = false,
            fixLabel = "Activate",
            fix = {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(
                        DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        ComponentName(context, AdminReceiver::class.java)
                    )
                }
                try { context.startActivity(intent) } catch (_: Throwable) { }
            }
        ),
        StatusItem(
            title = "Device owner (strong mode)",
            explanation = "Disables the system lock so the app becomes the only " +
                "lock. Needs a factory-reset phone and one USB command.",
            granted = deviceOwner,
            required = false,
            optional = if (deviceOwner) "Strong mode available"
            else "See README to enable"
        ),
        StatusItem(
            title = "Phone PIN or pattern set",
            explanation = "Your normal phone unlock is the safety fallback. " +
                "Strongly recommended.",
            granted = keyguardSecure,
            required = false,
            optional = if (keyguardSecure) null else "No fallback if you get locked out",
            fixLabel = "Set one",
            fix = {
                context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
        ),
        StatusItem(
            title = "Ignore battery optimisation",
            explanation = "Stops the system killing the lock service in the " +
                "background, which is common on Xiaomi phones.",
            granted = batterySaverIgnored,
            required = false,
            fixLabel = "Allow",
            fix = {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                try { context.startActivity(intent) } catch (_: Throwable) { }
            }
        )
    )

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionCard(
                title = "Device status",
                subtitle = items.count { it.granted }.toString() +
                    " of ${items.size} ready"
            ) {
                Text(
                    "Every item the lock needs is listed here. Fix anything marked " +
                        "in red — that is almost always why the lock did not appear.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(items.size) { index ->
            val item = items[index]
            StatusCard(item)
        }

        item {
            SectionCard(title = "Sensors on this phone") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SensorLine("Accelerometer", recorder.hasAccelerometer)
                    SensorLine("Gyroscope", recorder.hasGyroscope)
                    SensorLine("Gravity", recorder.hasGravity)
                    SensorLine("Rotation vector", recorder.hasRotationVector)
                    SensorLine("Linear acceleration", recorder.hasLinearAcceleration)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(item: StatusItem) {
    val color =
        if (item.granted) MaterialTheme.colorScheme.primary
        else if (item.required) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant

    SectionCard(title = item.title) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (item.granted) Icons.Default.CheckCircle
                    else Icons.Default.Warning,
                    contentDescription = if (item.granted) "Granted" else "Not granted",
                    tint = color
                )
                Text(
                    if (item.granted) "  Ready" else "  ${item.optional ?: "Not set up"}",
                    style = MaterialTheme.typography.titleMedium,
                    color = color
                )
            }
            Text(
                item.explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            if (!item.granted && item.fix != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { item.fix.invoke() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(item.fixLabel)
                }
            }
        }
    }
}

@Composable
private fun SensorLine(name: String, available: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        Text(
            if (available) "available" else "not available",
            style = MaterialTheme.typography.bodyMedium,
            color = if (available) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}