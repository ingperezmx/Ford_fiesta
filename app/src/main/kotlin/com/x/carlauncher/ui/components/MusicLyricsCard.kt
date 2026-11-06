package com.x.carlauncher.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x.carlauncher.media.LyricEntry
import com.x.carlauncher.media.LyricsRepository
import com.x.carlauncher.media.MediaState
import com.x.carlauncher.ui.theme.ColorBorder
import com.x.carlauncher.ui.theme.ColorGrayDark
import com.x.carlauncher.ui.theme.ColorGrayMid
import com.x.carlauncher.ui.theme.ColorSurface
import com.x.carlauncher.ui.theme.ColorSurfaceDeep
import com.x.carlauncher.ui.theme.XTheme
import kotlinx.coroutines.launch

/**
 * Automotive Live Synchronized Lyrics Card.
 * Automatically downloads and streams synchronized LRC lyrics with real-time highlighted singing lines.
 */
@Composable
fun MusicLyricsCard(
    mediaState: MediaState,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = XTheme.colors
    val lyricsCardBrush = remember(colors) {
        Brush.verticalGradient(listOf(colors.lyricsGradientStart, colors.lyricsGradientEnd))
    }
    val context = LocalContext.current
    val currentTrackKey = "${mediaState.title}_${mediaState.artist}".trim()

    // Isolated per track - strictly reset to empty whenever currentTrackKey changes!
    var fetchedLyrics by remember(currentTrackKey) { mutableStateOf<List<LyricEntry>>(emptyList()) }
    var isDownloading by remember(currentTrackKey) { mutableStateOf(false) }
    var showTranslation by remember { mutableStateOf(true) }

    // Combine locally passed lyrics or fetched online lyrics
    val lyrics = if (mediaState.fullLyrics.isNotEmpty()) mediaState.fullLyrics else fetchedLyrics

    val listState = rememberLazyListState()

    // Auto-fetch lyrics online when track changes if not already provided
    LaunchedEffect(currentTrackKey) {
        listState.scrollToItem(0)
        val isGenericTrack = mediaState.title.isBlank() ||
            mediaState.title.equals("Sin reproduccion", ignoreCase = true) ||
            mediaState.title.contains("Reproduciendo", ignoreCase = true) ||
            mediaState.title.contains("Bluetooth", ignoreCase = true) ||
            mediaState.title.contains("Audio conectado", ignoreCase = true)

        if (!isGenericTrack && mediaState.fullLyrics.isEmpty()) {
            isDownloading = true
            val downloaded = LyricsRepository.getOrFetchLyrics(
                context = context,
                rawTitle = mediaState.title,
                rawArtist = mediaState.artist
            )
            fetchedLyrics = downloaded
            isDownloading = false
        }
    }

    // Identify active lyric index based on playback position
    val activeIndex = remember(mediaState.progressMs, lyrics) {
        if (lyrics.isEmpty()) -1
        else lyrics.indexOfLast { it.timeMs <= mediaState.progressMs }
    }

    // Smooth auto-scroll to center the active line
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && lyrics.isNotEmpty()) {
            listState.animateScrollToItem(
                index = maxOf(0, activeIndex - 1),
                scrollOffset = 0
            )
        }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.45f)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .background(lyricsCardBrush)
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // ── Top Header Row ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(Color(0xFFA855F7).copy(alpha = 0.25f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = "Letra",
                            tint = Color(0xFFC084FC),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Text(
                        text = "Letra en Vivo",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                }

                // Status Pill + Translation Toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Translation Toggle Pill (ES ⇄ EN)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (showTranslation) Color(0xFF6366F1).copy(alpha = 0.35f) else colors.textPrimary.copy(alpha = 0.08f))
                            .border(
                                BorderStroke(
                                    1.dp,
                                    if (showTranslation) Color(0xFF818CF8) else Color(0xFF6366F1).copy(alpha = 0.25f)
                                ),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                showTranslation = !showTranslation
                                if (showTranslation) {
                                    com.x.carlauncher.media.XLocalPlayer.ensureLyricsTranslation()
                                }
                            }
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "ES ⇄ EN",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (showTranslation) Color(0xFF818CF8) else colors.textSecondary
                        )
                    }

                    // Status Pill
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.textPrimary.copy(alpha = 0.08f))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                color = colors.secondary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(text = "Descargando", fontSize = 10.sp, color = colors.secondary)
                        } else if (lyrics.isNotEmpty()) {
                            Box(modifier = Modifier.size(6.dp).background(Color(0xFF10B981), CircleShape))
                            Text(text = "Sincronizada", fontSize = 10.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Medium)
                        } else {
                            Text(text = "En espera", fontSize = 10.sp, color = colors.textSecondary)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ── Lyrics Content Area ───────────────────────────────────────────
            if (lyrics.isNotEmpty()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(lyrics) { index, lyric ->
                        val isActive = index == activeIndex

                        val textColor by animateColorAsState(
                            targetValue = if (isActive) colors.secondary else colors.textSecondary.copy(alpha = 0.7f),
                            animationSpec = tween(durationMillis = 200),
                            label = "textColor"
                        )

                        val bgBrush = if (isActive) {
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFF38BDF8).copy(alpha = 0.22f),
                                    Color(0xFFA855F7).copy(alpha = 0.12f),
                                    Color.Transparent
                                )
                            )
                        } else {
                            Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(bgBrush)
                                .clickable { onSeek(lyric.timeMs) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = lyric.text,
                                fontSize = if (isActive) 14.sp else 12.sp,
                                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Normal,
                                color = textColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            val trans = lyric.translation ?: com.x.carlauncher.media.LyricsTranslationManager.getTranslationSync(lyric.text)
                            if (showTranslation && !trans.isNullOrBlank()) {
                                Text(
                                    text = trans,
                                    fontSize = if (isActive) 11.sp else 9.sp,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isActive) Color(0xFFFDE047) else colors.textSecondary.copy(alpha = 0.65f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 1.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                // Empty / Waiting State
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = ColorGrayMid.copy(alpha = 0.4f),
                            modifier = Modifier.size(28.dp)
                        )
                        val isGeneric = mediaState.title.isBlank() ||
                            mediaState.title.equals("Sin reproduccion", ignoreCase = true) ||
                            mediaState.title.contains("Reproduciendo", ignoreCase = true) ||
                            mediaState.title.contains("Bluetooth", ignoreCase = true) ||
                            mediaState.title.contains("Audio conectado", ignoreCase = true)

                        val displayText = when {
                            isDownloading -> "Buscando letra sincronizada para:\n${mediaState.title}"
                            !isGeneric    -> "Sin letra sincronizada disponible para:\n${mediaState.title}"
                            else          -> "Reproduce una cancion para ver su letra sincronizada"
                        }

                        Text(
                            text = displayText,
                            fontSize = 11.sp,
                            color = ColorGrayMid,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

