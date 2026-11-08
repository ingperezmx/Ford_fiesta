package com.x.carlauncher.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.x.carlauncher.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * OEM SYU / FYT Car Music Service.
 * Intercepts "com.fyt.launcher.music" intents directly dispatched by the factory
 * head unit music player (com.syu.music) whenever a song is played, paused, or changed.
 *
 * Extracts track metadata, album art from embedded FLAC/MP3 tags or directory covers,
 * and loads synchronized LRC lyrics automatically.
 */
class FytMusicService : Service() {

    companion object {
        private const val TAG = "FytMusicService"
        private const val CHANNEL_ID = "x_fyt_music_channel"
        private const val CHANNEL_NAME = "XCarOS Music Sync"
        private const val NOTIF_ID = 2002

        // Broadcast actions accepted by com.syu.music
        const val ACTION_FYT_LAUNCHER_MUSIC = "com.fyt.launcher.music"
        const val ACTION_SYU_MUSIC_PLAYPAUSE = "com.syu.music.playpause"
        const val ACTION_SYU_MUSIC_NEXT = "com.syu.music.next"
        const val ACTION_SYU_MUSIC_PREV = "com.syu.music.prev"
        const val ACTION_SYU_MUSIC_STOP = "com.syu.music.stop"
        const val ACTION_SYU_WIDGET_START = "syu.widget.start"
        const val ACTION_SYU_WIDGET_UPDATE = "syu.widget.update"

        const val FACTORY_MUSIC_PKG = "com.syu.music"
        const val FACTORY_MUSIC_ACT = "com.syu.music.MAct"

        private var lastProcessedPath: String = ""
        private var cachedTrackKey: String = ""
        private var cachedAlbumArt: Bitmap? = null
        private var cachedLyrics: List<LyricEntry> = emptyList()

        @Volatile
        var isOemPlaying: Boolean = false

        /**
         * Dispatches Play/Pause toggle directly to factory music player MService or Bluetooth.
         * Distinguishes based on current playback state to ensure clean, reliable transitions:
         * - If currently playing -> sends "com.syu.music.stop" to pause cleanly.
         * - If currently paused -> sends "syu.widget.playpause" to resume cleanly.
         */
        fun togglePlayPause(context: Context) {
            val isBtConnected = com.x.carlauncher.bluetooth.BluetoothViewModel.isDeviceConnected
            if (PowerampNotificationListener.currentSource.value == AudioSource.BLUETOOTH && isBtConnected) {
                if (isOemPlaying) {
                    pauseMusic(context)
                }
                sendBtPlayPause(context)
                return
            }

            val isCurrentlyPlaying = isOemPlaying || PowerampNotificationListener.mediaState.value.isPlaying
            Log.i(TAG, "togglePlayPause invoked (current isPlaying=$isCurrentlyPlaying, isOemPlaying=$isOemPlaying, isBtConnected=$isBtConnected)")

            if (isCurrentlyPlaying) {
                pauseMusic(context)
            } else {
                resumeMusic(context)
            }
        }

        fun resumeMusic(context: Context) {
            Log.i(TAG, "Resuming music playback via syu.widget.playpause")
            // If Bluetooth is active, pause Bluetooth so both aren't playing simultaneously
            if (PowerampNotificationListener.currentSource.value == AudioSource.BLUETOOTH ||
                com.x.carlauncher.bluetooth.BluetoothStateReceiver.btTrackFlow.value.isPlaying) {
                Log.i(TAG, "Local resume requested while Bluetooth active -> sending BT pause")
                sendBtPlayPause(context)
            }
            PowerampNotificationListener.setSource(AudioSource.LOCAL)
            PowerampNotificationListener.setOptimisticPlaying(true)
            isOemPlaying = true
            try {
                val intent = Intent("syu.widget.playpause").apply {
                    component = ComponentName(FACTORY_MUSIC_PKG, "com.syu.music.MService")
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start syu.widget.playpause", e)
            }

            requestStateUpdate(context)
        }

        fun pauseMusic(context: Context) {
            Log.i(TAG, "Pausing music playback via com.syu.music.stop")
            PowerampNotificationListener.setOptimisticPlaying(false)
            isOemPlaying = false
            try {
                val intent = Intent("com.syu.music.stop").apply {
                    component = ComponentName(FACTORY_MUSIC_PKG, "com.syu.music.MService")
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start com.syu.music.stop", e)
            }

            requestStateUpdate(context)
        }

        /**
         * Skips to the next track on the factory music player or Bluetooth.
         */
        fun skipToNext(context: Context) {
            val isBtConnected = com.x.carlauncher.bluetooth.BluetoothViewModel.isDeviceConnected
            if (PowerampNotificationListener.currentSource.value == AudioSource.BLUETOOTH && isBtConnected) {
                if (isOemPlaying) {
                    pauseMusic(context)
                }
                sendBtNext(context)
                return
            }

            Log.i(TAG, "Skipping to next track via syu.widget.next")
            try {
                val intent = Intent("syu.widget.next").apply {
                    component = ComponentName(FACTORY_MUSIC_PKG, "com.syu.music.MService")
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot startService syu.widget.next", e)
            }

            requestStateUpdate(context)
        }

        /**
         * Dispatches Play/Pause toggle to the factory Bluetooth audio service (com.syu.bt).
         */
        fun sendBtPlayPause(context: Context) {
            Log.i(TAG, "Dispatching Bluetooth Play/Pause via com.syu.bt.byav.widgetPlayPause")
            try {
                val intent = Intent("com.syu.bt.byav.widgetPlayPause").apply {
                    component = ComponentName("com.syu.bt", "com.syu.broadcast.BtavPlayPauseService")
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start BtavPlayPauseService", e)
            }
        }

        /**
         * Dispatches Next track to the factory Bluetooth audio service (com.syu.bt).
         */
        fun sendBtNext(context: Context) {
            Log.i(TAG, "Dispatching Bluetooth Next via com.syu.bt.byav.widgetNext")
            try {
                val intent = Intent("com.syu.bt.byav.widgetNext").apply {
                    component = ComponentName("com.syu.bt", "com.syu.broadcast.BtavNextService")
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start BtavNextService", e)
            }
        }

        /**
         * Dispatches Previous track to the factory Bluetooth audio service (com.syu.bt).
         */
        fun sendBtPrev(context: Context) {
            Log.i(TAG, "Dispatching Bluetooth Prev via com.syu.bt.byav.widgetPrev")
            try {
                val intent = Intent("com.syu.bt.byav.widgetPrev").apply {
                    component = ComponentName("com.syu.bt", "com.syu.broadcast.BtavPrevService")
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start BtavPrevService", e)
            }
        }

        /**
         * Skips to the previous track on the factory music player or Bluetooth.
         */
        fun skipToPrevious(context: Context) {
            val isBtConnected = com.x.carlauncher.bluetooth.BluetoothViewModel.isDeviceConnected
            if (PowerampNotificationListener.currentSource.value == AudioSource.BLUETOOTH && isBtConnected) {
                if (isOemPlaying) {
                    pauseMusic(context)
                }
                sendBtPrev(context)
                return
            }

            Log.i(TAG, "Skipping to previous track via syu.widget.prev")
            try {
                val intent = Intent("syu.widget.prev").apply {
                    component = ComponentName(FACTORY_MUSIC_PKG, "com.syu.music.MService")
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot startService syu.widget.prev", e)
            }

            requestStateUpdate(context)
        }

        /**
         * Opens the full factory music player UI activity.
         */
        fun launchFactoryMusic(context: Context) {
            com.x.carlauncher.services.StartupGuardian.cancel()
            try {
                val intent = Intent().apply {
                    component = ComponentName(FACTORY_MUSIC_PKG, FACTORY_MUSIC_ACT)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot launch com.syu.music.MAct directly, trying package intent", e)
                val launch = context.packageManager.getLaunchIntentForPackage(FACTORY_MUSIC_PKG)
                if (launch != null) {
                    launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(launch)
                }
            }
        }

        /**
         * Prompts com.syu.music to send current track information immediately.
         * NOTE: We deliberately do NOT send ACTION_SYU_WIDGET_START ("syu.widget.start")
         * here because MService interprets syu.widget.start as a command to bring
         * com.syu.music.MAct to the foreground, which would steal focus from the launcher!
         * Only ACTION_SYU_WIDGET_UPDATE is safe for background queries.
         */
        fun requestStateUpdate(context: Context) {
            Log.d(TAG, "Requesting SYU music state update (passive background query)")
            try {
                val intent = Intent(ACTION_SYU_WIDGET_UPDATE).apply {
                    component = ComponentName(FACTORY_MUSIC_PKG, "com.syu.music.MService")
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot startService syu.widget.update", e)
            }
            try {
                context.sendBroadcast(Intent(ACTION_SYU_WIDGET_UPDATE))
            } catch (_: Exception) {}
        }

        /**
         * Starts music playback in the background without opening the factory music player UI.
         */
        fun startBackgroundMusic(context: Context) {
            Log.i(TAG, "Starting factory music playback in background without UI")
            try {
                val intent = Intent("syu.widget.playpause").apply {
                    component = ComponentName(FACTORY_MUSIC_PKG, "com.syu.music.MService")
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start syu.widget.playpause in background", e)
            }
            try {
                PowerampNotificationListener.dispatchHardwareMediaKey(context, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
            } catch (_: Exception) {}

            // Request state update immediately
            requestStateUpdate(context)

            // Auto-fallback: if player was completely idle without a song selected, send next after 1.5s
            CoroutineScope(Dispatchers.IO).launch {
                kotlinx.coroutines.delay(1500L)
                if (!PowerampNotificationListener.mediaState.value.isPlaying) {
                    Log.d(TAG, "Still not playing after playpause, sending syu.widget.next to start playlist")
                    try {
                        val nextIntent = Intent("syu.widget.next").apply {
                            component = ComponentName(FACTORY_MUSIC_PKG, "com.syu.music.MService")
                        }
                        context.startService(nextIntent)
                    } catch (_: Exception) {}
                    requestStateUpdate(context)
                }
            }
        }

        /**
         * Dispatches Seek command.
         * NOTE: FYT OEM music player (com.syu.music) does not expose an IPC seek command.
         * Crucially, sending "syu.widget.ToTime" must NEVER be done because in FYT firmware
         * it explicitly maps to opening "android.settings.DATE_SETTINGS" ("To Time settings")!
         */
        fun seekTo(context: Context, targetMs: Long) {
            Log.d(TAG, "FYT seek requested: $targetMs ms (OEM player has no IPC seek; handled via MediaSession)")
        }

        private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun processBroadcastIntent(context: Context, intent: Intent) {
            val action = intent.action ?: return
            if (action == ACTION_FYT_LAUNCHER_MUSIC || action == "com.syu.music") {
                val extras = intent.extras ?: return
                val title = extras.getString("title") ?: ""
                val artist = extras.getString("play_artist") ?: ""
                val album = extras.getString("play_album") ?: ""
                val playPath = extras.getString("play_path") ?: ""
                val isPlaying = extras.getBoolean("play_state", false)
                isOemPlaying = isPlaying
                val curTime = extras.getLong("play_cur", 0L)
                val totalTime = extras.getLong("play_total", 0L)

                Log.d(TAG, "OEM Music Update: '$title' by '$artist' [$playPath] (playing=$isPlaying, $curTime/$totalTime)")

                serviceScope.launch {
                    processTrackUpdate(context.applicationContext, title, artist, album, playPath, isPlaying, curTime, totalTime)
                }
            }
        }

        private suspend fun processTrackUpdate(
            appContext: Context,
            rawTitle: String,
            rawArtist: String,
            album: String,
            playPath: String,
            isPlaying: Boolean,
            curTime: Long,
            totalTime: Long
        ) {
            val (cleanTitle, cleanArtist) = sanitizeTrackInfo(rawTitle, rawArtist, playPath)
            val trackKey = "${cleanTitle}_$cleanArtist"

            // If track changed, resolve lyrics immediately (0ms if preloaded) & trigger preloader
            if ((playPath != lastProcessedPath || trackKey != cachedTrackKey) && playPath.isNotBlank()) {
                lastProcessedPath = playPath
                cachedTrackKey = trackKey
                cachedAlbumArt = extractAlbumArt(playPath)

                var lyrics = loadLyricsForTrack(playPath, cleanTitle, cleanArtist)
                if (lyrics.isEmpty()) {
                    lyrics = LyricsRepository.getOrFetchLyrics(appContext, cleanTitle, cleanArtist, playPath)
                }
                cachedLyrics = lyrics

                // Preload upcoming sequential and shuffle/random playlist songs
                LyricsRepository.preloadFolder(appContext, playPath)
            }

        // Calculate synchronized lyric line strictly for the currently active track
        val currentLyric = if (cachedLyrics.isNotEmpty() && trackKey == cachedTrackKey) {
            val idx = cachedLyrics.indexOfLast { it.timeMs <= curTime }
            if (idx >= 0) cachedLyrics[idx].text else ""
        } else ""

        // Push directly to PowerampMediaController media state flow
        withContext(Dispatchers.Main) {
            PowerampNotificationListener.updateFromFyt(
                title = cleanTitle,
                artist = cleanArtist,
                album = album,
                albumArt = cachedAlbumArt,
                isPlaying = isPlaying,
                progressMs = curTime,
                durationMs = if (totalTime > 0) totalTime else 1L,
                lyricsLine = currentLyric,
                fullLyrics = if (trackKey == cachedTrackKey) cachedLyrics else emptyList(),
                playerName = ""
            )
        }
    }

    private fun sanitizeTrackInfo(rawTitle: String, rawArtist: String, playPath: String): Pair<String, String> {
        var title = rawTitle.trim()
        var artist = rawArtist.trim()

        val isCorruptedTitle = title.contains("\uFFFD")
        val isCorruptedArtist = artist.contains("\uFFFD")
        val isUnknownTitle = title.isBlank() || isCorruptedTitle || title.equals("Desconocido", ignoreCase = true) || title.equals("Unknown", ignoreCase = true)
        val isUnknownArtist = artist.isBlank() || isCorruptedArtist || artist.equals("Desconocido", ignoreCase = true) || artist.equals("Unknown", ignoreCase = true)

        if ((isUnknownTitle || isUnknownArtist) && playPath.isNotBlank()) {
            try {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(playPath)
                val mmrTitle = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val mmrArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                mmr.release()

                if ((isUnknownTitle || isCorruptedTitle) && !mmrTitle.isNullOrBlank() && !mmrTitle.contains("\uFFFD")) {
                    title = mmrTitle.trim()
                }
                if ((isUnknownArtist || isCorruptedArtist) && !mmrArtist.isNullOrBlank() && !mmrArtist.contains("\uFFFD")) {
                    artist = mmrArtist.trim()
                }
            } catch (_: Exception) {}
        }

        // If still unknown or corrupted or raw "Desconocido", smart parse from filename (e.g. "Song Name - Artist Name.flac")
        if (playPath.isNotBlank()) {
            val file = File(playPath)
            val name = file.nameWithoutExtension
            if (name.isNotBlank()) {
                if (title.isBlank() || title.contains("\uFFFD") || title.equals("Desconocido", ignoreCase = true)) {
                    if (name.contains(" - ")) {
                        val parts = name.split(" - ")
                        if (parts.size >= 2) {
                            title = parts[0].trim()
                            if (artist.isBlank() || artist.contains("\uFFFD") || artist.equals("Desconocido", ignoreCase = true)) {
                                artist = parts[1].trim()
                            }
                        } else {
                            title = name
                        }
                    } else {
                        title = name
                    }
                }
            }
        }

        if (title.isBlank() || title.equals("Desconocido", ignoreCase = true)) {
            title = "Pista de audio"
        }
        if (artist.isBlank() || artist.equals("Desconocido", ignoreCase = true)) {
            artist = "Musica local / USB"
        }

        return Pair(title, artist)
    }

    private fun extractAlbumArt(filePath: String): Bitmap? {
        val file = File(filePath)
        if (!file.exists()) return null

        // 1. Try embedded picture via MediaMetadataRetriever (FLAC / MP3 / M4A)
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(filePath)
            val picture = mmr.embeddedPicture
            mmr.release()
            if (picture != null && picture.isNotEmpty()) {
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(picture, 0, picture.size, boundsOpts)
                var sampleSize = 1
                while (boundsOpts.outWidth / (sampleSize * 2) >= 384 && boundsOpts.outHeight / (sampleSize * 2) >= 384) {
                    sampleSize *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bmp = BitmapFactory.decodeByteArray(picture, 0, picture.size, decodeOpts)
                if (bmp != null) {
                    Log.d(TAG, "Extracted optimized album art (${bmp.width}x${bmp.height}, sample=$sampleSize) from $filePath")
                    return bmp
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaMetadataRetriever failed on $filePath", e)
        }

        // 2. Check directory for cover.jpg, folder.jpg, album.jpg, etc.
        try {
            val dir = file.parentFile
            if (dir != null && dir.isDirectory) {
                val candidateNames = listOf("cover", "folder", "album", "front", "art")
                val files = dir.listFiles()
                if (files != null) {
                    // Look for exact named cover
                    val matching = files.firstOrNull { f ->
                        val lower = f.name.lowercase()
                        (lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".jpeg")) &&
                                candidateNames.any { lower.contains(it) }
                    } ?: files.firstOrNull { f ->
                        val lower = f.name.lowercase()
                        lower.endsWith(".jpg") || lower.endsWith(".png")
                    }

                    if (matching != null) {
                        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(matching.absolutePath, boundsOpts)
                        var sampleSize = 1
                        while (boundsOpts.outWidth / (sampleSize * 2) >= 384 && boundsOpts.outHeight / (sampleSize * 2) >= 384) {
                            sampleSize *= 2
                        }
                        val decodeOpts = BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        val bmp = BitmapFactory.decodeFile(matching.absolutePath, decodeOpts)
                        if (bmp != null) {
                            Log.d(TAG, "Found optimized folder cover image: ${matching.name} (${bmp.width}x${bmp.height})")
                            return bmp
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error looking for folder cover", e)
        }

        return null
    }

    private fun loadLyricsForTrack(filePath: String, title: String, artist: String): List<LyricEntry> {
        val file = File(filePath)
        if (!file.exists()) return emptyList()

        // 1. Check for .lrc file alongside the track (e.g. song.flac -> song.lrc)
        try {
            val lrcFile = File(filePath.substringBeforeLast(".") + ".lrc")
            if (lrcFile.exists() && lrcFile.canRead()) {
                Log.d(TAG, "Found matching LRC file: ${lrcFile.name}")
                FileInputStream(lrcFile).use { fis ->
                    val parsed = PowerampNotificationListener.parseLrcStream(fis)
                    if (parsed.isNotEmpty()) return parsed
                }
            }

            // Also check directory for any .lrc file matching song title
            val dir = file.parentFile
            if (dir != null && dir.isDirectory) {
                val cleanTitle = title.trim().lowercase()
                val matchedLrc = dir.listFiles { f ->
                    f.name.endsWith(".lrc", ignoreCase = true) &&
                            (cleanTitle.isNotBlank() && f.name.lowercase().contains(cleanTitle))
                }?.firstOrNull()

                if (matchedLrc != null && matchedLrc.canRead()) {
                    Log.d(TAG, "Found title-matched LRC file: ${matchedLrc.name}")
                    FileInputStream(matchedLrc).use { fis ->
                        val parsed = PowerampNotificationListener.parseLrcStream(fis)
                        if (parsed.isNotEmpty()) return parsed
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading .lrc file", e)
        }

        return emptyList()
    }
    }

    private val fytReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context != null && intent != null) {
                processBroadcastIntent(context, intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            startForeground(NOTIF_ID, buildNotification("Sincronizando musica"))
            Log.d(TAG, "startForeground initialized successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot startForeground in onCreate", e)
        }
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(ACTION_FYT_LAUNCHER_MUSIC)
                addAction("com.syu.music")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(fytReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(fytReceiver, filter)
            }
            Log.d(TAG, "Dynamic broadcast receiver for com.fyt.launcher.music registered successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot register dynamic fytReceiver", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(fytReceiver)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIF_ID, buildNotification("Sincronizando musica"))
        } catch (e: Exception) {
            Log.w(TAG, "Cannot startForeground in onStartCommand", e)
        }

        if (intent != null) {
            processBroadcastIntent(this, intent)
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "XCarOS FYT Music Sync"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XCarOS Music")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}

/**
 * Static broadcast receiver to capture FYT music, SWC, and media button broadcasts.
 */
class FytMusicReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        Log.i("FytMusicReceiver", "Received broadcast action: $action")
        when (action) {
            "syu.widget.next", "com.syu.music.next" -> {
                XLocalPlayer.handleNextCommand(context)
            }
            "syu.widget.last", "syu.widget.prev", "com.syu.music.prev" -> {
                XLocalPlayer.handlePrevCommand(context)
            }
            "syu.widget.playpause", "com.syu.music.playpause" -> {
                XLocalPlayer.handlePlayPauseCommand(context)
            }
            Intent.ACTION_MEDIA_BUTTON -> {
                val event = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                if (event != null && event.action == android.view.KeyEvent.ACTION_DOWN) {
                    XLocalPlayer.handleMediaKeyCode(context, event.keyCode)
                }
            }
            else -> {
                FytMusicService.processBroadcastIntent(context, intent)
            }
        }
    }
}

