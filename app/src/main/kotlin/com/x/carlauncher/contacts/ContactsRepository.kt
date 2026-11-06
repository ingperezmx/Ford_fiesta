package com.x.carlauncher.contacts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

/**
 * Repositorio de contactos integrado para X CarOS.
 * Lee de forma asincrona la agenda del telefono, elimina duplicados,
 * gestiona favoritos locales y permite llamar o abrir WhatsApp en un solo toque.
 * Todo el codigo y textos en ASCII puro.
 */
object ContactsRepository {

    private const val TAG = "ContactsRepository"
    private const val PREFS_NAME = "pablo_car_os_contacts"
    private const val KEY_FAVORITES = "user_favorite_contact_ids"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _contacts = MutableStateFlow<List<CarContact>>(emptyList())
    val contacts: StateFlow<List<CarContact>> = _contacts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _hasPermission = MutableStateFlow(true)
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private var localFavoriteIds = mutableSetOf<String>()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        localFavoriteIds = prefs.getStringSet(KEY_FAVORITES, emptySet())?.toMutableSet() ?: mutableSetOf()
        loadContacts(context)
    }

    fun loadContacts(context: Context) {
        val appContext = context.applicationContext
        val permGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        _hasPermission.value = permGranted

        if (!permGranted) {
            Log.w(TAG, "Permiso READ_CONTACTS no concedido todavia")
            _contacts.value = emptyList()
            return
        }

        scope.launch {
            _isLoading.value = true
            val list = withContext(Dispatchers.IO) {
                querySystemContacts(appContext)
            }
            _contacts.value = list
            _isLoading.value = false
            Log.i(TAG, "Se cargaron ${list.size} contactos del sistema")
        }
    }

    private fun querySystemContacts(context: Context): List<CarContact> {
        val result = mutableListOf<CarContact>()
        val seenKeys = mutableSetOf<String>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
            ContactsContract.CommonDataKinds.Phone.STARRED,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )

        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC"

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )

            if (cursor != null && cursor.moveToFirst()) {
                val idCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                val numCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
                val starCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)
                val typeCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)

                var indexCounter = 0

                while (!cursor.isAfterLast) {
                    val id = if (idCol >= 0) cursor.getLong(idCol) else 0L
                    val rawName = if (nameCol >= 0) cursor.getString(nameCol) ?: "" else ""
                    val rawNumber = if (numCol >= 0) cursor.getString(numCol) ?: "" else ""
                    val photoUri = if (photoCol >= 0) cursor.getString(photoCol) else null
                    val isStarredSys = if (starCol >= 0) cursor.getInt(starCol) == 1 else false
                    val typeInt = if (typeCol >= 0) cursor.getInt(typeCol) else ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE

                    val cleanNumber = rawNumber.replace(" ", "").replace("-", "")
                    val dedupeKey = "${rawName.lowercase().trim()}#$cleanNumber"

                    if (rawName.isNotBlank() && cleanNumber.isNotBlank() && !seenKeys.contains(dedupeKey)) {
                        seenKeys.add(dedupeKey)

                        val isLocalFav = localFavoriteIds.contains(id.toString())
                        val isFavorite = isStarredSys || isLocalFav

                        val label = when (typeInt) {
                            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Casa"
                            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Trabajo"
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Movil"
                            else -> "Telefono"
                        }

                        val initials = computeInitials(rawName)
                        val formattedNum = formatDisplayNumber(rawNumber)

                        result.add(
                            CarContact(
                                id = id,
                                name = stripAccents(rawName),
                                number = rawNumber,
                                formattedNumber = formattedNum,
                                photoUri = photoUri,
                                isFavorite = isFavorite,
                                typeLabel = label,
                                initials = initials,
                                colorIndex = (indexCounter++) % 6
                            )
                        )
                    }

                    cursor.moveToNext()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error consultando contactos del sistema", e)
        } finally {
            try { cursor?.close() } catch (_: Exception) {}
        }

        // Ordenar favoritos primero, luego alfabetico
        return result.sortedWith(compareByDescending<CarContact> { it.isFavorite }.thenBy { it.name.lowercase() })
    }

    private fun computeInitials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> "C"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> "${parts[0].take(1)}${parts[1].take(1)}".uppercase()
        }
    }

    private fun formatDisplayNumber(raw: String): String {
        val digits = raw.filter { it.isDigit() || it == '+' }
        if (digits.length == 10) {
            // Ejemplo Mexico: 993 123 4567
            return "${digits.substring(0, 3)} ${digits.substring(3, 6)} ${digits.substring(6)}"
        } else if (digits.startsWith("+52") && digits.length == 13) {
            return "+52 ${digits.substring(3, 6)} ${digits.substring(6, 9)} ${digits.substring(9)}"
        }
        return raw.trim()
    }

    fun stripAccents(str: String): String {
        val nfd = Normalizer.normalize(str, Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{M}"), "")
    }

    fun toggleFavorite(context: Context, contactId: Long) {
        val key = contactId.toString()
        if (localFavoriteIds.contains(key)) {
            localFavoriteIds.remove(key)
        } else {
            localFavoriteIds.add(key)
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_FAVORITES, localFavoriteIds).apply()

        // Actualizar lista en memoria
        val current = _contacts.value
        _contacts.value = current.map {
            if (it.id == contactId) it.copy(isFavorite = localFavoriteIds.contains(key))
            else it
        }.sortedWith(compareByDescending<CarContact> { it.isFavorite }.thenBy { it.name.lowercase() })
    }

    fun callContact(context: Context, number: String) {
        val clean = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (clean.isBlank()) return

        try {
            val hasCallPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            if (hasCallPerm) {
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$clean")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(callIntent)
            } else {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error iniciando llamada: ${e.message}")
            try {
                val fallback = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Exception) {}
        }
    }

    fun openWhatsApp(context: Context, number: String) {
        val digits = number.filter { it.isDigit() }
        if (digits.isBlank()) return

        // Si son 10 digitos (numero local de Mexico), prefijar con 52
        val fullNumber = if (digits.length == 10) "52$digits" else digits

        try {
            val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$fullNumber")).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(waIntent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$fullNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Error abriendo WhatsApp: ${e.message}")
            }
        }
    }

    fun findBestMatch(query: String): CarContact? {
        val cleanQuery = stripAccents(query).lowercase().trim()
        if (cleanQuery.isBlank()) return null

        val list = _contacts.value

        // Coincidencia exacta de nombre
        list.firstOrNull { it.name.lowercase() == cleanQuery }?.let { return it }

        // Coincidencia que empiece con la consulta
        list.firstOrNull { it.name.lowercase().startsWith(cleanQuery) }?.let { return it }

        // Coincidencia que contenga la consulta
        list.firstOrNull { it.name.lowercase().contains(cleanQuery) }?.let { return it }

        return null
    }
}
