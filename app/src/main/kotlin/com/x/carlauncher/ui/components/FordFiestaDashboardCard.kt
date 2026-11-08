package com.x.carlauncher.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x.carlauncher.canbus.DoorState
import com.x.carlauncher.illumination.VehicleIlluminationManager
import com.x.carlauncher.obd.ObdData
import com.x.carlauncher.system.AutomotiveIntentResolver
import com.x.carlauncher.ui.theme.XTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cuadro de Instrumentos / Dashboard Oficial de Ford Fiesta 2016 Sedan (Transmision Estandar / Manual 5-Velocidades).
 *
 * Emula con precision el cluster original de fabrica del Ford Fiesta 2016 Estandar:
 * 1. Biseles cromados dobles binnacle (Tacometro a la izquierda, Velocimetro a la derecha).
 * 2. Agujas retroiluminadas en Ford Ice Blue (#00E5FF) con respuesta a 60 fps.
 * 3. Pantalla central multifuncion LCD (MID) con selector manual [N, 1, 2, 3, 4, 5, R]
 *    y el indicador oficial Ford Shift Indicator (flecha verde ▲ para cambio de marcha optimo).
 * 4. Indicador de combustible Ford con icono (⛽▶), temperatura de motor y voltimetro.
 * 5. Integracion directa con telemetria en tiempo real (OBD-II CAN bus y GPS).
 */
@Composable
fun FordFiestaDashboardCard(
    speedMph: Float,
    obdData: ObdData = ObdData(),
    doorState: DoorState? = null,
    isLightsOn: Boolean = false,
    onCardClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isKmh by remember { mutableStateOf(false) }
    var tripScreenMode by remember { mutableStateOf(0) } // 0: Marcha Manual, 1: Motor, 2: Gasolina

    // Calculo de marcha manual (caja IB5 de 5 velocidades de Ford Fiesta)
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

    // Indicador oficial Ford Gear Shift Indicator (GSI): flecha ▲ recomendando subir de marcha
    val shouldShiftUp = remember(manualGear, speedMph, liveRpm) {
        manualGear in listOf("1", "2", "3", "4") && (liveRpm >= 2100f || (manualGear == "1" && speedMph > 13f) || (manualGear == "2" && speedMph > 24f) || (manualGear == "3" && speedMph > 38f) || (manualGear == "4" && speedMph > 52f))
    }

    val currentSpeed = if (isKmh) speedMph * 1.60934f else speedMph
    val maxSpeed = if (isKmh) 220f else 140f
    val speedUnitLabel = if (isKmh) "km/h" else "mph"

    // Animaciones fluidas para las agujas
    val animatedRpm by animateFloatAsState(
        targetValue = liveRpm.coerceIn(0f, 8000f),
        animationSpec = tween(durationMillis = 250),
        label = "fiestaRpmAnim"
    )

    val animatedSpeed by animateFloatAsState(
        targetValue = currentSpeed.coerceIn(0f, maxSpeed),
        animationSpec = tween(durationMillis = 250),
        label = "fiestaSpeedAnim"
    )

    // Gradiente del fondo del tablero (textura mate oscura con destellos de luz)
    val clusterCardBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF0F172A),
                Color(0xFF090D16),
                Color(0xFF020617)
            )
        )
    }

    Card(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable {
                tripScreenMode = (tripScreenMode + 1) % 3
                onCardClick?.invoke()
            },
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, if (obdData.isConnected) Color(0xFF00E5FF).copy(alpha = 0.6f) else Color(0xFF334155)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .background(clusterCardBrush)
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // ── 1. Barra Superior del Cluster: Emblema Ford Fiesta + Transmision Estandar ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF1E3A8A).copy(alpha = 0.4f))
                                .border(0.8.dp, Color(0xFF38BDF8).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "FORD FIESTA",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF00E5FF),
                                letterSpacing = 1.sp
                            )
                        }

                        Text(
                            text = "2016 - Estandar 5-Vel",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    // Boton selector de unidad km/h o mph + Conexion OBD
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF1E293B))
                                .clickable { isKmh = !isKmh }
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = speedUnitLabel.uppercase(),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8)
                            )
                        }

                        // Indicador de enlace OBD
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (obdData.isConnected) Color(0xFF10B981) else Color(0xFF64748B))
                        )
                    }
                }

                // ── 2. Cluster Central: Tacometro (Izq) + Pantalla LCD Estandar (Centro) + Velocimetro (Der) ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Reloj Izquierdo: Tacometro RPM (0-8k)
                    Box(
                        modifier = Modifier
                            .weight(1.1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        FiestaGaugeCanvas(
                            value = animatedRpm,
                            maxValue = 8000f,
                            majorTicks = 8,
                            title = "RPM",
                            isRedline = true,
                            needleColor = Color(0xFF00E5FF),
                            formatValue = { (it / 1000f).let { v -> String.format("%.1f", v) } }
                        )
                    }

                    // Pantalla Central LCD del Ford Fiesta Estandar (MID)
                    Box(
                        modifier = Modifier
                            .weight(1.35f)
                            .fillMaxHeight()
                            .padding(horizontal = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        FiestaCenterLcdDisplay(
                            speedMph = speedMph,
                            manualGear = manualGear,
                            shouldShiftUp = shouldShiftUp,
                            obdData = obdData,
                            tripMode = tripScreenMode,
                            isKmh = isKmh
                        )
                    }

                    // Reloj Derecho: Velocimetro (0-140 mph o 0-220 km/h)
                    Box(
                        modifier = Modifier
                            .weight(1.1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        FiestaGaugeCanvas(
                            value = animatedSpeed,
                            maxValue = maxSpeed,
                            majorTicks = 7,
                            title = speedUnitLabel.uppercase(),
                            isRedline = false,
                            needleColor = Color(0xFF00E5FF),
                            formatValue = { it.toInt().toString() }
                        )
                    }
                }

                // ── 3. Barra Inferior: Testigos Luminosos Oficiales Ford ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Testigo de Luces / Faros (Toca para alternar Modo Auto / Manual)
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable {
                                VehicleIlluminationManager.toggleManual(context)
                            }
                            .padding(2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LightMode,
                            contentDescription = "Faros",
                            tint = if (isLightsOn) Color(0xFF10B981) else Color(0xFF334155),
                            modifier = Modifier.size(13.dp)
                        )
                    }

                    // Testigo Puertas Abiertas
                    val anyDoorOpen = doorState?.let {
                        it.frontLeft || it.frontRight || it.rearLeft || it.rearRight || it.trunk || it.hood
                    } == true
                    Icon(
                        imageVector = Icons.Default.MeetingRoom,
                        contentDescription = "Puertas",
                        tint = if (anyDoorOpen) Color(0xFFEF4444) else Color(0xFF334155),
                        modifier = Modifier.size(13.dp)
                    )

                    // Testigo Bateria
                    val batteryOk = obdData.isConnected && obdData.batteryVoltage >= 12.2f
                    Icon(
                        imageVector = Icons.Default.BatteryChargingFull,
                        contentDescription = "Bateria",
                        tint = if (batteryOk) Color(0xFF34D399).copy(alpha = 0.7f) else Color(0xFFF59E0B),
                        modifier = Modifier.size(13.dp)
                    )

                    // Testigo Motor Check Engine
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Motor",
                        tint = if (obdData.isConnected) Color(0xFF334155) else Color(0xFFF59E0B).copy(alpha = 0.6f),
                        modifier = Modifier.size(13.dp)
                    )

                    // Selector de modo tactil
                    Text(
                        text = "MODO ${tripModeLabel(tripScreenMode)}",
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64748B)
                    )
                }
            }
        }
    }
}

private fun tripModeLabel(mode: Int): String {
    return when (mode) {
        0 -> "CAMBIOS 5-VEL"
        1 -> "MOTOR"
        2 -> "AUTONOMIA"
        else -> "TRIP"
    }
}

/**
 * Lienzo Canvas de alta definicion para los relojes binnacle del Ford Fiesta 2016.
 * Incluye biseles metalicos cromados 3D, escala angular de 270 grados y aguja iluminada Ice Blue.
 */
@Composable
private fun FiestaGaugeCanvas(
    value: Float,
    maxValue: Float,
    majorTicks: Int,
    title: String,
    isRedline: Boolean,
    needleColor: Color,
    formatValue: (Float) -> String
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val diameter = minOf(size.width, size.height) * 0.94f
        val radius = diameter / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        // 1. Bisel Cromado Exterior 3D (Ford Chrome Ring)
        val chromeBrush = Brush.linearGradient(
            colors = listOf(
                Color(0xFFE2E8F0),
                Color(0xFF64748B),
                Color(0xFF1E293B),
                Color(0xFF94A3B8),
                Color(0xFFE2E8F0)
            ),
            start = Offset(center.x - radius, center.y - radius),
            end = Offset(center.x + radius, center.y + radius)
        )

        drawCircle(
            brush = chromeBrush,
            radius = radius,
            center = center,
            style = Stroke(width = 2.5f.dp.toPx())
        )

        // 2. Fondo interior del reloj (gradiente oscuro profundo)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF111827), Color(0xFF030712)),
                center = center,
                radius = radius * 0.95f
            ),
            radius = radius * 0.94f,
            center = center
        )

        // 3. Arco de fondo de la escala (270 de 135 a 405)
        val startAngle = 135f
        val sweepAngle = 270f
        val arcRadius = radius * 0.76f
        val arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)
        val arcSize = Size(arcRadius * 2, arcRadius * 2)

        // Arco base tenue
        drawArc(
            color = Color(0xFF1E293B),
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )

        // Zona roja del Tacometro Ford Fiesta (de 6,500 a 8,000 RPM)
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
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // 4. Marcas de la escala (Ticks)
        for (i in 0..majorTicks) {
            val ratio = i.toFloat() / majorTicks
            val angleDeg = startAngle + sweepAngle * ratio
            val angleRad = Math.toRadians(angleDeg.toDouble())

            val tickColor = if (isRedline && ratio >= (6.5f / 8.0f)) Color(0xFFEF4444) else Color(0xFFCBD5E1)
            val outerX = center.x + (radius * 0.85f) * cos(angleRad).toFloat()
            val outerY = center.y + (radius * 0.85f) * sin(angleRad).toFloat()
            val innerX = center.x + (radius * 0.72f) * cos(angleRad).toFloat()
            val innerY = center.y + (radius * 0.72f) * sin(angleRad).toFloat()

            drawLine(
                color = tickColor,
                start = Offset(innerX, innerY),
                end = Offset(outerX, outerY),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // 5. Aguja Ford Ice Blue
        val currentRatio = (value / maxValue).coerceIn(0f, 1f)
        val needleAngleDeg = startAngle + sweepAngle * currentRatio
        val needleAngleRad = Math.toRadians(needleAngleDeg.toDouble())

        val needleLength = radius * 0.78f
        val tipX = center.x + needleLength * cos(needleAngleRad).toFloat()
        val tipY = center.y + needleLength * sin(needleAngleRad).toFloat()

        // Sombra de la aguja
        drawLine(
            color = Color.Black.copy(alpha = 0.5f),
            start = center + Offset(1.5f, 1.5f),
            end = Offset(tipX + 1.5f, tipY + 1.5f),
            strokeWidth = 2.5.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Cuerpo luminoso de la aguja (Cyan Ice Blue)
        drawLine(
            color = needleColor,
            start = center,
            end = Offset(tipX, tipY),
            strokeWidth = 2.2.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Centro brillante blanco en la aguja
        val coreLength = needleLength * 0.85f
        val coreTipX = center.x + coreLength * cos(needleAngleRad).toFloat()
        val coreTipY = center.y + coreLength * sin(needleAngleRad).toFloat()
        drawLine(
            color = Color.White.copy(alpha = 0.8f),
            start = center,
            end = Offset(coreTipX, coreTipY),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Tapa central / Eje pivot metalico cromado
        drawCircle(
            color = Color(0xFF1E293B),
            radius = radius * 0.18f,
            center = center
        )
        drawCircle(
            color = Color(0xFF64748B),
            radius = radius * 0.18f,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )
        drawCircle(
            color = Color(0xFF00E5FF),
            radius = radius * 0.06f,
            center = center
        )

        // 6. Texto numerico digital impreso en el dial
        val paint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // Valor numerico
        paint.color = Color.White.toArgb()
        paint.textSize = 10.sp.toPx()
        drawContext.canvas.nativeCanvas.drawText(
            formatValue(value),
            center.x,
            center.y + radius * 0.52f,
            paint
        )

        // Titulo / Unidad
        paint.color = Color(0xFF00E5FF).toArgb()
        paint.textSize = 7.5.sp.toPx()
        drawContext.canvas.nativeCanvas.drawText(
            title,
            center.x,
            center.y - radius * 0.38f,
            paint
        )
    }
}

/**
 * Pantalla LCD Multifuncion Central original del Ford Fiesta Estandar (Multi-Information Display).
 * Muestra el selector manual [N, 1, 2, 3, 4, 5, R] y el Ford Shift Indicator (flecha ▲).
 */
@Composable
private fun FiestaCenterLcdDisplay(
    speedMph: Float,
    manualGear: String,
    shouldShiftUp: Boolean,
    obdData: ObdData,
    tripMode: Int,
    isKmh: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF081226))
            .border(1.dp, Color(0xFF1E3A8A).copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 5.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Fila de Marchas Manuales 5-Velocidades + Reversa [N, 1, 2, 3, 4, 5, R] + Indicador de Cambio ▲
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Selector de Marchas Estandar
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("N", "1", "2", "3", "4", "5", "R").forEach { g ->
                        val isCurrent = (g == manualGear)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (isCurrent) Color(0xFF00E5FF) else Color.Transparent)
                                .padding(horizontal = 2.5.dp, vertical = 0.8.dp)
                        ) {
                            Text(
                                text = g,
                                fontSize = 8.sp,
                                fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Medium,
                                color = if (isCurrent) Color(0xFF020617) else Color(0xFF64748B)
                            )
                        }
                    }
                }

                // Ford Shift Indicator (GSI): Flecha ▲ de cambio de marcha recomendada
                if (shouldShiftUp) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Text(
                            text = "▲",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF10B981)
                        )
                        val nextGear = (manualGear.toIntOrNull() ?: 1) + 1
                        Text(
                            text = "$nextGear",
                            fontSize = 7.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981)
                        )
                    }
                }
            }

            // Cuerpo Central: Dependiente del modo de visualizacion del Ford Fiesta Estandar
            when (tripMode) {
                0 -> {
                    // MODO 0: Velocidad Digital y Marcha Manual Actual
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        val displaySpeed = if (isKmh) (speedMph * 1.60934f).toInt() else speedMph.toInt()
                        Text(
                            text = "$displaySpeed",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF00E5FF)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = if (isKmh) "KM/H" else "MPH",
                                fontSize = 7.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF94A3B8)
                            )
                            Text(
                                text = "- MARCHA $manualGear",
                                fontSize = 7.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (manualGear == "N") Color(0xFF94A3B8) else Color(0xFF38BDF8)
                            )
                        }
                    }
                }
                1 -> {
                    // MODO 1: Diagnostico de Motor (Temp Refrigerante + Voltaje)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        val tempStr = if (obdData.isConnected && obdData.coolantTempC > 0) "${obdData.coolantTempC} C" else "-- C"
                        Text(
                            text = "TEMP $tempStr",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                        val battStr = if (obdData.isConnected && obdData.batteryVoltage > 0f) String.format(java.util.Locale.US, "%.1fV", obdData.batteryVoltage) else "-- V"
                        Text(
                            text = "BATT $battStr",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFFBBF24)
                        )
                    }
                }
                else -> {
                    // MODO 2: Combustible & Autonomia
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        val hasFuel = obdData.isConnected && obdData.fuelLevelPercent > 0
                        val fuelStr = if (hasFuel) "${obdData.fuelLevelPercent}%" else "--%"
                        Text(
                            text = "GAS $fuelStr",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (hasFuel && obdData.fuelLevelPercent < 20) Color(0xFFEF4444) else Color(0xFF34D399)
                        )
                        val rngStr = if (hasFuel) "${(obdData.fuelLevelPercent * 4.8f).toInt()} mi" else "-- mi"
                        Text(
                            text = "RNG $rngStr",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }

            // Barras Verticales de Temperatura y Combustible Ford Fiesta
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Indicador Temperatura (C ... H)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(text = "C", fontSize = 7.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF1E293B))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(if (obdData.isConnected && obdData.coolantTempC > 0) ((obdData.coolantTempC - 40f) / 80f).coerceIn(0f, 1f) else 0f)
                                .background(Color(0xFF00E5FF))
                        )
                    }
                    Text(text = "H", fontSize = 7.sp, color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }

                // Indicador Combustible Ford Fiesta (E ... F) con flecha a la derecha ⛽▶
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(text = "E", fontSize = 7.sp, color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF1E293B))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(if (obdData.isConnected && obdData.fuelLevelPercent > 0) (obdData.fuelLevelPercent / 100f).coerceIn(0f, 1f) else 0f)
                                .background(Color(0xFF10B981))
                        )
                    }
                    Text(text = "F", fontSize = 7.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
