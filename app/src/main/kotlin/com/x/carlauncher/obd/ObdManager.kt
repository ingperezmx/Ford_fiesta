package com.x.carlauncher.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.x.carlauncher.canbus.CanbusDoorReceiver
import com.x.carlauncher.canbus.DoorState
import com.x.carlauncher.illumination.VehicleIlluminationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Telemetria automotriz en tiempo real obtenida mediante adaptador OBD-II / CANBus
 * con soporte especializado para vLinker FD / FD+ (Ford HS-CAN 500k + MS-CAN 125k).
 */
data class ObdData(
    val speedMph: Float = 0f,
    val speedKmh: Float = 0f,
    val rpm: Int = 0,
    val coolantTempC: Int = 0,
    val intakeTempC: Int = 0,
    val ambientTempC: Int = 0,
    val batteryVoltage: Float = 12.6f,
    val throttlePercent: Int = 0,
    val engineLoadPercent: Int = 0,
    val fuelLevelPercent: Int = 0,
    val manifoldPressureKpa: Int = 0,
    val timingAdvanceDeg: Float = 0f,
    val instantConsumptionL100km: Float = 0f,
    val isConnected: Boolean = false,
    val connectionStatus: String = "Desconectado",
    val deviceName: String = "",
    val isVlinkerFd: Boolean = false,
    val protocolName: String = "ISO 15765-4 CAN (11/500)",
    val dtcCount: Int = 0,
    val dtcCodes: List<String> = emptyList(),
    val milOn: Boolean = false,

    // Estado de Puertas (desde BCM en MS-CAN o IPC Gateway en HS-CAN)
    val doorDriver: Boolean = false,
    val doorPassenger: Boolean = false,
    val doorRearLeft: Boolean = false,
    val doorRearRight: Boolean = false,
    val trunkOpen: Boolean = false,
    val hoodOpen: Boolean = false,

    // Estado de Luces y Senales Exteriores
    val headlightsOn: Boolean = false,
    val highBeamsOn: Boolean = false,
    val parkingLightsOn: Boolean = false,
    val fogLightsOn: Boolean = false,
    val turnSignalLeft: Boolean = false,
    val turnSignalRight: Boolean = false,
    val hazardLightsOn: Boolean = false,
    val brakeLightsOn: Boolean = false,
    val handbrakeOn: Boolean = false
)

/**
 * Gestor Automotriz de OBD-II y CANBus para Ford Fiesta 2016 y adaptador vLinker FD.
 *
 * Implementa comunicacion de doble bus para Ford:
 * 1. HS-CAN (High Speed 500 kbps, pines 6 y 14):
 *    - Velocidad real sin latencia (PID 010D)
 *    - Tacometro RPM motor (PID 010C)
 *    - Temperatura refrigerante ECT (PID 0105)
 *    - Voltaje de bateria (ATRV)
 *    - Posicion de acelerador TPS (PID 0111)
 *    - Carga de motor (PID 0104)
 *    - Presion de admision MAP (PID 010B)
 *    - Temperatura de aire IAT (PID 010F)
 *    - Nivel de combustible (PID 012F)
 *    - Lectura de codigos de falla DTCs (PID 0101, 03, 07)
 *
 * 2. MS-CAN (Medium Speed 125 kbps, pines 3 y 11) via comandos STN de vLinker FD:
 *    - Comando STP 33 para alternar a red de confort / carroceria (BCM 0x726)
 *    - Estado de las 4 puertas, cajuela y cofre (DID 220200 / 220101)
 *    - Estado de luces bajas, altas, cuartos, niebla y direccionales (DID 222804)
 *    - Estado de seguros centrales y freno de mano
 *    - Conmutacion automatica de regreso a HS-CAN (STP 31) en milisegundos.
 */
object ObdManager {
    private const val TAG = "PabloCarOS_OBD"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val KMH_TO_MPH = 0.621371f
    private const val PREFS_NAME = "pablo_car_os_obd"
    private const val KEY_PREFERRED_ADDR = "preferred_obd_mac"
    private const val SUPERVISOR_INTERVAL_MS = 15 * 1000L // Verificacion de conexion cada 15s

    private val _obdData = MutableStateFlow(ObdData())
    val obdData: StateFlow<ObdData> = _obdData.asStateFlow()

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var supervisorJob: Job? = null
    private var pollingJob: Job? = null
    private var isInitialized = false

    private var isVlinkerFdDetected = false

    fun initialize(context: Context) {
        if (isInitialized) return
        val app = context.applicationContext
        appContext = app
        prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val bm = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        isInitialized = true

        Log.i(TAG, "ObdManager inicializado con arquitectura vLinker FD / Ford Fiesta.")
        startSupervisor()
    }

    /**
     * Supervisor continuo que intenta enlazar automaticamente el escaner vLinker FD
     * o SCANNER AUTO cuando este disponible por Bluetooth.
     */
    fun startSupervisor() {
        supervisorJob?.cancel()
        supervisorJob = scope.launch {
            while (isActive) {
                try {
                    val adapter = bluetoothAdapter
                    if (adapter == null || !adapter.isEnabled) {
                        _obdData.value = _obdData.value.copy(
                            isConnected = false,
                            connectionStatus = "Bluetooth apagado"
                        )
                        delay(10000L)
                        continue
                    }

                    if (socket == null || socket?.isConnected != true) {
                        attemptConnection()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Supervisor OBD: ${e.message}")
                }
                delay(SUPERVISOR_INTERVAL_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun attemptConnection(targetAddress: String? = null) = withContext(Dispatchers.IO) {
        val adapter = bluetoothAdapter ?: return@withContext
        val paired = try { adapter.bondedDevices } catch (_: Exception) { null } ?: emptySet()

        val savedAddr = targetAddress ?: prefs?.getString(KEY_PREFERRED_ADDR, null)

        // Prioridad 1: vLinker FD / vLinker FD+
        val vlinkerDevice = paired.firstOrNull { d ->
            val name = d.name?.uppercase() ?: ""
            name.contains("VLINKER") || name.contains("VLINK") || name.contains("FORSCAN")
        }

        // Prioridad 2: SCANNER AUTO (escaner previo de Pablo)
        val scannerAuto = paired.firstOrNull { d ->
            val name = d.name?.uppercase() ?: ""
            name.contains("SCANNER AUTO") || name.contains("SCANNER_AUTO")
        }

        // Prioridad 3: Direccion guardada o cualquier adaptador OBD2 compatible
        val device: BluetoothDevice? = vlinkerDevice
            ?: scannerAuto
            ?: if (!savedAddr.isNullOrBlank()) {
                paired.firstOrNull { it.address.equals(savedAddr, ignoreCase = true) }
            } else null
            ?: paired.firstOrNull { d ->
                val name = d.name?.uppercase() ?: ""
                name.contains("SCANNER") || name.contains("OBD") || name.contains("ELM") ||
                name.contains("VGATE") || name.contains("CAR") || name.contains("VIE") ||
                name.contains("VEEPEAK") || name.contains("KONNWEI") || name.contains("BAFX")
            }

        if (device == null) {
            _obdData.value = _obdData.value.copy(
                isConnected = false,
                connectionStatus = "Emparejar vLinker FD en Bluetooth"
            )
            return@withContext
        }

        val devName = device.name ?: "vLinker FD"
        _obdData.value = _obdData.value.copy(
            isConnected = false,
            connectionStatus = "Conectando a $devName...",
            deviceName = devName
        )
        Log.i(TAG, "Conectando a escaner automotriz: $devName (${device.address})")

        try {
            adapter.cancelDiscovery()
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            socket = s
            inputStream = s.inputStream
            outputStream = s.outputStream

            // Guardar direccion de dispositivo preferido
            prefs?.edit()?.putString(KEY_PREFERRED_ADDR, device.address)?.apply()

            // Inicializar y detectar capacidades del vLinker FD
            initVlinkerFd(devName)

            _obdData.value = _obdData.value.copy(
                isConnected = true,
                connectionStatus = "Conectado",
                deviceName = devName,
                isVlinkerFd = isVlinkerFdDetected
            )
            Log.i(TAG, "Conexion exitosa con $devName (vLinker FD: $isVlinkerFdDetected)")

            // Iniciar ciclo de telemetria en tiempo real
            startPolling(devName)
        } catch (e: Exception) {
            Log.w(TAG, "Fallo al conectar con $devName: ${e.message}")
            _obdData.value = _obdData.value.copy(
                isConnected = false,
                connectionStatus = "Desconectado"
            )
            disconnect()
        }
    }

    /**
     * Secuencia de inicializacion y deteccion para vLinker FD / STN chip.
     */
    private suspend fun initVlinkerFd(devName: String) = withContext(Dispatchers.IO) {
        sendCommand("ATZ")      // Reinicio del chip
        delay(400)
        sendCommand("ATE0")     // Desactivar eco
        delay(100)
        sendCommand("ATL0")     // Desactivar saltos de linea
        delay(100)
        sendCommand("ATH0")     // Encabezados limpios
        delay(100)
        sendCommand("ATAT1")    // Tiempo adaptativo rapido
        delay(100)

        // Deteccion de chip STN (vLinker FD)
        val infoResp = sendCommand("ATI")
        val stnResp = sendCommand("STDI")
        val isStn = infoResp.uppercase().contains("STN") ||
                    stnResp.uppercase().contains("STN") ||
                    infoResp.uppercase().contains("VLINKER") ||
                    devName.uppercase().contains("VLINKER")

        isVlinkerFdDetected = isStn
        Log.i(TAG, "Firma del adaptador: '$infoResp' | STDI: '$stnResp' -> Es vLinker FD: $isStn")

        if (isStn) {
            // Configurar protocolo Ford HS-CAN 500k nativo
            sendCommand("STP 31")
            delay(150)
            _obdData.value = _obdData.value.copy(
                protocolName = "Ford Dual CAN (HS-CAN + MS-CAN)"
            )
        } else {
            // Fallback ELM327 estandar
            sendCommand("ATSP0") // Auto detectar ISO 15765-4 CAN 11/500
            delay(150)
        }
    }

    /**
     * Bucle de telemetria en tiempo real de alta frecuencia para Ford Fiesta.
     */
    private fun startPolling(devName: String) {
        pollingJob?.cancel()
        pollingJob = scope.launch(Dispatchers.IO) {
            var cycleCounter = 0
            while (isActive && socket?.isConnected == true) {
                try {
                    // ── 1. Telemetria Critica de Motor (Cada ciclo ~100-120ms) ─────────
                    // Velocidad real (010D)
                    val speedResp = sendCommand("010D")
                    val speedKmh = parseSpeedKmh(speedResp)
                    val speedMph = if (speedKmh != null) speedKmh * KMH_TO_MPH else null

                    // RPM Motor (010C)
                    val rpmResp = sendCommand("010C")
                    val rpm = parseRpm(rpmResp)

                    var tempC: Int? = null
                    var voltage: Float? = null
                    var throttle: Int? = null
                    var load: Int? = null
                    var map: Int? = null
                    var iat: Int? = null

                    // ── 2. Telemetria Secundaria de Motor (Cada 5 ciclos ~600ms) ──────
                    if (cycleCounter % 5 == 0) {
                        // Temperatura Refrigerante (0105)
                        val tempResp = sendCommand("0105")
                        tempC = parseCoolantTemp(tempResp)

                        // Voltaje de bateria (ATRV)
                        val voltResp = sendCommand("ATRV")
                        voltage = parseVoltage(voltResp)

                        // Posicion acelerador (0111)
                        val throtResp = sendCommand("0111")
                        throttle = parseThrottle(throtResp)

                        // Carga de motor (0104)
                        val loadResp = sendCommand("0104")
                        load = parseEngineLoad(loadResp)

                        // Presion colector admision MAP (010B)
                        val mapResp = sendCommand("010B")
                        map = parseMapKpa(mapResp)

                        // Temperatura aire de admision IAT (010F)
                        val iatResp = sendCommand("010F")
                        iat = parseCoolantTemp(iatResp)
                    }

                    // ── 3. Carroceria: Puertas y Luces (Cada 10 ciclos ~1.2s) ─────────
                    if (cycleCounter % 10 == 0) {
                        queryFordBodyAndLights()
                    }

                    // ── 4. Diagnostico y Nivel de Tanque (Cada 30 ciclos ~3.6s) ───────
                    var fuelLevel: Int? = null
                    var dtcCount: Int? = null
                    var dtcList: List<String>? = null
                    var milActive: Boolean? = null

                    if (cycleCounter % 30 == 0) {
                        val fuelResp = sendCommand("012F")
                        fuelLevel = parseFuelLevel(fuelResp)

                        val milResp = sendCommand("0101")
                        val milData = parseMilStatus(milResp)
                        if (milData != null) {
                            milActive = milData.first
                            dtcCount = milData.second
                        }

                        if ((dtcCount ?: 0) > 0) {
                            val dtcResp = sendCommand("03")
                            dtcList = parseDtcCodes(dtcResp)
                        }
                    }

                    cycleCounter++

                    _obdData.value = _obdData.value.copy(
                        speedMph = speedMph ?: _obdData.value.speedMph,
                        speedKmh = speedKmh ?: _obdData.value.speedKmh,
                        rpm = rpm ?: _obdData.value.rpm,
                        coolantTempC = tempC ?: _obdData.value.coolantTempC,
                        batteryVoltage = voltage ?: _obdData.value.batteryVoltage,
                        throttlePercent = throttle ?: _obdData.value.throttlePercent,
                        engineLoadPercent = load ?: _obdData.value.engineLoadPercent,
                        manifoldPressureKpa = map ?: _obdData.value.manifoldPressureKpa,
                        intakeTempC = iat ?: _obdData.value.intakeTempC,
                        fuelLevelPercent = fuelLevel ?: _obdData.value.fuelLevelPercent,
                        dtcCount = dtcCount ?: _obdData.value.dtcCount,
                        dtcCodes = dtcList ?: _obdData.value.dtcCodes,
                        milOn = milActive ?: _obdData.value.milOn,
                        isConnected = true,
                        connectionStatus = "Conectado",
                        deviceName = devName,
                        isVlinkerFd = isVlinkerFdDetected
                    )

                    delay(100) // ~8-10 Hz para respuesta suave en tiempo real
                } catch (e: Exception) {
                    Log.w(TAG, "Perdida de comunicacion OBD: ${e.message}")
                    _obdData.value = _obdData.value.copy(
                        isConnected = false,
                        connectionStatus = "Reconectando..."
                    )
                    disconnect()
                    break
                }
            }
        }
    }

    /**
     * Consulta el estado de puertas y luces exteriores de Ford Fiesta.
     * En vLinker FD: utiliza conmutacion nativa a Ford MS-CAN (BCM 0x726).
     * En adaptadores genericos: utiliza Gateway IPC en HS-CAN (0x720).
     */
    private fun queryFordBodyAndLights() {
        try {
            if (isVlinkerFdDetected) {
                // ── Conmutacion a Ford MS-CAN (125 kbps, BCM 0x726) ──
                sendCommand("STP 33")
                sendCommand("ATSH 726")

                // 1. Consulta de puertas (DID 220200)
                val doorResp = sendCommand("220200")
                parseAndEmitDoors(doorResp)

                // 2. Consulta de luces exteriores (DID 222804)
                val lightResp = sendCommand("222804")
                parseAndEmitLights(lightResp)

                // Restaurar de inmediato a Ford HS-CAN para lecturas de motor
                sendCommand("STP 31")
                sendCommand("ATSH 7DF")
            } else {
                // Fallback: IPC Gateway en HS-CAN (0x720)
                sendCommand("ATSH 720")
                val doorResp = sendCommand("220200")
                parseAndEmitDoors(doorResp)
                sendCommand("ATSH 7DF")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error consultando carroceria Ford: ${e.message}")
            try {
                if (isVlinkerFdDetected) sendCommand("STP 31")
                sendCommand("ATSH 7DF")
            } catch (_: Exception) {}
        }
    }

    private fun parseAndEmitDoors(raw: String) {
        val clean = raw.replace(" ", "").replace("\r", "").replace("\n", "").uppercase()
        if (clean.contains("NODATA") || clean.contains("ERROR") || clean.isBlank()) return

        val match = Regex("620200([0-9A-F]+)").find(clean)
        if (match != null) {
            val hexData = match.groupValues[1]
            if (hexData.length >= 2) {
                val byteVal = hexData.substring(0, 2).toIntOrNull(16) ?: 0
                val fl = (byteVal and 0x01) != 0 // Puerta conductor
                val fr = (byteVal and 0x02) != 0 // Puerta copiloto
                val rl = (byteVal and 0x04) != 0 // Puerta trasera izquierda
                val rr = (byteVal and 0x08) != 0 // Puerta trasera derecha
                val trunk = (byteVal and 0x10) != 0 // Cajuela
                val hood = (byteVal and 0x20) != 0  // Cofre

                val state = DoorState(
                    frontLeft = fl,
                    frontRight = fr,
                    rearLeft = rl,
                    rearRight = rr,
                    trunk = trunk,
                    hood = hood
                )
                CanbusDoorReceiver.updateDoorState(state)

                _obdData.value = _obdData.value.copy(
                    doorDriver = fl,
                    doorPassenger = fr,
                    doorRearLeft = rl,
                    doorRearRight = rr,
                    trunkOpen = trunk,
                    hoodOpen = hood
                )
            }
        }
    }

    private fun parseAndEmitLights(raw: String) {
        val clean = raw.replace(" ", "").replace("\r", "").replace("\n", "").uppercase()
        if (clean.contains("NODATA") || clean.contains("ERROR") || clean.isBlank()) return

        val match = Regex("622804([0-9A-F]+)").find(clean)
        if (match != null) {
            val hexData = match.groupValues[1]
            if (hexData.length >= 2) {
                val byteVal = hexData.substring(0, 2).toIntOrNull(16) ?: 0
                val lowBeam = (byteVal and 0x01) != 0
                val highBeam = (byteVal and 0x02) != 0
                val parking = (byteVal and 0x04) != 0
                val fog = (byteVal and 0x08) != 0
                val turnLeft = (byteVal and 0x10) != 0
                val turnRight = (byteVal and 0x20) != 0
                val hazard = (byteVal and 0x40) != 0
                val brake = (byteVal and 0x80) != 0

                val anyLightsOn = lowBeam || highBeam || parking

                // Notificar al gestor de iluminacion del auto
                VehicleIlluminationManager.setPhysicalHeadlightsState(anyLightsOn)

                _obdData.value = _obdData.value.copy(
                    headlightsOn = lowBeam,
                    highBeamsOn = highBeam,
                    parkingLightsOn = parking,
                    fogLightsOn = fog,
                    turnSignalLeft = turnLeft,
                    turnSignalRight = turnRight,
                    hazardLightsOn = hazard,
                    brakeLightsOn = brake
                )
            }
        }
    }

    private fun sendCommand(cmd: String): String {
        val out = outputStream ?: return ""
        val `in` = inputStream ?: return ""

        val fullCmd = "$cmd\r"
        out.write(fullCmd.toByteArray())
        out.flush()

        val buffer = StringBuilder()
        val byteBuf = ByteArray(64)
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < 400) {
            if (`in`.available() > 0) {
                val read = `in`.read(byteBuf)
                if (read > 0) {
                    val str = String(byteBuf, 0, read)
                    buffer.append(str)
                    if (buffer.contains(">")) {
                        break
                    }
                }
            } else {
                Thread.sleep(8)
            }
        }
        return buffer.toString().trim()
    }

    // ── Parsers de Telemetria SAE J1979 ──────────────────────────────────────────

    private fun parseSpeedKmh(raw: String): Float? {
        val clean = raw.replace(" ", "").replace("\r", "").replace("\n", "").uppercase()
        val match = Regex("410D([0-9A-F]{2})").find(clean) ?: return null
        val kmh = match.groupValues[1].toIntOrNull(16) ?: return null
        return kmh.toFloat()
    }

    private fun parseRpm(raw: String): Int? {
        val clean = raw.replace(" ", "").replace("\r", "").replace("\n", "").uppercase()
        val match = Regex("410C([0-9A-F]{4})").find(clean) ?: return null
        val value = match.groupValues[1].toIntOrNull(16) ?: return null
        return value / 4
    }

    private fun parseCoolantTemp(raw: String): Int? {
        val clean = raw.replace(" ", "").replace("\r", "").replace("\n", "").uppercase()
        val match = Regex("4105([0-9A-F]{2})").find(clean)
            ?: Regex("410F([0-9A-F]{2})").find(clean)
            ?: return null
        val tempRaw = match.groupValues[1].toIntOrNull(16) ?: return null
        return tempRaw - 40
    }

    private fun parseVoltage(raw: String): Float? {
        val clean = raw.replace("V", "").replace("v", "").replace(">", "").trim()
        val match = Regex("(\\d+\\.\\d+)").find(clean) ?: return null
        return match.groupValues[1].toFloatOrNull()
    }

    private fun parseThrottle(raw: String): Int? {
        val clean = raw.replace(" ", "").replace("\r", "").replace("\n", "").uppercase()
        val match = Regex("4111([0-9A-F]{2})").find(clean) ?: return null
        val value = match.groupValues[1].toIntOrNull(16) ?: return null
        return (value * 100) / 255
    }

    private fun parseEngineLoad(raw: String): Int? {
        val clean = raw.replace(" ", "").replace("\r", "").replace("\n", "").uppercase()
        val match = Regex("4104([0-9A-F]{2})").find(clean) ?: return null
        val value = match.groupValues[1].toIntOrNull(16) ?: return null
        return (value * 100) / 255
    }

    private fun parseMapKpa(raw: String): Int? {
        val clean = raw.replace(" ", "").replace("\r", "").replace("\n", "").uppercase()
        val match = Regex("410B([0-9A-F]{2})").find(clean) ?: return null
        return match.groupValues[1].toIntOrNull(16)
    }

    private fun parseFuelLevel(raw: String): Int? {
        val clean = raw.replace(" ", "").replace("\r", "").replace("\n", "").uppercase()
        val match = Regex("412F([0-9A-F]{2})").find(clean) ?: return null
        val value = match.groupValues[1].toIntOrNull(16) ?: return null
        return (value * 100) / 255
    }

    private fun parseMilStatus(raw: String): Pair<Boolean, Int>? {
        val clean = raw.replace(" ", "").replace("\r", "").replace("\n", "").uppercase()
        val match = Regex("4101([0-9A-F]{2})").find(clean) ?: return null
        val byteA = match.groupValues[1].toIntOrNull(16) ?: return null
        val milOn = (byteA and 0x80) != 0
        val dtcCount = byteA and 0x7F
        return Pair(milOn, dtcCount)
    }

    private fun parseDtcCodes(raw: String): List<String> {
        val clean = raw.replace(" ", "").replace("\r", "").replace("\n", "").uppercase()
        val match = Regex("43([0-9A-F]+)").find(clean) ?: return emptyList()
        val hex = match.groupValues[1]
        val codes = mutableListOf<String>()
        var i = 0
        while (i + 4 <= hex.length) {
            val chunk = hex.substring(i, i + 4)
            if (chunk != "0000") {
                val firstChar = when (chunk[0]) {
                    '0' -> "P0"
                    '1' -> "P1"
                    '2' -> "P2"
                    '3' -> "P3"
                    '4' -> "C0"
                    '5' -> "C1"
                    '8' -> "B0"
                    'C' -> "U0"
                    else -> "P"
                }
                codes.add(firstChar + chunk.substring(1))
            }
            i += 4
        }
        return codes
    }

    fun retryConnection(targetAddress: String? = null) {
        scope.launch {
            disconnect()
            _obdData.value = _obdData.value.copy(
                isConnected = false,
                connectionStatus = "Buscando vLinker FD..."
            )
            delay(150)
            attemptConnection(targetAddress)
        }
    }

    @SuppressLint("MissingPermission")
    fun getPairedObdDevices(): List<BluetoothDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()
        val bonded = try { adapter.bondedDevices } catch (_: Exception) { null } ?: return emptyList()
        return bonded.filter { d ->
            val name = try { d.name?.uppercase() ?: "" } catch (_: Exception) { "" }
            name.contains("VLINKER") || name.contains("OBD") || name.contains("ELM") ||
            name.contains("VGATE") || name.contains("CAR") || name.contains("VLINK") ||
            name.contains("VIE") || name.contains("VEEPEAK") || name.contains("KONNWEI") ||
            name.contains("BAFX") || name.contains("DIAG") || name.contains("SCAN") ||
            name.contains("AUTO") || name.contains("FORSCAN")
        }
    }

    fun pauseSupervisor() {
        Log.i(TAG, "Pausando supervisor OBD")
        supervisorJob?.cancel()
        supervisorJob = null
        disconnect()
    }

    fun resumeSupervisor() {
        Log.i(TAG, "Reanudando supervisor OBD")
        if (supervisorJob == null || supervisorJob?.isActive != true) {
            startSupervisor()
        }
    }

    fun disconnect() {
        pollingJob?.cancel()
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Cierre de socket OBD: ${e.message}")
        } finally {
            socket = null
            inputStream = null
            outputStream = null
            isVlinkerFdDetected = false
            _obdData.value = _obdData.value.copy(
                isConnected = false,
                connectionStatus = "Desconectado"
            )
        }
    }
}
