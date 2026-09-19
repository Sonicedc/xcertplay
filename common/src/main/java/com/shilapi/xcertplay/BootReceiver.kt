package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Starts the CarPlay host after boot when the user has enabled the startup option. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        val enabled = AirPlayPersistence.loadAutoStartOnBoot(context)
        Log.i(TAG, "boot action=${intent.action} autoStartEnabled=$enabled")
        if (!enabled) return

        val launch = Intent(context, CarPlayHostActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        try {
            context.startActivity(launch)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Boot auto-start could not launch CarPlayHostActivity", error)
        }
    }

    private companion object {
        const val TAG = "xcertplay-boot"
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
