package com.x.carlauncher.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.x.carlauncher.canbus.DoorState
import com.x.carlauncher.weather.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Asistente de Voz Automotriz para XCarOS con personalidad oficial JARVIS en Espanol Latino.
 *
 * Caracteristicas configuradas para Pablo:
 * 1. VOZ MASCULINA JARVIS:
 *    - Tono masculino, sereno, articulado, elegante y fluido.
 *    - Pitch ajustado a 0.90f (resonancia masculina profunda y formal de mayordomo IA).
 *    - Velocidad ajustada a 0.98f (cadencia conversacional natural y fluida).
 *    - Seleccion prioritaria de modelos neuronales masculinos en Espanol Latino (es-MX / es-US).
 * 2. PERSONALIDAD Y DIALOGOS JARVIS:
 *    - Saludo oficial: "A su servicio, senor Pablo. Buenos dias. Todos los sistemas del Ford Fiesta estan en linea. Buen viaje."
 *    - Alertas de puertas con "Atencion".
 *    - Avisos respetuosos y sobrios.
 *
 * Todo en puro formato ASCII sin acentos ni caracteres especiales.
 */
class CarVoiceAssistant(private val context: Context) {

    companion object {
        private const val TAG = "CarVoiceAssistant"
        private const val UTTERANCE_ID = "x_car_voice"

        @Volatile
        private var instance: CarVoiceAssistant? = null

        /**
         * Flag persistente por ciclo de ignicion.
         * Garantiza que el saludo se emita una sola vez por viaje.
         */
        @Volatile
        var hasGreetedThisIgnition: Boolean = false

        fun getInstance(context: Context): CarVoiceAssistant {
            return instance ?: synchronized(this) {
                instance ?: CarVoiceAssistant(context.applicationContext).also { instance = it }
            }
        }
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingGreeting = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    // Control de debounce
    private var lastSpokenText: String = ""
    private var lastSpokenTimestamp: Long = 0L

    // Coroutine scope para recordatorio de cinturon y clima (1m 30s)
    private val assistantScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var beltJob: Job? = null

    init {
        initTts(context)
    }

    private fun initTts(context: Context) {
        tts = TextToSpeech(context, { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureVoice()
                isInitialized = true
                Log.i(TAG, "Google TTS inicializado con exito con perfil masculino JARVIS Latino")
                if (pendingGreeting) {
                    greetX()
                    pendingGreeting = false
                }
            } else {
                Log.e(TAG, "Fallo inicializacion Google TTS, intentando motor predeterminado")
                tts = TextToSpeech(context) { fallbackStatus ->
                    if (fallbackStatus == TextToSpeech.SUCCESS) {
                        configureVoice()
                        isInitialized = true
                        Log.i(TAG, "TTS de respaldo inicializado con exito")
                        if (pendingGreeting) {
                            greetX()
                            pendingGreeting = false
                        }
                    }
                }
            }
        }, "com.google.android.tts")
    }

    private fun configureVoice() {
        val engine = tts ?: return

        // 1. Idioma: Espanol Latino (es-MX es el doblaje oficial de Marvel para Latinoamerica)
        val localeLatino = Locale("es", "MX")
        var langResult = engine.setLanguage(localeLatino)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            val localeUS = Locale("es", "US")
            langResult = engine.setLanguage(localeUS)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale("es", "ES"))
            }
        }

        // 2. Tono y cadencia oficiales de JARVIS (masculino, profundo, sereno y fluido)
        engine.setPitch(0.90f)        // Tono grave, elegante y sobrio
        engine.setSpeechRate(0.98f)   // Cadencia conversacional natural y fluida

        // 3. Atributos de audio para reproduccion en bocinas del vehiculo
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        engine.setAudioAttributes(audioAttributes)

        // 4. Listener para restaurar volumen y abandonar foco de audio al terminar de hablar
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                abandonFocusAfterSpeech()
            }
            override fun onError(utteranceId: String?) {
                abandonFocusAfterSpeech()
            }
        })

        // 5. Seleccion inteligente de la mejor voz masculina en Espanol Latino
        try {
            val voices = engine.voices
            if (!voices.isNullOrEmpty()) {
                val spanishVoices = voices.filter { v ->
                    v.locale.language.equals("es", ignoreCase = true)
                }

                for (v in spanishVoices) {
                    Log.d(TAG, "Voz disponible: ${v.name} (${v.locale}) quality=${v.quality}")
                }

                // Filtrar estrictamente voces masculinas (descartar tags femeninos)
                val maleVoices = spanishVoices.filter { v ->
                    val name = v.name.lowercase()
                    !name.contains("female") && !name.contains("fem") && !name.contains("woman") && !name.contains("zira")
                }

                // Clasificar la mejor voz para JARVIS:
                // Prioridad 1: Espanol Latino (MX o US) con etiqueta explicita male
                // Prioridad 2: Espanol Latino sin etiqueta femenina
                // Prioridad 3: Alta calidad neuronal (network o quality alta)
                val selectedVoice = maleVoices.maxByOrNull { v ->
                    val name = v.name.lowercase()
                    var score = 0
                    if (name.contains("male") || name.contains("man")) score += 50
                    if (v.locale.country.equals("MX", ignoreCase = true)) score += 40
                    if (v.locale.country.equals("US", ignoreCase = true)) score += 30
                    if (name.contains("network")) score += 20
                    if (v.quality == Voice.QUALITY_VERY_HIGH) score += 20
                    else if (v.quality == Voice.QUALITY_HIGH) score += 10
                    score
                } ?: spanishVoices.firstOrNull { v ->
                    val name = v.name.lowercase()
                    name.contains("male")
                } ?: maleVoices.firstOrNull()

                if (selectedVoice != null) {
                    engine.voice = selectedVoice
                    Log.i(TAG, "Voz oficial JARVIS asignada: ${selectedVoice.name} (${selectedVoice.locale})")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error seleccionando voz especifica JARVIS: ${e.message}")
        }
    }

    private fun requestFocusForSpeech() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(playbackAttrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* Manejado por el sistema */ }
                    .build()
                audioFocusRequest = focusReq
                audioManager?.requestAudioFocus(focusReq)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus request: ${e.message}")
        }
    }

    private fun abandonFocusAfterSpeech() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus abandon: ${e.message}")
        }
    }

    /**
     * Emite la frase dada con la voz masculina de JARVIS, atenuando temporalmente la musica.
     */
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        val now = System.currentTimeMillis()
        if (text == lastSpokenText && (now - lastSpokenTimestamp) < 2500L) {
            return
        }

        lastSpokenText = text
        lastSpokenTimestamp = now

        if (!isInitialized) {
            Log.d(TAG, "TTS en proceso de inicializacion, mensaje pendiente: $text")
            return
        }

        try {
            requestFocusForSpeech()
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID)
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            tts?.speak(text, queueMode, params, UTTERANCE_ID)
            Log.d(TAG, "JARVIS hablando: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Error durante la sintesis de voz", e)
            abandonFocusAfterSpeech()
        }
    }

    /**
     * Saludo al arrancar o despertar el vehiculo:
     * Saludo con voz deshabilitado por solicitud del usuario.
     */
    fun onVehicleStartupOrWake(weatherDescription: String? = null, weatherTemp: Int? = null) {
        hasGreetedThisIgnition = true
        beltJob?.cancel()
        Log.i(TAG, "Ignicion detectada: saludo de voz omitido por configuracion")
    }

    /**
     * Al apagar el vehiculo o entrar en reposo (ACC OFF).
     */
    fun onVehiclePowerOff() {
        Log.i(TAG, "Vehiculo apagado: reiniciando flag para el siguiente encendido")
        hasGreetedThisIgnition = false
        beltJob?.cancel()
    }

    fun onScreenWakeOrResume(weatherDescription: String? = null, weatherTemp: Int? = null) {
        onVehicleStartupOrWake(weatherDescription, weatherTemp)
    }

    /**
     * Saludo de bienvenida estilo JARVIS con voz (deshabilitado por solicitud de Pablo).
     */
    fun greetX() {
        pendingGreeting = false
        Log.i(TAG, "Saludo con voz greetX omitido por configuracion")
    }

    /**
     * Aviso de seguridad y reporte climatico cordial a los 90 segundos.
     */
    fun announceBeltAndWeather(description: String = "", temp: Int = 0) {
        val weatherClause = if (description.isNotBlank() && temp != 0) {
            "El pronostico para hoy indica $description, con una temperatura de $temp grados centigrados."
        } else {
            "Le deseo un excelente trayecto."
        }

        val message = "Pablo, le recuerdo abrochar su cinturon de seguridad. $weatherClause"
        speak(message)
    }

    /**
     * Anuncia apertura de puertas, cofre o cajuela en tiempo real.
     */
    fun onDoorStateTransition(previous: DoorState, current: DoorState) {
        val messages = mutableListOf<String>()

        if (!previous.frontLeft && current.frontLeft) {
            messages.add("Atencion: puerta del conductor abierta")
        }
        if (!previous.frontRight && current.frontRight) {
            messages.add("Atencion: puerta del copiloto abierta")
        }
        if (!previous.rearLeft && current.rearLeft) {
            messages.add("Atencion: puerta trasera izquierda abierta")
        }
        if (!previous.rearRight && current.rearRight) {
            messages.add("Atencion: puerta trasera derecha abierta")
        }
        if (!previous.hood && current.hood) {
            messages.add("Atencion: cofre del motor abierto")
        }
        if (!previous.trunk && current.trunk) {
            messages.add("Atencion: cajuela abierta")
        }

        if (messages.isNotEmpty()) {
            val combined = messages.joinToString(". ")
            speak(combined)
        }
    }

    fun shutdown() {
        beltJob?.cancel()
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
        } catch (e: Exception) {
            Log.w(TAG, "Error apagando TTS", e)
        }
    }
}
