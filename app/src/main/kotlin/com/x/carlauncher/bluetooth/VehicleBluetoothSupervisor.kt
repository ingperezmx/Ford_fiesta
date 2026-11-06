package com.x.carlauncher.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.x.carlauncher.obd.ObdManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Supervisor automotriz de conexiones Bluetooth persistentes para Ford Fiesta 2016.
 *
 * Mantiene la conexion activa y persistente con dos dispositivos clave:
 * 1. "SCANNER AUTO" (ELM327 OBD2 sobre SPP/RFCOMM) - Telemetria CAN del vehiculo.
 * 2. "SYNC" (Ford SYNC audio y llamadas sobre perfiles A2DP y HFP) - Manos libres y musica OEM.
 *
 * Todo en puro ASCII sin acentos ni caracteres especiales.
 */
object VehicleBluetoothSupervisor {
    private const val TAG = "VehicleBtSupervisor"

    private var appContext: Context? = null
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    private var a2dpProxy: BluetoothA2dp? = null
    private var headsetProxy: BluetoothHeadset? = null

    private val supervisorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var supervisorJob: Job? = null
    private var isRegistered = false

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> {
                    a2dpProxy = proxy as? BluetoothA2dp
                    Log.d(TAG, "Proxy Bluetooth A2DP conectado")
                }
                BluetoothProfile.HEADSET -> {
                    headsetProxy = proxy as? BluetoothHeadset
                    Log.d(TAG, "Proxy Bluetooth Headset conectado")
                }
            }
            triggerImmediateCheck()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = null
                BluetoothProfile.HEADSET -> headsetProxy = null
            }
        }
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
                    if (state == BluetoothAdapter.STATE_ON) {
                        Log.i(TAG, "Adaptador Bluetooth encendido -> Reactivando supervision persistente")
                        openProxies()
                        triggerImmediateCheck()
                        ObdManager.resumeSupervisor()
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val name = try { device?.name ?: "" } catch (_: Exception) { "" }
                    Log.w(TAG, "Dispositivo desconectado de ACL: $name")
                    if (isSyncDevice(name) || isScannerAutoDevice(name)) {
                        Log.i(TAG, "Dispositivo critico ($name) desconectado -> Disparando reconexion rapida")
                        triggerImmediateCheck()
                    }
                }
            }
        }
    }

    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app

        val bm = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothManager = bm
        bluetoothAdapter = bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()

        openProxies()
        registerReceiver(app)
        startPersistentLoop()
        Log.i(TAG, "VehicleBluetoothSupervisor inicializado con exito")
    }

    private fun openProxies() {
        val adapter = bluetoothAdapter ?: return
        try {
            adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.A2DP)
            adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HEADSET)
        } catch (e: Exception) {
            Log.w(TAG, "Error abriendo proxies de perfiles: ${e.message}")
        }
    }

    private fun registerReceiver(context: Context) {
        if (isRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(btStateReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(btStateReceiver, filter)
            }
            isRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Error registrando receptor btStateReceiver: ${e.message}")
        }
    }

    fun isSyncDevice(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val upper = name.uppercase()
        return upper.contains("SYNC") || upper.contains("FORD")
    }

    fun isScannerAutoDevice(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val upper = name.uppercase()
        return upper.contains("SCANNER AUTO") ||
               upper.contains("SCANNER_AUTO") ||
               upper.contains("SCANNER") ||
               upper.contains("OBD") ||
               upper.contains("ELM") ||
               upper.contains("VGATE") ||
               upper.contains("VIE") ||
               upper.contains("VLINK") ||
               upper.contains("FORSCAN")
    }

    private fun startPersistentLoop() {
        supervisorJob?.cancel()
        supervisorJob = supervisorScope.launch {
            while (isActive) {
                try {
                    checkAndMaintainConnections()
                } catch (e: Exception) {
                    Log.w(TAG, "Error en ciclo de supervision: ${e.message}")
                }
                delay(3500L)
            }
        }
    }

    fun triggerImmediateCheck() {
        supervisorScope.launch {
            delay(500L)
            checkAndMaintainConnections()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun checkAndMaintainConnections() = withContext(Dispatchers.IO) {
        val adapter = bluetoothAdapter ?: return@withContext
        if (!adapter.isEnabled) return@withContext

        val bondedDevices = try { adapter.bondedDevices } catch (_: Exception) { null } ?: emptySet()

        // 1. GESTION PERSISTENTE DE FORD SYNC (A2DP + HEADSET)
        val syncDevice = bondedDevices.firstOrNull { isSyncDevice(it.name) }
        if (syncDevice != null) {
            maintainSyncConnection(syncDevice)
        }

        // 2. GESTION PERSISTENTE DE SCANNER AUTO (OBD2 SPP)
        val scannerDevice = bondedDevices.firstOrNull { isScannerAutoDevice(it.name) }
        if (scannerDevice != null) {
            maintainScannerConnection(scannerDevice)
        }
    }

    @SuppressLint("MissingPermission")
    private fun maintainSyncConnection(device: BluetoothDevice) {
        val devName = try { device.name ?: device.address } catch (_: Exception) { device.address }

        // A2DP Check & Reconnect
        val a2dp = a2dpProxy
        if (a2dp != null) {
            val isA2dpConnected = try {
                a2dp.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED ||
                a2dp.connectedDevices.any { it.address.equals(device.address, ignoreCase = true) }
            } catch (_: Exception) { false }

            if (!isA2dpConnected) {
                Log.i(TAG, "SYNC A2DP no conectado -> Reconectando persistentemente a $devName")
                connectProfile(a2dp, device)
            }
        }

        // Headset / HFP Check & Reconnect
        val hfp = headsetProxy
        if (hfp != null) {
            val isHfpConnected = try {
                hfp.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED ||
                hfp.connectedDevices.any { it.address.equals(device.address, ignoreCase = true) }
            } catch (_: Exception) { false }

            if (!isHfpConnected) {
                Log.i(TAG, "SYNC Headset no conectado -> Reconectando persistentemente a $devName")
                connectProfile(hfp, device)
            }
        }
    }

    private var lastScannerAttemptTime = 0L
    private const val SCANNER_INTERVAL_MS = 30 * 60 * 1000L // Intervalo de 30 minutos solicitado por Pablo

    @SuppressLint("MissingPermission")
    private fun maintainScannerConnection(device: BluetoothDevice) {
        val now = System.currentTimeMillis()
        if (now - lastScannerAttemptTime < SCANNER_INTERVAL_MS) {
            return // No reintentar cada 3.5s; respetar el intervalo de 30 minutos
        }
        val isObdConnected = ObdManager.obdData.value.isConnected
        if (!isObdConnected) {
            lastScannerAttemptTime = now
            Log.i(TAG, "Supervisor 30m: Reintentando conexion a SCANNER AUTO: ${device.name}")
            ObdManager.retryConnection(device.address)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectProfile(proxy: BluetoothProfile, device: BluetoothDevice) {
        // Metodo 1: Invocar connect(device) oculto via reflexion
        try {
            val connectMethod = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
            connectMethod.isAccessible = true
            connectMethod.invoke(proxy, device)
        } catch (_: Exception) {}

        // Metodo 2: Configurar politica de conexion en PERMITIDA (100 = CONNECTION_POLICY_ALLOWED)
        try {
            val policyMethod = proxy.javaClass.getMethod(
                "setConnectionPolicy",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType
            )
            policyMethod.isAccessible = true
            policyMethod.invoke(proxy, device, 100)
        } catch (_: Exception) {}

        // Metodo 3: Despertar enlace SDP con el dispositivo
        try {
            device.fetchUuidsWithSdp()
        } catch (_: Exception) {}
    }
}
