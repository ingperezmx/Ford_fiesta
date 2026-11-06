package com.x.carlauncher.illumination

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.widget.Toast
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
 * Senior Automotive Illumination & Darkness Manager for XCarOS.
 *
 * Controla con precision el encendido y apagado de las luces del vehiculo en la interfaz grafica:
 * 1. Durante el DIA (entre la salida y la puesta de sol astronomica): Las luces permanecen estrictamente APAGADAS.
 * 2. Durante la NOCHE (entre la puesta de sol y la salida de sol astronomica): Las luces se ENCIENDEN.
 * 3. Entrada de senal fisica de faros (FYT ILLUM / com.syu.illum.on / HEADLIGHT_ON): Enciende faros si el conductor los activa fisicamente en el auto.
 * 4. Control manual tactil / voz: Permite a Pablo alternar entre Auto (Dia/Noche), Forzado ON y Forzado OFF.
 *
 * NOTA AUTOMOTRIZ: Se excluye intencionalmente el modo oscuro del sistema operativo Android (UI_MODE_NIGHT_YES)
 * y el sensor de luz en interiores o soporte de parabrisas, evitando falsos positivos que mantendrian las luces
 * encendidas en pleno mediodia.
 */
object VehicleIlluminationManager {

    private const val TAG = "VehicleIllumination"

    // Umbrales de lux para telemetria
    private const val LUX_DARK_THRESHOLD = 25f
    private const val LUX_BRIGHT_THRESHOLD = 38f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var monitorJob: Job? = null

    private var appContext: Context? = null
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null

    private val _isLightsOn = MutableStateFlow(false)
    val isLightsOn: StateFlow<Boolean> = _isLightsOn.asStateFlow()

    private val _isDark = MutableStateFlow(false)
    val isDark: StateFlow<Boolean> = _isDark.asStateFlow()

    private val _isHeadlightsOn = MutableStateFlow(false)
    val isHeadlightsOn: StateFlow<Boolean> = _isHeadlightsOn.asStateFlow()

    private val _currentLux = MutableStateFlow(0f)
    val currentLux: StateFlow<Float> = _currentLux.asStateFlow()

    private val _manualOverride = MutableStateFlow<Boolean?>(null)
    val manualOverride: StateFlow<Boolean?> = _manualOverride.asStateFlow()

    private val _statusDescription = MutableStateFlow("Iniciando deteccion...")
    val statusDescription: StateFlow<String> = _statusDescription.asStateFlow()

    // ── 1. Hardware Light Sensor Listener (Monitoreo pasivo de lux) ───────────
    private val lightSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
                val lux = event.values.getOrNull(0) ?: return
                _currentLux.value = lux

                val currentDark = _isDark.value
                val newDark = when {
                    lux <= LUX_DARK_THRESHOLD -> true
                    lux >= LUX_BRIGHT_THRESHOLD -> false
                    else -> currentDark
                }

                if (newDark != currentDark) {
                    _isDark.value = newDark
                    Log.d(TAG, "Sensor de luz ambiental: lux=$lux -> isDark=$newDark")
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ── 2. Headlight / Illumination Wire Broadcast Receiver ───────────────────
    private val illumBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.d(TAG, "Illumination broadcast received: $action")
            when (action) {
                "com.syu.illum.on",
                "android.intent.action.HEADLIGHT_ON" -> {
                    _isHeadlightsOn.value = true
                    reevaluateState()
                }
                "com.syu.illum.off",
                "android.intent.action.HEADLIGHT_OFF" -> {
                    _isHeadlightsOn.value = false
                    reevaluateState()
                }
                "com.fyt.system.illum" -> {
                    val state = intent.getBooleanExtra("state", false) ||
                                intent.getIntExtra("state", 0) == 1
                    _isHeadlightsOn.value = state
                    reevaluateState()
                }
                "com.x.illum.FORCE_ON" -> {
                    setManual(true)
                }
                "com.x.illum.FORCE_OFF" -> {
                    setManual(false)
                }
                "com.x.illum.AUTO" -> {
                    setManual(null)
                }
            }
        }
    }

    /**
     * Inicializa el pipeline de iluminacion vehicular.
     */
    fun initialize(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app

        // 1. Registro opcional de sensor de luz para telemetria interna
        sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

        if (lightSensor != null) {
            sensorManager?.registerListener(
                lightSensorListener,
                lightSensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            Log.i(TAG, "Sensor de luz ambiental registrado: ${lightSensor?.name}")
        } else {
            Log.d(TAG, "Sin sensor de luz fisico; usando calculo astronomico solar")
        }

        // 2. Registro del receptor de cable de faros (FYT / Android Automotive)
        try {
            val filter = IntentFilter().apply {
                addAction("com.syu.illum.on")
                addAction("com.syu.illum.off")
                addAction("com.fyt.system.illum")
                addAction("android.intent.action.HEADLIGHT_ON")
                addAction("android.intent.action.HEADLIGHT_OFF")
                addAction("com.x.illum.FORCE_ON")
                addAction("com.x.illum.FORCE_OFF")
                addAction("com.x.illum.AUTO")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(illumBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(illumBroadcastReceiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo registrar illumBroadcastReceiver", e)
        }

        // 3. Evaluacion inicial del estado
        reevaluateState()

        // 4. Monitoreo periodico dia/noche cada 15 segundos
        startMonitoringLoop()
    }

    private fun startMonitoringLoop() {
        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(15_000L)
                reevaluateState()
            }
        }
    }

    /**
     * Reevalua si las luces de la imagen del carro deben estar ENCENDIDAS o APAGADAS:
     * - Si hay override manual: respeta la eleccion del usuario (ON u OFF).
     * - Si los faros fisicos del carro estan encendidos (senal de cable ILLUM): ON.
     * - Modo Automatico Solar:
     *     * NOCHE (puesta de sol a salida de sol): ENCENDIDAS.
     *     * DIA (salida de sol a puesta de sol): APAGADAS.
     */
    fun reevaluateState() {
        val override = _manualOverride.value
        if (override != null) {
            _isLightsOn.value = override
            _statusDescription.value = if (override) "Luces forzadas (Manual)" else "Luces apagadas (Manual)"
            return
        }

        val physicalHeadlights = _isHeadlightsOn.value
        val solarNight = isSolarNight()

        // REGLA SOLICITADA POR PABLO:
        // Las luces de la imagen del carro prenden SOLO DE NOCHE y cuando sea de dia se apagan.
        // A menos que los faros fisicos del vehiculo (cable ILLUM) esten encendidos.
        val shouldBeOn = physicalHeadlights || solarNight
        _isLightsOn.value = shouldBeOn

        val reason = when {
            physicalHeadlights -> "Faros fisicos del vehiculo encendidos"
            solarNight -> "Noche (Horario nocturno / Luces encendidas)"
            else -> "Dia (Luz diurna / Luces apagadas)"
        }
        _statusDescription.value = reason
        Log.d(TAG, "Illumination evaluated: shouldBeOn=$shouldBeOn ($reason)")
    }

    /**
     * Alterna control manual: Auto (Dia/Noche) -> Forzado ON -> Forzado OFF -> Auto.
     */
    fun toggleManual(context: Context? = null): Boolean? {
        val next = when (_manualOverride.value) {
            null -> true
            true -> false
            false -> null
        }
        _manualOverride.value = next
        reevaluateState()

        context?.let { ctx ->
            val msg = when (next) {
                true -> "Faros: Forzados Encendidos (Manual)"
                false -> "Faros: Forzados Apagados (Manual)"
                null -> if (_isLightsOn.value) "Faros: Automatico (Noche - Encendidos)" else "Faros: Automatico (Dia - Apagados)"
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }

        return next
    }

    fun setManual(value: Boolean?) {
        _manualOverride.value = value
        reevaluateState()
    }

    /**
     * Actualiza el estado de los faros fisicos detectados por telemetria OBD-II / CANBus (vLinker FD).
     */
    fun setPhysicalHeadlightsState(isOn: Boolean) {
        if (_isHeadlightsOn.value != isOn) {
            _isHeadlightsOn.value = isOn
            reevaluateState()
        }
    }

    /**
     * Determina con precision matematica offline si actualmente es de noche o de dia
     * en base al algoritmo astronomico NOAA y las coordenadas GPS reales del vehiculo.
     */
    fun isSolarNight(calendar: Calendar = Calendar.getInstance()): Boolean {
        val loc = VehicleLocationManager.currentLocation
        if (!loc.hasFix || (loc.latitude == 0.0 && loc.longitude == 0.0)) {
            // Sin fix GPS real no se puede calcular el día/noche:
            // se asume día y no se inventa ninguna ubicación.
            return false
        }

        val solarTimes = SolarCalculator.calculate(loc.latitude, loc.longitude, calendar)
            ?: return false
        return solarTimes.isNight
    }
}
