package com.x.carlauncher.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real-time navigation telemetry and turn-by-turn guidance.
 * Supports both Waze and Google Maps notification interception.
 */
data class NavigationGuidance(
    val isNavigating: Boolean = false,
    val provider: String = "Waze", // "Waze" or "Google Maps"
    val instruction: String = "",
    val distance: String = "",
    val street: String = "",
    val eta: String = "",
    val remainingDistance: String = ""
)

object NavigationManager {
    private val _guidance = MutableStateFlow(NavigationGuidance())
    val guidance: StateFlow<NavigationGuidance> = _guidance.asStateFlow()

    // Preferred provider: "Waze" or "Google Maps"
    var preferredProvider: String = "Waze"

    fun updateGuidance(
        provider: String,
        instruction: String,
        distance: String,
        street: String,
        eta: String = "",
        remainingDistance: String = ""
    ) {
        _guidance.value = NavigationGuidance(
            isNavigating = true,
            provider = provider,
            instruction = instruction,
            distance = distance,
            street = street,
            eta = eta,
            remainingDistance = remainingDistance
        )
    }

    fun clearGuidance(provider: String? = null) {
        if (provider == null || _guidance.value.provider.equals(provider, ignoreCase = true)) {
            _guidance.value = NavigationGuidance(provider = preferredProvider)
        }
    }
}

