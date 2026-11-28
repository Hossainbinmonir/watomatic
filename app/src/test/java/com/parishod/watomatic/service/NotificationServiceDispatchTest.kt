package com.parishod.watomatic.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.parishod.watomatic.NotificationWear
import com.parishod.watomatic.model.ai.AiConversationMessage
import com.parishod.watomatic.model.ai.AiMemoryDatabase
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.utils.DbUtils
import com.parishod.watomatic.network.OpenAIService
import com.parishod.watomatic.network.model.openai.OpenAIModelsResponse
import com.parishod.watomatic.network.model.openai.OpenAIRequest
import com.parishod.watomatic.network.model.openai.OpenAIResponse
import com.parishod.watomatic.service.reply.ReplyExecutionManager
import com.parishod.watomatic.service.reply.ReplyExecutionStatus
import com.parishod.watomatic.service.reply.ReplySourceType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationServiceDispatchTest {

    private lateinit var context: Context
    private lateinit var service: NotificationService
    private lateinit var prefs: PreferencesManager
    private lateinit var dbUtils: DbUtils

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferencesManager.resetInstance()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        val encryptedStore = context.getSharedPreferences("test_dispatch_enc", Context.MODE_PRIVATE)
        encryptedStore.edit().clear().commit()
        PreferencesManager.setEncryptedPrefsProviderForTesting { encryptedStore }
        prefs = PreferencesManager.getPreferencesInstance(context)
        MessageLogsDB.getInstance(context).clearAllTables()
        dbUtils = DbUtils(context)
        service = Robolectric.buildService(NotificationService::class.java).create().get()
    }

    @After
    fun tearDown() {
        try {
            service.onDestroy()
        } catch (_: Exception) {
        }
        MessageLogsDB.getInstance(context).clearAllTables()
        val cleanupExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            cleanupExecutor.submit {
                AiMemoryDatabase.getInstance(context).clearAllTables()
            }.get(5, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            cleanupExecutor.shutdownNow()
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("test_dispatch_enc", Context.MODE_PRIVATE).edit().clear().commit()
        PreferencesManager.resetInstance()
    }

    private fun buildNotificationSbn(
        sender: String = "Alice",
        message: String = "Hey, are you free?",
        id: Int = 10
    ): StatusBarNotification {
        val replyPi = PendingIntent.getBroadcast(
            context,
            id,
            Intent("com.parishod.watomatic.TEST_DISPATCH").setPackage(context.packageName),
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
            .addAction(action)
            .build()
        return StatusBarNotification(
            "com.whatsapp",
            "com.whatsapp",
            id,
            "tag-$id",
            0,
            0,
            0,
            notification,
            Process.myUserHandle(),
            System.currentTimeMillis()
        )
    }

    private fun wearFromSbn(sbn: StatusBarNotification, piOverride: PendingIntent? = null): NotificationWear {
        val remoteInput = RemoteInput.Builder("reply_key")
            .setLabel("Reply")
            .setAllowFreeFormInput(true)
            .build()
        val pi = piOverride ?: PendingIntent.getBroadcast(
            context,
            sbn.id + 500,
            Intent("com.parishod.watomatic.TEST_WEAR").setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        return NotificationWear("com.whatsapp", pi, listOf(remoteInput), null, sbn.tag, sbn.id.toString())
    }

    @Test
    fun `sendActualReply dispatches normally through ReplyExecutionManager and logs reply`() {
        val sendCount = AtomicInteger(0)
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> sendCount.incrementAndGet() })
        service.setReplyExecutionManagerForTesting(manager)

        val sbn = buildNotificationSbn(id = 21)
        val wear = wearFromSbn(sbn)
        val result = service.sendActualReply(sbn, wear, "Auto reply!", "VIP Rule", ReplySourceType.RULE_STATIC)

        assertEquals(ReplyExecutionStatus.DISPATCHED, result.status)
        assertTrue(result.isDispatched())
        assertEquals(1, sendCount.get())
        assertEquals(1L, dbUtils.numReplies)
    }

    @Test
    fun `canceled PendingIntent does not log reply to MessageLogsDB`() {
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
            throw PendingIntent.CanceledException("expired")
        })
        service.setReplyExecutionManagerForTesting(manager)

        val sbn = buildNotificationSbn(id = 22)
        val wear = wearFromSbn(sbn)
        val result = service.sendActualReply(sbn, wear, "Auto reply!", null, ReplySourceType.DEFAULT)

        assertEquals(ReplyExecutionStatus.CANCELLED, result.status)
        assertFalse(result.isDispatched())
        assertEquals(0L, dbUtils.numReplies)
    }

    @Test
    fun `unexpected send exception returns UNKNOWN, does not retry, and does not log reply`() {
        val sendAttempts = AtomicInteger(0)
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
            sendAttempts.incrementAndGet()
            throw RuntimeException("ambiguous IPC error")
        })
        service.setReplyExecutionManagerForTesting(manager)

        val sbn = buildNotificationSbn(id = 23)
        val wear = wearFromSbn(sbn)
        val result = service.sendActualReply(sbn, wear, "Auto reply!", null, ReplySourceType.DEFAULT)

        assertEquals(ReplyExecutionStatus.UNKNOWN, result.status)
        assertTrue(result.isUnknown())
        assertFalse(result.isDispatched())
        assertEquals(1, sendAttempts.get())
        assertEquals(0L, dbUtils.numReplies)
    }

    @Test
    fun `default reply path via sendReply dispatches through ReplyExecutionManager without regression`() {
        val capturedReplies = mutableListOf<String>()
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, fillIn ->
            val bundle = RemoteInput.getResultsFromIntent(fillIn)
            capturedReplies.add(bundle?.getCharSequence("reply_key")?.toString().orEmpty())
        })
        service.setReplyExecutionManagerForTesting(manager)
        prefs.setEnableByokReplies(false)

        val sbn = buildNotificationSbn(sender = "Bob", message = "Ping", id = 24)
        val sendReplyMethod = NotificationService::class.java.getDeclaredMethod(
            "sendReply",
            StatusBarNotification::class.java
        ).apply { isAccessible = true }

        sendReplyMethod.invoke(service, sbn)

        assertEquals(1, capturedReplies.size)
        assertTrue(capturedReplies[0].isNotBlank())
        assertEquals(1L, dbUtils.numReplies)
    }

    @Test
    fun `global AI and rule AI successful responses dispatch through ReplyExecutionManager`() {
        val capturedReplies = mutableListOf<String>()
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, fillIn ->
            val bundle = RemoteInput.getResultsFromIntent(fillIn)
            capturedReplies.add(bundle?.getCharSequence("reply_key")?.toString().orEmpty())
        })
        service.setReplyExecutionManagerForTesting(manager)

        val handleSuccess = NotificationService::class.java.getDeclaredMethod(
            "handleSuccessfulAiReply",
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

        val sbnGlobal = buildNotificationSbn(sender = "Carol", message = "Hi", id = 25)
        handleSuccess.invoke(
            service, prefs, sbnGlobal, wearFromSbn(sbnGlobal),
            "com.whatsapp", "carol", "Carol", "Hi", "Global AI reply",
            false, false, 10, "AI"
        )

        val sbnRule = buildNotificationSbn(sender = "Dave", message = "Urgent", id = 26)
        handleSuccess.invoke(
            service, prefs, sbnRule, wearFromSbn(sbnRule),
            "com.whatsapp", "dave", "Dave", "Urgent", "Rule AI reply",
            false, false, 10, "VIP • AI"
        )

        assertEquals(listOf("Global AI reply", "Rule AI reply"), capturedReplies)
        assertEquals(2L, dbUtils.numReplies)
    }

    @Test
    fun `AI HTTP failure fallback path dispatches through ReplyExecutionManager`() {
        val capturedReplies = mutableListOf<String>()
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, fillIn ->
            val bundle = RemoteInput.getResultsFromIntent(fillIn)
            capturedReplies.add(bundle?.getCharSequence("reply_key")?.toString().orEmpty())
        })
        service.setReplyExecutionManagerForTesting(manager)

        val errResponse = Response.error<OpenAIResponse>(
            503,
            """{"error":"unavailable"}""".toResponseBody("application/json".toMediaType())
        )
        val fakeApi = object : OpenAIService {
            override fun getChatCompletion(authorization: String, requestBody: OpenAIRequest): Call<OpenAIResponse> =
                immediateCall(errResponse)

            override fun getChatCompletion(url: String, auth: String, request: OpenAIRequest): Call<OpenAIResponse> =
                immediateCall(errResponse)

            override fun getClaudeCompletion(url: String, headers: Map<String, String>, request: JsonObject): Call<JsonObject> =
                error("unused")

            override fun getGeminiCompletion(url: String, apiKey: String, request: JsonObject): Call<JsonObject> =
                error("unused")

            override fun getModels(authorization: String): Call<OpenAIModelsResponse> = error("unused")
            override fun getModels(headers: Map<String, String>): Call<OpenAIModelsResponse> = error("unused")
            override fun getModelsDynamic(url: String, headers: Map<String, String>): Call<JsonElement> = error("unused")
        }

        val fetchOpenAi = NotificationService::class.java.getDeclaredMethod(
            "fetchOpenAiCompatibleReply",
            PreferencesManager::class.java, OpenAIService::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            String::class.java, List::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, String::class.java, StatusBarNotification::class.java,
            NotificationWear::class.java, String::class.java
        ).apply { isAccessible = true }

        val sbn = buildNotificationSbn(id = 27)
        fetchOpenAi.invoke(
            service, prefs, fakeApi, "https://api.openai.com/", "sk-test",
            "gpt-4o-mini", "sys", "com.whatsapp", "alice", "Alice",
            "hello", emptyList<AiConversationMessage>(), false, false, 10, "AI",
            sbn, wearFromSbn(sbn), "Fallback static reply"
        )

        assertEquals(listOf("Fallback static reply"), capturedReplies)
        assertEquals(1L, dbUtils.numReplies)
    }

    @Test
    fun `service onDestroy shuts down active ReplyExecutionManager and returns null thereafter`() {
        val manager = service.replyExecutionManager
        assertFalse(manager.isShutDown())
        assertFalse(service.isServiceDestroyed)

        service.onDestroy()
        assertTrue(service.isServiceDestroyed)
        assertTrue(manager.isShutDown())
        assertNull(service.replyExecutionManager)
    }

    @Test
    fun `sendActualReply after onDestroy returns CANCELLED and executes zero dispatches or DB writes`() {
        val sendCount = AtomicInteger(0)
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
            sendCount.incrementAndGet()
        })
        service.setReplyExecutionManagerForTesting(manager)
        service.onDestroy()

        val sbn = buildNotificationSbn(id = 28)
        val result = service.sendActualReply(sbn, wearFromSbn(sbn), "Late reply", null, ReplySourceType.DEFAULT)

        assertEquals(ReplyExecutionStatus.CANCELLED, result.status)
        assertFalse(result.dispatchAttempted)
        assertFalse(result.dispatchedNormally)
        assertEquals(ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN, result.failureCategory)
        assertEquals(0, sendCount.get())
        assertEquals(0L, dbUtils.numReplies)
        assertNull(service.replyExecutionManager)
    }

    @Test
    fun `late AI callbacks after onDestroy do not recreate manager, dispatch replies, log DB entries, or record errors`() {
        val sendCount = AtomicInteger(0)
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
            sendCount.incrementAndGet()
        })
        service.setReplyExecutionManagerForTesting(manager)

        val deferredCallbacks = mutableListOf<() -> Unit>()
        val deferredOpenAiApi = object : OpenAIService {
            override fun getChatCompletion(authorization: String, requestBody: OpenAIRequest): Call<OpenAIResponse> =
                deferredCall()

            override fun getChatCompletion(url: String, auth: String, request: OpenAIRequest): Call<OpenAIResponse> =
                deferredCall { cb ->
                    deferredCallbacks.add {
                        cb.onResponse(
                            immediateCall(Response.error<OpenAIResponse>(503, """{"err":"down"}""".toResponseBody("application/json".toMediaType()))),
                            Response.error(503, """{"err":"down"}""".toResponseBody("application/json".toMediaType()))
                        )
                    }
                    deferredCallbacks.add {
                        cb.onFailure(
                            immediateCall(Response.error<OpenAIResponse>(503, "".toResponseBody("application/json".toMediaType()))),
                            java.io.IOException("late timeout")
                        )
                    }
                }

            override fun getClaudeCompletion(url: String, headers: Map<String, String>, request: JsonObject): Call<JsonObject> =
                deferredJsonCall { cb ->
                    val okJson = JsonObject().apply {
                        add("content", com.google.gson.JsonArray().apply {
                            add(JsonObject().apply { addProperty("text", "Late Claude reply") })
                        })
                    }
                    deferredCallbacks.add { cb.onResponse(immediateCall(Response.success(okJson)), Response.success(okJson)) }
                    deferredCallbacks.add { cb.onFailure(immediateCall(Response.success(okJson)), java.io.IOException("claude timeout")) }
                }

            override fun getGeminiCompletion(url: String, apiKey: String, request: JsonObject): Call<JsonObject> =
                deferredJsonCall { cb ->
                    val okJson = JsonObject().apply {
                        add("candidates", com.google.gson.JsonArray().apply {
                            add(JsonObject().apply {
                                add("content", JsonObject().apply {
                                    add("parts", com.google.gson.JsonArray().apply {
                                        add(JsonObject().apply { addProperty("text", "Late Gemini reply") })
                                    })
                                })
                            })
                        })
                    }
                    deferredCallbacks.add { cb.onResponse(immediateCall(Response.success(okJson)), Response.success(okJson)) }
                    deferredCallbacks.add { cb.onFailure(immediateCall(Response.success(okJson)), java.io.IOException("gemini timeout")) }
                }

            override fun getModels(authorization: String): Call<OpenAIModelsResponse> = error("unused")
            override fun getModels(headers: Map<String, String>): Call<OpenAIModelsResponse> = error("unused")
            override fun getModelsDynamic(url: String, headers: Map<String, String>): Call<JsonElement> = error("unused")
        }

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
        val handleSuccess = NotificationService::class.java.getDeclaredMethod(
            "handleSuccessfulAiReply",
            PreferencesManager::class.java, StatusBarNotification::class.java, NotificationWear::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
        ).apply { isAccessible = true }

        val sbn1 = buildNotificationSbn(id = 29)
        val sbn2 = buildNotificationSbn(id = 30)
        val sbn3 = buildNotificationSbn(id = 31)

        // Enqueue in-flight calls BEFORE onDestroy
        fetchOpenAi.invoke(
            service, prefs, deferredOpenAiApi, "https://api.openai.com/", "sk-test",
            "gpt-4o-mini", "sys", "com.whatsapp", "alice", "Alice",
            "hello", emptyList<AiConversationMessage>(), false, false, 10, "AI",
            sbn1, wearFromSbn(sbn1), "Fallback"
        )
        fetchClaude.invoke(
            service, prefs, deferredOpenAiApi, "https://api.anthropic.com/", "sk-test",
            "claude-sonnet-4-20250514", "sys", "com.whatsapp", "alice", "Alice",
            "hello", emptyList<AiConversationMessage>(), false, false, 10, "AI",
            sbn2, wearFromSbn(sbn2), "Fallback"
        )
        fetchGemini.invoke(
            service, prefs, deferredOpenAiApi, "https://generativelanguage.googleapis.com/", "sk-test",
            "gemini-2.5-flash", "sys", "com.whatsapp", "alice", "Alice",
            "hello", emptyList<AiConversationMessage>(), false, false, 10, "AI",
            sbn3, wearFromSbn(sbn3), "Fallback"
        )
        assertEquals(6, deferredCallbacks.size)

        // Now destroy service before any callback delivers
        service.onDestroy()

        // Deliver all late callbacks + direct handleSuccessfulAiReply
        deferredCallbacks.forEach { it.invoke() }
        handleSuccess.invoke(
            service, prefs, sbn1, wearFromSbn(sbn1),
            "com.whatsapp", "alice", "Alice", "hello", "Late direct reply",
            false, false, 10, "AI"
        )

        assertEquals(0, sendCount.get())
        assertEquals(0L, dbUtils.numReplies)
        assertNull(prefs.openAILastPersistentErrorMessage)
        assertNull(service.replyExecutionManager)
    }

    @Test
    fun `concurrent onDestroy and sendActualReply leaves no active manager and never dispatches after shutdown`() {
        val dispatchCount = AtomicInteger(0)
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
            dispatchCount.incrementAndGet()
        })
        service.setReplyExecutionManagerForTesting(manager)

        val workerPool = Executors.newFixedThreadPool(4)
        val startGate = CountDownLatch(1)
        try {
            val destroyFuture = workerPool.submit {
                assertTrue(startGate.await(5, TimeUnit.SECONDS))
                service.onDestroy()
            }
            val dispatchFutures = (1..8).map { index ->
                val sbn = buildNotificationSbn(id = 40 + index)
                val wear = wearFromSbn(sbn)
                workerPool.submit<com.parishod.watomatic.service.reply.ReplyExecutionResult> {
                    assertTrue(startGate.await(5, TimeUnit.SECONDS))
                    service.sendActualReply(sbn, wear, "Concurrent $index", null, ReplySourceType.DEFAULT)
                }
            }

            startGate.countDown()
            destroyFuture.get(5, TimeUnit.SECONDS)
            val results = dispatchFutures.map { it.get(5, TimeUnit.SECONDS) }

            assertTrue(service.isServiceDestroyed)
            assertTrue(manager.isShutDown())
            assertNull(service.replyExecutionManager)
            assertEquals(results.count { it.isDispatched() }, dispatchCount.get())
            assertTrue(results.all { it.isDispatched() || it.status == ReplyExecutionStatus.CANCELLED })

            // Any further invocation strictly after onDestroy must always be CANCELLED
            val afterDestroySbn = buildNotificationSbn(id = 99)
            val afterDestroyResult = service.sendActualReply(
                afterDestroySbn, wearFromSbn(afterDestroySbn), "Post destroy", null, ReplySourceType.DEFAULT
            )
            assertEquals(ReplyExecutionStatus.CANCELLED, afterDestroyResult.status)
            assertEquals(ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN, afterDestroyResult.failureCategory)
            assertEquals(dispatchCount.get().toLong(), dbUtils.numReplies)
        } finally {
            workerPool.shutdownNow()
        }
    }

    @Test
    fun `sendActualReply persists reply_attempts and DISPATCHED logs across all active reply paths`() {
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> })
        service.setReplyExecutionManagerForTesting(manager)

        val db = MessageLogsDB.getInstance(context)

        // 1. DEFAULT
        val sbnDefault = buildNotificationSbn(sender = "U1", message = "M1", id = 101)
        val resDefault = service.sendActualReply(sbnDefault, wearFromSbn(sbnDefault), "Default msg", null, ReplySourceType.DEFAULT)
        assertEquals(ReplyExecutionStatus.DISPATCHED, resDefault.status)
        val attDefault = db.replyAttemptDao().getAttemptByIdSync(resDefault.executionId)!!
        assertEquals(ReplySourceType.DEFAULT.name, attDefault.sourceType)
        assertEquals("DISPATCHED", attDefault.status)

        // 2. RULE_STATIC
        val sbnRule = buildNotificationSbn(sender = "U2", message = "M2", id = 102)
        val resRule = service.sendActualReply(sbnRule, wearFromSbn(sbnRule), "Rule msg", "Hours Rule", ReplySourceType.RULE_STATIC)
        val attRule = db.replyAttemptDao().getAttemptByIdSync(resRule.executionId)!!
        assertEquals(ReplySourceType.RULE_STATIC.name, attRule.sourceType)
        assertEquals("Hours Rule", attRule.sourceLabel)
        assertEquals("DISPATCHED", attRule.status)

        // 3 & 4. GLOBAL_AI and RULE_AI via handleSuccessfulAiReply
        val handleSuccess = NotificationService::class.java.getDeclaredMethod(
            "handleSuccessfulAiReply",
            PreferencesManager::class.java, StatusBarNotification::class.java, NotificationWear::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
        ).apply { isAccessible = true }

        val sbnGlobalAi = buildNotificationSbn(sender = "U3", message = "M3", id = 103)
        handleSuccess.invoke(
            service, prefs, sbnGlobalAi, wearFromSbn(sbnGlobalAi),
            "com.whatsapp", "u3", "U3", "M3", "Global AI text",
            false, false, 10, "AI Reply"
        )
        val sbnRuleAi = buildNotificationSbn(sender = "U4", message = "M4", id = 104)
        handleSuccess.invoke(
            service, prefs, sbnRuleAi, wearFromSbn(sbnRuleAi),
            "com.whatsapp", "u4", "U4", "M4", "Rule AI text",
            false, false, 10, "Support • AI Reply"
        )

        // 5. Direct AI_FALLBACK
        val sbnFallback = buildNotificationSbn(sender = "U5", message = "M5", id = 105)
        val resFallback = service.sendActualReply(sbnFallback, wearFromSbn(sbnFallback), "Fallback direct", null, ReplySourceType.AI_FALLBACK)
        val attFallback = db.replyAttemptDao().getAttemptByIdSync(resFallback.executionId)!!
        assertEquals(ReplySourceType.AI_FALLBACK.name, attFallback.sourceType)
        assertEquals("AI Fallback", attFallback.sourceLabel)

        // 6. Provider HTTP failure -> AI_FALLBACK
        val errResponse = Response.error<OpenAIResponse>(500, """{"err":"fail"}""".toResponseBody("application/json".toMediaType()))
        val fakeApi = object : OpenAIService {
            override fun getChatCompletion(authorization: String, requestBody: OpenAIRequest): Call<OpenAIResponse> = immediateCall(errResponse)
            override fun getChatCompletion(url: String, auth: String, request: OpenAIRequest): Call<OpenAIResponse> = immediateCall(errResponse)
            override fun getClaudeCompletion(url: String, headers: Map<String, String>, request: JsonObject): Call<JsonObject> = error("unused")
            override fun getGeminiCompletion(url: String, apiKey: String, request: JsonObject): Call<JsonObject> = error("unused")
            override fun getModels(authorization: String): Call<OpenAIModelsResponse> = error("unused")
            override fun getModels(headers: Map<String, String>): Call<OpenAIModelsResponse> = error("unused")
            override fun getModelsDynamic(url: String, headers: Map<String, String>): Call<JsonElement> = error("unused")
        }
        val fetchOpenAi = NotificationService::class.java.getDeclaredMethod(
            "fetchOpenAiCompatibleReply",
            PreferencesManager::class.java, OpenAIService::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            String::class.java, List::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, String::class.java, StatusBarNotification::class.java,
            NotificationWear::class.java, String::class.java
        ).apply { isAccessible = true }
        val sbnProviderFail = buildNotificationSbn(sender = "U6", message = "M6", id = 106)
        fetchOpenAi.invoke(
            service, prefs, fakeApi, "https://api.openai.com/", "sk-test",
            "gpt-4o-mini", "sys", "com.whatsapp", "u6", "U6",
            "M6", emptyList<AiConversationMessage>(), false, false, 10, "AI Reply",
            sbnProviderFail, wearFromSbn(sbnProviderFail), "Provider error fallback"
        )

        val attempts = db.replyAttemptDao().getAllAttemptsSync()
        assertEquals(6, attempts.size)
        assertTrue(attempts.all { it.status == "DISPATCHED" })
        assertTrue(attempts.any { it.sourceType == ReplySourceType.GLOBAL_AI.name })
        assertTrue(attempts.any { it.sourceType == ReplySourceType.RULE_AI.name })
        assertEquals(2, attempts.count { it.sourceType == ReplySourceType.AI_FALLBACK.name })
        assertEquals(6L, dbUtils.numReplies)
    }

    @Test
    fun `database write failure never alters actual ReplyExecutionResult for DISPATCHED or FAILED`() {
        val throwingDbUtils = object : DbUtils(context) {
            override fun recordAttemptTransition(
                attempt: com.parishod.watomatic.service.reply.ReplyAttempt,
                senderName: String?,
                incomingMsg: String?
            ) {
                throw IllegalStateException("Simulated SQLite transition write error")
            }

            override fun recordAttemptResult(
                attempt: com.parishod.watomatic.service.reply.ReplyAttempt,
                result: com.parishod.watomatic.service.reply.ReplyExecutionResult,
                senderName: String?,
                incomingMsg: String?,
                sbn: StatusBarNotification?
            ) {
                throw IllegalStateException("Simulated SQLite result write error")
            }
        }
        service.setDbUtilsForTesting(throwingDbUtils)

        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> })
        service.setReplyExecutionManagerForTesting(manager)

        val sbnOk = buildNotificationSbn(id = 201)
        val okResult = service.sendActualReply(sbnOk, wearFromSbn(sbnOk), "Should succeed", null, ReplySourceType.DEFAULT)
        assertEquals(ReplyExecutionStatus.DISPATCHED, okResult.status)
        assertTrue(okResult.isDispatched())

        val sbnFail = buildNotificationSbn(id = 202)
        val failWear = NotificationWear("com.whatsapp", null, wearFromSbn(sbnFail).remoteInputs, null, sbnFail.tag, sbnFail.id.toString())
        val failResult = service.sendActualReply(sbnFail, failWear, "Should fail", null, ReplySourceType.DEFAULT)
        assertEquals(ReplyExecutionStatus.FAILED, failResult.status)
        assertEquals(ReplyExecutionManager.FAILURE_MISSING_PENDING_INTENT, failResult.failureCategory)
        assertFalse(failResult.isDispatched())
    }

    @Test
    fun `sendReply with missing PendingIntent skips AI generation and releases claim`() {
        val sendCount = AtomicInteger(0)
        val manager = ReplyExecutionManager(
            context,
            pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> sendCount.incrementAndGet() }
        )
        service.setReplyExecutionManagerForTesting(manager)
        prefs.setEnableByokReplies(true)
        prefs.saveOpenAIApiKey("sk-test-missing-pi")

        val remoteInput = RemoteInput.Builder("reply_key")
            .setLabel("Reply")
            .setAllowFreeFormInput(true)
            .build()
        val actionWithoutPi = NotificationCompat.Action.Builder(0, "Reply", null)
            .addRemoteInput(remoteInput)
            .build()
        val notification = NotificationCompat.Builder(context, "test_channel")
            .setContentTitle("Grace")
            .setContentText("Are you there?")
            .addAction(actionWithoutPi)
            .build()
        val sbnWithoutPi = StatusBarNotification(
            "com.whatsapp",
            "com.whatsapp",
            301,
            "tag-301",
            0,
            0,
            0,
            notification,
            Process.myUserHandle(),
            System.currentTimeMillis()
        )
        val eventKey = com.parishod.watomatic.service.reply.NotificationEventKeyBuilder.buildEventKey(sbnWithoutPi, "Are you there?")!!
        val claimToken = "claim-missing-pi-301"
        val acquired = dbUtils.tryAcquireNotificationClaim(eventKey, claimToken, "com.whatsapp", "grace", sbnWithoutPi.key, 1_000L, false)
        assertTrue(acquired.acquired)

        val sendReplyWithToken = NotificationService::class.java.getDeclaredMethod(
            "sendReply",
            StatusBarNotification::class.java,
            String::class.java
        ).apply { isAccessible = true }
        sendReplyWithToken.invoke(service, sbnWithoutPi, claimToken)
        service.awaitAiMemoryIdleForTesting()

        assertEquals(0, sendCount.get())
        assertEquals(0L, dbUtils.numReplies)
        assertEquals(0, dbUtils.totalAttemptCount)
        assertNull(dbUtils.getNotificationClaim(eventKey))
    }

    @Test
    fun `sendActualReply with null StatusBarNotification fails closed without bypassing claim protection`() {
        val sendCount = AtomicInteger(0)
        val manager = ReplyExecutionManager(
            context,
            pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> sendCount.incrementAndGet() }
        )
        service.setReplyExecutionManagerForTesting(manager)
        val wear = wearFromSbn(buildNotificationSbn(id = 302))

        val result = service.sendActualReply(null, wear, "Should not send", null, ReplySourceType.DEFAULT)

        assertEquals(ReplyExecutionStatus.FAILED, result.status)
        assertFalse(result.dispatchAttempted)
        assertFalse(result.dispatchedNormally)
        assertEquals(ReplyExecutionManager.FAILURE_MISSING_WEAR_DATA, result.failureCategory)
        assertEquals(0, sendCount.get())
        assertEquals(0L, dbUtils.numReplies)
    }

    @Test
    fun `CANCELLED outcomes preserve dispatchAttempted false before send and true after PendingIntent CanceledException`() {
        val db = MessageLogsDB.getInstance(context)

        // 1. Pre-send CANCELLED (manager already shut down) -> dispatchAttempted = false
        val shutdownManager = ReplyExecutionManager(
            context,
            pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> }
        )
        shutdownManager.shutdown()
        service.setReplyExecutionManagerForTesting(shutdownManager)

        val sbnPreCancel = buildNotificationSbn(sender = "PreCancel", message = "Shutdown before send", id = 401)
        val preEventKey = com.parishod.watomatic.service.reply.NotificationEventKeyBuilder.buildEventKey(sbnPreCancel)!!
        val preRes = service.sendActualReply(sbnPreCancel, wearFromSbn(sbnPreCancel), "Reply", null, ReplySourceType.DEFAULT)

        assertEquals(ReplyExecutionStatus.CANCELLED, preRes.status)
        assertFalse(preRes.dispatchAttempted)
        assertEquals(ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN, preRes.failureCategory)
        assertFalse(dbUtils.getAttemptById(preRes.executionId)!!.dispatchAttempted)
        assertFalse(dbUtils.getNotificationClaim(preEventKey)!!.dispatchAttempted)
        assertEquals(0L, dbUtils.numReplies)

        // 2. Post-attempt CANCELLED via PendingIntent.CanceledException -> dispatchAttempted = true
        val canceledManager = ReplyExecutionManager(
            context,
            pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                throw PendingIntent.CanceledException("expired during send")
            }
        )
        service.setReplyExecutionManagerForTesting(canceledManager)

        val sbnPostCancel = buildNotificationSbn(sender = "PostCancel", message = "Canceled during send", id = 402)
        val postEventKey = com.parishod.watomatic.service.reply.NotificationEventKeyBuilder.buildEventKey(sbnPostCancel)!!
        val postRes = service.sendActualReply(sbnPostCancel, wearFromSbn(sbnPostCancel), "Reply", null, ReplySourceType.DEFAULT)

        assertEquals(ReplyExecutionStatus.CANCELLED, postRes.status)
        assertTrue(postRes.dispatchAttempted)
        assertFalse(postRes.dispatchedNormally)
        assertEquals(ReplyExecutionManager.FAILURE_PENDING_INTENT_CANCELED, postRes.failureCategory)
        val storedPostAttempt = db.replyAttemptDao().getAttemptByIdSync(postRes.executionId)!!
        assertEquals("CANCELLED", storedPostAttempt.status)
        assertTrue(storedPostAttempt.dispatchAttempted)
        val storedPostClaim = db.notificationDispatchClaimDao().getClaimByEventKeySync(postEventKey)!!
        assertEquals("CANCELLED", storedPostClaim.claimStatus)
        assertTrue(storedPostClaim.dispatchAttempted)
        assertEquals(0L, dbUtils.numReplies)
    }

    @Test
    fun `DISPATCHING persistence failure followed by unexpected execution exception classifies as UNKNOWN rather than FAILED`() {
        val db = MessageLogsDB.getInstance(context)
        val partialFailDbUtils = object : DbUtils(context) {
            override fun recordAttemptTransition(
                attempt: com.parishod.watomatic.service.reply.ReplyAttempt,
                senderName: String?,
                incomingMsg: String?
            ) {
                if (attempt.status == ReplyExecutionStatus.DISPATCHING) {
                    throw IllegalStateException("Simulated DB failure on DISPATCHING attempt write")
                }
                super.recordAttemptTransition(attempt, senderName, incomingMsg)
            }

            override fun updateNotificationClaimTransition(
                eventKey: String,
                expectedAttemptId: String,
                newAttemptId: String,
                status: ReplyExecutionStatus,
                updatedAtMs: Long
            ): Int {
                if (status == ReplyExecutionStatus.DISPATCHING) {
                    throw IllegalStateException("Simulated DB failure on DISPATCHING claim write")
                }
                return super.updateNotificationClaimTransition(eventKey, expectedAttemptId, newAttemptId, status, updatedAtMs)
            }
        }
        service.setDbUtilsForTesting(partialFailDbUtils)

        val clockCalls = AtomicInteger(0)
        val manager = ReplyExecutionManager(
            context,
            pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> },
            clock = {
                if (clockCalls.incrementAndGet() >= 2) {
                    throw IllegalStateException("Unexpected exception after crossing DISPATCHING")
                }
                10_000L
            }
        )
        service.setReplyExecutionManagerForTesting(manager)

        val sbn = buildNotificationSbn(sender = "Ambiguous", message = "Crash after DISPATCHING", id = 403)
        val eventKey = com.parishod.watomatic.service.reply.NotificationEventKeyBuilder.buildEventKey(sbn)!!
        val result = service.sendActualReply(sbn, wearFromSbn(sbn), "Reply", null, ReplySourceType.DEFAULT)

        assertEquals(ReplyExecutionStatus.UNKNOWN, result.status)
        assertTrue(result.dispatchAttempted)
        assertFalse(result.dispatchedNormally)
        assertEquals(ReplyExecutionManager.FAILURE_AMBIGUOUS_SEND_EXCEPTION, result.failureCategory)
        assertEquals("UNKNOWN", db.replyAttemptDao().getAttemptByIdSync(result.executionId)!!.status)
        assertEquals("UNKNOWN", db.notificationDispatchClaimDao().getClaimByEventKeySync(eventKey)!!.claimStatus)
        assertEquals(0L, partialFailDbUtils.numReplies)
    }

    @Test
    fun `outcome persistence failure does not promote claim to DISPATCHED and locks replay conservatively`() {
        val db = MessageLogsDB.getInstance(context)
        val throwingResultDbUtils = object : DbUtils(context) {
            override fun recordAttemptResult(
                attempt: com.parishod.watomatic.service.reply.ReplyAttempt,
                result: com.parishod.watomatic.service.reply.ReplyExecutionResult,
                senderName: String?,
                incomingMsg: String?,
                sbn: StatusBarNotification?
            ) {
                throw IllegalStateException("Simulated MessageLog write error")
            }
        }
        service.setDbUtilsForTesting(throwingResultDbUtils)

        val sendCount = AtomicInteger(0)
        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )

        val sbn = buildNotificationSbn(sender = "SplitBrain", message = "Check atomicity", id = 404)
        val eventKey = com.parishod.watomatic.service.reply.NotificationEventKeyBuilder.buildEventKey(sbn)!!
        val res = service.sendActualReply(sbn, wearFromSbn(sbn), "Reply text", null, ReplySourceType.DEFAULT)

        assertEquals(ReplyExecutionStatus.DISPATCHED, res.status)
        assertEquals(1, sendCount.get())

        // Claim MUST NOT be promoted to DISPATCHED while MessageLog/attempt persistence failed
        val claim = db.notificationDispatchClaimDao().getClaimByEventKeySync(eventKey)!!
        assertEquals(ReplyExecutionStatus.UNKNOWN.name, claim.claimStatus)
        assertEquals(ReplyExecutionManager.FAILURE_CLAIM_PERSISTENCE_ERROR, claim.failureCategory)
        assertTrue(claim.dispatchAttempted)
        assertFalse(claim.dispatchedNormally)
        assertEquals(0L, db.logsDao().getNumReplies())

        // Conservative fallback attempt persisted as UNKNOWN (not DISPATCHED)
        val attemptEntity = db.replyAttemptDao().getAttemptByIdSync(res.executionId)!!
        assertEquals(ReplyExecutionStatus.UNKNOWN.name, attemptEntity.status)
        assertEquals(ReplyExecutionManager.FAILURE_CLAIM_PERSISTENCE_ERROR, attemptEntity.failureCategory)

        // Duplicate redelivery MUST stay locked
        val replay = service.sendActualReply(sbn, wearFromSbn(sbn), "Reply text", null, ReplySourceType.DEFAULT)
        assertEquals(ReplyExecutionStatus.CANCELLED, replay.status)
        assertEquals(ReplyExecutionManager.FAILURE_UNKNOWN_OUTCOME_LOCKED, replay.failureCategory)
        assertEquals(1, sendCount.get())
    }

    @Test
    fun `claim-only failure in recordAttemptAndClaimResult recovers DISPATCHED consistency via fallback without duplicate MessageLog`() {
        val db = MessageLogsDB.getInstance(context)
        val failFirstTransactionDbUtils = object : DbUtils(context) {
            private var threwOnce = false
            override fun recordAttemptAndClaimResult(
                eventKey: String?,
                attempt: com.parishod.watomatic.service.reply.ReplyAttempt,
                result: com.parishod.watomatic.service.reply.ReplyExecutionResult,
                senderName: String?,
                incomingMsg: String?,
                sbn: StatusBarNotification?
            ): com.parishod.watomatic.model.logs.AttemptClaimPersistOutcome {
                if (!threwOnce) {
                    threwOnce = true
                    throw IllegalStateException("Simulated transient claim transaction failure")
                }
                return super.recordAttemptAndClaimResult(eventKey, attempt, result, senderName, incomingMsg, sbn)
            }
        }
        service.setDbUtilsForTesting(failFirstTransactionDbUtils)

        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> })
        )

        val sbn = buildNotificationSbn(sender = "Recoverable", message = "Recover fallback", id = 405)
        val eventKey = com.parishod.watomatic.service.reply.NotificationEventKeyBuilder.buildEventKey(sbn)!!
        val res = service.sendActualReply(sbn, wearFromSbn(sbn), "Reply text", null, ReplySourceType.DEFAULT)

        assertEquals(ReplyExecutionStatus.DISPATCHED, res.status)
        assertEquals(1, db.logsDao().countByAttemptIdSync(res.executionId))
        assertEquals("DISPATCHED", db.replyAttemptDao().getAttemptByIdSync(res.executionId)!!.status)
        assertEquals("DISPATCHED", db.notificationDispatchClaimDao().getClaimByEventKeySync(eventKey)!!.claimStatus)
    }

    private fun <T> immediateCall(response: Response<T>): Call<T> = object : Call<T> {
        override fun enqueue(callback: Callback<T>) = callback.onResponse(this, response)
        override fun isExecuted(): Boolean = true
        override fun clone(): Call<T> = this
        override fun isCanceled(): Boolean = false
        override fun cancel() {}
        override fun execute(): Response<T> = response
        override fun request(): Request = Request.Builder().url("https://localhost/").build()
        override fun timeout(): Timeout = Timeout.NONE
    }

    private fun deferredCall(onEnqueue: (Callback<OpenAIResponse>) -> Unit = {}): Call<OpenAIResponse> = object : Call<OpenAIResponse> {
        override fun enqueue(callback: Callback<OpenAIResponse>) = onEnqueue(callback)
        override fun isExecuted(): Boolean = false
        override fun clone(): Call<OpenAIResponse> = this
        override fun isCanceled(): Boolean = false
        override fun cancel() {}
        override fun execute(): Response<OpenAIResponse> = error("unused")
        override fun request(): Request = Request.Builder().url("https://localhost/").build()
        override fun timeout(): Timeout = Timeout.NONE
    }

    private fun deferredJsonCall(onEnqueue: (Callback<JsonObject>) -> Unit = {}): Call<JsonObject> = object : Call<JsonObject> {
        override fun enqueue(callback: Callback<JsonObject>) = onEnqueue(callback)
        override fun isExecuted(): Boolean = false
        override fun clone(): Call<JsonObject> = this
        override fun isCanceled(): Boolean = false
        override fun cancel() {}
        override fun execute(): Response<JsonObject> = error("unused")
        override fun request(): Request = Request.Builder().url("https://localhost/").build()
        override fun timeout(): Timeout = Timeout.NONE
    }
}
