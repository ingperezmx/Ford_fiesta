package com.x.carlauncher.media

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thread-safe persistent favorites manager for XCarOS2.
 * Remembers user-starred songs across reboots in SharedPreferences.
 */
object FavoritesManager {

    private const val PREFS_NAME = "x_favorites_prefs"
    private const val KEY_FAVORITES = "favorite_paths"

    private var prefs: SharedPreferences? = null
    private val _favoritesFlow = MutableStateFlow<Set<String>>(emptySet())
    val favoritesFlow: StateFlow<Set<String>> = _favoritesFlow.asStateFlow()

    fun initialize(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        val saved = sp.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
        _favoritesFlow.value = saved.toSet()
    }

    fun isFavorite(pathOrId: String): Boolean {
        if (pathOrId.isBlank()) return false
        val norm = normalizeKey(pathOrId)
        return _favoritesFlow.value.contains(norm)
    }

    fun toggleFavorite(pathOrId: String): Boolean {
        val sp = prefs ?: return false
        if (pathOrId.isBlank()) return false
        val norm = normalizeKey(pathOrId)
        val current = _favoritesFlow.value.toMutableSet()
        val willBeFav = if (current.contains(norm)) {
            current.remove(norm)
            false
        } else {
            current.add(norm)
            true
        }
        sp.edit().putStringSet(KEY_FAVORITES, current).apply()
        _favoritesFlow.value = current
        return willBeFav
    }

    fun setFavorite(pathOrId: String, isFav: Boolean) {
        val sp = prefs ?: return
        if (pathOrId.isBlank()) return
        val norm = normalizeKey(pathOrId)
        val current = _favoritesFlow.value.toMutableSet()
        if (isFav) {
            current.add(norm)
        } else {
            current.remove(norm)
        }
        sp.edit().putStringSet(KEY_FAVORITES, current).apply()
        _favoritesFlow.value = current
    }

    private fun normalizeKey(raw: String): String {
        return raw.trim().lowercase()
    }
}

