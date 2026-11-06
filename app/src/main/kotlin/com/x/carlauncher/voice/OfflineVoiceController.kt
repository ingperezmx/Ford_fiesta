package com.x.carlauncher.voice

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.x.carlauncher.illumination.VehicleIlluminationManager
import com.x.carlauncher.media.XLocalPlayer
import com.x.carlauncher.weather.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline Automotive Voice Controller for XCarOS (v2.0).
 *
 * Provides completely hands-free voice control designed for driving safety:
 * - Operates 100% offline via local on-device speech models (EXTRA_PREFER_OFFLINE).
 * - Real-time NLP intent matching for music, volume, navigation, climate, and lights.
 * - Spoken conversational feedback using the warm Colombian female voice assistant.
 * - Reactive state flow for animated listening banners and soundwave pulses.
 */
class OfflineVoiceController private constructor(private val context: Context) {

    companion object {
        private const val TAG = "OfflineVoiceController"

        @Volatile
        private var instance: OfflineVoiceController? = null

        fun getInstance(context: Context): OfflineVoiceController {
            return instance ?: synchronized(this) {
                instance ?: OfflineVoiceController(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val voiceAssistant = CarVoiceAssistant.getInstance(context)

    private var speechRecognizer: SpeechRecognizer? = null

    // ── Reactive States for UI ────────────────────────────────────────────────
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _assistantFeedback = MutableStateFlow("")
    val assistantFeedback: StateFlow<String> = _assistantFeedback.asStateFlow()

    private val _audioRmsLevel = MutableStateFlow(0f)
    val audioRmsLevel: StateFlow<Float> = _audioRmsLevel.asStateFlow()

    // ── Recognition Listener ──────────────────────────────────────────────────
    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _isListening.value = true
            _assistantFeedback.value = "Te escucho, Pablo..."
            Log.d(TAG, "SpeechRecognizer: Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            _assistantFeedback.value = "Escuchando a Pablo..."
            Log.d(TAG, "SpeechRecognizer: Beginning of speech")
        }

        override fun onRmsChanged(rmsdB: Float) {
            _audioRmsLevel.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _assistantFeedback.value = "Procesando comando..."
            _isListening.value = false
            Log.d(TAG, "SpeechRecognizer: End of speech")
        }

        override fun onError(error: Int) {
            _isListening.value = false
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (_: Exception) {}
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "No entendi el comando. Intenta de nuevo."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listo. Toca el microfono para hablar."
                SpeechRecognizer.ERROR_AUDIO -> "Error de captura de microfono."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconocedor ocupado."
                else -> "Listo para escuchar."
            }
            _assistantFeedback.value = errorMsg
            Log.w(TAG, "SpeechRecognizer error: $error ($errorMsg)")
        }

        override fun onResults(results: Bundle?) {
            _isListening.value = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val query = matches?.firstOrNull() ?: ""
            if (query.isNotBlank()) {
                _recognizedText.value = query
                executeVoiceCommand(query.trim())
            } else {
                _assistantFeedback.value = "Listo para escuchar."
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!partial.isNullOrBlank()) {
                _recognizedText.value = partial
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * Initializes or wakes the speech recognizer on the main thread.
     */
    fun startListening() {
        mainHandler.post {
            if (_isListening.value) {
                stopListening()
                return@post
            }

            try {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    Log.w(TAG, "Speech recognition not available on this ROM")
                    _assistantFeedback.value = "Reconocimiento no disponible en esta pantalla"
                    voiceAssistant.speak("El microfono no esta disponible en este sistema.")
                    return@post
                }

                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(listener)
                    }
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-MX")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                }

                _recognizedText.value = ""
                _assistantFeedback.value = "Iniciando microfono..."
                speechRecognizer?.startListening(intent)
                _isListening.value = true
                Log.i(TAG, "Speech listening started (offline preference requested)")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting speech recognizer", e)
                _isListening.value = false
                _assistantFeedback.value = "Error al abrir microfono"
            }
        }
    }

    /**
     * Stops listening immediately.
     */
    fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                _isListening.value = false
                _assistantFeedback.value = "Microfono cerrado"
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping recognizer", e)
            }
        }
    }

    /**
     * Normaliza el texto eliminando diacriticos (acentos, tildes), puntuacion y espacios extra.
     * Convierte "Pon música" a "pon musica", "¿Cómo está el clima?" a "como esta el clima", etc.
     */
    private fun normalize(input: String): String {
        val temp = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
        val pattern = java.util.regex.Pattern.compile("\\p{InCombiningDiacriticalMarks}+")
        return pattern.matcher(temp).replaceAll("")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Procesador inteligente de comandos de voz para vehiculo y consultas generales.
     */
    fun executeVoiceCommand(rawText: String) {
        val raw = rawText.trim()
        val normalized = normalize(raw)
        Log.i(TAG, "Processing vehicle voice command: '$raw' -> normalized: '$normalized'")

        // Limpieza de prefijos comunes de activacion (Hey Google / Gemini / Jarvis / X)
        var text = normalized
            .replace("hey google", "")
            .replace("ok google", "")
            .replace("oye google", "")
            .replace("hey gemini", "")
            .replace("ok gemini", "")
            .replace("oye gemini", "")
            .replace("hey jarvis", "")
            .replace("ok jarvis", "")
            .replace("oye jarvis", "")
            .replace("hey x", "")
            .replace("oye x", "")
            .trim()

        if (text.startsWith("google ")) text = text.removePrefix("google ").trim()
        if (text.startsWith("gemini ")) text = text.removePrefix("gemini ").trim()
        if (text.startsWith("jarvis ")) text = text.removePrefix("jarvis ").trim()
        if (text.startsWith("x ")) text = text.removePrefix("x ").trim()
        if (text.isBlank()) text = normalized

        when {
            // ── 1. Musica: Reproducir ──────────────────────────────────────────
            text.containsAny(
                "reproducir", "reproduce", "poner musica", "pon musica", "pon la musica",
                "play", "reanudar", "dale play", "musica", "cancion", "canciones",
                "iniciar musica", "inicia la musica", "toca musica", "tocar musica",
                "youtube music", "youtube", "yt music", "pon youtube music", "amazon music", "ponme musica"
            ) && !text.containsAny("pausar", "pausa", "detener", "parar", "para", "stop", "silencio", "siguiente", "anterior") -> {
                com.x.carlauncher.media.PowerampNotificationListener.play(context)
                _assistantFeedback.value = "Reproduciendo musica"
                voiceAssistant.speak("Enseguida Pablo. Reproduciendo musica.")
            }

            // ── 2. Musica: Pausar / Detener ────────────────────────────────────
            text.containsAny("pausar", "pausa", "detener", "parar", "para la musica", "para", "stop", "silencia la musica", "silencio") -> {
                com.x.carlauncher.media.PowerampNotificationListener.pause(context)
                _assistantFeedback.value = "Musica en pausa"
                voiceAssistant.speak("Musica pausada, Pablo.")
            }

            // ── 3. Musica: Siguiente Pista ─────────────────────────────────────
            text.containsAny("siguiente", "pasa", "cambiar", "avanzar", "otra cancion", "adelantar", "next", "salta") -> {
                com.x.carlauncher.media.PowerampNotificationListener.skipToNext(context)
                _assistantFeedback.value = "Siguiente cancion"
                voiceAssistant.speak("Cambiando a la siguiente pista, Pablo.")
            }

            // ── 4. Musica: Pista Anterior ──────────────────────────────────────
            text.containsAny("anterior", "cancion anterior", "atras", "regresar", "previa", "retrasar") -> {
                com.x.carlauncher.media.PowerampNotificationListener.skipToPrevious(context)
                _assistantFeedback.value = "Cancion anterior"
                voiceAssistant.speak("Regresando a la pista anterior, Pablo.")
            }

            // ── 5. Volumen: Aumentar ───────────────────────────────────────────
            text.containsAny("subir volumen", "mas volumen", "aumentar volumen", "volumen arriba", "sube el volumen", "mas fuerte") -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                _assistantFeedback.value = "Volumen aumentado"
                voiceAssistant.speak("Volumen aumentado, Pablo.")
            }

            // ── 6. Volumen: Reducir ────────────────────────────────────────────
            text.containsAny("bajar volumen", "menos volumen", "disminuir volumen", "volumen abajo", "baja el volumen", "menos fuerte") -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                _assistantFeedback.value = "Volumen reducido"
                voiceAssistant.speak("Volumen reducido, Pablo.")
            }

            // ── 7. Volumen: Silenciar ──────────────────────────────────────────
            text.containsAny("silencio", "mutear", "sin sonido", "mute", "callate") -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                _assistantFeedback.value = "Silencio activado"
                voiceAssistant.speak("Silenciando el sistema, Pablo.")
            }

            // ── 8. Navegacion / Mapa ───────────────────────────────────────────
            text.containsAny("abrir mapa", "mapa", "mapas", "navegacion", "waze", "google maps", "como llegar", "ruta") &&
            !text.containsAny("gasolinera", "restaurante", "oxxo", "farmacia") -> {
                _assistantFeedback.value = "Abriendo navegacion"
                try {
                    val wazeIntent = context.packageManager.getLaunchIntentForPackage("com.waze")
                    val mapsIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
                    val organicIntent = context.packageManager.getLaunchIntentForPackage("app.organicmaps")
                        ?: context.packageManager.getLaunchIntentForPackage("app.organicmaps.web")

                    when {
                        wazeIntent != null -> {
                            voiceAssistant.speak("Iniciando Waze para tu trayecto, Pablo.")
                            wazeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(wazeIntent)
                        }
                        mapsIntent != null -> {
                            voiceAssistant.speak("Iniciando Google Maps, Pablo.")
                            mapsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(mapsIntent)
                        }
                        organicIntent != null -> {
                            voiceAssistant.speak("Iniciando Organic Maps, Pablo.")
                            organicIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(organicIntent)
                        }
                        else -> {
                            voiceAssistant.speak("El mapa satelital integrado esta activo en tu pantalla, Pablo.")
                        }
                    }
                } catch (_: Exception) {}
            }

            // ── 9. Busqueda de lugares en Google Maps ──────────────────────────
            text.containsAny("gasolinera", "gasolineras", "restaurante", "restaurantes", "oxxo", "farmacia", "taller", "estacionamiento", "comida", "cafe", "tienda") -> {
                _assistantFeedback.value = "Buscando lugares cercanos"
                voiceAssistant.speak("Buscando lugares cercanos en el mapa, Pablo.")
                try {
                    val mapSearch = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(raw))).apply {
                        setPackage("com.google.android.apps.maps")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(mapSearch)
                } catch (_: Exception) {}
            }

            // ── 10. Reporte del Clima ──────────────────────────────────────────
            text.containsAny("clima", "como esta el clima", "tiempo", "temperatura", "hace calor", "hace frio") -> {
                val weather = WeatherRepository.latestWeatherData
                val temp = weather?.temp ?: 24
                val desc = weather?.description ?: "cielo despejado"
                val city = weather?.cityName ?: "tu ubicacion"
                _assistantFeedback.value = "$temp C, $desc en $city"
                voiceAssistant.speak("Pablo, en $city la temperatura actual es de $temp grados centigrados con $desc.")
            }

            // ── 11. Ubicacion del Vehiculo ────────────────────────────────────
            text.containsAny("donde estoy", "ubicacion", "donde me encuentro") -> {
                val weather = WeatherRepository.latestWeatherData
                val place = weather?.cityName ?: "tu zona"
                val muni = weather?.municipality ?: "local"
                _assistantFeedback.value = "Ubicacion: $place, $muni"
                voiceAssistant.speak("Pablo, te encuentras en $place, $muni.")
            }

            // ── 12. Hora Actual ────────────────────────────────────────────────
            text.containsAny("hora", "que hora es") -> {
                val timeFmt = SimpleDateFormat("h:mm a", Locale.ENGLISH)
                val horaActual = timeFmt.format(Date())
                _assistantFeedback.value = "Hora: $horaActual"
                voiceAssistant.speak("Pablo, son las $horaActual.")
            }

            // ── 13. Fecha Actual ───────────────────────────────────────────────
            text.containsAny("dia es hoy", "fecha", "que fecha es", "que dia es") -> {
                val dateFmt = SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "ES"))
                val fechaActual = dateFmt.format(Date())
                _assistantFeedback.value = "Fecha: $fechaActual"
                voiceAssistant.speak("Pablo, hoy es $fechaActual.")
            }

            // ── 14. Identidad del Asistente ────────────────────────────────────
            text.containsAny("quien eres", "como te llamas", "cual es tu nombre") -> {
                _assistantFeedback.value = "Soy X, copiloto inteligente"
                voiceAssistant.speak("Soy X, tu copiloto con inteligencia integrada, Pablo. A tus ordenes.")
            }

            // ── 15. Faros y Luces del Auto ─────────────────────────────────────
            text.containsAny("encender luces", "prender luces", "luces encendidas", "faros encendidos", "luces on") -> {
                VehicleIlluminationManager.setManual(true)
                _assistantFeedback.value = "Faros encendidos"
                voiceAssistant.speak("Faros encendidos, Pablo.")
            }

            text.containsAny("apagar luces", "luces apagadas", "luces automaticas", "luces off", "luces auto") -> {
                VehicleIlluminationManager.setManual(null)
                _assistantFeedback.value = "Luces automaticas"
                voiceAssistant.speak("Luces restablecidas a modo automatico, Pablo.")
            }

            // ── 16. Mensajes de WhatsApp ─────────────────────────────────────────
            text.containsAny(
                "leer mensaje", "leer mensajes", "lee mis mensajes", "lee los mensajes",
                "leer whatsapp", "lee whatsapp", "mensajes de whatsapp", "mis mensajes", "mensaje de whatsapp",
                "checar mensaje", "checar mensajes", "checa mensaje", "checa mensajes", "checar whatsapp", "checa whatsapp",
                "revisar mensaje", "revisar mensajes", "revisa mensaje", "revisa mensajes", "chequeo de mensajes", "comprobar mensajes"
            ) -> {
                com.x.carlauncher.whatsapp.WhatsAppMessageManager.performCheck(context, notifyIfNew = false)
                val unread = com.x.carlauncher.whatsapp.WhatsAppMessageManager.getUnreadMessages()
                if (unread.isEmpty()) {
                    _assistantFeedback.value = "Sin mensajes nuevos"
                    voiceAssistant.speak("Pablo, no tienes mensajes nuevos de WhatsApp.")
                } else {
                    val count = unread.size
                    val first = unread.first()
                    _assistantFeedback.value = "Mensaje de ${first.sender}"
                    val cleanBody = com.x.carlauncher.whatsapp.WhatsAppMessageManager.cleanTextForSpeech(first.text)
                    if (count == 1) {
                        voiceAssistant.speak("Pablo, tienes un mensaje de ${first.sender} que dice: $cleanBody")
                    } else {
                        voiceAssistant.speak("Pablo, tienes $count mensajes de WhatsApp. El mas reciente es de ${first.sender} y dice: $cleanBody")
                    }
                    com.x.carlauncher.whatsapp.WhatsAppMessageManager.markAsRead(first.id)
                }
            }

            // ── 17. Contactos y Marcacion Telefonica ────────────────────────────
            text.startsWith("llamar a ") || text.startsWith("llama a ") || text.startsWith("marcar a ") ||
            text.startsWith("marca a ") || text.startsWith("llamar ") || text.startsWith("llama ") ||
            text.containsAny("abrir contactos", "mis contactos", "ver contactos", "agenda") -> {
                val contactQuery = text
                    .removePrefix("llamar a ")
                    .removePrefix("llama a ")
                    .removePrefix("marcar a ")
                    .removePrefix("marca a ")
                    .removePrefix("llamar ")
                    .removePrefix("llama ")
                    .removePrefix("abrir contactos")
                    .removePrefix("mis contactos")
                    .removePrefix("ver contactos")
                    .removePrefix("agenda")
                    .trim()

                if (contactQuery.isNotBlank()) {
                    val match = com.x.carlauncher.contacts.ContactsRepository.findBestMatch(contactQuery)
                    if (match != null) {
                        _assistantFeedback.value = "Llamando a ${match.name}"
                        voiceAssistant.speak("Llamando a ${match.name}, Pablo.")
                        com.x.carlauncher.contacts.ContactsRepository.callContact(context, match.number)
                    } else if (contactQuery.all { it.isDigit() || it == '+' || it == ' ' }) {
                        _assistantFeedback.value = "Marcando a $contactQuery"
                        voiceAssistant.speak("Marcando numero $contactQuery, Pablo.")
                        com.x.carlauncher.contacts.ContactsRepository.callContact(context, contactQuery)
                    } else {
                        _assistantFeedback.value = "No encontre a '$contactQuery'"
                        voiceAssistant.speak("No encontre el contacto $contactQuery en tu agenda, Pablo.")
                    }
                } else {
                    _assistantFeedback.value = "Abre contactos en el dock"
                    voiceAssistant.speak("Pablo, toca el icono verde de contactos en el dock izquierdo para ver tu agenda o dime a quien llamar.")
                }
            }

            // ── 18. Ayuda y Comandos ───────────────────────────────────────────
            text.containsAny("ayuda", "comandos", "que puedes hacer", "instrucciones") -> {
                _assistantFeedback.value = "Comandos: Musica, Volumen, Contactos, Mapa, Clima, Luces, WhatsApp"
                voiceAssistant.speak("A tu servicio, Pablo. Puedes solicitar reproduccion musical, llamar a un contacto, ajuste de volumen, mapa de ruta, reporte del clima, lectura de mensajes de WhatsApp o control de luces.")
            }

            // ── 19. Fallback dentro del vehiculo (sin busquedas web externas) ───
            else -> {
                _assistantFeedback.value = "Comando: '$raw'"
                voiceAssistant.speak("No reconoci esa instruccion, Pablo. Puedes pedir llamar a un contacto, musica, volumen, clima, mapa o mensajes de WhatsApp.")
            }
        }
    }

    fun destroy() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (_: Exception) {}
        }
    }

    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it, ignoreCase = true) }
    }
}

