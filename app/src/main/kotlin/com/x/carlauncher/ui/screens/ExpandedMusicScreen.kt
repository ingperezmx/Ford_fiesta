package com.x.carlauncher.ui.screens

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x.carlauncher.media.AudioSource
import com.x.carlauncher.media.FavoritesManager
import com.x.carlauncher.system.AutomotiveIntentResolver
import com.x.carlauncher.media.MediaState
import com.x.carlauncher.media.PlaybackMode
import com.x.carlauncher.media.PowerampNotificationListener
import com.x.carlauncher.media.XLocalPlayer
import com.x.carlauncher.ui.components.RadioFmCard
import com.x.carlauncher.ui.components.YouTubeMusicPanel

/**
 * High-Performance Automotive Expanded Music Studio Screen for XCarOS2.
 * Directly integrates 3 responsive cards:
 * 1. Master Player (Source, High-res cover art, interactive seeking, full transport controls, -10s/+10s, volume).
 * 2. YouTube Music (controles multimedia: lo que suena, play/pausa, siguiente/anterior, progreso y volumen).
 * 3. Live Lyrics / Karaoke XL (Synchronized lyrics with instant timestamp seeking, bilingual EN/ES translation, zoom A-/A+, and expand mode).
 */
@Composable
fun ExpandedMusicScreen(
    mediaState: MediaState,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // ── Persistent state & collections ───────────────────────────────────────
    val playbackMode by XLocalPlayer.playbackMode.collectAsState()
    val activeQueueTitle by XLocalPlayer.queueTitleFlow.collectAsState()

    // ── Local Screen UI States ───────────────────────────────────────────────
    val currentSource by PowerampNotificationListener.currentSource.collectAsState()

    // Karaoke states
    var showTranslation by remember { mutableStateOf(false) }
    var fontScale by remember { mutableStateOf(0) } // -1, 0, 1, 2, 3
    var isKaraokeExpanded by remember { mutableStateOf(false) }

    // Volume state
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat().coerceAtLeast(1f) }
    var currentVolume by remember {
        mutableStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat())
    }

    // Interactive seek state
    var isUserSeeking by remember { mutableStateOf(false) }
    var seekPositionMs by remember { mutableStateOf(0L) }
    val effectiveProgressMs = if (isUserSeeking) seekPositionMs else mediaState.progressMs

    // Audio format badge (e.g. FLAC - 24-Bit / WAV - Sin perdida / MP3 - 320k)
    val audioQualityBadge = remember(mediaState.title, mediaState.artist) {
        val activePath = XLocalPlayer.currentTrack?.path ?: ""
        val ext = activePath.substringAfterLast('.', "").uppercase()
        when (ext) {
            "FLAC" -> "HI-RES FLAC"
            "WAV" -> "WAV LOSSLESS"
            "M4A", "AAC" -> "AAC 256K"
            else -> "MP3 320K"
        }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF050811))
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        // =====================================================================
        // 1. COLUMNA IZQUIERDA (37%): SINTONIZADOR DE RADIO FM & STREAMING (Reubicado)
        // =====================================================================
        RadioFmCard(
            modifier = Modifier
                .weight(0.37f)
                .fillMaxHeight()
        )

        // =====================================================================
        // 2. COLUMNA CENTRAL (36%): YOUTUBE MUSIC (controles multimedia)
        // =====================================================================
        if (!isKaraokeExpanded) {
            YouTubeMusicPanel(
                modifier = Modifier
                    .weight(0.36f)
                    .fillMaxHeight()
            )
        }

        // =====================================================================
        // 3. COLUMNA DERECHA (27%): REPRODUCTOR MASTER (Reubicado)
        // =====================================================================
        Card(
            modifier = Modifier
                .weight(0.27f)
                .fillMaxHeight(),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Color(0x618B5CF6)),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF27154E), Color(0xFF150B2E))
                        )
                    )
                    .padding(14.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {

                    // ── 1.1 Header Fuente de Audio + Badge ───────────────────
                    Column(modifier = Modifier.fillMaxWidth()) {
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
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF0F0B1E).copy(alpha = 0.8f))
                                        .clickable { onNavigateBack() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowBack,
                                        contentDescription = "Volver a Inicio",
                                        tint = Color(0xFFDDD6FE),
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                                Text(
                                    text = "FUENTE DE AUDIO",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFDDD6FE),
                                    letterSpacing = 0.8.sp
                                )
                            }
                            Text(
                                text = "● $audioQualityBadge",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF67E8F9)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Selector de Fuentes
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F0B1E).copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                                .border(BorderStroke(1.dp, Color(0x33A855F7)), RoundedCornerShape(12.dp))
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf(
                                Triple(AudioSource.LOCAL, "Local", Icons.Default.Album),
                                Triple(AudioSource.ONLINE, "Online", Icons.Default.Cloud),
                                Triple(AudioSource.BLUETOOTH, "BT", Icons.Default.Bluetooth),
                                Triple(AudioSource.USB, "USB", Icons.Default.Usb)
                            ).forEach { (source, label, icon) ->
                                val isActive = currentSource == source
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isActive) Color(0xFF9333EA) else Color.Transparent)
                                        .clickable {
                                            PowerampNotificationListener.setSource(source)
                                            when (source) {
                                                AudioSource.BLUETOOTH -> {
                                                    AutomotiveIntentResolver.launchSafely(context, AutomotiveIntentResolver.getPhoneIntent(context))
                                                }
                                                AudioSource.ONLINE -> {
                                                    PowerampNotificationListener.togglePlayPause(context)
                                                }
                                                else -> {
                                                    XLocalPlayer.play()
                                                }
                                            }
                                        }
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = if (isActive) Color.White else Color(0xFF94A3B8),
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = label,
                                            fontSize = 11.sp,
                                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isActive) Color.White else Color(0xFFCBD5E1)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── 1.2 Caratula de Album + Boton Favorito ───────────────
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        val activePath = XLocalPlayer.currentTrack?.path ?: ""
                        val isCurrentFav = FavoritesManager.isFavorite(activePath)

                        Box(
                            modifier = Modifier
                                .size(148.dp)
                                .shadow(24.dp, RoundedCornerShape(20.dp))
                                .border(BorderStroke(1.5.dp, Color(0x66C084FC)), RoundedCornerShape(20.dp))
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    Brush.radialGradient(
                                        listOf(Color(0xFF381B6D), Color(0xFF160D2E))
                                    )
                                )
                        ) {
                            if (mediaState.albumArt != null) {
                                Image(
                                    bitmap = mediaState.albumArt.asImageBitmap(),
                                    contentDescription = "Caratula",
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = Color(0xFFA855F7).copy(alpha = 0.5f),
                                        modifier = Modifier.size(54.dp)
                                    )
                                }
                            }

                            // Boton Favorito Flotante
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(x = (-6).dp, y = (-6).dp)
                                    .size(36.dp)
                                    .shadow(12.dp, CircleShape)
                                    .background(Color(0xFF0B0718).copy(alpha = 0.92f), CircleShape)
                                    .border(BorderStroke(1.dp, Color(0x66C084FC)), CircleShape)
                                    .clickable {
                                        FavoritesManager.toggleFavorite(activePath)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isCurrentFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "Favorito",
                                    tint = if (isCurrentFav) Color(0xFFF43F5E) else Color(0xFF94A3B8),
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Metadatos
                        Text(
                            text = mediaState.title.ifEmpty { "Sin reproduccion" },
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = mediaState.artist.ifEmpty { "PabloCarOS Player" },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFDDD6FE),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                        )
                        if (mediaState.album.isNotBlank()) {
                            Text(
                                text = mediaState.album,
                                fontSize = 10.sp,
                                color = Color(0xFF94A3B8),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 1.dp)
                            )
                        }

                        // Secuencia Activa (Contextual Queue Badge)
                        Box(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0F0B1E).copy(alpha = 0.85f))
                                .border(BorderStroke(0.8.dp, Color(0xFF67E8F9).copy(alpha = 0.4f)), RoundedCornerShape(8.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "▶ $activeQueueTitle",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF67E8F9),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // ── 1.3 Barra de Tiempo y Transporte ─────────────────────
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Seekbar
                        Slider(
                            value = effectiveProgressMs.coerceIn(0L, mediaState.durationMs.coerceAtLeast(1L)).toFloat(),
                            onValueChange = { newVal ->
                                isUserSeeking = true
                                seekPositionMs = newVal.toLong()
                            },
                            onValueChangeFinished = {
                                if (currentSource == AudioSource.ONLINE || currentSource == AudioSource.BLUETOOTH) {
                                    PowerampNotificationListener.seekTo(context, seekPositionMs)
                                } else {
                                    XLocalPlayer.seekTo(seekPositionMs)
                                }
                                isUserSeeking = false
                            },
                            valueRange = 0f..mediaState.durationMs.coerceAtLeast(1L).toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color(0xFF3B0764)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(20.dp)
                        )

                        // Tiempos
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (effectiveProgressMs > 0L) formatDuration(effectiveProgressMs) else "0:00",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFCBD5E1)
                            )
                            Text(
                                text = if (mediaState.durationMs > 0L) formatDuration(mediaState.durationMs) else "--:--",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFCBD5E1)
                            )
                        }

                        // Botones de Transporte
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Aleatorio
                            val isShuffle = playbackMode == PlaybackMode.SHUFFLE
                            IconButton(
                                onClick = { XLocalPlayer.toggleShuffle() },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = "Aleatorio",
                                    tint = if (isShuffle) Color(0xFF38BDF8) else Color(0xFF64748B),
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Anterior
                            IconButton(
                                onClick = {
                                    if (currentSource == AudioSource.ONLINE || currentSource == AudioSource.BLUETOOTH) {
                                        PowerampNotificationListener.skipToPrevious(context)
                                    } else {
                                        XLocalPlayer.skipToPrevious()
                                    }
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(Color(0xFF0F0B1E).copy(alpha = 0.6f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipPrevious,
                                    contentDescription = "Anterior",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Play / Pause Grande
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .shadow(16.dp, CircleShape)
                                    .background(Color.White, CircleShape)
                                    .clickable {
                                        if (currentSource == AudioSource.ONLINE || currentSource == AudioSource.BLUETOOTH) {
                                            PowerampNotificationListener.togglePlayPause(context)
                                        } else {
                                            if (mediaState.isPlaying) {
                                                XLocalPlayer.pause()
                                            } else {
                                                XLocalPlayer.play()
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (mediaState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pausa",
                                    tint = Color(0xFF0F172A),
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            // Siguiente
                            IconButton(
                                onClick = {
                                    if (currentSource == AudioSource.ONLINE || currentSource == AudioSource.BLUETOOTH) {
                                        PowerampNotificationListener.skipToNext(context)
                                    } else {
                                        XLocalPlayer.skipToNext()
                                    }
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(Color(0xFF0F0B1E).copy(alpha = 0.6f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "Siguiente",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Repetir
                            val isRepeat = playbackMode == PlaybackMode.REPEAT_ONE
                            IconButton(
                                onClick = { XLocalPlayer.toggleRepeat() },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = if (isRepeat) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                    contentDescription = "Repetir",
                                    tint = if (isRepeat) Color(0xFF38BDF8) else Color(0xFF64748B),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // ── 1.4 Salto -10s/+10s y Barra de Volumen ───────────
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(BorderStroke(0.5.dp, Color(0x33A855F7)), RoundedCornerShape(10.dp))
                                .background(Color(0xFF0B0718).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // -10s
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF1E1438))
                                    .clickable {
                                        if (currentSource == AudioSource.ONLINE || currentSource == AudioSource.BLUETOOTH) {
                                            val target = (effectiveProgressMs - 10000L).coerceAtLeast(0L)
                                            PowerampNotificationListener.seekTo(context, target)
                                        } else {
                                            XLocalPlayer.skipSeconds(-10)
                                        }
                                    }
                                    .padding(horizontal = 7.dp, vertical = 4.dp)
                            ) {
                                Text("-10s", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }

                            // Volumen
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = "Volumen",
                                tint = Color(0xFFC084FC),
                                modifier = Modifier.size(14.dp)
                            )

                            Slider(
                                value = currentVolume,
                                onValueChange = { newVol ->
                                    currentVolume = newVol
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol.toInt(), 0)
                                },
                                valueRange = 0f..maxVolume,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFFC084FC),
                                    activeTrackColor = Color(0xFFA855F7),
                                    inactiveTrackColor = Color(0xFF1E1438)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(16.dp)
                            )

                            // +10s
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF1E1438))
                                    .clickable {
                                        if (currentSource == AudioSource.ONLINE || currentSource == AudioSource.BLUETOOTH) {
                                            val target = (effectiveProgressMs + 10000L).coerceAtMost(mediaState.durationMs)
                                            PowerampNotificationListener.seekTo(context, target)
                                        } else {
                                            XLocalPlayer.skipSeconds(10)
                                        }
                                    }
                                    .padding(horizontal = 7.dp, vertical = 4.dp)
                            ) {
                                Text("+10s", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }

    }
}

/**
 * Utility helper to format milliseconds into "mm:ss".
 */
private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val totalSec = ms / 1000L
    val minutes = totalSec / 60L
    val seconds = totalSec % 60L
    return "%d:%02d".format(minutes, seconds)
}

