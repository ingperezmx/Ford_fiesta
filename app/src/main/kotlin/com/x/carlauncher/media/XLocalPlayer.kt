package com.x.carlauncher.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Data model for a local audio track on internal storage or USB.
 */
data class LocalTrack(
    val path: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L
)

/**
 * Playback modes for local media:
 * - SEQUENCE: Sequential continuous loop of entire library (1 -> N -> 1).
 * - REPEAT_ONE: Continuously loops the currently playing track.
 * - SHUFFLE: True random shuffle across all tracks without repetition until exhausted.
 */
enum class PlaybackMode {
    SEQUENCE,
    REPEAT_ONE,
    SHUFFLE
}

/**
 * High-performance, integrated native audio player engine for XCarOS.
 * Replaces the closed, proprietary FYT OEM player (`com.syu.music`) with a
 * modern, 100% controllable engine that supports instantaneous seeking,
 * multi-USB scanning & hot-plugging, gapless navigation, and synchronized lyrics.
 */
object XLocalPlayer {

    private const val TAG = "XLocalPlayer"
    private const val PREFS_NAME = "x_local_player_prefs"
    private const val KEY_LAST_PATH = "last_track_path"
    private const val KEY_LAST_INDEX = "last_track_index"
    private const val KEY_LAST_POS = "last_track_pos"
    private const val KEY_PLAYBACK_MODE = "playback_mode"

    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null
    private var scanJob: Job? = null

    private var mediaPlayer: MediaPlayer? = null
    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasAudioFocus: Boolean = false

    private val _playlist = mutableListOf<LocalTrack>()
    val playlist: List<LocalTrack> get() = _playlist

    private val _playlistFlow = MutableStateFlow<List<LocalTrack>>(emptyList())
    val playlistFlow: StateFlow<List<LocalTrack>> = _playlistFlow.asStateFlow()

    // ── Contextual Playback Queue (Scoped to Folder, Artist, Favorites, or Full Library) ──
    private val _activeQueue = mutableListOf<LocalTrack>()
    val activeQueue: List<LocalTrack> get() = _activeQueue

    private val _queueTitleFlow = MutableStateFlow("Biblioteca")
    val queueTitleFlow: StateFlow<String> = _queueTitleFlow.asStateFlow()

    private var currentQueueIndex: Int = 0

    // ── Ultra-fast Duration Cache ─────────────────────────────────────────────
    private val durationDiskCache = ConcurrentHashMap<String, Long>()
    private var durationCacheLoaded = false
    private const val DURATION_CACHE_FILE = "durations_cache.json"

    private val shuffledIndices = mutableListOf<Int>()
    private var shufflePointer: Int = 0

    var currentIndex: Int = 0
        private set

    val isPlaying: Boolean get() = mediaPlayer?.isPlaying == true

    var isPrepared: Boolean = false
        private set

    @Volatile
    private var autoPlayPending: Boolean = false

    var currentTrack: LocalTrack? = null
        private set

    private val _playbackMode = MutableStateFlow(PlaybackMode.SEQUENCE)
    val playbackMode: StateFlow<PlaybackMode> = _playbackMode.asStateFlow()

    private var cachedAlbumArt: Bitmap? = null
    private var cachedLyrics: List<LyricEntry> = emptyList()

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    private var usbReceiver: BroadcastReceiver? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "Audio focus permanently lost ($focusChange) -> external app (SmartTube, YouTube, etc.) requested playback. Pausing local player.")
                hasAudioFocus = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "Audio focus lost transiently ($focusChange). Pausing local player.")
                hasAudioFocus = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio focus ducking ($focusChange)")
                mediaPlayer?.setVolume(0.15f, 0.15f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "Audio focus regained ($focusChange)")
                hasAudioFocus = true
                mediaPlayer?.setVolume(1.0f, 1.0f)
            }
        }
    }

    /**
     * Initializes the native player engine, restores persistent state, registers USB receivers,
     * and scans all connected storage drives.
     */
    fun initialize(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // Restore saved playback mode
        val savedModeName = prefs?.getString(KEY_PLAYBACK_MODE, PlaybackMode.SEQUENCE.name)
        val restoredMode = try {
            PlaybackMode.valueOf(savedModeName ?: PlaybackMode.SEQUENCE.name)
        } catch (_: Exception) {
            PlaybackMode.SEQUENCE
        }
        _playbackMode.value = restoredMode

        // Ensure OEM music player does not conflict
        stopOemPlayer(app)

        // Initialize Android MediaSession for steering wheel controls and OS media buttons
        initMediaSession(app)

        // Register USB mount/unmount and attachment listeners
        registerUsbReceivers(app)

        // Rescan storage asynchronously
        rescanLibrary(autoStart = false)
    }

    /**
     * Called whenever vehicle ignition is engaged (cold boot or ACC ON wake).
     * Automatically begins playback of the last remembered track immediately.
     * Retries after 2.0s to accommodate late-mounting USB drives.
     */
    fun onVehicleIgnition() {
        Log.i(TAG, "onVehicleIgnition: checking previous playback state on startup")
        val wasPlaying = prefs?.getBoolean("key_was_playing", false) ?: false
        if (!wasPlaying) {
            Log.i(TAG, "onVehicleIgnition: local player was not active, keeping focus free")
            rescanLibrary(autoStart = false)
            return
        }
        autoPlayPending = true
        playerScope.launch(Dispatchers.Main) {
            val currentQ = if (_activeQueue.isNotEmpty()) _activeQueue else _playlist
            if (currentQ.isNotEmpty()) {
                val mp = mediaPlayer
                if (mp != null && isPrepared) {
                    play()
                    autoPlayPending = false
                } else {
                    val savedPos = prefs?.getLong(KEY_LAST_POS, 0L) ?: 0L
                    val track = currentQ.getOrElse(currentQueueIndex) { currentQ[0] }
                    prepareTrack(track, startPositionMs = savedPos, startImmediately = true)
                    autoPlayPending = false
                }
            } else {
                rescanLibrary(autoStart = true)
            }
        }

        // Retry check after 2 seconds to catch late USB volume mounts
        playerScope.launch(Dispatchers.Main) {
            delay(2000L)
            val mp = mediaPlayer
            val isPlaying = mp != null && isPrepared && mp.isPlaying
            if (!isPlaying && autoPlayPending) {
                Log.d(TAG, "Ignition playback retry: checking library again")
                val currentQ = if (_activeQueue.isNotEmpty()) _activeQueue else _playlist
                if (currentQ.isNotEmpty()) {
                    play()
                    autoPlayPending = false
                } else {
                    rescanLibrary(autoStart = true)
                }
            }
        }
    }

    /**
     * Dynamically searches all mounted storage volumes across the FYT Android system
     * (/storage/USB*, /mnt/media_rw/USB*, usbdisk*, external SD cards, and internal Music).
     */
    fun getStorageRoots(): List<File> {
        val roots = LinkedHashSet<File>()

        // 1. Dynamic search in /storage
        val storageDir = File("/storage")
        if (storageDir.exists() && storageDir.isDirectory) {
            storageDir.listFiles()?.forEach { f ->
                if (f.isDirectory && f.name != "self" && f.name != "emulated") {
                    roots.add(f)
                }
            }
        }

        // 2. Dynamic search in /mnt/media_rw
        val mediaRwDir = File("/mnt/media_rw")
        if (mediaRwDir.exists() && mediaRwDir.isDirectory) {
            mediaRwDir.listFiles()?.forEach { f ->
                if (f.isDirectory) roots.add(f)
            }
        }

        // 3. Common FYT hardware mount points
        val standard = listOf(
            File("/storage/USB1"),
            File("/storage/USB2"),
            File("/storage/USB3"),
            File("/storage/USB4"),
            File("/storage/usbdisk1"),
            File("/storage/usbdisk2"),
            File("/storage/usbdisk3"),
            File("/storage/usbdisk4"),
            File("/storage/sdcard1"),
            File("/mnt/media_rw/USB1"),
            File("/mnt/media_rw/USB2"),
            File("/mnt/media_rw/USB3"),
            File("/mnt/media_rw/USB4"),
            File("/storage/emulated/0/Music"),
            File("/sdcard/Music")
        )
        for (f in standard) {
            if (f.exists() && f.isDirectory) {
                roots.add(f)
            }
        }

        return roots.distinctBy {
            try { it.canonicalPath } catch (_: Exception) { it.absolutePath }
        }
    }

    private fun loadDurationCache(context: Context) {
        if (durationCacheLoaded) return
        durationCacheLoaded = true
        try {
            val f = File(context.filesDir, DURATION_CACHE_FILE)
            if (f.exists()) {
                val json = JSONObject(f.readText())
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    durationDiskCache[k] = json.getLong(k)
                }
                Log.d(TAG, "Loaded ${durationDiskCache.size} durations from disk cache")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed loading duration cache", e)
        }
    }

    private fun saveDurationCache(context: Context) {
        try {
            val f = File(context.filesDir, DURATION_CACHE_FILE)
            val json = JSONObject()
            durationDiskCache.forEach { (k, v) ->
                json.put(k, v)
            }
            f.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving duration cache", e)
        }
    }

    private fun queryMediaStoreDurations(context: Context): Map<String, Long> {
        val result = HashMap<String, Long>()
        try {
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                while (cursor.moveToNext()) {
                    if (dataCol >= 0 && durCol >= 0) {
                        val path = cursor.getString(dataCol)
                        val dur = cursor.getLong(durCol)
                        if (!path.isNullOrBlank() && dur > 0L) {
                            result[path] = dur
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed querying MediaStore durations", e)
        }
        return result
    }

    private fun enrichMissingDurations(context: Context) {
        playerScope.launch(Dispatchers.IO) {
            val missing = _playlist.filter { it.durationMs <= 0L }
            if (missing.isEmpty()) return@launch

            val mmr = MediaMetadataRetriever()
            var modified = false
            for (track in missing) {
                try {
                    mmr.setDataSource(track.path)
                    val durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val dur = durStr?.toLongOrNull() ?: 0L
                    if (dur > 0L) {
                        durationDiskCache[track.path] = dur
                        val idx = _playlist.indexOfFirst { it.path == track.path }
                        if (idx >= 0) {
                            _playlist[idx] = _playlist[idx].copy(durationMs = dur)
                            modified = true
                        }
                    }
                } catch (_: Exception) {}
            }
            try { mmr.release() } catch (_: Exception) {}

            if (modified) {
                saveDurationCache(context)
                withContext(Dispatchers.Main) {
                    _playlistFlow.value = _playlist.toList()
                }
            }
        }
    }

    /**
     * Scans all connected USB drives and local music folders for supported audio files.
     */
    fun rescanLibrary(autoStart: Boolean = false) {
        scanJob?.cancel()
        scanJob = playerScope.launch(Dispatchers.IO) {
            val ctx = appContext
            if (ctx != null) {
                loadDurationCache(ctx)
                val mediaStoreDurations = queryMediaStoreDurations(ctx)
                mediaStoreDurations.forEach { (p, d) ->
                    if (d > 0L) durationDiskCache[p] = d
                }
            }

            val roots = getStorageRoots()
            val audioExtensions = setOf("mp3", "flac", "m4a", "wav", "aac", "ogg")
            val foundFiles = mutableListOf<File>()

            for (root in roots) {
                if (root.exists() && root.isDirectory) {
                    scanDirectory(root, audioExtensions, foundFiles)
                }
            }

            // Natural alphanumeric sort
            foundFiles.sortBy { it.name.lowercase() }

            val tracks = foundFiles.map { extractTrackMetadata(it) }

            withContext(Dispatchers.Main) {
                val currentPlayingPath = currentTrack?.path
                _playlist.clear()
                _playlist.addAll(tracks)
                _playlistFlow.value = _playlist.toList()
                Log.i(TAG, "Indexed ${_playlist.size} tracks across ${roots.size} storage locations")

                if (_playlist.isNotEmpty()) {
                    // Try to preserve current playing track if still present
                    val matchingIndex = if (!currentPlayingPath.isNullOrBlank()) {
                        _playlist.indexOfFirst { it.path == currentPlayingPath }
                    } else -1

                    if (matchingIndex >= 0) {
                        currentIndex = matchingIndex
                        currentTrack = _playlist[currentIndex]
                        if (autoStart || autoPlayPending) {
                            autoPlayPending = false
                            val savedPos = prefs?.getLong(KEY_LAST_POS, 0L) ?: 0L
                            prepareTrack(currentIndex, startPositionMs = savedPos, startImmediately = true)
                        }
                    } else {
                        // Restore from persistent preferences
                        val lastPath = prefs?.getString(KEY_LAST_PATH, null)
                        val foundIndex = if (!lastPath.isNullOrBlank()) {
                            _playlist.indexOfFirst { it.path == lastPath }
                        } else -1

                        currentIndex = if (foundIndex >= 0) foundIndex else 0
                        currentTrack = _playlist[currentIndex]
                        val savedPos = prefs?.getLong(KEY_LAST_POS, 0L) ?: 0L

                        // Prepare restored track
                        val shouldStart = autoStart || autoPlayPending
                        autoPlayPending = false
                        prepareTrack(currentIndex, startPositionMs = savedPos, startImmediately = shouldStart)
                    }

                    // Update shuffle sequence
                    if (_playbackMode.value == PlaybackMode.SHUFFLE) {
                        regenerateShuffleOrder()
                    }
                }
            }

            if (ctx != null) {
                enrichMissingDurations(ctx)
            }
        }
    }

    private fun scanDirectory(dir: File, extensions: Set<String>, output: MutableList<File>) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                if (!f.name.startsWith(".") && !f.name.equals("Android", ignoreCase = true) && !f.name.equals("LOST.DIR", ignoreCase = true)) {
                    scanDirectory(f, extensions, output)
                }
            } else if (f.isFile) {
                val ext = f.extension.lowercase()
                if (ext in extensions && f.length() > 50_000) {
                    output.add(f)
                }
            }
        }
    }

    /**
     * Fast filename-based metadata extraction for instantaneous USB library indexing.
     */
    private fun extractTrackMetadata(file: File): LocalTrack {
        var title: String
        var artist = ""
        val name = file.nameWithoutExtension

        if (name.contains(" - ")) {
            val parts = name.split(" - ")
            title = parts[0].trim()
            artist = parts.getOrNull(1)?.trim() ?: ""
        } else {
            title = name
        }

        if (artist.isBlank()) {
            val parent = file.parentFile?.name
            artist = if (!parent.isNullOrBlank() && parent != "Music" && !parent.startsWith("USB")) parent else "Musica Local"
        }

        val cachedDur = durationDiskCache[file.absolutePath] ?: 0L

        return LocalTrack(
            path = file.absolutePath,
            title = title,
            artist = artist,
            album = "",
            durationMs = cachedDur
        )
    }

    /**
     * Sets the active playback context queue (scoped to Folder, Artist, Favorites, or Full Library)
     * and plays the selected track immediately.
     */
    fun playTrackInQueue(track: LocalTrack, queue: List<LocalTrack>, queueTitle: String = "Biblioteca") {
        val q = if (queue.isNotEmpty()) queue else _playlist
        _activeQueue.clear()
        _activeQueue.addAll(q)
        _queueTitleFlow.value = queueTitle

        val qIdx = _activeQueue.indexOfFirst { it.path == track.path }
        currentQueueIndex = if (qIdx >= 0) qIdx else 0

        val pIdx = _playlist.indexOfFirst { it.path == track.path }
        currentIndex = if (pIdx >= 0) pIdx else 0

        if (_playbackMode.value == PlaybackMode.SHUFFLE) {
            regenerateShuffleOrder()
        }

        prepareTrack(track, startPositionMs = 0L, startImmediately = true)
    }

    private fun prepareTrack(track: LocalTrack, startPositionMs: Long = 0L, startImmediately: Boolean = false) {
        val ctx = appContext ?: return
        currentTrack = track

        val pIdx = _playlist.indexOfFirst { it.path == track.path }
        if (pIdx >= 0) currentIndex = pIdx
        val qIdx = _activeQueue.indexOfFirst { it.path == track.path }
        if (qIdx >= 0) currentQueueIndex = qIdx

        try {
            // Strictly reset cached lyrics & album art immediately so old track data never leaks!
            cachedLyrics = emptyList()
            cachedAlbumArt = null

            // 0ms Synchronous Fast-Check: RAM or Local Disk Cache
            val immediateLyrics = LyricsRepository.loadFromMemoryOrDisk(ctx, track.title, track.artist, track.path)
            if (immediateLyrics.isNotEmpty()) {
                cachedLyrics = immediateLyrics
            }

            mediaPlayer?.release()
            isPrepared = false

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(track.path)
                setOnPreparedListener { mp ->
                    isPrepared = true
                    val realDuration = try { mp.duration.toLong() } catch (_: Exception) { 0L }
                    if (realDuration > 0L) {
                        durationDiskCache[track.path] = realDuration
                        val updated = (currentTrack ?: track).copy(durationMs = realDuration)
                        currentTrack = updated
                        val pi = _playlist.indexOfFirst { it.path == track.path }
                        if (pi >= 0) {
                            _playlist[pi] = _playlist[pi].copy(durationMs = realDuration)
                            _playlistFlow.value = _playlist.toList()
                        }
                        val qi = _activeQueue.indexOfFirst { it.path == track.path }
                        if (qi >= 0) {
                            _activeQueue[qi] = _activeQueue[qi].copy(durationMs = realDuration)
                        }
                    }
                    if (startPositionMs > 0L) {
                        try {
                            mp.seekTo(startPositionMs.toInt())
                        } catch (_: Exception) {}
                    }
                    if (startImmediately) {
                        requestAudioFocus()
                        mp.start()
                        startProgressLoop()
                    }
                    publishState(isPlaying = startImmediately)
                }
                setOnCompletionListener {
                    handleTrackCompletion()
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error: what=$what, extra=$extra on ${track.path}")
                    skipToNext()
                    true
                }
                prepareAsync()
            }

            // Save persistent track state
            prefs?.edit()
                ?.putString(KEY_LAST_PATH, track.path)
                ?.putInt(KEY_LAST_INDEX, currentIndex)
                ?.putLong(KEY_LAST_POS, startPositionMs)
                ?.apply()

            // Load album art & lyrics and refine tags in background
            playerScope.launch(Dispatchers.IO) {
                try {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(track.path)
                    val mmrTitle = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    val mmrArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    val mmrAlbum = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    val durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val mmrDur = durStr?.toLongOrNull() ?: 0L
                    if (mmrDur > 0L) {
                        durationDiskCache[track.path] = mmrDur
                    }
                    val refinedTitle = if (!mmrTitle.isNullOrBlank() && !mmrTitle.contains("\uFFFD")) mmrTitle.trim() else track.title
                    val refinedArtist = if (!mmrArtist.isNullOrBlank() && !mmrArtist.contains("\uFFFD")) mmrArtist.trim() else track.artist
                    currentTrack = (currentTrack ?: track).copy(
                        title = refinedTitle,
                        artist = refinedArtist,
                        album = mmrAlbum ?: "",
                        durationMs = if (mmrDur > 0L) mmrDur else track.durationMs
                    )
                    mmr.release()
                } catch (_: Exception) {}

                val activeTrack = currentTrack ?: track
                cachedAlbumArt = extractAlbumArt(activeTrack.path)
                val rawLyrics = LyricsRepository.getOrFetchLyrics(ctx, activeTrack.title, activeTrack.artist, activeTrack.path, activeTrack.durationMs)
                if (rawLyrics.isNotEmpty()) {
                    cachedLyrics = rawLyrics
                    withContext(Dispatchers.Main) {
                        publishState(isPlaying = startImmediately)
                    }
                }
                val enriched = LyricsTranslationManager.enrichLyricsWithTranslation(ctx, if (rawLyrics.isNotEmpty()) rawLyrics else cachedLyrics)
                if (enriched.isNotEmpty()) {
                    cachedLyrics = enriched
                    withContext(Dispatchers.Main) {
                        publishState(isPlaying = startImmediately)
                    }
                }
                LyricsRepository.preloadFolder(ctx, activeTrack.path)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare track ${track.path}", e)
        }
    }

    private fun prepareTrack(index: Int, startPositionMs: Long = 0L, startImmediately: Boolean = false) {
        if (index in _playlist.indices) {
            prepareTrack(_playlist[index], startPositionMs, startImmediately)
        }
    }

    /**
     * Handles track completion according to the active PlaybackMode:
     * - REPEAT_ONE: Restarts the current song at 0:00.
     * - SEQUENCE: Advances to the next track within the active scoped queue (loops folder/artist/queue).
     * - SHUFFLE: Advances to the next randomized track within the active scoped queue.
     */
    private fun handleTrackCompletion() {
        when (_playbackMode.value) {
            PlaybackMode.REPEAT_ONE -> {
                val mp = mediaPlayer
                if (mp != null && isPrepared) {
                    mp.seekTo(0)
                    mp.start()
                    startProgressLoop()
                    publishState(isPlaying = true)
                } else {
                    val currentQ = if (_activeQueue.isNotEmpty()) _activeQueue else _playlist
                    val track = currentQ.getOrNull(currentQueueIndex) ?: _playlist.getOrNull(currentIndex)
                    if (track != null) {
                        prepareTrack(track, startPositionMs = 0L, startImmediately = true)
                    }
                }
            }
            PlaybackMode.SHUFFLE, PlaybackMode.SEQUENCE -> {
                skipToNext()
            }
        }
    }

    /**
     * Toggles playback mode between SHUFFLE and SEQUENCE.
     */
    fun toggleShuffle(): PlaybackMode {
        val nextMode = if (_playbackMode.value == PlaybackMode.SHUFFLE) PlaybackMode.SEQUENCE else PlaybackMode.SHUFFLE
        setPlaybackMode(nextMode)
        return nextMode
    }

    /**
     * Toggles playback mode between REPEAT_ONE and SEQUENCE.
     */
    fun toggleRepeat(): PlaybackMode {
        val nextMode = if (_playbackMode.value == PlaybackMode.REPEAT_ONE) PlaybackMode.SEQUENCE else PlaybackMode.REPEAT_ONE
        setPlaybackMode(nextMode)
        return nextMode
    }

    /**
     * Cycles playback mode: SEQUENCE -> REPEAT_ONE -> SHUFFLE -> SEQUENCE.
     */
    fun cyclePlaybackMode(): PlaybackMode {
        val nextMode = when (_playbackMode.value) {
            PlaybackMode.SEQUENCE -> PlaybackMode.REPEAT_ONE
            PlaybackMode.REPEAT_ONE -> PlaybackMode.SHUFFLE
            PlaybackMode.SHUFFLE -> PlaybackMode.SEQUENCE
        }
        setPlaybackMode(nextMode)
        return nextMode
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        _playbackMode.value = mode
        prefs?.edit()?.putString(KEY_PLAYBACK_MODE, mode.name)?.apply()
        if (mode == PlaybackMode.SHUFFLE) {
            regenerateShuffleOrder()
        }
        publishState(isPlaying = mediaPlayer?.isPlaying == true)
    }

    private fun regenerateShuffleOrder() {
        val currentQ = if (_activeQueue.isNotEmpty()) _activeQueue else _playlist
        if (currentQ.isEmpty()) return
        shuffledIndices.clear()
        val indices = currentQ.indices.toMutableList()
        indices.remove(currentQueueIndex)
        indices.shuffle()
        shuffledIndices.add(currentQueueIndex)
        shuffledIndices.addAll(indices)
        shufflePointer = 0
    }

    /**
     * Toggles playback between Play and Pause.
     */
    fun togglePlayPause() {
        val mp = mediaPlayer
        if (mp != null && isPrepared) {
            if (mp.isPlaying) {
                pause()
            } else {
                play()
            }
        } else {
            play()
        }
    }

    /**
     * Starts or resumes playback.
     */
    fun play() {
        val ctx = appContext ?: return
        stopOemPlayer(ctx)
        hasAudioFocus = false
        requestAudioFocus()
        val mp = mediaPlayer
        if (mp != null && isPrepared) {
            try {
                mp.start()
                startProgressLoop()
                publishState(isPlaying = true)
            } catch (e: Exception) {
                Log.w(TAG, "Play failed", e)
            }
        } else {
            val currentQ = if (_activeQueue.isNotEmpty()) _activeQueue else _playlist
            if (currentQ.isNotEmpty()) {
                val savedPos = prefs?.getLong(KEY_LAST_POS, 0L) ?: 0L
                val track = currentQ.getOrElse(currentQueueIndex) { currentQ[0] }
                prepareTrack(track, startPositionMs = savedPos, startImmediately = true)
            } else {
                rescanLibrary(autoStart = true)
            }
        }
    }

    /**
     * Pauses playback and releases audio focus so external players (SmartTube, YouTube, etc.) can play.
     */
    fun pause() {
        try {
            mediaPlayer?.pause()
            stopProgressLoop()
            abandonAudioFocus()
            publishState(isPlaying = false)
            val pos = mediaPlayer?.currentPosition?.toLong() ?: 0L
            prefs?.edit()?.putLong(KEY_LAST_POS, pos)?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Pause failed", e)
        }
    }

    /**
     * Skips to the next song in the active scoped queue (folder, artist, favorites, or library).
     * In SEQUENCE mode: Continuously loops inside this active context (N -> 1).
     * In SHUFFLE mode: Randomly plays all tracks of this active context without repetition.
     */
    fun skipToNext() {
        val currentQ = if (_activeQueue.isNotEmpty()) _activeQueue else _playlist
        if (currentQ.isEmpty()) return

        when (_playbackMode.value) {
            PlaybackMode.SHUFFLE -> {
                if (shuffledIndices.isEmpty() || shuffledIndices.size != currentQ.size) {
                    regenerateShuffleOrder()
                }
                shufflePointer = (shufflePointer + 1) % shuffledIndices.size
                val nextQueueIdx = shuffledIndices.getOrElse(shufflePointer) { 0 }
                currentQueueIndex = nextQueueIdx
                val nextTrack = currentQ[currentQueueIndex]
                val pIdx = _playlist.indexOfFirst { it.path == nextTrack.path }
                if (pIdx >= 0) currentIndex = pIdx
                prepareTrack(nextTrack, startImmediately = true)
            }
            PlaybackMode.REPEAT_ONE, PlaybackMode.SEQUENCE -> {
                currentQueueIndex = (currentQueueIndex + 1) % currentQ.size
                val nextTrack = currentQ[currentQueueIndex]
                val pIdx = _playlist.indexOfFirst { it.path == nextTrack.path }
                if (pIdx >= 0) currentIndex = pIdx
                prepareTrack(nextTrack, startImmediately = true)
            }
        }
    }

    /**
     * Skips to the previous song in the active scoped queue, or restarts if past 3 seconds.
     */
    fun skipToPrevious() {
        val currentQ = if (_activeQueue.isNotEmpty()) _activeQueue else _playlist
        if (currentQ.isEmpty()) return

        val curPos = mediaPlayer?.currentPosition ?: 0
        if (curPos > 3000) {
            seekTo(0L)
            return
        }

        when (_playbackMode.value) {
            PlaybackMode.SHUFFLE -> {
                if (shuffledIndices.isEmpty() || shuffledIndices.size != currentQ.size) {
                    regenerateShuffleOrder()
                }
                shufflePointer = if (shufflePointer - 1 < 0) shuffledIndices.size - 1 else shufflePointer - 1
                val prevQueueIdx = shuffledIndices.getOrElse(shufflePointer) { 0 }
                currentQueueIndex = prevQueueIdx
                val prevTrack = currentQ[currentQueueIndex]
                val pIdx = _playlist.indexOfFirst { it.path == prevTrack.path }
                if (pIdx >= 0) currentIndex = pIdx
                prepareTrack(prevTrack, startImmediately = true)
            }
            PlaybackMode.REPEAT_ONE, PlaybackMode.SEQUENCE -> {
                currentQueueIndex = if (currentQueueIndex - 1 < 0) currentQ.size - 1 else currentQueueIndex - 1
                val prevTrack = currentQ[currentQueueIndex]
                val pIdx = _playlist.indexOfFirst { it.path == prevTrack.path }
                if (pIdx >= 0) currentIndex = pIdx
                prepareTrack(prevTrack, startImmediately = true)
            }
        }
    }

    /**
     * Plays the track at the specified index in the library immediately.
     */
    fun playTrackAtIndex(index: Int) {
        if (index in _playlist.indices) {
            playTrackInQueue(_playlist[index], _playlist, "Biblioteca (${_playlist.size})")
        }
    }

    /**
     * Plays the given LocalTrack immediately.
     */
    fun playTrack(track: LocalTrack) {
        val idx = _playlist.indexOfFirst { it.path == track.path }
        if (idx >= 0) {
            playTrackAtIndex(idx)
        } else {
            prepareTrack(track, startPositionMs = 0L, startImmediately = true)
        }
    }

    /**
     * Triggers asynchronous background translation enrichment for the currently playing lyrics.
     */
    fun ensureLyricsTranslation() {
        val ctx = appContext ?: return
        if (cachedLyrics.isEmpty()) return
        playerScope.launch(Dispatchers.IO) {
            val enriched = LyricsTranslationManager.enrichLyricsWithTranslation(ctx, cachedLyrics)
            cachedLyrics = enriched
            withContext(Dispatchers.Main) {
                publishState(isPlaying = mediaPlayer?.isPlaying == true)
            }
        }
    }

    /**
     * Skips forward or backward by a given number of seconds (-10s / +10s).
     */
    fun skipSeconds(seconds: Int) {
        val mp = mediaPlayer ?: return
        if (!isPrepared) return
        val current = mp.currentPosition.toLong()
        val target = (current + seconds * 1000L).coerceIn(0L, mp.duration.toLong())
        seekTo(target)
    }

    /**
     * INSTANTANEOUS SEEK: Seeks directly in the hardware audio stream to the exact millisecond.
     */
    fun seekTo(targetMs: Long) {
        val mp = mediaPlayer ?: return
        if (!isPrepared) return
        try {
            val safeTarget = targetMs.coerceIn(0L, mp.duration.toLong())
            mp.seekTo(safeTarget.toInt())
            prefs?.edit()?.putLong(KEY_LAST_POS, safeTarget)?.apply()

            // Update lyrics line immediately
            val currentLine = if (cachedLyrics.isNotEmpty()) {
                val idx = cachedLyrics.indexOfLast { it.timeMs <= safeTarget }
                if (idx >= 0) cachedLyrics[idx].text else ""
            } else ""

            PowerampNotificationListener.updateFromLocalPlayer(
                title = currentTrack?.title ?: "Musica Local",
                artist = currentTrack?.artist ?: "",
                album = currentTrack?.album ?: "",
                albumArt = cachedAlbumArt,
                isPlaying = mp.isPlaying,
                progressMs = safeTarget,
                durationMs = mp.duration.toLong(),
                lyricsLine = currentLine,
                fullLyrics = cachedLyrics,
                playbackMode = _playbackMode.value
            )
        } catch (e: Exception) {
            Log.w(TAG, "seekTo failed", e)
        }
    }

    private var lastSavedSec: Long = 0L

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = playerScope.launch {
            while (isActive) {
                val mp = mediaPlayer
                if (mp != null && isPrepared && mp.isPlaying) {
                    val pos = mp.currentPosition.toLong()
                    val dur = mp.duration.toLong()

                    // Continuously save current position every 2 seconds
                    val sec = pos / 1000L
                    if (sec != lastSavedSec && sec % 2L == 0L) {
                        lastSavedSec = sec
                        prefs?.edit()?.putLong(KEY_LAST_POS, pos)?.apply()
                    }

                    val currentLine = if (cachedLyrics.isNotEmpty()) {
                        val idx = cachedLyrics.indexOfLast { it.timeMs <= pos }
                        if (idx >= 0) cachedLyrics[idx].text else ""
                    } else ""

                    PowerampNotificationListener.updateFromLocalPlayer(
                        title = currentTrack?.title ?: "Musica Local",
                        artist = currentTrack?.artist ?: "",
                        album = currentTrack?.album ?: "",
                        albumArt = cachedAlbumArt,
                        isPlaying = true,
                        progressMs = pos,
                        durationMs = dur,
                        lyricsLine = currentLine,
                        fullLyrics = cachedLyrics,
                        playbackMode = _playbackMode.value
                    )
                }
                delay(250L)
            }
        }
    }

    private fun stopProgressLoop() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun publishState(isPlaying: Boolean) {
        val mp = mediaPlayer
        val track = currentTrack ?: return
        val pos = try { if (isPrepared && mp != null) mp.currentPosition.toLong() else 0L } catch (_: Exception) { 0L }
        val dur = try { if (isPrepared && mp != null) mp.duration.toLong() else track.durationMs } catch (_: Exception) { track.durationMs }

        val currentLine = if (cachedLyrics.isNotEmpty()) {
            val idx = cachedLyrics.indexOfLast { it.timeMs <= pos }
            if (idx >= 0) cachedLyrics[idx].text else ""
        } else ""

        PowerampNotificationListener.updateFromLocalPlayer(
            title = track.title,
            artist = track.artist,
            album = track.album,
            albumArt = cachedAlbumArt,
            isPlaying = isPlaying,
            progressMs = pos,
            durationMs = dur,
            lyricsLine = currentLine,
            fullLyrics = cachedLyrics,
            playbackMode = _playbackMode.value
        )

        updateMediaSessionState(isPlaying)
    }

    private fun extractAlbumArt(filePath: String): Bitmap? {
        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(filePath)
            val artBytes = retriever.embeddedPicture
            if (artBytes != null) {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = 2
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                return BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, opts)
            }
        } catch (_: Exception) {
        } finally {
            try {
                retriever?.release()
            } catch (_: Exception) {}
        }

        // Check for folder cover art: cover.jpg, folder.jpg
        val parent = File(filePath).parentFile ?: return null
        val candidates = listOf("cover.jpg", "folder.jpg", "album.jpg", "cover.png", "folder.png")
        for (name in candidates) {
            val img = File(parent, name)
            if (img.exists()) {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = 2
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                return BitmapFactory.decodeFile(img.absolutePath, opts)
            }
        }
        return null
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (hasAudioFocus) return
        try {
            val res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(playbackAttrs)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
                focusRequest?.let { am.requestAudioFocus(it) } ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
            if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                hasAudioFocus = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error requesting audio focus", e)
        }
    }

    /**
     * Completely releases audio focus so external players (SmartTube, YouTube, Spotify, etc.) have full audio access.
     */
    fun abandonAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(audioFocusChangeListener)
            }
            hasAudioFocus = false
            Log.d(TAG, "Audio focus surrendered to external audio/video player")
        } catch (e: Exception) {
            Log.w(TAG, "Error abandoning audio focus", e)
        }
    }

    private fun stopOemPlayer(context: Context) {
        try {
            context.sendBroadcast(Intent("com.syu.music.stop"))
            context.sendBroadcast(Intent("syu.widget.stop"))
        } catch (_: Exception) {}
    }

    private fun registerUsbReceivers(context: Context) {
        if (usbReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                Log.i(TAG, "Storage broadcast received: $action")
                playerScope.launch {
                    delay(1500L)
                    rescanLibrary(autoStart = false)
                }
            }
        }
        usbReceiver = receiver

        try {
            val storageFilter = IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_EJECT)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addDataScheme("file")
            }
            context.registerReceiver(receiver, storageFilter)

            val deviceFilter = IntentFilter().apply {
                addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED")
                addAction("android.hardware.usb.action.USB_DEVICE_DETACHED")
                addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
                addAction(Intent.ACTION_DEVICE_STORAGE_OK)
            }
            context.registerReceiver(receiver, deviceFilter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register USB receivers", e)
        }
    }

    fun next() = skipToNext()
    fun previous() = skipToPrevious()

    private fun initMediaSession(context: Context) {
        if (mediaSession != null) return
        try {
            val session = MediaSession(context, "XCarOS_Player").apply {
                setFlags(
                    MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
                )
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() {
                        handlePlayCommand(context)
                    }

                    override fun onPause() {
                        handlePauseCommand(context)
                    }

                    override fun onSkipToNext() {
                        handleNextCommand(context)
                    }

                    override fun onSkipToPrevious() {
                        handlePrevCommand(context)
                    }

                    override fun onStop() {
                        handlePauseCommand(context)
                    }

                    override fun onFastForward() {
                        val cur = mediaPlayer?.currentPosition?.toLong() ?: 0L
                        seekTo(cur + 10_000L)
                    }

                    override fun onRewind() {
                        val cur = mediaPlayer?.currentPosition?.toLong() ?: 0L
                        seekTo(cur - 10_000L)
                    }

                    override fun onSeekTo(pos: Long) {
                        seekTo(pos)
                    }

                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                        }
                        if (event != null && event.action == KeyEvent.ACTION_DOWN) {
                            Log.i(TAG, "MediaSession onMediaButtonEvent: keyCode=${event.keyCode}")
                            return handleMediaKeyCode(context, event.keyCode)
                        }
                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }
                })
                isActive = false
            }
            mediaSession = session
            updateMediaSessionState(isPlaying = false)
            Log.i(TAG, "Native MediaSession initialized and active")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MediaSession", e)
        }
    }

    private fun updateMediaSessionState(isPlaying: Boolean) {
        val session = mediaSession ?: return
        session.isActive = isPlaying
        val track = currentTrack
        val pos = mediaPlayer?.currentPosition?.toLong() ?: 0L
        try {
            val stateBuilder = PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_FAST_FORWARD or
                    PlaybackState.ACTION_REWIND
                )
                .setState(
                    if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    pos,
                    1.0f
                )
            session.setPlaybackState(stateBuilder.build())

            if (track != null) {
                val metaBuilder = MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
                if (cachedAlbumArt != null) {
                    metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cachedAlbumArt)
                }
                session.setMetadata(metaBuilder.build())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error updating MediaSession state", e)
        }
    }

    fun handleMediaKeyCode(context: Context, keyCode: Int): Boolean {
        Log.i(TAG, "handleMediaKeyCode: $keyCode")
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
            KeyEvent.KEYCODE_NAVIGATE_NEXT,
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                handleNextCommand(context)
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
            KeyEvent.KEYCODE_NAVIGATE_PREVIOUS,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                handlePrevCommand(context)
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                handlePlayPauseCommand(context)
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                handlePlayCommand(context)
                true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                handlePauseCommand(context)
                true
            }
            else -> false
        }
    }

    fun handleNextCommand(context: Context) {
        val isBtConnected = com.x.carlauncher.bluetooth.BluetoothViewModel.isDeviceConnected
        val isBtActive = PowerampNotificationListener.currentSource.value == AudioSource.BLUETOOTH
        Log.i(TAG, "handleNextCommand: isBtActive=$isBtActive, isBtConnected=$isBtConnected")
        if (isBtConnected && isBtActive) {
            FytMusicService.sendBtNext(context)
        } else if (PowerampNotificationListener.isExternalSessionActive()) {
            // YouTube Music / Spotify sonando: el boton del volante o del
            // widget FYT pasa a la siguiente cancion de esa app, no de la local.
            PowerampNotificationListener.skipToNext(context)
        } else {
            next()
        }
    }

    fun handlePrevCommand(context: Context) {
        val isBtConnected = com.x.carlauncher.bluetooth.BluetoothViewModel.isDeviceConnected
        val isBtActive = PowerampNotificationListener.currentSource.value == AudioSource.BLUETOOTH
        Log.i(TAG, "handlePrevCommand: isBtActive=$isBtActive, isBtConnected=$isBtConnected")
        if (isBtConnected && isBtActive) {
            FytMusicService.sendBtPrev(context)
        } else if (PowerampNotificationListener.isExternalSessionActive()) {
            PowerampNotificationListener.skipToPrevious(context)
        } else {
            previous()
        }
    }

    fun handlePlayPauseCommand(context: Context) {
        val isBtConnected = com.x.carlauncher.bluetooth.BluetoothViewModel.isDeviceConnected
        val isBtActive = PowerampNotificationListener.currentSource.value == AudioSource.BLUETOOTH
        Log.i(TAG, "handlePlayPauseCommand: isBtActive=$isBtActive, isBtConnected=$isBtConnected")
        if (isBtConnected && isBtActive) {
            FytMusicService.sendBtPlayPause(context)
        } else if (PowerampNotificationListener.isExternalSessionActive()) {
            PowerampNotificationListener.togglePlayPause(context)
        } else {
            togglePlayPause()
        }
    }

    fun handlePlayCommand(context: Context) {
        val isBtConnected = com.x.carlauncher.bluetooth.BluetoothViewModel.isDeviceConnected
        val isBtActive = PowerampNotificationListener.currentSource.value == AudioSource.BLUETOOTH
        Log.i(TAG, "handlePlayCommand: isBtActive=$isBtActive, isBtConnected=$isBtConnected")
        if (isBtConnected && isBtActive) {
            FytMusicService.sendBtPlayPause(context)
        } else if (PowerampNotificationListener.isExternalSessionActive()) {
            PowerampNotificationListener.play(context)
        } else {
            play()
        }
    }

    fun handlePauseCommand(context: Context) {
        val isBtConnected = com.x.carlauncher.bluetooth.BluetoothViewModel.isDeviceConnected
        val isBtActive = PowerampNotificationListener.currentSource.value == AudioSource.BLUETOOTH
        Log.i(TAG, "handlePauseCommand: isBtActive=$isBtActive, isBtConnected=$isBtConnected")
        if (isBtConnected && isBtActive) {
            FytMusicService.sendBtPlayPause(context)
        } else if (PowerampNotificationListener.isExternalSessionActive()) {
            PowerampNotificationListener.pause(context)
        } else {
            pause()
        }
    }
}
