package com.x.carlauncher.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.x.carlauncher.ui.theme.AppTheme
import com.x.carlauncher.ui.theme.ThemeManager
import com.x.carlauncher.ui.theme.XTheme
import com.x.carlauncher.voice.CarVoiceAssistant

/**
 * Automotive Theme Selection Modal for XCarOS.
 * Provides instant real-time theme preview and voice confirmation.
 */
@Composable
fun ThemePickerModal(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val currentTheme by ThemeManager.currentTheme.collectAsState()
    val activeColors = XTheme.colors
    val voiceAssistant = remember { CarVoiceAssistant.getInstance(context) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth(0.85f)
                    .heightIn(max = 500.dp)
                    .clickable(enabled = false) {}
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.5.dp, activeColors.border),
                colors = CardDefaults.cardColors(containerColor = activeColors.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    // ── Header Row ────────────────────────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(activeColors.primary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Palette,
                                    contentDescription = "Temas",
                                    tint = activeColors.secondary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Paleta de Temas PabloCarOS",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = activeColors.textPrimary
                                )
                                Text(
                                    text = "Personaliza la apariencia y colores de tu consola",
                                    fontSize = 13.sp,
                                    color = activeColors.textSecondary
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(36.dp)
                                .background(activeColors.surfaceDeep, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cerrar",
                                tint = activeColors.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // ── Grid of 6 Automotive Themes (3 columns x 2 rows) ─────
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(AppTheme.values()) { theme ->
                            val isSelected = theme == currentTheme
                            val themeColors = remember(theme) { ThemeManager.getColors(theme) }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(115.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        if (theme != currentTheme) {
                                            ThemeManager.setTheme(theme, context)
                                            voiceAssistant.speak("Tema ${theme.displayName} activado")
                                        }
                                    },
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) themeColors.secondary else themeColors.border.copy(alpha = 0.5f)
                                ),
                                colors = CardDefaults.cardColors(containerColor = themeColors.surface)
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        // Title & Checkmark
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = theme.displayName,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = themeColors.textPrimary,
                                                maxLines = 1
                                            )
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = "Activo",
                                                    tint = themeColors.secondary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }

                                        // Subtitle
                                        Text(
                                            text = theme.subtitle,
                                            fontSize = 11.sp,
                                            color = themeColors.textSecondary,
                                            maxLines = 2,
                                            lineHeight = 14.sp
                                        )

                                        // Color Swatches
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            theme.previewColors.forEach { color ->
                                                Box(
                                                    modifier = Modifier
                                                        .size(16.dp)
                                                        .clip(CircleShape)
                                                        .background(color)
                                                        .border(0.5.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // ── Bottom Action Row ─────────────────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = activeColors.primary,
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                text = "Listo",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

