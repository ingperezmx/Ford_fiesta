package com.x.carlauncher.whatsapp

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.x.carlauncher.media.PowerampNotificationListener
import com.x.carlauncher.system.AutomotiveIntentResolver
import com.x.carlauncher.voice.CarVoiceAssistant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modelo de datos para un mensaje recibido de WhatsApp en XCarOS.
 * Puro ASCII sin acentos ni caracteres especiales.
 */
data class WhatsAppMessage(
    val id: String,
    val sender: String,
    val text: String,
    val groupName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val formattedTime: String = "",
    val isRead: Boolean = false,
    val pendingIntent: PendingIntent? = null
)

/**
 * Gestor automotriz de mensajes entrantes de WhatsApp.
 * Intercepta notificaciones de com.whatsapp y com.whatsapp.w4b a traves de
 * PowerampNotificationListener (NotificationListenerService) y mantiene la
 * lista reactiva de mensajes nuevos para lectura visual y lectura en voz alta (TTS).
 */
object WhatsAppMessageManager {
    private const val TAG = "WhatsAppMsgManager"
    private const val MAX_MESSAGES = 25

    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.US)

    private val _messages = MutableStateFlow<List<WhatsAppMessage>>(emptyList())
    val messages: StateFlow<List<WhatsAppMessage>> = _messages.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    // ── Chequeo periodico cada 5 minutos ─────────────────────────────
    const val CHECK_INTERVAL_MS = 5 * 60 * 1000L // 5 minutos (300,000 ms)

    private val _lastCheckTime = MutableStateFlow<Long>(System.currentTimeMillis())
    val lastCheckTime: StateFlow<Long> = _lastCheckTime.asStateFlow()

    private val _isChecking = MutableStateFlow<Boolean>(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var periodicJob: Job? = null
    private val announcedMessageIds = mutableSetOf<String>()

    fun formatTime(timeMs: Long): String {
        return timeFormatter.format(Date(timeMs))
    }

    /**
     * Inicia el ciclo de chequeo continuo de mensajes cada 5 minutos.
     */
    fun startPeriodicCheck(context: Context, intervalMs: Long = CHECK_INTERVAL_MS) {
        if (periodicJob?.isActive == true) return
        val appContext = context.applicationContext
        periodicJob = scope.launch {
            Log.i(TAG, "Iniciado chequeo periodico de WhatsApp cada ${intervalMs / 60000} minutos")
            while (isActive) {
                delay(intervalMs)
                performCheck(appContext, notifyIfNew = true)
            }
        }
    }

    /**
     * Detiene el chequeo periodico si el ciclo de vida lo requiere.
     */
    fun stopPeriodicCheck() {
        periodicJob?.cancel()
        periodicJob = null
    }

    /**
     * Ejecuta una comprobacion inmediata de notificaciones activas de WhatsApp.
     */
    fun performCheck(context: Context, notifyIfNew: Boolean = false) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                _isChecking.value = true
                Log.i(TAG, "Ejecutando chequeo de mensajes WhatsApp...")

                val previousUnreadIds = _messages.value.filter { !it.isRead }.map { it.id }.toSet()
                PowerampNotificationListener.syncActiveNotifications(appContext)
                _lastCheckTime.value = System.currentTimeMillis()

                val currentUnread = _messages.value.filter { !it.isRead }
                val newUnread = currentUnread.filter { !previousUnreadIds.contains(it.id) && !announcedMessageIds.contains(it.id) }

                if (notifyIfNew && newUnread.isNotEmpty()) {
                    newUnread.forEach { announcedMessageIds.add(it.id) }
                    val count = newUnread.size
                    val alertText = if (count == 1) {
                        "Pablo, tienes un mensaje nuevo de WhatsApp."
                    } else {
                        "Pablo, tienes $count mensajes nuevos de WhatsApp."
                    }
                    CarVoiceAssistant.getInstance(appContext).speak(alertText)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error durante chequeo de WhatsApp: ${e.message}")
            } finally {
                _isChecking.value = false
            }
        }
    }

    private fun updateUnreadCount() {
        _unreadCount.value = _messages.value.count { !it.isRead }
    }

    /**
     * Procesa una notificacion entrante de WhatsApp.
     */
    fun handleNotification(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        val notification = sbn.notification ?: return
        if (notification.category == Notification.CATEGORY_SERVICE) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = notification.channelId?.lowercase(Locale.US) ?: ""
            if (channelId.contains("backup") || channelId.contains("silent")) return
        }

        val extras = notification.extras ?: return
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        val rawText = bigText ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()

        // Filtrar avisos tecnicos de WhatsApp
        val lowerTitle = rawTitle.lowercase(Locale.US)
        val lowerText = rawText.lowercase(Locale.US)
        if (lowerTitle.contains("whatsapp web") || lowerText.contains("whatsapp web")) return
        if (lowerTitle.contains("copia de seguridad") || lowerText.contains("copia de seguridad")) return
        if (lowerText.contains("comprobando mensajes") || lowerText.contains("checking for new messages")) return
        if (lowerText.contains("llamada de") || lowerTitle.contains("llamada de")) return

        val contentIntent = notification.contentIntent
        val postTime = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis()
        val timeStr = timeFormatter.format(Date(postTime))

        // 1. Extraccion directa de Bundles 'android.messages' (WhatsApp nativo en Android 10-15 / HyperOS)
        val rawMessages = extras.get("android.messages")
        if (rawMessages is Array<*> && rawMessages.isNotEmpty()) {
            var anyExtracted = false
            for (item in rawMessages) {
                if (item is android.os.Bundle) {
                    val msgBody = item.getCharSequence("text")?.toString()?.trim() ?: continue
                    if (msgBody.isBlank()) continue

                    val rawSender = item.getCharSequence("sender")?.toString()?.trim()
                    val senderName = if (!rawSender.isNullOrBlank()) rawSender else rawTitle.ifBlank { "WhatsApp" }
                    val msgTime = item.getLong("time", postTime)
                    val msgId = "${sbn.id}_${msgTime}_${senderName.hashCode()}_${msgBody.hashCode()}"

                    addOrUpdateMessage(
                        WhatsAppMessage(
                            id = msgId,
                            sender = senderName,
                            text = msgBody,
                            groupName = subText,
                            timestamp = msgTime,
                            formattedTime = timeFormatter.format(Date(msgTime)),
                            isRead = false,
                            pendingIntent = contentIntent
                        )
                    )
                    anyExtracted = true
                }
            }
            if (anyExtracted) return
        }

        // 2. Extraccion de lineas de texto 'android.textLines' (InboxStyle agrupado)
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?: extras.getCharSequenceArray("android.textLines")
        if (textLines != null && textLines.isNotEmpty()) {
            var anyExtracted = false
            for (line in textLines) {
                val lineStr = line?.toString()?.trim() ?: continue
                if (lineStr.isBlank()) continue

                val colonIdx = lineStr.indexOf(':')
                val (msgSender, msgBody) = if (colonIdx > 0 && colonIdx < lineStr.length - 1) {
                    Pair(lineStr.substring(0, colonIdx).trim(), lineStr.substring(colonIdx + 1).trim())
                } else {
                    Pair(rawTitle.ifBlank { "WhatsApp" }, lineStr)
                }

                val msgId = "${sbn.id}_${postTime}_${msgSender.hashCode()}_${msgBody.hashCode()}"
                addOrUpdateMessage(
                    WhatsAppMessage(
                        id = msgId,
                        sender = msgSender,
                        text = msgBody,
                        groupName = subText,
                        timestamp = postTime,
                        formattedTime = timeStr,
                        isRead = false,
                        pendingIntent = contentIntent
                    )
                )
                anyExtracted = true
            }
            if (anyExtracted) return
        }

        // 3. Extraccion estructurada mediante MessagingStyle compat
        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        if (messagingStyle != null && messagingStyle.messages.isNotEmpty()) {
            val isGroup = messagingStyle.isGroupConversation
            val conversationTitle = messagingStyle.conversationTitle?.toString()?.trim()

            for (m in messagingStyle.messages) {
                val msgBody = m.text?.toString()?.trim() ?: continue
                if (msgBody.isBlank()) continue

                val senderName = m.person?.name?.toString()?.trim()
                    ?: if (isGroup && !conversationTitle.isNullOrBlank()) conversationTitle else rawTitle.ifBlank { "WhatsApp" }

                val msgTime = if (m.timestamp > 0) m.timestamp else postTime
                val msgId = "${sbn.id}_${msgTime}_${senderName.hashCode()}_${msgBody.hashCode()}"

                addOrUpdateMessage(
                    WhatsAppMessage(
                        id = msgId,
                        sender = senderName,
                        text = msgBody,
                        groupName = if (isGroup) conversationTitle else null,
                        timestamp = msgTime,
                        formattedTime = timeFormatter.format(Date(msgTime)),
                        isRead = false,
                        pendingIntent = contentIntent
                    )
                )
            }
            return
        }

        // 4. Extraccion estandar de texto, bigText y tickerText
        val ticker = notification.tickerText?.toString()?.trim() ?: ""
        val finalText = if (rawText.isNotBlank()) rawText else if (ticker.isNotBlank() && !ticker.startsWith("WhatsApp")) ticker else ""

        val isSummaryNotice = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 && finalText.contains("mensajes nuevos")
        if (finalText.isNotBlank() && !isSummaryNotice) {
            val senderName = rawTitle.ifBlank { "WhatsApp" }
            val msgId = "${sbn.id}_${postTime}_${senderName.hashCode()}_${finalText.hashCode()}"

            addOrUpdateMessage(
                WhatsAppMessage(
                    id = msgId,
                    sender = senderName,
                    text = finalText,
                    groupName = subText,
                    timestamp = postTime,
                    formattedTime = timeStr,
                    isRead = false,
                    pendingIntent = contentIntent
                )
            )
        }
    }

    private fun addOrUpdateMessage(msg: WhatsAppMessage) {
        val lowerText = msg.text.lowercase().trim()
        // Omitir avisos de resumen genericos del sistema (ej. "2 mensajes nuevos")
        if (lowerText.contains("mensajes nuevos") || lowerText.contains("nuevo mensaje") || lowerText.matches(Regex("\\d+\\s+mensajes?.*"))) {
            return
        }

        val current = _messages.value.toMutableList()
        // Deduplicar si ya existe el mensaje exacto dentro de una ventana de 2 minutos
        val existingIndex = current.indexOfFirst {
            it.id == msg.id || (it.sender == msg.sender && it.text == msg.text && Math.abs(it.timestamp - msg.timestamp) < 120000L)
        }

        if (existingIndex >= 0) {
            current[existingIndex] = msg.copy(isRead = current[existingIndex].isRead)
        } else {
            current.add(0, msg)
        }

        // Mantener tope maximo ordenado por tiempo descendente
        val trimmed = current.sortedByDescending { it.timestamp }.take(MAX_MESSAGES)
        _messages.value = trimmed
        updateUnreadCount()
        Log.i(TAG, "Nuevo mensaje registrado de ${msg.sender}: ${msg.text}")
    }

    /**
     * Marca un mensaje como leido.
     */
    fun markAsRead(id: String) {
        val current = _messages.value.map {
            if (it.id == id) it.copy(isRead = true) else it
        }
        _messages.value = current
        updateUnreadCount()
    }

    /**
     * Marca todos los mensajes como leidos.
     */
    fun markAllAsRead() {
        val current = _messages.value.map { it.copy(isRead = true) }
        _messages.value = current
        updateUnreadCount()
    }

    /**
     * Elimina todos los mensajes de la lista.
     */
    fun clearAll() {
        announcedMessageIds.clear()
        _messages.value = emptyList()
        updateUnreadCount()
    }

    /**
     * Limpia secuencias complejas de emojis o caracteres no verbales para sintesis TTS fluida.
     */
    fun cleanTextForSpeech(raw: String): String {
        return raw.replace(Regex("[\\p{So}\\p{Cn}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Procesa la remocion de una notificacion del sistema (ej. leida o descartada en el telefono).
     */
    fun handleNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        val prefix = "${sbn.id}_"
        val current = _messages.value.map {
            if (it.id.startsWith(prefix)) it.copy(isRead = true) else it
        }
        _messages.value = current
        updateUnreadCount()
    }

    /**
     * Obtiene los mensajes no leidos ordenados del mas reciente al mas antiguo.
     */
    fun getUnreadMessages(): List<WhatsAppMessage> {
        return _messages.value.filter { !it.isRead }
    }

    /**
     * Lee un mensaje en voz alta con Jarvis / CarVoiceAssistant y lo marca como leido.
     */
    fun speakMessage(message: WhatsAppMessage, voiceAssistant: CarVoiceAssistant) {
        markAsRead(message.id)
        val cleanBody = cleanTextForSpeech(message.text)
        val isVoiceNote = cleanBody.contains("Mensaje de voz", ignoreCase = true) || cleanBody.contains("audio", ignoreCase = true)
        val textToSpeak = if (isVoiceNote) {
            "Pablo, ${message.sender} te envio una nota de voz. Toca la tarjeta para abrirla y escucharla en WhatsApp."
        } else if (cleanBody.isNotBlank()) {
            "Pablo, mensaje de ${message.sender}: $cleanBody"
        } else {
            "Pablo, mensaje multimedia o emoji de ${message.sender}"
        }
        voiceAssistant.speak(textToSpeak)
    }

    /**
     * Abre WhatsApp o la conversacion correspondiente.
     */
    fun openChat(context: Context, message: WhatsAppMessage?) {
        if (message?.pendingIntent != null) {
            try {
                message.pendingIntent.send()
                markAsRead(message.id)
                return
            } catch (e: Exception) {
                Log.w(TAG, "No fue posible lanzar pendingIntent de chat: ${e.message}")
            }
        }
        launchWhatsAppApp(context)
    }

    /**
     * Abre la aplicacion principal de WhatsApp.
     */
    fun launchWhatsAppApp(context: Context) {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage("com.whatsapp")
            ?: pm.getLaunchIntentForPackage("com.whatsapp.w4b")
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            AutomotiveIntentResolver.launchSafely(context, intent)
        }
    }

    // Sin mensajes de prueba: solo llegan mensajes reales de WhatsApp
    // leídos desde sus notificaciones.
}
