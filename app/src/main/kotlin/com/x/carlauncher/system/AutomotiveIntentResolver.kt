package com.x.carlauncher.system

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Universal Automotive Intent Resolver.
 *
 * Automatically resolves and dispatches intents to native factory automotive apps
 * across all major Android head-unit platforms (FYT/SYU, TopWay, Microntek/MTC,
 * TS10/TS18, AutoChips, and generic Android Automotive).
 */
object AutomotiveIntentResolver {

    /** Known factory Bluetooth Phone dialer packages in order of platform prominence */
    private val KNOWN_BT_PACKAGES = listOf(
        "com.syu.bt",               // FYT / UIS7862 / UIS8141E / SC9853i
        "com.tw.bt",                // TopWay
        "com.microntek.bt",         // Microntek / Rockchip PX5 / PX6
        "com.microntek.carcenter",  // Microntek alternative dialer
        "com.ts.bt",                // TS10 / TS9 / TS18 / Topway TS
        "com.autochips.bluetooth",  // AutoChips / AC8257 / AC8227L
        "com.android.dialer",       // Standard Android dialer
        "com.google.android.dialer" // Google Phone
    )

    /** Known factory OEM Bluetooth Settings packages (specifically for Bluetooth pairing / connection) */
    private val KNOWN_OEM_BT_SETTINGS_PACKAGES = listOf(
        "com.syu.bt",               // FYT / UIS7862 / UIS8141E / SC9853i
        "com.tw.bt",                // TopWay
        "com.microntek.bt",         // Microntek / Rockchip PX5 / PX6
        "com.microntek.carcenter",  // Microntek alternative dialer
        "com.ts.bt",                // TS10 / TS9 / TS18 / Topway TS
        "com.autochips.bluetooth"   // AutoChips / AC8257 / AC8227L
    )

    /** Known factory Vehicle Settings packages in order of prominence */
    private val KNOWN_SETTINGS_PACKAGES = listOf(
        "com.syu.settings",         // FYT Car Settings
        "com.tw.settings",          // TopWay Car Settings
        "com.microntek.settings",   // Microntek Car Settings
        "com.ts.settings",          // TS10 Settings
        "com.autochips.settings"    // AutoChips Settings
    )

    /**
     * Resolves the best available Bluetooth / Phone dialer activity on this head unit.
     * Falls back to [Intent.ACTION_DIAL] if no OEM automotive dialer is installed.
     */
    fun getPhoneIntent(context: Context): Intent {
        val pm = context.packageManager
        for (pkg in KNOWN_BT_PACKAGES) {
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                return intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            }
        }
        return Intent(Intent.ACTION_DIAL).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Resolves the best available Bluetooth settings / connection activity.
     * Does NOT launch the phone dialer / contacts; launches the system Bluetooth pairing screen.
     */
    fun getBluetoothSettingsIntent(context: Context): Intent {
        val pm = context.packageManager
        for (pkg in KNOWN_OEM_BT_SETTINGS_PACKAGES) {
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                return intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            }
        }
        return Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Resolves the native Car / Vehicle Settings activity, falling back to
     * standard Android System Settings if OEM car settings is not present.
     */
    fun getCarSettingsIntent(context: Context): Intent {
        val pm = context.packageManager
        for (pkg in KNOWN_SETTINGS_PACKAGES) {
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                return intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            }
        }
        return Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Safely executes a navigation or system intent with proper NEW_TASK flags.
     */
    fun launchSafely(context: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
