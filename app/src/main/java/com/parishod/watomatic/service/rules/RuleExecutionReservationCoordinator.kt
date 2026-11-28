package com.parishod.watomatic.service.rules

import com.parishod.watomatic.model.rules.AutoReplyRule
import java.util.Locale
import java.util.UUID

/**
 * Coordinates atomic rule evaluation + pre-dispatch reservation across delayed and async AI
 * rule executions so concurrent notifications cannot bypass `cooldownSeconds` or
 * `maxRepliesPerSender`.
 */
class RuleExecutionReservationCoordinator(
    private val defaultTimeZone: java.util.TimeZone = java.util.TimeZone.getDefault()
) {
    enum class ReservationStatus {
        RESERVED,
        CONFIRMED,
        LOCKED_UNKNOWN,
        RELEASED
    }

    data class ReservationKey(
        val ruleId: Long,
        val packageNameLower: String,
        val senderScopeLower: String
    )

    data class RuleReservation(
        val reservationId: String,
        val key: ReservationKey,
        val ruleId: Long,
        val packageName: String,
        val senderScopeKey: String,
        val reservedAtMs: Long,
        var status: ReservationStatus,
        var effectiveTimestampMs: Long,
        var persistedToDb: Boolean = false
    )

    data class ReservedRuleEvaluation(
        val evaluation: RuleEvaluationResult,
        val reservation: RuleReservation
    )

    private val lock = Any()
    private val reservationsById = LinkedHashMap<String, RuleReservation>()
    private val reservationsByKey = HashMap<ReservationKey, MutableList<RuleReservation>>()

    fun evaluateAndReserve(
        rules: List<AutoReplyRule>,
        context: IncomingMessageContext,
        logStore: RuleExecutionLogStore,
        nowMillis: Long = System.currentTimeMillis()
    ): ReservedRuleEvaluation? {
        if (context.packageName.isBlank()) return null
        val senderTrimmed = context.senderName?.trim().orEmpty()
        if (!context.selfDisplayName.isNullOrBlank() &&
            senderTrimmed.equals(context.selfDisplayName.trim(), ignoreCase = true)
        ) {
            return null
        }

        if (context.notificationKey.isNotBlank() && context.messageTimestamp > 0L &&
            logStore.hasRepliedToNotification(context.notificationKey, context.messageTimestamp)
        ) {
            return null
        }

        val senderScopeKey = RuleEvaluator.buildSenderScopeKey(context.senderName, context.isGroup)
        val pkgLower = context.packageName.trim().lowercase(Locale.ROOT)
        val senderLower = senderScopeKey.lowercase(Locale.ROOT)

        synchronized(lock) {
            pruneOldEntriesLocked(nowMillis)
            val coordinatorAwareStore = CoordinatorAwareLogStore(logStore, pkgLower, senderLower)
            val evaluator = RuleEvaluator(coordinatorAwareStore, defaultTimeZone)
            val result = evaluator.evaluate(rules, context, nowMillis) ?: return null

            val key = ReservationKey(
                ruleId = result.rule.id,
                packageNameLower = pkgLower,
                senderScopeLower = senderLower
            )
            val reservation = RuleReservation(
                reservationId = UUID.randomUUID().toString(),
                key = key,
                ruleId = result.rule.id,
                packageName = context.packageName,
                senderScopeKey = senderScopeKey,
                reservedAtMs = nowMillis,
                status = ReservationStatus.RESERVED,
                effectiveTimestampMs = nowMillis,
                persistedToDb = false
            )
            reservationsById[reservation.reservationId] = reservation
            reservationsByKey.getOrPut(key) { mutableListOf() }.add(reservation)
            return ReservedRuleEvaluation(evaluation = result, reservation = reservation)
        }
    }

    fun confirmDispatched(
        reservationId: String?,
        repliedAtMs: Long = System.currentTimeMillis()
    ) {
        if (reservationId.isNullOrBlank()) return
        synchronized(lock) {
            val res = reservationsById[reservationId] ?: return
            if (res.status == ReservationStatus.RELEASED) return
            res.status = ReservationStatus.CONFIRMED
            res.effectiveTimestampMs = maxOf(res.reservedAtMs, repliedAtMs)
        }
    }

    fun markPersistedToDb(reservationId: String?) {
        if (reservationId.isNullOrBlank()) return
        synchronized(lock) {
            val res = reservationsById[reservationId] ?: return
            res.persistedToDb = true
            if (res.status == ReservationStatus.CONFIRMED) {
                removeReservationLocked(res)
            }
        }
    }

    fun markUnknown(
        reservationId: String?,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        if (reservationId.isNullOrBlank()) return
        synchronized(lock) {
            val res = reservationsById[reservationId] ?: return
            res.status = ReservationStatus.LOCKED_UNKNOWN
            res.effectiveTimestampMs = maxOf(res.reservedAtMs, timestampMs)
        }
    }

    fun release(reservationId: String?) {
        if (reservationId.isNullOrBlank()) return
        synchronized(lock) {
            val res = reservationsById[reservationId] ?: return
            if (res.status == ReservationStatus.CONFIRMED || res.status == ReservationStatus.LOCKED_UNKNOWN) {
                return
            }
            res.status = ReservationStatus.RELEASED
            removeReservationLocked(res)
        }
    }

    fun releaseAllReserved(): Int {
        synchronized(lock) {
            val toRelease = reservationsById.values
                .filter { it.status == ReservationStatus.RESERVED }
                .toList()
            for (res in toRelease) {
                res.status = ReservationStatus.RELEASED
                removeReservationLocked(res)
            }
            return toRelease.size
        }
    }

    fun activeReservedCount(): Int {
        synchronized(lock) {
            return reservationsById.values.count { it.status == ReservationStatus.RESERVED }
        }
    }

    fun totalTrackedCount(): Int {
        synchronized(lock) {
            return reservationsById.size
        }
    }

    fun getStatus(reservationId: String?): ReservationStatus? {
        if (reservationId.isNullOrBlank()) return null
        synchronized(lock) {
            return reservationsById[reservationId]?.status
        }
    }

    fun clearForTesting() {
        synchronized(lock) {
            reservationsById.clear()
            reservationsByKey.clear()
        }
    }

    private fun removeReservationLocked(res: RuleReservation) {
        reservationsById.remove(res.reservationId)
        val list = reservationsByKey[res.key] ?: return
        list.removeAll { it.reservationId == res.reservationId }
        if (list.isEmpty()) {
            reservationsByKey.remove(res.key)
        }
    }

    private fun pruneOldEntriesLocked(nowMillis: Long) {
        val toRemove = reservationsById.values.filter { entry ->
            when (entry.status) {
                ReservationStatus.RESERVED -> false
                ReservationStatus.RELEASED -> true
                ReservationStatus.CONFIRMED -> entry.persistedToDb || (nowMillis - entry.effectiveTimestampMs) > MAX_IN_MEMORY_RETENTION_MS
                ReservationStatus.LOCKED_UNKNOWN -> (nowMillis - entry.effectiveTimestampMs) > MAX_IN_MEMORY_RETENTION_MS
            }
        }.toList()
        for (entry in toRemove) {
            removeReservationLocked(entry)
        }
    }

    private inner class CoordinatorAwareLogStore(
        private val delegate: RuleExecutionLogStore,
        private val expectedPkgLower: String,
        private val expectedSenderLower: String
    ) : RuleExecutionLogStore {
        override fun getLastReplyTimeForRuleAndSender(
            ruleId: Long,
            packageName: String,
            senderName: String
        ): Long? {
            val dbTime = delegate.getLastReplyTimeForRuleAndSender(ruleId, packageName, senderName)
            val key = ReservationKey(
                ruleId = ruleId,
                packageNameLower = packageName.trim().lowercase(Locale.ROOT).ifEmpty { expectedPkgLower },
                senderScopeLower = senderName.trim().lowercase(Locale.ROOT).ifEmpty { expectedSenderLower }
            )
            val inMemTime = reservationsByKey[key]
                ?.filter { isBlockingReservation(it) }
                ?.maxOfOrNull { it.effectiveTimestampMs }
            return when {
                dbTime == null -> inMemTime
                inMemTime == null -> dbTime
                else -> maxOf(dbTime, inMemTime)
            }
        }

        override fun getReplyCountForRuleAndSenderSince(
            ruleId: Long,
            packageName: String,
            senderName: String,
            sinceTime: Long
        ): Int {
            val dbCount = delegate.getReplyCountForRuleAndSenderSince(ruleId, packageName, senderName, sinceTime)
            val key = ReservationKey(
                ruleId = ruleId,
                packageNameLower = packageName.trim().lowercase(Locale.ROOT).ifEmpty { expectedPkgLower },
                senderScopeLower = senderName.trim().lowercase(Locale.ROOT).ifEmpty { expectedSenderLower }
            )
            val inMemCount = reservationsByKey[key]
                ?.count { isBlockingReservation(it) && it.effectiveTimestampMs >= sinceTime }
                ?: 0
            return dbCount + inMemCount
        }

        override fun hasRepliedToNotification(notificationKey: String, messageTimestamp: Long): Boolean {
            return delegate.hasRepliedToNotification(notificationKey, messageTimestamp)
        }

        override fun recordExecution(
            ruleId: Long,
            packageName: String,
            senderName: String,
            notificationKey: String,
            messageTimestamp: Long,
            repliedAt: Long
        ) {
            delegate.recordExecution(ruleId, packageName, senderName, notificationKey, messageTimestamp, repliedAt)
        }

        private fun isBlockingReservation(res: RuleReservation): Boolean {
            return when (res.status) {
                ReservationStatus.RESERVED,
                ReservationStatus.LOCKED_UNKNOWN -> true
                ReservationStatus.CONFIRMED -> !res.persistedToDb
                ReservationStatus.RELEASED -> false
            }
        }
    }

    companion object {
        private const val MAX_IN_MEMORY_RETENTION_MS = 24L * 60L * 60L * 1000L
    }
}
