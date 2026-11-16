package com.parishod.watomatic.model.rules

import kotlinx.coroutines.flow.Flow

class RuleRepository(
    private val ruleDao: AutoReplyRuleDao,
    private val logDao: RuleExecutionLogDao? = null
) {
    val allRules: Flow<List<AutoReplyRule>> = ruleDao.observeAllRules()

    suspend fun getAllRulesOnce(): List<AutoReplyRule> = ruleDao.getAllRulesOnce()

    suspend fun getRuleById(id: Long): AutoReplyRule? = ruleDao.getRuleById(id)

    suspend fun insertRule(rule: AutoReplyRule): Long {
        val now = System.currentTimeMillis()
        return ruleDao.insertRule(rule.copy(createdAt = now, updatedAt = now))
    }

    suspend fun updateRule(rule: AutoReplyRule) {
        ruleDao.updateRule(rule.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteRule(rule: AutoReplyRule) {
        ruleDao.deleteRule(rule)
    }

    suspend fun deleteRuleById(id: Long) {
        ruleDao.deleteRuleById(id)
    }

    suspend fun duplicateRule(rule: AutoReplyRule, copySuffix: String = " (Copy)"): Long {
        val now = System.currentTimeMillis()
        val copy = rule.copy(
            id = 0L,
            name = rule.name + copySuffix,
            createdAt = now,
            updatedAt = now
        )
        return ruleDao.insertRule(copy)
    }

    suspend fun setRuleEnabled(ruleId: Long, enabled: Boolean) {
        ruleDao.setRuleEnabled(ruleId, enabled, System.currentTimeMillis())
    }

    suspend fun setCategoryEnabled(category: String, enabled: Boolean) {
        ruleDao.setCategoryEnabled(category.trim(), enabled, System.currentTimeMillis())
    }

    suspend fun updateRulePriority(ruleId: Long, priority: Int) {
        ruleDao.updateRulePriority(ruleId, priority.coerceIn(0, 100), System.currentTimeMillis())
    }

    fun getEnabledRulesSync(): List<AutoReplyRule> = ruleDao.getEnabledRulesSync()

    fun getTotalRuleCountSync(): Int = ruleDao.getTotalRuleCountSync()

    fun recordRuleExecution(
        ruleId: Long,
        packageName: String,
        senderName: String,
        notificationKey: String,
        messageTimestamp: Long,
        repliedAt: Long = System.currentTimeMillis()
    ) {
        logDao?.insertLogSync(
            RuleExecutionLog(
                ruleId = ruleId,
                packageName = packageName,
                senderName = senderName,
                notificationKey = notificationKey,
                messageTimestamp = messageTimestamp,
                repliedAt = repliedAt
            )
        )
    }
}
