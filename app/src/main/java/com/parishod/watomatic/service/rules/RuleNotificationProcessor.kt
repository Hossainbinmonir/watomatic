package com.parishod.watomatic.service.rules

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.text.SpannableString
import android.util.Log
import com.parishod.watomatic.NotificationWear
import com.parishod.watomatic.R
import com.parishod.watomatic.model.CustomRepliesData
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.rules.AutoReplyRule
import com.parishod.watomatic.model.rules.AutoReplyRulesDatabase
import com.parishod.watomatic.model.utils.NotificationUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class RuleNotificationProcessor @JvmOverloads constructor(
    private val context: Context,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
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
    private val recentSignatures = ConcurrentHashMap<String, Long>()

    fun processNotification(
        sbn: StatusBarNotification?,
        callback: ReplyDispatchCallback
    ) {
        if (sbn == null) return

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
        val rawTextObj = extras?.get("android.text")
        val messageText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: rawTextObj?.toString()
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

        val signature = buildSignature(sbn, title, messageText)
        val now = System.currentTimeMillis()
        cleanupExpiredSignatures(now)
        val previousSeen = recentSignatures.putIfAbsent(signature, now)
        if (previousSeen != null && (now - previousSeen) < DUPLICATE_WINDOW_MS) {
            Log.d(TAG, "Duplicate notification suppressed for signature: $signature")
            return
        }
        recentSignatures[signature] = now

        executor.execute {
            try {
                val db = AutoReplyRulesDatabase.getInstance(appContext)
                val ruleDao = db.ruleDao()
                val logDao = db.executionLogDao()

                val totalRules = ruleDao.getTotalRuleCountSync()
                if (totalRules == 0) {
                    callback.onFallbackToLegacy(sbn)
                    return@execute
                }

                val enabledRules = ruleDao.getEnabledRulesSync()
                if (enabledRules.isEmpty()) {
                    if (prefs.isRuleFallbackToGlobalEnabled()) {
                        callback.onFallbackToLegacy(sbn)
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
                    if (prefs.isRuleFallbackToGlobalEnabled()) {
                        callback.onFallbackToLegacy(sbn)
                    }
                    return@execute
                }

                val finalReplyText = formatReplyWithAttribution(result.replyText, prefs)
                val delaySeconds = result.rule.replyDelaySeconds.coerceIn(0, MAX_DELAY_SECONDS)

                val dispatchAction = Runnable {
                    try {
                        val senderKey = title?.trim().orEmpty().ifEmpty { RuleEvaluator.UNKNOWN_SENDER }
                        logStore.recordExecution(
                            ruleId = result.rule.id,
                            packageName = packageName,
                            senderName = senderKey,
                            notificationKey = sbn.key ?: "",
                            messageTimestamp = notification.`when`,
                            repliedAt = System.currentTimeMillis()
                        )
                        callback.onRuleMatchedSendReply(sbn, notificationWear, finalReplyText, result.rule)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error dispatching rule reply", e)
                    }
                }

                if (delaySeconds > 0) {
                    executor.schedule(dispatchAction, delaySeconds.toLong(), TimeUnit.SECONDS)
                } else {
                    dispatchAction.run()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error evaluating auto-reply rules", e)
            }
        }
    }

    private fun formatReplyWithAttribution(
        replyText: String,
        prefs: PreferencesManager
    ): String {
        if (!prefs.isAppendWatomaticAttributionEnabled) return replyText
        val attribution = try {
            appContext.getString(R.string.sent_using_Watomatic)
        } catch (_: Exception) {
            "Sent using Watomatic"
        }
        return "$replyText\n\n${CustomRepliesData.RTL_ALIGN_INVISIBLE_CHAR}$attribution"
    }

    private fun buildSignature(
        sbn: StatusBarNotification,
        title: String?,
        messageText: String?
    ): String {
        val key = sbn.key ?: sbn.packageName
        val whenTime = sbn.notification?.`when` ?: 0L
        return "$key|$whenTime|${title.orEmpty()}|${messageText.orEmpty()}"
    }

    private fun cleanupExpiredSignatures(now: Long) {
        if (recentSignatures.size > MAX_SIGNATURE_CACHE_SIZE) {
            recentSignatures.entries.removeIf { (_, ts) -> (now - ts) > DUPLICATE_WINDOW_MS }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
        recentSignatures.clear()
    }

    companion object {
        private const val TAG = "RuleNotificationProc"
        private const val DUPLICATE_WINDOW_MS = 15_000L
        private const val MAX_SIGNATURE_CACHE_SIZE = 200
        private const val MAX_DELAY_SECONDS = 300
    }
}
