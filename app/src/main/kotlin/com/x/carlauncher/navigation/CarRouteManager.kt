package com.x.carlauncher.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.x.carlauncher.location.VehicleLocationManager
import com.x.carlauncher.voice.CarVoiceAssistant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

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
    val location: GeoPoint,
    val maneuver: TurnManeuver = TurnManeuver.STRAIGHT
)

/**
 * Real-road vehicle navigation route following physical streets and avenues.
 */
data class ActiveNavRoute(
    val destinationName: String,
    val destinationPoint: GeoPoint,
    val startPoint: GeoPoint,
    val roadPoints: List<GeoPoint>,
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
 * - Queries Open Source Routing Machine (OSRM) to calculate REAL street routes across Monterrey / Nuevo Leon.
 * - Synchronizes active navigation route across ALL 3 map views.
 * - Allows launching turn-by-turn navigation directly in Google Maps.
 * - Solo datos reales: sin ubicaciones simuladas, sin rutas inventadas. Si OSRM no
 *   responde, no hay ruta (null) en vez de inventar una.
 */
object CarRouteManager {

    private const val TAG = "CarRouteManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
    fun openInGoogleMaps(context: Context, dest: GeoPoint, destName: String? = null) {
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
     * Calculates a real road network route using the OSRM Driving Engine.
     * Follows physical streets, avenues, and highways. Never cuts through buildings or houses.
     */
    fun calculateRoute(
        start: GeoPoint,
        end: GeoPoint,
        destName: String,
        onComplete: ((ActiveNavRoute?) -> Unit)? = null
    ) {
        scope.launch {
            val route = withContext(Dispatchers.IO) {
                fetchRealRoadRoute(start, end, destName)
            }
            if (route != null) {
                _activeRoute.value = route
                _currentInstruction.value = if (route.steps.isNotEmpty()) route.steps.first().instruction else "Ruta trazada a $destName"
                Log.i(TAG, "Real road route calculated: ${route.roadPoints.size} road nodes, ${route.distanceKmFormatted}, ${route.etaMinutesFormatted}")
            }
            onComplete?.invoke(route)
        }
    }

    private fun fetchRealRoadRoute(start: GeoPoint, end: GeoPoint, destName: String): ActiveNavRoute? {
        // 1. Primary OSRM Server (HTTP port 80 - now explicitly permitted in network_security_config)
        val route1 = queryOsrmEndpoint(
            "http://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true",
            start, end, destName
        )
        if (route1 != null) return route1

        // 2. Secondary OSM Germany Routing Server
        val route2 = queryOsrmEndpoint(
            "http://routing.openstreetmap.de/routed-car/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true",
            start, end, destName
        )
        if (route2 != null) return route2

        // Sin ruta inventada: si OSRM no responde, no hay ruta.
        return null
    }

    private fun queryOsrmEndpoint(urlFormat: String, start: GeoPoint, end: GeoPoint, destName: String): ActiveNavRoute? {
        try {
            val urlStr = String.format(
                Locale.US,
                urlFormat,
                start.longitude, start.latitude, end.longitude, end.latitude
            )
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("User-Agent", "XCarOS/1.0 (Android Automotive; Contact: support@pablo.car)")
            }

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()
                conn.disconnect()

                val json = JSONObject(response)
                if (json.optString("code") == "Ok") {
                    val routes = json.getJSONArray("routes")
                    if (routes.length() > 0) {
                        val firstRoute = routes.getJSONObject(0)
                        val totalDistance = firstRoute.optDouble("distance", 5000.0)
                        val totalDuration = firstRoute.optDouble("duration", 600.0)

                        // Parse road geometry coordinates: [[lon, lat], ...]
                        val geometry = firstRoute.getJSONObject("geometry")
                        val coords = geometry.getJSONArray("coordinates")
                        val roadPoints = ArrayList<GeoPoint>(coords.length())
                        for (i in 0 until coords.length()) {
                            val coordPair = coords.getJSONArray(i)
                            val lon = coordPair.getDouble(0)
                            val lat = coordPair.getDouble(1)
                            roadPoints.add(GeoPoint(lat, lon))
                        }

                        // Parse turn-by-turn steps
                        val stepsList = mutableListOf<NavTurnStep>()
                        val legs = firstRoute.optJSONArray("legs")
                        if (legs != null && legs.length() > 0) {
                            val leg0 = legs.getJSONObject(0)
                            val stepsArray = leg0.optJSONArray("steps")
                            if (stepsArray != null) {
                                for (s in 0 until stepsArray.length()) {
                                    val stepObj = stepsArray.getJSONObject(s)
                                    val maneuver = stepObj.optJSONObject("maneuver")
                                    val stepType = maneuver?.optString("type", "turn") ?: "turn"
                                    val modifier = maneuver?.optString("modifier", "") ?: ""
                                    val modLower = modifier.lowercase(Locale.ROOT)
                                    val street = stepObj.optString("name", "Calle")
                                    val dist = stepObj.optDouble("distance", 0.0)
                                    val dur = stepObj.optDouble("duration", 0.0)

                                    val turnManeuver = when {
                                        stepType == "arrive" -> TurnManeuver.ARRIVED
                                        modLower.contains("sharp left") -> TurnManeuver.SHARP_LEFT
                                        modLower.contains("slight left") -> TurnManeuver.SLIGHT_LEFT
                                        modLower.contains("left") -> TurnManeuver.LEFT
                                        modLower.contains("sharp right") -> TurnManeuver.SHARP_RIGHT
                                        modLower.contains("slight right") -> TurnManeuver.SLIGHT_RIGHT
                                        modLower.contains("right") -> TurnManeuver.RIGHT
                                        modLower.contains("uturn") -> TurnManeuver.U_TURN
                                        else -> TurnManeuver.STRAIGHT
                                    }

                                    val inst = when (stepType) {
                                        "depart" -> "Inicie el recorrido por $street"
                                        "arrive" -> "Ha llegado a su destino: $destName"
                                        "turn" -> when (modifier) {
                                            "left" -> "Gire a la izquierda en $street"
                                            "right" -> "Gire a la derecha en $street"
                                            "slight left" -> "Incorporese levemente a la izquierda en $street"
                                            "slight right" -> "Incorporese levemente a la derecha en $street"
                                            "sharp left" -> "Giro pronunciado a la izquierda en $street"
                                            "sharp right" -> "Giro pronunciado a la derecha en $street"
                                            "uturn" -> "Retorno en U en $street"
                                            else -> "Gire en $street"
                                        }
                                        "new name" -> "Continue recto por $street"
                                        else -> "Continue por $street"
                                    }
                                    val mLoc = maneuver?.optJSONArray("location")
                                    val pt = if (mLoc != null && mLoc.length() >= 2) {
                                        GeoPoint(mLoc.getDouble(1), mLoc.getDouble(0))
                                    } else {
                                        start
                                    }
                                    stepsList.add(NavTurnStep(inst, street, dist, dur, pt, turnManeuver))
                                }
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
                }
            } else {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "OSRM query error on $urlFormat", e)
        }
        return null
    }

    /**
     * Starts active navigation along the real road route.
     * Sigue la posicion REAL del GPS (VehicleLocationManager): sin fix muestra
     * "Esperando señal GPS real..."; llega al destino a menos de 40 m.
     * Las maniobras e instrucciones se calculan con las distancias reales
     * desde la posicion GPS hasta cada paso de la ruta OSRM.
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
                val currentPos = GeoPoint(loc.latitude, loc.longitude)

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
        navigationJob?.cancel()
        navigationJob = null
        _currentManeuver.value = TurnManeuver.STRAIGHT
        _targetTurnAngle.value = 0f
        _distanceToNextManeuver.value = 0.0
        _currentInstruction.value = "Navegacion finalizada"
    }

    private fun calculateDistanceMeters(p1: GeoPoint, p2: GeoPoint): Double {
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
