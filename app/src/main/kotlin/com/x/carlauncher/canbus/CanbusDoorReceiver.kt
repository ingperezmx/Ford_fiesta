package com.x.carlauncher.canbus

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── Data class ────────────────────────────────────────────────────────────────

/**
 * Represents the open/closed state of every door, trunk, and hood.
 * `true` = open, `false` = closed.
 */
data class DoorState(
    val frontLeft: Boolean = false,
    val frontRight: Boolean = false,
    val rearLeft: Boolean = false,
    val rearRight: Boolean = false,
    val trunk: Boolean = false,
    val hood: Boolean = false
)

// ── BroadcastReceiver ─────────────────────────────────────────────────────────

/**
 * Modular CANBus door-state interceptor that supports multiple Android
 * head-unit brands/firmware variants:
 *
 * | Brand        | Action                               | Extra format             |
 * |--------------|--------------------------------------|--------------------------|
 * | SYU / FYT    | `com.syu.ms.canbus.DOOR_STATE`       | Int extras per door      |
 * | SYU variant  | `com.syu.ms.canbus.door`             | Int extras per door      |
 * | MTC/Microntek| `com.microntek.canbusdisplay`        | `door_status` byte array |
 * | Topway       | `com.tw.canbus`                      | Boolean extras per door  |
 * | MTC variant  | `microntek.intent.action.CAR_DOOR`   | `door_status` byte array |
 *
 * A **discovery-mode logger** prints every extra so unknown head units can be
 * reverse-engineered. See [formatExtrasDebug].
 *
 * Parsed [DoorState] is emitted on the companion [doorState] flow.
 */
class CanbusDoorReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "XCAR_CANBUS"

        // ── Intent actions ───────────────────────────────────────────────────
        private const val ACTION_SYU_DOOR       = "com.syu.ms.canbus.DOOR_STATE"
        private const val ACTION_SYU_DOOR_V2    = "com.syu.ms.canbus.door"
        private const val ACTION_MTC_CANBUS     = "com.microntek.canbusdisplay"
        private const val ACTION_TOPWAY_CANBUS  = "com.tw.canbus"
        private const val ACTION_MTC_CAR_DOOR   = "microntek.intent.action.CAR_DOOR"

        // ── SYU extra keys (Int, 1 = open) ──────────────────────────────────
        private const val SYU_DOOR_FL    = "door_fl"
        private const val SYU_DOOR_FR    = "door_fr"
        private const val SYU_DOOR_RL    = "door_rl"
        private const val SYU_DOOR_RR    = "door_rr"
        private const val SYU_DOOR_TRUNK = "door_trunk"

        // ── MTC extra key (byte array, bit-per-door) ─────────────────────────
        private const val MTC_DOOR_STATUS = "door_status"

        // ── Topway extra keys (Boolean) ──────────────────────────────────────
        private const val TW_DOOR_FL = "fl"
        private const val TW_DOOR_FR = "fr"
        private const val TW_DOOR_RL = "rl"
        private const val TW_DOOR_RR = "rr"

        // ── Shared state flow ────────────────────────────────────────────────
        private val _doorState = MutableStateFlow(DoorState())

        /** Real-time [DoorState], updated on every matching CANBus broadcast or OBD telemetry. */
        val doorState: StateFlow<DoorState> = _doorState

        /**
         * Actualiza el estado de las puertas desde el gestor OBD-II o CANBus real.
         */
        fun updateDoorState(newState: DoorState) {
            _doorState.value = newState
        }

        /**
         * Actualiza una o mas puertas individualmente manteniendo el estado de las demas.
         */
        fun updateDoor(
            frontLeft: Boolean? = null,
            frontRight: Boolean? = null,
            rearLeft: Boolean? = null,
            rearRight: Boolean? = null,
            trunk: Boolean? = null,
            hood: Boolean? = null
        ) {
            val current = _doorState.value
            _doorState.value = current.copy(
                frontLeft = frontLeft ?: current.frontLeft,
                frontRight = frontRight ?: current.frontRight,
                rearLeft = rearLeft ?: current.rearLeft,
                rearRight = rearRight ?: current.rearRight,
                trunk = trunk ?: current.trunk,
                hood = hood ?: current.hood
            )
        }

        /** Build the [IntentFilter] covering all supported actions. */
        fun buildIntentFilter(): IntentFilter = IntentFilter().apply {
            addAction(ACTION_SYU_DOOR)
            addAction(ACTION_SYU_DOOR_V2)
            addAction(ACTION_MTC_CANBUS)
            addAction(ACTION_TOPWAY_CANBUS)
            addAction(ACTION_MTC_CAR_DOOR)
        }

        /**
         * **Discovery-mode helper** - formats every extra in the given [Bundle]
         * as a human-readable string for logcat / debug overlay.
         *
         * Example output:
         * ```
         * door_fl=1 (Int) | door_fr=0 (Int) | door_status=[0x03] (ByteArray)
         * ```
         */
        fun formatExtrasDebug(extras: Bundle?): String {
            if (extras == null) return "<no extras>"
            return extras.keySet().joinToString(separator = " | ") { key ->
                val value = extras.get(key)
                val typeName = when (value) {
                    is ByteArray  -> "ByteArray[${value.size}]"
                    is BooleanArray -> "BooleanArray"
                    is IntArray   -> "IntArray"
                    else          -> value?.javaClass?.simpleName ?: "null"
                }
                val display = when (value) {
                    is ByteArray -> value.joinToString(",", "[0x", "]") {
                        String.format("%02X", it)
                    }
                    is BooleanArray -> value.toList().toString()
                    is IntArray -> value.toList().toString()
                    else -> value.toString()
                }
                "$key=$display ($typeName)"
            }
        }
    }

    // ── onReceive ────────────────────────────────────────────────────────────

    override fun onReceive(context: Context?, intent: Intent?) {
        intent ?: return
        val extras = intent.extras

        // ── Discovery-mode logging ───────────────────────────────────────────
        Log.d(TAG, "Action: ${intent.action} | Extras: ${formatExtrasDebug(extras)}")

        val newState: DoorState = when (intent.action) {
            ACTION_SYU_DOOR,
            ACTION_SYU_DOOR_V2  -> parseSyu(extras)

            ACTION_MTC_CANBUS,
            ACTION_MTC_CAR_DOOR -> parseMtc(extras)

            ACTION_TOPWAY_CANBUS -> parseTopway(extras)

            else -> {
                Log.w(TAG, "Unknown CANBus action: ${intent.action}")
                return
            }
        }

        Log.i(TAG, "DoorState updated: $newState")
        _doorState.value = newState
    }

    // ── Parsers ──────────────────────────────────────────────────────────────

    /**
     * Parse SYU/FYT format: individual Int extras per door (1 = open, 0 = closed).
     */
    private fun parseSyu(extras: Bundle?): DoorState {
        if (extras == null) return DoorState()
        return DoorState(
            frontLeft  = extras.getInt(SYU_DOOR_FL,    0) == 1,
            frontRight = extras.getInt(SYU_DOOR_FR,    0) == 1,
            rearLeft   = extras.getInt(SYU_DOOR_RL,    0) == 1,
            rearRight  = extras.getInt(SYU_DOOR_RR,    0) == 1,
            trunk      = extras.getInt(SYU_DOOR_TRUNK, 0) == 1,
            hood       = false   // SYU format does not expose hood
        )
    }

    /**
     * Parse Microntek/MTC format: single `door_status` byte array.
     *
     * Byte 0 bit layout:
     * ```
     * bit 0 → Front Left
     * bit 1 → Front Right
     * bit 2 → Rear Left
     * bit 3 → Rear Right
     * bit 4 → Trunk
     * bit 5 → Hood (if supported)
     * ```
     */
    private fun parseMtc(extras: Bundle?): DoorState {
        if (extras == null) return DoorState()
        val statusBytes = extras.getByteArray(MTC_DOOR_STATUS)
        if (statusBytes == null || statusBytes.isEmpty()) {
            Log.w(TAG, "MTC door_status extra missing or empty")
            return DoorState()
        }
        val byte0 = statusBytes[0].toInt()
        return DoorState(
            frontLeft  = (byte0 and 0x01) != 0,
            frontRight = (byte0 and 0x02) != 0,
            rearLeft   = (byte0 and 0x04) != 0,
            rearRight  = (byte0 and 0x08) != 0,
            trunk      = (byte0 and 0x10) != 0,
            hood       = (byte0 and 0x20) != 0
        )
    }

    /**
     * Parse Topway format: Boolean extras per door.
     */
    private fun parseTopway(extras: Bundle?): DoorState {
        if (extras == null) return DoorState()
        return DoorState(
            frontLeft  = extras.getBoolean(TW_DOOR_FL, false),
            frontRight = extras.getBoolean(TW_DOOR_FR, false),
            rearLeft   = extras.getBoolean(TW_DOOR_RL, false),
            rearRight  = extras.getBoolean(TW_DOOR_RR, false),
            trunk      = false,  // Topway format does not expose trunk
            hood       = false
        )
    }
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * ViewModel that registers [CanbusDoorReceiver] and exposes [doorState].
 *
 * Lifecycle is tied to the ViewModel scope; the receiver is automatically
 * unregistered in [onCleared].
 */
class DoorStateViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DoorStateViewModel"
    }

    private val context: Context = application.applicationContext
    private val receiver = CanbusDoorReceiver()
    private var receiverRegistered = false

    /** Live [DoorState] from CANBus broadcasts. */
    val doorState: StateFlow<DoorState> = CanbusDoorReceiver.doorState

    /** Formatted debug string of the latest extras (updated per broadcast). */
    private val _debugExtras = MutableStateFlow("")
    val debugExtras: StateFlow<String> = _debugExtras.asStateFlow()

    init {
        registerReceiver()
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    receiver,
                    CanbusDoorReceiver.buildIntentFilter(),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(receiver, CanbusDoorReceiver.buildIntentFilter())
            }
            receiverRegistered = true
            Log.d(TAG, "CanbusDoorReceiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register CanbusDoorReceiver", e)
        }
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(receiver)
            receiverRegistered = false
            Log.d(TAG, "CanbusDoorReceiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister CanbusDoorReceiver", e)
        }
    }

    /**
     * Manually trigger a debug-formatted dump of any arbitrary extras bundle.
     * Logs any received broadcast extras for debugging.
     */
    fun logExtras(extras: android.os.Bundle?) {
        val formatted = CanbusDoorReceiver.formatExtrasDebug(extras)
        viewModelScope.launch { _debugExtras.emit(formatted) }
        Log.d("XCAR_CANBUS", "Debug extras: $formatted")
    }

    override fun onCleared() {
        unregisterReceiver()
        super.onCleared()
    }
}
