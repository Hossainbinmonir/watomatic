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
import com.parishod.watomatic.NotificationWear
import com.parishod.watomatic.model.ai.AiConversationMessage
import com.parishod.watomatic.model.ai.AiMemoryDatabase
import com.parishod.watomatic.model.ai.AiNote
import com.parishod.watomatic.model.ai.PendingPostDispatchMemoryStore
import com.parishod.watomatic.model.logs.AppPackage
import com.parishod.watomatic.model.logs.MessageLog
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.logs.NotificationDispatchClaimEntity
import com.parishod.watomatic.model.logs.ReplyAttemptEntity
import com.parishod.watomatic.model.logs.ReplyHistoryStatus
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.rules.AutoReplyRule
import com.parishod.watomatic.model.rules.AutoReplyRulesDatabase
import com.parishod.watomatic.model.rules.MatchType
import com.parishod.watomatic.model.rules.RuleExecutionLog
import com.parishod.watomatic.model.utils.Constants
import com.parishod.watomatic.model.utils.DbUtils
import com.parishod.watomatic.network.OpenAIService
import com.parishod.watomatic.network.RetrofitInstance
import com.parishod.watomatic.network.model.openai.Choice
import com.parishod.watomatic.network.model.openai.OpenAIModelsResponse
import com.parishod.watomatic.network.model.openai.OpenAIRequest
import com.parishod.watomatic.network.model.openai.OpenAIResponse
import com.parishod.watomatic.network.model.openai.ResponseMessage
import com.parishod.watomatic.service.ai.AiPromptAndPolicyHelper
import com.parishod.watomatic.service.reply.NotificationEventKeyBuilder
import com.parishod.watomatic.service.reply.PendingClaimHandoff
import com.parishod.watomatic.service.reply.PreDispatchRetryCoordinator
import com.parishod.watomatic.service.reply.PreDispatchRetryStateStore
import com.parishod.watomatic.service.reply.ReplyAttempt
import com.parishod.watomatic.service.reply.ReplyExecutionManager
import com.parishod.watomatic.service.reply.ReplyExecutionResult
import com.parishod.watomatic.service.reply.ReplyExecutionStatus
import com.parishod.watomatic.service.reply.ReplyRetryPolicy
import com.parishod.watomatic.service.reply.ReplySourceType
import com.parishod.watomatic.service.reply.RetryDecision
import com.parishod.watomatic.service.rules.RuleExecutionReservationCoordinator
import com.parishod.watomatic.service.rules.RuleNotificationProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Stage025Batch3ReliabilityTest {

    private lateinit var context: Context
    private lateinit var prefs: PreferencesManager
    private lateinit var logsDb: MessageLogsDB
    private lateinit var rulesDb: AutoReplyRulesDatabase
    private lateinit var memoryDb: AiMemoryDatabase
    private lateinit var dbUtils: DbUtils
    private val createdServices = mutableListOf<NotificationService>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferencesManager.resetInstance()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("watomatic_pre_dispatch_retry_state", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val enc = context.getSharedPreferences("test_stage025_b3_enc", Context.MODE_PRIVATE)
        enc.edit().clear().commit()
        PreferencesManager.setEncryptedPrefsProviderForTesting { enc }
        prefs = PreferencesManager.getPreferencesInstance(context)
        prefs.setServicePref(true)
        prefs.saveEnabledApps("com.whatsapp", true)
        prefs.setEnableByokReplies(false)
        prefs.setAppendWatomaticAttribution(false)
        prefs.setRuleFallbackToGlobalEnabled(false)

        PendingClaimHandoff.clear()
        PendingPostDispatchMemoryStore.clearForTesting(context)
        MessageLogsDB.resetInstance()
        context.deleteDatabase(Constants.LOGS_DB_NAME)
        AutoReplyRulesDatabase.resetInstance()
        context.deleteDatabase(AutoReplyRulesDatabase.DB_NAME)
        AiMemoryDatabase.resetInstance()
        context.deleteDatabase(AiMemoryDatabase.DB_NAME)

        logsDb = MessageLogsDB.getInstance(context)
        rulesDb = AutoReplyRulesDatabase.getInstance(context)
        memoryDb = AiMemoryDatabase.getInstance(context)
        dbUtils = DbUtils(context)
    }

    @After
    fun tearDown() {
        for (svc in createdServices) {
            runCatching { svc.onDestroy() }
            runCatching { svc.awaitAiMemoryIdleForTesting() }
        }
        createdServices.clear()
        PendingClaimHandoff.clear()
        val cleanupExec = Executors.newSingleThreadExecutor()
        try {
            cleanupExec.submit {
                PendingPostDispatchMemoryStore.clearForTesting(context)
                MessageLogsDB.resetInstance()
                context.deleteDatabase(Constants.LOGS_DB_NAME)
                AutoReplyRulesDatabase.resetInstance()
                context.deleteDatabase(AutoReplyRulesDatabase.DB_NAME)
                AiMemoryDatabase.resetInstance()
                context.deleteDatabase(AiMemoryDatabase.DB_NAME)
            }.get(15, TimeUnit.SECONDS)
        } finally {
            cleanupExec.shutdownNow()
        }
        context.getSharedPreferences("watomatic_pre_dispatch_retry_state", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("test_stage025_b3_enc", Context.MODE_PRIVATE).edit().clear().commit()
        PreferencesManager.resetInstance()
    }

    private fun createManagedService(
        sendCounter: AtomicInteger = AtomicInteger(0),
        configureBeforeCreate: (NotificationService) -> Unit = {}
    ): NotificationService {
        val controller = Robolectric.buildService(NotificationService::class.java)
        val svc = controller.get()
        configureBeforeCreate(svc)
        controller.create()
        svc.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(
                context,
                pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                    sendCounter.incrementAndGet()
                }
            )
        )
        createdServices.add(svc)
        return svc
    }

    private fun buildSbn(
        pkg: String = "com.whatsapp",
        id: Int = 900,
        sender: String? = "Alice",
        message: String? = "Hello there",
        whenMs: Long = System.currentTimeMillis(),
        isGroup: Boolean = false
    ): StatusBarNotification {
        val replyPi = PendingIntent.getBroadcast(
            context,
            id,
            Intent("com.parishod.watomatic.BATCH3_REPLY").setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val ri = RemoteInput.Builder("reply_key").setLabel("Reply").setAllowFreeFormInput(true).build()
        val action = NotificationCompat.Action.Builder(0, "Reply", replyPi).addRemoteInput(ri).build()
        val builder = NotificationCompat.Builder(context, "ch")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setWhen(whenMs)
            .addAction(action)
        if (sender != null) {
            builder.setContentTitle(sender)
        }
        if (message != null) {
            builder.setContentText(message)
        }
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

    private fun wearFromSbn(
        sbn: StatusBarNotification,
        includePendingIntent: Boolean = true,
        allowFreeForm: Boolean = true
    ): NotificationWear {
        val ri = RemoteInput.Builder("reply_key")
            .setLabel("Reply")
            .setAllowFreeFormInput(allowFreeForm)
            .build()
        val pi = if (includePendingIntent) {
            PendingIntent.getBroadcast(
                context,
                sbn.id + 7000,
                Intent("com.parishod.watomatic.BATCH3_WEAR").setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        } else {
            null
        }
        return NotificationWear(
            sbn.packageName,
            pi,
            listOf(ri),
            sbn.notification.extras,
            sbn.tag,
            sbn.id.toString()
        )
    }

    private fun invokeFetchOpenAiInternal(
        svc: NotificationService,
        openAiService: OpenAIService,
        sbn: StatusBarNotification,
        wear: NotificationWear,
        claimToken: String
    ) {
        val method = NotificationService::class.java.getDeclaredMethod(
            "fetchOpenAiCompatibleReplyInternal",
            PreferencesManager::class.java,
            OpenAIService::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            List::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
            StatusBarNotification::class.java,
            NotificationWear::class.java,
            String::class.java,
            AutoReplyRule::class.java,
            String::class.java
        ).apply { isAccessible = true }

        val senderDisplay = sbn.notification.extras.getString("android.title") ?: "Alice"
        val senderKey = AiPromptAndPolicyHelper.normalizeSenderKey(senderDisplay, false)
        val msg = sbn.notification.extras.getCharSequence("android.text")?.toString() ?: "Hello"
        method.invoke(
            svc,
            prefs,
            openAiService,
            "https://api.openai.com/",
            "sk-test-key",
            "gpt-4o-mini",
            "System prompt",
            sbn.packageName,
            senderKey,
            senderDisplay,
            msg,
            emptyList<Any>(),
            true,
            false,
            20,
            "AI",
            sbn,
            wear,
            "Fallback reply",
            null,
            claimToken
        )
    }

    // =====================================================================
    // F-025-08 — Retrofit Lifecycle, Call Cancellation & Client Reuse
    // =====================================================================

    @Test
    fun `F-025-08 onDestroy cancels active Retrofit AI calls and suppresses late response or failure dispatch`() = runBlocking {
        val capturedCallback = AtomicReference<Callback<OpenAIResponse>?>(null)
        val capturedCall = AtomicReference<Call<OpenAIResponse>?>(null)
        val cancelCalled = AtomicBoolean(false)

        val mockOpenAiService = object : OpenAIService {
            override fun getChatCompletion(authorization: String, requestBody: OpenAIRequest): Call<OpenAIResponse> =
                error("Unused")

            override fun getChatCompletion(
                url: String,
                authorization: String,
                requestBody: OpenAIRequest
            ): Call<OpenAIResponse> {
                val call = object : Call<OpenAIResponse> {
                    private val canceled = AtomicBoolean(false)
                    private val executed = AtomicBoolean(false)
                    override fun enqueue(callback: Callback<OpenAIResponse>) {
                        executed.set(true)
                        capturedCallback.set(callback)
                    }
                    override fun isExecuted(): Boolean = executed.get()
                    override fun clone(): Call<OpenAIResponse> = this
                    override fun isCanceled(): Boolean = canceled.get()
                    override fun cancel() {
                        canceled.set(true)
                        cancelCalled.set(true)
                    }
                    override fun execute(): Response<OpenAIResponse> = error("Sync unused")
                    override fun request(): Request = Request.Builder().url(url).build()
                    override fun timeout(): Timeout = Timeout.NONE
                }
                capturedCall.set(call)
                return call
            }

            override fun getModels(authorization: String): Call<OpenAIModelsResponse> = error("Unused")
            override fun getModels(headers: MutableMap<String, String>): Call<OpenAIModelsResponse> = error("Unused")
            override fun getModelsDynamic(url: String, headers: MutableMap<String, String>) = error("Unused")
            override fun getClaudeCompletion(url: String, headers: MutableMap<String, String>, requestBody: com.google.gson.JsonObject) = error("Unused")
            override fun getGeminiCompletion(url: String, apiKey: String, requestBody: com.google.gson.JsonObject) = error("Unused")
        }

        val sendCount = AtomicInteger(0)
        val svc = createManagedService(sendCount)
        svc.awaitAiMemoryIdleForTesting()

        val sbn = buildSbn(id = 901, sender = "Alice", message = "Late AI call")
        val wear = wearFromSbn(sbn)
        val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn, "Late AI call")!!
        val claimToken = "claim-f025-08-destroy"
        withContext(Dispatchers.IO) {
            dbUtils.tryAcquireNotificationClaim(eventKey, claimToken, "com.whatsapp", "alice", sbn.key, System.currentTimeMillis(), false)
        }

        invokeFetchOpenAiInternal(svc, mockOpenAiService, sbn, wear, claimToken)
        assertNotNull(capturedCallback.get())
        assertEquals(1, svc.getActiveAiCallCountForTesting())

        svc.onDestroy()

        assertTrue("onDestroy must cancel in-flight Retrofit Call", cancelCalled.get())
        assertTrue(capturedCall.get()!!.isCanceled)
        assertEquals(0, svc.getActiveAiCallCountForTesting())

        // Fire late response AND late failure after service destruction
        val lateBody = OpenAIResponse().apply {
            choices = listOf(
                Choice().apply {
                    message = ResponseMessage().apply {
                        role = "assistant"
                        content = "Late AI answer"
                    }
                }
            )
        }
        capturedCallback.get()!!.onResponse(capturedCall.get()!!, Response.success(lateBody))
        capturedCallback.get()!!.onFailure(capturedCall.get()!!, IOException("Canceled socket"))

        assertEquals("Destroyed service must NEVER dispatch late AI or fallback reply", 0, sendCount.get())
        val finalClaim = withContext(Dispatchers.IO) { dbUtils.getNotificationClaim(eventKey) }
        assertNotNull(finalClaim)
        assertEquals(ReplyExecutionStatus.CANCELLED.name, finalClaim!!.claimStatus)
        assertEquals(ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN, finalClaim.failureCategory)
    }

    @Test
    fun `F-025-08 pre-cancelled Retrofit Call suppresses onResponse and fallback dispatch even while service is alive`() = runBlocking {
        val capturedCallback = AtomicReference<Callback<OpenAIResponse>?>(null)
        val capturedCall = AtomicReference<Call<OpenAIResponse>?>(null)

        val mockOpenAiService = object : OpenAIService {
            override fun getChatCompletion(authorization: String, requestBody: OpenAIRequest): Call<OpenAIResponse> =
                error("Unused")

            override fun getChatCompletion(
                url: String,
                authorization: String,
                requestBody: OpenAIRequest
            ): Call<OpenAIResponse> {
                val call = object : Call<OpenAIResponse> {
                    private val canceled = AtomicBoolean(false)
                    private val executed = AtomicBoolean(false)
                    override fun enqueue(callback: Callback<OpenAIResponse>) {
                        executed.set(true)
                        capturedCallback.set(callback)
                    }
                    override fun isExecuted(): Boolean = executed.get()
                    override fun clone(): Call<OpenAIResponse> = this
                    override fun isCanceled(): Boolean = canceled.get()
                    override fun cancel() { canceled.set(true) }
                    override fun execute(): Response<OpenAIResponse> = error("Unused")
                    override fun request(): Request = Request.Builder().url(url).build()
                    override fun timeout(): Timeout = Timeout.NONE
                }
                capturedCall.set(call)
                return call
            }

            override fun getModels(authorization: String): Call<OpenAIModelsResponse> = error("Unused")
            override fun getModels(headers: MutableMap<String, String>): Call<OpenAIModelsResponse> = error("Unused")
            override fun getModelsDynamic(url: String, headers: MutableMap<String, String>) = error("Unused")
            override fun getClaudeCompletion(url: String, headers: MutableMap<String, String>, requestBody: com.google.gson.JsonObject) = error("Unused")
            override fun getGeminiCompletion(url: String, apiKey: String, requestBody: com.google.gson.JsonObject) = error("Unused")
        }

        val sendCount = AtomicInteger(0)
        val svc = createManagedService(sendCount)
        svc.awaitAiMemoryIdleForTesting()

        val sbn = buildSbn(id = 902, sender = "Bob", message = "Cancelled call check")
        val wear = wearFromSbn(sbn)
        val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn, "Cancelled call check")!!
        val claimToken = "claim-f025-08-precancel"
        withContext(Dispatchers.IO) {
            dbUtils.tryAcquireNotificationClaim(eventKey, claimToken, "com.whatsapp", "bob", sbn.key, System.currentTimeMillis(), false)
        }

        invokeFetchOpenAiInternal(svc, mockOpenAiService, sbn, wear, claimToken)
        capturedCall.get()!!.cancel()

        val validBody = OpenAIResponse().apply {
            choices = listOf(
                Choice().apply {
                    message = ResponseMessage().apply {
                        role = "assistant"
                        content = "Should be ignored"
                    }
                }
            )
        }
        capturedCallback.get()!!.onResponse(capturedCall.get()!!, Response.success(validBody))
        assertEquals(0, sendCount.get())
        assertEquals(0, svc.getActiveAiCallCountForTesting())
    }

    @Test
    fun `F-025-08 shared AI OkHttpClient handles local socket failure cleanly without duplicate POST`() {
        val serverHits = AtomicInteger(0)
        val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newSingleThreadExecutor()
        val running = AtomicBoolean(true)
        pool.execute {
            while (running.get()) {
                try {
                    val sock = serverSocket.accept()
                    serverHits.incrementAndGet()
                    sock.use { /* drop socket without HTTP response */ }
                } catch (_: Exception) {
                    break
                }
            }
        }
        try {
            val baseUrl = "http://127.0.0.1:${serverSocket.localPort}/"
            val retrofit = RetrofitInstance.getOpenAIRetrofitInstance(baseUrl)
            val svcApi = retrofit.create(OpenAIService::class.java)
            var failed = false
            try {
                svcApi.getChatCompletion(
                    "${baseUrl}v1/chat/completions",
                    "Bearer k",
                    OpenAIRequest("m", emptyList())
                ).execute()
            } catch (_: IOException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals(1, serverHits.get())
        } finally {
            running.set(false)
            runCatching { serverSocket.close() }
            pool.shutdownNow()
        }
    }

    // =====================================================================
    // F-025-09 — Strict Bounded Pre-Dispatch Retry Boundary
    // =====================================================================

    @Test
    fun `F-025-09 pre-dispatch FAILED with dispatchAttempted false recovers via bounded retry and clears retry counter on DISPATCHED`() = runBlocking {
        val sendCount = AtomicInteger(0)
        val svc = createManagedService(sendCount)
        svc.awaitAiMemoryIdleForTesting()

        val sbn = buildSbn(id = 910, sender = "Carol", message = "Pre-dispatch recover")
        val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn, "Pre-dispatch recover")!!

        // 1. Initial failure happens BEFORE dispatch (missing PendingIntent -> FAILED, dispatchAttempted == false)
        val brokenWear = wearFromSbn(sbn, includePendingIntent = false)
        val initialResult = withContext(Dispatchers.IO) {
            svc.sendActualReply(sbn, brokenWear, "Reply text", null, ReplySourceType.DEFAULT, null, null)
        }
        assertEquals(ReplyExecutionStatus.FAILED, initialResult.status)
        assertFalse(initialResult.dispatchAttempted)
        assertEquals(0, sendCount.get())

        // Verify claim and attempt both reflect FAILED pre-dispatch
        val claimBeforeRetry = withContext(Dispatchers.IO) { dbUtils.getNotificationClaim(eventKey) }
        val attemptBeforeRetry = withContext(Dispatchers.IO) { dbUtils.getAttemptById(initialResult.executionId) }
        assertEquals(ReplyExecutionStatus.FAILED.name, claimBeforeRetry!!.claimStatus)
        assertFalse(claimBeforeRetry.dispatchAttempted)
        assertEquals(ReplyExecutionStatus.FAILED.name, attemptBeforeRetry!!.status)
        assertFalse(attemptBeforeRetry.dispatchAttempted)

        // 2. Bounded retry with fresh valid capability succeeds once
        val validWear = wearFromSbn(sbn, includePendingIntent = true)
        val retryResult = withContext(Dispatchers.IO) {
            svc.retryPreDispatchFailure(sbn, validWear, "Reply text", null, ReplySourceType.DEFAULT, null)
        }
        assertEquals(ReplyExecutionStatus.DISPATCHED, retryResult.status)
        assertTrue(retryResult.dispatchedNormally)
        assertEquals(1, sendCount.get())
        assertEquals(1L, dbUtils.numReplies)

        // Retry state counter is cleared upon terminal DISPATCHED outcome
        assertEquals(0, PreDispatchRetryStateStore.getRetryCount(context, eventKey))

        // 3. Subsequent retry attempt after DISPATCHED is strictly denied (never duplicate dispatch)
        val postDispatchRetry = withContext(Dispatchers.IO) {
            svc.retryPreDispatchFailure(sbn, validWear, "Reply text", null, ReplySourceType.DEFAULT, null)
        }
        assertNotEquals(ReplyExecutionStatus.DISPATCHED, postDispatchRetry.status)
        assertEquals(1, sendCount.get())
    }

    @Test
    fun `F-025-09 enforces bounded MAX_PRE_DISPATCH_RETRIES across repeated pre-dispatch failures`() = runBlocking {
        val sendCount = AtomicInteger(0)
        val svc = createManagedService(sendCount)
        // Always throw SecurityException? No: simulate repeated pre-dispatch FAILED by passing missing RemoteInput / broken capability?
        // Wait: missing capability denies before reclaiming claim; use failing ReplyExecutionManager pre-dispatch SecurityException!
        svc.awaitAiMemoryIdleForTesting()

        val failingManager = ReplyExecutionManager(
            context,
            pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                // Make failure happen inside prepare/send? If PendingIntentSender throws PendingIntent.CanceledException
                // dispatchAttempted becomes true -> post-dispatch UNKNOWN/FAILED. So let's force a pre-dispatch claim fail
                // by reclaiming with custom coordinator, OR test directly with missing RemoteInput/PendingIntent after failed claim!
                sendCount.incrementAndGet()
            }
        )
        svc.setReplyExecutionManagerForTesting(failingManager)

        val sbn = buildSbn(id = 911, sender = "Dave", message = "Bounded cap test")
        val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn, "Bounded cap test")!!
        val badWear = wearFromSbn(sbn, includePendingIntent = false)
        val validWear = wearFromSbn(sbn, includePendingIntent = true)

        // Initial FAILED pre-dispatch
        val r0 = withContext(Dispatchers.IO) {
            svc.sendActualReply(sbn, badWear, "Hi", null, ReplySourceType.DEFAULT, null, null)
        }
        assertEquals(ReplyExecutionStatus.FAILED, r0.status)
        assertFalse(r0.dispatchAttempted)

        // Simulate 2 authorized pre-dispatch retries that themselves fail pre-dispatch cleanly
        val coordinator = PreDispatchRetryCoordinator(context)
        val now = System.currentTimeMillis()
        for (attemptIdx in 1..ReplyRetryPolicy.MAX_PRE_DISPATCH_RETRIES) {
            val tok = "retry-tok-$attemptIdx"
            val outcome = withContext(Dispatchers.IO) {
                coordinator.tryAcquireExplicitPreDispatchRetry(
                    dbUtils, sbn, validWear, eventKey, tok, "com.whatsapp", "dave", sbn.key, now + attemptIdx, false
                )
            }
            assertTrue("Retry #$attemptIdx must be authorized", outcome.acquired)
            assertEquals(RetryDecision.ALLOW_AUTHORIZED_RETRY, outcome.decision)
            // Transition back to FAILED with dispatchAttempted = false (e.g. validation aborted before send)
            val preFail = ReplyExecutionResult(tok, ReplyExecutionStatus.FAILED, false, false, ReplyExecutionManager.FAILURE_MISSING_PENDING_INTENT, "Aborted before send", now + attemptIdx)
            withContext(Dispatchers.IO) {
                dbUtils.recordAttemptAndClaimResult(
                    eventKey,
                    ReplyAttempt.createWithAttemptId(tok, ReplySourceType.DEFAULT, null, "com.whatsapp", sbn.key, "Hi", eventKey),
                    preFail,
                    "Dave",
                    "Bounded cap test",
                    sbn
                )
            }
        }

        // Retry #3 MUST be denied with DENY_RETRY_LIMIT_EXCEEDED
        val deniedOutcome = withContext(Dispatchers.IO) {
            coordinator.tryAcquireExplicitPreDispatchRetry(
                dbUtils, sbn, validWear, eventKey, "retry-tok-3", "com.whatsapp", "dave", sbn.key, now + 10L, false
            )
        }
        assertFalse(deniedOutcome.acquired)
        assertEquals(RetryDecision.DENY_RETRY_LIMIT_EXCEEDED, deniedOutcome.decision)
        assertEquals(0, sendCount.get())
    }

    @Test
    fun `F-025-09 blocks retry for UNKNOWN CANCELLED dispatchAttempted true mismatched attempt stale notif and destroyed service`() = runBlocking {
        val sendCount = AtomicInteger(0)
        val svc = createManagedService(sendCount)
        svc.awaitAiMemoryIdleForTesting()
        val coordinator = PreDispatchRetryCoordinator(context)
        val now = System.currentTimeMillis()

        // 1. UNKNOWN claim -> blocked
        val unknownSbn = buildSbn(id = 912, sender = "Eve", message = "Unknown msg", whenMs = now)
        val unknownKey = NotificationEventKeyBuilder.buildEventKey(unknownSbn, "Unknown msg")!!
        withContext(Dispatchers.IO) {
            dbUtils.tryAcquireNotificationClaim(unknownKey, "tok-u", "com.whatsapp", "eve", unknownSbn.key, now, false)
            dbUtils.updateNotificationClaimResult(
                unknownKey, "tok-u",
                ReplyExecutionResult("tok-u", ReplyExecutionStatus.UNKNOWN, true, false, ReplyExecutionManager.FAILURE_AMBIGUOUS_SEND_EXCEPTION, "Unknown", now)
            )
        }
        val unknownOutcome = withContext(Dispatchers.IO) {
            coordinator.tryAcquireExplicitPreDispatchRetry(
                dbUtils, unknownSbn, wearFromSbn(unknownSbn), unknownKey, "tok-u2", "com.whatsapp", "eve", unknownSbn.key, now, false
            )
        }
        assertFalse(unknownOutcome.acquired)
        assertEquals(RetryDecision.DENY_UNKNOWN_OUTCOME, unknownOutcome.decision)

        // 2. FAILED with dispatchAttempted = true -> blocked
        val postFailSbn = buildSbn(id = 913, sender = "Frank", message = "Post fail msg", whenMs = now)
        val postFailKey = NotificationEventKeyBuilder.buildEventKey(postFailSbn, "Post fail msg")!!
        withContext(Dispatchers.IO) {
            dbUtils.tryAcquireNotificationClaim(postFailKey, "tok-f", "com.whatsapp", "frank", postFailSbn.key, now, false)
            dbUtils.updateNotificationClaimResult(
                postFailKey, "tok-f",
                ReplyExecutionResult("tok-f", ReplyExecutionStatus.FAILED, true, false, ReplyExecutionManager.FAILURE_AMBIGUOUS_SEND_EXCEPTION, "Post dispatch fail", now)
            )
        }
        val postFailOutcome = withContext(Dispatchers.IO) {
            coordinator.tryAcquireExplicitPreDispatchRetry(
                dbUtils, postFailSbn, wearFromSbn(postFailSbn), postFailKey, "tok-f2", "com.whatsapp", "frank", postFailSbn.key, now, false
            )
        }
        assertFalse(postFailOutcome.acquired)
        assertEquals(RetryDecision.DENY_DUPLICATE_PRE_DISPATCH_FAILURE, postFailOutcome.decision)

        // 3. Stale notification (>2 min) even on eligible pre-dispatch FAILED -> blocked
        val preFailSbn = buildSbn(id = 914, sender = "Grace", message = "Grace msg", whenMs = now)
        val preFailKey = NotificationEventKeyBuilder.buildEventKey(preFailSbn, "Grace msg")!!
        withContext(Dispatchers.IO) {
            dbUtils.tryAcquireNotificationClaim(preFailKey, "tok-g", "com.whatsapp", "grace", preFailSbn.key, now, false)
            dbUtils.updateNotificationClaimResult(
                preFailKey, "tok-g",
                ReplyExecutionResult("tok-g", ReplyExecutionStatus.FAILED, false, false, ReplyExecutionManager.FAILURE_MISSING_PENDING_INTENT, "Pre fail", now)
            )
        }
        val staleSbn = buildSbn(id = 914, sender = "Grace", message = "Grace msg", whenMs = now - 5 * 60 * 1000L)
        val staleOutcome = withContext(Dispatchers.IO) {
            coordinator.tryAcquireExplicitPreDispatchRetry(
                dbUtils, staleSbn, wearFromSbn(staleSbn), preFailKey, "tok-g2", "com.whatsapp", "grace", staleSbn.key, now, false
            )
        }
        assertFalse(staleOutcome.acquired)
        assertEquals(RetryDecision.DENY_CANCELLED_OR_STALE, staleOutcome.decision)

        // 4. Destroyed service -> blocked
        val shutdownOutcome = withContext(Dispatchers.IO) {
            coordinator.tryAcquireExplicitPreDispatchRetry(
                dbUtils, preFailSbn, wearFromSbn(preFailSbn), preFailKey, "tok-g3", "com.whatsapp", "grace", preFailSbn.key, now, true
            )
        }
        assertFalse(shutdownOutcome.acquired)
        assertEquals(RetryDecision.DENY_SERVICE_SHUTDOWN, shutdownOutcome.decision)
        assertEquals(0, sendCount.get())
    }

    @Test
    fun `F-025-10 RuleNotificationProcessor blocks Sender colon and at-space image group notifications safely`() = runBlocking {
        withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Catch All",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.DOES_NOT_CONTAIN,
                    keywords = listOf("__never_match__"),
                    replyMessage = "Auto reply"
                )
            )
        }
        val sendCount = AtomicInteger(0)
        val svc = createManagedService(sendCount)
        val immediateExecutor = object : AbstractExecutorService(), java.util.concurrent.ScheduledExecutorService {
            override fun shutdown() {}
            override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
            override fun isShutdown(): Boolean = false
            override fun isTerminated(): Boolean = false
            override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
            override fun execute(command: Runnable) { command.run() }
            override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): java.util.concurrent.ScheduledFuture<*> {
                command.run()
                return java.util.concurrent.CompletableFuture.completedFuture(null) as java.util.concurrent.ScheduledFuture<*>
            }
            override fun <V : Any?> schedule(callable: java.util.concurrent.Callable<V>, delay: Long, unit: TimeUnit) = error("Unused")
            override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit) = error("Unused")
            override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit) = error("Unused")
        }

        val processor = RuleNotificationProcessor(
            context,
            immediateExecutor,
            dbUtils,
            svc.getRuleReservationCoordinatorForTesting()
        )
        val cb = object : RuleNotificationProcessor.ClaimAwareReplyDispatchCallback() {
            override fun onRuleMatchedSendReply(
                sbn: StatusBarNotification,
                notificationWear: NotificationWear,
                replyText: String,
                matchedRule: AutoReplyRule,
                claimToken: String?,
                eventKey: String?,
                reservation: RuleExecutionReservationCoordinator.RuleReservation?
            ) {
                svc.sendActualReply(sbn, notificationWear, replyText, matchedRule.name, ReplySourceType.RULE_STATIC, matchedRule, claimToken, reservation)
            }
            override fun onFallbackToLegacy(sbn: StatusBarNotification, claimToken: String?, eventKey: String?) {}
        }

        withContext(Dispatchers.IO) {
            // Image group notifications with isGroup=false -> suppressed
            processor.processNotification(buildSbn(id = 920, sender = "Alice: Family", message = "\uD83D\uDCF7 Photo", isGroup = false), cb)
            processor.processNotification(buildSbn(id = 921, sender = "Bob @ Work", message = "\uD83D\uDCF7", isGroup = false), cb)
            assertEquals("Image group heuristics must suppress both notifications", 0, sendCount.get())

            // Direct photo & email handle -> allowed
            processor.processNotification(buildSbn(id = 922, sender = "Alice", message = "\uD83D\uDCF7 Photo", isGroup = false), cb)
            processor.processNotification(buildSbn(id = 923, sender = "alice@example.com", message = "\uD83D\uDCF7 Photo", isGroup = false), cb)
            assertEquals("Direct contact photo and email handle must be replied to", 2, sendCount.get())
        }
    }

    // =====================================================================
    // F-025-11 — Persistent Retention Cleanup & Protected Rows
    // =====================================================================

    @Test
    fun `F-025-11 startup recovery replays staged memory first and then persists retention cleanup intervals`() = runBlocking {
        val nowMs = System.currentTimeMillis()
        val oldMs = nowMs - (40L * 24L * 60L * 60L * 1000L)

        // Seed staged post-dispatch memory + expired claim
        val priorAttemptId = "att-b3-replay-930"
        withContext(Dispatchers.IO) {
            val attempt = ReplyAttempt.createWithAttemptId(priorAttemptId, ReplySourceType.GLOBAL_AI, "AI", "com.whatsapp", "n-930", "Replayed reply", "evt-replay-930")
            dbUtils.recordAttemptResult(attempt, ReplyExecutionResult(priorAttemptId, ReplyExecutionStatus.DISPATCHED, true, true, null, "ok", nowMs - 2_000L), "Alice", "Hello", null)
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
                    incomingMessage = "Hello",
                    incomingEventKey = "in-evt-930",
                    legacyIncomingEventKey = null,
                    assistantReply = "Replayed reply",
                    extractedNotes = emptyList(),
                    memoryEnabled = true,
                    notesEnabled = false,
                    recordReplyLog = true,
                    keepCount = 20,
                    timestampMs = nowMs - 2_000L,
                    retryCount = 0,
                    createdAtMs = nowMs - 2_000L
                )
            )
            logsDb.notificationDispatchClaimDao().insertClaimIgnoreSync(
                NotificationDispatchClaimEntity(
                    eventKey = "expired-claim-930",
                    attemptId = "att-expired-930",
                    packageName = "com.whatsapp",
                    senderKey = "bob",
                    notificationKey = "k-old",
                    claimStatus = ReplyExecutionStatus.DISPATCHED.name,
                    failureCategory = null,
                    dispatchAttempted = true,
                    dispatchedNormally = true,
                    createdAtMs = oldMs,
                    updatedAtMs = oldMs,
                    expiresAtMs = oldMs + 1000L
                )
            )
        }

        val replayFinishedBeforeCleanup = AtomicBoolean(false)
        val svc = createManagedService { instance ->
            instance.setStartupReplayTestHookForTesting {
                // Pending entry must NOT yet be wiped before replay runs
                assertFalse(PendingPostDispatchMemoryStore.getPendingEntries(context).isEmpty())
                replayFinishedBeforeCleanup.set(true)
            }
        }
        svc.awaitAiMemoryIdleForTesting()

        assertTrue(replayFinishedBeforeCleanup.get())
        assertTrue(PendingPostDispatchMemoryStore.getPendingEntries(context).isEmpty())
        val (assistantCount, expiredClaim) = withContext(Dispatchers.IO) {
            memoryDb.aiMemoryDao().countAssistantMessagesByAttemptIdSync(priorAttemptId) to
                logsDb.notificationDispatchClaimDao().getClaimByEventKeySync("expired-claim-930")
        }
        assertEquals(1, assistantCount)
        assertNull(expiredClaim)
        assertTrue("Claims purge timestamp must persist", prefs.getLastClaimsPurgedTime() > 0L)
        assertTrue("History purge timestamp must persist", prefs.getLastPurgedTime() > 0L)

        // Simulate app restart (destroy service + reset PreferencesManager singleton)
        svc.onDestroy()
        createdServices.remove(svc)
        PreferencesManager.resetInstance()
        val enc = context.getSharedPreferences("test_stage025_b3_enc", Context.MODE_PRIVATE)
        PreferencesManager.setEncryptedPrefsProviderForTesting { enc }
        val prefsAfterRestart = PreferencesManager.getPreferencesInstance(context)

        assertTrue(prefsAfterRestart.getLastClaimsPurgedTime() > 0L)
        assertTrue(prefsAfterRestart.getLastPurgedTime() > 0L)
        assertFalse(
            "Restart immediately after purge must throttle redundant cleanup",
            dbUtils.isRetentionCleanupDue(System.currentTimeMillis())
        )
        val skippedSummary = withContext(Dispatchers.IO) {
            dbUtils.runPeriodicRetentionCleanupIfDue(System.currentTimeMillis())
        }
        assertFalse(skippedSummary.isClaimsRan)
        assertFalse(skippedSummary.isHistoryRan)
    }

    @Test
    fun `F-025-11 retention cleanup prunes all expired artifacts while strictly preserving UNKNOWN and in-flight rows`() = runBlocking {
        val nowMs = 1_750_000_000_000L
        val oldMs = nowMs - (45L * 24L * 60L * 60L * 1000L) // >30d
        val recentMs = nowMs - (5L * 24L * 60L * 60L * 1000L) // 5d

        withContext(Dispatchers.IO) {
            // 1. Claims: expired DISPATCHED + recent DISPATCHED + old UNKNOWN + old CLAIMED
            val claimDao = logsDb.notificationDispatchClaimDao()
            claimDao.insertClaimIgnoreSync(
                NotificationDispatchClaimEntity("exp-disp", "att-exp-disp", "com.whatsapp", "s", "k", ReplyExecutionStatus.DISPATCHED.name, null, true, true, oldMs, oldMs, oldMs + 1000L)
            )
            claimDao.insertClaimIgnoreSync(
                NotificationDispatchClaimEntity("rec-disp", "att-rec-disp", "com.whatsapp", "s", "k", ReplyExecutionStatus.DISPATCHED.name, null, true, true, recentMs, recentMs, nowMs + 100_000L)
            )
            claimDao.insertClaimIgnoreSync(
                NotificationDispatchClaimEntity("old-unknown", "att-old-unk", "com.whatsapp", "s", "k", ReplyExecutionStatus.UNKNOWN.name, ReplyExecutionManager.FAILURE_AMBIGUOUS_SEND_EXCEPTION, true, false, oldMs, oldMs, oldMs + 1000L)
            )
            claimDao.insertClaimIgnoreSync(
                NotificationDispatchClaimEntity("old-claimed", "att-old-clm", "com.whatsapp", "s", "k", NotificationDispatchClaimEntity.STATUS_CLAIMED, null, false, false, oldMs, oldMs, oldMs + 1000L)
            )

            // 2. ReplyAttempts & MessageLogs: old resolved DISPATCHED vs old UNKNOWN vs recent
            val attemptDao = logsDb.replyAttemptDao()
            attemptDao.upsertRawSync(
                ReplyAttemptEntity("att-exp-disp", "DEFAULT", null, "com.whatsapp", "k", "Alice", "old", "r", ReplyExecutionStatus.DISPATCHED.name, true, true, null, oldMs, oldMs, "exp-disp")
            )
            attemptDao.upsertRawSync(
                ReplyAttemptEntity("att-old-unk", "DEFAULT", null, "com.whatsapp", "k", "Alice", "unk", "r", ReplyExecutionStatus.UNKNOWN.name, true, false, ReplyExecutionManager.FAILURE_AMBIGUOUS_SEND_EXCEPTION, oldMs, oldMs, "old-unknown")
            )
            attemptDao.upsertRawSync(
                ReplyAttemptEntity("att-rec-disp", "DEFAULT", null, "com.whatsapp", "k", "Alice", "new", "r", ReplyExecutionStatus.DISPATCHED.name, true, true, null, recentMs, recentMs, "rec-disp")
            )

            logsDb.appPackageDao().insertAppPackage(AppPackage("com.whatsapp"))
            val pkgIdx = logsDb.appPackageDao().getPackageIndex("com.whatsapp")
            logsDb.logsDao().logReply(MessageLog(pkgIdx, "Alice", "old", oldMs, "r", null, oldMs, ReplyHistoryStatus.DISPATCHED, "att-exp-disp"))
            logsDb.logsDao().logReply(MessageLog(pkgIdx, "Alice", "new", recentMs, "r", null, recentMs, ReplyHistoryStatus.DISPATCHED, "att-rec-disp"))

            // 3. AI memory & notes: expired vs protected UNKNOWN attempt vs recent
            val aiDao = memoryDb.aiMemoryDao()
            aiDao.insertConversationMessageSync(AiConversationMessage(packageName = "com.whatsapp", senderKey = "alice", senderDisplayName = "Alice", role = "assistant", content = "old mem", timestamp = oldMs, attemptId = "att-exp-disp", executionStatus = AiConversationMessage.STATUS_DISPATCHED))
            aiDao.insertConversationMessageSync(AiConversationMessage(packageName = "com.whatsapp", senderKey = "alice", senderDisplayName = "Alice", role = "assistant", content = "unk mem", timestamp = oldMs, attemptId = "att-old-unk", executionStatus = AiConversationMessage.STATUS_DISPATCHED))
            aiDao.insertConversationMessageSync(AiConversationMessage(packageName = "com.whatsapp", senderKey = "alice", senderDisplayName = "Alice", role = "assistant", content = "new mem", timestamp = recentMs, attemptId = "att-rec-disp", executionStatus = AiConversationMessage.STATUS_DISPATCHED))
            aiDao.insertNoteSync(AiNote(senderName = "Alice", packageName = "com.whatsapp", noteText = "old note", sourceMessage = "old", createdAt = oldMs, attemptId = "att-exp-disp", noteDedupKey = "k1", senderKey = "alice"))
            aiDao.insertNoteSync(AiNote(senderName = "Alice", packageName = "com.whatsapp", noteText = "unk note", sourceMessage = "unk", createdAt = oldMs, attemptId = "att-old-unk", noteDedupKey = "k2", senderKey = "alice"))

            // 4. Rule execution logs: expired vs recent
            val ruleId = rulesDb.ruleDao().insertRule(
                AutoReplyRule(name = "Retention Rule", isEnabled = true, selectedPackages = listOf("com.whatsapp"), matchType = MatchType.DOES_NOT_CONTAIN, keywords = listOf("__never_match__"), replyMessage = "ok")
            )
            rulesDb.executionLogDao().insertLogSync(RuleExecutionLog(ruleId = ruleId, packageName = "com.whatsapp", senderName = "Alice", notificationKey = "nk1", messageTimestamp = oldMs, repliedAt = oldMs))
            rulesDb.executionLogDao().insertLogSync(RuleExecutionLog(ruleId = ruleId, packageName = "com.whatsapp", senderName = "Alice", notificationKey = "nk2", messageTimestamp = recentMs, repliedAt = recentMs))

            // 5. PreDispatchRetryStateStore: expired vs fresh
            val retryPrefs = PreDispatchRetryStateStore.SharedPrefsBackend(context)
            retryPrefs.incrementRetryCount("evt-old-retry", oldMs)
            retryPrefs.incrementRetryCount("evt-fresh-retry", nowMs - 1_000L)

            val summary = dbUtils.runPeriodicRetentionCleanupIfDue(nowMs, true)
            assertTrue(summary.isClaimsRan)
            assertTrue(summary.isHistoryRan)
            assertTrue(summary.isClaimsSucceeded)
            assertTrue(summary.isHistorySucceeded)

            // Claims assertions
            assertNull(claimDao.getClaimByEventKeySync("exp-disp"))
            assertNotNull(claimDao.getClaimByEventKeySync("rec-disp"))
            assertNotNull("Old UNKNOWN claim MUST NEVER be deleted", claimDao.getClaimByEventKeySync("old-unknown"))
            assertNotNull("Old in-flight CLAIMED claim MUST NEVER be deleted", claimDao.getClaimByEventKeySync("old-claimed"))

            // ReplyAttempts & MessageLogs assertions
            assertNull(attemptDao.getAttemptByIdSync("att-exp-disp"))
            assertNotNull("Old UNKNOWN attempt MUST NEVER be deleted", attemptDao.getAttemptByIdSync("att-old-unk"))
            assertNotNull(attemptDao.getAttemptByIdSync("att-rec-disp"))
            assertEquals(1L, logsDb.logsDao().getNumReplies())

            // AI memory & notes assertions
            val remainingMsgs = aiDao.getAllConversationMessagesSync().map { it.content }
            assertFalse(remainingMsgs.contains("old mem"))
            assertTrue("Protected UNKNOWN attempt AI message must remain", remainingMsgs.contains("unk mem"))
            assertTrue(remainingMsgs.contains("new mem"))
            assertEquals(1, aiDao.getTotalNoteCountSync())

            // Rule execution logs assertions
            assertEquals(1, rulesDb.executionLogDao().getReplyCountForRuleAndSenderSince(ruleId, "com.whatsapp", "Alice", 0L))

            // PreDispatchRetryStateStore assertions
            assertEquals(0, PreDispatchRetryStateStore.getRetryCount(context, "evt-old-retry"))
            assertEquals(1, PreDispatchRetryStateStore.getRetryCount(context, "evt-fresh-retry"))
        }
    }
}
