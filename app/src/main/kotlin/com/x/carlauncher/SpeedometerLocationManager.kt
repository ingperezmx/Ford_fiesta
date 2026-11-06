package com.x.carlauncher

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.x.carlauncher.location.VehicleLocationManager
import com.x.carlauncher.obd.ObdData
import com.x.carlauncher.obd.ObdManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * SpeedometerViewModel
 *
 * Hybrid Speedometer: Prioritizes zero-latency hardware OBD-II CAN bus telemetry
 * from the vehicle transmission sensor when connected, falling back seamlessly
 * to unified GPS [VehicleLocationManager] with Kalman filtering.
 */
class SpeedometerViewModel(application: Application) : AndroidViewModel(application) {

    private val _speedMph = MutableStateFlow(0f)
    val speedMph: StateFlow<Float> = _speedMph.asStateFlow()

    private val _bearing = MutableStateFlow(0f)
    val bearing: StateFlow<Float> = _bearing.asStateFlow()

    private val _hasGpsFix = MutableStateFlow(false)
    val hasGpsFix: StateFlow<Boolean> = _hasGpsFix.asStateFlow()

    val obdData: StateFlow<ObdData> = ObdManager.obdData

    init {
        VehicleLocationManager.initialize(application)
        ObdManager.initialize(application)

        // 1. Observe GPS Location (Heading, Satellite Fix, and Fallback Speed)
        viewModelScope.launch {
            VehicleLocationManager.locationState.collect { loc ->
                _bearing.value = loc.bearing
                _hasGpsFix.value = loc.hasFix
                if (!ObdManager.obdData.value.isConnected) {
                    _speedMph.value = loc.speedMph
                }
            }
        }

        // 2. Observe Live OBD-II CAN Telemetry
        viewModelScope.launch {
            ObdManager.obdData.collect { obd ->
                if (obd.isConnected) {
                    _speedMph.value = obd.speedMph
                }
            }
        }
    }

    fun retryObdConnection(address: String? = null) {
        ObdManager.retryConnection(address)
    }

    fun startListening() {
        VehicleLocationManager.startListening()
    }

    fun stopListening() {
        // Shared manager keeps tracking for widgets
    }
}
