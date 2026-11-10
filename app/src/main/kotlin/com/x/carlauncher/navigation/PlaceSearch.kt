package com.x.carlauncher.navigation

import android.content.Context
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.x.carlauncher.ui.screens.NavDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Resultado de buscar un lugar por nombre o direccion.
 */
data class PlaceResult(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double
) {
    val geoPoint: LatLng get() = LatLng(latitude, longitude)
    fun toDestination(): NavDestination = NavDestination(name, address.ifBlank { "Búsqueda" }, geoPoint)
}

/**
 * Busqueda de lugares con Google Places API (New) + busquedas recientes.
 *
 * Autocomplete (POST /v1/places:autocomplete) devuelve predicciones con
 * nombre y direccion pero sin coordenadas; para cada prediccion se pide el
 * detalle (GET /v1/places/{placeId}) con el campo location. La llave se lee
 * del meta-data del manifest en runtime (nunca va escrita en el codigo) y
 * se mandan las cabeceras de identidad de la app, porque la llave esta
 * restringida al paquete + huella SHA-1.
 *
 * Sin restriccion de region: la busqueda es mundial, sin preferencia de
 * pais. Lanza excepcion si no hay internet, no hay llave o el servicio
 * falla; devuelve lista vacia si no hay coincidencias.
 */
object PlaceSearch {
    private const val TAG = "PlaceSearch"
    private const val PREFS = "place_search_recents"
    private const val KEY_RECENTS = "recents_json"
    private const val MAX_RECENTS = 5
    private const val MAX_RESULTS = 8

    private const val AUTOCOMPLETE_URL = "https://places.googleapis.com/v1/places:autocomplete"
    private const val PLACE_DETAILS_URL = "https://places.googleapis.com/v1/places/"
    private const val AUTOCOMPLETE_FIELD_MASK =
        "suggestions.placePrediction.place," +
        "suggestions.placePrediction.placeId," +
        "suggestions.placePrediction.structuredFormat.mainText.text," +
        "suggestions.placePrediction.structuredFormat.secondaryText.text"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun search(context: Context, query: String): List<PlaceResult> = withContext(Dispatchers.IO) {
        val apiKey = GoogleApiKey.readApiKey(context)
            ?: throw Exception("Sin llave de Google Maps en el manifest")
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()

        val bodyJson = JSONObject().apply {
            put("input", q)
            put("languageCode", "es")
        }
        val requestBuilder = Request.Builder()
            .url(AUTOCOMPLETE_URL)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("X-Goog-Api-Key", apiKey)
            .header("X-Goog-FieldMask", AUTOCOMPLETE_FIELD_MASK)
        GoogleApiKey.androidIdentityHeaders(context).forEach { (k, v) ->
            requestBuilder.header(k, v)
        }
        val predictions = client.newCall(requestBuilder.build()).execute().use { resp ->
            // Nota: en OkHttp 4.x, code/body son propiedades (resp.code, resp.body),
            // no métodos; las llamadas con paréntesis están deprecadas y no compilan.
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty()
                Log.w(TAG, "Places Autocomplete HTTP ${resp.code}: ${errBody.take(300)}")
                throw Exception("Places API HTTP ${resp.code}")
            }
            val body = resp.body?.string() ?: return@withContext emptyList()
            parsePredictions(JSONObject(body))
        }

        // Cada prediccion necesita sus coordenadas: detalle del lugar.
        // Si el detalle de una falla, se salta esa prediccion sin romper
        // toda la busqueda.
        val out = mutableListOf<PlaceResult>()
        for (pred in predictions.take(MAX_RESULTS)) {
            val loc = fetchPlaceLocation(context, apiKey, pred.placeId) ?: continue
            out.add(
                PlaceResult(
                    name = pred.name.ifBlank { "Lugar" },
                    address = pred.address,
                    latitude = loc.first,
                    longitude = loc.second
                )
            )
        }
        out
    }

    private data class Prediction(val placeId: String, val name: String, val address: String)

    private fun parsePredictions(json: JSONObject): List<Prediction> {
        val out = mutableListOf<Prediction>()
        val suggestions = json.optJSONArray("suggestions") ?: return out
        for (i in 0 until suggestions.length()) {
            val pred = suggestions.optJSONObject(i)?.optJSONObject("placePrediction") ?: continue
            val placeId = pred.optString("placeId", "")
            if (placeId.isBlank()) continue
            val format = pred.optJSONObject("structuredFormat")
            val mainText = format?.optJSONObject("mainText")?.optString("text", "").orEmpty()
            val secondaryText = format?.optJSONObject("secondaryText")?.optString("text", "").orEmpty()
            out.add(Prediction(placeId, mainText, secondaryText))
        }
        return out
    }

    /** Coordenadas (lat, lon) de un lugar por su placeId, o null si falla. */
    private fun fetchPlaceLocation(context: Context, apiKey: String, placeId: String): Pair<Double, Double>? {
        return try {
            val requestBuilder = Request.Builder()
                .url(PLACE_DETAILS_URL + placeId)
                .get()
                .header("X-Goog-Api-Key", apiKey)
                .header("X-Goog-FieldMask", "location")
            GoogleApiKey.androidIdentityHeaders(context).forEach { (k, v) ->
                requestBuilder.header(k, v)
            }
            client.newCall(requestBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Place Details HTTP ${resp.code} para $placeId")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val loc = JSONObject(body).optJSONObject("location") ?: return null
                val lat = loc.optDouble("latitude", 0.0)
                val lon = loc.optDouble("longitude", 0.0)
                if (lat == 0.0 && lon == 0.0) null else Pair(lat, lon)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Place Details error para $placeId", e)
            null
        }
    }

    /** Ultimas busquedas elegidas, mas reciente primero (max 5). */
    fun getRecent(context: Context): List<PlaceResult> {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_RECENTS, null) ?: return emptyList()
            val arr = JSONArray(raw)
            val out = mutableListOf<PlaceResult>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                out.add(
                    PlaceResult(
                        name = obj.optString("name", ""),
                        address = obj.optString("address", ""),
                        latitude = obj.optDouble("lat", 0.0),
                        longitude = obj.optDouble("lon", 0.0)
                    )
                )
            }
            out.filter { it.name.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Guarda un lugar al inicio de recientes (sin duplicados). */
    fun saveRecent(context: Context, place: PlaceResult) {
        try {
            val current = getRecent(context)
                .filterNot { it.name == place.name && it.latitude == place.latitude && it.longitude == place.longitude }
                .toMutableList()
            current.add(0, place)
            val arr = JSONArray()
            current.take(MAX_RECENTS).forEach { p ->
                arr.put(
                    JSONObject().apply {
                        put("name", p.name)
                        put("address", p.address)
                        put("lat", p.latitude)
                        put("lon", p.longitude)
                    }
                )
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_RECENTS, arr.toString()).apply()
        } catch (_: Exception) {
        }
    }
}
