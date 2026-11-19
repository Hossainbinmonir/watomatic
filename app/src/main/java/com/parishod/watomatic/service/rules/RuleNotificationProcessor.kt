package com.parishod.watomatic.service.rules

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.text.SpannableString
import android.util.Log
import com.parishod.watomatic.NotificationWear
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.rules.AutoReplyRule
import com.parishod.watomatic.model.rules.AutoReplyRulesDatabase
import com.parishod.watomatic.model.utils.DbUtils
import com.parishod.watomatic.model.utils.NotificationUtils
import com.parishod.watomatic.service.reply.NotificationEventKeyBuilder
import com.parishod.watomatic.service.reply.PendingClaimHandoff
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
    private val dbUtils: DbUtils = DbUtils(context.applicationContext)
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

    private val appContext = context.applicationContext
    private val shutDown = AtomicBoolean(false)
    private val activePendingClaims = ConcurrentHashMap<String, String>()

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

        // Preserve existing image-group heuristic safety check
        val rawTitle = NotificationUtils.getTitleRaw(sbn)
        val rawText = SpannableString.valueOf("" + rawTextObj)
        val isPossiblyAnImageGrpMsg = ((rawTitle != null) && (": ".contains(rawTitle) || "@ ".contains(rawTitle))) &&
            rawText.toString().contains("\uD83D\uDCF7")
        if (!isGroup && isPossiblyAnImageGrpMsg) {
            return
        }

        val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn, messageText) ?: return
        val senderKey = NotificationEventKeyBuilder.normalizeSenderKey(title)
        val claimToken = UUID.randomUUID().toString()
        val claimOutcome = try {
            dbUtils.tryAcquireNotificationClaim(
                eventKey,
                claimToken,
                packageName,
                senderKey,
                sbn.key,
                System.currentTimeMillis(),
                false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed acquiring persistent claim (${e.javaClass.simpleName})")
            return
        }

        if (!claimOutcome.acquired) {
            Log.d(TAG, "Duplicate or locked notification suppressed: decision=${claimOutcome.decision}")
            return
        }

        if (shutDown.get()) {
            cancelClaimSafely(eventKey, claimToken)
            return
        }

        activePendingClaims[claimToken] = eventKey
        PendingClaimHandoff.register(eventKey, claimToken)

        try {
            executor.execute {
                if (shutDown.get()) {
                    activePendingClaims.remove(claimToken)
                    PendingClaimHandoff.consume(eventKey)
                    cancelClaimSafely(eventKey, claimToken)
                    return@execute
                }
                try {
                    val db = AutoReplyRulesDatabase.getInstance(appContext)
                    val ruleDao = db.ruleDao()
                    val logDao = db.executionLogDao()

                    val totalRules = ruleDao.getTotalRuleCountSync()
                    if (totalRules == 0) {
                        activePendingClaims.remove(claimToken)
                        callback.onFallbackToLegacy(sbn)
                        return@execute
                    }

                    val enabledRules = ruleDao.getEnabledRulesSync()
                    if (enabledRules.isEmpty()) {
                        activePendingClaims.remove(claimToken)
                        if (prefs.isRuleFallbackToGlobalEnabled()) {
                            callback.onFallbackToLegacy(sbn)
                        } else {
                            PendingClaimHandoff.consume(eventKey)
                            releaseClaimSafely(eventKey, claimToken)
                        }
                        return@execute
                    }

                    val logStore = RoomRuleExecutionLogStore(logDao)
                    val evaluator = RuleEvaluator(logStore)
                    val msgContext = IncomingMessageContext(
                        packageName = packageName,
                        senderName = title,
                        messageText = messageText,
                        isGroup = isGroup,
                        notificationKey = sbn.key ?: "",
                        messageTimestamp = notification.`when`,
                        selfDisplayName = selfDisplayName
                    )

                    val result = evaluator.evaluate(enabledRules, msgContext, System.currentTimeMillis())
                    if (result == null) {
                        activePendingClaims.remove(claimToken)
                        if (prefs.isRuleFallbackToGlobalEnabled()) {
                            callback.onFallbackToLegacy(sbn)
                        } else {
                            PendingClaimHandoff.consume(eventKey)
                            releaseClaimSafely(eventKey, claimToken)
                        }
                        return@execute
                    }

                    val finalReplyText = result.replyText
                    val delaySeconds = result.rule.replyDelaySeconds.coerceIn(0, MAX_DELAY_SECONDS)

                    val dispatchAction = Runnable {
                        activePendingClaims.remove(claimToken)
                        if (shutDown.get()) {
                            PendingClaimHandoff.consume(eventKey)
                            cancelClaimSafely(eventKey, claimToken)
                            return@Runnable
                        }
                        try {
                            callback.onRuleMatchedSendReply(sbn, notificationWear, finalReplyText, result.rule)
                        } catch (e: Exception) {
                            PendingClaimHandoff.consume(eventKey)
                            cancelClaimSafely(eventKey, claimToken)
                            Log.e(TAG, "Error dispatching rule reply (${e.javaClass.simpleName})")
                        }
                    }

                    if (delaySeconds > 0) {
                        executor.schedule(dispatchAction, delaySeconds.toLong(), TimeUnit.SECONDS)
                    } else {
                        dispatchAction.run()
                    }
                } catch (e: Exception) {
                    activePendingClaims.remove(claimToken)
                    PendingClaimHandoff.consume(eventKey)
                    releaseClaimSafely(eventKey, claimToken)
                    Log.e(TAG, "Error evaluating auto-reply rules (${e.javaClass.simpleName})")
                }
            }
        } catch (e: Exception) {
            activePendingClaims.remove(claimToken)
            PendingClaimHandoff.consume(eventKey)
            cancelClaimSafely(eventKey, claimToken)
            Log.e(TAG, "Failed scheduling rule evaluation (${e.javaClass.simpleName})")
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
        executor.shutdownNow()
        val snapshot = activePendingClaims.entries.toList()
        activePendingClaims.clear()
        for ((token, eventKey) in snapshot) {
            PendingClaimHandoff.consume(eventKey)
            cancelClaimSafely(eventKey, token)
        }
    }

    companion object {
        private const val TAG = "RuleNotificationProc"
        private const val MAX_DELAY_SECONDS = 300
    }
}
