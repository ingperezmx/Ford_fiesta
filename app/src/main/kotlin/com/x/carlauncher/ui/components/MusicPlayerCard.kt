package com.x.carlauncher.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x.carlauncher.media.AudioSource
import com.x.carlauncher.media.LyricEntry
import com.x.carlauncher.media.MediaState
import com.x.carlauncher.media.PlaybackMode
import com.x.carlauncher.media.PowerampNotificationListener
import com.x.carlauncher.ui.theme.ColorBorder
import com.x.carlauncher.ui.theme.ColorGrayDark
import com.x.carlauncher.ui.theme.ColorGrayMid
import com.x.carlauncher.ui.theme.ColorMusicGradientEnd
import com.x.carlauncher.ui.theme.ColorMusicGradientStart
import com.x.carlauncher.ui.theme.ColorNeonBlue
import com.x.carlauncher.ui.theme.XTheme

/**
 * Universal media player card supporting Spotify, Poweramp, YouTube Music,
 * Apple Music, VLC, Bluetooth, etc. Includes interactive seeker and full lyrics view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerCard(
    mediaState:       MediaState,
    onPrevious:       () -> Unit,
    onPlayPause:      () -> Unit,
    onNext:           () -> Unit,
    onSeek:           (Long) -> Unit = {},
    onToggleShuffle:  () -> Unit = {},
    onToggleRepeat:   () -> Unit = {},
    onLoadLrc:        (Uri) -> Unit = {},
    onOpenPlayer:     () -> Unit = {},
    modifier:         Modifier = Modifier
) {
    val colors = XTheme.colors
    val musicCardBrush = remember(colors) {
        Brush.linearGradient(listOf(colors.musicGradientStart, colors.musicGradientEnd))
    }
    var showLyrics by remember { mutableStateOf(false) }
    val currentSource by PowerampNotificationListener.currentSource.collectAsState()

    Card(
        modifier = modifier,
        shape    = RoundedCornerShape(24.dp),
        border   = BorderStroke(1.dp, colors.border),
        colors   = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .background(musicCardBrush)
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Player Badge (Left) + Actions (Right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Active Player Badge (Radio FM, YouTube Music, Spotify, Bluetooth, Local, etc.)
                val (badgeColor, badgeText) = when {
                    mediaState.playerName.contains("radio", ignoreCase = true) -> Pair(Color(0xFF00E5FF), "Radio FM")
                    mediaState.playerName.contains("youtube", ignoreCase = true) -> Pair(Color(0xFFFF0033), "YouTube Music")
                    mediaState.playerName.contains("spotify", ignoreCase = true) -> Pair(Color(0xFF1DB954), "Spotify")
                    mediaState.playerName.contains("amazon", ignoreCase = true) -> Pair(Color(0xFF25D1DA), "Amazon Music")
                    mediaState.playerName.contains("apple", ignoreCase = true) -> Pair(Color(0xFFFA2D48), "Apple Music")
                    mediaState.playerName.contains("bluetooth", ignoreCase = true) || currentSource == AudioSource.BLUETOOTH -> Pair(Color(0xFF38BDF8), "Bluetooth Audio")
                    currentSource == AudioSource.ONLINE -> Pair(Color(0xFFFF0033), mediaState.playerName.ifBlank { "YouTube Music" })
                    else -> Pair(Color(0xFFFF0033), if (mediaState.title.isBlank()) "YouTube Music" else mediaState.playerName.ifBlank { "YouTube Music" })
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor.copy(alpha = 0.15f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(badgeColor, CircleShape)
                    )
                    Text(
                        text = badgeText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor
                    )
                }

                // Right: Actions (Lyrics Toggle + Source Toggle + Fullscreen)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Lyrics toggle button
                    IconButton(
                        onClick = { showLyrics = !showLyrics },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = "Letras",
                            tint = if (showLyrics) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Audio source toggle button (Local USB vs Online vs Bluetooth)
                    IconButton(
                        onClick = { PowerampNotificationListener.toggleSource() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        val (srcIcon, srcTint) = when (currentSource) {
                            AudioSource.BLUETOOTH -> Pair(Icons.Default.Bluetooth, Color(0xFF38BDF8))
                            AudioSource.ONLINE -> Pair(Icons.Default.Cloud, Color(0xFF10B981))
                            else -> Pair(Icons.Default.Album, Color.White.copy(alpha = 0.6f))
                        }
                        Icon(
                            imageVector = srcIcon,
                            contentDescription = "Fuente de audio",
                            tint = srcTint,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Expand / Open Expanded Music Studio
                    IconButton(
                        onClick = onOpenPlayer,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInFull,
                            contentDescription = "Pantalla completa",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Crossfade(
                targetState   = showLyrics,
                animationSpec = tween(350),
                label         = "lyricsCrossfade",
                modifier      = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { lyricsVisible ->
                if (lyricsVisible) {
                    LyricsView(
                        mediaState = mediaState,
                        onDismiss  = { showLyrics = false },
                        onLoadLrc  = onLoadLrc,
                        modifier   = Modifier.fillMaxSize()
                    )
                } else {
                    PlayerView(
                        mediaState      = mediaState,
                        onPrevious      = onPrevious,
                        onPlayPause     = onPlayPause,
                        onNext          = onNext,
                        onSeek          = onSeek,
                        onToggleShuffle = onToggleShuffle,
                        onToggleRepeat  = onToggleRepeat,
                        onAlbumClick    = onOpenPlayer,
                        modifier        = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

// ── Player view ──────────────────────────────────────────────────────────────

@Composable
private fun PlayerView(
    mediaState:      MediaState,
    onPrevious:      () -> Unit,
    onPlayPause:     () -> Unit,
    onNext:          () -> Unit,
    onSeek:          (Long) -> Unit,
    onToggleShuffle: () -> Unit = {},
    onToggleRepeat:  () -> Unit = {},
    onAlbumClick:    () -> Unit,
    modifier:        Modifier = Modifier
) {
    val colors = XTheme.colors
    Column(
        modifier            = modifier,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Album art + track info
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier              = Modifier.fillMaxWidth()
        ) {
            AlbumArtBox(
                bitmap  = mediaState.albumArt,
                onClick = onAlbumClick
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onAlbumClick)
            ) {
                Text(
                    text       = mediaState.title.ifBlank { "YouTube Music" },
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color      = colors.textPrimary,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Text(
                    text     = mediaState.artist.ifBlank { "Toca para abrir YouTube Music" },
                    fontSize = 10.sp,
                    color    = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (mediaState.lyricsLine.isNotBlank()) {
                    Text(
                        text = "${mediaState.lyricsLine}",
                        fontSize = 12.sp,
                        color = colors.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    val trans = mediaState.fullLyrics.find { it.text == mediaState.lyricsLine }?.translation
                        ?: com.x.carlauncher.media.LyricsTranslationManager.getTranslationSync(mediaState.lyricsLine)
                    if (!trans.isNullOrBlank()) {
                        Text(
                            text = "↳ $trans",
                            fontSize = 10.sp,
                            color = Color(0xFFFDE047),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Progress bar: Slim automotive seekbar with interactive tap-to-seek
        val progress = if (mediaState.durationMs > 0L) {
            (mediaState.progressMs.toFloat() / mediaState.durationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = formatTime(mediaState.progressMs),
                fontSize = 9.sp,
                color = colors.textSecondary
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(14.dp)
                    .pointerInput(mediaState.durationMs) {
                        detectTapGestures { offset ->
                            if (size.width > 0 && mediaState.durationMs > 0) {
                                val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                                onSeek((fraction * mediaState.durationMs).toLong())
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // Background track
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.2f))
                )
                // Active progress
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.secondary)
                )
            }

            Text(
                text = formatTime(mediaState.durationMs),
                fontSize = 9.sp,
                color = colors.textSecondary
            )
        }

        // Playback controls: [Shuffle] [Previous] [Play/Pause] [Next] [Repeat]
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            // Shuffle Button
            IconButton(
                onClick = onToggleShuffle,
                modifier = Modifier.size(28.dp)
            ) {
                val isShuffleActive = mediaState.playbackMode == PlaybackMode.SHUFFLE
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Aleatorio",
                    tint = if (isShuffleActive) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.size(17.dp)
                )
            }

            IconButton(
                onClick = onPrevious,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector        = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint               = colors.textPrimary,
                    modifier           = Modifier.size(24.dp)
                )
            }

            // Play / Pause circle button
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (colors.isDark) Color.White else colors.primary)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector        = if (mediaState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (mediaState.isPlaying) "Pause" else "Play",
                    tint               = if (colors.isDark) Color(0xFF1E1B4B) else Color.White,
                    modifier           = Modifier.size(22.dp)
                )
            }

            IconButton(
                onClick = onNext,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector        = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint               = colors.textPrimary,
                    modifier           = Modifier.size(24.dp)
                )
            }

            // Repeat Button
            IconButton(
                onClick = onToggleRepeat,
                modifier = Modifier.size(28.dp)
            ) {
                val isRepeatOne = mediaState.playbackMode == PlaybackMode.REPEAT_ONE
                val isSequence = mediaState.playbackMode == PlaybackMode.SEQUENCE
                Icon(
                    imageVector = if (isRepeatOne) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = if (isRepeatOne) "Repetir Pista" else "Secuencial",
                    tint = if (isRepeatOne || isSequence) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

// ── Album art box ────────────────────────────────────────────────────────────

@Composable
private fun AlbumArtBox(bitmap: Bitmap?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2D1B69))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                painter            = BitmapPainter(bitmap.asImageBitmap()),
                contentDescription = "Album art",
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector        = Icons.Default.MusicNote,
                contentDescription = "No album art",
                tint               = ColorGrayMid,
                modifier           = Modifier.size(28.dp)
            )
        }
    }
}

// ── Full Lyrics view ─────────────────────────────────────────────────────────

@Composable
private fun LyricsView(
    mediaState: MediaState,
    onDismiss:  () -> Unit,
    onLoadLrc:  (Uri) -> Unit,
    modifier:   Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // File picker launcher for .lrc files
    val lrcPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onLoadLrc(it) }
    }

    // Auto-scroll to current singing lyric entry
    val currentLyricIndex = remember(mediaState.progressMs, mediaState.fullLyrics) {
        if (mediaState.fullLyrics.isEmpty()) -1
        else {
            val idx = mediaState.fullLyrics.indexOfLast { it.timeMs <= mediaState.progressMs }
            if (idx >= 0) idx else 0
        }
    }

    LaunchedEffect(currentLyricIndex) {
        if (currentLyricIndex >= 0 && currentLyricIndex < mediaState.fullLyrics.size) {
            listState.animateScrollToItem((currentLyricIndex - 2).coerceAtLeast(0))
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Lyrics Content Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            when {
                // Case 1: Synchronized LRC lyrics available
                mediaState.fullLyrics.isNotEmpty() -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        itemsIndexed(mediaState.fullLyrics) { index, entry ->
                            val isCurrent = index == currentLyricIndex
                            Text(
                                text = entry.text,
                                fontSize = if (isCurrent) 17.sp else 14.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                            )
                        }
                    }
                }

                // Case 2: Plain text embedded lyrics available
                mediaState.rawLyricsText.isNotBlank() -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        item {
                            Text(
                                text = mediaState.rawLyricsText,
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.85f),
                                textAlign = TextAlign.Center,
                                lineHeight = 22.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Case 3: No lyrics detected
                else -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lyrics,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Sin letra disponible",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Puedes cargar un archivo .lrc sincronizado",
                            fontSize = 11.sp,
                            color = ColorGrayDark
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { lrcPickerLauncher.launch("*/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Cargar archivo .LRC", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Bottom dismissal bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { lrcPickerLauncher.launch("*/*") },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(text = "Buscar .LRC", fontSize = 11.sp, color = Color(0xFF38BDF8))
            }

            TextButton(
                onClick = onDismiss,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(text = "Volver al reproductor", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes      = totalSeconds / 60L
    val seconds      = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
