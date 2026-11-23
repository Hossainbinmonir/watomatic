package com.parishod.watomatic.model.ai

import android.content.Context
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.utils.DbUtils
import com.parishod.watomatic.service.ai.AiPromptAndPolicyHelper
import com.parishod.watomatic.service.reply.ReplyExecutionResult
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

open class AiMemoryRepository(private val dao: AiMemoryDao) {
    val allNotes: Flow<List<AiNote>> = dao.observeAllNotes()
    val pendingNotesCount: Flow<Int> = dao.observePendingNotesCount()
    val conversationMessageCount: Flow<Int> = dao.observeConversationMessageCount()

    private val attemptWriteLocks = ConcurrentHashMap<String, Any>()
    private val incomingWriteLocks = ConcurrentHashMap<String, Any>()

    private data class PersistCallOptions(
        val legacyIncomingEventKey: String?,
        val recordReplyLog: Boolean
    )

    private val currentPersistOptions = ThreadLocal<PersistCallOptions>()

    suspend fun insertNote(note: AiNote): Long = dao.insertNote(note)

    suspend fun setNoteCompleted(noteId: Long, completed: Boolean) {
        dao.setNoteCompleted(noteId, completed)
    }

    suspend fun deleteNoteById(noteId: Long) {
        dao.deleteNoteById(noteId)
    }

    suspend fun clearCompletedNotes() {
        dao.clearCompletedNotes()
    }

    suspend fun clearAllNotes() {
        dao.clearAllNotes()
    }

    suspend fun clearAllConversationMemory() {
        dao.clearAllConversationMessages()
    }

    suspend fun clearConversationMemoryAndAiNotes() {
        dao.clearConversationMemoryAndAiNotes()
    }

    open fun purgeExpiredMemory(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val appCtx = context.applicationContext
        val cutoffMs = nowMs - DbUtils.HISTORY_RETENTION_MS
        val protectedIds = LinkedHashSet<String>()
        try {
            val protectedAttemptIds = MessageLogsDB.getInstance(appCtx)
                .replyAttemptDao()
                .getProtectedAttemptIdsSync()
            for (attemptId in protectedAttemptIds) {
                if (attemptId.isNotBlank()) {
                    protectedIds.add(attemptId)
                }
            }
        } catch (_: Exception) {
        }
        protectedIds.addAll(PendingPostDispatchMemoryStore.getPendingAttemptIds(appCtx))
        return dao.purgeExpiredMemorySync(cutoffMs, protectedIds.toList())
    }

    @JvmOverloads
    open fun recordIncomingUserMessage(
        packageName: String,
        senderKey: String,
        senderDisplay: String,
        incomingMessage: String,
        incomingEventKey: String?,
        keepCount: Int,
        timestampMs: Long = System.currentTimeMillis(),
        legacyIncomingEventKey: String? = null
    ): Boolean {
        if (AiPromptAndPolicyHelper.isUnknownSender(senderKey)) {
            return false
        }
        val lockKey = incomingEventKey?.takeIf { it.isNotBlank() } ?: "$packageName|$senderKey"
        val lock = incomingWriteLocks.computeIfAbsent(lockKey) { Any() }
        synchronized(lock) {
            return dao.recordIncomingUserMessageSync(
                packageName = packageName,
                senderKey = senderKey,
                senderDisplay = senderDisplay,
                incomingMessage = incomingMessage,
                incomingEventKey = incomingEventKey,
                timestampMs = timestampMs,
                keepCount = keepCount,
                legacyIncomingEventKey = legacyIncomingEventKey
            )
        }
    }

    /**
     * Persists confirmed assistant memory, reply log, and extracted AI notes ONLY when
     * [result] represents [com.parishod.watomatic.service.reply.ReplyExecutionStatus.DISPATCHED].
     *
     * For non-dispatched outcomes (GENERATED, DISPATCHING, FAILED, CANCELLED, UNKNOWN),
     * assistant replies, AI notes, and AI reply cooldown logs are never persisted; if
     * [memoryEnabled] is true for an identifiable sender, the incoming user message is still
     * preserved idempotently.
     */
    open fun persistAuthoritativeAiReplyOutcome(
        result: ReplyExecutionResult,
        packageName: String,
        senderKey: String,
        senderDisplay: String,
        incomingMessage: String,
        incomingEventKey: String?,
        assistantReply: String,
        extractedNotes: List<String>,
        memoryEnabled: Boolean,
        notesEnabled: Boolean,
        keepCount: Int,
        timestampMs: Long = System.currentTimeMillis()
    ): Boolean {
        val options = currentPersistOptions.get()
        val legacyEventKey = options?.legacyIncomingEventKey
        val recordReplyLog = options?.recordReplyLog ?: true

        if (!result.isDispatched()) {
            if (memoryEnabled &&
                !AiPromptAndPolicyHelper.isUnknownSender(senderKey) &&
                incomingMessage.isNotBlank()
            ) {
                recordIncomingUserMessage(
                    packageName = packageName,
                    senderKey = senderKey,
                    senderDisplay = senderDisplay,
                    incomingMessage = incomingMessage,
                    incomingEventKey = incomingEventKey,
                    keepCount = keepCount,
                    timestampMs = timestampMs,
                    legacyIncomingEventKey = legacyEventKey
                )
            }
            return false
        }

        val attemptId = result.executionId
        if (attemptId.isBlank()) {
            return false
        }

        val sanitizedNotes = if (notesEnabled && extractedNotes.isNotEmpty()) {
            extractedNotes.mapNotNull { AiPromptAndPolicyHelper.sanitizeExtractedNote(it) }.distinct()
        } else {
            emptyList()
        }

        val lock = attemptWriteLocks.computeIfAbsent(attemptId) { Any() }
        synchronized(lock) {
            return dao.persistConfirmedAiReplySync(
                attemptId = attemptId,
                packageName = packageName,
                senderKey = senderKey,
                senderDisplay = senderDisplay,
                incomingMessage = incomingMessage,
                incomingEventKey = incomingEventKey,
                assistantReply = assistantReply,
                sanitizedNotes = sanitizedNotes,
                memoryEnabled = memoryEnabled,
                notesEnabled = notesEnabled,
                keepCount = keepCount,
                timestampMs = timestampMs,
                legacyIncomingEventKey = legacyEventKey,
                recordReplyLog = recordReplyLog
            )
        }
    }

    fun persistAuthoritativeAiReplyOutcome(
        result: ReplyExecutionResult,
        packageName: String,
        senderKey: String,
        senderDisplay: String,
        incomingMessage: String,
        incomingEventKey: String?,
        assistantReply: String,
        extractedNotes: List<String>,
        memoryEnabled: Boolean,
        notesEnabled: Boolean,
        keepCount: Int,
        timestampMs: Long,
        legacyIncomingEventKey: String?,
        recordReplyLog: Boolean
    ): Boolean {
        currentPersistOptions.set(
            PersistCallOptions(
                legacyIncomingEventKey = legacyIncomingEventKey,
                recordReplyLog = recordReplyLog
            )
        )
        return try {
            persistAuthoritativeAiReplyOutcome(
                result = result,
                packageName = packageName,
                senderKey = senderKey,
                senderDisplay = senderDisplay,
                incomingMessage = incomingMessage,
                incomingEventKey = incomingEventKey,
                assistantReply = assistantReply,
                extractedNotes = extractedNotes,
                memoryEnabled = memoryEnabled,
                notesEnabled = notesEnabled,
                keepCount = keepCount,
                timestampMs = timestampMs
            )
        } finally {
            currentPersistOptions.remove()
        }
    }
}
