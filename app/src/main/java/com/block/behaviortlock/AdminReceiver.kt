package com.block.behaviortlock

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast

// ---------------------------------------------------------------------------
// AdminReceiver.kt — handles device-admin permission and the two lock tiers.
//
//  Base mode  (any phone): the app calls DevicePolicyManager.lockNow() and then
//  shows LockActivity on top of the system lock. The user's real PIN stays
//  underneath as the backstop.
//
//  Strong mode (device owner only, set up via adb): the system keyguard is
//  disabled entirely so our lock is the only lock. The Watchdog re-enables the
//  keyguard if this app ever stops responding, so the phone is never bricked.
// ---------------------------------------------------------------------------

class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        val profile = ProfileStore.load(context)
        ProfileStore.save(context, profile.copy(setupDone = true))
        Toast.makeText(context, "Behavioural Lock is now allowed to lock the phone", Toast.LENGTH_LONG).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        // If the user revokes admin rights, always hand the phone back to the
        // system lock immediately — we must never hold the phone locked with no
        // way for the user to get in.
        Watchdog.restoreSystemLock(context)
        Toast.makeText(context, "Behavioural Lock turned off — system lock restored", Toast.LENGTH_LONG).show()
    }

    companion object {
        fun isAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, AdminReceiver::class.java)
            return dpm.isAdminActive(admin)
        }

        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, AdminReceiver::class.java)
            return dpm.isDeviceOwnerApp(admin.packageName)
        }

        /** Strong mode: disable the system keyguard so only our lock shows. */
        fun enableStrongMode(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, AdminReceiver::class.java)
            return try {
                // Arm the watchdog FIRST. If anything below throws, the alarm is
                // already scheduled to restore the keyguard.
                Watchdog.arm(context)
                dpm.setKeyguardDisabled(admin, true)
                true
            } catch (t: Throwable) {
                Watchdog.restoreSystemLock(context)
                false
            }
        }

        /** Base mode is always available once admin is granted. */
        fun lockNow(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, AdminReceiver::class.java)
            try {
                dpm.lockNow()
            } catch (t: Throwable) {
                // Some ROMs refuse lockNow from a non-owner admin. We still show
                // LockActivity, so the lock screen itself still appears.
            }
        }
    }
}