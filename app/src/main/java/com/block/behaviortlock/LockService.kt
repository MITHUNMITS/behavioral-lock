package com.block.behaviortlock

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log

// ---------------------------------------------------------------------------
// LockService.kt — keeps the app alive and notices when the screen turns on.
//
// Why a foreground service: on this POCO (MIUI) the system aggressively kills
// background apps. A foreground service with a visible notification is the only
// reliable way to stay running so the lock can catch the screen turning on.
//
// Note: ACTION_SCREEN_ON / ACTION_SCREEN_OFF are protected broadcasts, so they
// CANNOT be declared in the manifest. They must be registered at runtime, which
// is exactly what onStartCommand does.
// ---------------------------------------------------------------------------

class LockService : Service() {

    private val TAG = "BHT/LockService"

    private var screenReceiverRegistered = false

    /** Receives screen on/off while the service runs. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen on")
                    onScreenOn()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen off")
                    screenOffAt = System.currentTimeMillis()
                    // Arm the safety net the moment the phone locks.
                    val profile = ProfileStore.load(context)
                    if (profile.lockEnabled) {
                        Watchdog.arm(context)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        if (!screenReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            // API 33+ requires an explicit export flag on runtime receivers.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
            screenReceiverRegistered = true
        }

        handleIntent(intent)
        // Restart if the system kills us.
        return START_STICKY
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return
            }
            ACTION_SHOW_LOCK -> onScreenOn()
        }
    }

    private fun onScreenOn() {
        val profile = ProfileStore.load(this)
        if (!profile.lockEnabled) return

        // SAFETY: without device-admin we have no PIN fallback underneath, so
        // showing a lock we cannot guarantee to unlock could strand the user.
        // In that case we only show the lock if the routine is actually usable.
        val admin = AdminReceiver.isAdminActive(this)
        val routineUsable = profile.enrolledTrace != null &&
            profile.enrolledSession().events.size >= 2
        if (!admin && !routineUsable) {
            Log.w(TAG, "Lock on but not usable and no admin rights — not locking")
            return
        }

        // Respect the user's "lock after screen off" delay. 0 = immediately.
        if (profile.lockDelaySeconds > 0) {
            val sinceOff = System.currentTimeMillis() - screenOffAt
            if (sinceOff < profile.lockDelaySeconds * 1000L) return
        }

        showLockUi()
    }

    private fun showLockUi() {
        // Strong mode (device owner): the keyguard is disabled, so our Activity
        // genuinely is the lock screen and unlocking it works.
        //
        // Base mode (any phone): we CANNOT dismiss the Android keyguard, so we
        // do not try. Instead we draw a full-screen overlay on top of whatever
        // is showing and remove it ourselves when the gesture matches.
        if (AdminReceiver.isDeviceOwner(this)) {
            showLockActivity()
            return
        }
        if (!LockOverlay.canDraw(this)) {
            // Do NOT fail silently. Previously this returned quietly, so
            // pressing "Lock now" appeared to do nothing at all with no clue
            // why. Tell the user exactly what is missing and open the screen
            // where they can grant it.
            Log.w(TAG, "Overlay permission not granted — not locking")
            notifyPermissionMissing()
            return
        }
        LockOverlay.show(this)
    }

    /**
     * Tell the user the lock cannot run because the overlay permission is
     * missing, and point them at the settings screen that fixes it.
     */
    private fun notifyPermissionMissing() {
        try {
            // Foreground so the notice is visible immediately.
            startForegroundCompat()

            val open = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_SHOW_PERMISSION_PROMPT, true)
            }
            val pending = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, LockApp.CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val notification = builder
                .setContentTitle("Lock needs one permission")
                .setContentText("Tap to allow \"Display over other apps\"")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pending)
                .setOngoing(true)
                .build()

            // The string form is used deliberately: the typed getSystemService
            // overload does not resolve reliably in this Service subclass.
            val manager = getSystemService("notification") as? NotificationManager
            manager?.notify(NOTIFICATION_ID_PERMISSION, notification)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not show permission prompt", t)
        }
    }

    private fun showLockActivity() {
        val intent = Intent(this, LockActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Required to start an activity from the background on Android 10+.
            startActivity(intent)
        } else {
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        LockOverlay.dismiss()
        if (screenReceiverRegistered) {
            try { unregisterReceiver(screenReceiver) } catch (_: Throwable) { }
            screenReceiverRegistered = false
        }
        super.onDestroy()
    }

    // -- foreground notification ------------------------------------------------

    private fun startForegroundCompat() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, LockApp.CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Remembered so the "lock after delay" option can work. */
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_ID_PERMISSION = 1002
        const val ACTION_STOP = "com.block.behaviortlock.STOP"
        const val ACTION_SHOW_LOCK = "com.block.behaviortlock.SHOW_LOCK"

        @Volatile var screenOffAt: Long = 0L

        fun start(context: Context) {
            val intent = Intent(context, LockService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LockService::class.java))
        }

        fun showLockNow(context: Context) {
            context.startService(Intent(context, LockService::class.java).apply {
                action = ACTION_SHOW_LOCK
            })
        }
    }
}
