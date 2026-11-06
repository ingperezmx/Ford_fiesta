package com.x.carlauncher.ui.components

import android.content.Intent
import com.x.carlauncher.system.AutomotiveIntentResolver
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.x.carlauncher.voice.OfflineVoiceController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x.carlauncher.ui.theme.ColorGrayMid
import com.x.carlauncher.ui.theme.XTheme

/**
 * Top status bar for the automotive launcher.
 * Completely interactive with direct shortcuts to system settings, BT, and Car menus.
 *
 * @param currentTime  Formatted time string, e.g. "10:45"
 * @param currentDate  Formatted date string, e.g. "Lunes, 26 Oct"
 * @param outsideTemp  Outside temperature in Celsius
 */
@Composable
fun TopStatusBar(
    currentTime: String,
    currentDate: String,
    outsideTemp: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = XTheme.colors
    val voiceController = remember { OfflineVoiceController.getInstance(context) }
    val isListening by voiceController.isListening.collectAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // ── Left: Clock + Date (Tapping opens Date & Time settings) ───────────
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.clickable {
                try {
                    context.startActivity(Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            }
        ) {
            val timeParts = remember(currentTime) { currentTime.split(" ") }
            if (timeParts.size == 2) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text       = timeParts[0],
                        fontSize   = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color      = colors.textPrimary
                    )
                    Text(
                        text       = timeParts[1],
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color      = colors.secondary,
                        modifier   = Modifier.padding(bottom = 3.dp)
                    )
                }
            } else {
                Text(
                    text       = currentTime,
                    fontSize   = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color      = colors.textPrimary
                )
            }
            Text(
                text     = currentDate,
                fontSize = 13.sp,
                color    = colors.textSecondary,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }

        // ── Right: Status icons (Interactive) ─────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Voice Assistant (Offline Hands-Free)
            Icon(
                imageVector        = Icons.Default.Mic,
                contentDescription = "Voice Assistant",
                tint               = if (isListening) colors.secondary else colors.textPrimary,
                modifier           = Modifier
                    .size(22.dp)
                    .clickable {
                        voiceController.startListening()
                    }
            )

            // Location
            Icon(
                imageVector        = Icons.Default.LocationOn,
                contentDescription = "Location",
                tint               = colors.textPrimary,
                modifier           = Modifier
                    .size(22.dp)
                    .clickable {
                        try {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {}
                    }
            )

            // Bluetooth
            Icon(
                imageVector        = Icons.Default.Bluetooth,
                contentDescription = "Bluetooth",
                tint               = colors.textPrimary,
                modifier           = Modifier
                    .size(22.dp)
                    .clickable {
                        AutomotiveIntentResolver.launchSafely(context, AutomotiveIntentResolver.getBluetoothSettingsIntent(context))
                    }
            )

            // Phone
            Icon(
                imageVector        = Icons.Default.Phone,
                contentDescription = "Phone",
                tint               = colors.textPrimary,
                modifier           = Modifier
                    .size(22.dp)
                    .clickable {
                        AutomotiveIntentResolver.launchSafely(context, AutomotiveIntentResolver.getPhoneIntent(context))
                    }
            )

            // Vertical divider
            Divider(
                modifier  = Modifier
                    .height(24.dp)
                    .width(1.dp),
                color     = colors.textSecondary.copy(alpha = 0.3f)
            )

            // Car / Settings Shortcut
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clickable {
                    AutomotiveIntentResolver.launchSafely(context, AutomotiveIntentResolver.getCarSettingsIntent(context))
                }
            ) {
                Icon(
                    imageVector        = Icons.Default.DirectionsCar,
                    contentDescription = "Car",
                    tint               = colors.textPrimary,
                    modifier           = Modifier.size(20.dp)
                )
                Text(
                    text     = "$outsideTemp C",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color    = colors.textPrimary
                )
            }
        }
    }
}
