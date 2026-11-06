package com.x.carlauncher.carassets

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.x.carlauncher.R
import java.io.File
import java.io.FileOutputStream

/**
 * Definition of the 7 vehicle graphic slots for XCarOS.
 */
enum class CarSlot(
    val title: String,
    val subtitle: String,
    val filename: String,
    val defaultDrawableRes: Int
) {
    BASE(
        title = "Vehiculo Base (Luz Diurna)",
        subtitle = "Carroceria principal con luces apagadas",
        filename = "custom_corolla_base.png",
        defaultDrawableRes = R.drawable.corolla_base
    ),
    LIGHTS_ON(
        title = "Vehiculo (Luces Encendidas / Noche)",
        subtitle = "Carroceria con faros principales y antinieblas iluminados",
        filename = "custom_corolla_lights_on.png",
        defaultDrawableRes = R.drawable.corolla_lights_on
    ),
    DOOR_FR(
        title = "Puerta Delantera Derecha (Copiloto)",
        subtitle = "Capa de puerta del copiloto abierta",
        filename = "custom_corolla_door_fr.png",
        defaultDrawableRes = R.drawable.corolla_door_fr
    ),
    DOOR_FL(
        title = "Puerta Delantera Izquierda (Conductor)",
        subtitle = "Capa de puerta del conductor abierta",
        filename = "custom_corolla_door_fl.png",
        defaultDrawableRes = R.drawable.corolla_door_fl
    ),
    DOOR_RR(
        title = "Puerta Trasera Derecha",
        subtitle = "Capa de puerta trasera derecha abierta",
        filename = "custom_corolla_door_rr.png",
        defaultDrawableRes = R.drawable.corolla_door_rr
    ),
    DOOR_RL(
        title = "Puerta Trasera Izquierda",
        subtitle = "Capa de puerta trasera izquierda abierta",
        filename = "custom_corolla_door_rl.png",
        defaultDrawableRes = R.drawable.corolla_door_rl
    ),
    HOOD(
        title = "Capo / Vano Motor",
        subtitle = "Capa del capo levantado mostrando el motor",
        filename = "custom_corolla_hood.png",
        defaultDrawableRes = R.drawable.corolla_hood
    ),
    TRUNK(
        title = "Maletero / Aleron",
        subtitle = "Capa del maletero con aleron deportivo levantado",
        filename = "custom_corolla_trunk.png",
        defaultDrawableRes = R.drawable.corolla_trunk
    );
}

/**
 * Manages custom vehicle assets stored in the app's internal persistent directory.
 */
object CarAssetManager {

    private const val TAG = "CarAssetManager"

    private fun getStorageDir(context: Context): File {
        val dir = File(context.filesDir, "custom_car_assets")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Seeds the Ford Fiesta 2016 sedan assets into persistent internal storage
     * on initial boot / app startup if not already present.
     */
    fun initialize(context: Context) {
        val dir = getStorageDir(context)
        for (slot in listOf(CarSlot.BASE, CarSlot.LIGHTS_ON)) {
            val file = File(dir, slot.filename)
            if (!file.exists()) {
                try {
                    context.assets.open("default_car_assets/${slot.filename}").use { input ->
                        FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "Default vehicle asset deployed: ${slot.filename}")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not seed default asset from assets for ${slot.filename}: ${e.message}")
                }
            }
        }
    }

    fun hasCustomImage(context: Context, slot: CarSlot): Boolean {
        return File(getStorageDir(context), slot.filename).exists()
    }

    fun getCustomBitmap(context: Context, slot: CarSlot): ImageBitmap? {
        val file = File(getStorageDir(context), slot.filename)
        if (file.exists()) {
            try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) return bitmap.asImageBitmap()
            } catch (e: Exception) {
                Log.w(TAG, "Error decoding file ${file.absolutePath}", e)
            }
        }
        // Direct fallback to bundled APK assets
        return try {
            context.assets.open("default_car_assets/${slot.filename}").use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                bitmap?.asImageBitmap()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun saveCustomImage(context: Context, slot: CarSlot, uri: Uri): Boolean {
        return try {
            val file = File(getStorageDir(context), slot.filename)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun resetSlot(context: Context, slot: CarSlot): Boolean {
        val file = File(getStorageDir(context), slot.filename)
        return if (file.exists()) file.delete() else true
    }

    fun resetAll(context: Context) {
        val dir = getStorageDir(context)
        dir.listFiles()?.forEach { it.delete() }
    }
}
