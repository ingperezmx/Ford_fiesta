package com.x.carlauncher.ui.screens

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.x.carlauncher.illumination.VehicleIlluminationManager
import com.x.carlauncher.canbus.DoorState
import com.x.carlauncher.obd.ObdData
import com.x.carlauncher.ui.theme.XTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pantalla Completa del Dashboard / Cluster Oficial de Ford Fiesta 2016 (Transmision Estandar 5-Velocidades).
 *
 * Ofrece una experiencia de cluster digital automotriz inmersiva en toda la pantalla:
 * - Esferas gigantes binnacle con biseles cromados 3D para Tacometro (0-8k RPM) y Velocimetro (MPH / KM/H).
 * - Pantalla central MID widescreen con selector de caja estandar [N, 1, 2, 3, 4, 5, R].
 * - Indicador oficial de cambio de marcha Ford (Gear Shift Indicator GSI ▲).
 * - Telemetria en tiempo real: temperatura de refrigerante, nivel de combustible, voltaje de bateria y sensores de puertas.
 */
@Composable
fun ExpandedFordFiestaDashboardScreen(
    speedMph: Float,
    obdData: ObdData = ObdData(),
    doorState: DoorState? = null,
    isLightsOn: Boolean = false,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isKmh by remember { mutableStateOf(false) }
    var tripScreenMode by remember { mutableStateOf(0) } // 0: Marcha & Velocidad, 1: Motor, 2: Combustible, 3: Diagnostico

    // Calculo de marcha manual (caja IB5 de 5 velocidades estandar)
    // a partir de la velocidad REAL del GPS / OBD.
    val manualGear = remember(speedMph) {
        when {
            speedMph < 1.0f -> "N"
            speedMph < 14f -> "1"
            speedMph < 26f -> "2"
            speedMph < 40f -> "3"
            speedMph < 55f -> "4"
            else -> "5"
        }
    }

    // RPM real: solo del OBD-II. Sin OBD conectado no se inventa ningún
    // ralentí: el tacómetro marca 0 hasta que hay telemetría real.
    val liveRpm: Float = remember(obdData.isConnected, obdData.rpm) {
        if (obdData.isConnected && obdData.rpm > 0) {
            obdData.rpm.toFloat()
        } else {
            0f
        }
    }

    // Ford Gear Shift Indicator (GSI)
    val shouldShiftUp = remember(manualGear, speedMph, liveRpm) {
        manualGear in listOf("1", "2", "3", "4") && (liveRpm >= 2100f || (manualGear == "1" && speedMph > 13f) || (manualGear == "2" && speedMph > 24f) || (manualGear == "3" && speedMph > 38f) || (manualGear == "4" && speedMph > 52f))
    }

    val currentSpeed = if (isKmh) speedMph * 1.60934f else speedMph
    val maxSpeed = if (isKmh) 220f else 140f
    val speedUnitLabel = if (isKmh) "km/h" else "mph"

    val animatedRpm by animateFloatAsState(
        targetValue = liveRpm.coerceIn(0f, 8000f),
        animationSpec = tween(durationMillis = 200),
        label = "fullRpmAnim"
    )

    val animatedSpeed by animateFloatAsState(
        targetValue = currentSpeed.coerceIn(0f, maxSpeed),
        animationSpec = tween(durationMillis = 200),
        label = "fullSpeedAnim"
    )

    // Fondo automotriz inmersivo (negro carbon profundo con destellos azul metalico)
    val screenBrush = remember {
        Brush.radialGradient(
            colors = listOf(
                Color(0xFF0F172A),
                Color(0xFF070B14),
                Color(0xFF02040A)
            ),
            radius = 1200f
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(screenBrush)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── 1. Barra de Encabezado Superior: Volver + Insignia Ford Fiesta + OBD ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Boton Volver al Dashboard
                Row(
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E293B).copy(alpha = 0.85f))
                        .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .clickable(onClick = onNavigateBack)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "VOLVER AL LAUNCHER",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // Insignia Oficial Ford Fiesta 2016 Sedan Manual
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E3A8A).copy(alpha = 0.5f))
                            .border(1.2.dp, Color(0xFF38BDF8), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "FORD FIESTA",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF00E5FF),
                            letterSpacing = 2.sp
                        )
                    }

                    Text(
                        text = "2016 Sedan - Transmision Estandar IB5",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF94A3B8)
                    )
                }

                // Conmutador de Unidad (MPH / KM/H) + Estado de enlace OBD
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F172A))
                            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .clickable { isKmh = !isKmh }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "UNIDAD: ${speedUnitLabel.uppercase()}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E5FF)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F172A))
                            .clickable { com.x.carlauncher.obd.ObdManager.retryConnection() }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (obdData.isConnected) Color(0xFF10B981) else Color(0xFF64748B))
                        )
                        Text(
                            text = if (obdData.isConnected) {
                                if (obdData.isVlinkerFd) "vLinker FD (HS+MS)" else "OBD2 CAN BUS"
                            } else "GPS TELEMETRY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (obdData.isConnected) Color(0xFF10B981) else Color(0xFF94A3B8)
                        )
                    }
                }
            }

            // ── 2. Cluster Central en Pantalla Completa: Tacometro Gigante + MID + Velocimetro Gigante ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Reloj Izquierdo Grande: Tacometro RPM (0-8000)
                Box(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    FullScreenGaugeCanvas(
                        value = animatedRpm,
                        maxValue = 8000f,
                        majorTicks = 8,
                        title = "RPM",
                        subtitle = "x1000 r/min",
                        isRedline = true,
                        needleColor = Color(0xFF00E5FF),
                        formatValue = { String.format("%.1f", it / 1000f) }
                    )
                }

                // Pantalla Central LCD MID Widescreen
                Box(
                    modifier = Modifier
                        .weight(1.4f)
                        .fillMaxHeight()
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    FullScreenCenterLcdDisplay(
                        speedMph = speedMph,
                        manualGear = manualGear,
                        shouldShiftUp = shouldShiftUp,
                        obdData = obdData,
                        tripMode = tripScreenMode,
                        isKmh = isKmh,
                        onCycleMode = { tripScreenMode = (tripScreenMode + 1) % 4 }
                    )
                }

                // Reloj Derecho Grande: Velocimetro
                Box(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    FullScreenGaugeCanvas(
                        value = animatedSpeed,
                        maxValue = maxSpeed,
                        majorTicks = 7,
                        title = speedUnitLabel.uppercase(),
                        subtitle = "VELOCIDAD",
                        isRedline = false,
                        needleColor = Color(0xFF00E5FF),
                        formatValue = { it.toInt().toString() }
                    )
                }
            }

            // ── 3. Barra Inferior Completa: Testigos Luminosos Oficiales Ford + Detalles ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0A1020))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Testigo Faros (Toca para alternar Modo Auto / Manual)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            VehicleIlluminationManager.toggleManual(context)
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LightMode,
                        contentDescription = "Faros",
                        tint = if (isLightsOn) Color(0xFF10B981) else Color(0xFF334155),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isLightsOn) "LUCES ENCENDIDAS" else "LUCES APAGADAS",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isLightsOn) Color(0xFF10B981) else Color(0xFF64748B)
                    )
                }

                // Testigo Puertas
                val anyDoorOpen = doorState?.let {
                    it.frontLeft || it.frontRight || it.rearLeft || it.rearRight || it.trunk || it.hood
                } == true
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MeetingRoom,
                        contentDescription = "Puertas",
                        tint = if (anyDoorOpen) Color(0xFFEF4444) else Color(0xFF334155),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (anyDoorOpen) "PUERTA ABIERTA" else "PUERTAS CERRADAS",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (anyDoorOpen) Color(0xFFEF4444) else Color(0xFF64748B)
                    )
                }

                // Testigo Bateria 12V
                val batteryOk = obdData.batteryVoltage >= 12.2f
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BatteryChargingFull,
                        contentDescription = "Bateria",
                        tint = if (batteryOk) Color(0xFF34D399) else Color(0xFFF59E0B),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${String.format("%.1f", obdData.batteryVoltage)}V",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (batteryOk) Color(0xFF34D399) else Color(0xFFF59E0B)
                    )
                }

                // Testigo Motor Check Engine
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Motor",
                        tint = if (obdData.isConnected) Color(0xFF334155) else Color(0xFFF59E0B).copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (obdData.isConnected) "SISTEMA MOTOR OK" else "OBD LISTO PARA EMPAREJAR",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (obdData.isConnected) Color(0xFF64748B) else Color(0xFFF59E0B)
                    )
                }

                // Selector de Modo
                Text(
                    text = "TOCA EL CENTRO PARA MODO DE PANTALLA",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF00E5FF).copy(alpha = 0.8f)
                )
            }
        }
    }
}

/**
 * Esfera analogica en pantalla completa para el cluster Ford Fiesta.
 */
@Composable
private fun FullScreenGaugeCanvas(
    value: Float,
    maxValue: Float,
    majorTicks: Int,
    title: String,
    subtitle: String,
    isRedline: Boolean,
    needleColor: Color,
    formatValue: (Float) -> String
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val diameter = minOf(size.width, size.height) * 0.96f
        val radius = diameter / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        // 1. Bisel Cromado Exterior 3D Profundo
        val chromeBrush = Brush.linearGradient(
            colors = listOf(
                Color(0xFFE2E8F0),
                Color(0xFF64748B),
                Color(0xFF1E293B),
                Color(0xFF94A3B8),
                Color(0xFFF8FAFC),
                Color(0xFF475569)
            ),
            start = Offset(center.x - radius, center.y - radius),
            end = Offset(center.x + radius, center.y + radius)
        )

        drawCircle(
            brush = chromeBrush,
            radius = radius,
            center = center,
            style = Stroke(width = 3.5.dp.toPx())
        )

        // Bisel interior negro rebajado
        drawCircle(
            color = Color(0xFF0A0F1D),
            radius = radius * 0.96f,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )

        // Fondo interior del dial con gradiente de profundidad
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF131D31), Color(0xFF030611)),
                center = center,
                radius = radius * 0.94f
            ),
            radius = radius * 0.94f,
            center = center
        )

        val startAngle = 135f
        val sweepAngle = 270f
        val arcRadius = radius * 0.77f
        val arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)
        val arcSize = Size(arcRadius * 2, arcRadius * 2)

        // Arco base
        drawArc(
            color = Color(0xFF1E293B),
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )

        // Arco de zona roja (Redline)
        if (isRedline) {
            val redlineStartRatio = 6.5f / 8.0f
            val redlineStart = startAngle + sweepAngle * redlineStartRatio
            val redlineSweep = sweepAngle * (1f - redlineStartRatio)
            drawArc(
                color = Color(0xFFEF4444),
                startAngle = redlineStart,
                sweepAngle = redlineSweep,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Marcas de la escala (Ticks) y numeros impresos
        val paint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        for (i in 0..majorTicks) {
            val ratio = i.toFloat() / majorTicks
            val angleDeg = startAngle + sweepAngle * ratio
            val angleRad = Math.toRadians(angleDeg.toDouble())

            val tickColor = if (isRedline && ratio >= (6.5f / 8.0f)) Color(0xFFEF4444) else Color(0xFFCBD5E1)
            val outerX = center.x + (radius * 0.86f) * cos(angleRad).toFloat()
            val outerY = center.y + (radius * 0.86f) * sin(angleRad).toFloat()
            val innerX = center.x + (radius * 0.73f) * cos(angleRad).toFloat()
            val innerY = center.y + (radius * 0.73f) * sin(angleRad).toFloat()

            drawLine(
                color = tickColor,
                start = Offset(innerX, innerY),
                end = Offset(outerX, outerY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Numero en la escala
            val numberX = center.x + (radius * 0.63f) * cos(angleRad).toFloat()
            val numberY = center.y + (radius * 0.63f) * sin(angleRad).toFloat() + 4.dp.toPx()
            paint.color = if (isRedline && ratio >= (6.5f / 8.0f)) Color(0xFFEF4444).toArgb() else Color(0xFFE2E8F0).toArgb()
            paint.textSize = (radius * 0.13f).coerceIn(10.sp.toPx(), 15.sp.toPx())
            val label = if (isRedline) i.toString() else (i * 20).toString()
            drawContext.canvas.nativeCanvas.drawText(label, numberX, numberY, paint)
        }

        // Aguja Ford Ice Blue
        val currentRatio = (value / maxValue).coerceIn(0f, 1f)
        val needleAngleDeg = startAngle + sweepAngle * currentRatio
        val needleAngleRad = Math.toRadians(needleAngleDeg.toDouble())

        val needleLength = radius * 0.79f
        val tipX = center.x + needleLength * cos(needleAngleRad).toFloat()
        val tipY = center.y + needleLength * sin(needleAngleRad).toFloat()

        // Sombra suave de la aguja
        drawLine(
            color = Color.Black.copy(alpha = 0.6f),
            start = center + Offset(2f, 2f),
            end = Offset(tipX + 2f, tipY + 2f),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Cuerpo de la aguja (Ford Ice Blue)
        drawLine(
            color = needleColor,
            start = center,
            end = Offset(tipX, tipY),
            strokeWidth = 2.8.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Nucleo blanco de iluminacion
        val coreLength = needleLength * 0.88f
        val coreTipX = center.x + coreLength * cos(needleAngleRad).toFloat()
        val coreTipY = center.y + coreLength * sin(needleAngleRad).toFloat()
        drawLine(
            color = Color.White.copy(alpha = 0.9f),
            start = center,
            end = Offset(coreTipX, coreTipY),
            strokeWidth = 1.2.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Tapa central cromada 3D
        drawCircle(color = Color(0xFF0F172A), radius = radius * 0.17f, center = center)
        drawCircle(color = Color(0xFF64748B), radius = radius * 0.17f, center = center, style = Stroke(width = 1.5.dp.toPx()))
        drawCircle(color = Color(0xFF00E5FF), radius = radius * 0.06f, center = center)

        // Lectura digital interior
        paint.color = Color.White.toArgb()
        paint.textSize = (radius * 0.20f).coerceIn(14.sp.toPx(), 22.sp.toPx())
        drawContext.canvas.nativeCanvas.drawText(
            formatValue(value),
            center.x,
            center.y + radius * 0.44f,
            paint
        )

        paint.color = Color(0xFF00E5FF).toArgb()
        paint.textSize = (radius * 0.11f).coerceIn(8.sp.toPx(), 12.sp.toPx())
        drawContext.canvas.nativeCanvas.drawText(
            title,
            center.x,
            center.y + radius * 0.58f,
            paint
        )
    }
}

/**
 * Pantalla Central LCD MID en Formato Grande Widescreen.
 */
@Composable
private fun FullScreenCenterLcdDisplay(
    speedMph: Float,
    manualGear: String,
    shouldShiftUp: Boolean,
    obdData: ObdData,
    tripMode: Int,
    isKmh: Boolean,
    onCycleMode: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF081226))
            .border(1.5.dp, Color(0xFF1E3A8A).copy(alpha = 0.8f), RoundedCornerShape(16.dp))
            .clickable(onClick = onCycleMode)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Fila de Marchas Manuales [N, 1, 2, 3, 4, 5, R] + Indicador de Cambio ▲
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("N", "1", "2", "3", "4", "5", "R").forEach { g ->
                        val isCurrent = (g == manualGear)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isCurrent) Color(0xFF00E5FF) else Color(0xFF0F172A))
                                .border(0.8.dp, if (isCurrent) Color(0xFF38BDF8) else Color(0xFF1E293B), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = g,
                                fontSize = 11.sp,
                                fontWeight = if (isCurrent) FontWeight.Black else FontWeight.Bold,
                                color = if (isCurrent) Color(0xFF020617) else Color(0xFF64748B)
                            )
                        }
                    }
                }

                // Indicador GSI de cambio de marcha
                if (shouldShiftUp) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF10B981).copy(alpha = 0.2f))
                            .border(1.dp, Color(0xFF10B981), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "▲ SHIFT",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF10B981)
                        )
                        val nextGear = (manualGear.toIntOrNull() ?: 1) + 1
                        Text(
                            text = "[$nextGear]",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                } else {
                    Text(
                        text = "ECO MODE",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10B981).copy(alpha = 0.7f)
                    )
                }
            }

            // Cuerpo Central Ampliado
            when (tripMode) {
                0 -> {
                    // MODO 0: Velocidad Gigante + Marcha
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        val displaySpeed = if (isKmh) (speedMph * 1.60934f).toInt() else speedMph.toInt()
                        Text(
                            text = "$displaySpeed",
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF00E5FF)
                        )
                        Text(
                            text = if (isKmh) "KM/H - MARCHA $manualGear" else "MPH - MARCHA $manualGear",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (manualGear == "N") Color(0xFF94A3B8) else Color(0xFF38BDF8)
                        )
                    }
                }
                1 -> {
                    // MODO 1: Diagnostico de Motor
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        val temp = if (obdData.isConnected && obdData.coolantTempC > 0) obdData.coolantTempC else 90
                        Text(
                            text = "REFRIGERANTE: $temp C",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                        Text(
                            text = "BATERIA: " + String.format(java.util.Locale.US, "%.1f", obdData.batteryVoltage) + "V",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFBBF24)
                        )
                        Text(
                            text = "CARGA: ${obdData.engineLoadPercent}% | ACELERADOR: ${obdData.throttlePercent}%",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF94A3B8)
                        )
                        val mapStr = if (obdData.manifoldPressureKpa > 0) "${obdData.manifoldPressureKpa} kPa" else "101 kPa"
                        val iatStr = if (obdData.intakeTempC != 0) "${obdData.intakeTempC} C" else "24 C"
                        Text(
                            text = "ADMISION (MAP): $mapStr | AIRE: $iatStr",
                            fontSize = 9.5.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }
                2 -> {
                    // MODO 2: Combustible & Autonomia
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val fuel = if (obdData.fuelLevelPercent > 0) obdData.fuelLevelPercent else 75
                        Text(
                            text = "⛽ NIVEL DE TANQUE: $fuel%",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (fuel < 20) Color(0xFFEF4444) else Color(0xFF34D399)
                        )
                        val estimatedMiles = (fuel * 4.8f).toInt()
                        Text(
                            text = "AUTONOMIA ESTIMADA: ~$estimatedMiles mi",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF94A3B8)
                        )
                        Text(
                            text = "CONSUMO PROMEDIO: 32.4 MPG",
                            fontSize = 11.sp,
                            color = Color(0xFF38BDF8)
                        )
                    }
                }
                else -> {
                    // MODO 3: Diagnostico & Codigos de Falla DTC
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (obdData.dtcCount > 0 || obdData.milOn) {
                            Text(
                                text = "⚠️ FALLAS MOTOR: ${obdData.dtcCount} DTC",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFFEF4444)
                            )
                            val codesStr = if (obdData.dtcCodes.isNotEmpty()) obdData.dtcCodes.joinToString(", ") else "P0100 (Revisar PCM)"
                            Text(
                                text = "CODIGOS: $codesStr",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFBBF24)
                            )
                        } else {
                            Text(
                                text = "CHECK ENGINE: OK",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF10B981)
                            )
                            Text(
                                text = if (obdData.isVlinkerFd) "vLinker FD: HS-CAN + MS-CAN OK" else "SIN CODIGOS DE FALLA ACTIVOS",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Text(
                            text = if (obdData.isConnected) "ENLACE: ${obdData.deviceName}" else "ODOMETRO: 84,215 mi",
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }
            }

            // Indicadores de Barra Widescreen
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Barra de Temperatura
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = "C", fontSize = 9.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier
                            .width(50.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF1E293B))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.5f)
                                .background(Color(0xFF00E5FF))
                        )
                    }
                    Text(text = "H", fontSize = 9.sp, color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }

                Text(
                    text = "FORD FIESTA MID",
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E3A8A)
                )

                // Barra de Combustible
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = "E", fontSize = 9.sp, color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier
                            .width(50.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF1E293B))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.75f)
                                .background(Color(0xFF10B981))
                        )
                    }
                    Text(text = "F", fontSize = 9.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
