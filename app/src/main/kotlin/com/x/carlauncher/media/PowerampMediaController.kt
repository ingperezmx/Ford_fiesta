package com.x.carlauncher.media

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.InputStream

// ── Data classes ─────────────────────────────────────────────────────────────

/**
 * A single timestamped line of synchronized lyrics (LRC).
 */
data class LyricEntry(
    val timeMs: Long,
    val text: String,
    val translation: String? = null
)

/**
 * Universal media snapshot supporting ANY audio player:
 * Poweramp, Spotify, YouTube Music, Apple Music, VLC, Deezer, Tidal, Amazon Music,
 * Musicolet, Bluetooth AVRCP, and stock Android media players.
 */
data class MediaState(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArt: Bitmap? = null,
    val progressMs: Long = 0L,
    val durationMs: Long = 1L,
    val isPlaying: Boolean = false,
    val playerName: String = "",
    val lyricsLine: String = "",
    val fullLyrics: List<LyricEntry> = emptyList(),
    val rawLyricsText: String = "",
    val hasLyrics: Boolean = false,
    val playbackMode: PlaybackMode = PlaybackMode.SEQUENCE
)

enum class AudioSource {
    LOCAL,
    BLUETOOTH,
    USB,
    ONLINE
}

// ── Universal Media & Notification Listener Service ──────────────────────────

/**
 * System-privileged NotificationListenerService that connects to Android's
 * MediaSessionManager to monitor and control 100% of all media apps running
 * on the head unit (Spotify, Poweramp, YouTube Music, Apple Music, Bluetooth audio, etc.).
 */
class PowerampNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "XCarOS_Media"
        private const val POWERAMP_PKG = "com.maxmpz.audioplayer"

        // Poweramp broadcast intents (for Poweramp-specific deep enhancements)
        private const val ACTION_POWERAMP_TRACK_CHANGED = "com.maxmpz.audioplayer.TRACK_CHANGED"
        private const val ACTION_POWERAMP_STATUS_CHANGED = "com.maxmpz.audioplayer.STATUS_CHANGED"
        private const val ACTION_POWERAMP_API_COMMAND = "com.maxmpz.audioplayer.API_COMMAND"

        private val _mediaState = MutableStateFlow(MediaState())
        val mediaState: StateFlow<MediaState> = _mediaState.asStateFlow()

        var activeController: MediaController? = null
            private set
        private var instance: PowerampNotificationListener? = null

        var parsedLyrics: List<LyricEntry> = emptyList()
            private set

        private val _currentSource = MutableStateFlow(AudioSource.LOCAL)
        val currentSource: StateFlow<AudioSource> = _currentSource.asStateFlow()

        fun setSource(source: AudioSource) {
            _currentSource.value = source
        }

        fun toggleSource() {
            _currentSource.value = when (_currentSource.value) {
                AudioSource.LOCAL, AudioSource.USB -> {
                    if (activeController != null) AudioSource.ONLINE else AudioSource.BLUETOOTH
                }
                AudioSource.ONLINE -> AudioSource.BLUETOOTH
                AudioSource.BLUETOOTH -> AudioSource.LOCAL
            }
            Log.i(TAG, "Audio source toggled to: ${_currentSource.value}")
        }

        /**
         * ¿CarOS tiene el "Acceso a notificaciones" activado en Ajustes?
         * Sin este permiso Android no deja que el NotificationListenerService
         * vea las sesiones multimedia, y el control de YouTube Music,
         * Spotify, etc. no funciona aunque el servicio este declarado.
         * Es un permiso especial: no se pide con requestPermissions, el
         * usuario lo activa en Ajustes del sistema (o desde el boton que
         * abre esa pantalla con [openNotificationAccessSettings]).
         */
        fun isNotificationAccessGranted(context: Context): Boolean {
            val enabled = try {
                Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            } catch (_: Exception) {
                null
            } ?: return false
            val component = ComponentName(context, PowerampNotificationListener::class.java)
                .flattenToString()
            return enabled.split(":").any { it == component }
        }

        /** Abre la pantalla de Ajustes donde se activa el acceso a notificaciones. */
        fun openNotificationAccessSettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo abrir los ajustes de acceso a notificaciones", e)
            }
        }

        /**
         * ¿Android permite activar los ajustes restringidos para CarOS?
         * En Android 13+ las apps instaladas desde un archivo (no desde
         * Play Store) tienen bloqueados los permisos especiales (acceso a
         * notificaciones, mostrar sobre otras apps...) hasta que el usuario
         * toca "Permitir configuracion restringida" en la info de la app.
         * Se detecta con el app-op interno "android:access_restricted_settings".
         */
        fun isRestrictedSettingsAllowed(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
            return try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
                    ?: return true
                val mode = appOps.checkOpNoThrow(
                    "android:access_restricted_settings",
                    android.os.Process.myUid(),
                    context.packageName
                )
                mode == android.app.AppOpsManager.MODE_ALLOWED
            } catch (_: Exception) {
                true
            }
        }

        /** Abre la ficha "Info de la app" en Ajustes, donde esta el menu ⋮ con "Permitir configuracion restringida". */
        fun openAppInfoSettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo abrir la info de la app", e)
            }
        }

        /** ¿El servicio de escucha esta corriendo? (diagnostico del panel). */
        val isServiceRunning: Boolean
            get() = instance != null

        /** Paquete de la sesion multimedia enlazada, o null si no hay ninguna. */
        val activeSessionPackage: String?
            get() = activeController?.packageName

        /**
         * Reintenta conectar el listener y refrescar las sesiones activas.
         * Se llama cuando el usuario vuelve de Ajustes tras activar el
         * acceso, para que el control empiece a funcionar sin reiniciar.
         */
        fun refreshNow(context: Context) {
            val listener = instance
            if (listener != null) {
                listener.refreshActiveSessions()
            } else {
                try {
                    requestRebind(ComponentName(context, PowerampNotificationListener::class.java))
                } catch (_: Exception) {}
            }
        }

        /**
         * Reconexion forzada del servicio de escucha. En algunas pantallas
         * FYT el sistema no vuelve a enlazar el NotificationListenerService
         * tras actualizar la app aunque el acceso siga concedido, y el
         * requestRebind() de refreshNow no lo despierta (el panel se queda
         * en "Servicio: NO"). Apagar y volver a encender el componente
         * (nuestro propio servicio, sin permiso especial) obliga al sistema
         * a reconectarlo; despues se pide el rebind explicito. No mata la
         * app (DONT_KILL_APP). Si ni asi arranca, queda reiniciar la pantalla.
         */
        fun forceReconnect(context: Context) {
            try {
                val pm = context.packageManager
                val component = ComponentName(context, PowerampNotificationListener::class.java)
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                Log.i(TAG, "Componente del listener reiniciado, pidiendo rebind")
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo reiniciar el componente del listener", e)
            }
            refreshNow(context)
        }

        /**
         * ¿Los botones (play/pausa, siguiente, anterior, seek) deben ir a
         * la sesion externa (YouTube Music, Spotify...) en vez de al
         * reproductor local? Si, cuando el usuario eligio ONLINE, cuando la
         * sesion externa esta sonando, o cuando lo que se muestra en
         * pantalla ya es de esa app. Sin esto, con la fuente en LOCAL por
         * defecto, los botones controlaban la musica local aunque YouTube
         * Music estuviera sonando.
         */
        /**
         * ¿Hay una sesion externa (YouTube Music, Spotify...) que deba recibir
         * los botones? Lo usan los comandos de XLocalPlayer (volante / widget
         * FYT / teclas multimedia) para que esos botones controlen la app
         * que esta sonando y no solo la musica local.
         */
        fun isExternalSessionActive(): Boolean = shouldUseExternalController()

        private fun shouldUseExternalController(): Boolean {
            val controller = activeController ?: return false
            if (_currentSource.value == AudioSource.ONLINE) return true
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) return true
            val name = _mediaState.value.playerName
            return name != "Musica Local" && name != "Musica Pantalla"
        }

        @Volatile
        private var lastLocalMediaState: MediaState? = null
        @Volatile
        private var lastLocalLyrics: List<LyricEntry> = emptyList()

        /**
         * Restores local OEM media state and switches back to AudioSource.LOCAL.
         */
        /**
         * Sincroniza inmediatamente las notificaciones activas del sistema (ej. WhatsApp).
         */
        fun syncActiveNotifications(context: Context? = null) {
            val listener = instance
            if (listener != null) {
                try {
                    val active = listener.activeNotifications
                    Log.i(TAG, "Sincronizando notificaciones activas (${active?.size ?: 0} encontradas)")
                    active?.forEach { sbn ->
                        val p = sbn?.packageName ?: return@forEach
                        if (p == "com.whatsapp" || p == "com.whatsapp.w4b") {
                            com.x.carlauncher.whatsapp.WhatsAppMessageManager.handleNotification(sbn)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error consultando notificaciones activas: ${e.message}")
                }
            } else {
                Log.i(TAG, "PowerampNotificationListener instance null, solicitando conexion de servicio")
                if (context != null) {
                    try {
                        requestRebind(android.content.ComponentName(context, PowerampNotificationListener::class.java))
                    } catch (_: Exception) {}
                }
            }
        }

        fun restoreLocalMediaState(context: Context? = null) {
            Log.i(TAG, "Restoring local media state (switching fully to AudioSource.LOCAL)")
            _currentSource.value = AudioSource.LOCAL
            val cached = lastLocalMediaState
            if (cached != null && cached.title.isNotBlank()) {
                _mediaState.value = cached.copy(isPlaying = false)
                parsedLyrics = lastLocalLyrics
            } else {
                _mediaState.value = _mediaState.value.copy(
                    playerName = "Musica Pantalla"
                )
            }
            if (context != null) {
                FytMusicService.requestStateUpdate(context)
            }
        }

        /**
         * Direct update from Bluetooth AVRCP.
         */
        fun updateFromBluetooth(
            title: String,
            artist: String,
            album: String = "",
            isPlaying: Boolean,
            durationMs: Long = 0L,
            albumArt: Bitmap? = null
        ) {
            if (title.isBlank() && !isPlaying) return

            // If Bluetooth starts active playback, switch active source automatically
            if (isPlaying) {
                _currentSource.value = AudioSource.BLUETOOTH
                XLocalPlayer.pause()
            }

            val deviceName = com.x.carlauncher.bluetooth.BluetoothViewModel.currentDeviceName.ifBlank { "el telefono" }
            val resolvedTitle = when {
                title.isNotBlank() -> title
                isPlaying -> "Reproduciendo en $deviceName"
                else -> _mediaState.value.title.ifBlank { "Audio conectado" }
            }
            val resolvedArtist = when {
                artist.isNotBlank() -> artist
                album.isNotBlank() -> album
                isPlaying -> "Audio Bluetooth en vivo"
                else -> "Listo para reproducir"
            }

            if (_currentSource.value == AudioSource.BLUETOOTH || !_mediaState.value.isPlaying) {
                _mediaState.value = _mediaState.value.copy(
                    title = resolvedTitle,
                    artist = resolvedArtist,
                    album = album.ifBlank { _mediaState.value.album },
                    albumArt = albumArt ?: _mediaState.value.albumArt,
                    isPlaying = isPlaying,
                    durationMs = if (durationMs > 0) durationMs else _mediaState.value.durationMs,
                    playerName = "Bluetooth Audio"
                )
                if (title.isNotBlank()) {
                    autoDetectLyrics(artist, title)
                }
            }
        }

        /**
         * Actively polls active MediaSessions for Bluetooth AVRCP metadata.
         */
        fun syncBluetoothSession() {
            instance?.let { listener ->
                try {
                    val component = ComponentName(listener, PowerampNotificationListener::class.java)
                    val controllers = listener.mediaSessionManager?.getActiveSessions(component)
                    listener.updateActiveController(controllers)
                    val bt = listener.bluetoothController
                    if (bt != null) {
                        listener.handleUniversalBluetoothMetadata(bt.metadata)
                        listener.handleUniversalBluetoothPlaybackState(bt.playbackState)
                    }
                    Unit
                } catch (e: Exception) {
                    Log.w(TAG, "Error syncing Bluetooth session", e)
                }
            }
        }

        /**
         * Direct update from XLocalPlayer.
         */
        fun updateFromLocalPlayer(
            title: String,
            artist: String,
            album: String,
            albumArt: Bitmap?,
            isPlaying: Boolean,
            progressMs: Long,
            durationMs: Long,
            lyricsLine: String,
            fullLyrics: List<LyricEntry>,
            playbackMode: PlaybackMode = PlaybackMode.SEQUENCE
        ) {
            _currentSource.value = AudioSource.LOCAL
            parsedLyrics = fullLyrics
            _mediaState.value = _mediaState.value.copy(
                title = title,
                artist = artist,
                album = album,
                albumArt = albumArt ?: _mediaState.value.albumArt,
                isPlaying = isPlaying,
                progressMs = progressMs,
                durationMs = durationMs,
                lyricsLine = lyricsLine,
                fullLyrics = fullLyrics,
                hasLyrics = fullLyrics.isNotEmpty(),
                playerName = "Musica Local",
                playbackMode = playbackMode
            )
            if (title.isNotBlank()) {
                lastLocalMediaState = _mediaState.value
                lastLocalLyrics = fullLyrics
            }
        }

        /**
         * Direct update from OEM FYT / SYU Car Music Service (com.syu.music).
         */
        fun updateFromFyt(
            title: String,
            artist: String,
            album: String,
            albumArt: Bitmap?,
            isPlaying: Boolean,
            progressMs: Long,
            durationMs: Long,
            lyricsLine: String,
            fullLyrics: List<LyricEntry>,
            playerName: String = "Musica Pantalla"
        ) {
            if (isPlaying) {
                _currentSource.value = AudioSource.LOCAL
            }

            parsedLyrics = fullLyrics
            _mediaState.value = _mediaState.value.copy(
                title = title,
                artist = artist,
                album = album,
                albumArt = albumArt ?: _mediaState.value.albumArt,
                isPlaying = isPlaying,
                progressMs = progressMs,
                durationMs = durationMs,
                lyricsLine = lyricsLine,
                fullLyrics = fullLyrics,
                hasLyrics = fullLyrics.isNotEmpty(),
                playerName = playerName
            )

            if (title.isNotBlank()) {
                lastLocalMediaState = _mediaState.value
                lastLocalLyrics = fullLyrics
            }
        }

        fun setOptimisticPlaying(playing: Boolean) {
            _mediaState.value = _mediaState.value.copy(isPlaying = playing)
        }

        fun dispatchHardwareMediaKey(context: Context, keyCode: Int) {
            dispatchMediaKey(context, keyCode)
        }

        // ── Universal Playback Controls ──────────────────────────────────────

        fun play(context: Context) {
            val source = _currentSource.value
            val isBtConnected = com.x.carlauncher.bluetooth.BluetoothViewModel.isDeviceConnected
            val onlineController = activeController

            Log.i(TAG, "play invoked (source=$source, isBtConnected=$isBtConnected, onlineController=${onlineController?.packageName})")

            // 1. Bluetooth Audio
            if (isBtConnected && (source == AudioSource.BLUETOOTH || _mediaState.value.playerName == "Bluetooth Audio")) {
                val bt = instance?.bluetoothController
                if (bt != null) {
                    try {
                        bt.transportControls.play()
                        setOptimisticPlaying(true)
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed bluetooth transportControls play", e)
                    }
                }
                dispatchHardwareMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
                FytMusicService.sendBtPlayPause(context)
                return
            }

            // 2. Online / External Player (Amazon Music, Spotify, YouTube Music, etc.)
            if (onlineController != null) {
                try {
                    onlineController.transportControls.play()
                    setOptimisticPlaying(true)
                    Log.i(TAG, "Triggered transportControls.play() for ${onlineController.packageName}")
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Failed transportControls play, falling back to key", e)
                }
            }

            // 3. System Hardware Media Play Key & Broadcast to Amazon Music
            setOptimisticPlaying(true)
            dispatchHardwareMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
            try {
                val amznDown = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    setPackage("com.google.android.apps.youtube.music")
                    putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                }
                context.sendOrderedBroadcast(amznDown, null)
                val amznUp = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    setPackage("com.google.android.apps.youtube.music")
                    putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                }
                context.sendOrderedBroadcast(amznUp, null)
            } catch (_: Exception) {}

            // 4. Fallback to local player
            if (onlineController == null) {
                XLocalPlayer.play()
            }
        }

        fun pause(context: Context) {
            val source = _currentSource.value
            val isBtConnected = com.x.carlauncher.bluetooth.BluetoothViewModel.isDeviceConnected
            val onlineController = activeController

            Log.i(TAG, "pause invoked (source=$source, isBtConnected=$isBtConnected, onlineController=${onlineController?.packageName})")

            if (isBtConnected && (source == AudioSource.BLUETOOTH || _mediaState.value.playerName == "Bluetooth Audio")) {
                val bt = instance?.bluetoothController
                if (bt != null) {
                    try {
                        bt.transportControls.pause()
                        setOptimisticPlaying(false)
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed bluetooth transportControls pause", e)
                    }
                }
                dispatchHardwareMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
                return
            }

            if (onlineController != null) {
                try {
                    onlineController.transportControls.pause()
                    setOptimisticPlaying(false)
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Failed transportControls pause", e)
                }
            }

            setOptimisticPlaying(false)
            dispatchHardwareMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
            XLocalPlayer.pause()
        }

        fun togglePlayPause(context: Context) {
            val source = _currentSource.value
            val isBtConnected = com.x.carlauncher.bluetooth.BluetoothViewModel.isDeviceConnected
            val onlineController = activeController

            Log.i(TAG, "togglePlayPause invoked (source=$source, isBtConnected=$isBtConnected, onlineController=${onlineController?.packageName})")

            // 1. Bluetooth Audio (Standard Android A2DP Sink / AVRCP)
            if (isBtConnected && (source == AudioSource.BLUETOOTH || _mediaState.value.playerName == "Bluetooth Audio")) {
                val bt = instance?.bluetoothController
                if (bt != null) {
                    val isPlaying = bt.playbackState?.state == PlaybackState.STATE_PLAYING
                    setOptimisticPlaying(!isPlaying)
                    try {
                        if (isPlaying) {
                            bt.transportControls.pause()
                        } else {
                            bt.transportControls.play()
                        }
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed bluetooth transportControls play/pause, falling back", e)
                    }
                }
                setOptimisticPlaying(!_mediaState.value.isPlaying)
                dispatchHardwareMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                FytMusicService.sendBtPlayPause(context)
                return
            }

            // 2. Online / External Player (Spotify, YouTube Music, Apple Music, Deezer, etc.)
            // Si la sesion externa esta sonando, los botones la controlan
            // a ella aunque la fuente elegida sea LOCAL (ver shouldUseExternalController).
            if (shouldUseExternalController()) {
                if (onlineController != null) {
                    val isPlaying = onlineController.playbackState?.state == PlaybackState.STATE_PLAYING
                    setOptimisticPlaying(!isPlaying)
                    try {
                        if (isPlaying) {
                            onlineController.transportControls.pause()
                        } else {
                            onlineController.transportControls.play()
                        }
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed transportControls play/pause, falling back to key", e)
                    }
                }
                dispatchHardwareMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                return
            }

            // 3. Local Player (USB / Storage)
            _currentSource.value = AudioSource.LOCAL
            XLocalPlayer.togglePlayPause()
        }

        fun skipToNext(context: Context) {
            val source = _currentSource.value
            val isBtConnected = com.x.carlauncher.bluetooth.BluetoothViewModel.isDeviceConnected
            val onlineController = activeController

            Log.i(TAG, "skipToNext invoked (source=$source, isBtConnected=$isBtConnected, onlineController=${onlineController?.packageName})")

            // 1. Bluetooth Audio
            if (isBtConnected && (source == AudioSource.BLUETOOTH || _mediaState.value.playerName == "Bluetooth Audio")) {
                val bt = instance?.bluetoothController
                if (bt != null) {
                    try {
                        bt.transportControls.skipToNext()
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed bluetooth transportControls skipToNext", e)
                    }
                }
                dispatchHardwareMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
                FytMusicService.sendBtNext(context)
                return
            }

            // 2. Online / External Player
            if (shouldUseExternalController()) {
                if (onlineController != null) {
                    try {
                        onlineController.transportControls.skipToNext()
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed transportControls skipToNext, falling back to key", e)
                    }
                }
                dispatchHardwareMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
                return
            }

            // 3. Local Player
            _currentSource.value = AudioSource.LOCAL
            XLocalPlayer.skipToNext()
        }

        fun skipToPrevious(context: Context) {
            val source = _currentSource.value
            val isBtConnected = com.x.carlauncher.bluetooth.BluetoothViewModel.isDeviceConnected
            val onlineController = activeController

            Log.i(TAG, "skipToPrevious invoked (source=$source, isBtConnected=$isBtConnected, onlineController=${onlineController?.packageName})")

            // 1. Bluetooth Audio
            if (isBtConnected && (source == AudioSource.BLUETOOTH || _mediaState.value.playerName == "Bluetooth Audio")) {
                val bt = instance?.bluetoothController
                if (bt != null) {
                    try {
                        bt.transportControls.skipToPrevious()
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed bluetooth transportControls skipToPrevious", e)
                    }
                }
                dispatchHardwareMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                FytMusicService.sendBtPrev(context)
                return
            }

            // 2. Online / External Player
            if (shouldUseExternalController()) {
                if (onlineController != null) {
                    try {
                        onlineController.transportControls.skipToPrevious()
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed transportControls skipToPrevious, falling back to key", e)
                    }
                }
                dispatchHardwareMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                return
            }

            // 3. Local Player
            _currentSource.value = AudioSource.LOCAL
            XLocalPlayer.skipToPrevious()
        }

        fun seekTo(context: Context, positionMs: Long) {
            val source = _currentSource.value
            val isBtConnected = com.x.carlauncher.bluetooth.BluetoothViewModel.isDeviceConnected
            val onlineController = activeController

            if (isBtConnected && (source == AudioSource.BLUETOOTH || _mediaState.value.playerName == "Bluetooth Audio")) {
                val bt = instance?.bluetoothController
                if (bt != null) {
                    try {
                        bt.transportControls.seekTo(positionMs)
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed bluetooth transportControls seekTo", e)
                    }
                }
                return
            }

            if (shouldUseExternalController()) {
                if (onlineController != null) {
                    try {
                        onlineController.transportControls.seekTo(positionMs)
                        _mediaState.value = _mediaState.value.copy(
                            progressMs = positionMs,
                            lyricsLine = getCurrentLyricLine(parsedLyrics, positionMs)
                        )
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed transportControls seekTo", e)
                    }
                }
            }

            _currentSource.value = AudioSource.LOCAL
            XLocalPlayer.seekTo(positionMs)
        }

        fun toggleShuffle(context: Context) {
            // Solo musica local: la API de MediaSession de Android no
            // expone aleatorio/repetir para el controlador (no existen
            // setShuffleMode/setRepeatMode en el framework), asi que para
            // YouTube Music o Spotify se cambian dentro de su propia app.
            if (_currentSource.value == AudioSource.LOCAL) {
                val mode = XLocalPlayer.toggleShuffle()
                val msg = if (mode == PlaybackMode.SHUFFLE) "Modo: Aleatorio activado" else "Modo: Secuencial en bucle"
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        fun toggleRepeat(context: Context) {
            // Solo musica local (ver toggleShuffle: el framework no expone
            // repetir para el controlador de una sesion externa).
            if (_currentSource.value == AudioSource.LOCAL) {
                val mode = XLocalPlayer.toggleRepeat()
                val msg = if (mode == PlaybackMode.REPEAT_ONE) "Modo: Repetir pista actual" else "Modo: Secuencial en bucle"
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        fun cyclePlaybackMode(context: Context) {
            if (_currentSource.value == AudioSource.LOCAL) {
                val mode = XLocalPlayer.cyclePlaybackMode()
                val msg = when (mode) {
                    PlaybackMode.SEQUENCE -> "Modo: Secuencial en bucle"
                    PlaybackMode.REPEAT_ONE -> "Modo: Repetir pista actual"
                    PlaybackMode.SHUFFLE -> "Modo: Reproduccion aleatoria"
                }
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        private fun dispatchMediaKey(context: Context, keyCode: Int) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }

        // ── Lyrics Parser (.lrc & embedded) ──────────────────────────────────

        fun parseLrcStream(inputStream: InputStream): List<LyricEntry> {
            return try {
                val timestampRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{2,3}))?\]""")
                val result = mutableListOf<LyricEntry>()

                inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { rawLine ->
                        val line = rawLine.trim()
                        if (line.isNotEmpty()) {
                            val matches = timestampRegex.findAll(line).toList()
                            if (matches.isNotEmpty()) {
                                val lastMatch = matches.last()
                                val lyricText = line.substring(lastMatch.range.last + 1).trim()

                                for (match in matches) {
                                    val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
                                    val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
                                    val centisStr = match.groupValues[3]
                                    val centis = when {
                                        centisStr.isEmpty() -> 0L
                                        centisStr.length == 2 -> centisStr.toLong() * 10L
                                        else -> centisStr.toLong()
                                    }
                                    val millis = minutes * 60_000L + seconds * 1_000L + centis
                                    result.add(LyricEntry(millis, lyricText))
                                }
                            }
                        }
                    }
                }

                val sorted = result.sortedBy { it.timeMs }
                parsedLyrics = sorted
                _mediaState.value = _mediaState.value.copy(
                    fullLyrics = sorted,
                    hasLyrics = sorted.isNotEmpty(),
                    lyricsLine = getCurrentLyricLine(sorted, _mediaState.value.progressMs)
                )
                sorted
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse lyrics stream", e)
                emptyList()
            }
        }

        fun parseLrcFile(path: String): List<LyricEntry> {
            val file = File(path)
            if (!file.exists()) return emptyList()
            return parseLrcStream(file.inputStream())
        }

        fun getCurrentLyricLine(lyrics: List<LyricEntry>, positionMs: Long): String {
            if (lyrics.isEmpty()) return ""
            var lo = 0
            var hi = lyrics.size - 1
            var result = ""
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (lyrics[mid].timeMs <= positionMs) {
                    result = lyrics[mid].text
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return result
        }

        /**
         * Search for matching local .lrc files in common Android music paths,
         * or fetches synchronized lyrics online via LyricsRepository (LRCLIB).
         */
        fun autoDetectLyrics(artist: String, title: String) {
            if (title.isBlank() || title.contains("Reproduciendo", ignoreCase = true) || title.contains("Bluetooth", ignoreCase = true)) return

            val searchDirs = listOf(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Lyrics"),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                File("/sdcard/Music/Lyrics"),
                File("/sdcard/Music"),
                File("/sdcard/Download")
            )

            val cleanTitle = title.lowercase().replace(Regex("""[^a-z0-9]"""), "")
            val cleanArtist = artist.lowercase().replace(Regex("""[^a-z0-9]"""), "")

            for (dir in searchDirs) {
                if (dir.exists() && dir.isDirectory) {
                    val matching = dir.listFiles { f ->
                        if (!f.name.endsWith(".lrc", ignoreCase = true)) return@listFiles false
                        val name = f.nameWithoutExtension.lowercase().replace(Regex("""[^a-z0-9]"""), "")
                        name.contains(cleanTitle) || (cleanArtist.isNotEmpty() && name.contains(cleanArtist))
                    }
                    if (!matching.isNullOrEmpty()) {
                        Log.i(TAG, "Auto-detected matching local LRC file: ${matching[0].absolutePath}")
                        parseLrcFile(matching[0].absolutePath)
                        return
                    }
                }
            }

            // Fallback: Query online LRCLIB database via LyricsRepository
            val ctx = instance?.applicationContext ?: return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val lyrics = LyricsRepository.getOrFetchLyrics(ctx, title, artist)
                    if (lyrics.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            val cur = _mediaState.value
                            if (cur.title.isBlank() || cur.title.contains(title, ignoreCase = true) || title.contains(cur.title, ignoreCase = true)) {
                                parsedLyrics = lyrics
                                _mediaState.value = cur.copy(
                                    fullLyrics = lyrics,
                                    hasLyrics = true,
                                    lyricsLine = getCurrentLyricLine(lyrics, cur.progressMs)
                                )
                                Log.i(TAG, "Loaded ${lyrics.size} synchronized lyrics from LRCLIB for '$title' by '$artist'")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot fetch online lyrics for track '$title'", e)
                }
            }
        }
    }

    private var mediaSessionManager: MediaSessionManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressTickerJob: Job? = null

    // ── MediaController Callback ─────────────────────────────────────────────

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            Log.d(TAG, "MediaController metadata changed")
            handleUniversalMetadata(metadata, activeController?.packageName)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            Log.d(TAG, "MediaController playback state changed: ${state?.state}")
            handleUniversalPlaybackState(state)
        }

        override fun onSessionDestroyed() {
            Log.d(TAG, "Media session destroyed")
            activeController = null
            refreshActiveSessions()
        }
    }

    private var bluetoothController: MediaController? = null

    private val bluetoothCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            Log.i(TAG, "bluetoothController metadata changed callback")
            handleUniversalBluetoothMetadata(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            Log.i(TAG, "bluetoothController playback state changed callback: ${state?.state}")
            handleUniversalBluetoothPlaybackState(state)
        }

        override fun onSessionDestroyed() {
            Log.i(TAG, "bluetoothController session destroyed")
            bluetoothController = null
        }
    }

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        Log.d(TAG, "Active sessions changed. Found ${controllers?.size ?: 0} controllers")
        updateActiveController(controllers)
    }

    // ── Poweramp Direct Receiver (supplementary) ─────────────────────────────

    private val powerampReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                ACTION_POWERAMP_TRACK_CHANGED -> {
                    val title = intent.getStringExtra("title") ?: ""
                    val artist = intent.getStringExtra("artist") ?: ""
                    val albumArt: Bitmap? = intent.getParcelableExtra("albumArt")
                    val duration = intent.getLongExtra("dur", 0L)

                    val current = _mediaState.value
                    _mediaState.value = current.copy(
                        title = title.ifEmpty { current.title },
                        artist = artist.ifEmpty { current.artist },
                        albumArt = albumArt ?: current.albumArt,
                        durationMs = if (duration > 0) duration else current.durationMs,
                        progressMs = 0L,
                        playerName = "Poweramp"
                    )
                    autoDetectLyrics(artist, title)
                }
                ACTION_POWERAMP_STATUS_CHANGED -> {
                    val stateCode = intent.getIntExtra("state", -1)
                    val isPlaying = stateCode == 1
                    val positionMs = intent.getLongExtra("position", _mediaState.value.progressMs)
                    val current = _mediaState.value
                    _mediaState.value = current.copy(
                        isPlaying = isPlaying,
                        progressMs = positionMs,
                        lyricsLine = getCurrentLyricLine(parsedLyrics, positionMs)
                    )
                }
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Universal MediaNotificationListener created")

        // Register Poweramp direct broadcast receiver
        val filter = IntentFilter().apply {
            addAction(ACTION_POWERAMP_TRACK_CHANGED)
            addAction(ACTION_POWERAMP_STATUS_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerampReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(powerampReceiver, filter)
        }

        // Initialize MediaSessionManager
        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val component = ComponentName(this, PowerampNotificationListener::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, component)
            refreshActiveSessions()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaSessionManager", e)
        }

        startProgressTicker()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(powerampReceiver)
            activeController?.unregisterCallback(controllerCallback)
            bluetoothController?.unregisterCallback(bluetoothCallback)
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
            mainHandler.removeCallbacksAndMessages(null)
            progressTickerJob?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error during cleanup", e)
        }
        instance = null
        super.onDestroy()
    }

    // ── Active Sessions Resolution ───────────────────────────────────────────

    fun refreshActiveSessions() {
        try {
            val component = ComponentName(this, PowerampNotificationListener::class.java)
            val controllers = mediaSessionManager?.getActiveSessions(component)
            updateActiveController(controllers)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot get active sessions - permission may be pending", e)
        }
    }

    private fun updateActiveController(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) return

        // 1. Dedicated Bluetooth controller for com.android.bluetooth
        val bt = controllers.firstOrNull { it.packageName.contains("bluetooth", ignoreCase = true) }
        if (bt != null) {
            if (bluetoothController?.sessionToken != bt.sessionToken) {
                bluetoothController?.unregisterCallback(bluetoothCallback)
                bluetoothController = bt
                bt.registerCallback(bluetoothCallback, mainHandler)
                Log.i(TAG, "Bound dedicated bluetoothController to ${bt.packageName}")
                handleUniversalBluetoothMetadata(bt.metadata)
                handleUniversalBluetoothPlaybackState(bt.playbackState)
            }
        } else {
            if (bluetoothController != null) {
                bluetoothController?.unregisterCallback(bluetoothCallback)
                bluetoothController = null
            }
        }

        // 2. Select preferred media player for other sessions (Spotify, YouTube Music, Apple Music, Deezer, etc.)
        val myPkg = instance?.packageName ?: ""
        val externalControllers = controllers.filter {
            !it.packageName.contains("bluetooth", ignoreCase = true) &&
            it.packageName != "com.syu.music" &&
            it.packageName != myPkg &&
            !it.packageName.startsWith("com.x.carlauncher")
        }

        val preferred = externalControllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: externalControllers.firstOrNull()

        if (preferred != null) {
            val isPreferredPlaying = preferred.playbackState?.state == PlaybackState.STATE_PLAYING
            if (isPreferredPlaying && XLocalPlayer.isPlaying) {
                Log.i(TAG, "External streaming player (${preferred.packageName}) is playing -> auto-pausing local player")
                XLocalPlayer.pause()
            }

            if (activeController?.sessionToken != preferred.sessionToken) {
                activeController?.unregisterCallback(controllerCallback)
                activeController = preferred
                preferred.registerCallback(controllerCallback, mainHandler)

                Log.i(TAG, "Bound to active media player: ${preferred.packageName} (playing=${preferred.playbackState?.state == PlaybackState.STATE_PLAYING})")
                handleUniversalMetadata(preferred.metadata, preferred.packageName)
                handleUniversalPlaybackState(preferred.playbackState)
            } else {
                handleUniversalMetadata(preferred.metadata, preferred.packageName)
                handleUniversalPlaybackState(preferred.playbackState)
            }
        }
    }

    private fun handleUniversalBluetoothMetadata(metadata: MediaMetadata?) {
        metadata ?: return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR)
            ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val albumArt: Bitmap? = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        val isPlaying = bluetoothController?.playbackState?.state == PlaybackState.STATE_PLAYING

        Log.i(TAG, "handleUniversalBluetoothMetadata: title='$title', artist='$artist', album='$album', dur=$duration, isPlaying=$isPlaying")

        if (title.isNotBlank() || isPlaying) {
            com.x.carlauncher.bluetooth.BluetoothStateReceiver.updateBtTrack(
                title = title,
                artist = artist,
                album = album,
                isPlaying = isPlaying,
                durationMs = duration
            )
            updateFromBluetooth(
                title = title,
                artist = artist,
                album = album,
                isPlaying = isPlaying,
                durationMs = duration,
                albumArt = albumArt
            )
        }
    }

    private fun handleUniversalBluetoothPlaybackState(state: PlaybackState?) {
        state ?: return
        val isPlaying = state.state == PlaybackState.STATE_PLAYING
        val pos = state.position

        Log.i(TAG, "handleUniversalBluetoothPlaybackState: isPlaying=$isPlaying, pos=$pos")

        if (isPlaying) {
            if (FytMusicService.isOemPlaying || _currentSource.value == AudioSource.LOCAL) {
                Log.i(TAG, "Bluetooth started playback -> auto-pausing local music")
                instance?.let { FytMusicService.pauseMusic(it) }
            }
            _currentSource.value = AudioSource.BLUETOOTH
        }

        val meta = bluetoothController?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: _mediaState.value.title
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: _mediaState.value.artist
        val album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
            ?: _mediaState.value.album
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: _mediaState.value.durationMs

        com.x.carlauncher.bluetooth.BluetoothStateReceiver.updateBtTrack(
            title = title,
            artist = artist,
            album = album,
            isPlaying = isPlaying,
            durationMs = duration,
            positionMs = pos
        )

        if (_currentSource.value == AudioSource.BLUETOOTH) {
            _mediaState.value = _mediaState.value.copy(
                isPlaying = isPlaying,
                progressMs = if (pos >= 0) pos else _mediaState.value.progressMs,
                lyricsLine = getCurrentLyricLine(parsedLyrics, pos)
            )
        }
    }

    private fun handleUniversalMetadata(metadata: MediaMetadata?, pkg: String?) {
        metadata ?: return
        // Solo se ignora la sesion externa si la musica local esta SONANDO
        // de verdad: si esta en pausa, la tarjeta debe mostrar la cancion
        // de YouTube Music / Spotify (aunque este en pausa) para que los
        // botones la controlen a ella y no a la musica local. Antes se
        // ignoraba siempre que la fuente era LOCAL, y la tarjeta se quedaba
        // mostrando la musica local aunque YouTube Music estuviera activo.
        if (_currentSource.value == AudioSource.LOCAL && XLocalPlayer.isPlaying) {
            val isExtPlaying = activeController?.playbackState?.state == PlaybackState.STATE_PLAYING
            if (!isExtPlaying) {
                // La musica local esta sonando: no dejar que una sesion
                // externa en pausa sobrescriba lo que se muestra.
                return
            }
        }
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR)
            ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

        var albumArt: Bitmap? = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        // Check for embedded lyrics in metadata
        val embeddedLyrics = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
            ?: metadata.getString("android.media.metadata.LYRICS")
            ?: ""

        val friendlyPlayerName = when {
            pkg?.contains("spotify", ignoreCase = true) == true -> "Spotify"
            pkg?.contains("youtube", ignoreCase = true) == true || pkg?.contains("ytmusic", ignoreCase = true) == true -> "YouTube Music"
            pkg?.contains("apple.android.music", ignoreCase = true) == true -> "Apple Music"
            pkg?.contains("maxmpz", ignoreCase = true) == true -> "Poweramp"
            pkg?.contains("deezer", ignoreCase = true) == true -> "Deezer"
            pkg?.contains("tidal", ignoreCase = true) == true -> "Tidal"
            pkg?.contains("amazon.mp3", ignoreCase = true) == true -> "Amazon Music"
            pkg?.contains("soundcloud", ignoreCase = true) == true -> "SoundCloud"
            pkg?.contains("vlc", ignoreCase = true) == true -> "VLC"
            pkg?.contains("bluetooth", ignoreCase = true) == true -> "Bluetooth Audio"
            else -> {
                val appLabel = try {
                    pkg?.let { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }
                } catch (_: Exception) { null }
                appLabel?.ifBlank { null } ?: pkg?.substringAfterLast('.') ?: "Reproductor"
            }
        }

        // If albumArt is null, check for art URI (common in Spotify and YouTube Music)
        if (albumArt == null) {
            val artUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            if (!artUri.isNullOrBlank()) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val bitmap = if (artUri.startsWith("http://") || artUri.startsWith("https://")) {
                            val req = Request.Builder().url(artUri).build()
                            LyricsRepository.okHttpClient.newCall(req).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    resp.body()?.byteStream()?.use { stream ->
                                        BitmapFactory.decodeStream(stream)
                                    }
                                } else null
                            }
                        } else {
                            val uri = Uri.parse(artUri)
                            contentResolver.openInputStream(uri)?.use { stream ->
                                BitmapFactory.decodeStream(stream)
                            }
                        }
                        if (bitmap != null) {
                            withContext(Dispatchers.Main) {
                                if (_mediaState.value.title == title) {
                                    _mediaState.value = _mediaState.value.copy(albumArt = bitmap)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not load album art from URI: $artUri", e)
                    }
                }
            }
        }

        // Check if track has changed to reset lyrics cleanly
        val current = _mediaState.value
        val isNewTrack = current.title != title || current.artist != artist
        if (isNewTrack) {
            parsedLyrics = emptyList()
        }

        _mediaState.value = current.copy(
            title = title.ifEmpty { current.title },
            artist = artist.ifEmpty { current.artist },
            album = album.ifEmpty { current.album },
            albumArt = albumArt ?: current.albumArt,
            durationMs = if (duration > 0) duration else current.durationMs,
            playerName = friendlyPlayerName,
            rawLyricsText = embeddedLyrics,
            fullLyrics = if (isNewTrack) emptyList() else current.fullLyrics,
            hasLyrics = if (isNewTrack) false else current.hasLyrics,
            lyricsLine = if (isNewTrack) "" else current.lyricsLine
        )

        // If embedded lyrics exist, parse them
        if (embeddedLyrics.isNotBlank() && (embeddedLyrics.contains("[0") || embeddedLyrics.contains("[1"))) {
            parseLrcStream(embeddedLyrics.byteInputStream())
        } else if (title.isNotBlank()) {
            autoDetectLyrics(artist, title)
        }

        if (pkg?.contains("bluetooth", ignoreCase = true) == true) {
            com.x.carlauncher.bluetooth.BluetoothStateReceiver.updateBtTrack(
                title = title,
                artist = artist,
                album = album,
                isPlaying = _mediaState.value.isPlaying,
                durationMs = duration
            )
        }
    }

    private fun handleUniversalPlaybackState(state: PlaybackState?) {
        state ?: return
        val isPlaying = state.state == PlaybackState.STATE_PLAYING
        val positionMs = state.position
        val updateTime = state.lastPositionUpdateTime
        val speed = state.playbackSpeed.coerceAtLeast(0.1f)

        // Calculate accurate real-time position with elapsedRealtime
        val calculatedPos = if (isPlaying && updateTime > 0L) {
            val delta = SystemClock.elapsedRealtime() - updateTime
            (positionMs + (delta * speed).toLong()).coerceAtLeast(0L)
        } else {
            positionMs.coerceAtLeast(0L)
        }

        val pkg = activeController?.packageName
        val isExternalApp = pkg != null && !pkg.contains("bluetooth", ignoreCase = true) && pkg != "com.syu.music" && !pkg.startsWith("com.x.carlauncher")

        if (isPlaying && isExternalApp) {
            // Online streaming player (Spotify, YouTube Music, etc.) started playing!
            if (XLocalPlayer.isPlaying) {
                Log.i(TAG, "External streaming player ($pkg) is playing -> auto-pausing local player")
                XLocalPlayer.pause()
            }
            _currentSource.value = AudioSource.ONLINE
        }

        val current = _mediaState.value
        _mediaState.value = current.copy(
            isPlaying = isPlaying,
            progressMs = if (calculatedPos >= 0) calculatedPos else current.progressMs,
            lyricsLine = getCurrentLyricLine(parsedLyrics, calculatedPos)
        )

        if (activeController?.packageName?.contains("bluetooth", ignoreCase = true) == true) {
            com.x.carlauncher.bluetooth.BluetoothStateReceiver.updateBtTrack(
                title = _mediaState.value.title,
                artist = _mediaState.value.artist,
                album = _mediaState.value.album,
                isPlaying = isPlaying,
                durationMs = _mediaState.value.durationMs,
                positionMs = calculatedPos
            )
            if (isPlaying) {
                if (FytMusicService.isOemPlaying || _currentSource.value == AudioSource.LOCAL) {
                    Log.i(TAG, "Bluetooth active session is playing -> pausing local music")
                    instance?.let { FytMusicService.pauseMusic(it) }
                }
                _currentSource.value = AudioSource.BLUETOOTH
            }
        }
    }

    // ── Continuous Progress Ticker (250ms high-frequency precision for real-time lyrics) ──

    private fun startProgressTicker() {
        progressTickerJob?.cancel()
        mainHandler.post(object : Runnable {
            override fun run() {
                val current = _mediaState.value
                val source = _currentSource.value

                if (current.isPlaying && source == AudioSource.ONLINE && activeController != null) {
                    val state = activeController?.playbackState
                    if (state != null && state.state == PlaybackState.STATE_PLAYING) {
                        val timeDelta = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
                        val currentPos = (state.position + (timeDelta * state.playbackSpeed).toLong())
                            .coerceIn(0L, if (current.durationMs > 0) current.durationMs else Long.MAX_VALUE)

                        val lyricLine = getCurrentLyricLine(parsedLyrics, currentPos)
                        _mediaState.value = current.copy(
                            progressMs = currentPos,
                            lyricsLine = if (lyricLine.isNotEmpty()) lyricLine else current.lyricsLine
                        )
                    }
                } else if (current.isPlaying && source == AudioSource.BLUETOOTH) {
                    val nextPos = (current.progressMs + 250L).coerceAtMost(if (current.durationMs > 0) current.durationMs else Long.MAX_VALUE)
                    val lyricLine = getCurrentLyricLine(parsedLyrics, nextPos)
                    _mediaState.value = current.copy(
                        progressMs = nextPos,
                        lyricsLine = if (lyricLine.isNotEmpty()) lyricLine else current.lyricsLine
                    )
                }

                mainHandler.postDelayed(this, 250L)
            }
        })
    }

    // ── Universal Notification Interceptor (Navigation & Bluetooth) ──────────

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            activeNotifications?.forEach { sbn ->
                val p = sbn?.packageName ?: return@forEach
                if (p == "com.whatsapp" || p == "com.whatsapp.w4b") {
                    com.x.carlauncher.whatsapp.WhatsAppMessageManager.handleNotification(sbn)
                }
            }
        } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        val extras = sbn.notification?.extras ?: return

        // 0. WhatsApp Messaging Interceptor
        if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
            com.x.carlauncher.whatsapp.WhatsAppMessageManager.handleNotification(sbn)
        }

        // 1. Navigation Turn-by-Turn Guidance Interceptor (Waze & Google Maps)
        if (pkg == "com.waze" || pkg == "com.google.android.apps.maps") {
            val providerName = if (pkg == "com.waze") "Waze" else "Google Maps"
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
            val subText = extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

            if (title.isNotBlank() || text.isNotBlank()) {
                val distance = if (title.matches(Regex(""".*\d+\s*(m|km|ft|mi).*""", RegexOption.IGNORE_CASE))) title else ""
                val street = if (distance.isNotEmpty()) text else title
                val eta = subText.ifBlank { if (text.contains(":") || text.contains("min")) text else "" }

                com.x.carlauncher.navigation.NavigationManager.updateGuidance(
                    provider = providerName,
                    instruction = if (distance.isNotEmpty()) "$title $text" else text.ifBlank { title },
                    distance = distance,
                    street = street,
                    eta = eta
                )
            }
        }

        // 2. Bluetooth Media session / AVRCP metadata interceptor
        if (pkg.contains("bluetooth", ignoreCase = true)) {
            val title = extras.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
            val artist = extras.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
            val album = extras.getString(MediaMetadata.METADATA_KEY_ALBUM)
                ?: extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            if (title.isNotBlank()) {
                com.x.carlauncher.bluetooth.BluetoothStateReceiver.updateBtTrack(
                    title = title,
                    artist = artist,
                    album = album,
                    isPlaying = true
                )
            }
        }

        // 3. Media session update
        val isMedia = extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) ||
                      extras.containsKey(MediaMetadata.METADATA_KEY_TITLE) ||
                      extras.containsKey("android.mediaMetadata")
        if (isMedia) {
            refreshActiveSessions()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
            com.x.carlauncher.whatsapp.WhatsAppMessageManager.handleNotificationRemoved(sbn)
        }
        if (pkg == "com.waze" || pkg == "com.google.android.apps.maps") {
            val providerName = if (pkg == "com.waze") "Waze" else "Google Maps"
            com.x.carlauncher.navigation.NavigationManager.clearGuidance(providerName)
        }
        if (pkg == activeController?.packageName) {
            refreshActiveSessions()
        }
    }
}

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * Universal ViewModel for all media player operations in XCarOS.
 */
class PowerampMediaViewModel(application: Application) : AndroidViewModel(application) {

    val mediaState: StateFlow<MediaState> = PowerampNotificationListener.mediaState

    fun togglePlayPause(context: Context) {
        PowerampNotificationListener.togglePlayPause(context)
    }

    fun skipToNext(context: Context) {
        PowerampNotificationListener.skipToNext(context)
    }

    fun skipToPrevious(context: Context) {
        PowerampNotificationListener.skipToPrevious(context)
    }

    fun seekTo(context: Context, positionMs: Long) {
        PowerampNotificationListener.seekTo(context, positionMs)
    }

    fun toggleShuffle(context: Context) {
        PowerampNotificationListener.toggleShuffle(context)
    }

    fun toggleRepeat(context: Context) {
        PowerampNotificationListener.toggleRepeat(context)
    }

    fun cyclePlaybackMode(context: Context) {
        PowerampNotificationListener.cyclePlaybackMode(context)
    }

    fun loadLrcFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    PowerampNotificationListener.parseLrcStream(stream)
                }
            } catch (e: Exception) {
                Log.e("PowerampMediaViewModel", "Failed to load LRC from URI", e)
            }
        }
    }
}
