package com.x.carlauncher.navigation

import android.content.Context
import com.x.carlauncher.ui.screens.NavDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.URLEncoder
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
    val geoPoint: GeoPoint get() = GeoPoint(latitude, longitude)
    fun toDestination(): NavDestination = NavDestination(name, address.ifBlank { "Búsqueda" }, geoPoint)
}

/**
 * Busqueda de lugares con Nominatim (OpenStreetMap) + busquedas recientes.
 *
 * Nominatim es gratis y no pide llave ni cuenta: los mismos datos de
 * OpenStreetMap que usa OSRM para calcular las rutas. Solo necesita
 * internet, igual que las rutas. Politica de uso: max 1 peticion/segundo,
 * por eso la busqueda se lanza con el boton o la tecla Buscar del teclado,
 * no en cada letra que se escribe.
 */
object PlaceSearch {
    private const val PREFS = "place_search_recents"
    private const val KEY_RECENTS = "recents_json"
    private const val MAX_RECENTS = 5

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Busca lugares por nombre o direccion en todo el continente americano
     * (Mexico, Estados Unidos, Canada, Centroamerica, Sudamerica...). Sin
     * restriccion de pais: el viewbox del continente solo le da preferencia
     * a resultados de America, y si ahi no hay nada devuelve del resto del
     * mundo. Lanza excepcion si no hay internet o el servicio falla;
     * devuelve lista vacia si no hay coincidencias.
     */
    suspend fun search(query: String): List<PlaceResult> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://nominatim.openstreetmap.org/search" +
            "?format=json&addressdetails=1&limit=8&accept-language=es" +
            "&viewbox=-170,-60,-10,85&q=$q"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "CarOS Ford Fiesta/1.0.4 (Android)")
            .build()
        client.newCall(request).execute().use { resp ->
            // Nota: en OkHttp los campos code/body son package-private, asi
            // que hay que llamarlos como metodos con parentesis (resp.code()
            // y resp.body()); sin ellos Kotlin intenta leer el campo y no compila.
            if (!resp.isSuccessful) throw Exception("Nominatim HTTP ${resp.code()}")
            val body = resp.body()?.string() ?: return@withContext emptyList()
            val arr = JSONArray(body)
            val out = mutableListOf<PlaceResult>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val lat = obj.optDouble("lat", 0.0)
                val lon = obj.optDouble("lon", 0.0)
                if (lat == 0.0 && lon == 0.0) continue
                val display = obj.optString("display_name", "")
                val name = obj.optString("name", "").ifBlank { display.substringBefore(",").trim() }
                val address = display.substringAfter(",", "").trim()
                out.add(
                    PlaceResult(
                        name = name.ifBlank { "Lugar" },
                        address = address,
                        latitude = lat,
                        longitude = lon
                    )
                )
            }
            out
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
