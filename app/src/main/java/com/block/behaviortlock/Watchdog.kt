package com.block.behaviortlock

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

// ---------------------------------------------------------------------------
// Watchdog.kt — the safety net. This is the single most important file for
// reliability: it guarantees the phone can NEVER be left locked with no way in.
//
// The rule: never disable the system keyguard without arming this alarm first.
// If the app crashes, is force-stopped, or the process dies while the keyguard
// is disabled, the alarm fires shortly after and turns the system lock back on,
// so the user's real PIN works again.
// ---------------------------------------------------------------------------

class Watchdog : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_RESTORE) {
            restoreSystemLock(context)
        }
    }

    companion object {
        private const val TAG = "BHT/Watchdog"
        const val ACTION_RESTORE = "com.block.behaviortlock.RESTORE_SYSTEM_LOCK"

        /** How long after arming we assume the app has died and restore the lock. */
        const val RESTORE_DELAY_MS = 45_000L

        /** Arm the safety alarm. Always call BEFORE disabling the keyguard. */
        fun arm(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = restorePendingIntent(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Do not wake the device from doze — the point of this alarm is
                // to run even when the app is not running.
                alarm.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + RESTORE_DELAY_MS,
                    pending
                )
            } else {
                alarm.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + RESTORE_DELAY_MS,
                    pending
                )
            }
            Log.d(TAG, "Safety alarm armed — system lock will be restored in ${RESTORE_DELAY_MS / 1000}s")
        }

        /** Cancel the safety alarm. Only call this when the app is healthy. */
        fun disarm(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.cancel(restorePendingIntent(context))
        }

        /**
         * Put the phone back on the normal system lock.
         *
         * Safe to call even when strong mode was never enabled — it simply
         * re-enables a keyguard that was already enabled.
         */
        fun restoreSystemLock(context: Context) {
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(context, AdminReceiver::class.java)

                if (dpm.isAdminActive(admin)) {
                    // Remove our overlay before restoring anything, so the user
                    // is never left with a screen they cannot dismiss.
                    LockOverlay.dismiss()
                    // Stop our lock service so it cannot re-show the lock screen
                    // on top of the restored system lock.
                    context.stopService(Intent(context, LockService::class.java))
                    dpm.setKeyguardDisabled(admin, false)
                    Log.d(TAG, "System keyguard restored — phone unlocked with the normal PIN")
                } else {
                    // No admin rights, but our overlay must still come down,
                    // otherwise the phone is unusable.
                    LockOverlay.dismiss()
                    Log.d(TAG, "No admin rights; overlay dismissed so the phone is usable")
                }
            } catch (t: Throwable) {
                // Last-resort fallback that needs no admin rights at all.
                Log.e(TAG, "Could not restore keyguard via DPM", t)
            }
        }

        private fun restorePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, Watchdog::class.java).apply {
                action = ACTION_RESTORE
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private const val REQUEST_CODE = 4201
    }
}