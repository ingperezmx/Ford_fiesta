package com.x.carlauncher.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
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
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.x.carlauncher.media.PowerampNotificationListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

// ── Data class ────────────────────────────────────────────────────────────────

/**
 * Snapshot of a Bluetooth device's connection state.
 *
 * @param deviceName Friendly name of the Bluetooth device.
 * @param isConnected Whether the device is currently connected.
 * @param profile     Bluetooth profile: "HEADSET", "A2DP", or "NONE".
 * @param trackTitle  Current playing song title from phone.
 * @param trackArtist Current playing artist name from phone.
 * @param trackAlbum  Current playing album name from phone.
 * @param isPlaying   Whether the phone is actively playing audio.
 * @param durationMs  Track duration in milliseconds.
 * @param playbackPositionMs Current playback position in milliseconds.
 */
data class BluetoothConnectionState(
    val deviceName: String = "",
    val isConnected: Boolean = false,
    val profile: String = "NONE",
    val trackTitle: String = "",
    val trackArtist: String = "",
    val trackAlbum: String = "",
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val playbackPositionMs: Long = 0L
)

data class BtTrackInfo(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L
)

// ── BroadcastReceiver ─────────────────────────────────────────────────────────

/**
 * [BroadcastReceiver] that monitors Bluetooth connection events for the
 * HFP (Headset), A2DP Sink (Audio Receiver), and raw ACL link events.
 */
class BluetoothStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BluetoothStateReceiver"

        private val _events = MutableSharedFlow<BluetoothConnectionState>(
            replay = 1,
            extraBufferCapacity = 8
        )

        /** Shared stream of [BluetoothConnectionState] events. */
        val events: SharedFlow<BluetoothConnectionState> = _events.asSharedFlow()

        private val _btTrackFlow = MutableStateFlow(BtTrackInfo())
        val btTrackFlow: StateFlow<BtTrackInfo> = _btTrackFlow.asStateFlow()

        fun clearBtTrack() {
            _btTrackFlow.value = BtTrackInfo()
        }

        fun updateBtTrack(
            title: String,
            artist: String,
            album: String = "",
            isPlaying: Boolean,
            durationMs: Long = 0L,
            positionMs: Long = 0L
        ) {
            val curr = _btTrackFlow.value
            val resolvedTitle = if (title.isNotBlank()) title else curr.title
            val resolvedArtist = if (artist.isNotBlank()) artist else curr.artist
            val resolvedAlbum = if (album.isNotBlank()) album else curr.album
            _btTrackFlow.value = BtTrackInfo(
                title = resolvedTitle,
                artist = resolvedArtist,
                album = resolvedAlbum,
                isPlaying = isPlaying,
                durationMs = if (durationMs > 0) durationMs else curr.durationMs,
                positionMs = if (positionMs >= 0) positionMs else curr.positionMs
            )
        }

        /** Build the [IntentFilter] for all monitored actions. */
        fun buildIntentFilter(): IntentFilter = IntentFilter().apply {
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            // Head unit / Automotive specific actions
            addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT")
            addAction("android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.device.action.NAME_CHANGED")
            addAction("com.syu.bt.bywork")
            addAction("com.syu.bt")
        }
    }

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context?, intent: Intent?) {
        intent ?: return

        val device: BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

        val deviceName = tryGetDeviceName(device, intent)

        when (intent.action) {
            "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT" -> {
                try {
                    @Suppress("DEPRECATION")
                    val metadata = intent.getParcelableExtra<MediaMetadata>("android.bluetooth.avrcp-controller.profile.extra.METADATA")
                    @Suppress("DEPRECATION")
                    val playback = intent.getParcelableExtra<PlaybackState>("android.bluetooth.avrcp-controller.profile.extra.PLAYBACK")
                    val newTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                        ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                        ?: intent.getStringExtra("title")
                        ?: ""
                    val newArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                        ?: intent.getStringExtra("artist")
                        ?: ""
                    val newAlbum = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                        ?: intent.getStringExtra("album")
                        ?: ""
                    val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                    val position = playback?.position ?: 0L

                    val isPlaying = playback?.state == PlaybackState.STATE_PLAYING

                    Log.d(TAG, "TRACK_EVENT: title='$newTitle' artist='$newArtist' album='$newAlbum' state=${playback?.state} isPlaying=$isPlaying")

                    // AUTO-PAUSE LOCAL MUSIC WHEN BLUETOOTH PLAYBACK STARTS
                    if (isPlaying && context != null) {
                        if (com.x.carlauncher.media.FytMusicService.isOemPlaying ||
                            (PowerampNotificationListener.mediaState.value.isPlaying &&
                             PowerampNotificationListener.currentSource.value == com.x.carlauncher.media.AudioSource.LOCAL)) {
                            Log.i(TAG, "Bluetooth playback detected -> automatically pausing local music")
                            com.x.carlauncher.media.FytMusicService.pauseMusic(context)
                        }
                    }

                    if (newTitle.isNotBlank() || isPlaying) {
                        updateBtTrack(newTitle, newArtist, newAlbum, isPlaying, duration, position)
                        PowerampNotificationListener.updateFromBluetooth(newTitle, newArtist, newAlbum, isPlaying, duration)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error handling TRACK_EVENT", e)
                }
            }

            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED" -> {
                val state = intent.getIntExtra(
                    BluetoothProfile.EXTRA_STATE,
                    intent.getIntExtra("android.bluetooth.profile.extra.STATE", BluetoothProfile.STATE_DISCONNECTED)
                )
                val isConnected = state == BluetoothProfile.STATE_CONNECTED
                Log.d(TAG, "Headset state changed: device=$deviceName connected=$isConnected")
                emit(BluetoothConnectionState(deviceName, isConnected, "HEADSET"))
            }

            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED" -> {
                val state = intent.getIntExtra(
                    BluetoothProfile.EXTRA_STATE,
                    intent.getIntExtra("android.bluetooth.profile.extra.STATE", BluetoothProfile.STATE_DISCONNECTED)
                )
                val isConnected = state == BluetoothProfile.STATE_CONNECTED
                Log.d(TAG, "A2DP/AVRCP state changed: device=$deviceName connected=$isConnected")
                emit(BluetoothConnectionState(deviceName, isConnected, "A2DP"))
            }

            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Log.d(TAG, "ACL connected: device=$deviceName")
                emit(BluetoothConnectionState(deviceName, isConnected = true, profile = "NONE"))

                if (VehicleBluetoothSupervisor.isScannerAutoDevice(deviceName)) {
                    Log.i(TAG, "Escaner automotriz SCANNER AUTO conectado ('$deviceName') -> Abriendo XCarOS")
                    if (context != null) {
                        try {
                            val launchIntent = Intent(context, com.x.carlauncher.MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            context.startActivity(launchIntent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error iniciando XCarOS al conectar OBD2", e)
                        }
                    }
                } else if (VehicleBluetoothSupervisor.isSyncDevice(deviceName)) {
                    Log.i(TAG, "Dispositivo Ford SYNC conectado ('$deviceName')")
                    VehicleBluetoothSupervisor.triggerImmediateCheck()
                }
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val isObd = VehicleBluetoothSupervisor.isScannerAutoDevice(deviceName)
                val isSync = VehicleBluetoothSupervisor.isSyncDevice(deviceName)

                Log.i(TAG, "ACL disconnected: device=$deviceName (isObd=$isObd, isSync=$isSync) -> restoring local OEM music")
                clearBtTrack()
                emit(BluetoothConnectionState(deviceName = "", isConnected = false, profile = "NONE"))
                PowerampNotificationListener.restoreLocalMediaState(context)
                if (context != null) {
                    com.x.carlauncher.media.FytMusicService.resumeMusic(context)
                }

                if (isSync) {
                    VehicleBluetoothSupervisor.triggerImmediateCheck()
                }

                if (isObd && context != null) {
                    try {
                        val pm = context.packageManager
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                        }
                        val resolveInfo = pm.resolveActivity(homeIntent, 0)
                        val defaultPkg = resolveInfo?.activityInfo?.packageName
                        if (defaultPkg != null && defaultPkg != context.packageName) {
                            Log.i(TAG, "Regresando al launcher principal del usuario: $defaultPkg")
                            val backHome = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(backHome)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error volviendo al launcher predeterminado: ${e.message}")
                    }
                }
            }

            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
                if (state != BluetoothAdapter.STATE_ON) {
                    Log.i(TAG, "Bluetooth adapter turned off -> restoring local OEM music")
                    clearBtTrack()
                    emit(BluetoothConnectionState(deviceName = "", isConnected = false, profile = "NONE"))
                    PowerampNotificationListener.restoreLocalMediaState(context)
                    if (context != null) {
                        com.x.carlauncher.media.FytMusicService.resumeMusic(context)
                    }
                }
            }

            else -> {
                // Ignore unhandled actions
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun tryGetDeviceName(device: BluetoothDevice?, intent: Intent? = null): String {
        val fromIntent = intent?.getStringExtra(BluetoothDevice.EXTRA_NAME)
            ?: intent?.getStringExtra("android.bluetooth.device.extra.NAME")
        if (!fromIntent.isNullOrBlank()) return fromIntent

        if (device == null) return "Unknown"
        return try {
            device.name ?: device.address ?: "Unknown"
        } catch (e: Exception) {
            try { device.address ?: "Unknown" } catch (e2: Exception) { "Unknown" }
        }
    }

    private fun emit(state: BluetoothConnectionState) {
        _events.tryEmit(state)
    }
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * ViewModel that exposes separate [StateFlow]s for HFP (headset) and A2DP
 * profile connection states.
 *
 * On init, it reads the current connected devices from [BluetoothAdapter]
 * for each profile via [BluetoothProfile] proxies, so the UI is immediately
 * populated even if no broadcast has been received yet.
 */
class BluetoothViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "BluetoothViewModel"
        @Volatile
        var isDeviceConnected: Boolean = false
            private set
        @Volatile
        var currentDeviceName: String = ""
            private set
    }

    private val context: Context = application.applicationContext

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    // ── Exposed state flows ──────────────────────────────────────────────────

    private val _headsetState = MutableStateFlow(BluetoothConnectionState(profile = "HEADSET"))
    val headsetState: StateFlow<BluetoothConnectionState> = _headsetState.asStateFlow()

    private val _a2dpState = MutableStateFlow(BluetoothConnectionState(profile = "A2DP"))
    val a2dpState: StateFlow<BluetoothConnectionState> = _a2dpState.asStateFlow()

    // ── Receiver ─────────────────────────────────────────────────────────────

    private val receiver = BluetoothStateReceiver()
    private var receiverRegistered = false

    // ── Profile proxies (needed to query current connected devices) ───────────

    private var headsetProxy: BluetoothProfile? = null
    private var a2dpProxy: BluetoothProfile? = null
    private var a2dpSinkProxy: BluetoothProfile? = null
    private var headsetClientProxy: BluetoothProfile? = null

    private val universalProfileListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                11 -> a2dpSinkProxy = proxy
                16 -> headsetClientProxy = proxy
                BluetoothProfile.HEADSET -> headsetProxy = proxy
                BluetoothProfile.A2DP -> a2dpProxy = proxy
            }
            refreshAllConnectedDevices()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                11 -> a2dpSinkProxy = null
                16 -> headsetClientProxy = null
                BluetoothProfile.HEADSET -> headsetProxy = null
                BluetoothProfile.A2DP -> a2dpProxy = null
            }
            refreshAllConnectedDevices()
        }
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    init {
        registerReceiver()
        collectBroadcastEvents()
        openProfileProxies()
        refreshAllConnectedDevices()

        // Background poller every 2.5s to ensure instantaneous sync
        viewModelScope.launch {
            while (isActive) {
                delay(2500L)
                refreshAllConnectedDevices()
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun registerReceiver() {
        if (receiverRegistered) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    receiver,
                    BluetoothStateReceiver.buildIntentFilter(),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(receiver, BluetoothStateReceiver.buildIntentFilter())
            }
            receiverRegistered = true
            Log.d(TAG, "BluetoothStateReceiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register BluetoothStateReceiver", e)
        }
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(receiver)
            receiverRegistered = false
            Log.d(TAG, "BluetoothStateReceiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister BluetoothStateReceiver", e)
        }
    }

    /** Collect events from the shared flow and route them to the correct StateFlow. */
    private fun collectBroadcastEvents() {
        viewModelScope.launch {
            BluetoothStateReceiver.events.collect { state ->
                when (state.profile) {
                    "HEADSET" -> _headsetState.value = state
                    "A2DP"    -> _a2dpState.value = state
                    "NONE"    -> {
                        if (state.isConnected) {
                            _headsetState.value = _headsetState.value.copy(
                                deviceName = state.deviceName, isConnected = true
                            )
                            _a2dpState.value = _a2dpState.value.copy(
                                deviceName = state.deviceName, isConnected = true
                            )
                        } else {
                            _headsetState.value = BluetoothConnectionState(profile = "HEADSET")
                            _a2dpState.value    = BluetoothConnectionState(profile = "A2DP")
                        }
                    }
                }
                isDeviceConnected = _a2dpState.value.isConnected || _headsetState.value.isConnected
                currentDeviceName = if (_a2dpState.value.isConnected) _a2dpState.value.deviceName else _headsetState.value.deviceName
            }
        }

        viewModelScope.launch {
            BluetoothStateReceiver.btTrackFlow.collect { track ->
                _a2dpState.value = _a2dpState.value.copy(
                    trackTitle = track.title,
                    trackArtist = track.artist,
                    trackAlbum = track.album,
                    isPlaying = track.isPlaying,
                    durationMs = track.durationMs,
                    playbackPositionMs = track.positionMs
                )
            }
        }
    }

    /** Open BluetoothProfile proxy connections to read already-connected devices on startup. */
    private fun openProfileProxies() {
        val adapter = bluetoothAdapter ?: return
        try { adapter.getProfileProxy(context, universalProfileListener, 11) } catch (e: Exception) {}
        try { adapter.getProfileProxy(context, universalProfileListener, 16) } catch (e: Exception) {}
        try { adapter.getProfileProxy(context, universalProfileListener, BluetoothProfile.HEADSET) } catch (e: Exception) {}
        try { adapter.getProfileProxy(context, universalProfileListener, BluetoothProfile.A2DP) } catch (e: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun refreshAllConnectedDevices() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _headsetState.value = BluetoothConnectionState(profile = "HEADSET")
            _a2dpState.value = BluetoothConnectionState(profile = "A2DP")
            isDeviceConnected = false
            currentDeviceName = ""
            return
        }

        // Method 1: Check active profile proxies
        val proxies = listOfNotNull(a2dpSinkProxy, headsetClientProxy, a2dpProxy, headsetProxy)
        for (proxy in proxies) {
            try {
                val connected = proxy.connectedDevices
                val active = connected.firstOrNull { proxy.getConnectionState(it) == BluetoothProfile.STATE_CONNECTED }
                    ?: connected.firstOrNull()
                if (active != null) {
                    val name = try { active.name ?: active.address } catch (e: Exception) { active.address }
                    if (!name.isNullOrBlank() && name != "Unknown") {
                        applyConnectedState(name)
                        return
                    }
                }
            } catch (e: Exception) {
                // Ignore profile proxy errors
            }
        }

        // Method 2: Check bonded devices with reflection isConnected()
        try {
            val isConnectedMethod = BluetoothDevice::class.java.getMethod("isConnected")
            val bonded = adapter.bondedDevices ?: emptySet()
            for (dev in bonded) {
                val isConn = isConnectedMethod.invoke(dev) as? Boolean ?: false
                if (isConn) {
                    val name = dev.name ?: dev.address ?: "Dispositivo Bluetooth"
                    applyConnectedState(name)
                    return
                }
            }
        } catch (e: Exception) {
            // Ignore reflection errors
        }

        // Method 3: FYT OEM system shared preferences (bt_data.xml)
        try {
            val file = File("/data/data/com.syu.bt/shared_prefs/bt_data.xml")
            if (file.exists() && file.canRead()) {
                val xml = file.readText()
                val addrMatch = Regex("""name="bt_phone_addr">([0-9A-Fa-f:]{17})<""").find(xml)
                val stateMatch = Regex("""name="phoneState" value="(\d+)"""").find(xml)
                val pState = stateMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val addr = addrMatch?.groupValues?.get(1) ?: ""
                if (pState >= 1 && addr.isNotBlank() && addr != "00:00:00:00:00:00") {
                    val dev = try { adapter.getRemoteDevice(addr) } catch (e: Exception) { null }
                    val name = dev?.name ?: adapter.bondedDevices.find { it.address.equals(addr, true) }?.name ?: addr
                    applyConnectedState(name)
                    return
                }
            }
        } catch (e: Exception) {
            // Ignore file read errors
        }

        // If no device is currently connected
        val wasConnected = isDeviceConnected
        if (_a2dpState.value.isConnected || _headsetState.value.isConnected || wasConnected) {
            if (wasConnected) {
                Log.i(TAG, "refreshAllConnectedDevices: Device disconnected -> restoring local OEM music")
                BluetoothStateReceiver.clearBtTrack()
                PowerampNotificationListener.restoreLocalMediaState(context)
                com.x.carlauncher.media.FytMusicService.resumeMusic(context)
            }
            _headsetState.value = BluetoothConnectionState(profile = "HEADSET")
            _a2dpState.value = BluetoothConnectionState(profile = "A2DP")
        }
        isDeviceConnected = false
        currentDeviceName = ""
    }

    private fun applyConnectedState(name: String) {
        val wasConnected = isDeviceConnected
        isDeviceConnected = true
        currentDeviceName = name
        if (!_a2dpState.value.isConnected || _a2dpState.value.deviceName != name) {
            val prevTrack = _a2dpState.value.trackTitle
            val prevArtist = _a2dpState.value.trackArtist
            val prevAlbum = _a2dpState.value.trackAlbum
            val prevPlaying = _a2dpState.value.isPlaying
            val prevDur = _a2dpState.value.durationMs
            val prevPos = _a2dpState.value.playbackPositionMs
            _a2dpState.value = BluetoothConnectionState(
                deviceName = name,
                isConnected = true,
                profile = "A2DP",
                trackTitle = prevTrack,
                trackArtist = prevArtist,
                trackAlbum = prevAlbum,
                isPlaying = prevPlaying,
                durationMs = prevDur,
                playbackPositionMs = prevPos
            )
            _headsetState.value = BluetoothConnectionState(
                deviceName = name,
                isConnected = true,
                profile = "HEADSET"
            )
            Log.d(TAG, "Bluetooth device actively connected: $name")
        }
        if (!wasConnected) {
            Log.i(TAG, "applyConnectedState: Device newly connected ($name)")
        }
        com.x.carlauncher.media.PowerampNotificationListener.syncBluetoothSession()
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    override fun onCleared() {
        unregisterReceiver()
        bluetoothAdapter?.let { adapter ->
            headsetProxy?.let { adapter.closeProfileProxy(BluetoothProfile.HEADSET, it) }
            a2dpProxy?.let   { adapter.closeProfileProxy(BluetoothProfile.A2DP, it) }
            try { a2dpSinkProxy?.let { adapter.closeProfileProxy(11, it) } } catch (e: Exception) {}
            try { headsetClientProxy?.let { adapter.closeProfileProxy(16, it) } } catch (e: Exception) {}
        }
        super.onCleared()
    }
}
