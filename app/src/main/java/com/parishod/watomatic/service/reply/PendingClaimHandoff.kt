package com.parishod.watomatic.service.reply

/**
 * Coordinates fallback in-memory handoff of an already-acquired persistent notification claim token
 * between [com.parishod.watomatic.service.rules.RuleNotificationProcessor] and legacy callbacks.
 *
 * Active unconsumed entries are NEVER evicted merely for exceeding 512 entries; cleanup is driven by
 * explicit completion/cancellation, consumption, and TTL-based expiration of stale entries.
 */
object PendingClaimHandoff {
    const val DEFAULT_TTL_MS: Long = 30L * 60L * 1000L
    private const val HARD_MAX_ENTRIES = 16_384

    private data class HandoffEntry(
        val claimToken: String,
        val registeredAtMs: Long,
        val expiresAtMs: Long
    )

    private val lock = Any()
    private val handedOffTokens = LinkedHashMap<String, HandoffEntry>(64, 0.75f, false)

    @JvmStatic
    @JvmOverloads
    fun register(
        eventKey: String?,
        claimToken: String?,
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = DEFAULT_TTL_MS
    ) {
        if (eventKey.isNullOrBlank() || claimToken.isNullOrBlank()) return
        val safeTtl = ttlMs.coerceAtLeast(1L)
        synchronized(lock) {
            purgeExpiredLocked(nowMs)
            // Keep all active non-expired handoffs intact; only guard against extreme unbounded leaks.
            if (handedOffTokens.size >= HARD_MAX_ENTRIES && !handedOffTokens.containsKey(eventKey)) {
                val eldestKey = handedOffTokens.entries.firstOrNull()?.key
                if (eldestKey != null) {
                    handedOffTokens.remove(eldestKey)
                }
            }
            handedOffTokens[eventKey] = HandoffEntry(
                claimToken = claimToken,
                registeredAtMs = nowMs,
                expiresAtMs = nowMs + safeTtl
            )
        }
    }

    @JvmStatic
    @JvmOverloads
    fun consume(
        eventKey: String?,
        nowMs: Long = System.currentTimeMillis()
    ): String? {
        if (eventKey.isNullOrBlank()) return null
        synchronized(lock) {
            val removed = handedOffTokens.remove(eventKey) ?: return null
            return if (removed.expiresAtMs > nowMs) removed.claimToken else null
        }
    }

    @JvmStatic
    @JvmOverloads
    fun peek(
        eventKey: String?,
        nowMs: Long = System.currentTimeMillis()
    ): String? {
        if (eventKey.isNullOrBlank()) return null
        synchronized(lock) {
            val entry = handedOffTokens[eventKey] ?: return null
            if (entry.expiresAtMs <= nowMs) {
                handedOffTokens.remove(eventKey)
                return null
            }
            return entry.claimToken
        }
    }

    @JvmStatic
    fun complete(eventKey: String?, expectedClaimToken: String? = null): Boolean {
        return removeIfMatches(eventKey, expectedClaimToken)
    }

    @JvmStatic
    fun cancel(eventKey: String?, expectedClaimToken: String? = null): Boolean {
        return removeIfMatches(eventKey, expectedClaimToken)
    }

    private fun removeIfMatches(eventKey: String?, expectedClaimToken: String?): Boolean {
        if (eventKey.isNullOrBlank()) return false
        synchronized(lock) {
            val existing = handedOffTokens[eventKey] ?: return false
            if (expectedClaimToken != null && existing.claimToken != expectedClaimToken) {
                return false
            }
            handedOffTokens.remove(eventKey)
            return true
        }
    }

    @JvmStatic
    @JvmOverloads
    fun purgeExpired(nowMs: Long = System.currentTimeMillis()): Int {
        synchronized(lock) {
            return purgeExpiredLocked(nowMs)
        }
    }

    @JvmStatic
    fun activeSize(): Int {
        synchronized(lock) {
            return handedOffTokens.size
        }
    }

    @JvmStatic
    fun clear() {
        synchronized(lock) {
            handedOffTokens.clear()
        }
    }

    private fun purgeExpiredLocked(nowMs: Long): Int {
        var removed = 0
        val iterator = handedOffTokens.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.expiresAtMs <= nowMs) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }
}
