package com.parishod.watomatic.service.rules

import com.parishod.watomatic.model.rules.AutoReplyRule
import com.parishod.watomatic.model.rules.ContactFilterMode
import com.parishod.watomatic.model.rules.GroupFilterMode
import java.util.TimeZone

data class IncomingMessageContext(
    val packageName: String,
    val senderName: String?,
    val messageText: String?,
    val isGroup: Boolean,
    val notificationKey: String = "",
    val messageTimestamp: Long = 0L,
    val selfDisplayName: String? = null
)

data class RuleEvaluationResult(
    val rule: AutoReplyRule,
    val replyText: String
)

class RuleEvaluator(
    private val executionLogStore: RuleExecutionLogStore,
    private val timeZone: TimeZone = TimeZone.getDefault()
) {

    fun evaluate(
        rules: List<AutoReplyRule>,
        context: IncomingMessageContext,
        nowMillis: Long = System.currentTimeMillis()
    ): RuleEvaluationResult? {
        if (context.packageName.isBlank()) return null
        val sender = context.senderName?.trim().orEmpty()
        if (!context.selfDisplayName.isNullOrBlank() &&
            sender.equals(context.selfDisplayName.trim(), ignoreCase = true)
        ) {
            return null
        }

        if (context.notificationKey.isNotBlank() && context.messageTimestamp > 0L &&
            executionLogStore.hasRepliedToNotification(context.notificationKey, context.messageTimestamp)
        ) {
            return null
        }

        val sortedEnabledRules = rules
            .filter { it.isEnabled }
            .sortedWith(
                compareByDescending<AutoReplyRule> { it.priority }
                    .thenBy { it.id }
            )

        for (rule in sortedEnabledRules) {
            if (!matchesApplication(rule, context.packageName)) continue
            if (!matchesGroupFilter(rule, context.isGroup)) continue
            if (!matchesContactFilter(rule, sender, context.isGroup)) continue
            if (!RuleScheduleEvaluator.isWithinSchedule(rule, nowMillis, timeZone)) continue
            if (!RuleMatchingEngine.matches(
                    incomingMessage = context.messageText,
                    matchType = rule.matchType,
                    keywords = rule.keywords,
                    caseSensitive = rule.isCaseSensitive
                )
            ) continue
            if (!passesCooldown(rule, context.packageName, sender, nowMillis)) continue
            if (!passesFrequencyLimit(rule, context.packageName, sender, nowMillis)) continue
            if (rule.replyMessage.isBlank()) continue

            return RuleEvaluationResult(rule = rule, replyText = rule.replyMessage)
        }

        return null
    }

    internal fun matchesApplication(rule: AutoReplyRule, packageName: String): Boolean {
        if (rule.selectedPackages.isEmpty()) return false
        return rule.selectedPackages.any { it.equals(packageName, ignoreCase = true) }
    }

    internal fun matchesGroupFilter(rule: AutoReplyRule, isGroup: Boolean): Boolean {
        return when (rule.groupFilterMode) {
            GroupFilterMode.BOTH -> true
            GroupFilterMode.DIRECT_ONLY -> !isGroup
            GroupFilterMode.GROUP_ONLY -> isGroup
        }
    }

    internal fun matchesContactFilter(
        rule: AutoReplyRule,
        senderName: String,
        isGroup: Boolean
    ): Boolean {
        val cleanContacts = rule.filterContacts.map { it.trim() }.filter { it.isNotEmpty() }
        return when (rule.contactFilterMode) {
            ContactFilterMode.ALL -> true
            ContactFilterMode.INCLUDE_ONLY -> {
                if (senderName.isEmpty()) return false
                cleanContacts.any { it.equals(senderName, ignoreCase = true) }
            }
            ContactFilterMode.EXCLUDE -> {
                if (senderName.isEmpty()) return true
                cleanContacts.none { it.equals(senderName, ignoreCase = true) }
            }
        }
    }

    internal fun passesCooldown(
        rule: AutoReplyRule,
        packageName: String,
        senderName: String,
        nowMillis: Long
    ): Boolean {
        if (rule.cooldownSeconds <= 0) return true
        val senderKey = senderName.ifEmpty { UNKNOWN_SENDER }
        val lastReplied = executionLogStore.getLastReplyTimeForRuleAndSender(
            ruleId = rule.id,
            packageName = packageName,
            senderName = senderKey
        ) ?: return true
        return (nowMillis - lastReplied) >= rule.cooldownSeconds * 1000L
    }

    internal fun passesFrequencyLimit(
        rule: AutoReplyRule,
        packageName: String,
        senderName: String,
        nowMillis: Long
    ): Boolean {
        if (rule.maxRepliesPerSender <= 0) return true
        val senderKey = senderName.ifEmpty { UNKNOWN_SENDER }
        val sinceTime = if (rule.frequencyWindowMinutes > 0) {
            nowMillis - rule.frequencyWindowMinutes * 60_000L
        } else {
            0L
        }
        val count = executionLogStore.getReplyCountForRuleAndSenderSince(
            ruleId = rule.id,
            packageName = packageName,
            senderName = senderKey,
            sinceTime = sinceTime
        )
        return count < rule.maxRepliesPerSender
    }

    companion object {
        const val UNKNOWN_SENDER = "__unknown_sender__"
    }
}
