package com.parishod.watomatic.model.ai

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.parishod.watomatic.service.ai.AiPromptAndPolicyHelper
import kotlinx.coroutines.flow.Flow

@Dao
interface AiMemoryDao {

    // ---- Reactive Flows for UI ----

    @Query("SELECT * FROM ai_notes ORDER BY is_completed ASC, created_at DESC, id DESC")
    fun observeAllNotes(): Flow<List<AiNote>>

    @Query("SELECT COUNT(*) FROM ai_notes WHERE is_completed = 0")
    fun observePendingNotesCount(): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM ai_conversation_messages " +
            "WHERE role = 'user' OR execution_status IN ('DISPATCHED', 'LEGACY')"
    )
    fun observeConversationMessageCount(): Flow<Int>

    // ---- Suspend Operations for ViewModels ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: AiNote): Long

    @Query("UPDATE ai_notes SET is_completed = :completed WHERE id = :noteId")
    suspend fun setNoteCompleted(noteId: Long, completed: Boolean)

    @Query("DELETE FROM ai_notes WHERE id = :noteId")
    suspend fun deleteNoteById(noteId: Long)

    @Query("DELETE FROM ai_notes WHERE is_completed = 1")
    suspend fun clearCompletedNotes()

    @Query("DELETE FROM ai_notes")
    suspend fun clearAllNotes()

    @Query("DELETE FROM ai_notes WHERE attempt_id IS NOT NULL")
    suspend fun clearAiGeneratedNotes()

    @Query("DELETE FROM ai_conversation_messages")
    suspend fun clearAllConversationMessages()

    @Transaction
    suspend fun clearConversationMemoryAndAiNotes() {
        clearAllConversationMessages()
        clearAiGeneratedNotes()
    }

    // ---- Background Service Synchronous Queries ----

    @Query(
        "SELECT * FROM ai_conversation_messages " +
            "WHERE package_name = :packageName AND sender_key = :senderKey " +
            "AND sender_key != '__unknown_sender__' AND TRIM(sender_key) != '' " +
            "AND (role = 'user' OR execution_status IN ('DISPATCHED', 'LEGACY')) " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit"
    )
    fun getRecentMessagesDescSync(
        packageName: String,
        senderKey: String,
        limit: Int
    ): List<AiConversationMessage>

    @Query("SELECT * FROM ai_conversation_messages ORDER BY timestamp ASC, id ASC")
    fun getAllConversationMessagesSync(): List<AiConversationMessage>

    @Query("SELECT * FROM ai_conversation_messages WHERE attempt_id = :attemptId AND role = 'assistant'")
    fun getAssistantMessagesByAttemptIdSync(attemptId: String): List<AiConversationMessage>

    @Query("SELECT COUNT(*) FROM ai_conversation_messages WHERE attempt_id = :attemptId AND role = 'assistant'")
    fun countAssistantMessagesByAttemptIdSync(attemptId: String): Int

    @Query("SELECT COUNT(*) FROM ai_conversation_messages WHERE incoming_event_key = :incomingEventKey")
    fun countMessagesByIncomingEventKeySync(incomingEventKey: String): Int

    @Query(
        "SELECT MAX(timestamp) FROM ai_conversation_messages " +
            "WHERE package_name = :packageName AND sender_key = :senderKey"
    )
    fun getMaxMessageTimestampSync(packageName: String, senderKey: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertConversationMessageSync(message: AiConversationMessage): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertConversationMessageIgnoreSync(message: AiConversationMessage): Long

    @Query(
        "DELETE FROM ai_conversation_messages " +
            "WHERE package_name = :packageName AND sender_key = :senderKey " +
            "AND id NOT IN (" +
            "  SELECT id FROM ai_conversation_messages " +
            "  WHERE package_name = :packageName AND sender_key = :senderKey " +
            "  AND (role = 'user' OR execution_status IN ('DISPATCHED', 'LEGACY')) " +
            "  ORDER BY timestamp DESC, id DESC LIMIT :keepCount" +
            ")"
    )
    fun trimConversationMessagesSync(
        packageName: String,
        senderKey: String,
        keepCount: Int
    )

    @Query(
        "SELECT COUNT(*) FROM ai_conversation_messages " +
            "WHERE role = 'user' OR execution_status IN ('DISPATCHED', 'LEGACY')"
    )
    fun getConversationMessageCountSync(): Int

    @Query("SELECT COUNT(*) FROM ai_notes WHERE is_completed = 0")
    fun getPendingNoteCountSync(): Int

    @Query("SELECT COUNT(*) FROM ai_notes")
    fun getTotalNoteCountSync(): Int

    @Query("SELECT COUNT(*) FROM ai_notes WHERE attempt_id IS NOT NULL")
    fun getAiGeneratedNoteCountSync(): Int

    @Query("SELECT * FROM ai_notes WHERE attempt_id = :attemptId ORDER BY created_at ASC, id ASC")
    fun getNotesByAttemptIdSync(attemptId: String): List<AiNote>

    @Query("SELECT COUNT(*) FROM ai_notes WHERE attempt_id = :attemptId")
    fun countNotesByAttemptIdSync(attemptId: String): Int

    @Query(
        "SELECT * FROM ai_notes " +
            "WHERE is_completed = 0 AND package_name = :packageName " +
            "AND LOWER(TRIM(sender_name)) = LOWER(TRIM(:senderName)) " +
            "ORDER BY created_at DESC LIMIT :limit"
    )
    fun getPendingNotesForSenderSync(
        packageName: String,
        senderName: String,
        limit: Int
    ): List<AiNote>

    @Query(
        "SELECT * FROM ai_notes " +
            "WHERE is_completed = 0 AND package_name = :packageName " +
            "AND sender_key = :senderKey " +
            "AND sender_key != '' AND sender_key != '__unknown_sender__' " +
            "ORDER BY created_at DESC LIMIT :limit"
    )
    fun getPendingNotesForSenderKeySync(
        packageName: String,
        senderKey: String,
        limit: Int
    ): List<AiNote>

    @Query(
        "SELECT COUNT(*) FROM ai_notes " +
            "WHERE is_completed = 0 AND package_name = :packageName " +
            "AND sender_key = :senderKey AND note_dedup_key = :noteDedupKey"
    )
    fun countPendingNotesByDedupKeySync(
        packageName: String,
        senderKey: String,
        noteDedupKey: String
    ): Int

    @Query("DELETE FROM ai_conversation_messages WHERE timestamp <= :cutoffMs")
    fun purgeExpiredConversationMessagesBeforeSync(cutoffMs: Long): Int

    @Query(
        "DELETE FROM ai_conversation_messages " +
            "WHERE timestamp <= :cutoffMs " +
            "AND (attempt_id IS NULL OR attempt_id NOT IN (:protectedAttemptIds))"
    )
    fun purgeExpiredConversationMessagesExcludingSync(
        cutoffMs: Long,
        protectedAttemptIds: List<String>
    ): Int

    @Query("DELETE FROM ai_notes WHERE created_at <= :cutoffMs")
    fun purgeExpiredNotesBeforeSync(cutoffMs: Long): Int

    @Query(
        "DELETE FROM ai_notes " +
            "WHERE created_at <= :cutoffMs " +
            "AND (attempt_id IS NULL OR attempt_id NOT IN (:protectedAttemptIds))"
    )
    fun purgeExpiredNotesExcludingSync(
        cutoffMs: Long,
        protectedAttemptIds: List<String>
    ): Int

    @Transaction
    fun purgeExpiredMemorySync(cutoffMs: Long, protectedAttemptIds: List<String>): Int {
        val deletedMsgs = if (protectedAttemptIds.isEmpty()) {
            purgeExpiredConversationMessagesBeforeSync(cutoffMs)
        } else {
            purgeExpiredConversationMessagesExcludingSync(cutoffMs, protectedAttemptIds)
        }
        val deletedNotes = if (protectedAttemptIds.isEmpty()) {
            purgeExpiredNotesBeforeSync(cutoffMs)
        } else {
            purgeExpiredNotesExcludingSync(cutoffMs, protectedAttemptIds)
        }
        return deletedMsgs + deletedNotes
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertNoteSync(note: AiNote): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertNoteIgnoreSync(note: AiNote): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertReplyLogSync(log: AiReplyLog): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertReplyLogIgnoreSync(log: AiReplyLog): Long

    @Query("SELECT COUNT(*) FROM ai_reply_logs WHERE attempt_id = :attemptId")
    fun countReplyLogsByAttemptIdSync(attemptId: String): Int

    @Query("SELECT * FROM ai_reply_logs WHERE attempt_id = :attemptId")
    fun getReplyLogsByAttemptIdSync(attemptId: String): List<AiReplyLog>

    @Query(
        "SELECT MAX(replied_at) FROM ai_reply_logs " +
            "WHERE package_name = :packageName AND sender_key = :senderKey"
    )
    fun getLastAiReplyTimeSync(
        packageName: String,
        senderKey: String
    ): Long?

    @Query(
        "SELECT COUNT(*) FROM ai_reply_logs " +
            "WHERE package_name = :packageName AND sender_key = :senderKey AND replied_at >= :sinceTime"
    )
    fun getAiReplyCountSinceSync(
        packageName: String,
        senderKey: String,
        sinceTime: Long
    ): Int

    @Transaction
    fun recordIncomingUserMessageSync(
        packageName: String,
        senderKey: String,
        senderDisplay: String,
        incomingMessage: String,
        incomingEventKey: String?,
        timestampMs: Long,
        keepCount: Int,
        legacyIncomingEventKey: String? = null
    ): Boolean {
        if (AiPromptAndPolicyHelper.isUnknownSender(senderKey)) return false
        val cleanIncoming = incomingMessage.trim()
        if (cleanIncoming.isEmpty()) return false
        if (!incomingEventKey.isNullOrBlank() && countMessagesByIncomingEventKeySync(incomingEventKey) > 0) {
            return false
        }
        if (!legacyIncomingEventKey.isNullOrBlank() && countMessagesByIncomingEventKeySync(legacyIncomingEventKey) > 0) {
            return false
        }
        val rowId = insertConversationMessageIgnoreSync(
            AiConversationMessage(
                packageName = packageName,
                senderKey = senderKey,
                senderDisplayName = senderDisplay,
                role = "user",
                content = cleanIncoming,
                timestamp = timestampMs,
                attemptId = null,
                incomingEventKey = incomingEventKey?.takeIf { it.isNotBlank() },
                executionStatus = AiConversationMessage.STATUS_USER_RECEIVED
            )
        )
        if (rowId > 0L && keepCount > 0) {
            trimConversationMessagesSync(packageName, senderKey, keepCount)
        }
        return rowId > 0L
    }

    @Transaction
    fun persistConfirmedAiReplySync(
        attemptId: String,
        packageName: String,
        senderKey: String,
        senderDisplay: String,
        incomingMessage: String,
        incomingEventKey: String?,
        assistantReply: String,
        sanitizedNotes: List<String>,
        memoryEnabled: Boolean,
        notesEnabled: Boolean,
        keepCount: Int,
        timestampMs: Long,
        legacyIncomingEventKey: String? = null,
        recordReplyLog: Boolean = true
    ): Boolean {
        if (attemptId.isBlank()) return false

        var insertedAny = false

        if (recordReplyLog && countReplyLogsByAttemptIdSync(attemptId) == 0) {
            val logRowId = insertReplyLogIgnoreSync(
                AiReplyLog(
                    packageName = packageName,
                    senderKey = senderKey,
                    repliedAt = timestampMs,
                    attemptId = attemptId
                )
            )
            if (logRowId > 0L) insertedAny = true
        }

        val alreadyHasAssistant = countAssistantMessagesByAttemptIdSync(attemptId) > 0
        val effectiveMemoryEnabled = memoryEnabled && !AiPromptAndPolicyHelper.isUnknownSender(senderKey)

        if (effectiveMemoryEnabled) {
            val cleanIncoming = incomingMessage.trim()
            if (cleanIncoming.isNotEmpty()) {
                val hasMatchingEventKey = (!incomingEventKey.isNullOrBlank() && countMessagesByIncomingEventKeySync(incomingEventKey) > 0) ||
                    (!legacyIncomingEventKey.isNullOrBlank() && countMessagesByIncomingEventKeySync(legacyIncomingEventKey) > 0)
                val shouldInsertIncoming = if (!incomingEventKey.isNullOrBlank() || !legacyIncomingEventKey.isNullOrBlank()) {
                    !hasMatchingEventKey
                } else {
                    !alreadyHasAssistant
                }
                if (shouldInsertIncoming) {
                    val userRowId = insertConversationMessageIgnoreSync(
                        AiConversationMessage(
                            packageName = packageName,
                            senderKey = senderKey,
                            senderDisplayName = senderDisplay,
                            role = "user",
                            content = cleanIncoming,
                            timestamp = timestampMs,
                            attemptId = null,
                            incomingEventKey = incomingEventKey?.takeIf { it.isNotBlank() },
                            executionStatus = AiConversationMessage.STATUS_USER_RECEIVED
                        )
                    )
                    if (userRowId > 0L) insertedAny = true
                }
            }

            if (!alreadyHasAssistant) {
                val cleanReply = assistantReply.trim()
                if (cleanReply.isNotEmpty()) {
                    val maxExistingTs = getMaxMessageTimestampSync(packageName, senderKey) ?: 0L
                    val effectiveAssistantTs = maxOf(timestampMs + 1L, maxExistingTs + 1L)
                    val assistantRowId = insertConversationMessageIgnoreSync(
                        AiConversationMessage(
                            packageName = packageName,
                            senderKey = senderKey,
                            senderDisplayName = senderDisplay,
                            role = "assistant",
                            content = cleanReply,
                            timestamp = effectiveAssistantTs,
                            attemptId = attemptId,
                            incomingEventKey = null,
                            executionStatus = AiConversationMessage.STATUS_DISPATCHED
                        )
                    )
                    if (assistantRowId > 0L) insertedAny = true
                }
            }

            if (keepCount > 0) {
                trimConversationMessagesSync(packageName, senderKey, keepCount)
            }
        }

        if (notesEnabled && sanitizedNotes.isNotEmpty() && countNotesByAttemptIdSync(attemptId) == 0) {
            val seenKeys = HashSet<String>()
            sanitizedNotes.forEachIndexed { index, noteText ->
                val dedupKey = AiPromptAndPolicyHelper.computeNoteDedupKey(noteText)
                val alreadyPendingSameSender = !AiPromptAndPolicyHelper.isUnknownSender(senderKey) &&
                    countPendingNotesByDedupKeySync(packageName, senderKey, dedupKey) > 0
                if (seenKeys.add(dedupKey) && !alreadyPendingSameSender) {
                    val noteRowId = insertNoteIgnoreSync(
                        AiNote(
                            senderName = senderDisplay,
                            packageName = packageName,
                            noteText = noteText,
                            sourceMessage = incomingMessage,
                            createdAt = timestampMs + index,
                            isCompleted = false,
                            attemptId = attemptId,
                            noteDedupKey = dedupKey,
                            senderKey = senderKey
                        )
                    )
                    if (noteRowId > 0L) insertedAny = true
                }
            }
        }

        return insertedAny
    }
}
