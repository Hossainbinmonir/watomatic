package com.parishod.watomatic.service.reply

/**
 * Coordinates in-memory handoff of an already-acquired persistent notification claim token
 * between [com.parishod.watomatic.service.rules.RuleNotificationProcessor] and
 * [com.parishod.watomatic.service.NotificationService] so the owning execution reuses
 * its claimed token rather than rejecting itself as a duplicate.
 */
object PendingClaimHandoff {
    private const val MAX_ENTRIES = 512
    private val handedOffTokens = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    @JvmStatic
    fun register(eventKey: String?, claimToken: String?) {
        if (eventKey.isNullOrBlank() || claimToken.isNullOrBlank()) return
        synchronized(handedOffTokens) {
            handedOffTokens[eventKey] = claimToken
        }
    }

    @JvmStatic
    fun consume(eventKey: String?): String? {
        if (eventKey.isNullOrBlank()) return null
        synchronized(handedOffTokens) {
            return handedOffTokens.remove(eventKey)
        }
    }

    @JvmStatic
    fun peek(eventKey: String?): String? {
        if (eventKey.isNullOrBlank()) return null
        synchronized(handedOffTokens) {
            return handedOffTokens[eventKey]
        }
    }

    @JvmStatic
    fun clear() {
        synchronized(handedOffTokens) {
            handedOffTokens.clear()
        }
    }
}
