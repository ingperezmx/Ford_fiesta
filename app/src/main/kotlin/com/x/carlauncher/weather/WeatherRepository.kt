package com.x.carlauncher.weather

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.x.carlauncher.system.NetworkUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

import android.location.Geocoder
import android.location.LocationManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── DataStore extension ────────────────────────────────────────────────────────

private val Context.weatherDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "x_weather_cache"
)

// ── Data classes ──────────────────────────────────────────────────────────────

/**
 * Normalised weather data exposed to the UI.
 *
 * Temperature fields are in degrees Celsius (the API is called with units=metric).
 * Wind speed is converted from m/s to mph before storage here.
 */
data class WeatherData(
    val cityName: String? = "Yarumela",
    val municipality: String? = "La Paz",
    val department: String? = "La Paz",
    val country: String? = "HN",
    val temp: Int = 24,
    val feelsLike: Int = 24,
    val description: String? = "Cielo claro",
    val iconCode: String? = "02d",
    val humidity: Int = 50,
    val windSpeedMph: Double = 5.0,
    val tempMax: Int = 28,
    val tempMin: Int = 20,
    val pressureHpa: Int = 1013,
    val cloudsPercent: Int = 0,
    val lastUpdatedTime: String? = ""
)

// ── OpenWeatherMap response data classes ───────────────────────────────────────

data class WeatherResponse(
    @SerializedName("main")    val main: MainData,
    @SerializedName("weather") val weather: List<WeatherCondition>,
    @SerializedName("wind")    val wind: WindData,
    @SerializedName("clouds")  val clouds: CloudsData? = null,
    @SerializedName("sys")     val sys: SysData? = null,
    @SerializedName("name")    val cityName: String = ""
)

data class MainData(
    @SerializedName("temp")       val temp: Double,
    @SerializedName("feels_like") val feelsLike: Double,
    @SerializedName("temp_max")   val tempMax: Double,
    @SerializedName("temp_min")   val tempMin: Double,
    @SerializedName("humidity")   val humidity: Int,
    @SerializedName("pressure")   val pressure: Int = 1013
)

data class WeatherCondition(
    @SerializedName("description") val description: String,
    @SerializedName("icon")        val icon: String
)

data class WindData(
    @SerializedName("speed") val speed: Double   // m/s when units=metric
)

data class CloudsData(
    @SerializedName("all") val all: Int = 0
)

data class SysData(
    @SerializedName("country") val country: String = ""
)

data class GeoResponseItem(
    @SerializedName("name")    val name: String = "",
    @SerializedName("state")   val state: String? = null,
    @SerializedName("country") val country: String = ""
)

// ── Open-Meteo fallback data classes (100% public & keyless) ─────────────────

data class OpenMeteoResponse(
    @SerializedName("current") val current: OpenMeteoCurrent?
)

data class OpenMeteoCurrent(
    @SerializedName("temperature_2m") val temp: Double = 22.0,
    @SerializedName("relative_humidity_2m") val humidity: Int = 50,
    @SerializedName("surface_pressure") val pressure: Double = 1013.0,
    @SerializedName("wind_speed_10m") val windSpeedKmH: Double = 10.0,
    @SerializedName("weather_code") val weatherCode: Int = 0
)

// ── Retrofit service interface ────────────────────────────────────────────────

interface WeatherApiService {

    /**
     * Fetch current weather from the OpenWeatherMap "current weather" endpoint.
     *
     * @param lat     Latitude  (decimal degrees).
     * @param lon     Longitude (decimal degrees).
     * @param apiKey  Your OWM API key - set [WeatherRepository.API_KEY].
     * @param units   Unit system. Defaults to "metric" (C, m/s).
     * @param lang    Response language. Defaults to "es" (Spanish).
     */
    @GET("weather")
    suspend fun getCurrentWeather(
        @Query("lat")   lat: Double,
        @Query("lon")   lon: Double,
        @Query("appid") apiKey: String,
        @Query("units") units: String = "metric",
        @Query("lang")  lang: String  = "es"
    ): WeatherResponse

    @GET("https://api.openweathermap.org/geo/1.0/reverse")
    suspend fun getReverseGeocode(
        @Query("lat")   lat: Double,
        @Query("lon")   lon: Double,
        @Query("limit") limit: Int = 1,
        @Query("appid") apiKey: String
    ): List<GeoResponseItem>

    @GET("https://api.open-meteo.com/v1/forecast?current=temperature_2m,relative_humidity_2m,surface_pressure,wind_speed_10m,weather_code")
    suspend fun getOpenMeteoWeather(
        @Query("latitude")  lat: Double,
        @Query("longitude") lon: Double
    ): OpenMeteoResponse
}

// â"€â"€ Repository â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€

/**
 * Repository responsible for fetching weather data from OpenWeatherMap and
 * caching the last successful result in [DataStore].
 *
 * **Before shipping, replace [API_KEY] with your real OWM key.**
 */
class WeatherRepository(private val context: Context) {

    companion object {
        private const val TAG = "WeatherRepository"

        // â"€â"€â"€ !! Replace with your real OpenWeatherMap API key !! â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€
        const val API_KEY = "ebed4f78c2bbe6a79ea27f9d1f402f39"
        // â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€

        private const val BASE_URL = "https://api.openweathermap.org/data/2.5/"

        /** Conversion factor: metres-per-second â†' miles-per-hour. */
        private const val MPS_TO_MPH = 2.23694

        private val PREF_CACHED_WEATHER = stringPreferencesKey("cached_weather_json")

        @Volatile
        var latestWeatherData: WeatherData? = null
    }

    private val gson = Gson()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val apiService: WeatherApiService = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(WeatherApiService::class.java)

    // â"€â"€ Public API â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€â"€

    /**
     * Fetches current weather for the given coordinates.
     * On success, the result is persisted to DataStore and returned.
     * On failure, the last cached [WeatherData] is returned (if any).
     *
     * @return [WeatherData] on success or from cache; `null` if both fail.
     */
    suspend fun getWeather(lat: Double, lon: Double): WeatherData? {
        if (!NetworkUtils.isOnline(context)) {
            Log.d(TAG, "Offline mode active: loading cached weather data immediately (0ms network)")
            return loadCachedWeatherData()
        }

        return try {
            val response = apiService.getCurrentWeather(lat, lon, API_KEY)
            val geoItem = try {
                val geoList = apiService.getReverseGeocode(lat, lon, 1, API_KEY)
                geoList.firstOrNull()
            } catch (e: Exception) {
                Log.w(TAG, "OWM Reverse geocode failed", e)
                null
            }
            val weatherData = mapResponseToWeatherData(response, geoItem, lat, lon)
            cacheWeatherData(weatherData)
            Log.d(TAG, "Weather fetched successfully for ($lat, $lon): ${weatherData.cityName}, ${weatherData.department}")
            weatherData
        } catch (owmError: Exception) {
            Log.w(TAG, "OWM request failed ($owmError) - using keyless Open-Meteo fallback...")
            try {
                val openMeteo = apiService.getOpenMeteoWeather(lat, lon)
                val curr = openMeteo.current
                if (curr != null) {
                    val weatherData = mapOpenMeteoToWeatherData(curr, lat, lon)
                    cacheWeatherData(weatherData)
                    Log.d(TAG, "Weather fetched successfully via Open-Meteo (keyless fallback): ${weatherData.cityName}")
                    weatherData
                } else {
                    loadCachedWeatherData()
                }
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Both weather APIs failed - loading from cache", fallbackError)
                loadCachedWeatherData()
            }
        }
    }

    /**
     * Loads the last successfully cached [WeatherData] from DataStore.
     * Returns `null` if the cache is empty or the data is malformed.
     */
    suspend fun loadCachedWeatherData(): WeatherData? {
        return try {
            val prefs = context.weatherDataStore.data.firstOrNull() ?: return null
            val json = prefs[PREF_CACHED_WEATHER] ?: return null
            val cached = gson.fromJson(json, WeatherData::class.java)
            if (cached != null) {
                latestWeatherData = cached
            }
            cached
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached weather data", e)
            null
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun mapResponseToWeatherData(
        response: WeatherResponse,
        geoItem: GeoResponseItem?,
        lat: Double,
        lon: Double
    ): WeatherData {
        val condition = response.weather.firstOrNull()
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

        var geocoderPlace: String? = null
        var geocoderMuni: String? = null
        var geocoderDept: String? = null
        try {
            if (Geocoder.isPresent()) {
                val geocoder = Geocoder(context, Locale("es", "HN"))
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    geocoderPlace = addr.subLocality ?: addr.featureName
                    geocoderMuni = addr.locality ?: addr.subAdminArea
                    geocoderDept = addr.adminArea
                }
            }
        } catch (_: Exception) {}

        val geocoderValidPlace = when {
            geocoderPlace.isNullOrBlank() -> null
            geocoderPlace.contains("+") -> null // Ignore Google Plus Codes like 89Q4+MCR
            else -> geocoderPlace
        }

        val cleanDept = (geocoderDept?.ifBlank { null } ?: geoItem?.state?.ifBlank { null } ?: "La Paz")
            .replace(" Department", "", ignoreCase = true)
            .replace("Departamento de ", "", ignoreCase = true)
            .trim()

        val finalCity = geocoderValidPlace
            ?: response.cityName.ifBlank { null }
            ?: geoItem?.name?.ifBlank { null }
            ?: "Yarumela"

        val finalMuni = geocoderMuni?.ifBlank { null }
            ?: geoItem?.name?.ifBlank { null }
            ?: "La Paz"

        val finalDept = cleanDept

        val finalCountry = response.sys?.country?.ifBlank { null }
            ?: geoItem?.country?.ifBlank { null }
            ?: "HN"

        return WeatherData(
            cityName      = finalCity,
            municipality  = finalMuni,
            department    = finalDept,
            country       = finalCountry,
            temp          = response.main.temp.toInt(),
            feelsLike     = response.main.feelsLike.toInt(),
            description   = (condition?.description?.replaceFirstChar { it.uppercase() } ?: "Cielo claro").replace("a","a").replace("e","e").replace("i","i").replace("o","o").replace("u","u").replace("A","A").replace("E","E").replace("I","I").replace("O","O").replace("U","U").replace("n","n").replace("N","N"),
            iconCode      = condition?.icon ?: "02d",
            humidity      = response.main.humidity,
            windSpeedMph  = response.wind.speed * MPS_TO_MPH,
            tempMax       = response.main.tempMax.toInt(),
            tempMin       = response.main.tempMin.toInt(),
            pressureHpa   = response.main.pressure,
            cloudsPercent = response.clouds?.all ?: 0,
            lastUpdatedTime = timeFormat.format(Date())
        )
    }

    private fun mapOpenMeteoToWeatherData(
        curr: OpenMeteoCurrent,
        lat: Double,
        lon: Double
    ): WeatherData {
        val (desc, icon) = when (curr.weatherCode) {
            0 -> Pair("Cielo despejado", "01d")
            1, 2 -> Pair("Parcialmente nublado", "02d")
            3 -> Pair("Nublado", "04d")
            45, 48 -> Pair("Niebla", "50d")
            51, 53, 55 -> Pair("Llovizna", "09d")
            61, 63, 65, 80, 81, 82 -> Pair("Lluvia", "10d")
            71, 73, 75 -> Pair("Nieve", "13d")
            95, 96, 99 -> Pair("Tormenta electrica", "11d")
            else -> Pair("Despejado", "01d")
        }

        var place = "Ubicacion actual"
        try {
            if (Geocoder.isPresent()) {
                val geocoder = Geocoder(context, Locale.getDefault())
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                if (!addresses.isNullOrEmpty()) {
                    place = addresses[0].locality
                        ?: addresses[0].subAdminArea
                        ?: addresses[0].adminArea
                        ?: place
                }
            }
        } catch (_: Exception) {}

        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return WeatherData(
            cityName = place,
            municipality = place,
            department = "",
            country = "",
            temp = curr.temp.toInt(),
            feelsLike = curr.temp.toInt(),
            description = desc,
            iconCode = icon,
            humidity = curr.humidity,
            windSpeedMph = curr.windSpeedKmH * 0.621371,
            tempMax = curr.temp.toInt() + 2,
            tempMin = curr.temp.toInt() - 2,
            pressureHpa = curr.pressure.toInt(),
            cloudsPercent = if (curr.weatherCode in 1..3) 50 else 10,
            lastUpdatedTime = timeFormat.format(Date())
        )
    }

    private suspend fun cacheWeatherData(data: WeatherData) {
        latestWeatherData = data
        try {
            val json = gson.toJson(data)
            context.weatherDataStore.edit { prefs ->
                prefs[PREF_CACHED_WEATHER] = json
            }
            Log.d(TAG, "Weather data cached")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache weather data", e)
        }
    }
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * ViewModel that holds the current [WeatherData] and auto-refreshes every
 * 10 minutes referencing real-time vehicle location.
 */
class WeatherViewModel(private val application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "WeatherViewModel"
        private const val REFRESH_INTERVAL_MS = 10 * 60 * 1_000L  // 10 minutes

        fun getVehicleCoordinates(context: Context): Pair<Double, Double> {
            try {
                val vehicleLoc = com.x.carlauncher.location.VehicleLocationManager.currentLocation
                if (vehicleLoc.latitude != 0.0 && vehicleLoc.longitude != 0.0) {
                    return Pair(vehicleLoc.latitude, vehicleLoc.longitude)
                }
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                if (lm != null) {
                    val providers = listOf(
                        LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER,
                        LocationManager.PASSIVE_PROVIDER
                    )
                    for (provider in providers) {
                        try {
                            val loc = lm.getLastKnownLocation(provider)
                            if (loc != null && (loc.latitude != 0.0 || loc.longitude != 0.0)) {
                                return Pair(loc.latitude, loc.longitude)
                            }
                        } catch (_: SecurityException) {}
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read location for weather", e)
            }
            return Pair(14.3385, -87.6445) // Real vehicle location (Yarumela, La Paz)
        }
    }

    private val repository = WeatherRepository(application.applicationContext)

    private val _weatherState = MutableStateFlow<WeatherData?>(null)

    /** Current weather data. `null` while loading and if all fetches have failed. */
    val weatherState: StateFlow<WeatherData?> = _weatherState.asStateFlow()

    private var autoRefreshActive = false

    // ── Init - load cache immediately ──────────────────────────────────────────

    private val accReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                com.x.carlauncher.services.AccOffService.ACTION_ACC_OFF -> {
                    Log.i(TAG, "ACC OFF: Pausing weather background auto-refresh to preserve battery")
                    stopAutoRefresh()
                }
                com.x.carlauncher.services.AccOffService.ACTION_ACC_ON -> {
                    Log.i(TAG, "ACC ON: Resuming weather auto-refresh")
                    startAutoRefresh()
                    fetchWeather()
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            val cached = repository.loadCachedWeatherData()
            if (cached != null) {
                _weatherState.value = cached
                Log.d(TAG, "Loaded cached weather on init")
            }
        }

        val filter = android.content.IntentFilter().apply {
            addAction(com.x.carlauncher.services.AccOffService.ACTION_ACC_OFF)
            addAction(com.x.carlauncher.services.AccOffService.ACTION_ACC_ON)
        }
        try {
            application.registerReceiver(accReceiver, filter)
        } catch (_: Exception) {}
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetch weather once for the given coordinates or current GPS position.
     */
    fun fetchWeather(lat: Double = 0.0, lon: Double = 0.0) {
        viewModelScope.launch {
            val coords = if (lat != 0.0 && lon != 0.0) {
                Pair(lat, lon)
            } else {
                getVehicleCoordinates(application.applicationContext)
            }
            val data = repository.getWeather(coords.first, coords.second)
            if (data != null) {
                _weatherState.value = data
            }
        }
    }

    /**
     * Start the 10-minute auto-refresh loop referencing real-time vehicle GPS coordinates.
     */
    fun startAutoRefresh(lat: Double = 14.3385, lon: Double = -87.6445) {
        if (autoRefreshActive) return
        autoRefreshActive = true

        viewModelScope.launch {
            while (autoRefreshActive) {
                val (currentLat, currentLon) = getVehicleCoordinates(application.applicationContext)
                Log.d(TAG, "Auto-refreshing real-time weather (every 10 min) for ($currentLat, $currentLon)")
                val data = repository.getWeather(currentLat, currentLon)
                if (data != null) {
                    _weatherState.value = data
                }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop the auto-refresh loop.
     */
    fun stopAutoRefresh() {
        autoRefreshActive = false
    }

    /**
     * Returns the OWM icon URL for a given [iconCode].
     *
     * Example: `https://openweathermap.org/img/wn/10d@2x.png`
     */
    fun iconUrl(iconCode: String): String =
        "https://openweathermap.org/img/wn/${iconCode}@2x.png"

    override fun onCleared() {
        stopAutoRefresh()
        try {
            application.unregisterReceiver(accReceiver)
        } catch (_: Exception) {}
        super.onCleared()
    }
}

