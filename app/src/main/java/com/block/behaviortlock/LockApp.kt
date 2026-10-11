package com.block.behaviortlock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

// ---------------------------------------------------------------------------
// LockApp.kt — starts the background lock service as soon as the app opens.
//
// The service is what keeps the process alive and what notices the screen
// turning on/off. On MIUI (this is a POCO phone) aggressive background task
// killing is real, which is why it is a foreground service with a visible
// notification.
// ---------------------------------------------------------------------------

class LockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Ensure the lock service is running whenever the app process starts.
        // On MIUI the system kills background services aggressively, and a
        // killed service never sees SCREEN_ON, so the lock silently stops
        // working. Re-asserting it here is what makes the lock reliable.
        //
        // Only when the lock is actually on, so users who have not enabled it
        // do not get a permanent notification.
        try {
            if (ProfileStore.load(this).lockEnabled) LockService.start(this)
        } catch (_: Throwable) { }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "lock_service"
    }
}