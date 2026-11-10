package com.x.carlauncher.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.google.android.gms.maps.model.LatLng
import com.x.carlauncher.location.VehicleLocationManager
import com.x.carlauncher.voice.CarVoiceAssistant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Maneuver classification for dynamic HUD navigation arrows and audio instructions.
 */
enum class TurnManeuver(val displayName: String, val baseAngle: Float, val isTurn: Boolean) {
    STRAIGHT("Continue recto", 0f, false),
    SLIGHT_RIGHT("Leve giro a la derecha", 35f, true),
    RIGHT("Gire a la derecha", 68f, true),
    SHARP_RIGHT("Giro pronunciado a la derecha", 90f, true),
    SLIGHT_LEFT("Leve giro a la izquierda", -35f, true),
    LEFT("Gire a la izquierda", -68f, true),
    SHARP_LEFT("Giro pronunciado a la izquierda", -90f, true),
    U_TURN("Retorno en U", 180f, true),
    ARRIVED("Ha llegado a su destino", 0f, false)
}

/**
 * Step in a turn-by-turn route.
 */
data class NavTurnStep(
    val instruction: String,
    val streetName: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val location: LatLng,
    val maneuver: TurnManeuver = TurnManeuver.STRAIGHT
)

/**
 * Real-road vehicle navigation route following physical streets and avenues.
 */
data class ActiveNavRoute(
    val destinationName: String,
    val destinationPoint: LatLng,
    val startPoint: LatLng,
    val roadPoints: List<LatLng>,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val steps: List<NavTurnStep> = emptyList(),
    val isRealRoadData: Boolean = true
) {
    val distanceKmFormatted: String
        get() = String.format(Locale.US, "%.1f km", totalDistanceMeters / 1000.0)

    val etaMinutesFormatted: String
        get() = String.format(Locale.US, "%.1f min", (totalDurationSeconds / 60.0).coerceAtLeast(1.0))
}

/**
 * Unified Global Navigation & Route Manager for X CarOS.
 * - Queries Google Routes API to calculate REAL street routes.
 * - Synchronizes active navigation route across ALL 3 map views.
 * - Allows launching turn-by-turn navigation directly in Google Maps.
 * - Solo datos reales: sin ubicaciones simuladas, sin rutas inventadas. Si
 *   Routes API no responde, no hay ruta (null) en vez de inventar una.
 */
object CarRouteManager {

    private const val TAG = "CarRouteManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ── Unified Navigation State ───────────────────────────────────────────────
    private val _activeRoute = MutableStateFlow<ActiveNavRoute?>(null)
    val activeRoute: StateFlow<ActiveNavRoute?> = _activeRoute.asStateFlow()

    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

    private val _currentInstruction = MutableStateFlow("Siga la ruta trazada")
    val currentInstruction: StateFlow<String> = _currentInstruction.asStateFlow()

    // ── Turn Maneuver Telemetry for 3D HUD Arrow ───────────────────────────────
    private val _currentManeuver = MutableStateFlow(TurnManeuver.STRAIGHT)
    val currentManeuver: StateFlow<TurnManeuver> = _currentManeuver.asStateFlow()

    private val _targetTurnAngle = MutableStateFlow(0f)
    val targetTurnAngle: StateFlow<Float> = _targetTurnAngle.asStateFlow()

    private val _distanceToNextManeuver = MutableStateFlow(0.0)
    val distanceToNextManeuver: StateFlow<Double> = _distanceToNextManeuver.asStateFlow()

    private var navigationJob: Job? = null

    /**
     * Launches official Google Maps Driving Navigation with live real-time traffic.
     */
    fun openInGoogleMaps(context: Context, dest: LatLng, destName: String? = null) {
        try {
            val uri = Uri.parse("google.navigation:q=${dest.latitude},${dest.longitude}&mode=d")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                Toast.makeText(context, "Iniciando Google Maps a ${destName ?: "Destino"}...", Toast.LENGTH_SHORT).show()
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot launch Google Maps direct intent", e)
        }

        // Web fallback if Google Maps app is not installed
        try {
            val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${dest.latitude},${dest.longitude}&travelmode=driving")
            val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        } catch (_: Exception) {
            Toast.makeText(context, "No se pudo abrir Google Maps.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Calculates a real road network route using Google Routes API.
     * Follows physical streets, avenues, and highways. Never cuts through buildings or houses.
     */
    fun calculateRoute(
        context: Context,
        start: LatLng,
        end: LatLng,
        destName: String,
        onComplete: ((ActiveNavRoute?) -> Unit)? = null
    ) {
        scope.launch {
            val route = withContext(Dispatchers.IO) {
                fetchGoogleRoute(context, start, end, destName)
            }
            if (route != null) {
                _activeRoute.value = route
                _currentInstruction.value = if (route.steps.isNotEmpty()) route.steps.first().instruction else "Ruta trazada a $destName"
                Log.i(TAG, "Real road route calculated: ${route.roadPoints.size} road nodes, ${route.distanceKmFormatted}, ${route.etaMinutesFormatted}")
            }
            onComplete?.invoke(route)
        }
    }

    // ── Google Routes API ──────────────────────────────────────────────────────
    // POST https://routes.googleapis.com/directions/v2:computeRoutes
    // La llave se lee del meta-data del manifest en runtime (nunca va escrita
    // en el codigo) y se mandan las cabeceras de identidad de la app, porque
    // la llave esta restringida al paquete + huella SHA-1.

    private const val ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
    private const val ROUTES_FIELD_MASK =
        "routes.polyline.encodedPolyline," +
        "routes.legs.distanceMeters,routes.legs.duration," +
        "routes.legs.steps,routes.legs.steps.navigationInstruction," +
        "routes.legs.steps.polyline,routes.legs.steps.distanceMeters," +
        "routes.legs.steps.staticDuration"

    private fun fetchGoogleRoute(context: Context, start: LatLng, end: LatLng, destName: String): ActiveNavRoute? {
        val apiKey = GoogleApiKey.readApiKey(context)
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "Sin llave de Google Maps en el manifest; no se puede calcular la ruta")
            return null
        }
        return try {
            val bodyJson = JSONObject().apply {
                put("origin", JSONObject().apply {
                    put("location", JSONObject().apply {
                        put("latLng", JSONObject().apply {
                            put("latitude", start.latitude)
                            put("longitude", start.longitude)
                        })
                    })
                })
                put("destination", JSONObject().apply {
                    put("location", JSONObject().apply {
                        put("latLng", JSONObject().apply {
                            put("latitude", end.latitude)
                            put("longitude", end.longitude)
                        })
                    })
                })
                put("travelMode", "DRIVE")
                put("routingPreference", "TRAFFIC_AWARE")
                put("computeAlternativeRoutes", false)
                put("languageCode", "es")
            }
            val requestBuilder = Request.Builder()
                .url(ROUTES_URL)
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .header("X-Goog-Api-Key", apiKey)
                .header("X-Goog-FieldMask", ROUTES_FIELD_MASK)
            GoogleApiKey.androidIdentityHeaders(context).forEach { (k, v) ->
                requestBuilder.header(k, v)
            }
            httpClient.newCall(requestBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty()
                    Log.w(TAG, "Routes API HTTP ${resp.code}: ${errBody.take(300)}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                parseRoutesResponse(JSONObject(body), start, end, destName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Routes API query error", e)
            null
        }
    }

    private fun parseRoutesResponse(json: JSONObject, start: LatLng, end: LatLng, destName: String): ActiveNavRoute? {
        val routes = json.optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null
        val firstRoute = routes.getJSONObject(0)

        val encoded = firstRoute.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty()
        val roadPoints = if (encoded.isNotBlank()) decodePolyline(encoded) else emptyList()
        if (roadPoints.isEmpty()) return null

        val legs = firstRoute.optJSONArray("legs") ?: return null
        if (legs.length() == 0) return null
        val leg0 = legs.getJSONObject(0)
        val totalDistance = leg0.optDouble("distanceMeters", 0.0)
        val totalDuration = parseDurationSeconds(leg0.optString("duration", ""))

        // Parse turn-by-turn steps
        val stepsList = mutableListOf<NavTurnStep>()
        val stepsArray = leg0.optJSONArray("steps")
        if (stepsArray != null) {
            for (s in 0 until stepsArray.length()) {
                val stepObj = stepsArray.getJSONObject(s)
                val nav = stepObj.optJSONObject("navigationInstruction")
                val maneuverRaw = nav?.optString("maneuver", "") ?: ""
                val instructionRaw = nav?.optString("instructions", "") ?: ""
                val dist = stepObj.optDouble("distanceMeters", 0.0)
                val dur = parseDurationSeconds(stepObj.optString("staticDuration", ""))

                // Ubicacion de la maniobra: primer punto de la polilinea del paso.
                val stepPolyline = stepObj.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty()
                val stepPoints = if (stepPolyline.isNotBlank()) decodePolyline(stepPolyline) else emptyList()
                val stepLoc = stepPoints.firstOrNull() ?: start

                val turnManeuver = when (maneuverRaw) {
                    "ARRIVE" -> TurnManeuver.ARRIVED
                    "TURN_SHARP_LEFT" -> TurnManeuver.SHARP_LEFT
                    "TURN_SLIGHT_LEFT", "RAMP_LEFT", "FORK_LEFT", "ROUNDABOUT_LEFT" -> TurnManeuver.SLIGHT_LEFT
                    "TURN_LEFT" -> TurnManeuver.LEFT
                    "TURN_SHARP_RIGHT" -> TurnManeuver.SHARP_RIGHT
                    "TURN_SLIGHT_RIGHT", "RAMP_RIGHT", "FORK_RIGHT", "ROUNDABOUT_RIGHT" -> TurnManeuver.SLIGHT_RIGHT
                    "TURN_RIGHT" -> TurnManeuver.RIGHT
                    "U_TURN" -> TurnManeuver.U_TURN
                    else -> TurnManeuver.STRAIGHT
                }

                val instruction = when {
                    maneuverRaw == "ARRIVE" -> "Ha llegado a su destino: $destName"
                    maneuverRaw == "DEPART" -> instructionRaw.ifBlank { "Inicie el recorrido" }
                    instructionRaw.isNotBlank() -> instructionRaw
                    else -> "Continúe por la ruta"
                }
                stepsList.add(NavTurnStep(instruction, "", dist, dur, stepLoc, turnManeuver))
            }
        }

        return ActiveNavRoute(
            destinationName = destName,
            destinationPoint = end,
            startPoint = start,
            roadPoints = roadPoints,
            totalDistanceMeters = totalDistance,
            totalDurationSeconds = totalDuration,
            steps = stepsList,
            isRealRoadData = true
        )
    }

    /** Duraciones de Routes API vienen como texto "123s" (google.protobuf.Duration). */
    private fun parseDurationSeconds(raw: String): Double {
        return raw.trim().removeSuffix("s").toDoubleOrNull() ?: 0.0
    }

    /**
     * Starts active navigation along the real road route.
     * Sigue la posicion REAL del GPS (VehicleLocationManager): sin fix muestra
     * "Esperando señal GPS real..."; llega al destino a menos de 40 m.
     * Las maniobras e instrucciones se calculan con las distancias reales
     * desde la posicion GPS hasta cada paso de la ruta de Google.
     */
    fun startNavigation(context: Context, route: ActiveNavRoute) {
        _activeRoute.value = route
        _isNavigating.value = true

        val initialInstruction = route.steps.firstOrNull()?.instruction
            ?: "Iniciando navegacion asistida hacia ${route.destinationName}."
        _currentInstruction.value = initialInstruction

        try {
            CarVoiceAssistant.getInstance(context).speak(initialInstruction)
        } catch (_: Exception) {}

        navigationJob?.cancel()
        navigationJob = scope.launch {
            while (isActive && _isNavigating.value) {
                val loc = VehicleLocationManager.currentLocation
                if (!loc.hasFix) {
                    _currentInstruction.value = "Esperando señal GPS real..."
                    _currentManeuver.value = TurnManeuver.STRAIGHT
                    _targetTurnAngle.value = 0f
                    _distanceToNextManeuver.value = 0.0
                    delay(1000)
                    continue
                }
                val currentPos = LatLng(loc.latitude, loc.longitude)

                // Llegada: a menos de 40 m del destino
                if (calculateDistanceMeters(currentPos, route.destinationPoint) <= 40.0) {
                    val arrivalMsg = "Ha llegado a su destino: ${route.destinationName}."
                    _currentInstruction.value = arrivalMsg
                    _currentManeuver.value = TurnManeuver.ARRIVED
                    _targetTurnAngle.value = 0f
                    _distanceToNextManeuver.value = 0.0
                    try {
                        CarVoiceAssistant.getInstance(context).speak(arrivalMsg)
                    } catch (_: Exception) {}
                    _isNavigating.value = false
                    _activeRoute.value = null
                    break
                }

                // Check upcoming turn-by-turn maneuver within 300 meters (distancia real GPS)
                val upcomingTurn = route.steps.firstOrNull { step ->
                    val dist = calculateDistanceMeters(currentPos, step.location)
                    dist in 15.0..320.0 && step.maneuver.isTurn
                }

                if (upcomingTurn != null) {
                    val dist = calculateDistanceMeters(currentPos, upcomingTurn.location)
                    _currentManeuver.value = upcomingTurn.maneuver
                    _targetTurnAngle.value = upcomingTurn.maneuver.baseAngle
                    _distanceToNextManeuver.value = dist
                    _currentInstruction.value = "En ${dist.toInt()}m: ${upcomingTurn.instruction}"
                } else {
                    val atTurn = route.steps.firstOrNull { step ->
                        calculateDistanceMeters(currentPos, step.location) < 35.0
                    }
                    if (atTurn != null) {
                        _currentManeuver.value = atTurn.maneuver
                        _targetTurnAngle.value = atTurn.maneuver.baseAngle
                        _distanceToNextManeuver.value = 0.0
                        if (_currentInstruction.value != atTurn.instruction) {
                            _currentInstruction.value = atTurn.instruction
                            try {
                                CarVoiceAssistant.getInstance(context).speak(atTurn.instruction)
                            } catch (_: Exception) {}
                        }
                    } else {
                        _currentManeuver.value = TurnManeuver.STRAIGHT
                        _targetTurnAngle.value = 0f
                        _distanceToNextManeuver.value = 0.0
                    }
                }

                delay(1000)
            }
        }
    }

    fun stopNavigation() {
        _isNavigating.value = false
        _activeRoute.value = null
        navigationJob?.cancel()
        navigationJob = null
        _currentManeuver.value = TurnManeuver.STRAIGHT
        _targetTurnAngle.value = 0f
        _distanceToNextManeuver.value = 0.0
        _currentInstruction.value = "Navegacion finalizada"
    }

    fun clearRoute() {
        stopNavigation()
    }

    // ── Progreso en vivo por la ruta ──────────────────────────────────────────
    // La distancia y el ETA del HUD eran los totales fijos del calculo
    // inicial y nunca bajaban mientras el auto avanzaba, y la polilinea se
    // quedaba completa. Estas funciones calculan en vivo, desde la posicion
    // GPS actual, lo que falta por recorrer siguiendo la ruta. Son agnosticas
    // a la API de rutas: trabajan solo sobre la polilinea.

    private data class NearestOnRoute(val segmentIndex: Int, val closest: LatLng, val distanceMeters: Double)

    // Punto de la polilinea mas cercano a la posicion actual (proyeccion
    // equirectangular local, suficiente a escala de ciudad). Null sin puntos.
    private fun nearestOnRoute(currentPos: LatLng, route: ActiveNavRoute): NearestOnRoute? {
        val pts = route.roadPoints
        if (pts.size < 2) return null
        val lat0 = Math.toRadians(currentPos.latitude)
        val kx = 111320.0 * Math.cos(lat0)
        val ky = 110540.0
        val px = currentPos.longitude * kx
        val py = currentPos.latitude * ky
        var best: NearestOnRoute? = null
        var bestDist = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val ax = a.longitude * kx
            val ay = a.latitude * ky
            val bx = b.longitude * kx
            val by = b.latitude * ky
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((px - ax) * dx + (py - ay) * dy) / len2
            val tc = t.coerceIn(0.0, 1.0)
            val cx = ax + tc * dx
            val cy = ay + tc * dy
            val d = Math.hypot(px - cx, py - cy)
            if (d < bestDist) {
                bestDist = d
                best = NearestOnRoute(i, LatLng(cy / ky, cx / kx), d)
            }
        }
        return best
    }

    // Distancia real restante (metros) por la ruta hasta el destino. Si el
    // auto esta fuera de la ruta (mas de 150 m de la polilinea), devuelve la
    // distancia directa al destino en vez de inventar un tramo de ruta.
    fun remainingRoadDistanceMeters(currentPos: LatLng, route: ActiveNavRoute): Double {
        val pts = route.roadPoints
        if (pts.isEmpty()) return calculateDistanceMeters(currentPos, route.destinationPoint)
        val nearest = nearestOnRoute(currentPos, route)
        if (nearest == null || nearest.distanceMeters > 150.0) {
            return calculateDistanceMeters(currentPos, route.destinationPoint)
        }
        var remaining = calculateDistanceMeters(nearest.closest, pts[nearest.segmentIndex + 1])
        for (i in nearest.segmentIndex + 1 until pts.size - 1) {
            remaining += calculateDistanceMeters(pts[i], pts[i + 1])
        }
        return remaining
    }

    // Puntos de la ruta que faltan por recorrer, desde el punto mas cercano
    // hasta el destino. Sirve para recortar la polilinea mientras se avanza.
    // Si el auto esta fuera de la ruta, devuelve la ruta completa.
    fun remainingRoadPoints(currentPos: LatLng, route: ActiveNavRoute): List<LatLng> {
        val pts = route.roadPoints
        if (pts.isEmpty()) return pts
        val nearest = nearestOnRoute(currentPos, route)
        if (nearest == null || nearest.distanceMeters > 150.0) return pts
        val out = ArrayList<LatLng>(pts.size - nearest.segmentIndex)
        out.add(nearest.closest)
        for (i in nearest.segmentIndex + 1 until pts.size) out.add(pts[i])
        return out
    }

    // Tiempo restante estimado (segundos): la duracion de Routes API escalada
    // por la fraccion de ruta que falta. Suave y sin saltos (no depende de la
    // velocidad instantanea, que en un semaforo es 0).
    fun remainingEtaSeconds(currentPos: LatLng, route: ActiveNavRoute): Double {
        if (route.totalDistanceMeters <= 0.0) return route.totalDurationSeconds
        val remaining = remainingRoadDistanceMeters(currentPos, route)
        val ratio = (remaining / route.totalDistanceMeters).coerceIn(0.0, 1.0)
        return route.totalDurationSeconds * ratio
    }

    private fun calculateDistanceMeters(p1: LatLng, p2: LatLng): Double {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(p2.latitude - p1.latitude)
        val dLon = Math.toRadians(p2.longitude - p1.longitude)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(p1.latitude)) * Math.cos(Math.toRadians(p2.latitude)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c
    }
}

/**
 * Decodifica una polilinea codificada de Google (algoritmo de codificacion
 * de polilineas) a una lista de LatLng.
 */
internal fun decodePolyline(encoded: String): List<LatLng> {
    val points = ArrayList<LatLng>()
    var index = 0
    var lat = 0
    var lng = 0
    while (index < encoded.length) {
        var shift = 0
        var result = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        val dLat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
        lat += dLat

        shift = 0
        result = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        val dLng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
        lng += dLng

        points.add(LatLng(lat / 1E5, lng / 1E5))
    }
    return points
}
