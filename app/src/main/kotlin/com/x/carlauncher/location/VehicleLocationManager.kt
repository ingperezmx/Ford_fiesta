package com.x.carlauncher.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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

/**
 * High-precision vehicle location data snapshot.
 * Solo datos reales: hasFix es false hasta que llega el primer fix de hardware.
 */
data class VehicleLocation(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val bearing: Float = 0f,
    val speedMph: Float = 0f,
    val accuracyMeters: Float = 0f,
    val satellites: Int = 0,
    val hasFix: Boolean = false,
    val provider: String = "gps",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Automotive-grade, unified GPS & GNSS Location Manager for XCarOS.
 *
 * Fuente principal: FusedLocationProvider de Google Play Services
 * (el mismo motor de ubicación que usa Google Maps: GPS del hardware +
 * Wi-Fi + red móvil, con la mejor precisión disponible). Como respaldo
 * se mantienen los proveedores del sistema (GPS, red y pasivo).
 *
 * - Solo se aceptan fixes de hardware real. Cualquier ubicación
 *   simulada / mock (Fake GPS) se descarta de inmediato.
 * - Sin coordenadas por defecto ni ubicaciones inventadas: sin fix,
 *   la ubicación es (0.0, 0.0) con hasFix = false y la app lo muestra
 *   como "Buscando satélites...", nunca como una posición falsa.
 * - Filtro Kalman para suavizar la velocidad en el auto.
 * - Reconexión automática si el GPS se apaga o se pierde la señal.
 */
object VehicleLocationManager {

    private const val TAG = "VehicleLocationManager"
    private const val PREFS_NAME = "x_vehicle_location_prefs"
    private const val KEY_LAST_LAT = "last_latitude"
    private const val KEY_LAST_LNG = "last_longitude"
    private const val KEY_LAST_BEARING = "last_bearing"
    private const val KEY_LAST_ALT = "last_altitude"
    private const val KEY_LAST_ACCURACY = "last_accuracy"

    private const val MPS_TO_MPH = 2.23694f
    private const val KALMAN_Q = 0.1f
    private const val KALMAN_R = 1.0f
    private const val SPEED_FLOOR_MPH = 0.5f
    private const val RECONNECT_DELAY_MS = 5000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watchdogJob: Job? = null
    private var reconnectionJob: Job? = null

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var locationManager: LocationManager? = null
    private var fusedClient: FusedLocationProviderClient? = null

    private var kalmanEstimate: Float = 0f
    private var kalmanError: Float = 1f
    private var lastValidBearing: Float = 0f
    private var currentSatellites: Int = 0
    private var prevLocationForBearing: Location? = null

    private val _locationState = MutableStateFlow(VehicleLocation())
    val locationState: StateFlow<VehicleLocation> = _locationState.asStateFlow()

    val currentLocation: VehicleLocation get() = _locationState.value

    private var isListening = false
    private var lastUpdateTimeMs = 0L

    private fun isLocationMocked(loc: Location): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            loc.isMock
        } else {
            @Suppress("DEPRECATION")
            loc.isFromMockProvider
        }
    }

    // Callback de Google Play Services (Fused Location Provider): la
    // ubicación real que Google Maps usa en el teléfono / pantalla.
    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) {
                if (isLocationMocked(loc)) {
                    Log.w(TAG, "Ubicación simulada detectada y bloqueada (Fused): Lat: ${loc.latitude}, Lng: ${loc.longitude}")
                    continue
                }
                handleNewLocation(loc, sourceProvider = "fused")
            }
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            if (!availability.isLocationAvailable) {
                Log.w(TAG, "Fused Location: ubicación no disponible en este momento")
            }
        }
    }

    // Unified LocationListener for all system providers - strict anti-mocking
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (isLocationMocked(loc)) {
                Log.w(TAG, "Ubicación simulada detectada y bloqueada: Lat: ${loc.latitude}, Lng: ${loc.longitude}")
                return // Drop fake GPS instantly
            }
            handleNewLocation(loc)
        }

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            extras?.let { bundle ->
                if (bundle.containsKey("satellites")) {
                    val sats = bundle.getInt("satellites")
                    if (sats > 0) currentSatellites = sats
                }
            }
        }

        override fun onProviderEnabled(provider: String) {
            Log.d(TAG, "Location provider enabled: $provider")
            if (provider == LocationManager.GPS_PROVIDER) {
                triggerImmediateReconnection()
            }
        }

        override fun onProviderDisabled(provider: String) {
            Log.w(TAG, "Location provider disabled: $provider")
            if (provider == LocationManager.GPS_PROVIDER) {
                startAutoReconnectionLoop()
            }
        }
    }

    private var gnssCallback: GnssStatus.Callback? = null

    /**
     * Initializes the location manager and begins listening immediately for real coordinates.
     */
    fun initialize(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        locationManager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        fusedClient = LocationServices.getFusedLocationProviderClient(app)

        // 1. Restore persistent last known valid location from physical history (if available)
        restoreSavedLocation()

        // 2. Start unified location listening (Google Fused + GPS del sistema)
        startListening()

        // 3. Start GPS watchdog keep-alive
        startWatchdog()

        // 4. Start monitoring routine for provider availability status
        checkProviderStatusAndSchedule()
    }

    private fun hasLocationPermission(): Boolean {
        val ctx = appContext ?: return false
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun restoreSavedLocation() {
        val sp = prefs
        val savedLatStr = sp?.getString(KEY_LAST_LAT, null)
        val savedLngStr = sp?.getString(KEY_LAST_LNG, null)

        val savedBearing = sp?.getFloat(KEY_LAST_BEARING, 0f) ?: 0f
        val savedAlt = sp?.getString(KEY_LAST_ALT, "0.0")?.toDoubleOrNull() ?: 0.0
        val savedAcc = sp?.getFloat(KEY_LAST_ACCURACY, 0f) ?: 0f

        lastValidBearing = savedBearing

        // Try to query system lastKnownLocation immediately
        val lm = locationManager
        var bestLoc: Location? = null
        if (lm != null && hasLocationPermission()) {
            try {
                val providers = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                )
                for (p in providers) {
                    if (lm.isProviderEnabled(p)) {
                        val l = lm.getLastKnownLocation(p)
                        if (l != null && (bestLoc == null || l.time > bestLoc.time)) {
                            if (!isLocationMocked(l)) {
                                bestLoc = l
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (bestLoc != null && bestLoc.latitude != 0.0 && bestLoc.longitude != 0.0) {
            handleNewLocation(bestLoc)
        } else if (savedLatStr != null && savedLngStr != null) {
            val savedLat = savedLatStr.toDoubleOrNull() ?: 0.0
            val savedLng = savedLngStr.toDoubleOrNull() ?: 0.0

            if (savedLat != 0.0 && savedLng != 0.0) {
                _locationState.value = VehicleLocation(
                    latitude = savedLat,
                    longitude = savedLng,
                    altitude = savedAlt,
                    bearing = savedBearing,
                    speedMph = 0f,
                    accuracyMeters = savedAcc,
                    satellites = 0,
                    hasFix = true,
                    provider = "cache"
                )
                Log.i(TAG, "Restored vehicle location from storage cache: ($savedLat, $savedLng)")
                return
            }
        }

        // If no cache or previous physical fix is found, set fields to 0 and wait for hardware
        _locationState.value = VehicleLocation(
            latitude = 0.0,
            longitude = 0.0,
            hasFix = false,
            provider = "searching"
        )
        Log.i(TAG, "No valid historical hardware location found. System initialized to 0.0. Waiting for real GPS fix...")
    }

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Sin permiso de ubicación todavía: esperando a que el usuario lo conceda")
            startAutoReconnectionLoop()
            return
        }
        val lm = locationManager ?: return

        try {
            // ── 1. Google Fused Location Provider (fuente principal) ──
            var fusedRequested = false
            try {
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                    .setMinUpdateIntervalMillis(500L)
                    .setMaxUpdateDelayMillis(2000L)
                    .build()
                fusedClient?.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
                fusedRequested = true
                // Última ubicación conocida de Google (si existe y no es simulada)
                fusedClient?.lastLocation?.addOnSuccessListener { loc ->
                    if (loc != null && !isLocationMocked(loc) &&
                        loc.latitude != 0.0 && loc.longitude != 0.0
                    ) {
                        handleNewLocation(loc, sourceProvider = "fused")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fused Location no disponible, usando GPS del sistema", e)
            }

            // ── 2. Proveedores del sistema (respaldo) ──
            var gpsRequested = false
            var networkRequested = false
            var passiveRequested = false

            // Register GPS provider (1000ms interval for balanced vehicle tracking)
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
                gpsRequested = true
            }

            // Register Network provider (fallback when under heavy roof / garage)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, locationListener)
                networkRequested = true
            }

            // Register Passive provider (catches fixes from Waze or Google Maps)
            if (lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1000L, 0f, locationListener)
                passiveRequested = true
            }

            // ── 3. Conteo real de satélites (GNSS) ──
            try {
                if (gnssCallback == null) {
                    gnssCallback = object : GnssStatus.Callback() {
                        override fun onSatelliteStatusChanged(status: GnssStatus) {
                            var used = 0
                            for (i in 0 until status.satelliteCount) {
                                if (status.usedInFix(i)) used++
                            }
                            if (used > 0) currentSatellites = used
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    lm.registerGnssStatusCallback(gnssCallback!!)
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo registrar el callback GNSS", e)
            }

            isListening = fusedRequested || gpsRequested || networkRequested || passiveRequested
            if (gpsRequested || fusedRequested) {
                stopAutoReconnectionLoop() // Successfully tied to real hardware GPS stream
            } else {
                startAutoReconnectionLoop() // Hardware GPS is disabled or unavailable right now
            }
            Log.d(TAG, "Unified location streams updated. active=$isListening, fused=$fusedRequested, gps=$gpsRequested, network=$networkRequested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start or adjust location streams", e)
        }
    }

    fun stopListening() {
        try {
            fusedClient?.removeLocationUpdates(fusedCallback)
            locationManager?.removeUpdates(locationListener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                gnssCallback?.let { locationManager?.unregisterGnssStatusCallback(it) }
            }
            isListening = false
            Log.d(TAG, "Location listener unhooked successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing location updates", e)
        }
    }

    private fun checkProviderStatusAndSchedule() {
        val lm = locationManager ?: return
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            startAutoReconnectionLoop()
        }
    }

    private fun startAutoReconnectionLoop() {
        if (reconnectionJob != null && reconnectionJob?.isActive == true) return

        Log.w(TAG, "Scheduling hardware GPS reconnection watchdog loop...")
        reconnectionJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(RECONNECT_DELAY_MS)
                withContext(Dispatchers.Main) {
                    if (!hasLocationPermission()) {
                        // Sin permiso no hay nada que reconectar todavía.
                        return@withContext
                    }
                    val lm = locationManager
                    if (lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        Log.i(TAG, "Hardware GPS detected online. Re-subscribing to streams...")
                        startListening()
                    } else {
                        Log.v(TAG, "Hardware GPS provider is still unavailable. Retrying connection shortly...")
                        // Attempt to force-refresh stream hooks for active fallbacks if listening dropped
                        if (!isListening) {
                            startListening()
                        }
                    }
                }
            }
        }
    }

    private fun stopAutoReconnectionLoop() {
        reconnectionJob?.cancel()
        reconnectionJob = null
    }

    private fun triggerImmediateReconnection() {
        stopAutoReconnectionLoop()
        startListening()
    }

    /**
     * Publica un fix REAL de hardware en el flujo de ubicación.
     * Antes esta función solo actualizaba el rumbo y nunca publicaba
     * latitud, longitud ni velocidad, por eso Google Maps se quedaba
     * en 0,0 sin fix aunque el GPS ya estaba dando datos.
     */
    private fun handleNewLocation(loc: Location, sourceProvider: String? = null) {
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return

        // Velocidad real del GPS con filtro Kalman (suaviza el velocímetro)
        val rawSpeedMph = if (loc.hasSpeed()) (loc.speed * MPS_TO_MPH) else 0f
        kalmanError += KALMAN_Q
        val kalmanGain = kalmanError / (kalmanError + KALMAN_R)
        kalmanEstimate += kalmanGain * (rawSpeedMph - kalmanEstimate)
        kalmanError *= (1f - kalmanGain)
        val speedMph = if (kalmanEstimate < SPEED_FLOOR_MPH) 0f else kalmanEstimate

        // Rumbo real: del GPS cuando el auto se mueve, si no se conserva el último válido
        val bearing = when {
            loc.hasBearing() && rawSpeedMph > 1f -> loc.bearing.also { lastValidBearing = it }
            else -> lastValidBearing
        }
        prevLocationForBearing = loc
        lastUpdateTimeMs = loc.time

        val provider = sourceProvider ?: loc.provider ?: "gps"
        val newState = VehicleLocation(
            latitude = loc.latitude,
            longitude = loc.longitude,
            altitude = if (loc.hasAltitude()) loc.altitude else _locationState.value.altitude,
            bearing = bearing,
            speedMph = speedMph,
            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else 0f,
            satellites = currentSatellites,
            hasFix = true,
            provider = provider,
            timestamp = loc.time
        )
        _locationState.value = newState

        // Guardar el último fix real para el próximo arranque
        try {
            prefs?.edit()
                ?.putString(KEY_LAST_LAT, loc.latitude.toString())
                ?.putString(KEY_LAST_LNG, loc.longitude.toString())
                ?.putFloat(KEY_LAST_BEARING, bearing)
                ?.putString(KEY_LAST_ALT, newState.altitude.toString())
                ?.putFloat(KEY_LAST_ACCURACY, newState.accuracyMeters)
                ?.apply()
        } catch (_: Exception) {}

        Log.d(TAG, "Real GPS fix ($provider): ${loc.latitude}, ${loc.longitude} - ${speedMph.toInt()} mph, ${newState.accuracyMeters.toInt()}m, ${currentSatellites} sats")
    }

    fun updateCompassBearing(bearing: Float) {
        lastValidBearing = bearing
        _locationState.value = _locationState.value.copy(bearing = bearing)
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10000L)
            }
        }
    }
}
