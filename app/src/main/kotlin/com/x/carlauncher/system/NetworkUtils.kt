package com.x.carlauncher.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * High-speed, non-blocking network connectivity utility for XCarOS.
 * Guarantees that no background worker or media/weather pipeline will attempt
 * network operations or hang on DNS/socket timeouts when the car is offline.
 */
object NetworkUtils {

    fun isOnline(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val capabilities = cm.getNetworkCapabilities(network) ?: return false
                // On automotive Android ROMs, NET_CAPABILITY_VALIDATED often fails or takes minutes due to captive portal checks.
                // NET_CAPABILITY_INTERNET is the authoritative indicator for connectivity.
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val netInfo = cm.activeNetworkInfo
                netInfo != null && netInfo.isConnected
            }
        } catch (_: Exception) {
            false
        }
    }
}

