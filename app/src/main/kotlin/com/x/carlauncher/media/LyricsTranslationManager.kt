package com.x.carlauncher.media

import android.content.Context
import android.util.Log
import com.x.carlauncher.system.NetworkUtils
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Intelligent Bilingual Lyrics Translation Pipeline for XCarOS2.
 * - Detects if lyrics are already in Spanish (no duplicate translation needed).
 * - High-speed batch translation for English/foreign lyrics.
 * - Persistent offline disk cache in `lyrics_translations.json`.
 */
object LyricsTranslationManager {

    private const val TAG = "LyricsTranslation"
    private const val CACHE_FILE = "lyrics_translations.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // Key: lowercase English text -> Value: Spanish translation
    private val memoryCache = ConcurrentHashMap<String, String>()
    private var isInitialized = false
    private var cacheFile: File? = null

    // Common Spanish word markers for offline detection
    private val spanishMarkers = setOf(
        "de", "la", "que", "el", "en", "y", "a", "los", "del", "se", "las", "por", "un",
        "para", "con", "no", "una", "su", "al", "lo", "como", "mas", "pero", "sus", "le",
        "ya", "o", "porque", "cuando", "muy", "sin", "sobre", "me", "te", "mi", "tu", "todo",
        "nada", "donde", "si", "yo", "ella", "el", "amor", "corazon", "vida", "noche", "tiempo",
        "siento", "quiero", "estoy", "esta", "somos", "eres", "tengo", "tiene", "pa", "tu", "mi"
    )

    // Pre-seeded offline automotive translation vocabulary (Spanish <-> English)
    private val offlineVocabulary = mapOf(
        // Spanish -> English
        "te amo" to "I love you",
        "mi amor" to "my love",
        "mi corazon" to "my heart",
        "te quiero" to "I care for you",
        "no puedo olvidar" to "I cannot forget",
        "sin tu amor" to "without your love",
        "la noche" to "the night",
        "junto a ti" to "next to you",
        "donde estas" to "where are you",
        "tu y yo" to "you and I",
        "por siempre" to "forever",
        "me haces falta" to "I miss you",
        "me importas tu" to "you matter to me",
        "ojos negros" to "dark eyes",
        "piel canela" to "cinnamon skin",
        "se supone" to "supposed to be",
        "deberia haberte olvidado" to "I should have forgotten you",
        "si te pudiera olvidar" to "if I could forget you",
        "tenlo por seguro" to "be rest assured",
        "ya no habria nada tuyo" to "there'd be nothing left of you",
        "tengo que colgar" to "I have to hang up",
        "no me quieres" to "you don't love me",
        "sabor a mi" to "taste of me",
        "besame mucho" to "kiss me a lot",
        "historia de un amor" to "story of a love",
        "a puro dolor" to "in pure pain",
        "solamente una vez" to "only once",
        "somos novios" to "we are sweethearts",
        "contigo aprendi" to "with you I learned",
        "reloj no marques las horas" to "clock don't tick the hours",
        // English -> Spanish
        "i love you" to "te amo",
        "my love" to "mi amor",
        "my heart" to "mi corazon",
        "forever" to "por siempre",
        "without you" to "sin ti",
        "don't go" to "no te vayas",
        "i miss you" to "te extrano",
        "let me know" to "dejame saber",
        "stay with me" to "quedate conmigo",
        "hold my hand" to "toma mi mano",
        "all night long" to "toda la noche",
        "take me away" to "llevame lejos",
        "i need you" to "te necesito"
    )

    fun initialize(context: Context) {
        if (isInitialized) return
        isInitialized = true
        cacheFile = File(context.filesDir, CACHE_FILE)

        // Seed with built-in offline vocabulary immediately (0ms)
        offlineVocabulary.forEach { (k, v) ->
            memoryCache.putIfAbsent(k.lowercase().trim(), v)
        }

        scope.launch {
            try {
                val f = cacheFile ?: return@launch
                if (f.exists()) {
                    val json = JSONObject(f.readText())
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        memoryCache[key.lowercase().trim()] = json.getString(key)
                    }
                    Log.d(TAG, "Loaded ${memoryCache.size} offline translations from cache")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed loading offline translation cache", e)
            }
        }
    }

    /**
     * Determines whether the given lyrics collection is in Spanish.
     */
    fun isSongInSpanish(lyrics: List<LyricEntry>): Boolean {
        if (lyrics.isEmpty()) return false

        // Check for Spanish specific characters (a, e, i, o, u, n, ¿, ¡)
        val textSample = lyrics.take(25).joinToString(" ") { it.text.lowercase() }
        val spanishChars = listOf('a', 'e', 'i', 'o', 'u', 'n', '¿', '¡')
        if (spanishChars.any { textSample.contains(it) }) {
            return true
        }

        // Tokenize words and count Spanish marker words
        val words = textSample.split(Regex("[\\s.,!?;:\"'()]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return false

        val markerHits = words.count { it in spanishMarkers }
        val ratio = markerHits.toFloat() / words.size.toFloat()
        return ratio > 0.16f
    }

    fun getTranslationSync(rawText: String): String? {
        val clean = rawText.lowercase().trim()
        if (clean.isBlank()) return null
        return memoryCache[clean]
    }

    /**
     * Enriches a list of lyrics with bidirectional translations:
     * - Spanish songs -> Translated to English (ES -> EN).
     * - English/foreign songs -> Translated to Spanish (EN -> ES).
     * Works 100% offline using disk cache and embedded automotive vocabulary.
     */
    suspend fun enrichLyricsWithTranslation(
        context: Context,
        lyrics: List<LyricEntry>
    ): List<LyricEntry> = withContext(Dispatchers.IO) {
        initialize(context)
        if (lyrics.isEmpty()) return@withContext lyrics

        val isSpanish = isSongInSpanish(lyrics)
        val sourceLang = if (isSpanish) "es" else "auto"
        val targetLang = if (isSpanish) "en" else "es"

        // 1. Fast pass: Check memory & offline cache (0ms)
        val missingLines = mutableListOf<String>()
        val result = lyrics.map { entry ->
            val clean = entry.text.lowercase().trim()
            val cached = memoryCache[clean]
            if (cached == null && clean.isNotBlank() && clean.length > 1) {
                missingLines.add(entry.text)
            }
            entry.copy(translation = cached)
        }.toMutableList()

        if (missingLines.isEmpty() || !NetworkUtils.isOnline(context)) {
            return@withContext result
        }

        // 2. Online batch translation for missing lines
        try {
            val chunks = missingLines.chunked(35)
            var diskCacheModified = false

            for (chunk in chunks) {
                val combined = chunk.joinToString("\n")
                val encoded = URLEncoder.encode(combined, "UTF-8")
                val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=$encoded"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                    .header("Accept", "*/*")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body()?.string() ?: return@use
                        val jsonArr = JSONArray(body)
                        val sentences = jsonArr.optJSONArray(0) ?: return@use
                        val sb = StringBuilder()
                        for (i in 0 until sentences.length()) {
                            val part = sentences.getJSONArray(i).getString(0)
                            sb.append(part)
                        }

                        val translatedParts = sb.toString().split("\n")
                        for (i in chunk.indices) {
                            val orig = chunk[i].lowercase().trim()
                            val trans = translatedParts.getOrNull(i)?.trim()
                            if (!trans.isNullOrBlank()) {
                                memoryCache[orig] = trans
                                diskCacheModified = true
                            }
                        }
                    }
                }
            }

            if (diskCacheModified) {
                saveCacheToDisk()
            }

            // Re-apply translations
            return@withContext lyrics.map { entry ->
                val clean = entry.text.lowercase().trim()
                val trans = memoryCache[clean]
                entry.copy(translation = trans)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Batch translation failed: ${e.message}")
        }

        return@withContext result
    }

    private fun saveCacheToDisk() {
        try {
            val f = cacheFile ?: return
            val json = JSONObject()
            memoryCache.forEach { (k, v) ->
                json.put(k, v)
            }
            f.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing translation cache to disk", e)
        }
    }
}
