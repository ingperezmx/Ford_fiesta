package com.x.carlauncher.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import kotlin.math.sin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import com.x.carlauncher.illumination.VehicleIlluminationManager
import com.x.carlauncher.location.VehicleLocationManager
import com.x.carlauncher.navigation.CarRouteManager
import com.x.carlauncher.navigation.TurnManeuver
import com.x.carlauncher.obd.ObdManager
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x.carlauncher.R
import com.x.carlauncher.canbus.DoorState
import com.x.carlauncher.carassets.CarAssetManager
import com.x.carlauncher.carassets.CarSlot
import com.x.carlauncher.carassets.FiestaColorManager
import com.x.carlauncher.carassets.FiestaColor
import kotlinx.coroutines.delay
import com.x.carlauncher.ui.theme.ColorAccentGreen
import com.x.carlauncher.ui.theme.ColorBorder
import com.x.carlauncher.ui.theme.ColorGrayMid
import com.x.carlauncher.ui.theme.ColorPrimaryVariant
import com.x.carlauncher.ui.theme.ColorSurface
import com.x.carlauncher.ui.theme.ColorSurfaceDeep
import com.x.carlauncher.ui.theme.XTheme
import com.x.carlauncher.weather.WeatherData

// ── VehicleCard ───────────────────────────────────────────────────────────────

/**
 * Full vehicle overview card: greeting, door‑state overlay, weather widget.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VehicleCard(
    doorState:   DoorState,
    weatherData: WeatherData?,
    modifier:    Modifier = Modifier,
    onCarDetailsClick: () -> Unit = {}
) {
    val colors = XTheme.colors
    val cardBrush = remember(colors) {
        Brush.verticalGradient(listOf(colors.surface, colors.surfaceDeep))
    }
    var showAssetModal by remember { mutableStateOf(false) }
    var showThemeModal by remember { mutableStateOf(false) }
    var showDoorModal by remember { mutableStateOf(false) }
    var assetRefreshKey by remember { mutableStateOf(0) }
    val isLightsOn by VehicleIlluminationManager.isLightsOn.collectAsState()

    val context = LocalContext.current
    val activeFiestaColor by FiestaColorManager.currentColor.collectAsState()
    val colorBanner by FiestaColorManager.bannerNotification.collectAsState()

    LaunchedEffect(Unit) {
        FiestaColorManager.initialize(context)
    }

    LaunchedEffect(colorBanner) {
        if (colorBanner != null) {
            delay(2500)
            FiestaColorManager.clearBanner()
        }
    }

    val userPrefs = remember(context) { context.getSharedPreferences("x_user_prefs", android.content.Context.MODE_PRIVATE) }
    var currentUserName by remember { mutableStateOf(userPrefs.getString("user_custom_name", "Pablo") ?: "Pablo") }
    var showNameDialog by remember { mutableStateOf(false) }
    var editedNameText by remember { mutableStateOf("") }

    // Live Vehicle Telemetry for dynamic movement while driving (> 10 km/h)
    val vehicleLoc by VehicleLocationManager.locationState.collectAsState()
    val obdData by ObdManager.obdData.collectAsState()
    val liveSpeedKmh = remember(vehicleLoc.speedMph, obdData.speedMph, obdData.isConnected) {
        if (obdData.isConnected && obdData.speedMph > 0f) {
            obdData.speedMph * 1.60934f
        } else {
            vehicleLoc.speedMph * 1.60934f
        }
    }
    // Dynamic movement trigger requested by Pablo: moving > 10 km/h
    val isMovingOver10 = liveSpeedKmh > 10.0f

    // Unified Navigation & Highway Driving States
    val activeRoute by CarRouteManager.activeRoute.collectAsState()
    val isNavigatingRoute by CarRouteManager.isNavigating.collectAsState()
    val currentInstruction by CarRouteManager.currentInstruction.collectAsState()
    val currentManeuver by CarRouteManager.currentManeuver.collectAsState()
    val targetTurnAngle by CarRouteManager.targetTurnAngle.collectAsState()
    val distanceToNextManeuver by CarRouteManager.distanceToNextManeuver.collectAsState()
    var isPreviewHighwayMode by remember { mutableStateOf(false) }
    val isNavigatingOrRoute = isNavigatingRoute || activeRoute != null
    val isDrivingHighway = isMovingOver10 || isPreviewHighwayMode || isNavigatingOrRoute

    // Smooth dynamic highway sway and suspension motion
    val infiniteTransition = rememberInfiniteTransition(label = "carMovementAnim")
    val rawTurnSway by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "turnSway"
    )
    val rawSuspension by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "suspensionJitter"
    )

    // Animated rotation angles: turns/sways smoothly when moving > 10 km/h, perfectly flat and stationary when stopped
    val carRotationZ by animateFloatAsState(
        targetValue = if (isMovingOver10) rawTurnSway * 5.0f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "carRotationZ"
    )
    val carRotationY by animateFloatAsState(
        targetValue = if (isMovingOver10) rawTurnSway * 4.0f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "carRotationY"
    )
    val carTranslationY by animateFloatAsState(
        targetValue = if (isMovingOver10) rawSuspension * 1.8f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "carTransY"
    )

    // Animaciones orgánicas de avance en carretera para la vista trasera del Fiesta
    val cruiseBounce by infiniteTransition.animateFloat(
        initialValue = -1.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 380, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cruiseBounce"
    )
    val roadJitter by infiniteTransition.animateFloat(
        initialValue = -0.6f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 160, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "roadJitter"
    )
    val cruiseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.022f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cruiseScale"
    )
    val laneSway by infiniteTransition.animateFloat(
        initialValue = -1.8f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laneSway"
    )
    val roadProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "roadProgress"
    )
    val tailGlowPulse by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tailGlowPulse"
    )

    Card(
        modifier = modifier,
        shape    = RoundedCornerShape(24.dp),
        border   = BorderStroke(1.dp, colors.border),
        colors   = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .background(cardBrush)
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Header row ────────────────────────────────────────────────
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val timeGreeting = when (hour) {
                    in 5..11 -> "Buenos dias"
                    in 12..18 -> "Buenas tardes"
                    else -> "Buenas noches"
                }

                Row(
                    modifier            = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment   = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = {
                                    editedNameText = currentUserName
                                    showNameDialog = true
                                }
                            )
                        }
                    ) {
                        Text(
                            text       = "Hola, $currentUserName",
                            fontSize   = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color      = colors.textPrimary
                        )
                        Text(
                            text     = "$timeGreeting - Bienvenido",
                            fontSize = 12.sp,
                            color    = colors.textSecondary
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Quick Headlights / Night Mode Toggle Button
                        IconButton(
                            onClick = {
                                VehicleIlluminationManager.toggleManual(context)
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    color = if (isLightsOn) colors.secondary.copy(alpha = 0.35f) else Color(0xFF1E293B).copy(alpha = 0.6f),
                                    shape = CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Highlight,
                                contentDescription = if (isLightsOn) "Faros Encendidos" else "Faros Apagados",
                                tint = if (isLightsOn) colors.secondary else colors.textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Circular car button - shows active color swatch & cycles official Ford Fiesta colors
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(activeFiestaColor.swatchColor.copy(alpha = 0.9f))
                                .border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                                .combinedClickable(
                                    onClick = {
                                        FiestaColorManager.cycleNextColor(context)
                                    },
                                    onLongClick = {
                                        showThemeModal = true
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector        = Icons.Default.DirectionsCar,
                                contentDescription = "Cambiar color oficial Ford Fiesta",
                                tint               = Color.White,
                                modifier           = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // ── Indicador rapido de estado de puertas ─────────────────────
                val anyDoorOpen = doorState.frontLeft || doorState.frontRight || doorState.rearLeft || doorState.rearRight || doorState.trunk || doorState.hood
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (anyDoorOpen) Color(0x33EF4444) else Color(0x1A10B981))
                        .clickable { showDoorModal = true }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(if (anyDoorOpen) Color(0xFFEF4444) else Color(0xFF10B981), CircleShape)
                    )
                    Text(
                        text = if (anyDoorOpen) "PUERTA ABIERTA (Toca para ver)" else "Puertas cerradas",
                        fontSize = 10.sp,
                        fontWeight = if (anyDoorOpen) FontWeight.Bold else FontWeight.Normal,
                        color = if (anyDoorOpen) Color(0xFFEF4444) else colors.textSecondary
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                // ── Car container: Smooth transition between Parked Fiesta and Highway Driving Fiesta ────
                val context = LocalContext.current
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(124.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = isDrivingHighway,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(500)) togetherWith fadeOut(animationSpec = tween(500))
                        },
                        label = "highwayCarMode"
                    ) { driving ->
                        if (driving) {
                            // ── 1. Dynamic 3D Highway Driving View (Ford Fiesta cruise + 3D turn arrow) ──
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onTap = {
                                                FiestaColorManager.cycleNextColor(context)
                                            },
                                            onDoubleTap = {
                                                if (isPreviewHighwayMode) {
                                                    isPreviewHighwayMode = false
                                                }
                                            }
                                        )
                                    }
                            ) {
                                // Contenedor animado del Ford Fiesta avanzando en carretera con suspensión y micro-vibración
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            scaleX = cruiseScale
                                            scaleY = cruiseScale
                                            translationY = cruiseBounce + roadJitter
                                            translationX = laneSway
                                            rotationZ = (targetTurnAngle / 68f * 1.8f) + (laneSway * 0.12f)
                                        }
                                ) {
                                    // Foto en alta fidelidad del Ford Fiesta avanzando
                                    Image(
                                        painter = painterResource(R.drawable.fiesta_highway_driving),
                                        contentDescription = "Ford Fiesta en carretera",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                        colorFilter = activeFiestaColor.highwayColorFilter
                                    )

                                    // Rayas viales y destellos de avance continuo en perspectiva
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val w = size.width
                                        val h = size.height
                                        val vpX = w * 0.50f
                                        val vpY = h * 0.35f

                                        // 1. Rayas divisorias centrales avanzando desde el auto hacia el frente
                                        for (i in 0 until 5) {
                                            val p = (roadProgress + i * 0.20f) % 1.0f
                                            val py = 0.44f + 0.56f * (p * p)
                                            val y = h * py
                                            val x = vpX + (targetTurnAngle / 68f * 12.dp.toPx()) * (p * p)
                                            val dashW = (3.5.dp.toPx() * p).coerceAtLeast(1.2.dp.toPx())
                                            val dashH = (14.dp.toPx() * p).coerceAtLeast(3.dp.toPx())
                                            val alpha = (sin(p * Math.PI.toFloat()) * 0.75f).coerceIn(0f, 0.75f)

                                            drawRoundRect(
                                                color = Color.White.copy(alpha = alpha),
                                                topLeft = Offset(x - dashW / 2, y),
                                                size = Size(dashW, dashH),
                                                cornerRadius = CornerRadius(2.dp.toPx())
                                            )
                                        }

                                        // 2. Destellos cian de velocidad en los guardarraíles laterales
                                        for (i in 0 until 4) {
                                            val p = (roadProgress + i * 0.25f) % 1.0f
                                            val railY = vpY + (h * 0.52f) * (p * p)
                                            val railLeftX = vpX - (w * 0.52f) * (p * p)
                                            val railRightX = vpX + (w * 0.52f) * (p * p)
                                            val glowRadius = 14.dp.toPx() * p
                                            val railAlpha = (sin(p * Math.PI.toFloat()) * 0.85f).coerceIn(0f, 0.85f)

                                            if (glowRadius > 1.dp.toPx()) {
                                                drawCircle(
                                                    brush = Brush.radialGradient(
                                                        colors = listOf(Color(0xFF00E5FF).copy(alpha = railAlpha), Color.Transparent),
                                                        center = Offset(railLeftX, railY),
                                                        radius = glowRadius
                                                    ),
                                                    radius = glowRadius,
                                                    center = Offset(railLeftX, railY)
                                                )
                                                drawCircle(
                                                    brush = Brush.radialGradient(
                                                        colors = listOf(Color(0xFF00E5FF).copy(alpha = railAlpha), Color.Transparent),
                                                        center = Offset(railRightX, railY),
                                                        radius = glowRadius
                                                    ),
                                                    radius = glowRadius,
                                                    center = Offset(railRightX, railY)
                                                )
                                            }
                                        }

                                        // 3. Resplandor pulsante en el asfalto (luces traseras rojas y neón cian bajo el auto)
                                        val carCenterY = h * 0.74f
                                        val carCenterX = vpX + laneSway
                                        drawOval(
                                            brush = Brush.radialGradient(
                                                colors = listOf(
                                                    Color(0xFFEF4444).copy(alpha = tailGlowPulse * 0.35f),
                                                    Color(0xFF00E5FF).copy(alpha = tailGlowPulse * 0.20f),
                                                    Color.Transparent
                                                ),
                                                center = Offset(carCenterX, carCenterY),
                                                radius = 42.dp.toPx()
                                            ),
                                            topLeft = Offset(carCenterX - 48.dp.toPx(), carCenterY - 14.dp.toPx()),
                                            size = Size(96.dp.toPx(), 28.dp.toPx())
                                        )
                                    }
                                }

                                // Dynamic 3D Glowing Navigation Arrow pointing along route
                                val animatedTurnRotation by animateFloatAsState(
                                    targetValue = targetTurnAngle,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessLow
                                    ),
                                    label = "arrowTurnRotation"
                                )

                                val arrowLateralOffset by animateDpAsState(
                                    targetValue = (targetTurnAngle / 68f * 16f).dp,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessLow
                                    ),
                                    label = "arrowLateralOffset"
                                )

                                val arrowPulseScale by infiniteTransition.animateFloat(
                                    initialValue = if (currentManeuver.isTurn) 0.94f else 0.98f,
                                    targetValue = if (currentManeuver.isTurn) 1.14f else 1.05f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(if (currentManeuver.isTurn) 750 else 1400, easing = FastOutSlowInEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "arrowPulseScale"
                                )

                                // 3D Neon Cyan Navigation Arrow (Aligned directly above vehicle roof)
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 10.dp)
                                        .offset(x = arrowLateralOffset)
                                        .graphicsLayer {
                                            rotationZ = animatedTurnRotation
                                            scaleX = arrowPulseScale
                                            scaleY = arrowPulseScale
                                        }
                                        .clickable {
                                            com.x.carlauncher.voice.CarVoiceAssistant.getInstance(context)
                                                .speak(CarRouteManager.currentInstruction.value)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Outer cyan glow aura
                                    Box(
                                        modifier = Modifier
                                            .size(if (currentManeuver.isTurn) 48.dp else 40.dp)
                                            .background(
                                                Brush.radialGradient(
                                                    listOf(
                                                        Color(0xFF00E5FF).copy(alpha = if (currentManeuver.isTurn) 0.95f else 0.75f),
                                                        Color(0xFF00E5FF).copy(alpha = 0.35f),
                                                        Color.Transparent
                                                    )
                                                ),
                                                CircleShape
                                            )
                                    )

                                    // 3D Neon Arrow Stroke
                                    Icon(
                                        imageVector = Icons.Default.Navigation,
                                        contentDescription = "Ruta",
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier.size(34.dp)
                                    )

                                    // Inner crisp core
                                    Icon(
                                        imageVector = Icons.Default.Navigation,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                // Top-Left: Live Speed Pill
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF0F172A).copy(alpha = 0.88f),
                                    border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .background(Color(0xFF10B981), CircleShape)
                                        )
                                        Text(
                                            text = "${liveSpeedKmh.toInt()} km/h",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }

                                // Bottom: Turn maneuver or active route pill
                                val isTurning = currentManeuver.isTurn
                                val routeLabel = when {
                                    isTurning && distanceToNextManeuver > 0 -> "En ${distanceToNextManeuver.toInt()}m: ${currentManeuver.displayName}"
                                    isTurning -> currentManeuver.displayName
                                    isNavigatingRoute && currentInstruction.isNotBlank() -> currentInstruction
                                    activeRoute != null -> "${activeRoute?.destinationName} (${activeRoute?.etaMinutesFormatted})"
                                    else -> "Autopista Activa (Toca para probar giro)"
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF0F172A).copy(alpha = 0.90f),
                                    border = BorderStroke(1.dp, if (isTurning) Color(0xFF00E5FF) else Color(0xFF00E5FF).copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 5.dp)
                                        .clickable {
                                            com.x.carlauncher.voice.CarVoiceAssistant.getInstance(context)
                                                .speak(CarRouteManager.currentInstruction.value)
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = when (currentManeuver) {
                                                TurnManeuver.RIGHT, TurnManeuver.SHARP_RIGHT, TurnManeuver.SLIGHT_RIGHT -> Icons.Default.TurnRight
                                                TurnManeuver.LEFT, TurnManeuver.SHARP_LEFT, TurnManeuver.SLIGHT_LEFT, TurnManeuver.U_TURN -> Icons.Default.TurnLeft
                                                else -> Icons.Default.Straight
                                            },
                                            contentDescription = null,
                                            tint = if (isTurning) Color(0xFF00E5FF) else Color.White,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            text = routeLabel,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        } else {
                            // ── 2. Standard Parked Vehicle View (Fiesta front 3/4 with lights & door overlays) ──
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        rotationZ = carRotationZ
                                        rotationY = carRotationY
                                        translationY = carTranslationY
                                        cameraDistance = 14f * density
                                    }
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onTap = {
                                                FiestaColorManager.cycleNextColor(context)
                                            },
                                            onDoubleTap = {
                                                isPreviewHighwayMode = true
                                            },
                                            onLongPress = {
                                                showDoorModal = true
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                val carWidth = Modifier.fillMaxWidth(0.92f).height(100.dp)

                                key(assetRefreshKey, isLightsOn, doorState, activeFiestaColor) {
                                    CarSlotLayer(
                                        slot = if (isLightsOn) CarSlot.LIGHTS_ON else CarSlot.BASE,
                                        visible = true,
                                        modifier = carWidth,
                                        fallbackRes = if (isLightsOn) R.drawable.fiesta_lights_on else R.drawable.fiesta_base,
                                        colorFilter = activeFiestaColor.colorFilter
                                    )

                                    CarSlotLayer(slot = CarSlot.DOOR_FL, visible = doorState.frontLeft,  modifier = carWidth, colorFilter = activeFiestaColor.colorFilter)
                                    CarSlotLayer(slot = CarSlot.DOOR_FR, visible = doorState.frontRight, modifier = carWidth, colorFilter = activeFiestaColor.colorFilter)
                                    CarSlotLayer(slot = CarSlot.DOOR_RL, visible = doorState.rearLeft,   modifier = carWidth, colorFilter = activeFiestaColor.colorFilter)
                                    CarSlotLayer(slot = CarSlot.DOOR_RR, visible = doorState.rearRight,  modifier = carWidth, colorFilter = activeFiestaColor.colorFilter)
                                    CarSlotLayer(slot = CarSlot.TRUNK,   visible = doorState.trunk,      modifier = carWidth, colorFilter = activeFiestaColor.colorFilter)
                                    CarSlotLayer(slot = CarSlot.HOOD,    visible = doorState.hood,       modifier = carWidth, colorFilter = activeFiestaColor.colorFilter)
                                }

                                // Indicador flotante temporal con el nombre del color oficial Ford Fiesta
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = colorBanner != null,
                                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(),
                                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically(),
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 2.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color(0xFF0F172A).copy(alpha = 0.92f),
                                        border = BorderStroke(1.dp, activeFiestaColor.swatchColor.copy(alpha = 0.9f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(9.dp)
                                                    .clip(CircleShape)
                                                    .background(activeFiestaColor.swatchColor)
                                                    .border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                                            )
                                            Text(
                                                text = colorBanner ?: "",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ── Weather widget ────────────────────────────────────────────
                WeatherWidget(weatherData = weatherData)
            }
        }
    }

    // Modal to customize car assets manually
    if (showAssetModal) {
        CarAssetPickerModal(
            onDismissRequest = { showAssetModal = false },
            onAssetsUpdated = { assetRefreshKey++ }
        )
    }

    // Modal to select themes on car button long-press
    if (showThemeModal) {
        ThemePickerModal(
            onDismiss = { showThemeModal = false }
        )
    }

    // Modal to customize welcome greeting name on long-press
    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            containerColor = Color(0xFF0F172A),
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = "Personalizar Nombre",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Introduce el nombre para el saludo en pantalla y la voz del auto:",
                        color = ColorGrayMid,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = editedNameText,
                        onValueChange = { editedNameText = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF334155),
                            cursorColor = Color(0xFF38BDF8)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = editedNameText.trim()
                        val toSave = if (clean.isNotBlank()) clean else "Pablo"
                        currentUserName = toSave
                        userPrefs.edit().putString("user_custom_name", toSave).apply()
                        showNameDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Guardar", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showNameDialog = false }
                ) {
                    Text("Cancelar", color = ColorGrayMid)
                }
            }
        )
    }

    // Modal de diagnostico y prueba de sensores de puertas
    if (showDoorModal) {
        AlertDialog(
            onDismissRequest = { showDoorModal = false },
            containerColor = Color(0xFF0F172A),
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsCar,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8)
                    )
                    Text(
                        text = "Sensores de Puertas - Ford Fiesta",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Los sensores de puertas en Ford Fiesta transmiten por el modulo de carroceria BCM (red MS-CAN, pines 3 y 11). Los escaneres ELM327 estandar conectan en HS-CAN (pines 6 y 14: motor, RPM, velocidad). Aqui puedes verificar y probar cada sensor en pantalla:",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DoorTogglePill(
                            label = "Conductor (FL)",
                            isOpen = doorState.frontLeft,
                            onToggle = {
                                com.x.carlauncher.canbus.CanbusDoorReceiver.updateDoor(frontLeft = !doorState.frontLeft)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        DoorTogglePill(
                            label = "Copiloto (FR)",
                            isOpen = doorState.frontRight,
                            onToggle = {
                                com.x.carlauncher.canbus.CanbusDoorReceiver.updateDoor(frontRight = !doorState.frontRight)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DoorTogglePill(
                            label = "Trasera Izq (RL)",
                            isOpen = doorState.rearLeft,
                            onToggle = {
                                com.x.carlauncher.canbus.CanbusDoorReceiver.updateDoor(rearLeft = !doorState.rearLeft)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        DoorTogglePill(
                            label = "Trasera Der (RR)",
                            isOpen = doorState.rearRight,
                            onToggle = {
                                com.x.carlauncher.canbus.CanbusDoorReceiver.updateDoor(rearRight = !doorState.rearRight)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DoorTogglePill(
                            label = "Cajuela (Trunk)",
                            isOpen = doorState.trunk,
                            onToggle = {
                                com.x.carlauncher.canbus.CanbusDoorReceiver.updateDoor(trunk = !doorState.trunk)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        DoorTogglePill(
                            label = "Cofre (Hood)",
                            isOpen = doorState.hood,
                            onToggle = {
                                com.x.carlauncher.canbus.CanbusDoorReceiver.updateDoor(hood = !doorState.hood)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        com.x.carlauncher.canbus.CanbusDoorReceiver.updateDoorState(com.x.carlauncher.canbus.DoorState())
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Cerrar Todas", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDoorModal = false }) {
                    Text("Aceptar", color = Color(0xFF38BDF8), fontSize = 12.sp)
                }
            }
        )
    }
}

@Composable
private fun DoorTogglePill(
    label: String,
    isOpen: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onToggle,
        modifier = modifier.height(34.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (isOpen) Color(0x33EF4444) else Color(0xFF1E293B),
        border = BorderStroke(1.dp, if (isOpen) Color(0xFFEF4444) else Color(0xFF334155))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (isOpen) "ABIERTA" else "Cerrada",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = if (isOpen) Color(0xFFEF4444) else Color(0xFF10B981)
            )
        }
    }
}

// ── Car slot layer with hardware alpha animation ─────────────────────────────

@Composable
private fun CarSlotLayer(
    slot:        CarSlot,
    visible:     Boolean,
    modifier:    Modifier = Modifier,
    fallbackRes: Int = slot.defaultDrawableRes,
    colorFilter: ColorFilter? = null
) {
    val context = LocalContext.current
    val customBitmap = remember(slot) { CarAssetManager.getCustomBitmap(context, slot) }

    val alpha by animateFloatAsState(
        targetValue    = if (visible) 1f else 0f,
        animationSpec  = tween(durationMillis = 350),
        label          = "${slot.name}Alpha"
    )

    if (alpha > 0f) {
        if (customBitmap != null) {
            Image(
                bitmap             = customBitmap,
                contentDescription = slot.title,
                contentScale       = androidx.compose.ui.layout.ContentScale.Fit,
                filterQuality      = FilterQuality.High,
                modifier           = modifier.graphicsLayer { this.alpha = alpha },
                colorFilter        = colorFilter
            )
        } else {
            Image(
                painter            = painterResource(fallbackRes),
                contentDescription = slot.title,
                contentScale       = androidx.compose.ui.layout.ContentScale.Fit,
                modifier           = modifier.graphicsLayer { this.alpha = alpha },
                colorFilter        = colorFilter
            )
        }
    }
}

// ── Weather widget ────────────────────────────────────────────────────────────

private fun stripAccents(str: String): String {
    return java.text.Normalizer.normalize(str, java.text.Normalizer.Form.NFD)
        .replace("\\p{M}".toRegex(), "")
}

@Composable
private fun WeatherWidget(weatherData: WeatherData?) {
    val colors = XTheme.colors
    if (weatherData == null) {
        WeatherShimmer()
        return
    }

    val city = stripAccents(weatherData.cityName?.ifBlank { "Ubicacion actual" } ?: "Ubicacion actual")
    val department = stripAccents(weatherData.department ?: "")
    val country = weatherData.country ?: ""
    val rawDesc = weatherData.description?.ifBlank { "Despejado" } ?: "Despejado"
    val desc = stripAccents(rawDesc)
    val displayLocation = listOf(city, department, country).filter { it.isNotBlank() }.joinToString(", ")
    Column(modifier = Modifier.fillMaxWidth()) {
        // Location row: Pin + City Name + Department + Country + Update interval
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = "Ubicacion",
                tint = ColorAccentGreen,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = displayLocation,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = ColorAccentGreen,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                text = "(10 min)",
                fontSize = 8.5.sp,
                color = ColorGrayMid,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Main temp row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Highlight,
                contentDescription = null,
                tint = Color(0xFF38BDF8),
                modifier = Modifier.size(22.dp)
            )
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text       = "${weatherData.temp} C",
                        fontSize   = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color      = colors.textPrimary,
                        lineHeight = 26.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text     = "Sensacion ${weatherData.feelsLike} C",
                        fontSize = 10.5.sp,
                        color    = colors.textSecondary,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
                Text(
                    text     = desc,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    color    = Color(0xFF38BDF8),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Bottom pill row: max, min, humidity, wind
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            WeatherPill(label = "Max ${weatherData.tempMax} C")
            WeatherPill(label = "Min ${weatherData.tempMin} C")
            WeatherPill(label = "Hum ${weatherData.humidity}%")
            WeatherPill(label = "Viento " + weatherData.windSpeedMph.toInt() + " mph")
        }
    }
}

@Composable
private fun WeatherPill(label: String) {
    val colors = XTheme.colors
    Box(
        modifier = Modifier
            .background(
                color  = colors.border,
                shape  = RoundedCornerShape(50)
            )
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(text = label, fontSize = 8.5.sp, color = colors.textPrimary)
    }
}

@Composable
private fun WeatherShimmer() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF1E3A8A).copy(alpha = 0.4f),
                        Color(0xFF1E3A8A).copy(alpha = 0.2f),
                        Color(0xFF1E3A8A).copy(alpha = 0.4f)
                    )
                )
            )
    )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun mapIconCodeToEmoji(iconCode: String): String = when {
    iconCode.startsWith("01") -> "☀️"
    iconCode.startsWith("02") -> "🌤️"
    iconCode.startsWith("03") -> "☁️"
    iconCode.startsWith("04") -> "☁️"
    iconCode.startsWith("09") -> "🌧️"
    iconCode.startsWith("10") -> "🌦️"
    iconCode.startsWith("11") -> "⛈️"
    iconCode.startsWith("13") -> "❄️"
    iconCode.startsWith("50") -> "🌫️"
    else                      -> "🌡️"
}
