package com.x.carlauncher.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x.carlauncher.media.RadioCategory
import com.x.carlauncher.media.RadioManager
import com.x.carlauncher.media.RadioStation
import com.x.carlauncher.system.AutomotiveIntentResolver
import com.x.carlauncher.ui.theme.XTheme

/**
 * Modulo Automotriz de Radio FM y Streaming Online para XCarOS.
 * Reemplaza el modulo de "Letra en Vivo" por un sintonizador directo
 * de estaciones de Monterrey, Rock Alternativo, Ska y Rock en Espanol.
 */
@Composable
fun RadioFmCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentStation by RadioManager.currentStation.collectAsState()
    val isPlaying by RadioManager.isPlaying.collectAsState()
    val isBuffering by RadioManager.isBuffering.collectAsState()
    val statusText by RadioManager.statusText.collectAsState()

    var selectedCategory by remember { mutableStateOf(RadioCategory.MONTERREY) }

    val filteredStations = remember(selectedCategory) {
        if (selectedCategory == RadioCategory.ALL) {
            RadioManager.stations
        } else {
            RadioManager.stations.filter { it.category == selectedCategory }
        }
    }

    Card(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color(0x5200E5FF)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF0F172A), Color(0xFF070B14))
                    )
                )
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // ── 1. Cabecera Radio FM + Estado En Vivo ─────────────────────────
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF00E5FF).copy(alpha = 0.2f))
                                    .border(
                                        BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
                                        RoundedCornerShape(10.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Radio,
                                    contentDescription = null,
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Radio FM en Vivo",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                    if (isPlaying) {
                                        Text(
                                            text = "● EN VIVO",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF10B981),
                                            modifier = Modifier
                                                .background(
                                                    Color(0xFF064E3B).copy(alpha = 0.8f),
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .border(
                                                    BorderStroke(0.5.dp, Color(0xFF10B981).copy(alpha = 0.5f)),
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    } else if (isBuffering) {
                                        Text(
                                            text = "● SINTONIZANDO",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFF59E0B),
                                            modifier = Modifier
                                                .background(
                                                    Color(0xFF78350F).copy(alpha = 0.8f),
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .border(
                                                    BorderStroke(0.5.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = statusText,
                                    fontSize = 10.sp,
                                    color = Color(0xFF94A3B8),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Boton de Detener transmision
                        if (isPlaying || isBuffering) {
                            IconButton(
                                onClick = { RadioManager.stop() },
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEF4444).copy(alpha = 0.2f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Detener radio",
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // ── 2. Filtros de Categoria (Monterrey, Rock Alt, Ska, Rock ES) ──
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(RadioCategory.values()) { category ->
                            val isSelected = selectedCategory == category
                            val chipBg = if (isSelected) {
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF00E5FF).copy(alpha = 0.35f), Color(0xFF38BDF8).copy(alpha = 0.2f))
                                )
                            } else {
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF1E293B).copy(alpha = 0.5f), Color(0xFF1E293B).copy(alpha = 0.3f))
                                )
                            }
                            val chipBorder = if (isSelected) Color(0xFF00E5FF) else Color(0xFF334155).copy(alpha = 0.5f)
                            val textColor = if (isSelected) Color.White else Color(0xFF94A3B8)

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(chipBg)
                                    .border(BorderStroke(1.dp, chipBorder), RoundedCornerShape(12.dp))
                                    .clickable { selectedCategory = category }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = category.chipLabel,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = textColor
                                )
                            }
                        }
                    }
                }

                // ── 3. Lista de Estaciones Filtradas ─────────────────────────────
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(filteredStations, key = { it.id }) { station ->
                        val isThisActive = currentStation?.id == station.id
                        val itemAccent = Color(station.accentColor)

                        val itemBg = if (isThisActive) {
                            Brush.horizontalGradient(
                                listOf(itemAccent.copy(alpha = 0.22f), Color(0xFF1E293B).copy(alpha = 0.5f))
                            )
                        } else {
                            Brush.horizontalGradient(
                                listOf(Color(0xFF1E293B).copy(alpha = 0.45f), Color(0xFF0F172A).copy(alpha = 0.3f))
                            )
                        }

                        val itemBorder = if (isThisActive) {
                            BorderStroke(1.dp, itemAccent.copy(alpha = 0.8f))
                        } else {
                            BorderStroke(0.5.dp, Color(0xFF334155).copy(alpha = 0.4f))
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(itemBg)
                                .border(itemBorder, RoundedCornerShape(14.dp))
                                .clickable {
                                    RadioManager.togglePlay(context, station)
                                }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Frecuencia Dial + Info de la estacion
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(9.dp)
                            ) {
                                // Insignia Dial / Frecuencia
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(itemAccent.copy(alpha = 0.2f))
                                        .border(BorderStroke(1.dp, itemAccent.copy(alpha = 0.6f)), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = station.frequency,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = itemAccent
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = station.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = station.description,
                                        fontSize = 9.sp,
                                        color = Color(0xFF94A3B8),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Boton de Reproducir / Pausar / Buffer
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isThisActive && isPlaying) itemAccent else Color(0xFF1E293B)
                                    )
                                    .border(
                                        BorderStroke(
                                            1.dp,
                                            if (isThisActive) itemAccent else Color(0xFF475569)
                                        ),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isThisActive && isBuffering) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = itemAccent,
                                        strokeWidth = 2.dp
                                    )
                                } else if (isThisActive && isPlaying) {
                                    Icon(
                                        imageVector = Icons.Default.Pause,
                                        contentDescription = "Pausar",
                                        tint = Color.Black,
                                        modifier = Modifier.size(16.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Reproducir",
                                        tint = if (isThisActive) itemAccent else Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // ── 4. Barra Inferior Sintonizador / Reproduciendo Ahora ──────────
                if (currentStation != null) {
                    val activeStation = currentStation!!
                    val activeColor = Color(activeStation.accentColor)

                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF1E293B).copy(alpha = 0.9f), Color(0xFF0F172A).copy(alpha = 0.95f))
                                )
                            )
                            .border(BorderStroke(1.dp, activeColor.copy(alpha = 0.5f)), RoundedCornerShape(14.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                // Ecualizador visual animado
                                if (isPlaying) {
                                    LiveEqualizerBars(color = activeColor)
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.GraphicEq,
                                        contentDescription = null,
                                        tint = Color(0xFF94A3B8),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${activeStation.frequency} - ${activeStation.name}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = activeStation.city,
                                        fontSize = 9.sp,
                                        color = activeColor,
                                        maxLines = 1
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                IconButton(
                                    onClick = { RadioManager.togglePlay(context, activeStation) },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                        contentDescription = null,
                                        tint = activeColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Barras animadas de ecualizador en vivo para representar la transmision radial.
 */
@Composable
private fun LiveEqualizerBars(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "eq_bars")

    val h1 by infiniteTransition.animateFloat(
        initialValue = 4f, targetValue = 16f,
        animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse),
        label = "h1"
    )
    val h2 by infiniteTransition.animateFloat(
        initialValue = 14f, targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(320, easing = LinearEasing), RepeatMode.Reverse),
        label = "h2"
    )
    val h3 by infiniteTransition.animateFloat(
        initialValue = 6f, targetValue = 18f,
        animationSpec = infiniteRepeatable(tween(480, easing = LinearEasing), RepeatMode.Reverse),
        label = "h3"
    )

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .height(18.dp)
            .padding(end = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(h1.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(color)
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(h2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(color)
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(h3.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(color)
        )
    }
}
