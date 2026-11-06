package com.x.carlauncher.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x.carlauncher.media.PowerampNotificationListener
import kotlinx.coroutines.delay

/**
 * Columna central de musica: controles multimedia de YouTube Music.
 *
 * Sin cuentas, sin API y sin registro en Google Cloud: la musica se elige
 * en la app de YouTube Music y aqui se controla con la sesion multimedia
 * del sistema (MediaSession): lo que suena, play/pausa, siguiente y
 * anterior, barra de progreso y volumen. Funciona para cualquier usuario
 * que tenga YouTube Music instalado, sin configurar nada.
 */

/** Abre YouTube Music, o su ficha en Play Store si no esta instalado. */
fun openYouTubeMusicApp(context: Context): Boolean {
    val intents = listOfNotNull(
        Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/"))
            .apply { setPackage("com.google.android.apps.youtube.music") },
        context.packageManager.getLaunchIntentForPackage("com.google.android.apps.youtube.music"),
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.youtube.music")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.youtube.music"))
    )
    for (intent in intents) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // Probar la siguiente opcion.
        }
    }
    Toast.makeText(context, "Instala YouTube Music para escuchar música", Toast.LENGTH_LONG).show()
    return false
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val totalSec = ms / 1000L
    val minutes = totalSec / 60L
    val seconds = totalSec % 60L
    return "%d:%02d".format(minutes, seconds)
}

@Composable
fun YouTubeMusicPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mediaState by PowerampNotificationListener.mediaState.collectAsState()

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat().coerceAtLeast(1f) }
    var currentVolume by remember {
        mutableStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat())
    }

    var isUserSeeking by remember { mutableStateOf(false) }
    var seekPositionMs by remember { mutableStateOf(0L) }

    // Acceso a notificaciones: sin el, el panel no puede ver ni controlar
    // YouTube Music. Se revisa cada 2 s mientras falte, para que el aviso
    // desaparezca solo cuando el usuario lo activa en Ajustes.
    var listenerEnabled by remember {
        mutableStateOf(PowerampNotificationListener.isNotificationAccessGranted(context))
    }
    LaunchedEffect(listenerEnabled) {
        if (!listenerEnabled) {
            while (true) {
                delay(2000)
                if (PowerampNotificationListener.isNotificationAccessGranted(context)) {
                    listenerEnabled = true
                    PowerampNotificationListener.refreshNow(context)
                    break
                }
            }
        }
    }
    val effectiveProgressMs = if (isUserSeeking) seekPositionMs else mediaState.progressMs
    val durationMs = mediaState.durationMs.coerceAtLeast(1L)

    val isYouTubeMusic = mediaState.playerName.contains("youtube", ignoreCase = true) ||
        mediaState.playerName.contains("ytmusic", ignoreCase = true)
    val hasTrack = mediaState.title.isNotBlank()

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color(0x6B1E3A8A)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF0B1326), Color(0xFF080E1C))
                    )
                )
                .padding(14.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Cabecera YouTube Music ───────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(Color(0xFFFF0033)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "YouTube Music",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Text(
                                text = when {
                                    isYouTubeMusic -> "Sonando en YouTube Music"
                                    hasTrack -> "Sonando en ${mediaState.playerName.ifBlank { "otra app" }}"
                                    else -> "Controles multimedia"
                                },
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }

                    if (mediaState.isPlaying) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFFF0033).copy(alpha = 0.25f))
                                .border(BorderStroke(1.dp, Color(0xFFFF0033).copy(alpha = 0.5f)), RoundedCornerShape(8.dp))
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "SONANDO",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF8A95)
                            )
                        }
                    }
                }

                // ── Aviso: sin acceso a notificaciones el control no funciona ──
                if (!listenerEnabled) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFFF0033).copy(alpha = 0.18f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsOff,
                            contentDescription = null,
                            tint = Color(0xFFFF8A95),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Activa el acceso a notificaciones para controlar YouTube Music",
                            fontSize = 11.sp,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFFF0033))
                                .clickable { PowerampNotificationListener.openNotificationAccessSettings(context) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Activar",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ── Caratula ─────────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.6f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0F172A))
                        .border(BorderStroke(1.dp, Color(0xFF1E293B)), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (mediaState.albumArt != null) {
                        Image(
                            bitmap = mediaState.albumArt!!.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = Color(0xFFFF0033),
                            modifier = Modifier.size(56.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ── Titulo / artista ─────────────────────────────────────────
                Text(
                    text = if (hasTrack) mediaState.title else "Nada sonando",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = when {
                        mediaState.artist.isNotBlank() -> mediaState.artist
                        hasTrack -> mediaState.playerName.ifBlank { "YouTube Music" }
                        else -> "Abre YouTube Music, pon una canción y contrólala desde aquí."
                    },
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // ── Barra de progreso ────────────────────────────────────────
                Slider(
                    value = effectiveProgressMs.toFloat(),
                    onValueChange = {
                        isUserSeeking = true
                        seekPositionMs = it.toLong().coerceIn(0L, durationMs)
                    },
                    onValueChangeFinished = {
                        isUserSeeking = false
                        PowerampNotificationListener.seekTo(context, seekPositionMs)
                    },
                    valueRange = 0f..durationMs.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFF0033),
                        activeTrackColor = Color(0xFFFF0033),
                        inactiveTrackColor = Color(0xFF1E293B)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatDuration(effectiveProgressMs),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = formatDuration(durationMs),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // ── Transporte ───────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { PowerampNotificationListener.skipToPrevious(context) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Anterior",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF0033))
                            .clickable { PowerampNotificationListener.togglePlayPause(context) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (mediaState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (mediaState.isPlaying) "Pausa" else "Reproducir",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { PowerampNotificationListener.skipToNext(context) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Siguiente",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // ── Salto -10 s / +10 s ──────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E293B))
                            .clickable {
                                val target = (effectiveProgressMs - 10_000L).coerceAtLeast(0L)
                                PowerampNotificationListener.seekTo(context, target)
                            }
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(text = "−10 s", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E293B))
                            .clickable {
                                val target = (effectiveProgressMs + 10_000L).coerceAtMost(durationMs)
                                PowerampNotificationListener.seekTo(context, target)
                            }
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(text = "+10 s", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ── Volumen ──────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Volumen",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.width(52.dp)
                    )
                    Slider(
                        value = currentVolume,
                        onValueChange = {
                            currentVolume = it
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0)
                        },
                        valueRange = 0f..maxVolume,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFF0033),
                            activeTrackColor = Color(0xFFFF0033),
                            inactiveTrackColor = Color(0xFF1E293B)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // ── Abrir YouTube Music ──────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFFF0033))
                        .clickable { openYouTubeMusicApp(context) }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Abrir YouTube Music",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
                Text(
                    text = "La música se elige en YouTube Music; aquí solo la controlas.",
                    fontSize = 10.sp,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 5.dp)
                )
            }
        }
    }
}
