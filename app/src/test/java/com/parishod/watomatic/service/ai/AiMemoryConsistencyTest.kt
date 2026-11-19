package com.parishod.watomatic.service.ai

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.parishod.watomatic.NotificationWear
import com.parishod.watomatic.model.ai.AiConversationMessage
import com.parishod.watomatic.model.ai.AiMemoryDatabase
import com.parishod.watomatic.model.ai.AiMemoryRepository
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.utils.DbUtils
import com.parishod.watomatic.network.OpenAIService
import com.parishod.watomatic.network.model.openai.Choice
import com.parishod.watomatic.network.model.openai.OpenAIModelsResponse
import com.parishod.watomatic.network.model.openai.OpenAIRequest
import com.parishod.watomatic.network.model.openai.OpenAIResponse
import com.parishod.watomatic.network.model.openai.ResponseMessage
import com.parishod.watomatic.service.NotificationService
import com.parishod.watomatic.service.reply.ReplyExecutionManager
import com.parishod.watomatic.service.reply.ReplyExecutionResult
import com.parishod.watomatic.service.reply.ReplyExecutionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
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
import org.robolectric.annotation.Config
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AiMemoryConsistencyTest {

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
        val encryptedStore = context.getSharedPreferences("test_ai_consistency_enc", Context.MODE_PRIVATE)
        encryptedStore.edit().clear().commit()
        PreferencesManager.setEncryptedPrefsProviderForTesting { encryptedStore }
        prefs = PreferencesManager.getPreferencesInstance(context)

        AiMemoryDatabase.resetInstance()
        context.deleteDatabase(AiMemoryDatabase.DB_NAME)
        MessageLogsDB.resetInstance()
        MessageLogsDB.getInstance(context).clearAllTables()

        memoryDb = AiMemoryDatabase.getInstance(context)
        logsDb = MessageLogsDB.getInstance(context)
        service = Robolectric.buildService(NotificationService::class.java).create().get()
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
                AiMemoryDatabase.resetInstance()
                context.deleteDatabase(AiMemoryDatabase.DB_NAME)
                MessageLogsDB.resetInstance()
                context.deleteDatabase("message_logs_db")
            }.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("test_ai_consistency_enc", Context.MODE_PRIVATE).edit().clear().commit()
        PreferencesManager.resetInstance()
    }

    private fun buildNotificationSbn(
        pkg: String = "com.whatsapp",
        sender: String = "Alice",
        message: String = "Hey, can you call me at 5 PM?",
        id: Int = 100,
        timestampMs: Long = 1_700_000_000_000L + id
    ): StatusBarNotification {
        val replyPi = PendingIntent.getBroadcast(
            context,
            id,
            Intent("com.parishod.watomatic.TEST_AI_CONSISTENCY").setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val remoteInput = RemoteInput.Builder("reply_key")
            .setLabel("Reply")
            .setAllowFreeFormInput(true)
            .build()
        val action = NotificationCompat.Action.Builder(0, "Reply", replyPi)
            .addRemoteInput(remoteInput)
            .build()
        val notification = NotificationCompat.Builder(context, "test_channel")
            .setContentTitle(sender)
            .setContentText(message)
            .setWhen(timestampMs)
            .addAction(action)
            .build()
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

    private fun wearFromSbn(
        sbn: StatusBarNotification,
        includePendingIntent: Boolean = true
    ): NotificationWear {
        val remoteInput = RemoteInput.Builder("reply_key")
            .setLabel("Reply")
            .setAllowFreeFormInput(true)
            .build()
        val pi = if (includePendingIntent) {
            PendingIntent.getBroadcast(
                context,
                sbn.id + 5000,
                Intent("com.parishod.watomatic.TEST_AI_WEAR").setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        } else {
            null
        }
        return NotificationWear(sbn.packageName, pi, listOf(remoteInput), null, sbn.tag, sbn.id.toString())
    }

    private fun invokeHandleSuccessWithResult(
        sbn: StatusBarNotification,
        wear: NotificationWear,
        packageName: String = sbn.packageName,
        senderKey: String = AiPromptAndPolicyHelper.normalizeSenderKey(sbn.notification.extras.getString("android.title")),
        senderDisplay: String = sbn.notification.extras.getString("android.title") ?: "Alice",
        incomingMessage: String = sbn.notification.extras.getString("android.text") ?: "Hi",
        rawAiReply: String,
        memoryEnabled: Boolean = true,
        notesEnabled: Boolean = true,
        maxTurnsToKeep: Int = 20,
        historySourceLabel: String = "AI Reply"
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
            wear,
            packageName,
            senderKey,
            senderDisplay,
            incomingMessage,
            rawAiReply,
            memoryEnabled,
            notesEnabled,
            maxTurnsToKeep,
            historySourceLabel
        ) as ReplyExecutionResult
    }

    private fun setReplyManager(manager: ReplyExecutionManager) {
        NotificationService::class.java
            .getDeclaredMethod("setReplyExecutionManagerForTesting", ReplyExecutionManager::class.java)
            .apply { isAccessible = true }
            .invoke(service, manager)
    }

    private fun setMemoryRepo(repo: AiMemoryRepository) {
        NotificationService::class.java
            .getDeclaredMethod("setAiMemoryRepositoryForTesting", AiMemoryRepository::class.java)
            .apply { isAccessible = true }
            .invoke(service, repo)
    }

    private fun setTestDbUtils(dbUtils: DbUtils) {
        NotificationService::class.java
            .getDeclaredMethod("setDbUtilsForTesting", DbUtils::class.java)
            .apply { isAccessible = true }
            .invoke(service, dbUtils)
    }

    private fun awaitMemory() {
        val method = NotificationService::class.java.getDeclaredMethod("awaitAiMemoryIdleForTesting")
            .apply { isAccessible = true }
        method.invoke(service)
    }

    @Test
    fun `DISPATCHED reply persists assistant memory, reply log, and notes once correlated with attempt_id`() = runBlocking {
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> })
        setReplyManager(manager)

        val sbn = buildNotificationSbn(sender = "Alice", message = "Remind me to call Bob at 5 PM", id = 101)
        val result = invokeHandleSuccessWithResult(
            sbn = sbn,
            wear = wearFromSbn(sbn),
            rawAiReply = "Sure thing, I will pass that along.\n[[SAVE_NOTE: Call Bob at 5 PM]]"
        )
        awaitMemory()

        assertEquals(ReplyExecutionStatus.DISPATCHED, result.status)
        assertTrue(result.isDispatched())

        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            val history = dao.getRecentMessagesDescSync("com.whatsapp", "alice", 10).asReversed()
            assertEquals(2, history.size)
            assertEquals("user", history[0].role)
            assertEquals("Remind me to call Bob at 5 PM", history[0].content)
            assertEquals("assistant", history[1].role)
            assertEquals("Sure thing, I will pass that along.", history[1].content)
            assertEquals(result.executionId, history[1].attemptId)
            assertEquals(AiConversationMessage.STATUS_DISPATCHED, history[1].executionStatus)

            val notes = dao.getNotesByAttemptIdSync(result.executionId)
            assertEquals(1, notes.size)
            assertEquals("Call Bob at 5 PM", notes[0].noteText)
            assertEquals(result.executionId, notes[0].attemptId)

            val logs = dao.getReplyLogsByAttemptIdSync(result.executionId)
            assertEquals(1, logs.size)
        }
    }

    @Test
    fun `FAILED reply never persists assistant message, AI notes, or AI reply log`() = runBlocking {
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> })
        setReplyManager(manager)

        val sbn = buildNotificationSbn(sender = "Alice", message = "Meeting at 4 PM", id = 102)
        // Missing PendingIntent causes FAILED status before dispatch
        val result = invokeHandleSuccessWithResult(
            sbn = sbn,
            wear = wearFromSbn(sbn, includePendingIntent = false),
            rawAiReply = "See you at 4 PM!\n[[SAVE_NOTE: Meeting at 4 PM]]"
        )
        awaitMemory()

        assertEquals(ReplyExecutionStatus.FAILED, result.status)
        assertFalse(result.isDispatched())

        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            assertEquals(0, dao.countAssistantMessagesByAttemptIdSync(result.executionId))
            assertEquals(0, dao.countNotesByAttemptIdSync(result.executionId))
            assertEquals(0, dao.countReplyLogsByAttemptIdSync(result.executionId))
            assertEquals(0, dao.getTotalNoteCountSync())
            assertNull(dao.getLastAiReplyTimeSync("com.whatsapp", "alice"))

            // Incoming user message MUST remain available when reply dispatch fails
            val history = dao.getRecentMessagesDescSync("com.whatsapp", "alice", 10)
            assertEquals(1, history.size)
            assertEquals("user", history[0].role)
            assertEquals("Meeting at 4 PM", history[0].content)
        }
    }

    @Test
    fun `CANCELLED reply never persists assistant message, AI notes, or AI reply log`() = runBlocking {
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
            throw PendingIntent.CanceledException("expired")
        })
        setReplyManager(manager)

        val sbn = buildNotificationSbn(sender = "Bob", message = "Are we still on?", id = 103)
        val result = invokeHandleSuccessWithResult(
            sbn = sbn,
            wear = wearFromSbn(sbn),
            senderKey = "bob",
            senderDisplay = "Bob",
            rawAiReply = "Yes!\n[[SAVE_NOTE: Confirm schedule with Bob]]"
        )
        awaitMemory()

        assertEquals(ReplyExecutionStatus.CANCELLED, result.status)

        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            assertEquals(0, dao.countAssistantMessagesByAttemptIdSync(result.executionId))
            assertEquals(0, dao.getTotalNoteCountSync())
            assertNull(dao.getLastAiReplyTimeSync("com.whatsapp", "bob"))

            val history = dao.getRecentMessagesDescSync("com.whatsapp", "bob", 10)
            assertEquals(1, history.size)
            assertEquals("user", history[0].role)
            assertEquals("Are we still on?", history[0].content)
        }
    }

    @Test
    fun `UNKNOWN reply is not auto-retried, preserves uncertain state in reply_attempts, and creates no assistant memory or notes`() = runBlocking {
        val sendCount = AtomicInteger(0)
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
            sendCount.incrementAndGet()
            throw RuntimeException("Ambiguous binder failure")
        })
        setReplyManager(manager)

        val sbn = buildNotificationSbn(sender = "Carol", message = "Send contract tomorrow", id = 104)
        val result = invokeHandleSuccessWithResult(
            sbn = sbn,
            wear = wearFromSbn(sbn),
            senderKey = "carol",
            senderDisplay = "Carol",
            rawAiReply = "Will send tomorrow.\n[[SAVE_NOTE: Send contract tomorrow]]"
        )
        awaitMemory()

        assertEquals(ReplyExecutionStatus.UNKNOWN, result.status)
        assertTrue(result.isUnknown())
        assertEquals(1, sendCount.get())

        val persistedAttempt = logsDb.replyAttemptDao().getAttemptByIdSync(result.executionId)
        assertNotNull(persistedAttempt)
        assertEquals(ReplyExecutionStatus.UNKNOWN.name, persistedAttempt?.status)
        assertTrue(persistedAttempt?.dispatchAttempted == true)
        assertFalse(persistedAttempt?.dispatchedNormally == true)

        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            assertEquals(0, dao.countAssistantMessagesByAttemptIdSync(result.executionId))
            assertEquals(0, dao.getTotalNoteCountSync())
            assertNull(dao.getLastAiReplyTimeSync("com.whatsapp", "carol"))

            val history = dao.getRecentMessagesDescSync("com.whatsapp", "carol", 10)
            assertEquals(1, history.size)
            assertEquals("user", history[0].role)
        }
    }

    @Test
    fun `GENERATED and DISPATCHING states do not create confirmed assistant memory, notes, or logs`() = runBlocking {
        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            val repo = AiMemoryRepository(dao)

            val generatedResult = ReplyExecutionResult(
                executionId = "att-generated",
                status = ReplyExecutionStatus.GENERATED,
                dispatchAttempted = false,
                dispatchedNormally = false
            )
            val dispatchingResult = ReplyExecutionResult(
                executionId = "att-dispatching",
                status = ReplyExecutionStatus.DISPATCHING,
                dispatchAttempted = false,
                dispatchedNormally = false
            )

            assertFalse(
                repo.persistAuthoritativeAiReplyOutcome(
                    result = generatedResult,
                    packageName = "com.whatsapp",
                    senderKey = "alice",
                    senderDisplay = "Alice",
                    incomingMessage = "Msg 1",
                    incomingEventKey = "ev-gen-1",
                    assistantReply = "Draft 1",
                    extractedNotes = listOf("Draft Note 1"),
                    memoryEnabled = true,
                    notesEnabled = true,
                    keepCount = 20
                )
            )
            assertFalse(
                repo.persistAuthoritativeAiReplyOutcome(
                    result = dispatchingResult,
                    packageName = "com.whatsapp",
                    senderKey = "alice",
                    senderDisplay = "Alice",
                    incomingMessage = "Msg 2",
                    incomingEventKey = "ev-disp-2",
                    assistantReply = "Draft 2",
                    extractedNotes = listOf("Draft Note 2"),
                    memoryEnabled = true,
                    notesEnabled = true,
                    keepCount = 20
                )
            )

            assertEquals(0, dao.countAssistantMessagesByAttemptIdSync("att-generated"))
            assertEquals(0, dao.countAssistantMessagesByAttemptIdSync("att-dispatching"))
            assertEquals(0, dao.getTotalNoteCountSync())
            assertNull(dao.getLastAiReplyTimeSync("com.whatsapp", "alice"))
            // Only the two incoming user messages were preserved, zero assistant drafts
            val history = dao.getRecentMessagesDescSync("com.whatsapp", "alice", 10)
            assertTrue(history.all { it.role == "user" })
            assertEquals(2, history.size)
        }
    }

    @Test
    fun `repeated AI provider callbacks cannot dispatch twice or duplicate assistant memory or notes`() = runBlocking {
        val dispatchCount = AtomicInteger(0)
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
            dispatchCount.incrementAndGet()
        })
        setReplyManager(manager)

        val capturedCallbacks = mutableListOf<Callback<OpenAIResponse>>()
        val api = object : OpenAIService {
            override fun getChatCompletion(authorization: String, requestBody: OpenAIRequest): Call<OpenAIResponse> = error("unused")
            override fun getChatCompletion(url: String, auth: String, request: OpenAIRequest): Call<OpenAIResponse> =
                object : Call<OpenAIResponse> {
                    override fun enqueue(callback: Callback<OpenAIResponse>) {
                        capturedCallbacks.add(callback)
                    }
                    override fun isExecuted(): Boolean = false
                    override fun clone(): Call<OpenAIResponse> = this
                    override fun isCanceled(): Boolean = false
                    override fun cancel() {}
                    override fun execute(): Response<OpenAIResponse> = error("unused")
                    override fun request(): Request = Request.Builder().url("https://localhost/").build()
                    override fun timeout(): Timeout = Timeout.NONE
                }
            override fun getClaudeCompletion(url: String, headers: Map<String, String>, request: JsonObject): Call<JsonObject> = error("unused")
            override fun getGeminiCompletion(url: String, apiKey: String, request: JsonObject): Call<JsonObject> = error("unused")
            override fun getModels(authorization: String): Call<OpenAIModelsResponse> = error("unused")
            override fun getModels(headers: Map<String, String>): Call<OpenAIModelsResponse> = error("unused")
            override fun getModelsDynamic(url: String, headers: Map<String, String>): Call<JsonElement> = error("unused")
        }

        val okResponse = Response.success(
            OpenAIResponse().apply {
                choices = listOf(
                    Choice().apply {
                        message = ResponseMessage().apply {
                            role = "assistant"
                            content = "Reply once!\n[[SAVE_NOTE: Remember callback once]]"
                        }
                    }
                )
            }
        )

        val fetchOpenAi = NotificationService::class.java.getDeclaredMethod(
            "fetchOpenAiCompatibleReply",
            PreferencesManager::class.java, OpenAIService::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            String::class.java, List::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, String::class.java, StatusBarNotification::class.java,
            NotificationWear::class.java, String::class.java
        ).apply { isAccessible = true }

        val sbn = buildNotificationSbn(sender = "Alice", message = "Hello twice?", id = 105)
        fetchOpenAi.invoke(
            service, prefs, api, "https://api.openai.com/", "sk-test",
            "gpt-4o-mini", "sys", "com.whatsapp", "alice", "Alice",
            "Hello twice?", emptyList<AiConversationMessage>(), true, true, 20, "AI",
            sbn, wearFromSbn(sbn), "Fallback"
        )
        assertEquals(1, capturedCallbacks.size)
        val dummyCall = object : Call<OpenAIResponse> {
            override fun enqueue(callback: Callback<OpenAIResponse>) {}
            override fun isExecuted(): Boolean = true
            override fun clone(): Call<OpenAIResponse> = this
            override fun isCanceled(): Boolean = false
            override fun cancel() {}
            override fun execute(): Response<OpenAIResponse> = okResponse
            override fun request(): Request = Request.Builder().url("https://localhost/").build()
            override fun timeout(): Timeout = Timeout.NONE
        }

        // Fire callback twice
        capturedCallbacks[0].onResponse(dummyCall, okResponse)
        capturedCallbacks[0].onResponse(dummyCall, okResponse)
        awaitMemory()

        assertEquals(1, dispatchCount.get())
        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            val history = dao.getRecentMessagesDescSync("com.whatsapp", "alice", 10).asReversed()
            assertEquals(2, history.size)
            assertEquals("user", history[0].role)
            assertEquals("assistant", history[1].role)
            assertEquals(1, dao.getTotalNoteCountSync())
        }
    }

    @Test
    fun `same attempt_id prevents duplicate assistant messages and duplicate AI notes across retries and concurrency`() = runBlocking {
        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            val repo = AiMemoryRepository(dao)
            val attemptId = "concurrent-attempt-uuid-777"
            val result = ReplyExecutionResult(
                executionId = attemptId,
                status = ReplyExecutionStatus.DISPATCHED,
                dispatchAttempted = true,
                dispatchedNormally = true,
                timestampMs = 1_700_000_010_000L
            )

            val threads = 8
            val pool = Executors.newFixedThreadPool(threads)
            val gate = CountDownLatch(1)
            try {
                val futures = (1..threads).map {
                    pool.submit<Boolean> {
                        assertTrue(gate.await(5, TimeUnit.SECONDS))
                        repo.persistAuthoritativeAiReplyOutcome(
                            result = result,
                            packageName = "com.whatsapp",
                            senderKey = "alice",
                            senderDisplay = "Alice",
                            incomingMessage = "Book demo & call back",
                            incomingEventKey = "ev-concurrent-777",
                            assistantReply = "Booked!",
                            extractedNotes = listOf("Book demo", "Call back", "Book demo"),
                            memoryEnabled = true,
                            notesEnabled = true,
                            keepCount = 20,
                            timestampMs = 1_700_000_010_000L
                        )
                    }
                }
                gate.countDown()
                val outcomes = futures.map { it.get(5, TimeUnit.SECONDS) }
                assertEquals(1, outcomes.count { it })
            } finally {
                pool.shutdownNow()
            }

            assertEquals(1, dao.countAssistantMessagesByAttemptIdSync(attemptId))
            assertEquals(2, dao.countMessagesByIncomingEventKeySync("ev-concurrent-777").let { dao.getRecentMessagesDescSync("com.whatsapp", "alice", 10).size })
            assertEquals(2, dao.countNotesByAttemptIdSync(attemptId)) // 2 unique notes, deduplicated
            assertEquals(1, dao.countReplyLogsByAttemptIdSync(attemptId))
        }
    }

    @Test
    fun `AI notes filter sensitive tokens, internal errors, and hidden markers`() = runBlocking {
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> })
        setReplyManager(manager)

        val sbn = buildNotificationSbn(sender = "Alice", message = "Check settings", id = 106)
        val result = invokeHandleSuccessWithResult(
            sbn = sbn,
            wear = wearFromSbn(sbn),
            rawAiReply = "Checked!\n" +
                "[[SAVE_NOTE: Bearer sk-live-secret-token-999999]]\n" +
                "[[SAVE_NOTE: HTTP 500 (Provider server error)]]\n" +
                "[[SAVE_NOTE: Call Alice about renewal [[INTERNAL_DEBUG]]]]"
        )
        awaitMemory()

        assertTrue(result.isDispatched())
        withContext(Dispatchers.IO) {
            val notes = memoryDb.aiMemoryDao().getNotesByAttemptIdSync(result.executionId)
            assertEquals(1, notes.size)
            assertEquals("Call Alice about renewal", notes[0].noteText)
            assertFalse(notes[0].noteText.contains("INTERNAL_DEBUG"))
            assertFalse(notes[0].noteText.contains("sk-live"))
        }
    }

    @Test
    fun `future AI context excludes unsent generated replies and preserves incoming message ordering`() = runBlocking {
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> })
        setReplyManager(manager)

        // Turn 1 fails dispatch
        val sbnFail = buildNotificationSbn(sender = "Alice", message = "First question?", id = 107, timestampMs = 1_700_000_020_000L)
        val failRes = invokeHandleSuccessWithResult(
            sbn = sbnFail,
            wear = wearFromSbn(sbnFail, includePendingIntent = false),
            incomingMessage = "First question?",
            rawAiReply = "Unsent failed reply"
        )
        awaitMemory()
        assertEquals(ReplyExecutionStatus.FAILED, failRes.status)

        // Turn 2 succeeds dispatch
        val sbnOk = buildNotificationSbn(sender = "Alice", message = "Second question?", id = 108, timestampMs = 1_700_000_030_000L)
        val okRes = invokeHandleSuccessWithResult(
            sbn = sbnOk,
            wear = wearFromSbn(sbnOk),
            incomingMessage = "Second question?",
            rawAiReply = "Confirmed answer"
        )
        awaitMemory()
        assertEquals(ReplyExecutionStatus.DISPATCHED, okRes.status)

        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            // Even if a non-dispatched status row were ever present, DAO query filters it out
            dao.insertConversationMessageIgnoreSync(
                AiConversationMessage(
                    packageName = "com.whatsapp",
                    senderKey = "alice",
                    senderDisplayName = "Alice",
                    role = "assistant",
                    content = "Phantom FAILED row",
                    timestamp = 1_700_000_025_000L,
                    attemptId = "phantom-fail",
                    executionStatus = "FAILED"
                )
            )

            val contextAsc = dao.getRecentMessagesDescSync("com.whatsapp", "alice", 10).asReversed()
            assertEquals(3, contextAsc.size)
            assertEquals("user" to "First question?", contextAsc[0].role to contextAsc[0].content)
            assertEquals("user" to "Second question?", contextAsc[1].role to contextAsc[1].content)
            assertEquals("assistant" to "Confirmed answer", contextAsc[2].role to contextAsc[2].content)
            assertTrue(contextAsc.none { it.content.contains("Unsent failed reply") || it.content.contains("Phantom FAILED row") })

            val openAiMsgs = AiPromptAndPolicyHelper.buildOpenAiMessages("sys", contextAsc, "Third question?")
            assertEquals(5, openAiMsgs.size) // system + 3 history + current
            assertFalse(openAiMsgs.any { it.content.contains("Unsent failed reply") })
        }
    }

    @Test
    fun `per-app and per-sender memory isolation remains strict`() = runBlocking {
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> })
        setReplyManager(manager)

        val waAlice = buildNotificationSbn(pkg = "com.whatsapp", sender = "Alice", message = "WA Alice msg", id = 109)
        val tgAlice = buildNotificationSbn(pkg = "org.telegram.messenger", sender = "Alice", message = "TG Alice msg", id = 110)
        val waBob = buildNotificationSbn(pkg = "com.whatsapp", sender = "Bob", message = "WA Bob msg", id = 111)

        invokeHandleSuccessWithResult(waAlice, wearFromSbn(waAlice), packageName = "com.whatsapp", senderKey = "alice", senderDisplay = "Alice", incomingMessage = "WA Alice msg", rawAiReply = "WA Alice reply\n[[SAVE_NOTE: WA Alice note]]")
        invokeHandleSuccessWithResult(tgAlice, wearFromSbn(tgAlice), packageName = "org.telegram.messenger", senderKey = "alice", senderDisplay = "Alice", incomingMessage = "TG Alice msg", rawAiReply = "TG Alice reply\n[[SAVE_NOTE: TG Alice note]]")
        invokeHandleSuccessWithResult(waBob, wearFromSbn(waBob), packageName = "com.whatsapp", senderKey = "bob", senderDisplay = "Bob", incomingMessage = "WA Bob msg", rawAiReply = "WA Bob reply")
        awaitMemory()

        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            val waAliceHist = dao.getRecentMessagesDescSync("com.whatsapp", "alice", 10).asReversed()
            val tgAliceHist = dao.getRecentMessagesDescSync("org.telegram.messenger", "alice", 10).asReversed()
            val waBobHist = dao.getRecentMessagesDescSync("com.whatsapp", "bob", 10).asReversed()

            assertEquals(listOf("WA Alice msg", "WA Alice reply"), waAliceHist.map { it.content })
            assertEquals(listOf("TG Alice msg", "TG Alice reply"), tgAliceHist.map { it.content })
            assertEquals(listOf("WA Bob msg", "WA Bob reply"), waBobHist.map { it.content })

            assertEquals(listOf("WA Alice note"), dao.getPendingNotesForSenderSync("com.whatsapp", "Alice", 5).map { it.noteText })
            assertEquals(listOf("TG Alice note"), dao.getPendingNotesForSenderSync("org.telegram.messenger", "Alice", 5).map { it.noteText })
            assertTrue(dao.getPendingNotesForSenderSync("com.whatsapp", "Bob", 5).isEmpty())
        }
    }

    @Test
    fun `AI memory DB failure does not alter ReplyExecutionResult and MessageLogsDB failure does not fabricate AI memory success`() = runBlocking {
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> })
        setReplyManager(manager)

        // 1. Failing AiMemoryRepository must not alter DISPATCHED ReplyExecutionResult
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
                throw IllegalStateException("Simulated AI memory SQLite disk full")
            }
        }
        setMemoryRepo(throwingRepo)

        val sbnOk = buildNotificationSbn(sender = "Alice", message = "Hi", id = 112)
        val okResult = invokeHandleSuccessWithResult(sbnOk, wearFromSbn(sbnOk), rawAiReply = "All good!")
        awaitMemory()
        assertEquals(ReplyExecutionStatus.DISPATCHED, okResult.status)
        assertTrue(okResult.isDispatched())

        // 2. Failing DbUtils (MessageLogsDB) with FAILED dispatch must not fabricate AI memory success
        setMemoryRepo(AiMemoryRepository(memoryDb.aiMemoryDao()))
        val throwingDbUtils = object : DbUtils(context) {
            override fun recordAttemptResult(
                attempt: com.parishod.watomatic.service.reply.ReplyAttempt,
                result: ReplyExecutionResult,
                senderName: String?,
                incomingMsg: String?,
                sbn: StatusBarNotification?
            ) {
                throw IllegalStateException("Simulated MessageLogsDB error")
            }
        }
        setTestDbUtils(throwingDbUtils)

        val sbnFail = buildNotificationSbn(sender = "Dan", message = "Urgent?", id = 113)
        val failResult = invokeHandleSuccessWithResult(
            sbnFail,
            wearFromSbn(sbnFail, includePendingIntent = false),
            senderKey = "dan",
            senderDisplay = "Dan",
            incomingMessage = "Urgent?",
            rawAiReply = "Should never enter assistant memory\n[[SAVE_NOTE: Never persist]]"
        )
        awaitMemory()
        assertEquals(ReplyExecutionStatus.FAILED, failResult.status)

        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            assertEquals(0, dao.countAssistantMessagesByAttemptIdSync(failResult.executionId))
            assertEquals(0, dao.getTotalNoteCountSync())
        }
    }

    @Test
    fun `OpenAI, Claude, and Gemini provider flows preserve execution-to-memory consistency on success and HTTP or network failure`() = runBlocking {
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> })
        setReplyManager(manager)

        val okOpenAi = OpenAIResponse().apply {
            choices = listOf(Choice().apply {
                message = ResponseMessage().apply {
                    role = "assistant"
                    content = "OpenAI ok\n[[SAVE_NOTE: OpenAI note]]"
                }
            })
        }
        val okClaude = JsonObject().apply {
            add("content", JsonArray().apply { add(JsonObject().apply { addProperty("text", "Claude ok\n[[SAVE_NOTE: Claude note]]") }) })
        }
        val okGemini = JsonObject().apply {
            add("candidates", JsonArray().apply {
                add(JsonObject().apply {
                    add("content", JsonObject().apply {
                        add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", "Gemini ok\n[[SAVE_NOTE: Gemini note]]") }) })
                    })
                })
            })
        }

        val successApi = providerService(
            openAiCall = immediateResponseCall(Response.success(okOpenAi)),
            claudeCall = immediateResponseCall(Response.success(okClaude)),
            geminiCall = immediateResponseCall(Response.success(okGemini))
        )

        val fetchOpenAi = NotificationService::class.java.getDeclaredMethod(
            "fetchOpenAiCompatibleReply",
            PreferencesManager::class.java, OpenAIService::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            String::class.java, List::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, String::class.java, StatusBarNotification::class.java,
            NotificationWear::class.java, String::class.java
        ).apply { isAccessible = true }
        val fetchClaude = NotificationService::class.java.getDeclaredMethod(
            "fetchClaudeReply",
            PreferencesManager::class.java, OpenAIService::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            String::class.java, List::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, String::class.java, StatusBarNotification::class.java,
            NotificationWear::class.java, String::class.java
        ).apply { isAccessible = true }
        val fetchGemini = NotificationService::class.java.getDeclaredMethod(
            "fetchGeminiReply",
            PreferencesManager::class.java, OpenAIService::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            String::class.java, List::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, String::class.java, StatusBarNotification::class.java,
            NotificationWear::class.java, String::class.java
        ).apply { isAccessible = true }

        val s1 = buildNotificationSbn(sender = "OpenAiUser", message = "In 1", id = 201)
        val s2 = buildNotificationSbn(sender = "ClaudeUser", message = "In 2", id = 202)
        val s3 = buildNotificationSbn(sender = "GeminiUser", message = "In 3", id = 203)

        fetchOpenAi.invoke(service, prefs, successApi, "https://api.openai.com/", "sk-1", "gpt-4o-mini", "sys", "com.whatsapp", "openaiuser", "OpenAiUser", "In 1", emptyList<AiConversationMessage>(), true, true, 20, "AI", s1, wearFromSbn(s1), "Fallback")
        fetchClaude.invoke(service, prefs, successApi, "https://api.anthropic.com/", "sk-2", "claude-sonnet-4-20250514", "sys", "com.whatsapp", "claudeuser", "ClaudeUser", "In 2", emptyList<AiConversationMessage>(), true, true, 20, "Rule • AI", s2, wearFromSbn(s2), "Fallback")
        fetchGemini.invoke(service, prefs, successApi, "https://generativelanguage.googleapis.com/", "AIza3", "gemini-2.5-flash", "sys", "com.whatsapp", "geminiuser", "GeminiUser", "In 3", emptyList<AiConversationMessage>(), true, true, 20, "AI", s3, wearFromSbn(s3), "Fallback")

        // Also verify provider HTTP & network failures preserve incoming message without fabricating assistant reply
        val failApi = providerService(
            openAiCall = immediateResponseCall(Response.error(500, """{"err":"down"}""".toResponseBody("application/json".toMediaType()))),
            claudeCall = immediateFailureCall(IOException("claude timeout")),
            geminiCall = immediateResponseCall(Response.error(429, """{"err":"quota"}""".toResponseBody("application/json".toMediaType())))
        )
        val s4 = buildNotificationSbn(sender = "FailOpenAi", message = "Fail In 4", id = 204)
        val s5 = buildNotificationSbn(sender = "FailClaude", message = "Fail In 5", id = 205)
        fetchOpenAi.invoke(service, prefs, failApi, "https://api.openai.com/", "sk-1", "gpt-4o-mini", "sys", "com.whatsapp", "failopenai", "FailOpenAi", "Fail In 4", emptyList<AiConversationMessage>(), true, true, 20, "AI", s4, wearFromSbn(s4), "Static fallback")
        fetchClaude.invoke(service, prefs, failApi, "https://api.anthropic.com/", "sk-2", "claude-sonnet-4-20250514", "sys", "com.whatsapp", "failclaude", "FailClaude", "Fail In 5", emptyList<AiConversationMessage>(), true, true, 20, "AI", s5, wearFromSbn(s5), "Static fallback")
        awaitMemory()

        withContext(Dispatchers.IO) {
            val dao = memoryDb.aiMemoryDao()
            assertEquals(listOf("In 1", "OpenAI ok"), dao.getRecentMessagesDescSync("com.whatsapp", "openaiuser", 10).asReversed().map { it.content })
            assertEquals(listOf("In 2", "Claude ok"), dao.getRecentMessagesDescSync("com.whatsapp", "claudeuser", 10).asReversed().map { it.content })
            assertEquals(listOf("In 3", "Gemini ok"), dao.getRecentMessagesDescSync("com.whatsapp", "geminiuser", 10).asReversed().map { it.content })

            // Failed provider calls keep incoming user messages but do NOT fabricate assistant replies or AI notes
            assertEquals(listOf("user" to "Fail In 4"), dao.getRecentMessagesDescSync("com.whatsapp", "failopenai", 10).map { it.role to it.content })
            assertEquals(listOf("user" to "Fail In 5"), dao.getRecentMessagesDescSync("com.whatsapp", "failclaude", 10).map { it.role to it.content })
            assertNull(dao.getLastAiReplyTimeSync("com.whatsapp", "failopenai"))
            assertNull(dao.getLastAiReplyTimeSync("com.whatsapp", "failclaude"))

            val notes = dao.observeAllNotes().first()
            assertEquals(setOf("OpenAI note", "Claude note", "Gemini note"), notes.map { it.noteText }.toSet())
        }
    }

    private fun providerService(
        openAiCall: Call<OpenAIResponse>,
        claudeCall: Call<JsonObject>,
        geminiCall: Call<JsonObject>
    ): OpenAIService = object : OpenAIService {
        override fun getChatCompletion(authorization: String, requestBody: OpenAIRequest): Call<OpenAIResponse> = openAiCall
        override fun getChatCompletion(url: String, auth: String, request: OpenAIRequest): Call<OpenAIResponse> = openAiCall
        override fun getClaudeCompletion(url: String, headers: Map<String, String>, request: JsonObject): Call<JsonObject> = claudeCall
        override fun getGeminiCompletion(url: String, apiKey: String, request: JsonObject): Call<JsonObject> = geminiCall
        override fun getModels(authorization: String): Call<OpenAIModelsResponse> = error("unused")
        override fun getModels(headers: Map<String, String>): Call<OpenAIModelsResponse> = error("unused")
        override fun getModelsDynamic(url: String, headers: Map<String, String>): Call<JsonElement> = error("unused")
    }

    private fun <T> immediateResponseCall(response: Response<T>): Call<T> = object : Call<T> {
        override fun enqueue(callback: Callback<T>) = callback.onResponse(this, response)
        override fun isExecuted(): Boolean = true
        override fun clone(): Call<T> = this
        override fun isCanceled(): Boolean = false
        override fun cancel() {}
        override fun execute(): Response<T> = response
        override fun request(): Request = Request.Builder().url("https://localhost/").build()
        override fun timeout(): Timeout = Timeout.NONE
    }

    private fun <T> immediateFailureCall(error: Throwable): Call<T> = object : Call<T> {
        override fun enqueue(callback: Callback<T>) = callback.onFailure(this, error)
        override fun isExecuted(): Boolean = true
        override fun clone(): Call<T> = this
        override fun isCanceled(): Boolean = false
        override fun cancel() {}
        override fun execute(): Response<T> = error("unused")
        override fun request(): Request = Request.Builder().url("https://localhost/").build()
        override fun timeout(): Timeout = Timeout.NONE
    }
}
