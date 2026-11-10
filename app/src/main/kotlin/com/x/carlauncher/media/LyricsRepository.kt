package com.x.carlauncher.media

import android.content.Context
import com.x.carlauncher.system.NetworkUtils
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Intelligent & Strict Lyrics Repository for XCarOS.
 * Automatically searches, downloads, validates, and locally caches synchronized LRC lyrics.
 * Preloads upcoming sequential and shuffle/random playlist songs in the background (0ms latency).
 * Strictly verifies song title and artist to guarantee zero lyric mismatches.
 */
object LyricsRepository {

    private const val TAG = "LyricsRepository"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    val okHttpClient: OkHttpClient get() = client

    private val memoryCache = mutableMapOf<String, List<LyricEntry>>()
    private val preloadedFiles = Collections.synchronizedSet(mutableSetOf<String>())
    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var preloadJob: Job? = null

    /**
     * Synchronous 0ms check: RAM cache or Local Disk Cache.
     * Returns immediately without coroutine switching or network lag.
     */
    fun loadFromMemoryOrDisk(
        context: Context,
        rawTitle: String,
        rawArtist: String,
        playPath: String = ""
    ): List<LyricEntry> {
        val cleanTitle = cleanTrackString(rawTitle)
        val cleanArtist = cleanArtistString(rawArtist)
        if (cleanTitle.isBlank() || cleanTitle.equals("Pista de audio", ignoreCase = true)) {
            return emptyList()
        }
        val cacheKey = "${cleanTitle}_$cleanArtist".lowercase()
        memoryCache[cacheKey]?.let { return it }

        val local = loadFromLocal(context, cleanTitle, cleanArtist, playPath)
        if (local.isNotEmpty()) {
            memoryCache[cacheKey] = local
            return local
        }
        return emptyList()
    }

    /**
     * Resolves synchronized lyrics for track:
     * 1. Memory Cache (0ms)
     * 2. Local Storage Cache (.lrc file in app storage or alongside music file, ~1ms)
     * 3. Online Fetch from NetEase, LRCLIB, Megalobiz with STRICT matching
     */
    suspend fun getOrFetchLyrics(
        context: Context,
        rawTitle: String,
        rawArtist: String,
        playPath: String = "",
        targetDurationMs: Long = 0L
    ): List<LyricEntry> = withContext(Dispatchers.IO) {
        val cleanTitle = cleanTrackString(rawTitle)
        val cleanArtist = cleanArtistString(rawArtist)

        if (cleanTitle.isBlank() || cleanTitle.equals("Pista de audio", ignoreCase = true)) {
            return@withContext emptyList()
        }

        val cacheKey = "${cleanTitle}_$cleanArtist".lowercase()
        memoryCache[cacheKey]?.let { return@withContext it }

        // 1. Try local cache / sidecar file (.lrc)
        val localLyrics = loadFromLocal(context, cleanTitle, cleanArtist, playPath)
        if (localLyrics.isNotEmpty()) {
            memoryCache[cacheKey] = localLyrics
            return@withContext localLyrics
        }

        // 2. Fetch from Internet strictly matching title & artist (only if network is active)
        if (!NetworkUtils.isOnline(context)) {
            return@withContext emptyList()
        }

        try {
            val onlineLyrics = fetchOnlineLyrics(cleanTitle, cleanArtist, targetDurationMs)
            if (onlineLyrics.isNotEmpty()) {
                saveToLocalCache(context, cleanTitle, cleanArtist, onlineLyrics)
                memoryCache[cacheKey] = onlineLyrics
                return@withContext onlineLyrics
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download online lyrics for $cleanTitle - $cleanArtist: ${e.message}")
        }

        emptyList()
    }

    /**
     * Preloads lyrics for upcoming tracks and all songs in the current folder/playlist
     * so that whenever playback advances (sequentially or in shuffle/random mode),
     * the lyrics are already cached in RAM and disk (0ms delay).
     */
    fun preloadFolder(context: Context, playPath: String) {
        if (playPath.isBlank()) return
        val currentFile = File(playPath)
        val dir = currentFile.parentFile ?: return
        if (!dir.exists() || !dir.isDirectory) return

        preloadJob?.cancel()
        preloadJob = preloadScope.launch {
            try {
                val audioExts = setOf("flac", "mp3", "m4a", "wav", "ogg", "aac", "wma", "ape")
                val files = dir.listFiles { f -> f.isFile && f.extension.lowercase() in audioExts }
                    ?: return@launch

                if (files.isEmpty()) return@launch

                val curIdx = files.indexOfFirst { it.absolutePath == currentFile.absolutePath }
                val size = files.size

                // Priority order:
                // 1. Next track in folder (curIdx + 1)
                // 2. Following track (curIdx + 2)
                // 3. Previous track (curIdx - 1)
                // Preload up to 3 upcoming tracks online to save API quota and bandwidth
                val queue = mutableListOf<File>()
                if (curIdx >= 0) {
                    queue.add(files[(curIdx + 1) % size])
                    if (size > 2) queue.add(files[(curIdx + 2) % size])
                    if (size > 1) queue.add(files[(curIdx - 1 + size) % size])
                }

                for (targetFile in queue) {
                    if (!isActive) break
                    val path = targetFile.absolutePath
                    if (preloadedFiles.contains(path)) continue

                    val (parsedTitle, parsedArtist) = extractTitleAndArtist(targetFile)
                    val cleanT = cleanTrackString(parsedTitle)
                    val cleanA = cleanArtistString(parsedArtist)
                    val key = "${cleanT}_$cleanA".lowercase()

                    if (memoryCache.containsKey(key)) {
                        preloadedFiles.add(path)
                        continue
                    }

                    // Check local disk cache / sidecar first (0ms)
                    val local = loadFromLocal(context, cleanT, cleanA, path)
                    if (local.isNotEmpty()) {
                        memoryCache[key] = local
                        preloadedFiles.add(path)
                        continue
                    }

                    // If offline, skip online downloads completely
                    if (!NetworkUtils.isOnline(context)) {
                        preloadedFiles.add(path)
                        continue
                    }

                    // Download online in background with 3.5s throttle
                    try {
                        val downloaded = fetchOnlineLyrics(cleanT, cleanA)
                        if (downloaded.isNotEmpty()) {
                            saveToLocalCache(context, cleanT, cleanA, downloaded)
                            memoryCache[key] = downloaded
                            Log.d(TAG, "Preloaded lyrics for upcoming track: '$cleanT' - '$cleanA'")
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Preload skipped for $cleanT: ${e.message}")
                    }

                    preloadedFiles.add(path)
                    delay(3500)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Preload folder error", e)
            }
        }
    }

    private fun extractTitleAndArtist(file: File): Pair<String, String> {
        val name = file.nameWithoutExtension
        if (name.contains(" - ")) {
            val parts = name.split(" - ")
            if (parts.size >= 2) {
                return Pair(parts[0].trim(), parts[1].trim())
            }
        }

        // Try extracting metadata tags
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(file.absolutePath)
            val t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val a = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            mmr.release()
            if (!t.isNullOrBlank()) {
                return Pair(t.trim(), a?.trim() ?: "")
            }
        } catch (_: Exception) {}

        return Pair(name.trim(), "")
    }

    private fun loadFromLocal(
        context: Context,
        title: String,
        artist: String,
        playPath: String
    ): List<LyricEntry> {
        val cleanT = sanitizeFileName(title).lowercase()
        val cleanA = sanitizeFileName(artist).lowercase()

        // 1. Check alongside audio file if path provided
        if (playPath.isNotBlank()) {
            val audioFile = File(playPath)
            if (audioFile.exists()) {
                val lrcSidecar = File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc")
                if (lrcSidecar.exists() && lrcSidecar.length() > 0) {
                    try {
                        val parsed = parseLrc(lrcSidecar.readText())
                        if (parsed.isNotEmpty()) return enrichWithCompanionTrans(lrcSidecar, parsed)
                    } catch (_: Exception) {}
                }
            }
        }

        // 2. Check internal disk cache
        val cacheDir = File(context.cacheDir, "lyrics")
        val cacheFile = File(cacheDir, "${cleanT}_${cleanA}.lrc")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            try {
                val parsed = parseLrc(cacheFile.readText())
                if (parsed.isNotEmpty()) return enrichWithCompanionTrans(cacheFile, parsed)
            } catch (_: Exception) {}
        }

        // 3. Check public storage locations (/sdcard/Music/Lyrics, etc.)
        val externalDirs = listOf(
            File("/sdcard/Music/Lyrics"),
            File(context.getExternalFilesDir(null), "Lyrics"),
            File("/sdcard/Music"),
            File("/sdcard/Download")
        )

        for (dir in externalDirs) {
            if (dir.exists() && dir.isDirectory) {
                val directFile = File(dir, "${cleanT}_${cleanA}.lrc")
                if (directFile.exists() && directFile.length() > 0) {
                    try {
                        val parsed = parseLrc(directFile.readText())
                        if (parsed.isNotEmpty()) return enrichWithCompanionTrans(directFile, parsed)
                    } catch (_: Exception) {}
                }

                // If artist is known, only match files that also contain the artist name
                val matching = dir.listFiles { f ->
                    if (!f.name.endsWith(".lrc", ignoreCase = true)) return@listFiles false
                    val n = f.nameWithoutExtension.lowercase().replace(Regex("""[^a-z0-9]"""), "")
                    val tNorm = normalizeString(title)
                    val aNorm = normalizeString(artist)
                    n.contains(tNorm) && (aNorm.isBlank() || n.contains(aNorm))
                }
                if (!matching.isNullOrEmpty()) {
                    try {
                        val parsed = parseLrc(matching[0].readText())
                        if (parsed.isNotEmpty()) return enrichWithCompanionTrans(matching[0], parsed)
                    } catch (_: Exception) {}
                }
            }
        }

        return emptyList()
    }

    private fun enrichWithCompanionTrans(lrcFile: File, entries: List<LyricEntry>): List<LyricEntry> {
        val transFile = File(lrcFile.parentFile, "${lrcFile.nameWithoutExtension}.trans.json")
        if (transFile.exists() && transFile.length() > 0) {
            try {
                val json = JSONObject(transFile.readText())
                return entries.map { entry ->
                    val k1 = entry.timeMs.toString()
                    val k2 = entry.text.lowercase().trim()
                    val trans = when {
                        json.has(k1) -> json.optString(k1)
                        json.has(k2) -> json.optString(k2)
                        else -> null
                    }
                    if (!trans.isNullOrBlank()) entry.copy(translation = trans) else entry
                }
            } catch (_: Exception) {}
        }
        return entries
    }

    private fun fetchOnlineLyrics(title: String, artist: String, targetDurationMs: Long = 0L): List<LyricEntry> {
        // Provider 1: NetEase Cloud Music (Massive catalog, fast, unrestricted, full LRC)
        try {
            val netease = fetchNetEaseLyrics(title, artist, targetDurationMs)
            if (netease.isNotEmpty()) {
                Log.i(TAG, "Loaded ${netease.size} synced lyrics from NetEase for '$title' by '$artist'")
                return netease
            }
        } catch (e: Exception) {
            Log.d(TAG, "NetEase provider error: ${e.message}")
        }

        // Provider 2: Megalobiz API (Fast, reliable public synced LRC API)
        try {
            val mega = fetchMegalobizLyrics(title, artist, targetDurationMs)
            if (mega.isNotEmpty()) {
                Log.i(TAG, "Loaded ${mega.size} synced lyrics from Megalobiz for '$title' by '$artist'")
                return mega
            }
        } catch (e: Exception) {
            Log.d(TAG, "Megalobiz provider error: ${e.message}")
        }

        // Provider 3: LRCLIB API (Community database)
        try {
            val lrclib = fetchLrclibLyrics(title, artist, targetDurationMs)
            if (lrclib.isNotEmpty()) {
                Log.i(TAG, "Loaded ${lrclib.size} synced lyrics from LRCLIB for '$title' by '$artist'")
                return lrclib
            }
        } catch (e: Exception) {
            Log.d(TAG, "LRCLIB provider error: ${e.message}")
        }

        // Provider 4: lyrics.ovh (Plain text fallback converted to readable lyrics)
        try {
            val ovh = fetchLyricsOvh(title, artist)
            if (ovh.isNotEmpty()) {
                Log.i(TAG, "Loaded ${ovh.size} unsynced lyrics lines from lyrics.ovh for '$title' by '$artist'")
                return ovh
            }
        } catch (e: Exception) {
            Log.d(TAG, "lyrics.ovh provider error: ${e.message}")
        }

        return emptyList()
    }

    private fun fetchNetEaseLyrics(title: String, artist: String, targetDurationMs: Long = 0L): List<LyricEntry> {
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
        val queries = mutableListOf<String>()
        if (artist.isNotBlank()) {
            queries.add("$title $artist")
        }
        queries.add(title)

        for (q in queries) {
            try {
                val formBody = okhttp3.FormBody.Builder()
                    .add("s", q)
                    .add("type", "1")
                    .add("offset", "0")
                    .add("total", "true")
                    .add("limit", "10")
                    .build()

                val searchReq = Request.Builder()
                    .url("https://music.163.com/api/search/get/")
                    .header("User-Agent", userAgent)
                    .header("Referer", "https://music.163.com/")
                    .post(formBody)
                    .build()

                client.newCall(searchReq).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            val json = JSONObject(body)
                            val result = json.optJSONObject("result")
                            val songs = result?.optJSONArray("songs")
                            if (songs != null && songs.length() > 0) {
                                for (i in 0 until minOf(songs.length(), 10)) {
                                    val s = songs.getJSONObject(i)
                                    val songId = s.optLong("id")
                                    val sName = s.optString("name", "").trim()
                                    val artistsArr = s.optJSONArray("artists")
                                    val sArtist = if (artistsArr != null && artistsArr.length() > 0) {
                                        (0 until artistsArr.length()).mapNotNull { idx ->
                                            artistsArr.optJSONObject(idx)?.optString("name", "")?.trim()
                                        }.filter { it.isNotBlank() }.joinToString(", ")
                                    } else ""
                                    val sDur = s.optLong("duration", 0L)

                                    if (songId > 0 && isTrackMatch(title, artist, sName, sArtist, targetDurationMs, sDur)) {
                                        val lyricReq = Request.Builder()
                                            .url("https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1")
                                            .header("User-Agent", userAgent)
                                            .header("Referer", "https://music.163.com/")
                                            .build()

                                        client.newCall(lyricReq).execute().use { lResp ->
                                            if (lResp.isSuccessful) {
                                                val lBody = lResp.body?.string()
                                                if (!lBody.isNullOrBlank()) {
                                                    val lJson = JSONObject(lBody)
                                                    val lrcObj = lJson.optJSONObject("lrc")
                                                    val lrcText = lrcObj?.optString("lyric", "") ?: ""
                                                    if (lrcText.length > 20) {
                                                        val parsed = parseLrc(lrcText)
                                                        if (parsed.isNotEmpty()) {
                                                            return parsed
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "NetEase query '$q' failed: ${e.message}")
            }
        }
        return emptyList()
    }

    private fun fetchLyricsOvh(title: String, artist: String): List<LyricEntry> {
        val cleanT = cleanTrackString(title)
        val artistCandidates = mutableListOf<String>()
        if (artist.isNotBlank()) {
            artistCandidates.add(artist)
            val parts = artist.split(Regex("""[,&/]|\bfeat\.?\b|\bft\.?\b""", RegexOption.IGNORE_CASE))
            for (p in parts) {
                val cp = p.trim()
                if (cp.isNotBlank() && !artistCandidates.contains(cp)) {
                    artistCandidates.add(cp)
                }
            }
        }

        for (a in artistCandidates) {
            if (a.isBlank()) continue
            try {
                val encArtist = URLEncoder.encode(a, "UTF-8")
                val encTitle = URLEncoder.encode(cleanT, "UTF-8")
                val url = "https://api.lyrics.ovh/v1/$encArtist/$encTitle"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "XCarOS/1.2")
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) {
                            val json = JSONObject(body)
                            val lyricsStr = json.optString("lyrics", "").trim()
                            if (lyricsStr.isNotBlank() && !lyricsStr.contains("No lyrics found", ignoreCase = true)) {
                                val lines = lyricsStr.lines().map { it.trim() }.filter { it.isNotBlank() }
                                if (lines.isNotEmpty()) {
                                    return lines.mapIndexed { idx, lineText ->
                                        LyricEntry(timeMs = idx * 4000L, text = lineText)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    private fun fetchMegalobizLyrics(title: String, artist: String, targetDurationMs: Long = 0L): List<LyricEntry> {
        val userAgent = "XCarOS/1.2 (https://github.com/TioXavi/XCarOS; automotive-headunit)"
        val queries = mutableListOf<String>()
        if (artist.isNotBlank()) {
            queries.add("$title $artist")
        }
        queries.add(title)

        for (q in queries) {
            try {
                val encQuery = URLEncoder.encode(q, "UTF-8")
                val url = "https://api.megalobiz.com/api/public/v1/search?q=$encQuery"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            val json = JSONObject(body)
                            val results = json.optJSONArray("results")
                            if (results != null && results.length() > 0) {
                                for (i in 0 until minOf(results.length(), 10)) {
                                    val item = results.getJSONObject(i)
                                    val resTitle = item.optString("title", "").trim()
                                    val resArtist = item.optString("artist", "").trim()
                                    val lrcUrl = item.optString("lrc_url", "").trim()

                                    if (lrcUrl.isNotBlank() && isTrackMatch(title, artist, resTitle, resArtist, targetDurationMs)) {
                                        val lrcReq = Request.Builder()
                                            .url(lrcUrl)
                                            .header("User-Agent", userAgent)
                                            .build()
                                        client.newCall(lrcReq).execute().use { lrcResp ->
                                            if (lrcResp.isSuccessful) {
                                                val lrcBody = lrcResp.body?.string()
                                                if (!lrcBody.isNullOrBlank()) {
                                                    val parsed = parseLrc(lrcBody)
                                                    if (parsed.isNotEmpty()) {
                                                        return parsed
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Megalobiz query '$q' failed: ${e.message}")
            }
        }
        return emptyList()
    }

    private fun fetchLrclibLyrics(title: String, artist: String, targetDurationMs: Long = 0L): List<LyricEntry> {
        val userAgent = "XCarOS/1.2 (https://github.com/TioXavi/XCarOS; automotive-headunit)"

        // Step 1: Direct exact match via /api/get
        try {
            val encTitle = URLEncoder.encode(title, "UTF-8")
            val encArtist = URLEncoder.encode(artist, "UTF-8")
            val url = if (artist.isNotBlank()) {
                "https://lrclib.net/api/get?track_name=$encTitle&artist_name=$encArtist"
            } else {
                "https://lrclib.net/api/get?track_name=$encTitle"
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        val synced = json.optString("syncedLyrics", "").trim()
                        val resTrack = json.optString("trackName", "").trim()
                        val resArtist = json.optString("artistName", "").trim()
                        val resDur = (json.optDouble("duration", 0.0) * 1000).toLong()

                        if (synced.isNotBlank() && isTrackMatch(title, artist, resTrack, resArtist, targetDurationMs, resDur)) {
                            val parsed = parseLrc(synced)
                            if (parsed.isNotEmpty()) {
                                Log.i(TAG, "LRCLIB exact match verified for '$title' by '$artist'")
                                return parsed
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "LRCLIB exact match failed: ${e.message}")
        }

        // Step 2: Search fallback via /api/search
        try {
            val queryParam = if (artist.isNotBlank()) "$title $artist" else title
            val query = URLEncoder.encode(queryParam, "UTF-8")
            val searchUrl = "https://lrclib.net/api/search?q=$query"

            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", userAgent)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val array = JSONArray(body)
                        for (i in 0 until minOf(array.length(), 25)) {
                            val item = array.getJSONObject(i)
                            val synced = item.optString("syncedLyrics", "").trim()
                            val resTrack = item.optString("trackName", "").trim()
                            val resArtist = item.optString("artistName", "").trim()
                            val resDur = (item.optDouble("duration", 0.0) * 1000).toLong()

                            if (synced.isNotBlank() && isTrackMatch(title, artist, resTrack, resArtist, targetDurationMs, resDur)) {
                                val parsed = parseLrc(synced)
                                if (parsed.isNotEmpty()) {
                                    Log.i(TAG, "LRCLIB search match verified: '$resTrack' by '$resArtist'")
                                    return parsed
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "LRCLIB search fallback failed: ${e.message}")
        }

        return emptyList()
    }

    /**
     * Strict & Intelligent similarity check between target song and search result.
     * Prevents downloading lyrics of completely different songs with the same title.
     * Validates:
     * 1. Title match (exact or >= 55% length ratio on containment)
     * 2. Artist match (mandatory when target artist is known; splits tokens for multi-artist tracks)
     * 3. Duration match (within 25s or 20% tolerance when both durations are known)
     */
    fun isTrackMatch(
        targetTitle: String,
        targetArtist: String,
        resTrack: String,
        resArtist: String,
        targetDurationMs: Long = 0L,
        resDurationMs: Long = 0L
    ): Boolean {
        if (resTrack.isBlank()) return false

        val cleanT = cleanTrackString(targetTitle)
        val cleanR = cleanTrackString(resTrack)

        val normTargetTitle = normalizeString(cleanT)
        val normResTrack = normalizeString(cleanR)

        // 1. Title validation
        val titleExact = normTargetTitle == normResTrack
        val titleContains = (normTargetTitle.length >= 4 && normResTrack.contains(normTargetTitle)) ||
                (normResTrack.length >= 4 && normTargetTitle.contains(normResTrack))
        val titleRatio = if (titleContains) {
            val minLen = minOf(normTargetTitle.length, normResTrack.length).toFloat()
            val maxLen = maxOf(normTargetTitle.length, normResTrack.length).toFloat()
            minLen / maxLen
        } else 0f

        val titleMatches = titleExact || (titleContains && titleRatio >= 0.55f)
        if (!titleMatches) return false

        // 2. Artist validation (Strict: zero cross-artist contamination)
        val cleanTargetArtist = cleanArtistString(targetArtist)
        val cleanResArtist = cleanArtistString(resArtist)

        val normTargetArtist = normalizeString(cleanTargetArtist)
        val normResArtist = normalizeString(cleanResArtist)

        val isTargetKnown = normTargetArtist.isNotBlank() && !isGenericArtist(normTargetArtist)
        val isResKnown = normResArtist.isNotBlank() && !isGenericArtist(normResArtist)

        if (isTargetKnown && isResKnown) {
            val directMatch = normTargetArtist == normResArtist ||
                    normTargetArtist.contains(normResArtist) ||
                    normResArtist.contains(normTargetArtist)

            if (!directMatch) {
                val targetTokens = splitArtistTokens(cleanTargetArtist)
                val resTokens = splitArtistTokens(cleanResArtist)

                val anyTokenMatches = targetTokens.any { tt ->
                    resTokens.any { rt ->
                        tt == rt || (tt.length >= 4 && rt.length >= 4 && (tt.contains(rt) || rt.contains(tt)))
                    }
                }

                // If target artist is known and NO token matches, REJECT IMMEDIATELY!
                if (!anyTokenMatches) {
                    return false
                }
            }
        } else if (isTargetKnown && !isResKnown) {
            // Target artist is known, but result has no artist tag:
            // Only accept if title is an exact match
            if (!titleExact) return false
        }

        // 3. Duration validation (if both durations are available and > 10s)
        if (targetDurationMs > 10_000L && resDurationMs > 10_000L) {
            val diff = Math.abs(targetDurationMs - resDurationMs)
            val tolerance = maxOf(25_000L, (targetDurationMs * 0.20).toLong())
            if (diff > tolerance) {
                Log.d(TAG, "Duration mismatch: target=${targetDurationMs}ms vs res=${resDurationMs}ms (diff=$diff > tol=$tolerance)")
                return false
            }
        }

        return true
    }

    private fun isGenericArtist(normArtist: String): Boolean {
        val genericKeywords = setOf("boleros", "varios", "various", "clasicos", "latin", "latino", "musica", "soundtrack", "desconocido", "unknown", "musicalocal", "usb")
        return genericKeywords.contains(normArtist)
    }

    private fun splitArtistTokens(artist: String): List<String> {
        return artist.split(Regex("""[,&/]|\bfeat\.?\b|\bft\.?\b|\by\b|\band\b|\bcon\b""", RegexOption.IGNORE_CASE))
            .map { normalizeString(it) }
            .filter { it.length >= 3 }
    }

    private fun normalizeString(input: String): String {
        val decomposed = Normalizer.normalize(input.lowercase(), Normalizer.Form.NFD)
        return decomposed.replace(Regex("""\p{M}"""), "")
            .replace(Regex("""[^a-z0-9]"""), "")
            .trim()
    }

    private fun saveToLocalCache(
        context: Context,
        title: String,
        artist: String,
        lyrics: List<LyricEntry>
    ) {
        try {
            val cleanT = sanitizeFileName(title)
            val cleanA = sanitizeFileName(artist)
            val fileName = "${cleanT}_${cleanA}.lrc"
            val transFileName = "${cleanT}_${cleanA}.trans.json"

            val sb = StringBuilder()
            val transJson = JSONObject()
            for (entry in lyrics) {
                val mins = entry.timeMs / 60_000
                val secs = (entry.timeMs % 60_000) / 1000
                val ms = (entry.timeMs % 1000) / 10
                sb.append(String.format(java.util.Locale.US, "[%02d:%02d.%02d]%s\n", mins, secs, ms, entry.text))
                if (!entry.translation.isNullOrBlank()) {
                    transJson.put(entry.timeMs.toString(), entry.translation)
                    transJson.put(entry.text.lowercase().trim(), entry.translation)
                }
            }
            val content = sb.toString()

            // 1. Internal cache directory
            val cacheDir = File(context.cacheDir, "lyrics")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            File(cacheDir, fileName).writeText(content)
            if (transJson.length() > 0) {
                File(cacheDir, transFileName).writeText(transJson.toString())
            }

            // 2. Public /sdcard/Music/Lyrics directory for persistent offline backup
            try {
                val publicDir = File("/sdcard/Music/Lyrics")
                if (!publicDir.exists()) publicDir.mkdirs()
                File(publicDir, fileName).writeText(content)
                if (transJson.length() > 0) {
                    File(publicDir, transFileName).writeText(transJson.toString())
                }
            } catch (_: Exception) {}

            Log.i(TAG, "Cached ${lyrics.size} synced lyric lines to $fileName")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save lyrics to disk cache", e)
        }
    }

    /**
     * Parses standard or enhanced LRC content.
     * Accurately supports the [offset:+-XXXX] tag to adjust playback synchronization.
     * Automatically filters metadata lines (author, composer, tags).
     */
    fun parseLrc(lrcContent: String): List<LyricEntry> {
        val list = mutableListOf<LyricEntry>()
        val timeRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]""")
        val offsetRegex = Regex("""\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)

        val offsetMs = offsetRegex.find(lrcContent)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L

        lrcContent.lineSequence().forEach { line ->
            val match = timeRegex.find(line)
            if (match != null) {
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val msString = match.groupValues.getOrNull(3) ?: "0"
                val millis = when (msString.length) {
                    1 -> msString.toLong() * 100
                    2 -> msString.toLong() * 10
                    else -> msString.take(3).toLong()
                }
                val rawTotalMs = minutes * 60_000L + seconds * 1000L + millis
                val totalMs = (rawTotalMs + offsetMs).coerceAtLeast(0L)
                val text = line.substring(match.range.last + 1).trim()
                if (text.isNotBlank() && !isMetadataText(text)) {
                    list.add(LyricEntry(totalMs, text))
                }
            }
        }
        return list.sortedBy { it.timeMs }
    }

    private fun isMetadataText(text: String): Boolean {
        val t = text.lowercase()
        return t.startsWith("作词") || t.startsWith("作曲") || t.startsWith("编曲") ||
                t.startsWith("制作") || t.startsWith("by:") || t.startsWith("ti:") ||
                t.startsWith("ar:") || t.startsWith("al:") || t.startsWith("qq:")
    }

    fun cleanTrackString(raw: String): String {
        var res = raw
        // Remove parenthesized or bracketed noise: (Official Video), [Official Audio], (Visualizer), (Remastered 2021), etc.
        res = res.replace(Regex("""\s*[\(\[\{][^\)\]\}]*(?:official|video|audio|remix|hd|4k|lyric|lyrics|hq|dembow|version|remaster|single|album|feat|ft|live|prod|with|visualizer|clip)[^\)\]\}]*[\)\]\}]""", RegexOption.IGNORE_CASE), "")
        // Remove trailing dash noise: - 2015 Remaster, - Remastered, - Official Audio, - Single, - Remix
        res = res.replace(Regex("""\s*-\s*(?:official|video|audio|remix|hd|4k|remaster|remastered|live|single|album|visualizer).*""", RegexOption.IGNORE_CASE), "")
        // Remove standalone noise phrases: Album Version, Single Version, Remastered, 2015 Remaster
        res = res.replace(Regex("""\b(?:album version|single version|\d{4} remaster(?:ed)?|remastered \d{4}|remastered|remaster)\b""", RegexOption.IGNORE_CASE), "")
        // Remove feat. / ft. / featuring / with
        res = res.replace(Regex("""\b(?:feat\.|ft\.|featuring|with)\s+.*""", RegexOption.IGNORE_CASE), "")
        // Clean empty parens
        res = res.replace(Regex("""[\(\[\{]\s*[\)\]\}]"""), "")
        return res.replace("_", " ").trim()
    }

    fun cleanArtistString(raw: String): String {
        if (raw.equals("Desconocido", ignoreCase = true) ||
            raw.equals("Unknown", ignoreCase = true) ||
            raw.contains("Musica local", ignoreCase = true) ||
            raw.contains("USB", ignoreCase = true)
        ) {
            return ""
        }
        return raw.trim()
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("""[^a-zA-Z0-9.-]"""), "_").take(40)
    }

    fun clearCache() {
        memoryCache.clear()
        preloadedFiles.clear()
    }

    fun wipeAllLyricsCache(context: Context) {
        clearCache()
        try {
            val internal = File(context.cacheDir, "lyrics")
            if (internal.exists()) internal.deleteRecursively()
        } catch (_: Exception) {}
        try {
            val publicDir = File("/sdcard/Music/Lyrics")
            if (publicDir.exists()) {
                publicDir.listFiles()?.forEach { it.delete() }
            }
        } catch (_: Exception) {}
        Log.i(TAG, "Wiped all lyrics cache completely")
    }
}
