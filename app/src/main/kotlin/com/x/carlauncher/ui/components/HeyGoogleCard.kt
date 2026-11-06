package com.x.carlauncher.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Redireccion de compatibilidad hacia GeminiCard.
 * Garantiza que cualquier invocacion previa se dirija al modulo integrado de Gemini.
 */
@Composable
fun HeyGoogleCard(
    isListening: Boolean,
    recognizedText: String,
    feedback: String,
    audioRmsLevel: Float,
    onMicClick: () -> Unit,
    onCommandClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    GeminiCard(
        isListening = isListening,
        recognizedText = recognizedText,
        feedback = feedback,
        audioRmsLevel = audioRmsLevel,
        onMicClick = onMicClick,
        onCommandClick = onCommandClick,
        modifier = modifier
    )
}
