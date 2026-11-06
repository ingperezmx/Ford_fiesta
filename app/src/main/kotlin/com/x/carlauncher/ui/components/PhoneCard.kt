package com.x.carlauncher.ui.components

import com.x.carlauncher.bluetooth.BluetoothConnectionState

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x.carlauncher.media.AudioSource
import com.x.carlauncher.system.AutomotiveIntentResolver
import com.x.carlauncher.media.FytMusicService
import com.x.carlauncher.media.PowerampNotificationListener
import com.x.carlauncher.ui.theme.ColorAccentGreen
import com.x.carlauncher.ui.theme.ColorBorder
import com.x.carlauncher.ui.theme.ColorGrayMid
import com.x.carlauncher.ui.theme.ColorPrimary
import com.x.carlauncher.ui.theme.ColorSurface
import com.x.carlauncher.ui.theme.ColorSurfaceDeep
import com.x.carlauncher.ui.theme.XTheme

/**
 * Quick-dial phone card. Tapping opens the phone dialer.
 */
@Composable
fun PhoneCard(
    connectionState: BluetoothConnectionState,
    modifier:        Modifier = Modifier
) {
    val colors = XTheme.colors
    val connectCardBrush = remember(colors) {
        Brush.verticalGradient(listOf(colors.surface, colors.surfaceDeep))
    }
    val context = LocalContext.current

    Card(
        modifier = modifier.clickable {
            AutomotiveIntentResolver.launchSafely(context, AutomotiveIntentResolver.getPhoneIntent(context))
        },
        shape  = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, colors.border),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .background(connectCardBrush)
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector        = Icons.Default.Phone,
                contentDescription = "Phone",
                tint               = ColorAccentGreen,
                modifier           = Modifier.size(36.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = "Telefono",
                    fontSize   = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color      = colors.textPrimary
                )
                Text(
                    text     = if (connectionState.isConnected)
                                    "Conectado a ${connectionState.deviceName.ifBlank { "Dispositivo" }}"
                               else
                                    "Conectar dispositivo",
                    fontSize = 12.sp,
                    color    = if (connectionState.isConnected) ColorAccentGreen else colors.textSecondary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Bluetooth audio card. Displays connected device name, active playback info,
 * track title, artist, album, and controls playback without leaving the launcher.
 */
@Composable
fun BluetoothAudioCard(
    connectionState: BluetoothConnectionState,
    modifier:        Modifier = Modifier
) {
    val colors = XTheme.colors
    val connectCardBrush = remember(colors) {
        Brush.verticalGradient(listOf(colors.surface, colors.surfaceDeep))
    }
    val context = LocalContext.current

    val openBtApp: () -> Unit = {
        AutomotiveIntentResolver.launchSafely(context, AutomotiveIntentResolver.getBluetoothSettingsIntent(context))
    }

    Card(
        modifier = modifier,
        shape  = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, colors.border),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .background(connectCardBrush)
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── TOP ROW: Bluetooth Badge + Connected Device Name + Live Status Dot ─
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = openBtApp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier              = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(
                                color = if (connectionState.isConnected) Color(0xFF0284C7) else ColorPrimary,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector        = Icons.Default.Bluetooth,
                            contentDescription = "Bluetooth",
                            tint               = Color.White,
                            modifier           = Modifier.size(15.dp)
                        )
                    }
                    Text(
                        text       = if (connectionState.isConnected && connectionState.deviceName.isNotBlank())
                                         connectionState.deviceName
                                     else "Bluetooth Audio",
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color      = if (connectionState.isConnected) ColorAccentGreen else colors.textPrimary,
                        maxLines   = 1,
                        overflow   = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                if (connectionState.isConnected) {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(ColorAccentGreen, CircleShape)
                        )
                        Text(
                            text       = if (connectionState.isPlaying) "Reproduciendo" else "Conectado",
                            fontSize   = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color      = if (connectionState.isPlaying) colors.secondary else colors.textSecondary
                        )
                    }
                }
            }

            // ── MIDDLE SECTION: Full-Width Song Title & Artist / Album ───────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = openBtApp)
            ) {
                if (connectionState.isConnected) {
                    val hasTitle = connectionState.trackTitle.isNotBlank()
                    val displayTitle = if (hasTitle) {
                        connectionState.trackTitle
                    } else if (connectionState.isPlaying) {
                        "Reproduciendo en telefono"
                    } else {
                        "Audio conectado - Listo"
                    }

                    Text(
                        text       = displayTitle,
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color      = colors.textPrimary,
                        maxLines   = 1,
                        overflow   = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )

                    val displaySubtitle = buildString {
                        if (connectionState.trackArtist.isNotBlank()) {
                            append(connectionState.trackArtist)
                        }
                        if (connectionState.trackAlbum.isNotBlank()) {
                            if (isNotEmpty()) append(" - ")
                            append(connectionState.trackAlbum)
                        }
                        if (isEmpty()) {
                            if (connectionState.isPlaying) append("Audio estereo Bluetooth A2DP")
                            else append("Control remoto AVRCP")
                        }
                    }

                    Text(
                        text     = displaySubtitle,
                        fontSize = 10.sp,
                        color    = if (hasTitle) colors.secondary else colors.textSecondary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text       = "Sin dispositivo conectado",
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color      = colors.textPrimary,
                        maxLines   = 1,
                        overflow   = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text     = "Toca para vincular telefono",
                        fontSize = 10.sp,
                        color    = colors.secondary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            // ── BOTTOM ROW: Media Controls (Prev, Play/Pause, Next) ───────────────
            if (connectionState.isConnected) {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            PowerampNotificationListener.setSource(AudioSource.BLUETOOTH)
                            PowerampNotificationListener.skipToPrevious(context)
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector        = Icons.Default.SkipPrevious,
                            contentDescription = "Anterior",
                            tint               = Color.White,
                            modifier           = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    IconButton(
                        onClick = {
                            PowerampNotificationListener.setSource(AudioSource.BLUETOOTH)
                            PowerampNotificationListener.togglePlayPause(context)
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFF0284C7), CircleShape)
                    ) {
                        Icon(
                            imageVector        = if (connectionState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pausa",
                            tint               = Color.White,
                            modifier           = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    IconButton(
                        onClick = {
                            PowerampNotificationListener.setSource(AudioSource.BLUETOOTH)
                            PowerampNotificationListener.skipToNext(context)
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector        = Icons.Default.SkipNext,
                            contentDescription = "Siguiente",
                            tint               = Color.White,
                            modifier           = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
