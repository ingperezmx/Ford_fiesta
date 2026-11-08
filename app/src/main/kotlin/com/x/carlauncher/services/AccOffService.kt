package com.x.carlauncher.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.x.carlauncher.MainActivity
import com.x.carlauncher.R
import com.x.carlauncher.bluetooth.VehicleBluetoothSupervisor
import com.x.carlauncher.obd.ObdManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Servicio de gestion automotriz ACC, energia y Bluetooth para XCarOS (Ford Fiesta 2016).
 *
 * Logica configurada para Pablo:
 * 1. APERTURA EXCLUSIVA:
 *    - La aplicacion XCarOS solo se abre/despierta cuando se conecta el escaner OBD2 "SCANNER AUTO".
 *    - La conexion del cable USB-C de carga solo mantiene la energia/actividad interna, NO abre la aplicacion sobre el launcher principal.
 *
 * 2. PERSISTENCIA DE BLUETOOTH:
 *    - Supervision y reconexion activa continua para "SCANNER AUTO" (OBD2) y "SYNC" (Ford SYNC audio y llamadas).
 *
 * 3. DESCONEXION OBD2:
 *    - Al desconectarse "SCANNER AUTO", el telefono regresa de inmediato al launcher principal del usuario (Microsoft Launcher).
 *
 * Todo en puro ASCII sin acentos ni simbolos especiales.
 */
class AccOffService : Service() {

    companion object {
        private const val TAG = "AccOffService"
        private const val CHANNEL_ID = "x_car_system"
        private const val CHANNEL_NAME = "X Car System"
        private const val NOTIF_ID = 1001

        const val ACTION_ACC_OFF        = "com.x.carlauncher.ACC_OFF"
        const val ACTION_ACC_ON         = "com.x.carlauncher.ACC_ON"
        const val ACTION_PAUSE_GPS      = "com.x.carlauncher.PAUSE_GPS"
        const val ACTION_RESUME_GPS     = "com.x.carlauncher.RESUME_GPS"
        const val ACTION_REDUCE_REFRESH = "com.x.carlauncher.REDUCE_REFRESH"
        const val ACTION_RESTORE_REFRESH = "com.x.carlauncher.RESTORE_REFRESH"

    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var disconnectSleepJob: Job? = null
    private var obdObserverJob: Job? = null

    @Volatile
    private var isVehicleActive: Boolean = false

    // Receptor de senales de ignicion, Bluetooth y energia USB
    private val accReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.d(TAG, "Broadcast recibido: $action")
            when (action) {
                // Energia USB-C conectada: Mantener activo pero NO abrir pantalla (solo abre con SCANNER AUTO)
                Intent.ACTION_POWER_CONNECTED -> {
                    Log.i(TAG, "Energia detectada en puerto USB-C (Cargando)")
                    disconnectSleepJob?.cancel()
                    isVehicleActive = true
                    handleAccOn()
                }

                // Energia USB-C desconectada
                Intent.ACTION_POWER_DISCONNECTED -> {
                    Log.i(TAG, "Energia desconectada de puerto USB-C")
                    scheduleSleepAfterDisconnect(debounceMs = 3000L, ignoreHardwareCharging = false)
                }





                // Bluetooth ACL conectado
                android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    }
                    val fromIntent = intent.getStringExtra(android.bluetooth.BluetoothDevice.EXTRA_NAME)
                        ?: intent.getStringExtra("android.bluetooth.device.extra.NAME")
                    val devName = if (!fromIntent.isNullOrBlank()) fromIntent else try { device?.name ?: device?.address ?: "" } catch (_: Exception) { "" }
                    Log.i(TAG, "Bluetooth ACL conectado detectado: $devName")

                    if (VehicleBluetoothSupervisor.isScannerAutoDevice(devName)) {
                        Log.i(TAG, "Escaner automotriz 'SCANNER AUTO' conectado ('$devName') -> Despertando y abriendo XCarOS")
                        disconnectSleepJob?.cancel()
                        isVehicleActive = true
                        wakeScreenAndLaunchApp()
                        handleAccOn()
                    } else if (VehicleBluetoothSupervisor.isSyncDevice(devName)) {
                        Log.i(TAG, "Sistema de audio Ford SYNC conectado ('$devName')")
                        VehicleBluetoothSupervisor.triggerImmediateCheck()
                    }
                }

                // Bluetooth ACL desconectado
                android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    }
                    val fromIntent = intent.getStringExtra(android.bluetooth.BluetoothDevice.EXTRA_NAME)
                        ?: intent.getStringExtra("android.bluetooth.device.extra.NAME")
                    val devName = if (!fromIntent.isNullOrBlank()) fromIntent else try { device?.name ?: device?.address ?: "" } catch (_: Exception) { "" }

                    if (VehicleBluetoothSupervisor.isScannerAutoDevice(devName)) {
                        Log.i(TAG, "Escaner automotriz 'SCANNER AUTO' desconectado ('$devName') -> Regresando a launcher principal")
                        returnToUserLauncherIfNeeded()
                        scheduleSleepAfterDisconnect(debounceMs = 3000L, ignoreHardwareCharging = false)
                    } else if (VehicleBluetoothSupervisor.isSyncDevice(devName)) {
                        Log.i(TAG, "Sistema de audio Ford SYNC desconectado ('$devName') -> Reintentando conexion")
                        VehicleBluetoothSupervisor.triggerImmediateCheck()
                    }
                }

                // Pantalla activa
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "Pantalla activa detectada")
                }

                // Pantalla apagada o apagado de sistema
                Intent.ACTION_SCREEN_OFF,
                "android.intent.action.ACTION_SHUTDOWN",
                "com.android.internal.intent.action.REQUEST_SHUTDOWN" -> {
                    Log.i(TAG, "Pantalla apagada o apagado de sistema")
                    handleAccOff()
                }

                // Compatibilidad con senales ACC de estereos Android
                "com.syu.ms.accon", "com.syu.accon", "com.syu.acc_on",
                "syu.intent.action.accon", "com.syu.ms.screen_on",
                "com.microntek.accon", "com.tw.acc_on" -> {
                    Log.i(TAG, "Senal ACC ON de estereo recibida")
                    disconnectSleepJob?.cancel()
                    isVehicleActive = true
                    handleAccOn()
                }

                "com.syu.ms.accoff", "com.syu.accoff", "com.syu.acc_off",
                "syu.intent.action.accoff", "com.syu.ms.screen_off",
                "com.microntek.accoff", "com.tw.acc_off" -> {
                    Log.i(TAG, "Senal ACC OFF de estereo recibida")
                    scheduleSleepAfterDisconnect(debounceMs = 1500L)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AccOffService creado")

        createNotificationChannel()
        try {
            startForeground(NOTIF_ID, buildNotification("XCarOS - Servicio activo"))
        } catch (e: Exception) {
            Log.e(TAG, "AccOffService startForeground error", e)
        }

        registerAccReceiver()
        startObdObserver()

        // Inicializar supervisor persistente de conexiones Bluetooth (SYNC + SCANNER AUTO)
        VehicleBluetoothSupervisor.initialize(applicationContext)

        serviceScope.launch { com.x.carlauncher.system.RootHelper.execRoot("settings put global stay_on_while_plugged_in 0") }

        // Verificar estado inicial de energia USB-C
        checkInitialPowerState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIF_ID, buildNotification("XCarOS - Servicio activo"))
        } catch (_: Exception) {}
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        disconnectSleepJob?.cancel()
        obdObserverJob?.cancel()
        try {
            unregisterReceiver(accReceiver)
        } catch (_: Exception) {}
    }

    private fun registerAccReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_BOOT_COMPLETED)
            addAction("android.intent.action.ACTION_SHUTDOWN")
            addAction("com.android.internal.intent.action.REQUEST_SHUTDOWN")

            // Bluetooth OBD2 y SYNC
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)

            // Radios chinas
            addAction("com.syu.ms.accoff")
            addAction("com.syu.ms.accon")
            addAction("com.syu.acc_off")
            addAction("com.syu.acc_on")
            addAction("com.microntek.accoff")
            addAction("com.microntek.accon")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(accReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(accReceiver, filter)
        }
    }

    /**
     * Revisa si al iniciar el servicio el puerto USB-C ya tiene energia.
     * Solo actualiza el estado interno; NO abre la aplicacion sobre el launcher.
     */
    private fun checkInitialPowerState() {
        if (isUsbPowerConnected()) {
            Log.i(TAG, "Inicializacion: Energia USB-C presente (Cargando)")
            isVehicleActive = true
            handleAccOn()
        }
    }

    /**
     * Monitorea si el escaner "SCANNER AUTO" se conecta via ObdManager para abrir XCarOS.
     */
    private fun startObdObserver() {
        obdObserverJob?.cancel()
        obdObserverJob = serviceScope.launch {
            ObdManager.obdData.collect { data ->
                if (data.isConnected && !isVehicleActive) {
                    if (VehicleBluetoothSupervisor.isScannerAutoDevice(data.deviceName)) {
                        Log.i(TAG, "Escaner OBD2 'SCANNER AUTO' conectado (${data.deviceName}). Activando pantalla y lanzando XCarOS.")
                        disconnectSleepJob?.cancel()
                        isVehicleActive = true
                        wakeScreenAndLaunchApp()
                        handleAccOn()
                    }
                }
            }
        }
    }

    /**
     * Enciende fisicamente la pantalla y trae XCarOS a primer plano.
     * Se ejecuta exclusivamente cuando se conecta el escaner OBD2 "SCANNER AUTO".
     */
    private fun wakeScreenAndLaunchApp() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive != true) {
            try {
                @Suppress("DEPRECATION")
                val wakeLock = pm?.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "x:VehicleWakeLock"
                )
                wakeLock?.acquire(3000L)
            } catch (e: Exception) {
                Log.w(TAG, "WakeLock error: ${e.message}")
            }

            serviceScope.launch { com.x.carlauncher.system.RootHelper.execRoot("input keyevent 224 ; wm dismiss-keyguard") }
        } else {
            serviceScope.launch { com.x.carlauncher.system.RootHelper.execRoot("wm dismiss-keyguard") }
        }

        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val options = android.app.ActivityOptions.makeBasic()
        try {
            val method1 = options.javaClass.getMethod("setPendingIntentBackgroundActivityStartMode", Int::class.javaPrimitiveType)
            method1.invoke(options, 1) // MODE_BACKGROUND_ACTIVITY_START_ALLOWED = 1
        } catch (_: Exception) {}
        try {
            val method2 = options.javaClass.getMethod("setPendingIntentCreatorBackgroundActivityStartMode", Int::class.javaPrimitiveType)
            method2.invoke(options, 1) // MODE_BACKGROUND_ACTIVITY_START_ALLOWED = 1
        } catch (_: Exception) {}

        // Metodo 1: PendingIntent con ActivityOptions (opt-in para inicio en segundo plano)
        try {
            val pendingIntent = android.app.PendingIntent.getActivity(
                applicationContext,
                101,
                launchIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                options.toBundle()
            )
            pendingIntent.send(applicationContext, 0, null, null, null, null, options.toBundle())
            Log.i(TAG, "MainActivity lanzada via PendingIntent con opt-in de segundo plano")
        } catch (e: Exception) {
            Log.w(TAG, "PendingIntent launch error: ${e.message}")
        }

        // Metodo 2: Notificacion de maxima prioridad con canal HIGH y fullScreenIntent
        try {
            val pi = android.app.PendingIntent.getActivity(
                applicationContext,
                102,
                launchIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                options.toBundle()
            )
            val notif = NotificationCompat.Builder(this, "x_vehicle_alert")
                .setContentTitle("XCarOS")
                .setContentText("SCANNER AUTO Conectado")
                .setSmallIcon(R.drawable.ic_stat_car)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1002, notif)
        } catch (e: Exception) {
            Log.w(TAG, "FullScreenIntent notif error: ${e.message}")
        }

        // Metodo 3: Direct startActivity con ActivityOptions
        try {
            startActivity(launchIntent, options.toBundle())
        } catch (_: Exception) {}

        // Reclamar primer plano
        com.x.carlauncher.services.StartupGuardian.start(applicationContext)
    }

    /**
     * Retarda el reposo 3 segundos tras desconexion.
     */
    private fun scheduleSleepAfterDisconnect(debounceMs: Long, ignoreHardwareCharging: Boolean = false) {
        disconnectSleepJob?.cancel()
        disconnectSleepJob = serviceScope.launch {
            Log.i(TAG, "Retardo de $debounceMs ms antes de pasar a reposo...")
            delay(debounceMs)

            // Si el OBD sigue conectado, mantener activo el sistema
            if (ObdManager.obdData.value.isConnected) {
                Log.i(TAG, "OBD2 SCANNER AUTO sigue conectado. Manteniendo vehiculo activo.")
                return@launch
            }

            // Si la energia regreso durante el retardo, cancelar apagado
            if (!ignoreHardwareCharging && isUsbPowerConnected()) {
                Log.i(TAG, "Energia USB-C restaurada durante el retardo. Manteniendo activo.")
                return@launch
            }

            isVehicleActive = false
            Log.i(TAG, "Confirmada perdida de conexion vehicular. Pasando a minimo consumo.")
            handleAccOff()

            // Apagar fisicamente la pantalla si sigue encendida y no esta cargando
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if ((pm?.isInteractive == true && !isUsbPowerConnected()) || ignoreHardwareCharging) {
                com.x.carlauncher.system.RootHelper.execRoot("input keyevent 26")
            }
        }
    }

    private fun isUsbPowerConnected(): Boolean {
        return try {
            val batteryStatus = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val isPlugged = plug == BatteryManager.BATTERY_PLUGGED_AC || plug == BatteryManager.BATTERY_PLUGGED_USB || plug == BatteryManager.BATTERY_PLUGGED_WIRELESS
            isCharging || isPlugged
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Reposo / Desconexion: Detener consumo y ahorrar bateria.
     */
    private fun handleAccOff() {
        Log.i(TAG, "Modo MINIMO CONSUMO activado - Ahorro total de bateria")

        // 1. Apagar chip satelital GPS
        com.x.carlauncher.location.VehicleLocationManager.stopListening()

        // 2. Notificar a componentes locales que reduzcan refresco
        sendLocalBroadcast(ACTION_PAUSE_GPS)
        sendLocalBroadcast(ACTION_REDUCE_REFRESH)
        sendLocalBroadcast(ACTION_ACC_OFF)

        // 3. Reiniciar estado de bienvenida por voz para la proxima ignicion
        com.x.carlauncher.voice.CarVoiceAssistant.getInstance(applicationContext).onVehiclePowerOff()

        // 4. Pausar reproduccion musical
        com.x.carlauncher.media.XLocalPlayer.pause()
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am?.isMusicActive == true) {
                val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
                val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
                am.dispatchMediaKeyEvent(down)
                am.dispatchMediaKeyEvent(up)
            }
        } catch (_: Exception) {}

        // Notificacion de reposo
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification("XCarOS - Reposo (Ahorro de energia)"))
    }

    /**
     * Activo: Restaurar rendimiento y telemetria completa.
     */
    private fun handleAccOn() {
        Log.i(TAG, "Modo ACTIVO automotriz restaurado")

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification("XCarOS - En linea"))

        // 1. Restaurar GPS
        com.x.carlauncher.location.VehicleLocationManager.startListening()

        // 2. Reanudar supervisor OBD
        ObdManager.resumeSupervisor()

        // 3. Notificar reanudacion
        sendLocalBroadcast(ACTION_RESUME_GPS)
        sendLocalBroadcast(ACTION_RESTORE_REFRESH)
        sendLocalBroadcast(ACTION_ACC_ON)

        // 4. Saludo de voz
        com.x.carlauncher.voice.CarVoiceAssistant.getInstance(applicationContext).onVehicleStartupOrWake()

        // 5. Reanudar musica
        com.x.carlauncher.media.XLocalPlayer.onVehicleIgnition()
    }

    private fun returnToUserLauncherIfNeeded() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolveInfo = packageManager.resolveActivity(homeIntent, 0)
            val defaultPkg = resolveInfo?.activityInfo?.packageName
            if (defaultPkg != null && defaultPkg != packageName) {
                Log.i(TAG, "Regresando al launcher principal configurado por el usuario: $defaultPkg")
                val backHome = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(backHome)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error volviendo a Home: ${e.message}")
        }
    }

    private fun sendLocalBroadcast(action: String) {
        val intent = Intent(action).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "XCarOS gestion de energia y suspension automotriz"
                setShowBadge(false)
            }
            val alertChannel = NotificationChannel(
                "x_vehicle_alert",
                "X Vehicle Connection Alert",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Apertura en primer plano al conectar escaner automotriz"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
            nm.createNotificationChannel(alertChannel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XCarOS")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
