package com.x.carlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.x.carlauncher.system.RootHelper
import com.x.carlauncher.ui.screens.DashboardScreen
import android.app.KeyguardManager
import android.os.Build
import android.view.WindowManager
import com.x.carlauncher.ui.theme.ThemeManager
import com.x.carlauncher.ui.theme.XCarOSTheme
import com.x.carlauncher.voice.CarVoiceAssistant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        @Volatile
        var isActivityResumed: Boolean = false
            private set

        private const val REQUEST_ALL_PERMISSIONS = 1001
        private const val REQUEST_BACKGROUND_LOCATION = 1002
    }

    /**
     * Todos los permisos en tiempo de ejecución que CarOS necesita.
     * La ubicación en segundo plano (ACCESS_BACKGROUND_LOCATION) NO va
     * en esta lista: Android obliga a pedirla por separado y solo
     * después de conceder la ubicación precisa.
     */
    private fun allRuntimePermissions(): Array<String> {
        val perms = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.WRITE_CONTACTS,
            android.Manifest.permission.CALL_PHONE,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.ANSWER_PHONE_CALLS,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.CAMERA,
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            perms.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(android.Manifest.permission.BLUETOOTH_SCAN)
            perms.add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
            perms.add(android.Manifest.permission.READ_MEDIA_AUDIO)
            perms.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(android.Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            perms.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return perms.toTypedArray()
    }

    private fun requestAllPermissions() {
        val missing = allRuntimePermissions().filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_ALL_PERMISSIONS)
        } else {
            requestBackgroundLocationIfNeeded()
        }
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        requestPermissions(
            arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            REQUEST_BACKGROUND_LOCATION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_ALL_PERMISSIONS -> {
                // Con la ubicación concedida se pide la de segundo plano
                // por separado, como exige Android 11+.
                requestBackgroundLocationIfNeeded()
            }
            REQUEST_BACKGROUND_LOCATION -> {
                // Nada más que pedir: el GPS ya puede seguir en segundo plano.
            }
        }
        // Arrancar (o rearrancar) la ubicación real y contactos ahora que hay permisos.
        try {
            com.x.carlauncher.location.VehicleLocationManager.startListening()
            com.x.carlauncher.contacts.ContactsRepository.loadContacts(applicationContext)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "No se pudo arrancar el GPS real", e)
        }
    }

    private lateinit var audioManager: AudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        enableAutomotiveScreenFlags()

        // Engage Startup Guardian to reclaim foreground immediately when music starts on boot
        com.x.carlauncher.services.StartupGuardian.start(applicationContext)

        // Make fullscreen, hide system bars (nav bar + status bar)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Start AccOffService for vehicle power management and deep sleep
        val accIntent = Intent(this, com.x.carlauncher.services.AccOffService::class.java)
        try {
            startService(accIntent)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Cannot start AccOffService", e)
        }

        // Start FytMusicService safely
        val fytIntent = Intent(this, com.x.carlauncher.media.FytMusicService::class.java)
        try {
            startService(fytIntent)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Cannot start FytMusicService", e)
        }

        // Initialize persistent favorites and lyrics translation pipeline
        com.x.carlauncher.media.FavoritesManager.initialize(applicationContext)
        com.x.carlauncher.media.LyricsTranslationManager.initialize(applicationContext)

        // One-time cache clean for legacy mismatched lyrics to ensure 100% strict matching
        val lyricsPrefs = getSharedPreferences("x_lyrics_prefs", Context.MODE_PRIVATE)
        if (!lyricsPrefs.getBoolean("v1_2_strict_cache_wiped", false)) {
            com.x.carlauncher.media.LyricsRepository.wipeAllLyricsCache(applicationContext)
            lyricsPrefs.edit().putBoolean("v1_2_strict_cache_wiped", true).apply()
        }

        // Initialize native audio player engine (with instant seeking, USB scanning, and synced lyrics)
        com.x.carlauncher.media.XLocalPlayer.initialize(applicationContext)

        // Initialize unified automotive GPS location manager
        com.x.carlauncher.location.VehicleLocationManager.initialize(applicationContext)
        com.x.carlauncher.location.VehicleCompassManager.initialize(applicationContext)
        val initialDisplayRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_90
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        com.x.carlauncher.location.VehicleCompassManager.updateDisplayRotation(initialDisplayRotation)

        // Initialize vehicle illumination & darkness detection pipeline
        com.x.carlauncher.illumination.VehicleIlluminationManager.initialize(applicationContext)

        // Initialize automotive OBD-II manager (ELM327 CAN telemetry)
        com.x.carlauncher.obd.ObdManager.initialize(applicationContext)
        com.x.carlauncher.bluetooth.VehicleBluetoothSupervisor.initialize(applicationContext)
        com.x.carlauncher.carassets.CarAssetManager.initialize(applicationContext)
        com.x.carlauncher.media.PowerampNotificationListener.syncActiveNotifications(applicationContext)
        com.x.carlauncher.whatsapp.WhatsAppMessageManager.startPeriodicCheck(applicationContext)
        com.x.carlauncher.contacts.ContactsRepository.init(applicationContext)

        // Automatically start native music playback on vehicle startup / ignition
        com.x.carlauncher.media.XLocalPlayer.onVehicleIgnition()

        // One-time setup on initial install (persisted so it never executes or spams root toasts again)
        val initPrefs = applicationContext.getSharedPreferences("x_init_prefs", Context.MODE_PRIVATE)
        if (!initPrefs.getBoolean("initial_setup_completed", false)) {
            initPrefs.edit().putBoolean("initial_setup_completed", true).apply()
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                if (RootHelper.isRootAvailable()) {
                    RootHelper.performOneTimeSetup(applicationContext)
                }
            }
        }

        // Voice Assistant: Welcome X with warm Colombian female voice on startup (strictly once per ignition session)
        CarVoiceAssistant.getInstance(applicationContext).onVehicleStartupOrWake()

        // Pre-warm App Drawer cache in background for 0ms instant display
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.x.carlauncher.ui.screens.AppDrawerCache.getOrLoad(applicationContext)
        }

        // Pedir TODOS los permisos que la app usa (ubicación real de
        // Google/GPS, Bluetooth OBD-II, contactos, teléfono, micrófono,
        // cámara, archivos de música, notificaciones...). Sin ellos la
        // ubicación real y el resto de funciones no pueden arrancar.
        requestAllPermissions()

        // Initialize active automotive theme
        ThemeManager.initialize(applicationContext)

        setContent {
            val currentTheme by ThemeManager.currentTheme.collectAsState()
            // Aviso una vez por arranque: sin el acceso a notificaciones
            // CarOS no puede ver ni controlar YouTube Music, Spotify ni
            // ninguna otra app (el servicio de control depende de el).
            var showListenerPrompt by remember {
                mutableStateOf(!com.x.carlauncher.media.PowerampNotificationListener.isNotificationAccessGranted(applicationContext))
            }
            XCarOSTheme(theme = currentTheme) {
                DashboardScreen()
                if (showListenerPrompt) {
                    // Los dos pasos en el mismo aviso: no se puede confiar
                    // solo en detectar si los ajustes restringidos estan
                    // permitidos (la deteccion puede fallar en algunas
                    // ROMs), asi que los dos botones estan siempre a mano.
                    AlertDialog(
                        onDismissRequest = { showListenerPrompt = false },
                        title = { Text("Controlar YouTube Music") },
                        text = {
                            Text(
                                "Para que CarOS pueda ver y controlar lo que suena en " +
                                "YouTube Music hacen falta 2 pasos:\n\n" +
                                "1. Toca 'Abrir info de la app', luego los 3 puntos (⋮) y " +
                                "'Permitir configuración restringida'.\n" +
                                "2. Después toca 'Activar notificaciones' y enciende el " +
                                "acceso para CarOS."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showListenerPrompt = false
                                com.x.carlauncher.media.PowerampNotificationListener.openAppInfoSettings(applicationContext)
                            }) { Text("Abrir info de la app") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showListenerPrompt = false
                                com.x.carlauncher.media.PowerampNotificationListener.openNotificationAccessSettings(applicationContext)
                            }) { Text("Activar notificaciones") }
                        }
                    )
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (handleHardwareMediaKey(event.keyCode)) {
                return true
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (isMediaKeyCode(event.keyCode)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (handleHardwareMediaKey(keyCode)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun isMediaKeyCode(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
            KeyEvent.KEYCODE_NAVIGATE_NEXT,
            KeyEvent.KEYCODE_NAVIGATE_PREVIOUS,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOICE_ASSIST,
            KeyEvent.KEYCODE_ASSIST,
            KeyEvent.KEYCODE_SEARCH -> true
            else -> false
        }
    }

    private fun handleHardwareMediaKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
            KeyEvent.KEYCODE_NAVIGATE_NEXT,
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                handleTrackNext()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
            KeyEvent.KEYCODE_NAVIGATE_PREVIOUS,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                handleTrackPrev()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                handlePlayPause()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                handlePlay()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                handlePause()
                true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI
                )
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
                true
            }
            KeyEvent.KEYCODE_VOICE_ASSIST,
            KeyEvent.KEYCODE_ASSIST,
            KeyEvent.KEYCODE_SEARCH -> {
                com.x.carlauncher.voice.OfflineVoiceController.getInstance(applicationContext).startListening()
                true
            }
            else -> false
        }
    }

    private fun handleTrackNext() {
        android.util.Log.i("MainActivity", "Hardware SWC NEXT pressed")
        com.x.carlauncher.media.PowerampNotificationListener.skipToNext(applicationContext)
    }

    private fun handleTrackPrev() {
        android.util.Log.i("MainActivity", "Hardware SWC PREV pressed")
        com.x.carlauncher.media.PowerampNotificationListener.skipToPrevious(applicationContext)
    }

    private fun handlePlayPause() {
        android.util.Log.i("MainActivity", "Hardware SWC PLAY_PAUSE pressed")
        com.x.carlauncher.media.PowerampNotificationListener.togglePlayPause(applicationContext)
    }

    private fun handlePlay() {
        android.util.Log.i("MainActivity", "Hardware SWC PLAY pressed")
        com.x.carlauncher.media.PowerampNotificationListener.togglePlayPause(applicationContext)
    }

    private fun handlePause() {
        android.util.Log.i("MainActivity", "Hardware SWC PAUSE pressed")
        com.x.carlauncher.media.PowerampNotificationListener.togglePlayPause(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        val currentDisplayRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_90
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        com.x.carlauncher.location.VehicleCompassManager.updateDisplayRotation(currentDisplayRotation)
        // Passive status update for lyrics & track title (never steals UI focus)
        com.x.carlauncher.media.FytMusicService.requestStateUpdate(applicationContext)
        com.x.carlauncher.whatsapp.WhatsAppMessageManager.performCheck(applicationContext, notifyIfNew = false)
        // Al volver a CarOS (desde YouTube Music, desde Ajustes...) hay que
        // re-enlazar el servicio de escucha y re-leer las sesiones activas:
        // tras actualizar la app el servicio puede quedar sin enlazar y el
        // panel se queda en "Nada sonando" aunque la musica este sonando.
        // Si el servicio esta caido, se fuerza la reconexion (reinicia el
        // componente); si esta corriendo, basta con el rebind suave.
        val mediaListener = com.x.carlauncher.media.PowerampNotificationListener
        if (!mediaListener.isServiceRunning &&
            mediaListener.isNotificationAccessGranted(applicationContext)
        ) {
            mediaListener.forceReconnect(applicationContext)
        } else {
            mediaListener.refreshNow(applicationContext)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val currentDisplayRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_90
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        com.x.carlauncher.location.VehicleCompassManager.updateDisplayRotation(currentDisplayRotation)
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
    }

    override fun onDestroy() {
        super.onDestroy()
        com.x.carlauncher.whatsapp.WhatsAppMessageManager.stopPeriodicCheck()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (onBackPressedDispatcher.hasEnabledCallbacks()) {
            onBackPressedDispatcher.onBackPressed()
        }
        // Essential for Automotive Launcher: Never finish or half-collapse on back press when at root
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        enableAutomotiveScreenFlags()
        val action = intent?.action ?: ""
        if (action == Intent.ACTION_ASSIST ||
            action == Intent.ACTION_VOICE_COMMAND ||
            action == "android.intent.action.VOICE_COMMAND") {
            com.x.carlauncher.voice.OfflineVoiceController.getInstance(applicationContext).startListening()
        } else if (action == "com.x.carlauncher.ACTION_PIN_SHORTCUT") {
            requestPinHomeShortcut()
        }
    }

    fun requestPinHomeShortcut() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val sm = getSystemService(android.content.pm.ShortcutManager::class.java)
                if (sm != null && sm.isRequestPinShortcutSupported) {
                    val launchIntent = Intent(this, MainActivity::class.java).apply {
                        action = Intent.ACTION_MAIN
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    }
                    val pinShortcutInfo = android.content.pm.ShortcutInfo.Builder(this, "car_launcher_main_shortcut")
                        .setShortLabel("Car Launcher")
                        .setLongLabel("Car Launcher Ford Fiesta")
                        .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher))
                        .setIntent(launchIntent)
                        .build()
                    sm.requestPinShortcut(pinShortcutInfo, null)
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Could not pin shortcut", e)
            }
        }
    }

    private fun enableAutomotiveScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }
}