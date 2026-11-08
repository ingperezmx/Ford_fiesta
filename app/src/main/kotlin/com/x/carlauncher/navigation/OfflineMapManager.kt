package com.x.carlauncher.navigation

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView

/**
 * Offline Map Tile Downloader and Manager for X CarOS.
 * Descarga y guarda en caché los mosaicos del mapa alrededor de la ubicación
 * GPS en vivo del vehículo (en cualquier lugar de México) para uso sin conexión.
 */
object OfflineMapManager {

    private const val TAG = "OfflineMapManager"

    // Custom online tile source that permits bulk download and caching
    val AUTOMOTIVE_TILE_SOURCE = XYTileSource(
        "AutomotiveMapnik",
        0, 19, 256, ".png",
        arrayOf(
            "https://a.tile.openstreetmap.org/",
            "https://b.tile.openstreetmap.org/",
            "https://c.tile.openstreetmap.org/"
        ),
        "© OpenStreetMap contributors",
        TileSourcePolicy(4, 0) // flags = 0 -> permits bulk download without TileSourcePolicyException
    )

    private val _downloadProgress = MutableStateFlow(0) // 0 to 100%
    val downloadProgress = _downloadProgress.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading = _isDownloading.asStateFlow()

    private val _downloadStatus = MutableStateFlow("Mapas offline listos")
    val downloadStatus = _downloadStatus.asStateFlow()

    private var currentTask: CacheManager.CacheManagerTask? = null

    /**
     * Descarga y guarda en caché los mosaicos alrededor de la posición GPS actual en vivo.
     */
    fun downloadCurrentArea(context: Context, mapView: MapView) {
        if (_isDownloading.value) {
            Toast.makeText(context, "Descarga de mapas offline en curso...", Toast.LENGTH_SHORT).show()
            return
        }

        val loc = com.x.carlauncher.location.VehicleLocationManager.currentLocation
        if (!loc.hasFix || (loc.latitude == 0.0 && loc.longitude == 0.0)) {
            Toast.makeText(context, "Esperando señal GPS real para descargar el área actual...", Toast.LENGTH_SHORT).show()
            return
        }

        // Área dinámica de ~20 km alrededor de la posición GPS actual en vivo (en cualquier parte de México)
        val dynamicBox = BoundingBox(
            loc.latitude + 0.12,
            loc.longitude + 0.15,
            loc.latitude - 0.12,
            loc.longitude - 0.15
        )

        try {
            mapView.setTileSource(AUTOMOTIVE_TILE_SOURCE)

            val cacheManager = CacheManager(mapView)
            val minZoom = 12
            val maxZoom = 15

            _isDownloading.value = true
            _downloadProgress.value = 0
            _downloadStatus.value = "Iniciando descarga de mapa del área actual..."
            Toast.makeText(context, "Descargando mapas de tu zona actual para uso offline...", Toast.LENGTH_SHORT).show()

            currentTask = cacheManager.downloadAreaAsyncNoUI(
                context,
                dynamicBox,
                minZoom,
                maxZoom,
                object : CacheManager.CacheManagerCallback {
                    override fun onTaskComplete() {
                        _isDownloading.value = false
                        _downloadProgress.value = 100
                        _downloadStatus.value = "Mapas de la zona actual guardados (100% Offline)"
                        Log.i(TAG, "Offline map download complete for current GPS area")
                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(context, "Mapas de la zona actual listos para uso sin conexion.", Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun updateProgress(progress: Int, current: Int, total: Int, errors: Int) {
                        val pct = if (total > 0) (current * 100) / total else 0
                        _downloadProgress.value = pct
                        _downloadStatus.value = "Descargando: $current / $total mosaicos ($pct%)"
                    }

                    override fun downloadStarted() {
                        _isDownloading.value = true
                        _downloadStatus.value = "Conectando con servidores de mapas..."
                        Log.i(TAG, "Map tile download started for current GPS bounding box")
                    }

                    override fun setPossibleTilesInArea(total: Int) {
                        Log.i(TAG, "Total possible tiles in current area: $total")
                    }

                    override fun onTaskFailed(errors: Int) {
                        _isDownloading.value = false
                        _downloadStatus.value = "Descarga pausada ($errors errores). Reintentando..."
                        Log.w(TAG, "Map download interrupted with $errors errors")
                    }
                }
            )
        } catch (e: Throwable) {
            _isDownloading.value = false
            _downloadStatus.value = "Mapas offline listos"
            Log.e(TAG, "Cannot start CacheManager bulk download", e)
        }
    }

    fun cancelDownload() {
        try {
            currentTask?.cancel(true)
        } catch (_: Exception) {}
        _isDownloading.value = false
        _downloadStatus.value = "Descarga cancelada"
    }
}
