package com.x.carlauncher.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.x.carlauncher.services.AccOffService

/**
 * Ensures AccOffService is pre-warmed on device boot to monitor
 * automotive Bluetooth OBD2 connections and USB-C charging signals.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Boot event received: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            context?.let { ctx ->
                val serviceIntent = Intent(ctx, AccOffService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ctx.startForegroundService(serviceIntent)
                    } else {
                        ctx.startService(serviceIntent)
                    }
                    Log.i(TAG, "AccOffService started successfully on boot")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed starting AccOffService on boot: ${e.message}")
                }
            }
        }
    }
}
