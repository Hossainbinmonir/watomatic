package com.parishod.watomatic.model.logs

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.parishod.watomatic.service.reply.ReplyExecutionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ReplyAttemptDao {

    @Query("SELECT * FROM reply_attempts WHERE attempt_id = :attemptId LIMIT 1")
    fun getAttemptByIdSync(attemptId: String): ReplyAttemptEntity?

    @Query("SELECT * FROM reply_attempts ORDER BY updated_at_ms DESC")
    fun getAllAttemptsSync(): List<ReplyAttemptEntity>

    @Query("SELECT * FROM reply_attempts WHERE status IN ('GENERATED', 'DISPATCHING') ORDER BY created_at_ms ASC")
    fun getUnresolvedAttemptsSync(): List<ReplyAttemptEntity>

    @Query("SELECT attempt_id FROM reply_attempts WHERE status IN ('GENERATED', 'DISPATCHING', 'UNKNOWN')")
    fun getProtectedAttemptIdsSync(): List<String>

    @Query("SELECT * FROM reply_attempts WHERE event_key = :eventKey ORDER BY created_at_ms DESC")
    fun getAttemptsByEventKeySync(eventKey: String): List<ReplyAttemptEntity>

    @Query("SELECT COUNT(attempt_id) FROM reply_attempts WHERE status = :status")
    fun countAttemptsByStatusSync(status: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertRawSync(entity: ReplyAttemptEntity)

    @Transaction
    fun upsertAttemptSafely(entity: ReplyAttemptEntity) {
        val existing = getAttemptByIdSync(entity.attemptId)
        if (existing != null) {
            // Once an attempt reaches a terminal state (DISPATCHED, FAILED, CANCELLED, UNKNOWN),
            // it is immutable and cannot be overwritten by stale, out-of-order, or conflicting updates.
            if (isTerminalStatus(existing.status)) {
                return
            }
            // Never regress an in-progress DISPATCHING attempt back to GENERATED.
            if (existing.status == ReplyExecutionStatus.DISPATCHING.name &&
                entity.status == ReplyExecutionStatus.GENERATED.name
            ) {
                return
            }
            val merged = entity.copy(
                createdAtMs = minOf(existing.createdAtMs, entity.createdAtMs),
                dispatchAttempted = existing.dispatchAttempted || entity.dispatchAttempted,
                senderName = entity.senderName ?: existing.senderName,
                incomingMessage = entity.incomingMessage ?: existing.incomingMessage,
                failureCategory = entity.failureCategory ?: existing.failureCategory,
                eventKey = entity.eventKey ?: existing.eventKey
            )
            upsertRawSync(merged)
        } else {
            upsertRawSync(entity)
        }
    }

    @Query(
        "DELETE FROM reply_attempts " +
            "WHERE updated_at_ms <= :cutoffMs " +
            "AND status IN ('DISPATCHED', 'FAILED', 'CANCELLED')"
    )
    fun purgeResolvedAttemptsBeforeSync(cutoffMs: Long): Int

    @Query("DELETE FROM reply_attempts WHERE attempt_id = :attemptId")
    fun deleteByAttemptIdSync(attemptId: String)

    @Query("DELETE FROM reply_attempts")
    fun clearAllAttemptsSync()

    @Query("SELECT COUNT(attempt_id) FROM reply_attempts")
    fun getTotalAttemptCountSync(): Int

    @Query("SELECT COUNT(attempt_id) FROM reply_attempts")
    fun observeTotalAttemptCount(): Flow<Int>

    companion object {
        private fun isTerminalStatus(status: String): Boolean =
            status == ReplyExecutionStatus.DISPATCHED.name ||
                status == ReplyExecutionStatus.FAILED.name ||
                status == ReplyExecutionStatus.CANCELLED.name ||
                status == ReplyExecutionStatus.UNKNOWN.name
    }
}
