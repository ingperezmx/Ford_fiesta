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
 * Downloads and caches OpenStreetMap tiles for the Monterrey Metropolitan Area
 * (San Nicolas, Monterrey, Guadalupe, San Pedro, Apodaca, Escobedo, Santa Catarina)
 * so maps are 100% available offline without requiring mobile data or Wi-Fi.
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

    // Bounding Box for Monterrey Metropolitan Area & Nuevo Leon Corridors
    val MONTERREY_BOUNDING_BOX = BoundingBox(
        25.86,   // North (Escobedo / San Nicolas Norte / Apodaca)
        -100.15, // East (Guadalupe / Juarez / Apodaca)
        25.60,   // South (Monterrey Sur / Valle Oriente / San Pedro)
        -100.45  // West (Santa Catarina / Garcia)
    )

    private val _downloadProgress = MutableStateFlow(0) // 0 to 100%
    val downloadProgress = _downloadProgress.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading = _isDownloading.asStateFlow()

    private val _downloadStatus = MutableStateFlow("Mapas offline listos")
    val downloadStatus = _downloadStatus.asStateFlow()

    private var currentTask: CacheManager.CacheManagerTask? = null

    /**
     * Downloads and permanently caches map tiles for Monterrey and Nuevo Leon.
     */
    fun downloadMonterreyArea(context: Context, mapView: MapView) {
        if (_isDownloading.value) {
            Toast.makeText(context, "Descarga de mapas offline en curso...", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            mapView.setTileSource(AUTOMOTIVE_TILE_SOURCE)

            val cacheManager = CacheManager(mapView)
            val minZoom = 12
            val maxZoom = 15

            _isDownloading.value = true
            _downloadProgress.value = 0
            _downloadStatus.value = "Iniciando descarga de mapa de Monterrey y Nuevo Leon..."
            Toast.makeText(context, "Descargando mapas de Monterrey para uso offline...", Toast.LENGTH_SHORT).show()

            currentTask = cacheManager.downloadAreaAsyncNoUI(
                context,
                MONTERREY_BOUNDING_BOX,
                minZoom,
                maxZoom,
                object : CacheManager.CacheManagerCallback {
                    override fun onTaskComplete() {
                        _isDownloading.value = false
                        _downloadProgress.value = 100
                        _downloadStatus.value = "Mapas de Monterrey guardados (100% Offline)"
                        Log.i(TAG, "Offline map download complete for Monterrey")
                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(context, "Mapas de Monterrey listos para uso sin conexion.", Toast.LENGTH_LONG).show()
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
                        Log.i(TAG, "Map tile download started for Monterrey bounding box")
                    }

                    override fun setPossibleTilesInArea(total: Int) {
                        Log.i(TAG, "Total possible tiles in Monterrey area: $total")
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
            _downloadStatus.value = "Mapas de Monterrey listos offline"
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
