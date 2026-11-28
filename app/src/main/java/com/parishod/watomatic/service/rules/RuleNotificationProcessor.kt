package com.parishod.watomatic.service.rules

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import com.parishod.watomatic.NotificationWear
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.rules.AutoReplyRule
import com.parishod.watomatic.model.rules.AutoReplyRulesDatabase
import com.parishod.watomatic.model.utils.DbUtils
import com.parishod.watomatic.model.utils.NotificationUtils
import com.parishod.watomatic.service.reply.NotificationEventKeyBuilder
import com.parishod.watomatic.service.reply.PendingClaimHandoff
import com.parishod.watomatic.service.reply.PreDispatchRetryCoordinator
import com.parishod.watomatic.service.reply.ReplyExecutionManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RuleNotificationProcessor @JvmOverloads constructor(
    private val context: Context,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    private val dbUtils: DbUtils = DbUtils(context.applicationContext),
    val reservationCoordinator: RuleExecutionReservationCoordinator = RuleExecutionReservationCoordinator(),
    private val retryCoordinator: PreDispatchRetryCoordinator = PreDispatchRetryCoordinator(context.applicationContext)
) {
    interface ReplyDispatchCallback {
        fun onRuleMatchedSendReply(
            sbn: StatusBarNotification,
            notificationWear: NotificationWear,
            replyText: String,
            matchedRule: AutoReplyRule
        )
        fun onFallbackToLegacy(sbn: StatusBarNotification)
    }

    /**
     * Preferred callback receiving explicit `claimToken`, `eventKey`, and [RuleExecutionReservationCoordinator.RuleReservation]
     * directly without relying on global registry lookups.
     */
    abstract class ClaimAwareReplyDispatchCallback : ReplyDispatchCallback {
        abstract fun onRuleMatchedSendReply(
            sbn: StatusBarNotification,
            notificationWear: NotificationWear,
            replyText: String,
            matchedRule: AutoReplyRule,
            claimToken: String?,
            eventKey: String?,
            reservation: RuleExecutionReservationCoordinator.RuleReservation?
        )

        abstract fun onFallbackToLegacy(
            sbn: StatusBarNotification,
            claimToken: String?,
            eventKey: String?
        )

        final override fun onRuleMatchedSendReply(
            sbn: StatusBarNotification,
            notificationWear: NotificationWear,
            replyText: String,
            matchedRule: AutoReplyRule
        ) {
            onRuleMatchedSendReply(sbn, notificationWear, replyText, matchedRule, null, null, null)
        }

        final override fun onFallbackToLegacy(sbn: StatusBarNotification) {
            onFallbackToLegacy(sbn, null, null)
        }
    }

    private data class ActivePendingClaim(
        val eventKey: String,
        @Volatile var reservation: RuleExecutionReservationCoordinator.RuleReservation? = null
    )

    private val appContext = context.applicationContext
    private val shutDown = AtomicBoolean(false)
    private val activePendingClaims = ConcurrentHashMap<String, ActivePendingClaim>()
    private val inFlightEventKeys = ConcurrentHashMap<String, Boolean>()

    internal fun activeInFlightEventKeyCountForTesting(): Int = inFlightEventKeys.size

    @androidx.annotation.VisibleForTesting
    fun awaitIdleForTesting() {
        executor.submit { }.get(5, TimeUnit.SECONDS)
    }

    fun processNotification(
        sbn: StatusBarNotification?,
        callback: ReplyDispatchCallback
    ) {
        if (sbn == null || shutDown.get()) return

        val prefs = PreferencesManager.getPreferencesInstance(appContext)
        if (!prefs.isServiceEnabled) return

        val packageName = sbn.packageName ?: return
        if (packageName.equals(appContext.packageName, ignoreCase = true)) {
            return
        }

        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            return
        }
        if (sbn.isOngoing) {
            return
        }
        if (!NotificationUtils.isNewNotification(sbn)) {
            return
        }

        val notificationWear = NotificationUtils.extractWearNotification(sbn)
        if (notificationWear.remoteInputs.isEmpty() || notificationWear.pendingIntent == null) {
            return
        }

        val extras = notification.extras
        val messageText = NotificationEventKeyBuilder.extractIncomingMessage(sbn)
        val rawTextObj = extras?.get("android.text")
        val isGroup = extras?.getBoolean("android.isGroupConversation", false) ?: false
        val selfDisplayName = extras?.getString("android.selfDisplayName")
        val title = NotificationUtils.getTitle(sbn)

        if (!selfDisplayName.isNullOrBlank() && title != null &&
            title.equals(selfDisplayName, ignoreCase = true)
        ) {
            return
        }

        // Fixed image-group heuristic safety check for notifications missing android.isGroupConversation
        val rawTitle = NotificationUtils.getTitleRaw(sbn)
        if (!isGroup && NotificationUtils.isPossiblyImageGroupMessage(rawTitle, rawTextObj)) {
            return
        }

        val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn, messageText) ?: return
        val senderKey = NotificationEventKeyBuilder.normalizeSenderKey(title, isGroup)

        if (inFlightEventKeys.putIfAbsent(eventKey, java.lang.Boolean.TRUE) != null) {
            Log.d(TAG, "Concurrent in-flight notification suppressed before evaluation")
            return
        }
        if (shutDown.get()) {
            inFlightEventKeys.remove(eventKey)
            return
        }

        try {
            executor.execute {
                var acquiredClaimToken: String? = null
                var scheduledDelayed = false
                var reservation: RuleExecutionReservationCoordinator.RuleReservation? = null
                try {
                    if (shutDown.get()) {
                        return@execute
                    }

                    val db = AutoReplyRulesDatabase.getInstance(appContext)
                    val ruleDao = db.ruleDao()
                    val logDao = db.executionLogDao()

                    val totalRules = ruleDao.getTotalRuleCountSync()
                    if (totalRules == 0) {
                        dispatchLegacyFallback(callback, sbn, null, eventKey)
                        return@execute
                    }

                    val enabledRules = ruleDao.getEnabledRulesSync()
                    if (enabledRules.isEmpty()) {
                        if (prefs.isRuleFallbackToGlobalEnabled()) {
                            dispatchLegacyFallback(callback, sbn, null, eventKey)
                        }
                        return@execute
                    }

                    val logStore = RoomRuleExecutionLogStore(logDao)
                    val msgContext = IncomingMessageContext(
                        packageName = packageName,
                        senderName = title,
                        messageText = messageText,
                        isGroup = isGroup,
                        notificationKey = sbn.key ?: "",
                        messageTimestamp = notification.`when`,
                        selfDisplayName = selfDisplayName
                    )

                    val reservedMatch = reservationCoordinator.evaluateAndReserve(
                        rules = enabledRules,
                        context = msgContext,
                        logStore = logStore,
                        nowMillis = System.currentTimeMillis()
                    )
                    if (reservedMatch == null) {
                        if (prefs.isRuleFallbackToGlobalEnabled()) {
                            dispatchLegacyFallback(callback, sbn, null, eventKey)
                        }
                        return@execute
                    }

                    reservation = reservedMatch.reservation
                    val result = reservedMatch.evaluation
                    val finalReplyText = result.replyText
                    val delaySeconds = result.rule.replyDelaySeconds.coerceIn(0, MAX_DELAY_SECONDS)

                    val claimToken = UUID.randomUUID().toString()
                    val claimOutcome = try {
                        retryCoordinator.tryAcquireForFreshNotification(
                            dbUtils = dbUtils,
                            sbn = sbn,
                            notificationWear = notificationWear,
                            eventKey = eventKey,
                            claimToken = claimToken,
                            packageName = packageName,
                            senderKey = senderKey,
                            notificationKey = sbn.key,
                            nowMs = System.currentTimeMillis(),
                            isServiceDestroyed = shutDown.get()
                        )
                    } catch (e: Exception) {
                        reservationCoordinator.release(reservation.reservationId)
                        reservation = null
                        Log.e(TAG, "Failed acquiring persistent claim (${e.javaClass.simpleName})")
                        return@execute
                    }

                    if (!claimOutcome.acquired) {
                        reservationCoordinator.release(reservation.reservationId)
                        reservation = null
                        Log.d(TAG, "Duplicate or locked notification suppressed: decision=${claimOutcome.decision}")
                        return@execute
                    }

                    acquiredClaimToken = claimToken
                    if (shutDown.get()) {
                        reservationCoordinator.release(reservation.reservationId)
                        reservation = null
                        cancelClaimSafely(eventKey, claimToken)
                        return@execute
                    }

                    val pendingEntry = ActivePendingClaim(eventKey = eventKey, reservation = reservation)
                    activePendingClaims[claimToken] = pendingEntry
                    if (callback !is ClaimAwareReplyDispatchCallback) {
                        PendingClaimHandoff.register(eventKey, claimToken)
                    }

                    val capturedReservation = reservation
                    val dispatchAction = Runnable {
                        activePendingClaims.remove(claimToken)
                        if (shutDown.get()) {
                            PendingClaimHandoff.cancel(eventKey, claimToken)
                            reservationCoordinator.release(capturedReservation.reservationId)
                            cancelClaimSafely(eventKey, claimToken)
                            return@Runnable
                        }
                        try {
                            if (callback is ClaimAwareReplyDispatchCallback) {
                                PendingClaimHandoff.complete(eventKey, claimToken)
                                callback.onRuleMatchedSendReply(
                                    sbn,
                                    notificationWear,
                                    finalReplyText,
                                    result.rule,
                                    claimToken,
                                    eventKey,
                                    capturedReservation
                                )
                            } else {
                                callback.onRuleMatchedSendReply(sbn, notificationWear, finalReplyText, result.rule)
                            }
                        } catch (e: Exception) {
                            PendingClaimHandoff.cancel(eventKey, claimToken)
                            reservationCoordinator.release(capturedReservation.reservationId)
                            cancelClaimSafely(eventKey, claimToken)
                            Log.e(TAG, "Error dispatching rule reply (${e.javaClass.simpleName})")
                        }
                    }

                    if (delaySeconds > 0) {
                        scheduledDelayed = true
                        try {
                            executor.schedule(dispatchAction, delaySeconds.toLong(), TimeUnit.SECONDS)
                        } catch (scheduleEx: Exception) {
                            scheduledDelayed = false
                            activePendingClaims.remove(claimToken)
                            PendingClaimHandoff.cancel(eventKey, claimToken)
                            reservationCoordinator.release(capturedReservation.reservationId)
                            reservation = null
                            cancelClaimSafely(eventKey, claimToken)
                            Log.e(TAG, "Failed scheduling delayed rule dispatch (${scheduleEx.javaClass.simpleName})")
                        }
                    } else {
                        dispatchAction.run()
                    }
                } catch (e: Exception) {
                    val token = acquiredClaimToken
                    if (!scheduledDelayed && token != null) {
                        activePendingClaims.remove(token)
                        PendingClaimHandoff.cancel(eventKey, token)
                        releaseClaimSafely(eventKey, token)
                    }
                    reservationCoordinator.release(reservation?.reservationId)
                    Log.e(TAG, "Error evaluating auto-reply rules (${e.javaClass.simpleName})")
                } finally {
                    inFlightEventKeys.remove(eventKey)
                }
            }
        } catch (e: Exception) {
            inFlightEventKeys.remove(eventKey)
            Log.e(TAG, "Failed scheduling rule evaluation (${e.javaClass.simpleName})")
        }
    }

    private fun dispatchLegacyFallback(
        callback: ReplyDispatchCallback,
        sbn: StatusBarNotification,
        claimToken: String?,
        eventKey: String
    ) {
        if (callback is ClaimAwareReplyDispatchCallback) {
            if (claimToken != null) {
                PendingClaimHandoff.complete(eventKey, claimToken)
            }
            callback.onFallbackToLegacy(sbn, claimToken, eventKey)
        } else {
            callback.onFallbackToLegacy(sbn)
        }
    }

    private fun releaseClaimSafely(eventKey: String, claimToken: String) {
        try {
            dbUtils.releaseUnusedNotificationClaim(eventKey, claimToken)
        } catch (e: Exception) {
            Log.w(TAG, "Failed releasing unused claim (${e.javaClass.simpleName})")
        }
    }

    private fun cancelClaimSafely(eventKey: String, claimToken: String) {
        try {
            dbUtils.cancelPendingNotificationClaim(
                eventKey,
                claimToken,
                ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN,
                System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed cancelling pending claim (${e.javaClass.simpleName})")
        }
    }

    fun shutdown() {
        shutDown.set(true)
        inFlightEventKeys.clear()
        executor.shutdownNow()
        val snapshot = activePendingClaims.entries.toList()
        activePendingClaims.clear()
        for ((token, entry) in snapshot) {
            PendingClaimHandoff.cancel(entry.eventKey, token)
            reservationCoordinator.release(entry.reservation?.reservationId)
            cancelClaimSafely(entry.eventKey, token)
        }
        reservationCoordinator.releaseAllReserved()
    }

    companion object {
        private const val TAG = "RuleNotificationProc"
        private const val MAX_DELAY_SECONDS = 300
    }
}
