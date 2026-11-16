package com.parishod.watomatic.model.rules

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AutoReplyRuleDao {
    @Query("SELECT * FROM auto_reply_rules ORDER BY priority DESC, updated_at DESC, id ASC")
    fun observeAllRules(): Flow<List<AutoReplyRule>>

    @Query("SELECT * FROM auto_reply_rules ORDER BY priority DESC, updated_at DESC, id ASC")
    suspend fun getAllRulesOnce(): List<AutoReplyRule>

    @Query("SELECT * FROM auto_reply_rules WHERE is_enabled = 1 ORDER BY priority DESC, id ASC")
    fun getEnabledRulesSync(): List<AutoReplyRule>

    @Query("SELECT COUNT(*) FROM auto_reply_rules")
    fun getTotalRuleCountSync(): Int

    @Query("SELECT COUNT(*) FROM auto_reply_rules WHERE is_enabled = 1")
    fun getEnabledRuleCountSync(): Int

    @Query("SELECT COUNT(*) FROM auto_reply_rules")
    suspend fun getTotalRuleCount(): Int

    @Query("SELECT * FROM auto_reply_rules WHERE id = :id LIMIT 1")
    suspend fun getRuleById(id: Long): AutoReplyRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: AutoReplyRule): Long

    @Update
    suspend fun updateRule(rule: AutoReplyRule)

    @Delete
    suspend fun deleteRule(rule: AutoReplyRule)

    @Query("DELETE FROM auto_reply_rules WHERE id = :id")
    suspend fun deleteRuleById(id: Long)

    @Query("UPDATE auto_reply_rules SET is_enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setRuleEnabled(id: Long, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE auto_reply_rules SET is_enabled = :enabled, updated_at = :updatedAt WHERE LOWER(TRIM(category)) = LOWER(TRIM(:category))")
    suspend fun setCategoryEnabled(category: String, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE auto_reply_rules SET priority = :priority, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateRulePriority(id: Long, priority: Int, updatedAt: Long = System.currentTimeMillis())
}
