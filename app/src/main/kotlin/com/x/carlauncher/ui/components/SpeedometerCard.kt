package com.x.carlauncher.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x.carlauncher.obd.ObdData
import com.x.carlauncher.system.AutomotiveIntentResolver
import com.x.carlauncher.ui.theme.XTheme
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Interactive Speedometer card with Canvas arc gauge & live OBD-II CAN bus telemetry.
 * Tapping the card toggles between MPH and KM/H dynamically.
 *
 * @param speedMph  Current vehicle speed in mph (OBD-II CAN sensor prioritized, fallback to GPS).
 * @param obdData   Live telemetry from Bluetooth ELM327 OBD-II adapter.
 */
@Composable
fun SpeedometerCard(
    speedMph: Float,
    obdData: ObdData = ObdData(),
    onObdClick: (() -> Unit)? = null,
    onCardClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = XTheme.colors
    val speedometerCardBrush = remember(colors) {
        Brush.verticalGradient(listOf(colors.surface, colors.surfaceDeep))
    }
    var isKmh by remember { mutableStateOf(false) }

    val currentSpeed = if (isKmh) speedMph * 1.60934f else speedMph
    val maxGaugeSpeed = if (isKmh) 200f else 120f
    val unitLabel = if (isKmh) "km/h" else "mph"

    Card(
        modifier = modifier.clickable {
            if (onCardClick != null) {
                onCardClick()
            } else {
                isKmh = !isKmh
            }
        },
        shape    = RoundedCornerShape(24.dp),
        border   = BorderStroke(1.dp, if (obdData.isConnected) Color(0xFF10B981) else colors.border),
        colors   = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .background(speedometerCardBrush)
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier            = Modifier.fillMaxSize()
            ) {
                // Animated speed value for smooth arc sweep
                val animatedSpeed by animateFloatAsState(
                    targetValue   = currentSpeed.coerceIn(0f, maxGaugeSpeed),
                    animationSpec = tween(durationMillis = 300),
                    label         = "speedAnim"
                )

                Box(
                    modifier         = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { isKmh = !isKmh },
                    contentAlignment = Alignment.Center
                ) {
                    SpeedometerCanvas(
                        animatedSpeed = animatedSpeed,
                        currentSpeed  = currentSpeed,
                        maxSpeed      = maxGaugeSpeed,
                        unit          = unitLabel
                    )
                }

                // ── Live OBD-II Telemetry / Status Row ──
                if (obdData.isConnected) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ObdTelemetryPill(
                            label = "${obdData.rpm}",
                            unit  = "RPM",
                            color = Color(0xFF38BDF8)
                        )
                        ObdTelemetryPill(
                            label = "${obdData.coolantTempC} C",
                            unit  = "Motor",
                            color = if (obdData.coolantTempC > 105) Color(0xFFEF4444) else Color(0xFF10B981)
                        )
                        ObdTelemetryPill(
                            label = String.format(Locale.US, "%.1fV", obdData.batteryVoltage),
                            unit  = "Bat",
                            color = Color(0xFFFBBF24)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onObdClick?.invoke() }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Color(0xFF10B981), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "OBD2 CAN - Ford Fiesta",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF10B981)
                        )
                    }
                } else {
                    val isConnecting = obdData.connectionStatus.contains("Conectando", ignoreCase = true)
                    val statusDotColor = if (isConnecting) Color(0xFFFBBF24) else Color(0xFF94A3B8)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.surface.copy(alpha = 0.5f))
                            .clickable {
                                if (onObdClick != null) {
                                    onObdClick()
                                } else {
                                    AutomotiveIntentResolver.launchSafely(
                                        context,
                                        AutomotiveIntentResolver.getBluetoothSettingsIntent(context)
                                    )
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(statusDotColor, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "OBD2: ${obdData.connectionStatus}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isConnecting) Color(0xFFFBBF24) else colors.textSecondary
                        )
                    }
                    Text(
                        text     = "Toca para abrir dashboard",
                        fontSize = 10.sp,
                        color    = colors.textSecondary.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ObdTelemetryPill(
    label: String,
    unit: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .background(Color(0x22FFFFFF), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = unit,
            fontSize = 9.sp,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun SpeedometerCanvas(
    animatedSpeed: Float,
    currentSpeed:  Float,
    maxSpeed:      Float,
    unit:          String
) {
    val colors = XTheme.colors
    val arcProgressColor = colors.secondary
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth  = 12.dp.toPx()
            val arcPadding   = strokeWidth / 2f + 8.dp.toPx()
            val diameter     = minOf(size.width, size.height) - arcPadding * 2f
            val topLeft      = Offset(
                x = (size.width  - diameter) / 2f,
                y = (size.height - diameter) / 2f + 12.dp.toPx()
            )
            val arcSize      = Size(diameter, diameter)
            val startAngle   = 150f
            val totalSweep   = 240f

            // Background arc (dark gray track)
            drawArc(
                color      = Color(0xFF2D3748),
                startAngle = startAngle,
                sweepAngle = totalSweep,
                useCenter  = false,
                topLeft    = topLeft,
                size       = arcSize,
                style      = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Progress arc (neon blue)
            val progressSweep = (animatedSpeed / maxSpeed) * totalSweep
            if (progressSweep > 0f) {
                drawArc(
                    color      = arcProgressColor,
                    startAngle = startAngle,
                    sweepAngle = progressSweep,
                    useCenter  = false,
                    topLeft    = topLeft,
                    size       = arcSize,
                    style      = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            // Tick marks
            val centerX   = size.width  / 2f
            val centerY   = size.height / 2f + 12.dp.toPx()
            val radius    = diameter / 2f
            val outerR    = radius + strokeWidth / 2f + 4.dp.toPx()
            val innerR    = radius - strokeWidth / 2f - 4.dp.toPx()
            val tickColor = Color(0xFF4B5563)
            val tickCount = 7

            for (i in 0 until tickCount) {
                val fraction   = i / (tickCount - 1).toFloat()
                val angleDeg   = startAngle + fraction * totalSweep
                val angleRad   = Math.toRadians(angleDeg.toDouble())
                val cosA       = cos(angleRad).toFloat()
                val sinA       = sin(angleRad).toFloat()
                drawLine(
                    color       = tickColor,
                    start       = Offset(centerX + outerR * cosA, centerY + outerR * sinA),
                    end         = Offset(centerX + innerR * cosA, centerY + innerR * sinA),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }

        // Center text overlay
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text       = currentSpeed.toInt().toString(),
                fontSize   = 38.sp,
                fontWeight = FontWeight.Bold,
                color      = colors.textPrimary
            )
            Text(
                text     = unit,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color    = colors.secondary
            )
        }
    }
}
