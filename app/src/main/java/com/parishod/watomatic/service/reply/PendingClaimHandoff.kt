package com.parishod.watomatic.service.reply

import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates in-memory handoff of an already-acquired persistent notification claim token
 * between [com.parishod.watomatic.service.rules.RuleNotificationProcessor] and
 * [com.parishod.watomatic.service.NotificationService] so the owning execution reuses
 * its claimed token rather than rejecting itself as a duplicate.
 */
object PendingClaimHandoff {
    private const val MAX_ENTRIES = 512
    private val handedOffTokens = ConcurrentHashMap<String, String>()

    @JvmStatic
    fun register(eventKey: String?, claimToken: String?) {
        if (eventKey.isNullOrBlank() || claimToken.isNullOrBlank()) return
        if (handedOffTokens.size >= MAX_ENTRIES) {
            handedOffTokens.clear()
        }
        handedOffTokens[eventKey] = claimToken
    }

    @JvmStatic
    fun consume(eventKey: String?): String? {
        if (eventKey.isNullOrBlank()) return null
        return handedOffTokens.remove(eventKey)
    }

    @JvmStatic
    fun peek(eventKey: String?): String? {
        if (eventKey.isNullOrBlank()) return null
        return handedOffTokens[eventKey]
    }

    @JvmStatic
    fun clear() {
        handedOffTokens.clear()
    }
}
