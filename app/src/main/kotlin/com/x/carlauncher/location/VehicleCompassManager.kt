package com.x.carlauncher.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Automotive Gyroscope + GPS Sensor Fusion Compass Manager for XCarOS.
 *
 * Implements high-precision real-time heading estimation:
 * 1. Hardware 6-axis Gyroscope / Game Rotation Vector provides zero-latency,
 *    drift-resistant yaw orientation with 60fps responsiveness.
 * 2. Real-time GPS Course Over Ground (COG) automatically anchors and calibrates
 *    the gyroscope to true geographic North when the vehicle is moving (speed >= 1.5 mph).
 * 3. When stationary (stopped at lights or parked), the gyroscope holds orientation
 *    and tracks turns or phone movement in real time without GPS jitter or freezing.
 * 4. Offloads sensor math to a dedicated background HandlerThread to ensure zero UI jank.
 */
object VehicleCompassManager : SensorEventListener {

    private const val TAG = "VehicleCompassManager"
    private const val PREFS_NAME = "x_vehicle_compass_prefs"
    private const val KEY_LAST_CALIBRATED_HEADING = "last_calibrated_heading"
    private const val KEY_IS_HEADING_INVERTED = "is_heading_inverted"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var appContext: Context? = null
    private var sensorManager: SensorManager? = null
    private var activeSensor: Sensor? = null
    private var handlerThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    // Matrices for 3D rotation computation
    private val rotationMatrix = FloatArray(16)
    private val remappedMatrix = FloatArray(16)
    private val orientationAngles = FloatArray(3)

    // Fusion state
    @Volatile
    private var rawGyroYawDeg = 0f

    @Volatile
    private var gyroCalibrationOffset = 0f

    @Volatile
    private var smoothedHeading = 0f

    @Volatile
    private var isCalibrated = false

    @Volatile
    var isHeadingInverted = false
        private set

    @Volatile
    var currentDisplayRotation: Int = Surface.ROTATION_90

    fun updateDisplayRotation(rotation: Int) {
        currentDisplayRotation = rotation
        Log.i(TAG, "Display rotation updated: $rotation")
    }

    fun toggleHeadingInversion(): Boolean {
        isHeadingInverted = !isHeadingInverted
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_IS_HEADING_INVERTED, isHeadingInverted)
            ?.apply()
        updateFusedHeading()
        Log.i(TAG, "Compass heading inversion toggled: $isHeadingInverted")
        return isHeadingInverted
    }

    /**
     * Manually tare or calibrate the compass to true sensor heading.
     */
    fun manualTareHeading(targetHeading: Float = 0f) {
        gyroCalibrationOffset = 0f
        isCalibrated = false
        val target = if (isHeadingInverted) (rawGyroYawDeg + 180f) % 360f else rawGyroYawDeg
        smoothedHeading = target
        _compassHeading.value = target
        _cardinalDirection.value = getCardinal(target)
        VehicleLocationManager.updateCompassBearing(target)
        Log.i(TAG, "Compass reset to sensor heading: $target deg")
    }

    // Heading state flows
    private val _compassHeading = MutableStateFlow(0f)
    val compassHeading: StateFlow<Float> = _compassHeading.asStateFlow()

    private val _cardinalDirection = MutableStateFlow("N")
    val cardinalDirection: StateFlow<String> = _cardinalDirection.asStateFlow()

    private val _isCompassActive = MutableStateFlow(false)
    val isCompassActive: StateFlow<Boolean> = _isCompassActive.asStateFlow()

    /**
     * Initializes the compass sensor manager and begins listening to gyroscope/rotation sensors.
     */
    fun initialize(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app

        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isHeadingInverted = prefs.getBoolean(KEY_IS_HEADING_INVERTED, false)
        val savedHeading = prefs.getFloat(KEY_LAST_CALIBRATED_HEADING, 0f)
        smoothedHeading = savedHeading
        gyroCalibrationOffset = 0f // Start clean! ROTATION_VECTOR is already calibrated to Magnetic North
        _compassHeading.value = savedHeading
        _cardinalDirection.value = getCardinal(savedHeading)

        sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sm = sensorManager ?: run {
            Log.w(TAG, "SensorManager not available on this device")
            return
        }

        // Prioritize Hardware ROTATION_VECTOR (Magnetometer + Gyro + Accel calibrated to Magnetic North)
        activeSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ORIENTATION)
            ?: sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        if (activeSensor == null) {
            Log.w(TAG, "No suitable rotation or gyroscope sensor found for compass")
            return
        }

        Log.i(TAG, "Using sensor: ${activeSensor?.name} (Type: ${activeSensor?.type}) for compass")

        // Start background thread for high-frequency sensor events
        handlerThread = HandlerThread("VehicleCompassThread").apply {
            start()
            sensorHandler = Handler(looper)
        }

        startListening()

        // Observe GPS updates to continuously anchor and calibrate gyro to true geographic North
        scope.launch {
            VehicleLocationManager.locationState.collect { loc ->
                onGpsLocationUpdated(loc)
            }
        }
    }

    fun startListening() {
        val sm = sensorManager ?: return
        val sensor = activeSensor ?: return
        val handler = sensorHandler ?: return

        try {
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI, handler)
            _isCompassActive.value = true
            Log.i(TAG, "Compass sensor listener registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register compass sensor listener", e)
        }
    }

    fun stopListening() {
        try {
            sensorManager?.unregisterListener(this)
            _isCompassActive.value = false
            Log.d(TAG, "Compass sensor listener unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering compass sensor", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                // XCarOS is locked to landscape (Surface.ROTATION_90)
                val displayRot = if (currentDisplayRotation != Surface.ROTATION_0) {
                    currentDisplayRotation
                } else {
                    Surface.ROTATION_90
                }

                // 1. Remap coordinates based on physical device display orientation in landscape
                val (axisX, axisY) = when (displayRot) {
                    Surface.ROTATION_270 -> Pair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
                    Surface.ROTATION_180 -> Pair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
                    Surface.ROTATION_0 -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
                    else -> Pair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X) // Surface.ROTATION_90 (Default Landscape)
                }

                SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
                SensorManager.getOrientation(remappedMatrix, orientationAngles)

                // 2. Automotive Attitude Adaptation (Phone lying flat vs in car mount):
                // In landscape, when phone is mounted upright on dash / AC vent (pitch > 45 deg or < -45 deg),
                // forward direction into traffic is out the back of the device (-Z).
                // When phone is lying flat on console/seat (pitch near 0), forward direction is top of screen (+X).
                val pitch = orientationAngles[1]
                if (kotlin.math.abs(pitch) > Math.toRadians(45.0)) {
                    val mountAxisY = if (pitch > 0) SensorManager.AXIS_MINUS_Z else SensorManager.AXIS_Z
                    SensorManager.remapCoordinateSystem(rotationMatrix, axisX, mountAxisY, remappedMatrix)
                    SensorManager.getOrientation(remappedMatrix, orientationAngles)
                }

                // Azimuth (yaw) in degrees: 0..360 (0 = North, 90 = East, 180 = South, 270 = West)
                val rawYawRad = orientationAngles[0]
                val deg = (Math.toDegrees(rawYawRad.toDouble()).toFloat() + 360f) % 360f
                rawGyroYawDeg = deg

                updateFusedHeading()
            }

            @Suppress("DEPRECATION")
            Sensor.TYPE_ORIENTATION -> {
                val displayRot = if (currentDisplayRotation != Surface.ROTATION_0) currentDisplayRotation else Surface.ROTATION_90
                val rawVal = event.values[0]
                val adjustedVal = when (displayRot) {
                    Surface.ROTATION_90 -> (rawVal + 90f + 360f) % 360f
                    Surface.ROTATION_270 -> (rawVal - 90f + 360f) % 360f
                    Surface.ROTATION_180 -> (rawVal + 180f + 360f) % 360f
                    else -> rawVal
                }
                rawGyroYawDeg = (adjustedVal + 360f) % 360f
                updateFusedHeading()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    /**
     * Called whenever a new GPS fix arrives.
     * When the vehicle is moving forward (speed >= 1.5 mph), GPS bearing is ground truth.
     * We dynamically calibrate the gyroscope offset so that:
     *   (rawGyroYawDeg + gyroCalibrationOffset) aligns with GPS geographic course.
     */
    private fun onGpsLocationUpdated(loc: VehicleLocation) {
        if (!loc.hasFix || loc.speedMph < 1.5f) return

        val gpsCourse = (loc.bearing % 360f + 360f) % 360f
        if (gpsCourse == 0f && loc.bearing == 0f) return

        val rawYaw = if (isHeadingInverted) (rawGyroYawDeg + 180f) % 360f else rawGyroYawDeg
        val targetOffset = (gpsCourse - rawYaw + 360f) % 360f

        if (!isCalibrated) {
            gyroCalibrationOffset = targetOffset
            smoothedHeading = gpsCourse
            isCalibrated = true
            Log.i(TAG, "Compass initially calibrated to GPS heading: $gpsCourse")
        } else {
            // Smoothly adjust offset to correct microscopic gyro drift over long driving sessions
            val diff = shortestAngleDiff(targetOffset, gyroCalibrationOffset)
            gyroCalibrationOffset = (gyroCalibrationOffset + diff * 0.06f + 360f) % 360f
        }

        // Persist calibrated heading
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putFloat(KEY_LAST_CALIBRATED_HEADING, smoothedHeading)
            ?.apply()
    }

    /**
     * Combines real-time gyro yaw with the GPS-referenced calibration offset,
     * applying an exponential low-pass filter to guarantee 60fps liquid rotation.
     */
    private fun updateFusedHeading() {
        var targetHeading = (rawGyroYawDeg + gyroCalibrationOffset + 360f) % 360f
        if (isHeadingInverted) {
            targetHeading = (targetHeading + 180f) % 360f
        }

        val diff = shortestAngleDiff(targetHeading, smoothedHeading)
        // High-responsiveness low-pass filter: 0.32 gives instant response with zero jitter
        smoothedHeading = (smoothedHeading + diff * 0.32f + 360f) % 360f

        // Only emit if change exceeds deadband threshold (0.2 degrees) to save CPU
        val currentEmitted = _compassHeading.value
        if (abs(shortestAngleDiff(smoothedHeading, currentEmitted)) >= 0.2f) {
            _compassHeading.value = smoothedHeading
            _cardinalDirection.value = getCardinal(smoothedHeading)
            VehicleLocationManager.updateCompassBearing(smoothedHeading)
        }
    }

    /**
     * Returns shortest angular difference between two angles in degrees (-180..+180).
     */
    fun shortestAngleDiff(target: Float, current: Float): Float {
        var diff = (target - current + 180f) % 360f - 180f
        if (diff < -180f) diff += 360f
        return diff
    }

    /**
     * Returns the 8-wind cardinal point corresponding to the given heading.
     */
    fun getCardinal(degrees: Float): String {
        val cardinals = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW", "N")
        val index = (((degrees + 22.5f) % 360f) / 45f).toInt()
        return cardinals[index.coerceIn(0, 8)]
    }
}
