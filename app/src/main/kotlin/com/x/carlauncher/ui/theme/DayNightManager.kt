package com.x.carlauncher.ui.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.x.carlauncher.location.SolarCalculator
import com.x.carlauncher.location.VehicleLocationManager
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
import java.util.Calendar

/**
 * Senior Automotive Day/Night & Screen Burn-In Protection Manager.
 *
 * Implements:
 * 1. Head Unit Illumination (ILL) wire listener via MCU Broadcasts (faros encendidos)
 * 2. NOAA Astronomical Solar Calculator (Amanecer / Atardecer exacto por coordenadas GPS)
 * 3. 15% Soft Dimming para mitigar fatiga visual y prevenir quemado de pantallas OLED/IPS
 */
class DayNightManager(private val context: Context) {

    companion object {
        private const val TAG = "DayNightManager"

        // Broadcasts de faros (Illumination) emitidos por plataformas automotrices
        private const val ACTION_SYU_ILL = "com.syu.ms.ill"
        private const val ACTION_TW_ILL_ON = "com.tw.ill_on"
        private const val ACTION_TW_ILL_OFF = "com.tw.ill_off"
        private const val ACTION_MTC_ILL = "com.microntek.light"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var gpsObserverJob: Job? = null
    private var timerJob: Job? = null

    private val _isNightMode = MutableStateFlow(false)
    val isNightMode: StateFlow<Boolean> = _isNightMode.asStateFlow()

    private val _dimmingAlpha = MutableStateFlow(1.0f)
    val dimmingAlpha: StateFlow<Float> = _dimmingAlpha.asStateFlow()

    private val _solarInfo = MutableStateFlow<SolarCalculator.SolarTimes?>(null)
    val solarInfo: StateFlow<SolarCalculator.SolarTimes?> = _solarInfo.asStateFlow()

    @Volatile
    private var isHeadlightsActive = false

    private val illReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent?.action ?: return
            Log.d(TAG, "Illumination intent received: ${intent.action}")

            when (intent.action) {
                ACTION_SYU_ILL -> {
                    val illState = intent.getIntExtra("ill", -1)
                    if (illState != -1) {
                        isHeadlightsActive = (illState == 1)
                        reevaluateTheme()
                    }
                }
                ACTION_TW_ILL_ON -> {
                    isHeadlightsActive = true
                    reevaluateTheme()
                }
                ACTION_TW_ILL_OFF -> {
                    isHeadlightsActive = false
                    reevaluateTheme()
                }
                ACTION_MTC_ILL -> {
                    val lightState = intent.getBooleanExtra("light", false)
                    isHeadlightsActive = lightState
                    reevaluateTheme()
                }
            }
        }
    }

    init {
        registerIllReceiver()
        observeGpsAndTimer()
    }

    private fun registerIllReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_SYU_ILL)
            addAction(ACTION_TW_ILL_ON)
            addAction(ACTION_TW_ILL_OFF)
            addAction(ACTION_MTC_ILL)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(illReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(illReceiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not register ILL receiver: ${e.message}")
        }
    }

    private fun observeGpsAndTimer() {
        // 1. Observe real vehicle GPS location
        gpsObserverJob = scope.launch {
            VehicleLocationManager.locationState.collect { loc ->
                evaluateSolarDayNight(loc.latitude, loc.longitude)
            }
        }

        // 2. Periodic minute checker to catch exact sunset/sunrise minutes
        timerJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(60_000L) // Every 1 minute
                val loc = VehicleLocationManager.currentLocation
                evaluateSolarDayNight(loc.latitude, loc.longitude)
            }
        }
    }

    fun setNightMode(isNight: Boolean) {
        _isNightMode.value = isNight
        // 15% de reduccion de brillo / contraste suave
        _dimmingAlpha.value = if (isNight) 0.85f else 1.0f
        Log.i(TAG, "Night mode set to $isNight (Dimming factor: ${_dimmingAlpha.value})")
    }

    /**
     * Calcula con precision astronomica NOAA la puesta y salida de sol
     * en las coordenadas GPS reales del vehiculo.
     */
    fun evaluateSolarDayNight(latitude: Double, longitude: Double) {
        val times = SolarCalculator.calculate(latitude, longitude)
        _solarInfo.value = times
        reevaluateTheme()
    }

    private fun reevaluateTheme() {
        // Headlights wire overrides or supplements solar night
        val shouldBeNight = isHeadlightsActive || (_solarInfo.value?.isNight ?: false)
        setNightMode(shouldBeNight)
    }

    fun unregister() {
        try {
            context.unregisterReceiver(illReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering ILL receiver", e)
        }
        gpsObserverJob?.cancel()
        timerJob?.cancel()
    }
}
