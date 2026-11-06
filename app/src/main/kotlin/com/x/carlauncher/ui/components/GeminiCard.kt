package com.x.carlauncher.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x.carlauncher.ui.theme.XTheme

private val GeminiBlue    = Color(0xFF4285F4)
private val GeminiPurple  = Color(0xFF9B72CB)
private val GeminiPink    = Color(0xFFD96570)
private val GeminiCyan    = Color(0xFF38BDF8)
private val GeminiIndigo  = Color(0xFF818CF8)

/**
 * Modulo integrado de Google Gemini para el tablero automotriz de XCarOS.
 *
 * Integra completamente el asistente de IA conversacional y comandos por voz
 * dentro de la pantalla del vehiculo, evitando ventanas flotantes externas (FloatyActivity).
 * Muestra el logo oficial de Gemini (estrella de cuatro puntas con gradiente),
 * ondas de audio reactivas en tiempo real, transcripcion en vivo y atajos rapidos seguros.
 */
@Composable
fun GeminiCard(
    isListening: Boolean,
    recognizedText: String,
    feedback: String,
    audioRmsLevel: Float,
    onMicClick: () -> Unit,
    onCommandClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = XTheme.colors
    val cardBrush = remember(colors) {
        Brush.verticalGradient(listOf(colors.surface, colors.surfaceDeep))
    }

    // Animacion sutil de pulso al escuchar o procesar
    val infiniteTransition = rememberInfiniteTransition(label = "geminiPulseTransition")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue  = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "geminiPulseAlpha"
    )

    Card(
        modifier = modifier,
        shape  = RoundedCornerShape(24.dp),
        border = BorderStroke(
            1.dp,
            if (isListening) GeminiCyan.copy(alpha = 0.85f) else colors.border
        ),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .background(cardBrush)
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── TOP ROW: Gemini Sparkle Icon + Title + Live Status Badge ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Gemini Brand: Icono de estrella asteroide + Titulo
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    GeminiSparkleIcon(modifier = Modifier.size(15.dp))

                    Text(
                        text = "Gemini",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // Live Status Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(
                                color = if (isListening) GeminiCyan.copy(alpha = pulseAlpha) else GeminiBlue,
                                shape = CircleShape
                            )
                    )
                    Text(
                        text = if (isListening) "Escuchando" else "Listo",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isListening) GeminiCyan else colors.textSecondary
                    )
                }
            }

            // ── CONTENT ROW: Live Waveform / Query Feedback + Mic Button ────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Columna interactiva izquierda: texto en vivo o atajos
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    if (isListening) {
                        // Visualizador dinamico de ondas con el gradiente caracteristico de Gemini
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.height(18.dp)
                        ) {
                            val rms = audioRmsLevel.coerceIn(0f, 1f)
                            val h1 = (4f + rms * 9f).dp
                            val h2 = (4f + rms * 14f).dp
                            val h3 = (4f + rms * 12f).dp
                            val h4 = (4f + rms * 10f).dp
                            val h5 = (4f + rms * 7f).dp

                            Box(modifier = Modifier.width(3.dp).height(h1).background(GeminiBlue, RoundedCornerShape(2.dp)))
                            Box(modifier = Modifier.width(3.dp).height(h2).background(GeminiPurple, RoundedCornerShape(2.dp)))
                            Box(modifier = Modifier.width(3.dp).height(h3).background(GeminiPink, RoundedCornerShape(2.dp)))
                            Box(modifier = Modifier.width(3.dp).height(h4).background(GeminiCyan, RoundedCornerShape(2.dp)))
                            Box(modifier = Modifier.width(3.dp).height(h5).background(GeminiIndigo, RoundedCornerShape(2.dp)))
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = if (recognizedText.isNotBlank()) "\"$recognizedText\"" else "Pideselo a Gemini...",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        // Retroalimentacion de ultima respuesta o sugerencia de inicio
                        val isFeedbackActive = feedback.isNotBlank() && feedback != "Listo para escuchar."
                        val displayText = if (isFeedbackActive) feedback else "Pideselo a Gemini"

                        Text(
                            text = displayText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isFeedbackActive) GeminiCyan else Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Atajos rapidos tactiles automotrices
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GeminiQuickActionChip(label = "Clima") { onCommandClick("clima") }
                            GeminiQuickActionChip(label = "Musica") { onCommandClick("reproducir") }
                            GeminiQuickActionChip(label = "Mensajes") { onCommandClick("leer mensajes") }
                        }
                    }
                }

                // Boton de microfono integrado con gradiente oficial de Gemini
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (isListening) {
                                Brush.linearGradient(listOf(GeminiCyan, GeminiPurple))
                            } else {
                                Brush.linearGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A)))
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = if (isListening) GeminiCyan else GeminiPurple.copy(alpha = 0.6f),
                            shape = CircleShape
                        )
                        .clickable(onClick = onMicClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Gemini Mic",
                        tint = if (isListening) Color.White else GeminiCyan,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

/**
 * Dibuja la iconica estrella asteroide de cuatro puntas de Google Gemini.
 */
@Composable
fun GeminiSparkleIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f

        val gradient = Brush.linearGradient(
            listOf(GeminiBlue, GeminiPurple, GeminiPink)
        )

        val path = Path().apply {
            moveTo(cx, 0f)
            quadraticBezierTo(cx, cy, w, cy)
            quadraticBezierTo(cx, cy, cx, h)
            quadraticBezierTo(cx, cy, 0f, cy)
            quadraticBezierTo(cx, cy, cx, 0f)
            close()
        }

        drawPath(path = path, brush = gradient)
    }
}

@Composable
private fun GeminiQuickActionChip(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1E293B))
            .border(0.5.dp, Color(0xFF334155), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFE2E8F0),
            maxLines = 1
        )
    }
}
