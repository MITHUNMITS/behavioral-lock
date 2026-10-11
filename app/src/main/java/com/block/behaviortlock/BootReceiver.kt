package com.block.behaviortlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// ---------------------------------------------------------------------------
// BootReceiver.kt — after a reboot, re-arm the lock.
//
// Note: after a reboot Android always shows the real system PIN once before
// third-party locks take over. That is expected and is by design — it means a
// factory reset or a stolen phone is still protected by the system credential.
// ---------------------------------------------------------------------------

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val profile = ProfileStore.load(context)
        if (!profile.lockEnabled) return
        Log.d("BHT/Boot", "Reboot detected — restarting lock service")
        LockService.start(context)
    }
}