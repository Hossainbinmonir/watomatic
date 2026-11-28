package com.parishod.watomatic.service.ai

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.parishod.watomatic.NotificationWear
import com.parishod.watomatic.R
import com.parishod.watomatic.model.ai.AiConversationMessage
import com.parishod.watomatic.model.ai.AiMemoryDatabase
import com.parishod.watomatic.model.ai.AiMemoryRepository
import com.parishod.watomatic.model.ai.AiNote
import com.parishod.watomatic.model.ai.AiReplyLog
import com.parishod.watomatic.model.ai.PendingPostDispatchMemoryStore
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.logs.ReplyAttemptEntity
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.utils.Constants
import com.parishod.watomatic.model.utils.DbUtils
import com.parishod.watomatic.service.NotificationService
import com.parishod.watomatic.service.reply.NotificationEventKeyBuilder
import com.parishod.watomatic.service.reply.ReplyExecutionManager
import com.parishod.watomatic.service.reply.ReplyExecutionResult
import com.parishod.watomatic.service.reply.ReplyExecutionStatus
import com.parishod.watomatic.service.reply.ReplySourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Stage024AiMemoryHardeningTest {

    private lateinit var context: Context
    private lateinit var service: NotificationService
    private lateinit var prefs: PreferencesManager
    private lateinit var memoryDb: AiMemoryDatabase
    private lateinit var logsDb: MessageLogsDB

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferencesManager.resetInstance()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        val encryptedStore = context.getSharedPreferences("test_stage024_enc", Context.MODE_PRIVATE)
        encryptedStore.edit().clear().commit()
        PreferencesManager.setEncryptedPrefsProviderForTesting { encryptedStore }
        prefs = PreferencesManager.getPreferencesInstance(context)
        prefs.setAiMemoryEnabled(true)
        prefs.setAiAutoNotesEnabled(true)
        prefs.setAiGroupFilterMode("BOTH")

        PendingPostDispatchMemoryStore.clearForTesting(context)
        AiMemoryDatabase.resetInstance()
        context.deleteDatabase(AiMemoryDatabase.DB_NAME)
        MessageLogsDB.resetInstance()
        MessageLogsDB.getInstance(context).clearAllTables()

        memoryDb = AiMemoryDatabase.getInstance(context)
        logsDb = MessageLogsDB.getInstance(context)
        service = Robolectric.buildService(NotificationService::class.java).create().get()
        awaitMemory()
    }

    @After
    fun tearDown() {
        try {
            service.onDestroy()
        } catch (_: Exception) {
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit {
                PendingPostDispatchMemoryStore.clearForTesting(context)
                AiMemoryDatabase.resetInstance()
                context.deleteDatabase(AiMemoryDatabase.DB_NAME)
                MessageLogsDB.resetInstance()
                context.deleteDatabase("message_logs_db")
            }.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("test_stage024_enc", Context.MODE_PRIVATE).edit().clear().commit()
        PreferencesManager.resetInstance()
    }

    private fun buildSbn(
        pkg: String = "com.whatsapp",
        sender: String? = "Alice",
        message: String = "Hello?",
        id: Int = 500,
        isGroup: Boolean = false,
        timestampMs: Long = 1_750_000_000_000L + id
    ): StatusBarNotification {
        val replyPi = PendingIntent.getBroadcast(
            context,
            id,
            Intent("com.parishod.watomatic.STAGE024_TEST").setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val remoteInput = RemoteInput.Builder("reply_key")
            .setLabel("Reply")
            .setAllowFreeFormInput(true)
            .build()
        val action = NotificationCompat.Action.Builder(0, "Reply", replyPi)
            .addRemoteInput(remoteInput)
            .build()
        val builder = NotificationCompat.Builder(context, "test_channel")
            .setContentText(message)
            .setWhen(timestampMs)
            .addAction(action)
        if (sender != null) {
            builder.setContentTitle(sender)
        }
        val notification = builder.build()
        notification.extras.putBoolean("android.isGroupConversation", isGroup)
        if (isGroup && sender != null) {
            notification.extras.putString("android.hiddenConversationTitle", sender)
        }
        return StatusBarNotification(
            pkg,
            pkg,
            id,
            "tag-$id",
            0,
            0,
            0,
            notification,
            Process.myUserHandle(),
            timestampMs
        )
    }

    private fun wearFromSbn(sbn: StatusBarNotification): NotificationWear {
        val remoteInput = RemoteInput.Builder("reply_key")
            .setLabel("Reply")
            .setAllowFreeFormInput(true)
            .build()
        val pi = PendingIntent.getBroadcast(
            context,
            sbn.id + 9000,
            Intent("com.parishod.watomatic.STAGE024_WEAR").setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        return NotificationWear(sbn.packageName, pi, listOf(remoteInput), sbn.notification.extras, sbn.tag, sbn.id.toString())
    }

    private fun setReplyManager(manager: ReplyExecutionManager, target: NotificationService = service) {
        NotificationService::class.java
            .getDeclaredMethod("setReplyExecutionManagerForTesting", ReplyExecutionManager::class.java)
            .apply { isAccessible = true }
            .invoke(target, manager)
    }

    private fun setMemoryRepo(repo: AiMemoryRepository, target: NotificationService = service) {
        NotificationService::class.java
            .getDeclaredMethod("setAiMemoryRepositoryForTesting", AiMemoryRepository::class.java)
            .apply { isAccessible = true }
            .invoke(target, repo)
    }

    private fun setTestDbUtils(dbUtils: DbUtils, target: NotificationService = service) {
        NotificationService::class.java
            .getDeclaredMethod("setDbUtilsForTesting", DbUtils::class.java)
            .apply { isAccessible = true }
            .invoke(target, dbUtils)
    }

    private fun awaitMemory(target: NotificationService = service) {
        NotificationService::class.java
            .getDeclaredMethod("awaitAiMemoryIdleForTesting")
            .apply { isAccessible = true }
            .invoke(target)
    }

    private fun awaitLatchWithMainLooper(latch: CountDownLatch, timeoutMs: Long = 5_000L): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (latch.await(15, TimeUnit.MILLISECONDS)) {
                shadowOf(Looper.getMainLooper()).idle()
                return true
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        return latch.count == 0L
    }

    private suspend fun awaitConditionWithMainLooper(
        timeoutMs: Long = 5_000L,
        predicate: suspend () -> Boolean
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            awaitMemory()
            if (withContext(Dispatchers.IO) { predicate() }) {
                return true
            }
            kotlinx.coroutines.delay(15)
        }
        shadowOf(Looper.getMainLooper()).idle()
        awaitMemory()
        return withContext(Dispatchers.IO) { predicate() }
    }

    private fun invokeHandleSuccess(
        sbn: StatusBarNotification,
        rawAiReply: String,
        senderKey: String = AiPromptAndPolicyHelper.normalizeSenderKey(
            com.parishod.watomatic.model.utils.NotificationUtils.getTitle(sbn),
            sbn.notification.extras.getBoolean("android.isGroupConversation", false)
        ),
        senderDisplay: String = com.parishod.watomatic.model.utils.NotificationUtils.getTitle(sbn)?.trim().takeUnless { it.isNullOrEmpty() } ?: "Unknown",
        incomingMessage: String = sbn.notification.extras.getString("android.text") ?: "Hi",
        memoryEnabled: Boolean = true,
        notesEnabled: Boolean = true,
        historySourceLabel: String = context.getString(R.string.history_source_ai)
    ): ReplyExecutionResult {
        val method = NotificationService::class.java.getDeclaredMethod(
            "handleSuccessfulAiReplyWithResult",
            PreferencesManager::class.java,
            StatusBarNotification::class.java,
            NotificationWear::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java
        ).apply { isAccessible = true }

        return method.invoke(
            service,
            prefs,
            sbn,
            wearFromSbn(sbn),
            sbn.packageName,
            senderKey,
            senderDisplay,
            incomingMessage,
            rawAiReply,
            memoryEnabled,
            notesEnabled,
            20,
            historySourceLabel
        ) as ReplyExecutionResult
    }

    private fun invokePrepareAndFetchAiReply(
        sbn: StatusBarNotification,
        incomingMessage: String,
        fallbackReplyText: String = "Fallback",
        ruleGuidance: String? = null,
        historySourceLabel: String = context.getString(R.string.history_source_ai),
        enforceGlobalAiPolicy: Boolean = true
    ) {
        val method = NotificationService::class.java.getDeclaredMethod(
            "prepareAndFetchAiReply",
            StatusBarNotification::class.java,
            NotificationWear::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            com.parishod.watomatic.model.rules.AutoReplyRule::class.java,
            String::class.java
        ).apply { isAccessible = true }

        method.invoke(
            service,
            sbn,
            wearFromSbn(sbn),
            incomingMessage,
            fallbackReplyText,
            ruleGuidance,
            historySourceLabel,
            enforceGlobalAiPolicy,
            null,
            null
        )
    }

    // --- F-01: Post-dispatch durability across onDestroy & PendingPostDispatchMemoryStore replay ---

    @Test
    fun `F-01 accepted post-dispatch persistence drains cleanly despite immediate onDestroy`() = runBlocking {
        setReplyManager(ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> }))
        val realRepo = AiMemoryRepository(memoryDb.aiMemoryDao())
        val persistenceStarted = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)

        val slowRepo = object : AiMemoryRepository(memoryDb.aiMemoryDao()) {
            override fun persistAuthoritativeAiReplyOutcome(
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
                timestampMs: Long
            ): Boolean {
                persistenceStarted.countDown()
                allowFinish.await(5, TimeUnit.SECONDS)
                return realRepo.persistAuthoritativeAiReplyOutcome(
                    result, packageName, senderKey, senderDisplay, incomingMessage, incomingEventKey,
                    assistantReply, extractedNotes, memoryEnabled, notesEnabled, keepCount, timestampMs
                )
            }
        }
        setMemoryRepo(slowRepo)

        val sbn = buildSbn(sender = "Alice", message = "Keep this turn", id = 501)
        val res = invokeHandleSuccess(sbn, "Persisted safely\n[[SAVE_NOTE: Durable reminder]]")
        assertTrue(res.isDispatched())
        assertTrue(persistenceStarted.await(3, TimeUnit.SECONDS))

        // Immediately destroy service while post-dispatch persistence is in flight; onDestroy must not abort or block
        val startDestroyNs = System.nanoTime()
        service.onDestroy()
        val destroyElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startDestroyNs)
        assertTrue("onDestroy() should not block main thread waiting on slow write ($destroyElapsedMs ms)", destroyElapsedMs < 1000L)

        allowFinish.countDown()
        awaitMemory()

        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            val hist = dao.getRecentMessagesDescSync("com.whatsapp", "alice", 10).asReversed()
            assertEquals(listOf("Keep this turn", "Persisted safely"), hist.map { it.content })
            assertEquals(1, dao.getNotesByAttemptIdSync(res.executionId).size)
            assertEquals(1, dao.getReplyLogsByAttemptIdSync(res.executionId).size)
            assertTrue(PendingPostDispatchMemoryStore.getPendingAttemptIds(context).isEmpty())
        }
    }

    @Test
    fun `F-01 transient failure keeps staged entry and startup recovery replays idempotently`() = runBlocking {
        setReplyManager(ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> }))
        val throwingRepo = object : AiMemoryRepository(memoryDb.aiMemoryDao()) {
            override fun persistAuthoritativeAiReplyOutcome(
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
                timestampMs: Long
            ): Boolean {
                throw IllegalStateException("Transient SQLITE_BUSY")
            }
        }
        setMemoryRepo(throwingRepo)

        val sbn = buildSbn(sender = "Alice", message = "Recover me", id = 502)
        val res = invokeHandleSuccess(sbn, "Recovered reply\n[[SAVE_NOTE: Recovered note]]")
        awaitMemory()
        assertTrue(res.isDispatched())

        // Staged entry must survive failed DB write and still sit in PendingPostDispatchMemoryStore
        val stagedBefore = PendingPostDispatchMemoryStore.getPendingEntries(context)
        assertEquals(1, stagedBefore.size)
        assertEquals(res.executionId, stagedBefore[0].attemptId)
        assertEquals(1, stagedBefore[0].retryCount)

        // Recreate NotificationService -> onCreate -> ensureStartupRecovery replays staged write idempotently
        val recoveredService = Robolectric.buildService(NotificationService::class.java).create().get()
        try {
            awaitMemory(recoveredService)
        } finally {
            recoveredService.onDestroy()
        }

        // Second manual replay must be a no-op because staged queue is already drained cleanly
        val extraReplayed = withContext(Dispatchers.IO) {
            PendingPostDispatchMemoryStore.replayPendingEntries(context)
        }
        assertEquals(0, extraReplayed)

        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            assertEquals(1, dao.countAssistantMessagesByAttemptIdSync(res.executionId))
            assertEquals(1, dao.countNotesByAttemptIdSync(res.executionId))
            assertEquals(1, dao.countReplyLogsByAttemptIdSync(res.executionId))
            assertTrue(PendingPostDispatchMemoryStore.getPendingEntries(context).isEmpty())
        }
    }

    // --- F-02: Clear AI Memory & 30-day Retention ---

    @Test
    fun `F-02 clearConversationMemoryAndAiNotes keeps manual notes and ai_reply_logs intact`() = runBlocking {
        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            val repo = AiMemoryRepository(dao)

            // AI-generated turn + note + reply log
            repo.persistAuthoritativeAiReplyOutcome(
                result = ReplyExecutionResult("att-clear-1", ReplyExecutionStatus.DISPATCHED, true, true, null, "ok", 5_000L),
                packageName = "com.whatsapp",
                senderKey = "alice",
                senderDisplay = "Alice",
                incomingMessage = "Hello",
                incomingEventKey = "evt-clear-1",
                assistantReply = "Hi Alice",
                extractedNotes = listOf("AI saved task"),
                memoryEnabled = true,
                notesEnabled = true,
                keepCount = 20,
                timestampMs = 5_000L
            )
            // Manual note (attemptId == null)
            dao.insertNoteSync(
                AiNote(
                    senderName = "Owner Reminder",
                    packageName = "manual",
                    noteText = "Manual reminder stays",
                    sourceMessage = "Added manually",
                    createdAt = 6_000L,
                    isCompleted = false,
                    attemptId = null,
                    noteDedupKey = null,
                    senderKey = "owner reminder"
                )
            )
            assertEquals(2, dao.getConversationMessageCountSync())
            assertEquals(2, dao.getTotalNoteCountSync())
            assertEquals(1, dao.getAiGeneratedNoteCountSync())

            repo.clearConversationMemoryAndAiNotes()

            assertEquals(0, dao.getConversationMessageCountSync())
            val remainingNotes = dao.observeAllNotes().first()
            assertEquals(1, remainingNotes.size)
            assertEquals("Manual reminder stays", remainingNotes[0].noteText)
            assertNull(remainingNotes[0].attemptId)
            // Cooldown / frequency reply log MUST be preserved!
            assertEquals(5_000L, dao.getLastAiReplyTimeSync("com.whatsapp", "alice"))
        }

        // Verify updated clear dialog copy matches behavior
        assertTrue(context.getString(R.string.ai_clear_memory_message).contains("manual reminders", ignoreCase = true))
        assertTrue(context.getString(R.string.history_clear_message).contains("AI conversation memory", ignoreCase = true))
    }

    @Test
    fun `F-02 DbUtils purgeExpiredHistory removes 30-day expired memory idempotently while protecting unresolved and staged attempts`() = runBlocking {
        val nowMs = 1_850_000_000_000L
        val expiredTs = nowMs - DbUtils.HISTORY_RETENTION_MS - 1_000L
        val recentTs = nowMs - 1_000L

        withContext(Dispatchers.IO) {
            val attemptDao = logsDb.replyAttemptDao()
            attemptDao.upsertAttemptSafely(
                ReplyAttemptEntity(
                    attemptId = "att-unresolved",
                    packageName = "com.whatsapp",
                    senderName = "Alice",
                    incomingMessage = "M",
                    notificationKey = "nk-u",
                    replyText = "R",
                    sourceType = ReplySourceType.GLOBAL_AI.name,
                    sourceLabel = "AI",
                    status = ReplyExecutionStatus.UNKNOWN.name,
                    dispatchAttempted = true,
                    dispatchedNormally = false,
                    failureCategory = null,
                    createdAtMs = expiredTs,
                    updatedAtMs = expiredTs,
                    eventKey = "evt-u"
                )
            )

            PendingPostDispatchMemoryStore.stage(
                context,
                PendingPostDispatchMemoryStore.PendingPostDispatchEntry(
                    attemptId = "att-staged",
                    statusName = "DISPATCHED",
                    dispatchAttempted = true,
                    dispatchedNormally = true,
                    failureCategory = null,
                    packageName = "com.whatsapp",
                    senderKey = "alice",
                    senderDisplay = "Alice",
                    incomingMessage = "Staged",
                    incomingEventKey = "evt-s",
                    legacyIncomingEventKey = null,
                    assistantReply = "Staged reply",
                    extractedNotes = emptyList(),
                    memoryEnabled = true,
                    notesEnabled = false,
                    recordReplyLog = true,
                    keepCount = 20,
                    timestampMs = expiredTs,
                    retryCount = 0,
                    createdAtMs = nowMs
                )
            )

            val aiDao = memoryDb.aiMemoryDao()
            aiDao.insertConversationMessageSync(
                AiConversationMessage(packageName = "com.whatsapp", senderKey = "alice", senderDisplayName = "Alice", role = "user", content = "Old expired", timestamp = expiredTs, attemptId = "att-old-done", executionStatus = "DISPATCHED")
            )
            aiDao.insertConversationMessageSync(
                AiConversationMessage(packageName = "com.whatsapp", senderKey = "alice", senderDisplayName = "Alice", role = "assistant", content = "Protected unresolved", timestamp = expiredTs + 1, attemptId = "att-unresolved", executionStatus = "DISPATCHED")
            )
            aiDao.insertConversationMessageSync(
                AiConversationMessage(packageName = "com.whatsapp", senderKey = "alice", senderDisplayName = "Alice", role = "assistant", content = "Protected staged", timestamp = expiredTs + 2, attemptId = "att-staged", executionStatus = "DISPATCHED")
            )
            aiDao.insertConversationMessageSync(
                AiConversationMessage(packageName = "com.whatsapp", senderKey = "alice", senderDisplayName = "Alice", role = "user", content = "Recent msg", timestamp = recentTs, attemptId = null, executionStatus = "USER_RECEIVED")
            )

            aiDao.insertNoteSync(
                AiNote(senderName = "Alice", packageName = "com.whatsapp", noteText = "Expired note", sourceMessage = "src", createdAt = expiredTs, isCompleted = false, attemptId = "att-old-done", noteDedupKey = "k-old", senderKey = "alice")
            )
            aiDao.insertNoteSync(
                AiNote(senderName = "Alice", packageName = "com.whatsapp", noteText = "Protected unresolved note", sourceMessage = "src", createdAt = expiredTs, isCompleted = false, attemptId = "att-unresolved", noteDedupKey = "k-u", senderKey = "alice")
            )
            aiDao.insertNoteSync(
                AiNote(senderName = "Alice", packageName = "com.whatsapp", noteText = "Recent note", sourceMessage = "src", createdAt = recentTs, isCompleted = false, attemptId = "att-recent", noteDedupKey = "k-rec", senderKey = "alice")
            )
            aiDao.insertReplyLogSync(AiReplyLog(packageName = "com.whatsapp", senderKey = "alice", repliedAt = expiredTs, attemptId = "att-old-done"))

            val dbUtils = DbUtils(context)
            dbUtils.purgeExpiredHistory(nowMs)
            // Idempotent second run
            dbUtils.purgeExpiredHistory(nowMs)

            val remainingContents = aiDao.getAllConversationMessagesSync().map { it.content }
            assertEquals(listOf("Protected unresolved", "Protected staged", "Recent msg"), remainingContents)

            val remainingNotes = aiDao.observeAllNotes().first().map { it.noteText }.toSet()
            assertEquals(setOf("Protected unresolved note", "Recent note"), remainingNotes)

            // Rate-limit reply logs are preserved across retention cleanup
            assertEquals(expiredTs, aiDao.getLastAiReplyTimeSync("com.whatsapp", "alice"))
        }
    }

    // --- F-03: Unknown sender & direct vs group isolation ---

    @Test
    fun `F-03 unknown senders never share context or notes and direct vs group chats remain isolated`() = runBlocking {
        setReplyManager(ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> }))

        // Two separate unidentified notifications (null / blank title)
        val unk1 = buildSbn(sender = null, message = "Unknown 1 secret", id = 510)
        val unk2 = buildSbn(sender = "   ", message = "Unknown 2 secret", id = 511)
        val res1 = invokeHandleSuccess(unk1, "Reply to unknown 1\n[[SAVE_NOTE: Note from unk1]]")
        val res2 = invokeHandleSuccess(unk2, "Reply to unknown 2")
        assertTrue(res1.isDispatched())
        assertTrue(res2.isDispatched())

        // Direct "Sam" vs Group "Sam"
        val directSam = buildSbn(sender = "Sam", message = "Direct msg", id = 512, isGroup = false)
        val groupSam = buildSbn(sender = "Sam", message = "Group msg", id = 513, isGroup = true)
        invokeHandleSuccess(directSam, "Direct reply\n[[SAVE_NOTE: Direct Sam note]]")
        invokeHandleSuccess(groupSam, "Group reply\n[[SAVE_NOTE: Group Sam note]]")
        awaitMemory()

        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            // Unknown senders produce zero shared conversation memory or prompt notes
            assertTrue(dao.getRecentMessagesDescSync("com.whatsapp", AiPromptAndPolicyHelper.UNKNOWN_SENDER, 10).isEmpty())
            assertTrue(dao.getPendingNotesForSenderKeySync("com.whatsapp", AiPromptAndPolicyHelper.UNKNOWN_SENDER, 5).isEmpty())

            // Unknown sender note is still stored cleanly for the owner UI under UNKNOWN_SENDER
            assertTrue("Expected consistent MessageLogsDB state for res1", DbUtils(context).hasConsistentDispatchedPersistence(res1.executionId))
            assertTrue("Unexpected pending entries: ${PendingPostDispatchMemoryStore.getPendingAttemptIds(context)}", PendingPostDispatchMemoryStore.getPendingAttemptIds(context).isEmpty())
            val res1Notes = dao.getNotesByAttemptIdSync(res1.executionId)
            assertEquals("Expected 1 note for res1 (all notes=${dao.observeAllNotes().first()})", 1, res1Notes.size)
            assertEquals(AiPromptAndPolicyHelper.UNKNOWN_SENDER, res1Notes[0].senderKey)

            // Direct "sam" vs group "group:sam" isolated across messages, notes, and reply logs
            assertEquals(listOf("Direct msg", "Direct reply"), dao.getRecentMessagesDescSync("com.whatsapp", "sam", 10).asReversed().map { it.content })
            assertEquals(listOf("Group msg", "Group reply"), dao.getRecentMessagesDescSync("com.whatsapp", "group:sam", 10).asReversed().map { it.content })
            assertEquals(listOf("Direct Sam note"), dao.getPendingNotesForSenderKeySync("com.whatsapp", "sam", 5).map { it.noteText })
            assertEquals(listOf("Group Sam note"), dao.getPendingNotesForSenderKeySync("com.whatsapp", "group:sam", 5).map { it.noteText })
            assertNotNull(dao.getLastAiReplyTimeSync("com.whatsapp", "sam"))
            assertNotNull(dao.getLastAiReplyTimeSync("com.whatsapp", "group:sam"))
        }
    }

    // --- F-04: Cross-DB consistency when MessageLogsDB falls back to UNKNOWN ---

    @Test
    fun `F-04 MessageLogsDB UNKNOWN downgrade prevents ai_memory_db assistant commit and replay promotion`() = runBlocking {
        setReplyManager(ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> }))

        // Simulate MessageLogsDB recordAttemptResult failing after binder send, causing DbUtils fallback -> UNKNOWN
        val realDbUtils = DbUtils(context)
        val brokenMessageLogDbUtils = object : DbUtils(context) {
            override fun recordAttemptResult(
                attempt: com.parishod.watomatic.service.reply.ReplyAttempt,
                result: ReplyExecutionResult,
                senderName: String?,
                incomingMsg: String?,
                sbn: StatusBarNotification?
            ) {
                throw IllegalStateException("Simulated MessageLogsDB failure after binder send")
            }
        }
        setTestDbUtils(brokenMessageLogDbUtils)

        val sbn = buildSbn(sender = "Alice", message = "Did reply send?", id = 520)
        val res = invokeHandleSuccess(sbn, "Claimed sent\n[[SAVE_NOTE: Never commit on UNKNOWN]]")
        awaitMemory()

        // Stage 02.3 dispatch contract remains intact (returns DISPATCHED) while DB fallback recorded UNKNOWN
        assertTrue(res.isDispatched())
        assertEquals("UNKNOWN", realDbUtils.getAttemptById(res.executionId)?.status)
        assertFalse(realDbUtils.hasConsistentDispatchedPersistence(res.executionId))

        // Because MessageLogsDB consistency check returned false, ai_memory_db must NOT record assistant/notes/replyLog
        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            assertEquals(0, dao.countAssistantMessagesByAttemptIdSync(res.executionId))
            assertEquals(0, dao.countNotesByAttemptIdSync(res.executionId))
            assertEquals(0, dao.countReplyLogsByAttemptIdSync(res.executionId))
            // Incoming user turn stays preserved
            assertEquals(listOf("user" to "Did reply send?"), dao.getRecentMessagesDescSync("com.whatsapp", "alice", 10).map { it.role to it.content })
        }

        // Verify PendingPostDispatchMemoryStore replay also refuses to promote an inconsistent attempt
        PendingPostDispatchMemoryStore.stage(
            context,
            PendingPostDispatchMemoryStore.PendingPostDispatchEntry(
                attemptId = res.executionId,
                statusName = "DISPATCHED",
                dispatchAttempted = true,
                dispatchedNormally = true,
                failureCategory = null,
                packageName = "com.whatsapp",
                senderKey = "alice",
                senderDisplay = "Alice",
                incomingMessage = "Did reply send?",
                incomingEventKey = NotificationEventKeyBuilder.buildEventKey(sbn, "Did reply send?"),
                legacyIncomingEventKey = null,
                assistantReply = "Claimed sent",
                extractedNotes = listOf("Never promote"),
                memoryEnabled = true,
                notesEnabled = true,
                recordReplyLog = true,
                keepCount = 20,
                timestampMs = 1_750_000_000_520L,
                retryCount = 0,
                createdAtMs = System.currentTimeMillis()
            )
        )
        withContext(Dispatchers.IO) {
            PendingPostDispatchMemoryStore.replayPendingEntries(context, AiMemoryRepository(memoryDb.aiMemoryDao()), realDbUtils)
            val dao = memoryDb.aiMemoryDao()
            assertEquals(0, dao.countAssistantMessagesByAttemptIdSync(res.executionId))
            assertEquals(0, dao.countNotesByAttemptIdSync(res.executionId))
            assertEquals(0, dao.countReplyLogsByAttemptIdSync(res.executionId))
        }
    }

    // --- F-05: Per-sender FIFO concurrency & cross-sender parallelism ---

    @Test
    fun `F-05 coordinator enforces same-sender FIFO, parallel different senders, isolated unknown senders, and error permit release`() {
        val exec = Executors.newCachedThreadPool()
        val coordinator = AiConversationCoordinator()
        try {
            val alice1Started = CountDownLatch(1)
            val alice1Release = CountDownLatch(1)
            val alice2Started = CountDownLatch(1)
            val bobStarted = CountDownLatch(1)
            val unk1Started = CountDownLatch(1)
            val unk2Started = CountDownLatch(1)
            val order = CopyOnWriteArrayList<String>()

            // Submit Alice #1 (holds permit until alice1Release)
            assertTrue(coordinator.submit("com.whatsapp|alice", { exec }, null) { releaser ->
                order.add("alice1_start")
                alice1Started.countDown()
                exec.execute {
                    alice1Release.await(5, TimeUnit.SECONDS)
                    order.add("alice1_done")
                    releaser.run()
                }
            })
            assertTrue(alice1Started.await(3, TimeUnit.SECONDS))

            // Submit Alice #2 (must wait behind Alice #1) and Bob #1 (must execute immediately)
            assertTrue(coordinator.submit("com.whatsapp|alice", { exec }, null) { releaser ->
                order.add("alice2_start")
                alice2Started.countDown()
                releaser.run()
            })
            assertTrue(coordinator.submit("com.whatsapp|bob", { exec }, null) { releaser ->
                order.add("bob1_start")
                bobStarted.countDown()
                releaser.run()
            })

            // Two unknown-sender events use distinct lanes and must NOT block behind each other
            val k1 = AiConversationCoordinator.buildConversationKey("com.whatsapp", AiPromptAndPolicyHelper.UNKNOWN_SENDER, "evt-1")
            val k2 = AiConversationCoordinator.buildConversationKey("com.whatsapp", AiPromptAndPolicyHelper.UNKNOWN_SENDER, "evt-2")
            assertTrue(k1 != k2)
            assertTrue(coordinator.submit(k1, { exec }, null) { _ ->
                unk1Started.countDown() // deliberately stays open while unk2 submits
            })
            assertTrue(unk1Started.await(3, TimeUnit.SECONDS))
            assertTrue(coordinator.submit(k2, { exec }, null) { releaser ->
                unk2Started.countDown()
                releaser.run()
            })

            assertTrue(bobStarted.await(3, TimeUnit.SECONDS))
            assertTrue(unk2Started.await(3, TimeUnit.SECONDS))
            assertFalse("Alice #2 must stay queued behind Alice #1", alice2Started.await(150, TimeUnit.MILLISECONDS))

            alice1Release.countDown()
            assertTrue(alice2Started.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("alice1_start", "bob1_start", "alice1_done", "alice2_start"), order.toList())

            // Throwing task must release permit so next task on same key still runs
            val afterThrowRan = CountDownLatch(1)
            coordinator.submit("com.whatsapp|carol", { exec }, null) { _ ->
                throw IllegalStateException("Boom before async handoff")
            }
            coordinator.submit("com.whatsapp|carol", { exec }, null) { releaser ->
                afterThrowRan.countDown()
                releaser.run()
            }
            assertTrue(afterThrowRan.await(3, TimeUnit.SECONDS))

            coordinator.cancelAll()
            assertEquals(0, coordinator.getActiveLaneCountForTesting())
            assertFalse(coordinator.submit("com.whatsapp|alice", { exec }, null) { it.run() })
        } finally {
            exec.shutdownNow()
        }
    }

    @Test
    fun `F-05 NotificationService serializes concurrent prepareAndFetchAiReply per sender until post-dispatch persistence completes`() = runBlocking {
        setReplyManager(ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> }))

        val alice1HitServer = CountDownLatch(1)
        val releaseAlice1Response = CountDownLatch(1)
        val bobCompleted = CountDownLatch(1)
        val alice2HitServer = CountDownLatch(1)
        val alice2Completed = CountDownLatch(1)
        val alice2BodyHolder = arrayOfNulls<String>(1)

        val serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val serverPool = Executors.newCachedThreadPool()
        val serverRunning = java.util.concurrent.atomic.AtomicBoolean(true)
        serverPool.execute {
            while (serverRunning.get()) {
                try {
                    val client = serverSocket.accept()
                    serverPool.execute {
                        client.use { sock ->
                            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                            var contentLength = 0
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.lowercase().startsWith("content-length:")) {
                                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                                }
                            }
                            val bodyChars = CharArray(contentLength)
                            var readTotal = 0
                            while (readTotal < contentLength) {
                                val n = reader.read(bodyChars, readTotal, contentLength - readTotal)
                                if (n <= 0) break
                                readTotal += n
                            }
                            val reqBody = String(bodyChars, 0, readTotal)

                            val replyText = when {
                                reqBody.contains("Alice Turn 2") -> {
                                    alice2BodyHolder[0] = reqBody
                                    alice2HitServer.countDown()
                                    "Alice Reply 2"
                                }
                                reqBody.contains("Alice Turn 1") -> {
                                    alice1HitServer.countDown()
                                    releaseAlice1Response.await(5, TimeUnit.SECONDS)
                                    "Alice Reply 1"
                                }
                                else -> "Bob Reply 1"
                            }

                            val json = """{"choices":[{"message":{"role":"assistant","content":"$replyText"}}]}"""
                            val jsonBytes = json.toByteArray(StandardCharsets.UTF_8)
                            val respHeader = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${jsonBytes.size}\r\nConnection: close\r\n\r\n"
                            val out = sock.getOutputStream()
                            out.write(respHeader.toByteArray(StandardCharsets.US_ASCII))
                            out.write(jsonBytes)
                            out.flush()

                            if (replyText == "Bob Reply 1") bobCompleted.countDown()
                            if (replyText == "Alice Reply 2") alice2Completed.countDown()
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }

        try {
            prefs.setEnableByokReplies(true)
            prefs.saveOpenApiSource(Constants.CUSTOM_AI_PROVIDER_NAME)
            prefs.saveCustomOpenAIApiUrl("http://127.0.0.1:${serverSocket.localPort}/")
            prefs.saveSelectedOpenAIModel("mock-model")
            prefs.saveOpenAIApiKeySecurely("sk-mock-valid-key-123456")

            val aliceSbn1 = buildSbn(sender = "Alice", message = "Alice Turn 1", id = 525, timestampMs = 1_750_000_001_000L)
            val aliceSbn2 = buildSbn(sender = "Alice", message = "Alice Turn 2", id = 526, timestampMs = 1_750_000_002_000L)
            val bobSbn1 = buildSbn(sender = "Bob", message = "Bob Turn 1", id = 527, timestampMs = 1_750_000_003_000L)

            invokePrepareAndFetchAiReply(aliceSbn1, "Alice Turn 1")
            assertTrue(awaitLatchWithMainLooper(alice1HitServer, 5_000L))

            // While Alice Turn 1 HTTP request is still open: enqueue Alice Turn 2 and Bob Turn 1
            invokePrepareAndFetchAiReply(aliceSbn2, "Alice Turn 2")
            invokePrepareAndFetchAiReply(bobSbn1, "Bob Turn 1")

            // Bob must finish WITHOUT waiting for Alice Turn 1, while Alice Turn 2 must still be waiting
            assertTrue("Bob must not block behind Alice", awaitLatchWithMainLooper(bobCompleted, 5_000L))
            assertFalse("Alice Turn 2 must wait until Alice Turn 1 finishes persistence", awaitLatchWithMainLooper(alice2HitServer, 200L))

            // Now let Alice Turn 1 HTTP response return; Turn 2 should run only AFTER Turn 1 assistant memory persisted
            releaseAlice1Response.countDown()
            assertTrue(awaitLatchWithMainLooper(alice2Completed, 5_000L))
            assertTrue(
                "Both Alice Turn 2 and Bob Turn 1 post-dispatch persistence should finish",
                awaitConditionWithMainLooper(5_000L) {
                    val dao = memoryDb.aiMemoryDao()
                    dao.getRecentMessagesDescSync("com.whatsapp", "alice", 10).size >= 4 &&
                        dao.getRecentMessagesDescSync("com.whatsapp", "bob", 10).size >= 2
                }
            )

            val turn2Body = alice2BodyHolder[0]
            assertNotNull(turn2Body)
            assertTrue("Turn 2 prompt must include Turn 1 incoming message", turn2Body!!.contains("Alice Turn 1"))
            assertTrue("Turn 2 prompt must include Turn 1 assistant reply", turn2Body.contains("Alice Reply 1"))

            withContext(Dispatchers.IO) {
                val dao = memoryDb.aiMemoryDao()
                val aliceHist = dao.getRecentMessagesDescSync("com.whatsapp", "alice", 10).asReversed()
                assertEquals(
                    listOf("Alice Turn 1", "Alice Reply 1", "Alice Turn 2", "Alice Reply 2"),
                    aliceHist.map { it.content }
                )
                val bobHist = dao.getRecentMessagesDescSync("com.whatsapp", "bob", 10).asReversed()
                assertEquals(listOf("Bob Turn 1", "Bob Reply 1"), bobHist.map { it.content })
            }
        } finally {
            serverRunning.set(false)
            runCatching { serverSocket.close() }
            serverPool.shutdownNow()
        }
    }

    // --- F-06: Outbound sanitization when notesEnabled = false & repeated pending note dedup ---

    @Test
    fun `F-06 outbound sanitization strips markers when notes disabled and deduplicates pending notes across turns`() = runBlocking {
        // Notes disabled: strip closed, unclosed, malformed tags, keep normal brackets, no DEFAULT_NOTE_CONFIRMATION
        val strippedTagOnly = AiPromptAndPolicyHelper.parseAndStripNotes("[[SAVE_NOTE: Call me]]", false)
        assertEquals("", strippedTagOnly.cleanReply)
        assertTrue(strippedTagOnly.extractedNotes.isEmpty())

        val unclosedAndBrackets = AiPromptAndPolicyHelper.parseAndStripNotes(
            "Use [Option A] or arr[0]. [[INTERNAL_TAG]] [SAVE_NOTE: bad] [[SAVE_NOTE: cut off",
            false
        )
        assertEquals("Use [Option A] or arr[0].", unclosedAndBrackets.cleanReply)
        assertTrue(unclosedAndBrackets.extractedNotes.isEmpty())

        // Notes enabled: duplicate pending note across consecutive DISPATCHED turns does not insert a 2nd pending row
        setReplyManager(ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> }))
        val s1 = buildSbn(sender = "Alice", message = "Remind me at 5", id = 530)
        val s2 = buildSbn(sender = "Alice", message = "Did you note 5 PM?", id = 531)
        val r1 = invokeHandleSuccess(s1, "Got it!\n[[SAVE_NOTE:   Call Alice   at 5 PM ]]")
        val r2 = invokeHandleSuccess(s2, "Yes!\n[[SAVE_NOTE: call alice at 5 pm]]")
        awaitMemory()
        assertTrue(r1.isDispatched())
        assertTrue(r2.isDispatched())

        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            val pending = dao.getPendingNotesForSenderKeySync("com.whatsapp", "alice", 10)
            assertEquals(1, pending.size)

            // Mark completed -> same reminder CAN be saved again later
            dao.setNoteCompleted(pending[0].id, true)
        }

        val s3 = buildSbn(sender = "Alice", message = "Add it again", id = 532)
        val r3 = invokeHandleSuccess(s3, "Re-added!\n[[SAVE_NOTE: Call Alice at 5 PM]]")
        awaitMemory()
        assertTrue(r3.isDispatched())
        withContext(Dispatchers.IO) {
            assertEquals(1, memoryDb.aiMemoryDao().getPendingNotesForSenderKeySync("com.whatsapp", "alice", 10).size)
            assertEquals(2, memoryDb.aiMemoryDao().getTotalNoteCountSync())
        }
    }

    // --- F-07: Notification event key consistency & legacy dedup ---

    @Test
    fun `F-07 incoming_event_key uses authoritative evt key and deduplicates legacy in_ rows`() = runBlocking {
        setReplyManager(ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> }))
        val sbn = buildSbn(sender = "Alice", message = "Event key check", id = 540, timestampMs = 1_750_000_000_540L)
        val expectedEvtKey = NotificationEventKeyBuilder.buildEventKey(sbn, "Event key check")!!
        assertTrue(expectedEvtKey.startsWith("evt_"))

        val res = invokeHandleSuccess(sbn, "Checked!")
        awaitMemory()
        assertTrue(res.isDispatched())

        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            val msgs = dao.getRecentMessagesDescSync("com.whatsapp", "alice", 10).asReversed()
            assertEquals(expectedEvtKey, msgs.first { it.role == "user" }.incomingEventKey)

            // Now insert a legacy row using legacy "in_..." key for Bob and verify new evt_ write for same notification skips duplicate user message
            val bobSbn = buildSbn(sender = "Bob", message = "Legacy event", id = 541, timestampMs = 1_750_000_000_541L)
            val legacyKey = AiPromptAndPolicyHelper.buildLegacyIncomingEventKey(
                bobSbn.key, bobSbn.postTime, bobSbn.notification.`when`, "com.whatsapp", "bob", "Legacy event"
            )
            assertTrue(legacyKey.startsWith("in_"))
            val repo = AiMemoryRepository(dao)
            assertTrue(
                repo.recordIncomingUserMessage("com.whatsapp", "bob", "Bob", "Legacy event", legacyKey, 20, 1000L)
            )
            // Recording same incoming with new evt_ key + legacyIncomingEventKey parameter must deduplicate cleanly
            assertFalse(
                repo.recordIncomingUserMessage(
                    "com.whatsapp",
                    "bob",
                    "Bob",
                    "Legacy event",
                    NotificationEventKeyBuilder.buildEventKey(bobSbn, "Legacy event"),
                    20,
                    2000L,
                    legacyKey
                )
            )
            assertEquals(1, dao.getRecentMessagesDescSync("com.whatsapp", "bob", 10).size)
        }
    }

    // --- F-08: Pre-Retrofit / policy fallback incoming logging & RULE_AI reply-log separation ---

    @Test
    fun `F-08 fallback incoming message preserved on policy block and missing key, skipped on group filter, and RULE_AI skips ai_reply_logs`() = runBlocking {
        setReplyManager(ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> }))

        // 1. RULE_AI dispatch saves memory/notes without consuming global ai_reply_logs budget
        val ruleSbn = buildSbn(sender = "Alice", message = "VIP question", id = 550)
        val ruleRes = invokeHandleSuccess(
            ruleSbn,
            "VIP answer\n[[SAVE_NOTE: VIP note]]",
            historySourceLabel = "VIP • ${context.getString(R.string.history_source_ai)}"
        )
        awaitMemory()
        assertTrue(ruleRes.isDispatched())

        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            assertEquals(2, dao.getRecentMessagesDescSync("com.whatsapp", "alice", 10).size)
            assertEquals(1, dao.getNotesByAttemptIdSync(ruleRes.executionId).size)
            assertEquals(0, dao.countReplyLogsByAttemptIdSync(ruleRes.executionId))
            assertNull(dao.getLastAiReplyTimeSync("com.whatsapp", "alice"))
        }

        // 2. Missing API key fallback via prepareAndFetchAiReply records incoming user turn for identifiable sender
        prefs.saveOpenAIApiKeySecurely("")
        prefs.setAiGroupFilterMode("DIRECT_ONLY")
        val missingKeySbn = buildSbn(sender = "Alice", message = "Fallback incoming", id = 551, isGroup = false)
        invokePrepareAndFetchAiReply(missingKeySbn, "Fallback incoming", "Fallback")

        // 3. BLOCKED_GROUP_FILTER does NOT record incoming user turn
        val blockedGroupSbn = buildSbn(sender = "GroupChat", message = "Filtered group msg", id = 552, isGroup = true)
        invokePrepareAndFetchAiReply(blockedGroupSbn, "Filtered group msg", "Fallback")
        awaitMemory()

        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            assertTrue(dao.getRecentMessagesDescSync("com.whatsapp", "alice", 10).any { it.content == "Fallback incoming" })
            assertTrue(dao.getRecentMessagesDescSync("com.whatsapp", "group:groupchat", 10).isEmpty())
        }

        // 4. BLOCKED_COOLDOWN records incoming user turn cleanly
        withContext(Dispatchers.IO) {
            memoryDb.aiMemoryDao().insertReplyLogSync(
                AiReplyLog(packageName = "com.whatsapp", senderKey = "alice", repliedAt = System.currentTimeMillis(), attemptId = "att-recent-cool")
            )
        }
        prefs.setAiCooldownSeconds(300)
        val cooledSbn = buildSbn(sender = "Alice", message = "During cooldown", id = 553, isGroup = false)
        invokePrepareAndFetchAiReply(cooledSbn, "During cooldown", "Cooldown fallback")
        awaitMemory()

        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            assertTrue(dao.getRecentMessagesDescSync("com.whatsapp", "alice", 10).any { it.content == "During cooldown" })
        }
    }
}
