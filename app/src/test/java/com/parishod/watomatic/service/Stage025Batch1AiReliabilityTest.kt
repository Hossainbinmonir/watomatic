package com.parishod.watomatic.service

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
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.parishod.watomatic.NotificationWear
import com.parishod.watomatic.R
import com.parishod.watomatic.model.ai.AiConversationMessage
import com.parishod.watomatic.model.ai.AiMemoryDatabase
import com.parishod.watomatic.model.ai.AiMemoryRepository
import com.parishod.watomatic.model.ai.PendingPostDispatchMemoryStore
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.utils.Constants
import com.parishod.watomatic.model.utils.DbUtils
import com.parishod.watomatic.network.OpenAIService
import com.parishod.watomatic.network.model.openai.OpenAIModelsResponse
import com.parishod.watomatic.network.model.openai.OpenAIRequest
import com.parishod.watomatic.network.model.openai.OpenAIResponse
import com.parishod.watomatic.service.ai.AiPromptAndPolicyHelper
import com.parishod.watomatic.service.reply.NotificationEventKeyBuilder
import com.parishod.watomatic.service.reply.ReplyAttempt
import com.parishod.watomatic.service.reply.ReplyExecutionManager
import com.parishod.watomatic.service.reply.ReplyExecutionResult
import com.parishod.watomatic.service.reply.ReplyExecutionStatus
import com.parishod.watomatic.service.reply.ReplySourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.Timeout
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Stage025Batch1AiReliabilityTest {

    private lateinit var context: Context
    private lateinit var prefs: PreferencesManager
    private lateinit var logsDb: MessageLogsDB
    private lateinit var memoryDb: AiMemoryDatabase
    private lateinit var dbUtils: DbUtils
    private val createdServices = mutableListOf<NotificationService>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferencesManager.resetInstance()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        val enc = context.getSharedPreferences("test_stage025_b1_enc", Context.MODE_PRIVATE)
        enc.edit().clear().commit()
        PreferencesManager.setEncryptedPrefsProviderForTesting { enc }
        prefs = PreferencesManager.getPreferencesInstance(context)
        prefs.setServicePref(true)
        prefs.saveEnabledApps("com.whatsapp", true)
        prefs.setEnableByokReplies(true)
        prefs.setAiMemoryEnabled(true)
        prefs.setAiAutoNotesEnabled(true)
        prefs.setAiGroupFilterMode("BOTH")
        prefs.setAppendWatomaticAttribution(false)

        PendingPostDispatchMemoryStore.clearForTesting(context)
        MessageLogsDB.resetInstance()
        context.deleteDatabase(Constants.LOGS_DB_NAME)
        AiMemoryDatabase.resetInstance()
        context.deleteDatabase(AiMemoryDatabase.DB_NAME)

        logsDb = MessageLogsDB.getInstance(context)
        memoryDb = AiMemoryDatabase.getInstance(context)
        dbUtils = DbUtils(context)
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        PendingPostDispatchMemoryStore.setForceStageFailureForTesting(false)
        for (svc in createdServices) {
            runCatching { svc.onDestroy() }
            runCatching { svc.awaitAiMemoryIdleForTesting() }
        }
        createdServices.clear()
        val cleanupExec = Executors.newSingleThreadExecutor()
        try {
            cleanupExec.submit {
                PendingPostDispatchMemoryStore.clearForTesting(context)
                MessageLogsDB.resetInstance()
                context.deleteDatabase(Constants.LOGS_DB_NAME)
                AiMemoryDatabase.resetInstance()
                context.deleteDatabase(AiMemoryDatabase.DB_NAME)
            }.get(15, TimeUnit.SECONDS)
        } finally {
            cleanupExec.shutdownNow()
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("test_stage025_b1_enc", Context.MODE_PRIVATE).edit().clear().commit()
        PreferencesManager.resetInstance()
        ShadowLog.clear()
    }

    private fun createManagedService(configureBeforeCreate: (NotificationService) -> Unit = {}): NotificationService {
        val controller: ServiceController<NotificationService> =
            Robolectric.buildService(NotificationService::class.java)
        val svc = controller.get()
        configureBeforeCreate(svc)
        controller.create()
        createdServices.add(svc)
        return svc
    }

    private fun buildSbn(
        pkg: String = "com.whatsapp",
        sender: String? = "Alice",
        message: String? = "Hello there",
        id: Int = 800,
        isGroup: Boolean = false,
        whenMs: Long = System.currentTimeMillis()
    ): StatusBarNotification {
        val replyPi = PendingIntent.getBroadcast(
            context,
            id,
            Intent("com.parishod.watomatic.STAGE025_B1_DISPATCH").setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val ri = RemoteInput.Builder("reply_key").setLabel("Reply").setAllowFreeFormInput(true).build()
        val action = NotificationCompat.Action.Builder(0, "Reply", replyPi).addRemoteInput(ri).build()
        val builder = NotificationCompat.Builder(context, "ch")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setWhen(whenMs)
            .addAction(action)
        if (sender != null) builder.setContentTitle(sender)
        if (message != null) builder.setContentText(message)
        val notification = builder.build().apply {
            extras.putBoolean("android.isGroupConversation", isGroup)
            if (isGroup && sender != null) {
                extras.putString("android.hiddenConversationTitle", sender)
            }
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
            whenMs
        )
    }

    private fun wearFromSbn(sbn: StatusBarNotification): NotificationWear {
        val ri = RemoteInput.Builder("reply_key").setLabel("Reply").setAllowFreeFormInput(true).build()
        val pi = PendingIntent.getBroadcast(
            context,
            sbn.id + 5000,
            Intent("com.parishod.watomatic.STAGE025_B1_WEAR").setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        return NotificationWear(sbn.packageName, pi, listOf(ri), sbn.notification.extras, sbn.tag, sbn.id.toString())
    }

    private fun invokeHandleSuccess(
        svc: NotificationService,
        sbn: StatusBarNotification,
        rawReply: String
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

        val senderDisplay = sbn.notification.extras.getString("android.title") ?: "Unknown"
        val isGroup = sbn.notification.extras.getBoolean("android.isGroupConversation", false)
        val senderKey = AiPromptAndPolicyHelper.normalizeSenderKey(senderDisplay, isGroup)
        val incoming = sbn.notification.extras.getCharSequence("android.text")?.toString() ?: ""

        return method.invoke(
            svc,
            prefs,
            sbn,
            wearFromSbn(sbn),
            sbn.packageName,
            senderKey,
            senderDisplay,
            incoming,
            rawReply,
            true,
            true,
            20,
            context.getString(R.string.history_source_ai)
        ) as ReplyExecutionResult
    }

    private fun invokePrepareAndFetchAiReply(
        svc: NotificationService,
        sbn: StatusBarNotification,
        incomingMessage: String,
        fallbackText: String = "Conservative fallback"
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
            svc,
            sbn,
            wearFromSbn(sbn),
            incomingMessage,
            fallbackText,
            null,
            context.getString(R.string.history_source_ai),
            true,
            null,
            null
        )
    }

    private fun invokeSendReply(svc: NotificationService, sbn: StatusBarNotification, claimToken: String? = null) {
        val method = NotificationService::class.java.getDeclaredMethod(
            "sendReply",
            StatusBarNotification::class.java,
            String::class.java
        ).apply { isAccessible = true }
        method.invoke(svc, sbn, claimToken)
    }

    private class RejectingExecutorService : AbstractExecutorService() {
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown(): Boolean = true
        override fun isTerminated(): Boolean = true
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
        override fun execute(command: Runnable) {
            throw RejectedExecutionException("Simulated executor rejection")
        }
    }

    // =====================================================================
    // F-025-01 — Post-Dispatch Memory Staging Gap
    // =====================================================================

    @Test
    fun `F-025-01 staged entry exists synchronously before async post-dispatch task runs`() = runBlocking {
        val svc = createManagedService()
        svc.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> })
        )

        // Defer execution manually so we can inspect PendingPostDispatchMemoryStore BEFORE async Room write begins
        val queuedTasks = mutableListOf<Runnable>()
        val deferredExecutor = object : AbstractExecutorService() {
            override fun shutdown() {}
            override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
            override fun isShutdown(): Boolean = false
            override fun isTerminated(): Boolean = false
            override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
            override fun execute(command: Runnable) {
                queuedTasks.add(command)
            }
        }
        svc.awaitAiMemoryIdleForTesting()
        svc.setPostDispatchPersistenceExecutorForTesting(deferredExecutor)

        val sbn = buildSbn(sender = "Alice", message = "Stage synchronously", id = 801)
        val result = invokeHandleSuccess(svc, sbn, "Sync stage reply\n[[SAVE_NOTE: Sync note]]")
        assertTrue(result.isDispatched())
        assertEquals(1, queuedTasks.size)

        // Even though queuedTasks[0] has NOT executed yet, the staged entry MUST already be persisted durably!
        val stagedBeforeAsync = PendingPostDispatchMemoryStore.getPendingEntries(context)
        assertEquals(1, stagedBeforeAsync.size)
        assertEquals(result.executionId, stagedBeforeAsync[0].attemptId)
        assertEquals("Sync stage reply", stagedBeforeAsync[0].assistantReply)
        assertEquals(listOf("Sync note"), stagedBeforeAsync[0].extractedNotes)

        withContext(Dispatchers.IO) {
            assertEquals(0, memoryDb.aiMemoryDao().countAssistantMessagesByAttemptIdSync(result.executionId))
        }

        // Running the queued task on a background thread now commits Room and cleans up the staged entry
        withContext(Dispatchers.IO) {
            queuedTasks.single().run()
            val dao = memoryDb.aiMemoryDao()
            assertEquals(1, dao.countAssistantMessagesByAttemptIdSync(result.executionId))
            assertEquals(1, dao.countNotesByAttemptIdSync(result.executionId))
            assertEquals(1, dao.countReplyLogsByAttemptIdSync(result.executionId))
            assertTrue(PendingPostDispatchMemoryStore.getPendingEntries(context).isEmpty())
        }
    }

    @Test
    fun `F-025-01 executor rejection after dispatch preserves staged entry and startup recovery commits once`() = runBlocking {
        val sendCount = AtomicInteger(0)
        val svc = createManagedService()
        svc.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )
        svc.awaitAiMemoryIdleForTesting()
        svc.setPostDispatchPersistenceExecutorForTesting(RejectingExecutorService())

        val sbn = buildSbn(sender = "Alice", message = "Executor rejected", id = 802)
        val result = invokeHandleSuccess(svc, sbn, "Reply before rejection\n[[SAVE_NOTE: Recovery note]]")
        assertTrue(result.isDispatched())
        assertEquals(1, sendCount.get())

        // Staged entry survives executor rejection with retryCount == 0
        val pending = PendingPostDispatchMemoryStore.getPendingEntries(context)
        assertEquals(1, pending.size)
        assertEquals(result.executionId, pending[0].attemptId)
        assertEquals(0, pending[0].retryCount)

        // Room DB does not have the assistant turn yet
        withContext(Dispatchers.IO) {
            assertEquals(0, memoryDb.aiMemoryDao().countAssistantMessagesByAttemptIdSync(result.executionId))
        }

        // Next service startup replays cleanly without re-dispatching reply
        val recoveredSvc = createManagedService()
        recoveredSvc.awaitAiMemoryIdleForTesting()

        assertEquals(1, sendCount.get())
        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            assertEquals(1, dao.countAssistantMessagesByAttemptIdSync(result.executionId))
            assertEquals(1, dao.countNotesByAttemptIdSync(result.executionId))
            assertEquals(1, dao.countReplyLogsByAttemptIdSync(result.executionId))
            assertTrue(PendingPostDispatchMemoryStore.getPendingEntries(context).isEmpty())
        }
    }

    @Test
    fun `F-025-01 staging failure logs explicit error and never retries or re-dispatches`() {
        val sendCount = AtomicInteger(0)
        val svc = createManagedService()
        svc.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )
        svc.awaitAiMemoryIdleForTesting()
        svc.setPostDispatchPersistenceExecutorForTesting(RejectingExecutorService())
        PendingPostDispatchMemoryStore.setForceStageFailureForTesting(true)

        val sbn = buildSbn(sender = "Alice", message = "Private text should not log", id = 803)
        val result = invokeHandleSuccess(svc, sbn, "Dispatched once")

        assertTrue(result.isDispatched())
        assertEquals(1, sendCount.get())
        assertTrue(PendingPostDispatchMemoryStore.getPendingEntries(context).isEmpty())

        val errorLogs = ShadowLog.getLogs().filter { it.type == android.util.Log.ERROR }.joinToString("\n") { it.msg }
        assertTrue("Expected explicit staging error log", errorLogs.contains("Unable to stage pending AI memory write") || errorLogs.contains("Failed synchronously staging"))
        assertTrue("Expected explicit scheduling error log without durable stage", errorLogs.contains("without durable stage"))
        assertFalse("Error logs must never leak incoming message content", errorLogs.contains("Private text should not log"))
        assertFalse("Error logs must never leak reply content", errorLogs.contains("Dispatched once"))
    }

    // =====================================================================
    // F-025-02 — BYOK Fallback Policy Bypass
    // =====================================================================

    @Test
    fun `F-025-02 BYOK enabled without API key enforces group restriction on DEFAULT fallback`() {
        prefs.setEnableByokReplies(true)
        prefs.saveOpenAIApiKeySecurely("") // shouldUseAI == false
        prefs.setGroupReplyPref(false)
        prefs.setAiGroupFilterMode("BOTH")

        val sendCount = AtomicInteger(0)
        val svc = createManagedService()
        svc.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )

        val groupSbn = buildSbn(sender = "Family Group", message = "Anyone home?", id = 810, isGroup = true)
        val eventKey = NotificationEventKeyBuilder.buildEventKey(groupSbn)!!
        val token = "tok-grp-bypass-810"
        val acquire = dbUtils.tryAcquireNotificationClaim(eventKey, token, "com.whatsapp", "group:family group", groupSbn.key, System.currentTimeMillis(), false)
        assertTrue(acquire.acquired)

        invokeSendReply(svc, groupSbn, token)

        assertEquals("Group restriction MUST block DEFAULT fallback when BYOK has no API key", 0, sendCount.get())
        assertEquals(0L, dbUtils.numReplies)
        assertEquals(null, logsDb.notificationDispatchClaimDao().getClaimByEventKeySync(eventKey))
    }

    @Test
    fun `F-025-02 BYOK enabled with PENDING_CONFIGURATION key enforces legacy cooldown on DEFAULT fallback`() {
        prefs.setEnableByokReplies(true)
        prefs.saveOpenAIApiKeySecurely("PENDING_CONFIGURATION")
        prefs.setGroupReplyPref(true)
        prefs.setAutoReplyDelay(60_000L)

        val sendCount = AtomicInteger(0)
        val svc = createManagedService()
        svc.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )

        // First direct reply succeeds and sets lastRepliedTime for "Bob"
        val firstSbn = buildSbn(sender = "Bob", message = "First ping", id = 811, isGroup = false, whenMs = System.currentTimeMillis() - 10_000L)
        invokeSendReply(svc, firstSbn)
        assertEquals(1, sendCount.get())

        // Second direct reply from Bob within 60s cooldown MUST be blocked by legacy cooldown policy
        val secondSbn = buildSbn(sender = "Bob", message = "Second ping during cooldown", id = 812, isGroup = false, whenMs = System.currentTimeMillis())
        val secondKey = NotificationEventKeyBuilder.buildEventKey(secondSbn)!!
        val token = "tok-cooldown-812"
        dbUtils.tryAcquireNotificationClaim(secondKey, token, "com.whatsapp", "bob", secondSbn.key, System.currentTimeMillis(), false)

        invokeSendReply(svc, secondSbn, token)
        assertEquals("Legacy cooldown MUST block DEFAULT fallback in BYOK mode", 1, sendCount.get())
        assertEquals(1L, dbUtils.numReplies)
        assertEquals(null, logsDb.notificationDispatchClaimDao().getClaimByEventKeySync(secondKey))
    }

    @Test
    fun `F-025-02 BYOK fallback allows valid direct message when cooldown is zero and preserves legacy path`() {
        prefs.setEnableByokReplies(true)
        prefs.saveOpenAIApiKeySecurely("")
        prefs.setGroupReplyPref(false)
        prefs.setAutoReplyDelay(0L)

        val sendCount = AtomicInteger(0)
        val svc = createManagedService()
        svc.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )

        val directSbn = buildSbn(sender = "Carol", message = "Valid direct message", id = 813, isGroup = false)
        invokeSendReply(svc, directSbn)
        assertEquals(1, sendCount.get())

        // Also verify non-BYOK legacy path continues working identically
        prefs.setEnableByokReplies(false)
        val nonByokSbn = buildSbn(sender = "Dave", message = "Legacy direct message", id = 814, isGroup = false)
        invokeSendReply(svc, nonByokSbn)
        assertEquals(2, sendCount.get())
        assertEquals(2L, dbUtils.numReplies)
    }

    @Test
    fun `F-025-02 valid BYOK key still routes group chat through AI policy even if legacy groupReply is off`() = runBlocking {
        val aiServerHit = CountDownLatch(1)
        val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        val running = AtomicBoolean(true)
        pool.execute {
            while (running.get()) {
                try {
                    val sock = serverSocket.accept()
                    sock.use { s ->
                        val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                        var len = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.lowercase().startsWith("content-length:")) {
                                len = line.substringAfter(":").trim().toIntOrNull() ?: 0
                            }
                        }
                        val buf = CharArray(len)
                        var read = 0
                        while (read < len) {
                            val n = reader.read(buf, read, len - read)
                            if (n <= 0) break
                            read += n
                        }
                        aiServerHit.countDown()
                        val body = """{"choices":[{"message":{"role":"assistant","content":"AI Group Reply"}}]}"""
                        val bytes = body.toByteArray(StandardCharsets.UTF_8)
                        s.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
                        )
                        s.getOutputStream().write(bytes)
                        s.getOutputStream().flush()
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
            prefs.saveOpenAIApiKeySecurely("sk-valid-key-123456")
            prefs.setGroupReplyPref(false) // legacy group toggle OFF
            prefs.setAiGroupFilterMode("BOTH") // AI group filter allows groups

            val dispatched = CountDownLatch(1)
            val svc = createManagedService()
            svc.setReplyExecutionManagerForTesting(
                ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                    dispatched.countDown()
                })
            )

            val groupSbn = buildSbn(sender = "Project Team", message = "Status update?", id = 815, isGroup = true)
            invokeSendReply(svc, groupSbn)

            assertTrue("AI request should run even when legacy groupReply is false", aiServerHit.await(5, TimeUnit.SECONDS))
            assertTrue("AI group reply should dispatch", dispatched.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                svc.awaitAiMemoryIdleForTesting()
                val persisted = withContext(Dispatchers.IO) {
                    val attempts = logsDb.replyAttemptDao().getAllAttemptsSync()
                    attempts.size == 1 &&
                        memoryDb.aiMemoryDao().countAssistantMessagesByAttemptIdSync(attempts[0].attemptId) == 1
                }
                if (persisted) break
            }
            val attempt = logsDb.replyAttemptDao().getAllAttemptsSync().single()
            assertEquals(ReplySourceType.GLOBAL_AI.name, attempt.sourceType)
        } finally {
            running.set(false)
            runCatching { serverSocket.close() }
            pool.shutdownNow()
        }
    }

    @Test
    fun `F-025-02 onNotificationPosted fallback in BYOK mode without key enforces group restriction end-to-end`() {
        prefs.setServicePref(true)
        prefs.saveEnabledApps("com.whatsapp", true)
        prefs.setEnableByokReplies(true)
        prefs.saveOpenAIApiKeySecurely("")
        prefs.setGroupReplyPref(false)
        prefs.setAiGroupFilterMode("BOTH")

        val sendCount = AtomicInteger(0)
        val svc = createManagedService()
        svc.awaitAiMemoryIdleForTesting()
        val claimAttempts = AtomicInteger(0)
        val claimReleases = AtomicInteger(0)
        svc.setDbUtilsForTesting(object : DbUtils(context) {
            override fun tryAcquireAuthorizedPreDispatchClaim(
                eventKey: String,
                claimToken: String,
                packageName: String,
                senderKey: String,
                notificationKey: String?,
                nowMs: Long,
                priorRetryCount: Int,
                maxRetries: Int
            ) = super.tryAcquireAuthorizedPreDispatchClaim(
                eventKey, claimToken, packageName, senderKey, notificationKey, nowMs, priorRetryCount, maxRetries
            ).also { claimAttempts.incrementAndGet() }

            override fun releaseUnusedNotificationClaim(eventKey: String, claimToken: String): Int {
                claimReleases.incrementAndGet()
                return super.releaseUnusedNotificationClaim(eventKey, claimToken)
            }
        })
        svc.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )

        val groupSbn = buildSbn(sender = "EndToEndGroup", message = "Bypass check", id = 816, isGroup = true)
        val eventKey = NotificationEventKeyBuilder.buildEventKey(groupSbn)!!
        svc.onNotificationPosted(groupSbn)
        svc.awaitRuleProcessorIdleForTesting()

        assertEquals(0, sendCount.get())
        assertEquals(0L, dbUtils.numReplies)
        assertEquals("Non-actionable group fallback must not churn persistent claims", 0, claimAttempts.get())
        assertEquals(0, claimReleases.get())
        assertNull(dbUtils.getNotificationClaim(eventKey))
    }

    // =====================================================================
    // F-025-03 — Startup Recovery Async Race
    // =====================================================================

    @Test
    fun `F-025-03 delayed startup replay completes before AI cooldown and memory lookup and avoids duplicate replay runs`() = runBlocking {
        val nowMs = System.currentTimeMillis()
        val priorAttemptId = "att-staged-prior-820"
        val priorEventKey = "evt-staged-prior-820"

        // Seed confirmed DISPATCHED attempt in MessageLogsDB + staged pending memory/note/replyLog
        withContext(Dispatchers.IO) {
            val attempt = ReplyAttempt(
                attemptId = priorAttemptId,
                sourceType = ReplySourceType.GLOBAL_AI,
                sourceLabel = "AI",
                packageName = "com.whatsapp",
                notificationKey = "notif-prior",
                replyText = "Prior AI answer",
                status = ReplyExecutionStatus.DISPATCHED,
                createdAtMs = nowMs - 2_000L,
                updatedAtMs = nowMs - 2_000L,
                eventKey = priorEventKey
            )
            val res = ReplyExecutionResult(priorAttemptId, ReplyExecutionStatus.DISPATCHED, true, true, null, "ok", nowMs - 2_000L)
            dbUtils.recordAttemptResult(attempt, res, "Alice", "Prior question", null)

            PendingPostDispatchMemoryStore.stage(
                context,
                PendingPostDispatchMemoryStore.PendingPostDispatchEntry(
                    attemptId = priorAttemptId,
                    statusName = ReplyExecutionStatus.DISPATCHED.name,
                    dispatchAttempted = true,
                    dispatchedNormally = true,
                    failureCategory = null,
                    packageName = "com.whatsapp",
                    senderKey = "alice",
                    senderDisplay = "Alice",
                    incomingMessage = "Prior question",
                    incomingEventKey = priorEventKey,
                    legacyIncomingEventKey = null,
                    assistantReply = "Prior AI answer",
                    extractedNotes = listOf("Remember prior note"),
                    memoryEnabled = true,
                    notesEnabled = true,
                    recordReplyLog = true,
                    keepCount = 20,
                    timestampMs = nowMs - 2_000L,
                    retryCount = 0,
                    createdAtMs = nowMs - 2_000L
                )
            )
        }

        // Configure 300s AI cooldown + skip-when-limit-reached, valid key, unreachable URL if LLM were ever mistakenly called
        prefs.setAiCooldownSeconds(300)
        prefs.setAiSkipWhenLimitReached(true)
        prefs.saveOpenAIApiKeySecurely("sk-valid-key-820")

        val replayHookEntered = CountDownLatch(1)
        val allowReplayFinish = CountDownLatch(1)
        val hookExecutions = AtomicInteger(0)

        val svc = createManagedService { instance ->
            instance.setStartupReplayTestHookForTesting {
                hookExecutions.incrementAndGet()
                replayHookEntered.countDown()
                allowReplayFinish.await(5, TimeUnit.SECONDS)
            }
        }
        val dispatchedCount = AtomicInteger(0)
        svc.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                dispatchedCount.incrementAndGet()
            })
        )

        // Repeated lifecycle recovery calls while in-flight must NOT schedule duplicate replays
        val ensureMethod = NotificationService::class.java.getDeclaredMethod("ensureStartupRecovery").apply { isAccessible = true }
        assertTrue(replayHookEntered.await(5, TimeUnit.SECONDS))
        ensureMethod.invoke(svc)
        ensureMethod.invoke(svc)

        // Incoming AI request arrives while startup replay is still blocked on allowReplayFinish
        val newSbn = buildSbn(sender = "Alice", message = "Follow-up during startup replay", id = 821, whenMs = nowMs)
        invokePrepareAndFetchAiReply(svc, newSbn, "Follow-up during startup replay")

        // While startup replay is still unfinished, AI worker must be waiting and must not have dispatched
        assertEquals(0, dispatchedCount.get())
        assertEquals(1, hookExecutions.get())

        // Release startup replay -> commits staged ai_reply_logs & memory & notes, then AI worker sees recovered 300s cooldown and blocks!
        allowReplayFinish.countDown()
        svc.awaitAiMemoryIdleForTesting()

        assertEquals(1, hookExecutions.get())
        assertEquals("Recovered cooldown MUST block second AI reply instead of bypassing via empty ai_reply_logs", 0, dispatchedCount.get())
        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            assertTrue(PendingPostDispatchMemoryStore.getPendingEntries(context).isEmpty())
            assertEquals(1, dao.countReplyLogsByAttemptIdSync(priorAttemptId))
            assertEquals(1, dao.countNotesByAttemptIdSync(priorAttemptId))
            val history = dao.getRecentMessagesDescSync("com.whatsapp", "alice", 10).asReversed()
            assertTrue(history.any { it.role == "assistant" && it.content == "Prior AI answer" })
            assertTrue(history.any { it.role == "user" && it.content == "Follow-up during startup replay" })
        }
    }

    @Test
    fun `F-025-03 startup recovery wait sees recovered prompt context and notes when cooldown allows`() = runBlocking {
        val nowMs = System.currentTimeMillis()
        val priorAttemptId = "att-staged-context-822"
        val priorEventKey = "evt-staged-context-822"

        withContext(Dispatchers.IO) {
            val attempt = ReplyAttempt(
                attemptId = priorAttemptId,
                sourceType = ReplySourceType.GLOBAL_AI,
                sourceLabel = "AI",
                packageName = "com.whatsapp",
                notificationKey = "notif-ctx",
                replyText = "Earlier assistant context",
                status = ReplyExecutionStatus.DISPATCHED,
                createdAtMs = nowMs - 5_000L,
                updatedAtMs = nowMs - 5_000L,
                eventKey = priorEventKey
            )
            dbUtils.recordAttemptResult(
                attempt,
                ReplyExecutionResult(priorAttemptId, ReplyExecutionStatus.DISPATCHED, true, true, null, "ok", nowMs - 5_000L),
                "Alice",
                "Earlier user question",
                null
            )
            PendingPostDispatchMemoryStore.stage(
                context,
                PendingPostDispatchMemoryStore.PendingPostDispatchEntry(
                    attemptId = priorAttemptId,
                    statusName = ReplyExecutionStatus.DISPATCHED.name,
                    dispatchAttempted = true,
                    dispatchedNormally = true,
                    failureCategory = null,
                    packageName = "com.whatsapp",
                    senderKey = "alice",
                    senderDisplay = "Alice",
                    incomingMessage = "Earlier user question",
                    incomingEventKey = priorEventKey,
                    legacyIncomingEventKey = null,
                    assistantReply = "Earlier assistant context",
                    extractedNotes = listOf("Flight departs Friday"),
                    memoryEnabled = true,
                    notesEnabled = true,
                    recordReplyLog = true,
                    keepCount = 20,
                    timestampMs = nowMs - 5_000L,
                    retryCount = 0,
                    createdAtMs = nowMs - 5_000L
                )
            )
        }

        val capturedBody = AtomicReference<String?>(null)
        val serverHit = CountDownLatch(1)
        val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        val running = AtomicBoolean(true)
        pool.execute {
            while (running.get()) {
                try {
                    val sock = serverSocket.accept()
                    sock.use { s ->
                        val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                        var len = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.lowercase().startsWith("content-length:")) {
                                len = line.substringAfter(":").trim().toIntOrNull() ?: 0
                            }
                        }
                        val buf = CharArray(len)
                        var read = 0
                        while (read < len) {
                            val n = reader.read(buf, read, len - read)
                            if (n <= 0) break
                            read += n
                        }
                        capturedBody.set(String(buf, 0, read))
                        serverHit.countDown()
                        val body = """{"choices":[{"message":{"role":"assistant","content":"Followup answer"}}]}"""
                        val bytes = body.toByteArray(StandardCharsets.UTF_8)
                        s.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
                        )
                        s.getOutputStream().write(bytes)
                        s.getOutputStream().flush()
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }

        try {
            prefs.setAiCooldownSeconds(0)
            prefs.saveOpenApiSource(Constants.CUSTOM_AI_PROVIDER_NAME)
            prefs.saveCustomOpenAIApiUrl("http://127.0.0.1:${serverSocket.localPort}/")
            prefs.saveSelectedOpenAIModel("mock-model")
            prefs.saveOpenAIApiKeySecurely("sk-valid-key-822")

            val allowReplayFinish = CountDownLatch(1)
            val replayEntered = CountDownLatch(1)
            val svc = createManagedService { instance ->
                instance.setStartupReplayTestHookForTesting {
                    replayEntered.countDown()
                    allowReplayFinish.await(5, TimeUnit.SECONDS)
                }
            }
            val dispatched = CountDownLatch(1)
            svc.setReplyExecutionManagerForTesting(
                ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                    dispatched.countDown()
                })
            )

            assertTrue(replayEntered.await(5, TimeUnit.SECONDS))
            val newSbn = buildSbn(sender = "Alice", message = "What about my flight?", id = 822, whenMs = nowMs)
            invokePrepareAndFetchAiReply(svc, newSbn, "What about my flight?")

            // LLM MUST NOT be called before startup replay finishes
            assertFalse(serverHit.await(150, TimeUnit.MILLISECONDS))

            allowReplayFinish.countDown()
            assertTrue("LLM request must proceed after startup replay finishes", serverHit.await(5, TimeUnit.SECONDS))
            assertTrue(dispatched.await(5, TimeUnit.SECONDS))
            svc.awaitAiMemoryIdleForTesting()

            val bodyStr = capturedBody.get()
            assertNotNull(bodyStr)
            assertTrue("Prompt must include recovered user question", bodyStr!!.contains("Earlier user question"))
            assertTrue("Prompt must include recovered assistant answer", bodyStr.contains("Earlier assistant context"))
            assertTrue("Prompt must include recovered pending note", bodyStr.contains("Flight departs Friday"))
        } finally {
            running.set(false)
            runCatching { serverSocket.close() }
            pool.shutdownNow()
        }
    }

    @Test
    fun `F-025-03 failed or timed-out startup recovery triggers conservative fallback instead of running LLM with stale context`() {
        val llmCalled = AtomicInteger(0)
        prefs.setEnableByokReplies(true)
        prefs.saveOpenApiSource(Constants.CUSTOM_AI_PROVIDER_NAME)
        prefs.saveCustomOpenAIApiUrl("http://127.0.0.1:9/")
        prefs.saveOpenAIApiKeySecurely("sk-valid-key-823")

        // 1. Recovery fails via exception -> aiSkipWhenLimitReached = false -> sends conservative AI_FALLBACK
        prefs.setAiSkipWhenLimitReached(false)
        val failSendCount = AtomicInteger(0)
        val failSvc = createManagedService { instance ->
            instance.setStartupReplayTestHookForTesting {
                llmCalled.get() // no-op reference
                throw IllegalStateException("Simulated startup replay failure")
            }
        }
        failSvc.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                failSendCount.incrementAndGet()
            })
        )
        failSvc.awaitAiMemoryIdleForTesting()
        val failSbn = buildSbn(sender = "Alice", message = "Message after failed recovery", id = 823)
        invokePrepareAndFetchAiReply(failSvc, failSbn, "Message after failed recovery", "Safe fallback")
        failSvc.awaitAiMemoryIdleForTesting()

        assertEquals(1, failSendCount.get())
        val attempt = logsDb.replyAttemptDao().getAllAttemptsSync().single()
        assertEquals(ReplySourceType.AI_FALLBACK.name, attempt.sourceType)
        assertEquals("Safe fallback", attempt.replyText)

        // 2. Recovery times out -> aiSkipWhenLimitReached = true -> skips safely and releases claim
        prefs.setAiSkipWhenLimitReached(true)
        val blockForever = CountDownLatch(1)
        val timeoutSendCount = AtomicInteger(0)
        val claimReleasedLatch = CountDownLatch(1)
        val timeoutSvc = createManagedService { instance ->
            instance.setStartupRecoveryWaitTimeoutMsForTesting(50L)
            instance.setStartupReplayTestHookForTesting {
                blockForever.await(5, TimeUnit.SECONDS)
            }
        }
        timeoutSvc.setDbUtilsForTesting(object : DbUtils(context) {
            override fun releaseUnusedNotificationClaim(eventKey: String, claimToken: String): Int {
                val released = super.releaseUnusedNotificationClaim(eventKey, claimToken)
                claimReleasedLatch.countDown()
                return released
            }
        })
        timeoutSvc.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                timeoutSendCount.incrementAndGet()
            })
        )
        try {
            val timeoutSbn = buildSbn(sender = "Bob", message = "Message while recovery stalls", id = 824)
            val eventKey = NotificationEventKeyBuilder.buildEventKey(timeoutSbn)!!
            val claimToken = "tok-timeout-824"
            dbUtils.tryAcquireNotificationClaim(eventKey, claimToken, "com.whatsapp", "bob", timeoutSbn.key, System.currentTimeMillis(), false)

            val prepareMethod = NotificationService::class.java.getDeclaredMethod(
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
            prepareMethod.invoke(
                timeoutSvc,
                timeoutSbn,
                wearFromSbn(timeoutSbn),
                "Message while recovery stalls",
                "Safe fallback",
                null,
                context.getString(R.string.history_source_ai),
                true,
                null,
                claimToken
            )

            assertTrue("Expected conservative skip to release unused claim on recovery timeout", claimReleasedLatch.await(5, TimeUnit.SECONDS))
            assertEquals("Conservative skip must NOT dispatch on timed-out recovery", 0, timeoutSendCount.get())
            assertTrue(
                "Expected explicit warning when falling back on unavailable startup recovery",
                ShadowLog.getLogs().any { it.msg.contains("Startup AI recovery unavailable before AI preparation") }
            )
        } finally {
            blockForever.countDown()
            timeoutSvc.onDestroy()
        }
    }
}
