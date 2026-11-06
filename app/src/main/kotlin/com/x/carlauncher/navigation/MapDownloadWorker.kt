package com.x.carlauncher.navigation

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Background worker that automatically and silently downloads / updates
 * the offline Honduras OpenStreetMap sqlite archive (`honduras.sqlite`)
 * strictly over unmetered Wi-Fi connections.
 */
class MapDownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "MapDownloadWorker"
        const val WORK_NAME = "monterrey_map_download_worker"

        // Direct URL for Monterrey / Nuevo Leon offline OSM SQLite package
        const val DEFAULT_MAP_URL = "https://raw.githubusercontent.com/x-automotive/assets/main/maps/monterrey_nuevoleon.sqlite"
        private const val BACKUP_MAP_URL = "https://download.geofabrik.de/north-america/mexico-latest.osm.pbf"

        private val _mapUpdateEvent = MutableStateFlow(0L)
        val mapUpdateEvent = _mapUpdateEvent.asStateFlow()

        /**
         * Enqueues unique periodic check and initial immediate download on Wi-Fi or LTE.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(false)
                .build()

            // 1. One-time immediate request if file is missing
            val targetFile = getTargetFile()
            if (!targetFile.exists() || targetFile.length() < 1024) {
                val immediateWork = OneTimeWorkRequestBuilder<MapDownloadWorker>()
                    .setConstraints(constraints)
                    .addTag("map_download_immediate")
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "map_download_immediate",
                    ExistingWorkPolicy.KEEP,
                    immediateWork
                )
                Log.i(TAG, "Enqueued immediate map download for Monterrey / Nuevo Leon")
            }

            // 2. Periodic weekly update check
            val periodicWork = PeriodicWorkRequestBuilder<MapDownloadWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .addTag("map_download_periodic")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWork
            )
            Log.i(TAG, "Enqueued periodic map update for Monterrey")
        }

        fun getOsmdroidDir(): File {
            val baseDir = Environment.getExternalStorageDirectory()
            val dir = File(baseDir, "osmdroid")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        fun getTargetFile(): File {
            return File(getOsmdroidDir(), "monterrey_nuevoleon.sqlite")
        }

        fun getTempFile(): File {
            return File(getOsmdroidDir(), "monterrey_temp.sqlite")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val mapUrl = inputData.getString("map_url") ?: DEFAULT_MAP_URL
        val targetFile = getTargetFile()
        val tempFile = getTempFile()

        Log.i(TAG, "Starting map check/download from: $mapUrl")

        try {
            val url = URL(mapUrl)
            val headConn = url.openConnection() as HttpURLConnection
            headConn.requestMethod = "HEAD"
            headConn.connectTimeout = 15000
            headConn.readTimeout = 15000
            headConn.instanceFollowRedirects = true
            headConn.connect()

            val remoteLength = headConn.contentLengthLong
            val remoteLastModified = headConn.lastModified
            headConn.disconnect()

            // Verify if local file is already up to date
            if (targetFile.exists() && targetFile.length() > 0) {
                if (remoteLength > 0 && targetFile.length() == remoteLength) {
                    Log.i(TAG, "Local honduras.sqlite is already up-to-date (${targetFile.length()} bytes)")
                    return@withContext Result.success()
                }
                if (remoteLastModified > 0 && targetFile.lastModified() >= remoteLastModified) {
                    Log.i(TAG, "Local honduras.sqlite has matching or newer timestamp")
                    return@withContext Result.success()
                }
            }

            // Clean up any stale incomplete temp file
            if (tempFile.exists()) {
                tempFile.delete()
            }

            // Safe streaming download into temp file
            val getConn = url.openConnection() as HttpURLConnection
            getConn.requestMethod = "GET"
            getConn.connectTimeout = 20000
            getConn.readTimeout = 30000
            getConn.instanceFollowRedirects = true

            val responseCode = getConn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Server returned HTTP $responseCode for map URL: $mapUrl")
                return@withContext Result.retry()
            }

            var input: InputStream? = null
            var output: FileOutputStream? = null

            try {
                input = getConn.inputStream
                output = FileOutputStream(tempFile)
                val buffer = ByteArray(32768)
                var bytesRead: Int
                var totalRead = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (isStopped) {
                        Log.w(TAG, "Map download interrupted/stopped by WorkManager")
                        output.flush()
                        output.close()
                        tempFile.delete()
                        return@withContext Result.retry()
                    }
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                }
                output.flush()
                Log.i(TAG, "Download finished: $totalRead bytes written to temp file")
            } finally {
                try { output?.close() } catch (_: Exception) {}
                try { input?.close() } catch (_: Exception) {}
                getConn.disconnect()
            }

            // Atomic file replacement to prevent corrupted database
            if (tempFile.exists() && tempFile.length() > 0) {
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                val renamed = tempFile.renameTo(targetFile)
                if (renamed) {
                    if (remoteLastModified > 0) {
                        targetFile.setLastModified(remoteLastModified)
                    }
                    Log.i(TAG, "Honduras map sqlite archive successfully installed: ${targetFile.absolutePath}")
                    // Notify active MapView to refresh tiles
                    _mapUpdateEvent.value = System.currentTimeMillis()
                    return@withContext Result.success()
                } else {
                    Log.e(TAG, "Failed to rename temp map file to target")
                    return@withContext Result.failure()
                }
            } else {
                Log.e(TAG, "Temp map file empty or missing after download")
                return@withContext Result.retry()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error downloading map of Honduras", e)
            if (tempFile.exists()) tempFile.delete()
            return@withContext Result.retry()
        }
    }
}

