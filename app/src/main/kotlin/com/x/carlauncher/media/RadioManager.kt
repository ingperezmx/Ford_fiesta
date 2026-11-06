package com.x.carlauncher.media

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Categorias de Estaciones de Radio para XCarOS.
 */
enum class RadioCategory(val title: String, val chipLabel: String) {
    ALL("Todas las Estaciones", "Todas"),
    MONTERREY("Estaciones de Monterrey", "Monterrey FM"),
    ROCK_ALT("Rock Alternativo", "Rock Alt"),
    SKA("Ska & 2-Tone", "Ska"),
    ROCK_ES("Rock en Espanol", "Rock ES")
}

/**
 * Modelo de datos para una estacion de radio FM / Online.
 */
data class RadioStation(
    val id: String,
    val name: String,
    val frequency: String,
    val category: RadioCategory,
    val description: String,
    val streamUrl: String,
    val city: String = "Monterrey, NL",
    val accentColor: Long = 0xFF00E5FF
)

/**
 * Gestor y Reproductor Nativo de Radio FM y Streaming Online para XCarOS.
 * Desarrollado con Android Media3 / ExoPlayer para soporte robusto de streaming continuo,
 * gestion automatica de buffer y foco de audio automotriz sin cortes.
 */
object RadioManager {

    private const val TAG = "RadioManager"

    private var exoPlayer: ExoPlayer? = null
    private var audioManager: AudioManager? = null

    // ── Estados observables ───────────────────────────────────────────────────
    private val _currentStation = MutableStateFlow<RadioStation?>(null)
    val currentStation: StateFlow<RadioStation?> = _currentStation.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _statusText = MutableStateFlow("Listo para sintonizar")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    // ── Catalogo de Estaciones Verificadas ─────────────────────────────────────
    val stations: List<RadioStation> = listOf(
        // ── 1. ESTACIONES DE MONTERREY Y NUEVO LEON ──
        RadioStation(
            id = "mty_classic_1069",
            name = "Classic 106.9 FM",
            frequency = "106.9 FM",
            category = RadioCategory.MONTERREY,
            description = "Rock Clasico, Pop & Retro - Multimedios Monterrey",
            streamUrl = "https://mdstrm.com/audio/673788019b826f0dc1842750/icecast.audio",
            city = "Monterrey, NL",
            accentColor = 0xFF38BDF8
        ),
        RadioStation(
            id = "mty_d99_989",
            name = "D99 98.9 FM",
            frequency = "98.9 FM",
            category = RadioCategory.MONTERREY,
            description = "Rock Juvenil, Pop & Alternativo - Multimedios Monterrey",
            streamUrl = "https://mdstrm.com/audio/662800a9c30cbb3e2e61f44b/icecast.audio",
            city = "Monterrey, NL",
            accentColor = 0xFFF59E0B
        ),
        RadioStation(
            id = "mty_digital_1029",
            name = "Digital 102.9 FM",
            frequency = "102.9 FM",
            category = RadioCategory.MONTERREY,
            description = "Pop Internacional & Hits en Vivo - Radio Alegria",
            streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/DIGITAL_1029FMAAC.aac",
            city = "Monterrey, NL",
            accentColor = 0xFFEC4899
        ),
        RadioStation(
            id = "mty_genesis_981",
            name = "Genesis 98.1 FM",
            frequency = "98.1 FM",
            category = RadioCategory.MONTERREY,
            description = "Musica en Espanol & Baladas - Nucleo Radio Monterrey",
            streamUrl = "https://streamingcwsradio30.com/8238/stream",
            city = "Monterrey, NL",
            accentColor = 0xFF10B981
        ),
        RadioStation(
            id = "mty_lupe_1053",
            name = "La Lupe 105.3 FM",
            frequency = "105.3 FM",
            category = RadioCategory.MONTERREY,
            description = "Musica Mexicana y Clasicos de Oro - Multimedios",
            streamUrl = "https://mmradio-lb.mmlabs.mx/xhlupefm",
            city = "Monterrey, NL",
            accentColor = 0xFFEF4444
        ),
        RadioStation(
            id = "mty_gran_aw_1013",
            name = "La Gran AW 101.3 FM",
            frequency = "101.3 FM",
            category = RadioCategory.MONTERREY,
            description = "Noticias y Grandes Exitos - Multimedios Monterrey",
            streamUrl = "https://mdstrm.com/audio/67378ab60639b6fb47c91763/icecast.audio",
            city = "Monterrey, NL",
            accentColor = 0xFF6366F1
        ),
        RadioStation(
            id = "mty_exa_901",
            name = "Exa FM 90.1 MTY",
            frequency = "90.1 FM",
            category = RadioCategory.MONTERREY,
            description = "Exa FM Ponte Exa - MVS Radio Monterrey",
            streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/XHRSMTYFM.mp3",
            city = "Monterrey, NL",
            accentColor = 0xFFF97316
        ),
        RadioStation(
            id = "mty_los40_885",
            name = "LOS40 88.5 FM",
            frequency = "88.5 FM",
            category = RadioCategory.MONTERREY,
            description = "Los 40 Principales - Pop & Hits Monterrey",
            streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/LOS40_MTY.mp3",
            city = "Monterrey, NL",
            accentColor = 0xFF00E5FF
        ),

        // ── 2. ROCK ALTERNATIVO ──
        RadioStation(
            id = "rock_alternativa_mix",
            name = "Alternativa by Mix",
            frequency = "106.5 HD",
            category = RadioCategory.ROCK_ALT,
            description = "Rock Alternativo 80s, 90s, 2000s y Grunge - Grupo ACIR",
            streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/ACIR12_s01AAC.aac",
            city = "Mexico",
            accentColor = 0xFF00E5FF
        ),
        RadioStation(
            id = "rock_mix_1065",
            name = "Mix 106.5 Rock & Pop",
            frequency = "106.5 FM",
            category = RadioCategory.ROCK_ALT,
            description = "Rock y Pop Clasico 80s, 90s y 2000s - Grupo ACIR",
            streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/ACIR02_S01AAC.aac",
            city = "Mexico / Monterrey",
            accentColor = 0xFFE11D48
        ),
        RadioStation(
            id = "rock_kexp_903",
            name = "KEXP 90.3 Alternative",
            frequency = "90.3 FM",
            category = RadioCategory.ROCK_ALT,
            description = "Post-Punk, Grunge, Indie Rock & Alternativo Mundial",
            streamUrl = "https://kexp.streamguys1.com/kexp160.aac",
            city = "Seattle / Global",
            accentColor = 0xFFFBBF24
        ),

        // ── 3. SKA Y 2-TONE ──
        RadioStation(
            id = "ska_bobs_radio",
            name = "Bob's Ska Radio",
            frequency = "Ska World",
            category = RadioCategory.SKA,
            description = "Tradicional Ska, Rocksteady, 2-Tone & Ska-Punk 24/7",
            streamUrl = "http://51.255.235.165:5528/stream",
            city = "Global Ska",
            accentColor = 0xFF10B981
        ),
        RadioStation(
            id = "ska_world_radio",
            name = "Ska World Radio",
            frequency = "Ska 24/7",
            category = RadioCategory.SKA,
            description = "Ska Punk, Latin Ska, Ska-P, Los Fabulosos Cadillacs",
            streamUrl = "http://skaworld.stream.laut.fm/skaworld",
            city = "Global Ska",
            accentColor = 0xFFF59E0B
        ),
        RadioStation(
            id = "ska_alpha_boys",
            name = "Alpha Boys Ska",
            frequency = "2-Tone Roots",
            category = RadioCategory.SKA,
            description = "Ska Clasico, Specials, Madness, Desmond Dekker",
            streamUrl = "http://alphaboys-live.streamguys1.com/alphaboys.mp3",
            city = "UK / Global",
            accentColor = 0xFFFFFFFF
        ),
        RadioStation(
            id = "ska_2toneroom_gdl",
            name = "+2ToneRoom Radio (GDL)",
            frequency = "2-Tone Ska",
            category = RadioCategory.SKA,
            description = "Ska Mexicano, Inspector, Panteon Rococo, Madness",
            streamUrl = "http://209.141.43.200:10018/stream",
            city = "Guadalajara / Mexico",
            accentColor = 0xFF00E5FF
        ),

        // ── 4. ROCK EN ESPANOL ──
        RadioStation(
            id = "rock_es_acir",
            name = "Rock en Espanol (ACIR)",
            frequency = "Rock ES",
            category = RadioCategory.ROCK_ES,
            description = "Soda Stereo, Caifanes, Heroes del Silencio, Enanitos, Mana",
            streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/ACIR20_S01AAC.aac",
            city = "Mexico",
            accentColor = 0xFFEF4444
        ),
        RadioStation(
            id = "rock_es_clasicos",
            name = "Rock en tu Idioma",
            frequency = "Clasicos ES",
            category = RadioCategory.ROCK_ES,
            description = "Maldita Vecindad, El Tri, Cafe Tacvba, Fobia, La Cuca",
            streamUrl = "https://playerservices.streamtheworld.com/api/livestream-redirect/ACIR02_S01AAC.aac",
            city = "Mexico / Monterrey",
            accentColor = 0xFF38BDF8
        )
    )

    /**
     * Inicializa la instancia singleton de ExoPlayer con soporte de redirecciones
     * y gestion automatica de foco de audio automotriz.
     */
    private fun getOrCreatePlayer(context: Context): ExoPlayer {
        exoPlayer?.let { return it }

        val appContext = context.applicationContext

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 10; Mobile)")
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(10000)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(httpDataSourceFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val player = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .build().apply {
                volume = 1.0f

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                _isBuffering.value = true
                                val stationName = _currentStation.value?.name ?: "estacion"
                                _statusText.value = "Sintonizando $stationName..."
                                Log.i(TAG, "ExoPlayer buffering: $stationName")
                            }
                            Player.STATE_READY -> {
                                _isBuffering.value = false
                                if (playWhenReady) {
                                    _isPlaying.value = true
                                    _statusText.value = "Transmitiendo en vivo"
                                    Log.i(TAG, "ExoPlayer playing live: ${_currentStation.value?.name}")
                                }
                            }
                            Player.STATE_ENDED -> {
                                _isPlaying.value = false
                                _isBuffering.value = false
                                _statusText.value = "Transmision finalizada"
                            }
                            Player.STATE_IDLE -> {
                                _isPlaying.value = false
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlaying.value = isPlaying
                        if (isPlaying) {
                            _isBuffering.value = false
                            _statusText.value = "Transmitiendo en vivo"
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "ExoPlayer error on station ${_currentStation.value?.name}: ${error.message}", error)
                        _isBuffering.value = false
                        _isPlaying.value = false
                        _statusText.value = "Error al sintonizar stream online"
                    }
                })
            }

        exoPlayer = player
        return player
    }

    /**
     * Sintoniza y reproduce una estacion de radio FM / Streaming via Internet.
     */
    fun playStation(context: Context, station: RadioStation) {
        try {
            // Pausar cualquier reproductor local previo
            XLocalPlayer.pause()

            // Asegurar volumen audible si estaba en 0
            ensureAudibleVolume(context)

            _currentStation.value = station
            _isBuffering.value = true
            _isPlaying.value = false
            _statusText.value = "Sintonizando ${station.name}..."

            val player = getOrCreatePlayer(context)

            val mediaItem = MediaItem.fromUri(station.streamUrl)
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true

            Log.i(TAG, "Starting ExoPlayer for station: ${station.name} (URL: ${station.streamUrl})")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing station: ${station.name}", e)
            _isBuffering.value = false
            _isPlaying.value = false
            _statusText.value = "No se pudo sintonizar la estacion"
        }
    }

    /**
     * Pausa la transmision actual.
     */
    fun pause() {
        try {
            exoPlayer?.pause()
            _isPlaying.value = false
            _isBuffering.value = false
            _statusText.value = "Transmision pausada"
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing radio", e)
        }
    }

    /**
     * Reanuda la transmision si hay una estacion activa.
     */
    fun resume(context: Context) {
        val current = _currentStation.value ?: return
        if (exoPlayer != null) {
            ensureAudibleVolume(context)
            exoPlayer?.play()
            _isPlaying.value = true
            _statusText.value = "Transmitiendo en vivo"
        } else {
            playStation(context, current)
        }
    }

    /**
     * Alterna la reproduccion de una estacion dada.
     */
    fun togglePlay(context: Context, station: RadioStation) {
        if (_currentStation.value?.id == station.id) {
            if (_isPlaying.value) {
                pause()
            } else {
                resume(context)
            }
        } else {
            playStation(context, station)
        }
    }

    /**
     * Detiene por completo la radio y libera los recursos del reproductor.
     */
    fun stop() {
        try {
            exoPlayer?.stop()
            exoPlayer?.release()
            exoPlayer = null
            _isPlaying.value = false
            _isBuffering.value = false
            _currentStation.value = null
            _statusText.value = "Listo para sintonizar"
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping radio", e)
        }
    }

    private fun ensureAudibleVolume(context: Context) {
        if (audioManager == null) {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
        val am = audioManager ?: return
        try {
            val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (currentVol == 0) {
                val targetVol = (maxVol * 0.7f).toInt().coerceAtLeast(1)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
                Log.i(TAG, "Unmuted media stream volume to $targetVol/$maxVol")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not check or unmute media volume: ${e.message}")
        }
    }
}
