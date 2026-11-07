package com.x.carlauncher.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.x.carlauncher.SpeedometerViewModel
import com.x.carlauncher.bluetooth.BluetoothViewModel
import com.x.carlauncher.canbus.DoorStateViewModel
import com.x.carlauncher.media.MediaState
import com.x.carlauncher.media.PowerampMediaViewModel
import com.x.carlauncher.media.RadioManager
import com.x.carlauncher.ui.components.*
import com.x.carlauncher.system.AutomotiveIntentResolver
import com.x.carlauncher.ui.theme.DayNightManager
import com.x.carlauncher.ui.theme.XTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.x.carlauncher.voice.OfflineVoiceController
import com.x.carlauncher.voice.CarVoiceAssistant
import com.x.carlauncher.weather.WeatherViewModel
import androidx.compose.foundation.ExperimentalFoundationApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen() {
    val context = LocalContext.current

    // ─── ViewModels ───────────────────────────────────────────────────────────
    val speedometerVm: SpeedometerViewModel = viewModel()
    val mediaVm: PowerampMediaViewModel    = viewModel()
    val btVm: BluetoothViewModel           = viewModel()
    val doorVm: DoorStateViewModel         = viewModel()
    val weatherVm: WeatherViewModel        = viewModel()

    // ─── State collection ─────────────────────────────────────────────────────
    val gpsSpeedMph    by speedometerVm.speedMph.collectAsState()
    val obdData        by speedometerVm.obdData.collectAsState()
    val mediaState     by mediaVm.mediaState.collectAsState()
    val headsetState   by btVm.headsetState.collectAsState()
    val a2dpState      by btVm.a2dpState.collectAsState()
    val doorState      by doorVm.doorState.collectAsState()
    val weatherData    by weatherVm.weatherState.collectAsState()
    val isLightsOn      by com.x.carlauncher.illumination.VehicleIlluminationManager.isLightsOn.collectAsState()

    // ─── Day/Night & Dimming Manager ──────────────────────────────────────────
    val dayNightManager = remember { DayNightManager(context) }
    val dimmingAlpha by dayNightManager.dimmingAlpha.collectAsState()

    // Active speed: GPS speed
    val displaySpeedMph = gpsSpeedMph

    // ─── Live Clock & Date ────────────────────────────────────────────────────
    var currentTime by remember { mutableStateOf("12:00 PM") }
    var currentDate by remember { mutableStateOf("Hoy") }

    LaunchedEffect(Unit) {
        val timeFmt = SimpleDateFormat("h:mm a", Locale.ENGLISH)
        val dateFmt = SimpleDateFormat("EEE, d 'de' MMMM", Locale("es", "ES"))
        while (true) {
            val now = Date()
            currentTime = timeFmt.format(now).uppercase()
            val rawDate = dateFmt.format(now).replaceFirstChar { it.uppercase() }
            currentDate = rawDate
                .replace("a", "a").replace("e", "e").replace("i", "i").replace("o", "o").replace("u", "u")
                .replace("A", "A").replace("E", "E").replace("I", "I").replace("O", "O").replace("U", "U")
                .replace("n", "n").replace("N", "N")
            delay(1000L)
        }
    }

    // ─── Start background listeners ───────────────────────────────────────────
    LaunchedEffect(Unit) {
        speedometerVm.startListening()
        weatherVm.startAutoRefresh() // Uses real-time GPS coordinates (updates every 30 min)
    }

    // ─── Voice Assistant: Real-time Door Announcements & Offline Voice Control ──
    val voiceAssistant = remember { CarVoiceAssistant.getInstance(context) }
    val voiceController = remember { OfflineVoiceController.getInstance(context) }
    val isVoiceListening by voiceController.isListening.collectAsState()
    val recognizedVoiceText by voiceController.recognizedText.collectAsState()
    val assistantFeedback by voiceController.assistantFeedback.collectAsState()
    val audioRmsLevel by voiceController.audioRmsLevel.collectAsState()
    var previousDoorState by remember { mutableStateOf(doorState) }

    LaunchedEffect(doorState) {
        voiceAssistant.onDoorStateTransition(previousDoorState, doorState)
        previousDoorState = doorState
    }

    // ─── Radio Manager state synchronization ──────────────────────────────────
    val activeRadioStation by RadioManager.currentStation.collectAsState()
    val isRadioPlaying by RadioManager.isPlaying.collectAsState()

    val displayMediaState = if (isRadioPlaying && activeRadioStation != null) {
        MediaState(
            title = activeRadioStation!!.name,
            artist = activeRadioStation!!.description,
            album = activeRadioStation!!.frequency,
            isPlaying = true,
            playerName = "Radio FM"
        )
    } else {
        mediaState
    }

    // ─── Google Maps Launcher ──────────────────────────────────────────────────
    fun launchGoogleMaps() {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage("com.google.android.apps.maps")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")).apply {
                setPackage("com.google.android.apps.maps")
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (_: Exception) {}
        }
    }

    // ─── Navigation Dock selection & handling ──────────────────────────────────
    var selectedTab by remember { mutableStateOf(0) }
    var showAppDrawer by remember { mutableStateOf(false) }
    var currentPage by remember { mutableStateOf(0) } // 0 = Dashboard, 1 = Expanded Music Studio, 2 = Expanded Ford Fiesta Dashboard, 3 = Expanded Contacts Screen
    var isMapExpanded by remember { mutableStateOf(false) }
    var showClusterInCenter by remember { mutableStateOf(false) }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    BackHandler(enabled = showAppDrawer || currentPage != 0 || selectedTab != 0 || isMapExpanded) {
        if (showAppDrawer) {
            showAppDrawer = false
            selectedTab = 0
        } else if (currentPage != 0) {
            currentPage = 0
            selectedTab = 0
        } else if (isMapExpanded || selectedTab != 0) {
            isMapExpanded = false
            selectedTab = 0
        }
    }

    fun onDockItemClick(index: Int) {
        selectedTab = index
        when (index) {
            0 -> {
                // Home: modo dividido con WhatsApp
                showAppDrawer = false
                currentPage = 0
                selectedTab = 0
                isMapExpanded = false
            }
            1 -> {
                // Navegacion dentro del modulo central (sin salir del launcher)
                showAppDrawer = false
                currentPage = 0
                selectedTab = 1
                isMapExpanded = true
            }
            2 -> {
                // Modulo de Contactos integrado nativo dentro de X CarOS (no abre app externa)
                showAppDrawer = false
                currentPage = 3
                isMapExpanded = false
            }
            3 -> {
                // Expanded Music Studio Screen
                showAppDrawer = false
                currentPage = 1
                isMapExpanded = false
            }
            4 -> {
                // Navegacion Detallada (Mapa Full) - Reemplaza el cajon de apps
                showAppDrawer = false
                currentPage = 4
                isMapExpanded = false
            }
            5 -> {
                // Ir directamente a Microsoft Launcher al presionar el engrane
                showAppDrawer = false
                val pm = context.packageManager
                val msIntent = pm.getLaunchIntentForPackage("com.microsoft.launcher")
                if (msIntent != null) {
                    msIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    context.startActivity(msIntent)
                } else {
                    try {
                        val explicitIntent = Intent().apply {
                            setClassName("com.microsoft.launcher", "com.microsoft.launcher.Launcher")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(explicitIntent)
                    } catch (e: Exception) {
                        try {
                            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                setPackage("com.microsoft.launcher")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(homeIntent)
                        } catch (e2: Exception) {
                            AutomotiveIntentResolver.launchSafely(context, AutomotiveIntentResolver.getCarSettingsIntent(context))
                        }
                    }
                }
            }
        }
    }

    // ─── Root Layout with Dimming factor ──────────────────────────────────────
    // Tablero adaptable: el diseño se hace a 1024x600 dp y se escala en
    // proporción a la pantalla real, para que se vea igual (sin nada movido)
    // en pantallas grandes. En una pantalla de 1024x600 dp la escala es 1.0
    // y no cambia nada. En pantallas MAS CHICAS (teléfono) la escala nunca
    // baja de 1.0 y la caja toma el tamaño de la pantalla, así el tablero
    // llena la pantalla con su diseño flexible original en vez de achicarse.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(XTheme.colors.background)
            .alpha(dimmingAlpha)
    ) {
        val uiScale = remember(maxWidth, maxHeight) {
            minOf(maxWidth / 1024.dp, maxHeight / 600.dp).coerceIn(1.0f, 2.0f)
        }
        Box(
            modifier = Modifier
                .size(width = minOf(1024.dp, maxWidth), height = minOf(600.dp, maxHeight))
                .graphicsLayer(scaleX = uiScale, scaleY = uiScale)
                .align(Alignment.Center)
        ) {
        Row(modifier = Modifier.fillMaxSize()) {

            // ── Left Navigation Dock (56.dp) ──────────────────────────────────
            LeftNavigationDock(
                selectedTab   = selectedTab,
                onTabSelected = ::onDockItemClick,
                modifier      = Modifier
                    .width(56.dp)
                    .fillMaxHeight()
            )

            // ── Main Content Area ─────────────────────────────────────────────
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Top Status Bar (48.dp) ────────────────────────────────────
                TopStatusBar(
                    currentTime = currentTime,
                    currentDate = currentDate,
                    outsideTemp = weatherData?.temp ?: 17,
                    modifier    = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                )

                // ── Instant Screen Switcher: Page 0 = Dashboard, Page 1 = Expanded Music Studio ──
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(0.95f)
                        .then(
                            if (currentPage != 4) {
                                Modifier.pointerInput(currentPage) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { dragAccumulator = 0f },
                                        onHorizontalDrag = { _, dragAmount ->
                                            dragAccumulator += dragAmount
                                        },
                                        onDragEnd = {
                                            if (currentPage == 0 && dragAccumulator < -40f) {
                                                currentPage = 1
                                                selectedTab = 3
                                            } else if ((currentPage == 1 || currentPage == 2 || currentPage == 3) && dragAccumulator > 40f) {
                                                currentPage = 0
                                                selectedTab = 0
                                            }
                                        }
                                    )
                                }
                            } else {
                                Modifier
                            }
                        )
                ) {
                    if (currentPage == 0) {
                        // ── 3-Column Asymmetric Grid ──────────────────────────────────
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {

                            // 1. COLUMNA IZQUIERDA: Ford Fiesta 2016 Sedan + Clima
                            VehicleCard(
                                doorState   = doorState,
                                weatherData = weatherData,
                                modifier    = Modifier
                                    .weight(0.35f)
                                    .fillMaxHeight(),
                                onCarDetailsClick = { currentPage = 2 }
                            )

                            // 2. COLUMNA CENTRAL: Navegacion GPS + Modulo de Mensajes WhatsApp (o Cluster)
                            Column(
                                modifier = Modifier
                                    .weight(0.37f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                NavigationMapCard(
                                    modifier = Modifier
                                        .weight(if (isMapExpanded) 1f else 0.44f)
                                        .fillMaxWidth(),
                                    isExpanded = isMapExpanded,
                                    onToggleExpand = {
                                        isMapExpanded = !isMapExpanded
                                        selectedTab = if (isMapExpanded) 1 else 0
                                    },
                                    onMapClick = {
                                        if (!isMapExpanded) {
                                            isMapExpanded = true
                                            selectedTab = 1
                                        }
                                    }
                                )

                                if (!isMapExpanded) {
                                    if (showClusterInCenter) {
                                    Box(
                                        modifier = Modifier
                                            .weight(0.56f)
                                            .fillMaxWidth()
                                    ) {
                                        FordFiestaDashboardCard(
                                            speedMph = displaySpeedMph,
                                            obdData = obdData,
                                            doorState = doorState,
                                            isLightsOn = isLightsOn,
                                            onCardClick = { currentPage = 2 },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        IconButton(
                                            onClick = { showClusterInCenter = false },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(6.dp)
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF25D366))
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Chat,
                                                contentDescription = "Ver WhatsApp",
                                                tint = Color.White,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                } else {
                                    WhatsAppCard(
                                        voiceAssistant = voiceAssistant,
                                        onToggleCluster = { currentPage = 2 },
                                        onOpenContacts = {
                                            currentPage = 3
                                            selectedTab = 2
                                        },
                                        modifier = Modifier
                                            .weight(0.56f)
                                            .fillMaxWidth()
                                    )
                                }
                                }
                            }

                            // 3. COLUMNA DERECHA: YouTube Music + Velocimetro + Hey Google
                            Column(
                                modifier = Modifier
                                    .weight(0.28f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                MusicPlayerCard(
                                    mediaState      = displayMediaState,
                                    onPrevious      = {
                                        if (isRadioPlaying && activeRadioStation != null) {
                                            val idx = RadioManager.stations.indexOf(activeRadioStation)
                                            val prevIdx = if (idx > 0) idx - 1 else RadioManager.stations.size - 1
                                            RadioManager.playStation(context, RadioManager.stations[prevIdx])
                                        } else {
                                            mediaVm.skipToPrevious(context)
                                        }
                                    },
                                    onPlayPause     = {
                                        if (activeRadioStation != null) {
                                            RadioManager.togglePlay(context, activeRadioStation!!)
                                        } else {
                                            mediaVm.togglePlayPause(context)
                                        }
                                    },
                                    onNext          = {
                                        if (isRadioPlaying && activeRadioStation != null) {
                                            val idx = RadioManager.stations.indexOf(activeRadioStation)
                                            val nextIdx = (idx + 1) % RadioManager.stations.size
                                            RadioManager.playStation(context, RadioManager.stations[nextIdx])
                                        } else {
                                            mediaVm.skipToNext(context)
                                        }
                                    },
                                    onSeek          = { pos -> mediaVm.seekTo(context, pos) },
                                    onToggleShuffle = { mediaVm.toggleShuffle(context) },
                                    onToggleRepeat  = { mediaVm.toggleRepeat(context) },
                                    onLoadLrc       = { uri -> mediaVm.loadLrcFromUri(context, uri) },
                                    onOpenPlayer    = {
                                        currentPage = 1
                                        selectedTab = 3
                                    },
                                    modifier        = Modifier
                                        .weight(1.30f)
                                        .fillMaxWidth()
                                )
                                SpeedometerCard(
                                    speedMph    = displaySpeedMph,
                                    obdData     = obdData,
                                    onObdClick  = {
                                        com.x.carlauncher.obd.ObdManager.retryConnection()
                                        currentPage = 2
                                    },
                                    onCardClick = {
                                        currentPage = 2
                                    },
                                    modifier    = Modifier
                                        .weight(0.85f)
                                        .fillMaxWidth()
                                )
                                GeminiCard(
                                    isListening     = isVoiceListening,
                                    recognizedText  = recognizedVoiceText,
                                    feedback        = assistantFeedback,
                                    audioRmsLevel   = audioRmsLevel,
                                    onMicClick      = { voiceController.startListening() },
                                    onCommandClick  = { cmd -> voiceController.executeVoiceCommand(cmd) },
                                    modifier        = Modifier
                                        .weight(0.85f)
                                        .fillMaxWidth()
                                )
                            }
                        }
                    } else if (currentPage == 1) {
                        // ── PAGINA 1: REPRODUCTOR MUSICAL AMPLIADO ─────────────
                        ExpandedMusicScreen(
                            mediaState = mediaState,
                            onNavigateBack = {
                                currentPage = 0
                                selectedTab = 0
                            }
                        )
                    } else if (currentPage == 2) {
                        // ── PAGINA 2: CLUSTER DASHBOARD FORD FIESTA EN PANTALLA COMPLETA
                        ExpandedFordFiestaDashboardScreen(
                            speedMph       = displaySpeedMph,
                            obdData        = obdData,
                            doorState      = doorState,
                            isLightsOn     = isLightsOn,
                            onNavigateBack = {
                                currentPage = 0
                                selectedTab = 0
                            }
                        )
                    } else if (currentPage == 3) {
                        // ── PAGINA 3: CONTACTOS Y MARCADOR TELEFONICO INTEGRADO (NO ABRE APP EXTERNA)
                        ExpandedContactsScreen(
                            voiceAssistant = voiceAssistant,
                            onBackToHome = {
                                currentPage = 0
                                selectedTab = 0
                            }
                        )
                    } else if (currentPage == 4) {
                        // ── PAGINA 4: NAVEGACION DETALLADA (MAPA FULL) ──────────────────────
                        DetailedFullMapScreen(
                            onNavigateBack = {
                                currentPage = 0
                                selectedTab = 0
                            }
                        )
                    }
                }
            }
        }
        } // Cierre del Box adaptable (1024x600 dp escalado)

        // ── Full-Screen Native Automotive App Drawer ─────────────────────────
        AppDrawerScreen(
            isOpen = showAppDrawer,
            onClose = {
                showAppDrawer = false
                selectedTab = 0
            }
        )

        // ── Voice Assistant Floating HUD Banner (Only on full-screen modes) ─
        if (currentPage != 0 && (isVoiceListening || (assistantFeedback.isNotBlank() && assistantFeedback != "Listo para escuchar."))) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xF00A1026))
                    .border(
                        BorderStroke(
                            1.5.dp,
                            if (isVoiceListening) Color(0xFF38BDF8) else Color(0xFF9333EA)
                        ),
                        RoundedCornerShape(20.dp)
                    )
                    .clickable { voiceController.stopListening() }
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Voice Assistant",
                        tint = if (isVoiceListening) Color(0xFF38BDF8) else Color(0xFFA855F7),
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = if (isVoiceListening) "🎙️ Escuchando comando..." else "Comando de Voz",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isVoiceListening) Color(0xFF38BDF8) else Color(0xFFA855F7)
                        )
                        Text(
                            text = if (recognizedVoiceText.isNotBlank()) "\"$recognizedVoiceText\"" else assistantFeedback,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
