package com.x.carlauncher.contacts

/**
 * Modelo de datos optimizado para contactos automotrices en X CarOS.
 * Formateado en ASCII puro sin caracteres especiales.
 */
data class CarContact(
    val id: Long,
    val name: String,
    val number: String,
    val formattedNumber: String,
    val photoUri: String? = null,
    val isFavorite: Boolean = false,
    val typeLabel: String = "Movil",
    val initials: String = "",
    val colorIndex: Int = 0
)
