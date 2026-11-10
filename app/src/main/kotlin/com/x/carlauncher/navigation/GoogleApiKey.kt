package com.x.carlauncher.navigation

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * Lectura de la llave de Google Maps y de la identidad de la app en runtime.
 *
 * La llave vive en el AndroidManifest (meta-data com.google.android.geo.API_KEY)
 * y NUNCA se escribe en el codigo. Las llamadas directas a las APIs REST de
 * Google (Routes API, Places API New) necesitan ademas las cabeceras
 * X-Android-Package y X-Android-Cert: la llave esta restringida a la app
 * (paquete + huella SHA-1) y sin esas cabeceras Google rechaza la peticion
 * con 403, porque no puede comprobar que la llamada viene de esta app.
 * La huella se calcula en runtime desde la firma real instalada, asi sirve
 * igual con la clave de lanzamiento que con la de depuracion.
 */
object GoogleApiKey {

    private const val TAG = "GoogleApiKey"
    private const val META_KEY = "com.google.android.geo.API_KEY"

    /** Llave de Google Maps del meta-data del AndroidManifest, o null si no esta. */
    fun readApiKey(context: Context): String? {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName, PackageManager.GET_META_DATA
            )
            appInfo.metaData?.getString(META_KEY)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo leer la llave de Google Maps del manifest", e)
            null
        }
    }

    /**
     * Huella SHA-1 del certificado con el que esta firmada la app instalada,
     * en hexadecimal minusculo sin dos puntos (formato que espera la
     * cabecera X-Android-Cert). Null si no se puede calcular.
     */
    fun signingCertSha1Hex(context: Context): String? {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = info.signingInfo ?: return null
                if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
                else signingInfo.signingCertificateHistory
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
            val sig = signatures?.firstOrNull() ?: return null
            val digest = MessageDigest.getInstance("SHA-1").digest(sig.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo calcular la huella SHA-1 de la firma", e)
            null
        }
    }

    /**
     * Cabeceras de identidad de la app para las APIs REST de Google.
     * X-Android-Package: paquete de la app. X-Android-Cert: huella SHA-1
     * de la firma (solo si se pudo calcular).
     */
    fun androidIdentityHeaders(context: Context): Map<String, String> {
        val headers = mutableMapOf("X-Android-Package" to context.packageName)
        signingCertSha1Hex(context)?.let { headers["X-Android-Cert"] = it }
        return headers
    }
}
