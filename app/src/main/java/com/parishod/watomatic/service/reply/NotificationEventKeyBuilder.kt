package com.parishod.watomatic.service.reply

import android.app.Notification
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import com.parishod.watomatic.model.utils.NotificationUtils
import java.security.MessageDigest
import java.util.Locale

/**
 * Derives a deterministic, privacy-safe SHA-256 event key for an incoming [StatusBarNotification].
 *
 * Uses durable notification identity fields (`packageName`, `sbn.key` or `(id, tag)`,
 * timestamp anchor, group flag, normalized sender key, and normalized incoming text digest)
 * rather than relying on sender name alone.
 */
object NotificationEventKeyBuilder {
    const val UNKNOWN_SENDER = "__unknown_sender__"
    private val WHITESPACE_REGEX = Regex("\\s+")

    @JvmStatic
    @JvmOverloads
    fun buildEventKey(
        sbn: StatusBarNotification?,
        fallbackIncomingMessage: String? = null
    ): String? {
        if (sbn == null) return null

        val notification = sbn.notification
        val extras = notification?.extras

        val normalizedPackage = sbn.packageName?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val sbnKey = sbn.key?.trim().orEmpty()
        val sbnTag = sbn.tag?.trim().orEmpty()
        val notifIdentity = if (sbnKey.isNotEmpty()) {
            "k:$sbnKey"
        } else {
            "id:${sbn.id}|t:$sbnTag"
        }

        val whenMs = notification?.`when` ?: 0L
        val postTimeMs = sbn.postTime
        val effectiveTimestampMs = when {
            whenMs > 0L -> whenMs
            postTimeMs > 0L -> postTimeMs
            else -> 0L
        }

        val isGroup = extras?.getBoolean("android.isGroupConversation", false) ?: false
        val rawSender = runCatching { NotificationUtils.getTitle(sbn) }.getOrNull()
        val senderNormalized = normalizeSenderKey(rawSender, isGroup)

        val extractedMessage = extractIncomingMessage(sbn) ?: fallbackIncomingMessage
        val normalizedMessage = normalizeMessage(extractedMessage)

        val canonical = buildString {
            append(normalizedPackage).append('|')
            append(notifIdentity).append('|')
            append(effectiveTimestampMs).append('|')
            append(isGroup).append('|')
            append(senderNormalized).append('|')
            append(normalizedMessage)
        }
        return "evt_${sha256Hex(canonical)}"
    }

    @JvmStatic
    @JvmOverloads
    fun normalizeSenderKey(senderName: String?, isGroup: Boolean = false): String {
        val trimmed = senderName?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (trimmed.isEmpty()) return UNKNOWN_SENDER
        return if (isGroup) "group:$trimmed" else trimmed
    }

    @JvmStatic
    fun isUnknownSender(senderKey: String?): Boolean {
        return senderKey.isNullOrBlank() || senderKey == UNKNOWN_SENDER
    }

    @JvmStatic
    fun normalizeMessage(message: String?): String {
        if (message.isNullOrBlank()) return ""
        return WHITESPACE_REGEX.replace(message.trim(), " ")
    }

    @JvmStatic
    fun extractIncomingMessage(sbn: StatusBarNotification?): String? {
        val extras = sbn?.notification?.extras ?: return null

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)
        if (!text.isNullOrBlank()) return text.toString()

        @Suppress("DEPRECATION")
        val rawText = extras.get("android.text")
        if (rawText != null && rawText.toString().isNotBlank()) return rawText.toString()

        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        if (!bigText.isNullOrBlank()) return bigText.toString()

        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!textLines.isNullOrEmpty()) {
            for (i in textLines.indices.reversed()) {
                val line = textLines[i]
                if (!line.isNullOrBlank()) return line.toString()
            }
        }

        @Suppress("DEPRECATION")
        val messages: Array<Parcelable>? = extras.getParcelableArray("android.messages")
        if (!messages.isNullOrEmpty()) {
            for (i in messages.indices.reversed()) {
                val msgBundle = messages[i] as? Bundle ?: continue
                val msgText = msgBundle.getCharSequence("text")
                if (!msgText.isNullOrBlank()) return msgText.toString()
            }
        }
        return null
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }
}
