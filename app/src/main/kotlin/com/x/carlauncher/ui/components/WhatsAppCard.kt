package com.x.carlauncher.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import com.x.carlauncher.ui.theme.XTheme
import com.x.carlauncher.voice.CarVoiceAssistant
import com.x.carlauncher.whatsapp.WhatsAppMessage
import com.x.carlauncher.whatsapp.WhatsAppMessageManager

/**
 * Tarjeta interactiva de mensajes de WhatsApp para la pantalla principal de XCarOS.
 * Disenada para visualizacion automotriz rapida, lectura de texto grande y lectura en voz alta (TTS).
 * Puro ASCII sin acentos ni caracteres especiales.
 */
@Composable
fun WhatsAppCard(
    voiceAssistant: CarVoiceAssistant,
    onToggleCluster: (() -> Unit)? = null,
    onOpenContacts: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = XTheme.colors
    val messages by WhatsAppMessageManager.messages.collectAsState()
    val unreadCount by WhatsAppMessageManager.unreadCount.collectAsState()
    val isChecking by WhatsAppMessageManager.isChecking.collectAsState()
    val lastCheckTime by WhatsAppMessageManager.lastCheckTime.collectAsState()

    val cardBrush = remember(colors) {
        Brush.verticalGradient(
            listOf(
                colors.surface,
                colors.surfaceDeep
            )
        )
    }

    LaunchedEffect(Unit) {
        WhatsAppMessageManager.performCheck(context, notifyIfNew = false)
    }

    val whatsappGreen = Color(0xFF25D366)
    val whatsappDarkGreen = Color(0xFF128C7E)

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (unreadCount > 0) whatsappGreen.copy(alpha = 0.6f) else colors.border
        ),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .background(cardBrush)
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // ── 1. Encabezado de la Tarjeta WhatsApp ─────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Logo + Titulo + Badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(whatsappGreen),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Chat,
                                contentDescription = "WhatsApp",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "WhatsApp",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )

                                if (unreadCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(whatsappGreen)
                                            .padding(horizontal = 6.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = "$unreadCount NUEVOS",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(colors.surface.copy(alpha = 0.5f))
                                            .padding(horizontal = 6.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = "AL DIA",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = colors.textSecondary
                                        )
                                    }
                                }
                            }

                            Text(
                                text = if (unreadCount > 0) "Toca un mensaje para leerlo" else "Sin mensajes pendientes",
                                fontSize = 10.sp,
                                color = if (unreadCount > 0) whatsappGreen else colors.textSecondary
                            )
                        }
                    }

                    // Acciones rapidas en encabezado
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Boton Escuchar Todo / Ultimo
                        if (messages.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    val unread = WhatsAppMessageManager.getUnreadMessages()
                                    val target = unread.firstOrNull() ?: messages.firstOrNull()
                                    if (target != null) {
                                        WhatsAppMessageManager.speakMessage(target, voiceAssistant)
                                    }
                                },
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(whatsappGreen.copy(alpha = 0.2f))
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "Leer en voz alta",
                                    tint = whatsappGreen,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }

                        // Boton para alternar al Cluster Ford si el usuario lo desea
                        if (onToggleCluster != null) {
                            IconButton(
                                onClick = onToggleCluster,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(colors.surface.copy(alpha = 0.6f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = "Ver Cluster Ford",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }

                        // Boton para ver Contactos integrados
                        if (onOpenContacts != null) {
                            IconButton(
                                onClick = onOpenContacts,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(colors.surface.copy(alpha = 0.6f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Phone,
                                    contentDescription = "Ver Contactos",
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }

                        // Boton Sincronizar / Refrescar WhatsApp (chequeo cada 5 min)
                        IconButton(
                            onClick = {
                                WhatsAppMessageManager.performCheck(context, notifyIfNew = false)
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(colors.surface.copy(alpha = 0.6f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Sincronizar mensajes cada 5 min",
                                tint = if (isChecking) whatsappGreen else Color(0xFF38BDF8),
                                modifier = Modifier.size(15.dp)
                            )
                        }

                        // Boton Abrir App WhatsApp
                        IconButton(
                            onClick = { WhatsAppMessageManager.launchWhatsAppApp(context) },
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(colors.surface.copy(alpha = 0.6f))
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Abrir WhatsApp",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }

                // ── 2. Contenido de Mensajes o Estado Vacio ─────────────────────
                if (messages.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        items(items = messages, key = { it.id }) { msg ->
                            WhatsAppMessageItem(
                                message = msg,
                                onSpeak = {
                                    WhatsAppMessageManager.speakMessage(msg, voiceAssistant)
                                },
                                onOpen = {
                                    WhatsAppMessageManager.openChat(context, msg)
                                },
                                onMarkRead = {
                                    WhatsAppMessageManager.markAsRead(msg.id)
                                }
                            )
                        }
                    }
                } else {
                    // Estado Vacio Elegante para Conduccion
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(whatsappGreen.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MarkChatRead,
                                contentDescription = null,
                                tint = whatsappGreen,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Bandeja de Entrada al Dia",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )

                        Text(
                            text = "Chequeo automatico cada 5 min activado. Los nuevos mensajes de WhatsApp apareceran aqui.",
                            fontSize = 10.sp,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Button(
                            onClick = { WhatsAppMessageManager.launchWhatsAppApp(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = whatsappDarkGreen),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                text = "Abrir WhatsApp",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                        }
                    }
                }

                // ── 3. Pie de Tarjeta: Botones Informativos ─────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val lastCheckStr = remember(lastCheckTime) {
                        WhatsAppMessageManager.formatTime(lastCheckTime)
                    }
                    Text(
                        text = "Chequeo cada 5m (Ultimo: $lastCheckStr)",
                        fontSize = 9.sp,
                        color = colors.textSecondary.copy(alpha = 0.85f)
                    )

                    if (messages.isNotEmpty()) {
                        Text(
                            text = "Limpiar lista",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { WhatsAppMessageManager.clearAll() }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Item individual para renderizar un mensaje de WhatsApp en la lista.
 */
@Composable
private fun WhatsAppMessageItem(
    message: WhatsAppMessage,
    onSpeak: () -> Unit,
    onOpen: () -> Unit,
    onMarkRead: () -> Unit
) {
    val colors = XTheme.colors

    val whatsappGreen = Color(0xFF25D366)
    val itemBg = if (!message.isRead) Color(0xFF1E293B).copy(alpha = 0.85f) else Color(0xFF0F172A).copy(alpha = 0.6f)
    val itemBorder = if (!message.isRead) whatsappGreen.copy(alpha = 0.45f) else Color(0xFF334155).copy(alpha = 0.4f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onSpeak() },
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, itemBorder),
        colors = CardDefaults.cardColors(containerColor = itemBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Avatar inicial + Texto del mensaje
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Indicador / Avatar
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (!message.isRead) whatsappGreen else Color(0xFF475569)),
                    contentAlignment = Alignment.Center
                ) {
                    val initial = message.sender.firstOrNull()?.uppercase() ?: "W"
                    Text(
                        text = initial,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (!message.isRead) Color.Black else Color.White
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = message.sender,
                            fontSize = 12.sp,
                            fontWeight = if (!message.isRead) FontWeight.Bold else FontWeight.Medium,
                            color = if (!message.isRead) Color.White else Color(0xFFCBD5E1),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Text(
                            text = message.formattedTime,
                            fontSize = 10.sp,
                            color = colors.textSecondary
                        )
                    }

                    if (!message.groupName.isNullOrBlank()) {
                        Text(
                            text = "Grupo: ${message.groupName}",
                            fontSize = 9.sp,
                            color = whatsappGreen,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = message.text,
                        fontSize = 11.sp,
                        fontWeight = if (!message.isRead) FontWeight.Normal else FontWeight.Light,
                        color = if (!message.isRead) Color(0xFFF1F5F9) else Color(0xFF94A3B8),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Botones de accion para conduccion: Escuchar, Abrir y Marcar Leido
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Boton Escuchar en voz alta
                IconButton(
                    onClick = onSpeak,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(whatsappGreen.copy(alpha = 0.2f))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Escuchar mensaje",
                        tint = whatsappGreen,
                        modifier = Modifier.size(15.dp)
                    )
                }

                // Boton Abrir chat
                IconButton(
                    onClick = onOpen,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(colors.surface.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Chat,
                        contentDescription = "Abrir chat",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }

                // Boton Marcar como leido
                if (!message.isRead) {
                    IconButton(
                        onClick = onMarkRead,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(colors.surface.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Marcar leido",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}
