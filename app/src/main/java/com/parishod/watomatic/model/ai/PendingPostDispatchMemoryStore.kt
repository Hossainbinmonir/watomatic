package com.parishod.watomatic.model.ai

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.parishod.watomatic.model.utils.DbUtils
import com.parishod.watomatic.service.reply.ReplyExecutionResult
import com.parishod.watomatic.service.reply.ReplyExecutionStatus

/**
 * Durable staging queue for accepted post-dispatch AI memory/note/log persistence tasks.
 *
 * When a reply has been dispatched (or completed dispatch attempt), the persistence payload is
 * staged here before the Room write and removed only once Room persistence completes (or is
 * confirmed non-dispatched). If the process dies or SQLite fails transiently, startup recovery
 * replays pending entries idempotently without ever running heavy SQLite on the main thread.
 */
object PendingPostDispatchMemoryStore {
    private const val TAG = "PendingAiMemoryStore"
    private const val PREFS_NAME = "watomatic_pending_ai_memory"
    private const val KEY_PREFIX = "pending_"
    private const val MAX_RETRIES = 5
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    private val gson = Gson()
    private val lock = Any()

    @Volatile
    private var lastContext: Context? = null

    @Volatile
    private var forceStageFailureForTesting: Boolean = false

    data class ReplayReport(
        val totalPending: Int,
        val replayedCount: Int,
        val failedCount: Int
    ) {
        val allSucceeded: Boolean
            get() = failedCount == 0
    }

    data class PendingPostDispatchEntry(
        val attemptId: String,
        val statusName: String,
        val dispatchAttempted: Boolean,
        val dispatchedNormally: Boolean,
        val failureCategory: String?,
        val packageName: String,
        val senderKey: String,
        val senderDisplay: String,
        val incomingMessage: String,
        val incomingEventKey: String?,
        val legacyIncomingEventKey: String?,
        val assistantReply: String,
        val extractedNotes: List<String>,
        val memoryEnabled: Boolean,
        val notesEnabled: Boolean,
        val recordReplyLog: Boolean,
        val keepCount: Int,
        val timestampMs: Long,
        val retryCount: Int = 0,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    @JvmStatic
    fun stage(context: Context, entry: PendingPostDispatchEntry): Boolean {
        if (entry.attemptId.isBlank()) return false
        val appCtx = context.applicationContext
        lastContext = appCtx
        synchronized(lock) {
            return try {
                if (forceStageFailureForTesting) {
                    throw IllegalStateException("Forced stage failure for testing")
                }
                val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val committed = prefs.edit()
                    .putString(KEY_PREFIX + entry.attemptId, gson.toJson(entry))
                    .commit()
                if (!committed) {
                    Log.e(
                        TAG,
                        "Unable to commit pending AI memory write for attemptId=${entry.attemptId}"
                    )
                }
                committed
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Unable to stage pending AI memory write for attemptId=${entry.attemptId} (${e.javaClass.simpleName})"
                )
                false
            }
        }
    }

    @JvmStatic
    fun remove(context: Context, attemptId: String?) {
        if (attemptId.isNullOrBlank()) return
        val appCtx = context.applicationContext
        lastContext = appCtx
        synchronized(lock) {
            try {
                val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().remove(KEY_PREFIX + attemptId).commit()
            } catch (e: Exception) {
                Log.w(TAG, "Unable to remove staged AI memory write (${e.javaClass.simpleName})")
            }
        }
    }

    @JvmStatic
    fun markRetryFailure(context: Context, attemptId: String?) {
        if (attemptId.isNullOrBlank()) return
        val appCtx = context.applicationContext
        lastContext = appCtx
        synchronized(lock) {
            try {
                val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val key = KEY_PREFIX + attemptId
                val raw = prefs.getString(key, null) ?: return
                val parsed = runCatching { gson.fromJson(raw, PendingPostDispatchEntry::class.java) }.getOrNull()
                if (parsed == null || parsed.retryCount + 1 >= MAX_RETRIES) {
                    prefs.edit().remove(key).commit()
                } else {
                    prefs.edit()
                        .putString(key, gson.toJson(parsed.copy(retryCount = parsed.retryCount + 1)))
                        .commit()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to update retry count (${e.javaClass.simpleName})")
            }
        }
    }

    @JvmStatic
    fun getPendingEntries(context: Context, nowMs: Long = System.currentTimeMillis()): List<PendingPostDispatchEntry> {
        val appCtx = context.applicationContext
        lastContext = appCtx
        synchronized(lock) {
            return try {
                val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val result = mutableListOf<PendingPostDispatchEntry>()
                val staleKeys = mutableListOf<String>()
                for ((key, value) in prefs.all) {
                    if (!key.startsWith(KEY_PREFIX)) continue
                    val raw = value as? String
                    val parsed = raw?.let {
                        runCatching { gson.fromJson(it, PendingPostDispatchEntry::class.java) }.getOrNull()
                    }
                    if (parsed == null ||
                        parsed.attemptId.isBlank() ||
                        parsed.retryCount >= MAX_RETRIES ||
                        (nowMs - parsed.createdAtMs) > MAX_AGE_MS
                    ) {
                        staleKeys.add(key)
                    } else {
                        result.add(parsed)
                    }
                }
                if (staleKeys.isNotEmpty()) {
                    val editor = prefs.edit()
                    staleKeys.forEach { editor.remove(it) }
                    editor.commit()
                }
                result.sortedBy { it.createdAtMs }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to read staged AI memory writes (${e.javaClass.simpleName})")
                emptyList()
            }
        }
    }

    @JvmStatic
    fun getPendingAttemptIds(context: Context): List<String> {
        return getPendingEntries(context).map { it.attemptId }
    }

    /**
     * Replays any staged post-dispatch AI memory entries after verifying authoritative
     * MessageLogsDB persistence consistency (so UNKNOWN/failed attempts never get promoted).
     */
    @JvmStatic
    @JvmOverloads
    fun replayPendingEntries(
        context: Context,
        repository: AiMemoryRepository? = null,
        dbUtils: DbUtils? = null
    ): Int {
        return replayPendingEntriesWithReport(context, repository, dbUtils).replayedCount
    }

    /**
     * Replays staged post-dispatch AI memory entries and returns a structured [ReplayReport]
     * indicating both succeeded and failed entry counts.
     */
    @JvmStatic
    @JvmOverloads
    fun replayPendingEntriesWithReport(
        context: Context,
        repository: AiMemoryRepository? = null,
        dbUtils: DbUtils? = null
    ): ReplayReport {
        val appCtx = context.applicationContext
        val pending = getPendingEntries(appCtx)
        if (pending.isEmpty()) return ReplayReport(totalPending = 0, replayedCount = 0, failedCount = 0)

        val repo = repository ?: AiMemoryRepository(AiMemoryDatabase.getInstance(appCtx).aiMemoryDao())
        val db = dbUtils ?: DbUtils(appCtx)
        var replayedCount = 0
        var failedCount = 0

        for (entry in pending) {
            try {
                val originalStatus = runCatching { ReplyExecutionStatus.valueOf(entry.statusName) }
                    .getOrDefault(ReplyExecutionStatus.UNKNOWN)
                var effectiveResult = ReplyExecutionResult(
                    executionId = entry.attemptId,
                    status = originalStatus,
                    dispatchAttempted = entry.dispatchAttempted,
                    dispatchedNormally = entry.dispatchedNormally,
                    failureCategory = entry.failureCategory,
                    timestampMs = entry.timestampMs
                )
                if (effectiveResult.isDispatched() && !db.hasConsistentDispatchedPersistence(entry.attemptId)) {
                    effectiveResult = ReplyExecutionResult(
                        executionId = entry.attemptId,
                        status = ReplyExecutionStatus.UNKNOWN,
                        dispatchAttempted = true,
                        dispatchedNormally = false,
                        failureCategory = entry.failureCategory,
                        timestampMs = entry.timestampMs
                    )
                }
                repo.persistAuthoritativeAiReplyOutcome(
                    result = effectiveResult,
                    packageName = entry.packageName,
                    senderKey = entry.senderKey,
                    senderDisplay = entry.senderDisplay,
                    incomingMessage = entry.incomingMessage,
                    incomingEventKey = entry.incomingEventKey,
                    assistantReply = entry.assistantReply,
                    extractedNotes = entry.extractedNotes,
                    memoryEnabled = entry.memoryEnabled,
                    notesEnabled = entry.notesEnabled,
                    keepCount = entry.keepCount,
                    timestampMs = entry.timestampMs,
                    legacyIncomingEventKey = entry.legacyIncomingEventKey,
                    recordReplyLog = entry.recordReplyLog
                )
                remove(appCtx, entry.attemptId)
                replayedCount++
            } catch (e: Exception) {
                failedCount++
                Log.w(TAG, "Replay of staged AI memory write failed (${e.javaClass.simpleName})")
                markRetryFailure(appCtx, entry.attemptId)
            }
        }
        return ReplayReport(
            totalPending = pending.size,
            replayedCount = replayedCount,
            failedCount = failedCount
        )
    }

    @JvmStatic
    fun setForceStageFailureForTesting(force: Boolean) {
        forceStageFailureForTesting = force
    }

    @JvmStatic
    fun clearForTesting(context: Context? = lastContext) {
        forceStageFailureForTesting = false
        val appCtx = context?.applicationContext ?: return
        synchronized(lock) {
            runCatching {
                appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            }
        }
    }
}
