package com.parishod.watomatic.service

import android.app.Notification
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
import com.parishod.watomatic.R
import com.parishod.watomatic.activity.history.ReplyHistoryAdapter
import com.parishod.watomatic.model.ai.AiConversationMessage
import com.parishod.watomatic.model.ai.AiMemoryDatabase
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.logs.NotificationDispatchClaimEntity
import com.parishod.watomatic.model.logs.ReplyAttemptEntity
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.rules.AutoReplyRule
import com.parishod.watomatic.model.rules.AutoReplyRulesDatabase
import com.parishod.watomatic.model.rules.MatchType
import com.parishod.watomatic.model.utils.Constants
import com.parishod.watomatic.model.utils.DbUtils
import com.parishod.watomatic.network.OpenAIService
import com.parishod.watomatic.network.model.openai.Choice
import com.parishod.watomatic.network.model.openai.OpenAIModelsResponse
import com.parishod.watomatic.network.model.openai.OpenAIRequest
import com.parishod.watomatic.network.model.openai.OpenAIResponse
import com.parishod.watomatic.network.model.openai.ResponseMessage
import com.parishod.watomatic.service.reply.NotificationEventKeyBuilder
import com.parishod.watomatic.service.reply.PendingClaimHandoff
import com.parishod.watomatic.service.reply.ReplyExecutionManager
import com.parishod.watomatic.service.reply.ReplyExecutionStatus
import com.parishod.watomatic.service.reply.ReplySourceType
import com.parishod.watomatic.service.rules.RoomRuleExecutionLogStore
import com.parishod.watomatic.service.rules.RuleNotificationProcessor
import kotlinx.coroutines.Dispatchers
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Stage025ReliabilityRegressionTest {

    private lateinit var context: Context
    private lateinit var service: NotificationService
    private lateinit var prefs: PreferencesManager
    private lateinit var logsDb: MessageLogsDB
    private lateinit var rulesDb: AutoReplyRulesDatabase
    private lateinit var dbUtils: DbUtils

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferencesManager.resetInstance()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        val enc = context.getSharedPreferences("test_stage025_enc", Context.MODE_PRIVATE)
        enc.edit().clear().commit()
        PreferencesManager.setEncryptedPrefsProviderForTesting { enc }
        prefs = PreferencesManager.getPreferencesInstance(context)
        prefs.setServicePref(true)
        prefs.setEnableByokReplies(false)
        prefs.setAppendWatomaticAttribution(false)
        prefs.setRuleFallbackToGlobalEnabled(false)

        PendingClaimHandoff.clear()
        MessageLogsDB.resetInstance()
        context.deleteDatabase(Constants.LOGS_DB_NAME)
        AutoReplyRulesDatabase.resetInstance()
        context.deleteDatabase(AutoReplyRulesDatabase.DB_NAME)
        AiMemoryDatabase.resetInstance()
        context.deleteDatabase(AiMemoryDatabase.DB_NAME)

        logsDb = MessageLogsDB.getInstance(context)
        rulesDb = AutoReplyRulesDatabase.getInstance(context)
        dbUtils = DbUtils(context)
        service = Robolectric.buildService(NotificationService::class.java).create().get()
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        try {
            service.onDestroy()
        } catch (_: Exception) {
        }
        PendingClaimHandoff.clear()
        MessageLogsDB.resetInstance()
        context.deleteDatabase(Constants.LOGS_DB_NAME)
        AutoReplyRulesDatabase.resetInstance()
        context.deleteDatabase(AutoReplyRulesDatabase.DB_NAME)
        AiMemoryDatabase.resetInstance()
        context.deleteDatabase(AiMemoryDatabase.DB_NAME)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("test_stage025_enc", Context.MODE_PRIVATE).edit().clear().commit()
        PreferencesManager.resetInstance()
        ShadowLog.clear()
    }

    private fun buildSbn(
        pkg: String = "com.whatsapp",
        sender: String? = "Alice",
        message: String? = "Hey there!",
        id: Int = 10,
        whenMs: Long = System.currentTimeMillis(),
        isGroup: Boolean = false
    ): StatusBarNotification {
        val replyPi = PendingIntent.getBroadcast(
            context,
            id,
            Intent("com.parishod.watomatic.STAGE025_DISPATCH").setPackage(context.packageName),
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

    private fun wearFromSbn(sbn: StatusBarNotification, nullPi: Boolean = false): NotificationWear {
        val ri = RemoteInput.Builder("reply_key").setLabel("Reply").setAllowFreeFormInput(true).build()
        val pi = if (nullPi) null else PendingIntent.getBroadcast(
            context,
            sbn.id + 1000,
            Intent("com.parishod.watomatic.STAGE025_WEAR").setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        return NotificationWear(sbn.packageName, pi, listOf(ri), null, sbn.tag, sbn.id.toString())
    }

    @Test
    fun `identical notification redelivery before and after DB instance reset is suppressed persistently`() {
        val sendCount = AtomicInteger(0)
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
            sendCount.incrementAndGet()
        })
        service.setReplyExecutionManagerForTesting(manager)

        val fixedWhen = System.currentTimeMillis()
        val sbn1 = buildSbn(id = 41, message = "Track order 55", whenMs = fixedWhen)
        val firstResult = service.sendActualReply(sbn1, wearFromSbn(sbn1), "On it!", null, ReplySourceType.DEFAULT)
        assertEquals(ReplyExecutionStatus.DISPATCHED, firstResult.status)
        assertEquals(1, sendCount.get())

        // Simulate immediate duplicate redelivery
        val sbnDup = buildSbn(id = 41, message = "Track order 55", whenMs = fixedWhen)
        val dupResult = service.sendActualReply(sbnDup, wearFromSbn(sbnDup), "On it!", null, ReplySourceType.DEFAULT)
        assertEquals(ReplyExecutionStatus.CANCELLED, dupResult.status)
        assertFalse(dupResult.dispatchAttempted)
        assertEquals(ReplyExecutionManager.FAILURE_DUPLICATE_EVENT, dupResult.failureCategory)
        assertEquals(1, sendCount.get())

        // Simulate app/service restart: destroy service, reset singletons (preserving SQLite file on disk), recreate service
        service.onDestroy()
        MessageLogsDB.resetInstance()
        service = Robolectric.buildService(NotificationService::class.java).create().get()
        val sendCountAfterRestart = AtomicInteger(0)
        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCountAfterRestart.incrementAndGet()
            })
        )

        val sbnAfterRestart = buildSbn(id = 41, message = "Track order 55", whenMs = fixedWhen)
        val afterRestartResult = service.sendActualReply(sbnAfterRestart, wearFromSbn(sbnAfterRestart), "On it!", null, ReplySourceType.DEFAULT)
        assertEquals(ReplyExecutionStatus.CANCELLED, afterRestartResult.status)
        assertFalse(afterRestartResult.dispatchAttempted)
        assertEquals(0, sendCountAfterRestart.get())

        // DB still holds exactly one reply log and one dispatched attempt
        val freshDb = MessageLogsDB.getInstance(context)
        assertEquals(1L, freshDb.logsDao().getNumReplies())
        assertEquals(1, freshDb.replyAttemptDao().getTotalAttemptCountSync())
    }

    @Test
    fun `same sender different messages both dispatch without sender-only suppression`() {
        val sendCount = AtomicInteger(0)
        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )

        val sbn1 = buildSbn(sender = "Alice", message = "First question", id = 50, whenMs = 1700000200000L)
        val sbn2 = buildSbn(sender = "Alice", message = "Second question", id = 51, whenMs = 1700000205000L)

        val res1 = service.sendActualReply(sbn1, wearFromSbn(sbn1), "Reply 1", null, ReplySourceType.DEFAULT)
        val res2 = service.sendActualReply(sbn2, wearFromSbn(sbn2), "Reply 2", null, ReplySourceType.DEFAULT)

        assertEquals(ReplyExecutionStatus.DISPATCHED, res1.status)
        assertEquals(ReplyExecutionStatus.DISPATCHED, res2.status)
        assertEquals(2, sendCount.get())
        assertEquals(2L, dbUtils.numReplies)
    }

    @Test
    fun `failed or cancelled or UNKNOWN rule dispatch does not consume rule cooldown or frequency limits`() = runBlocking {
        val (ruleId, rule) = withContext(Dispatchers.IO) {
            val id = rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Strict Limited Rule",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("price"),
                    replyMessage = "It costs $10",
                    cooldownSeconds = 3600,
                    maxRepliesPerSender = 1,
                    frequencyWindowMinutes = 60
                )
            )
            id to rulesDb.ruleDao().getRuleById(id)!!
        }
        val logStore = RoomRuleExecutionLogStore(rulesDb.executionLogDao())

        // 1. Ambiguous UNKNOWN failure during dispatch
        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                throw RuntimeException("IPC error")
            })
        )
        val sbnFail = buildSbn(sender = "Bob", message = "What is the price?", id = 60, whenMs = System.currentTimeMillis())
        val unkResult = service.sendActualReply(
            sbnFail, wearFromSbn(sbnFail), "It costs $10", rule.name, ReplySourceType.RULE_STATIC, rule, null
        )
        assertEquals(ReplyExecutionStatus.UNKNOWN, unkResult.status)

        // Cooldown/limit MUST remain unconsumed (0 rule logs)
        withContext(Dispatchers.IO) {
            assertNull(logStore.getLastReplyTimeForRuleAndSender(ruleId, "com.whatsapp", "Bob"))
            assertEquals(0, logStore.getReplyCountForRuleAndSenderSince(ruleId, "com.whatsapp", "Bob", 0L))
        }

        // Re-delivering the exact same UNKNOWN notification MUST remain locked
        val blockedRetry = service.sendActualReply(
            sbnFail, wearFromSbn(sbnFail), "It costs $10", rule.name, ReplySourceType.RULE_STATIC, rule, null
        )
        assertEquals(ReplyExecutionStatus.CANCELLED, blockedRetry.status)
        assertEquals(ReplyExecutionManager.FAILURE_UNKNOWN_OUTCOME_LOCKED, blockedRetry.failureCategory)
        assertFalse(blockedRetry.dispatchAttempted)

        // 2. A genuinely new notification from Bob CAN still match and dispatch, and THAT one consumes cooldown
        val dispatchedCount = AtomicInteger(0)
        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                dispatchedCount.incrementAndGet()
            })
        )
        val sbnNew = buildSbn(sender = "Bob", message = "Checking price again", id = 61, whenMs = System.currentTimeMillis() + 5000L)
        val okResult = service.sendActualReply(
            sbnNew, wearFromSbn(sbnNew), "It costs $10", rule.name, ReplySourceType.RULE_STATIC, rule, null
        )
        service.awaitAiMemoryIdleForTesting()
        assertEquals(ReplyExecutionStatus.DISPATCHED, okResult.status)
        assertEquals(1, dispatchedCount.get())
        withContext(Dispatchers.IO) {
            assertNotNull(logStore.getLastReplyTimeForRuleAndSender(ruleId, "com.whatsapp", "Bob"))
            assertEquals(1, logStore.getReplyCountForRuleAndSenderSince(ruleId, "com.whatsapp", "Bob", 0L))
        }
    }

    @Test
    fun `rule AI fallback preserves matched rule metadata in history and attempts`() {
        val sendCount = AtomicInteger(0)
        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )

        val matchedRule = AutoReplyRule(
            id = 77L,
            name = "VIP Support",
            isEnabled = true,
            selectedPackages = listOf("com.whatsapp"),
            matchType = MatchType.CONTAINS,
            keywords = listOf("help"),
            replyMessage = "Fallback VIP message",
            useAiReply = true
        )

        val errResponse = Response.error<OpenAIResponse>(500, """{"error":"down"}""".toResponseBody("application/json".toMediaType()))
        val fakeApi = object : OpenAIService {
            override fun getChatCompletion(authorization: String, requestBody: OpenAIRequest): Call<OpenAIResponse> = immediateCall(errResponse)
            override fun getChatCompletion(url: String, auth: String, request: OpenAIRequest): Call<OpenAIResponse> = immediateCall(errResponse)
            override fun getClaudeCompletion(url: String, headers: Map<String, String>, request: JsonObject): Call<JsonObject> = error("unused")
            override fun getGeminiCompletion(url: String, apiKey: String, request: JsonObject): Call<JsonObject> = error("unused")
            override fun getModels(authorization: String): Call<OpenAIModelsResponse> = error("unused")
            override fun getModels(headers: Map<String, String>): Call<OpenAIModelsResponse> = error("unused")
            override fun getModelsDynamic(url: String, headers: Map<String, String>): Call<JsonElement> = error("unused")
        }

        val fetchInternal = NotificationService::class.java.getDeclaredMethod(
            "fetchOpenAiCompatibleReplyInternal",
            PreferencesManager::class.java, OpenAIService::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            String::class.java, List::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, String::class.java, StatusBarNotification::class.java,
            NotificationWear::class.java, String::class.java, AutoReplyRule::class.java, String::class.java
        ).apply { isAccessible = true }

        val sbn = buildSbn(sender = "VIP User", message = "Need help urgently", id = 77)
        fetchInternal.invoke(
            service, prefs, fakeApi, "https://api.openai.com/", "sk-secret",
            "gpt-4o-mini", "sys", "com.whatsapp", "vip user", "VIP User",
            "Need help urgently", emptyList<AiConversationMessage>(), false, false, 10,
            "VIP Support • AI", sbn, wearFromSbn(sbn), "Fallback VIP message", matchedRule, null
        )

        assertEquals(1, sendCount.get())
        val attempt = logsDb.replyAttemptDao().getAllAttemptsSync().single()
        assertEquals(ReplySourceType.AI_FALLBACK.name, attempt.sourceType)
        assertTrue(attempt.sourceLabel?.contains("VIP Support") == true)
        assertTrue(attempt.sourceLabel?.contains(context.getString(R.string.history_source_ai_fallback)) == true)
    }

    @Test
    fun `repeated Retrofit callbacks trigger only one actual dispatch`() {
        val sendCount = AtomicInteger(0)
        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )

        var capturedCallback: Callback<OpenAIResponse>? = null
        val capturingApi = object : OpenAIService {
            override fun getChatCompletion(authorization: String, requestBody: OpenAIRequest): Call<OpenAIResponse> = error("unused")
            override fun getChatCompletion(url: String, auth: String, request: OpenAIRequest): Call<OpenAIResponse> = object : Call<OpenAIResponse> {
                override fun enqueue(callback: Callback<OpenAIResponse>) { capturedCallback = callback }
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

        val sbn = buildSbn(id = 88, sender = "Eve", message = "Double callback check")
        val okResp = OpenAIResponse().apply {
            choices = listOf(Choice().apply {
                message = ResponseMessage().apply {
                    role = "assistant"
                    content = "Hello Eve!"
                }
            })
        }

        val fetchOpenAi = NotificationService::class.java.getDeclaredMethod(
            "fetchOpenAiCompatibleReply",
            PreferencesManager::class.java, OpenAIService::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            String::class.java, List::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, String::class.java, StatusBarNotification::class.java,
            NotificationWear::class.java, String::class.java
        ).apply { isAccessible = true }

        fetchOpenAi.invoke(
            service, prefs, capturingApi, "https://api.openai.com/", "sk-secret",
            "gpt-4o-mini", "sys", "com.whatsapp", "eve", "Eve",
            "Double callback check", emptyList<AiConversationMessage>(), false, false, 10,
            "AI", sbn, wearFromSbn(sbn), "Fallback"
        )

        val call = capturingApi.getChatCompletion("https://localhost/", "Bearer sk", OpenAIRequest("m", emptyList()))
        capturedCallback!!.onResponse(call, Response.success(okResp))
        // Duplicate callbacks (both success and failure) MUST be ignored
        capturedCallback!!.onResponse(call, Response.success(okResp))
        capturedCallback!!.onFailure(call, RuntimeException("duplicate late failure"))

        assertEquals(1, sendCount.get())
        assertEquals(1L, dbUtils.numReplies)
    }

    @Test
    fun `AI failure with memory and notes does not create phantom assistant memory, AI notes, or reply logs`() = runBlocking {
        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                throw PendingIntent.CanceledException("expired before AI completed")
            })
        )
        val handleResult = NotificationService::class.java.getDeclaredMethod(
            "handleSuccessfulAiReplyWithResult",
            PreferencesManager::class.java, StatusBarNotification::class.java, NotificationWear::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
        ).apply { isAccessible = true }

        val sbn = buildSbn(id = 91, sender = "Frank", message = "Remind me to call John")
        val res = handleResult.invoke(
            service, prefs, sbn, wearFromSbn(sbn),
            "com.whatsapp", "frank", "Frank", "Remind me to call John",
            "Sure! [[NOTE: Call John tomorrow]]", true, true, 10, "AI"
        ) as com.parishod.watomatic.service.reply.ReplyExecutionResult

        service.awaitAiMemoryIdleForTesting()

        assertEquals(ReplyExecutionStatus.CANCELLED, res.status)
        assertEquals(0L, dbUtils.numReplies)

        withContext(Dispatchers.IO) {
            val aiDao = AiMemoryDatabase.getInstance(context).aiMemoryDao()
            assertEquals(0, aiDao.getPendingNoteCountSync())
            assertEquals(0, aiDao.getAiReplyCountSinceSync("com.whatsapp", "frank", 0L))
            val msgs = aiDao.getRecentMessagesDescSync("com.whatsapp", "frank", 10)
            assertTrue(msgs.none { it.role == "assistant" })
        }
    }

    @Test
    fun `claim failure fails closed without sending or retrying`() {
        val sendCount = AtomicInteger(0)
        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )
        service.setDbUtilsForTesting(object : DbUtils(context) {
            override fun tryAcquireNotificationClaim(
                eventKey: String,
                claimToken: String,
                packageName: String,
                senderKey: String,
                notificationKey: String?,
                nowMs: Long,
                allowRetryOnPreDispatchFailure: Boolean
            ): com.parishod.watomatic.model.logs.ClaimAcquireOutcome {
                throw IllegalStateException("SQLite disk full")
            }
        })

        val sbn = buildSbn(id = 95, sender = "Gina", message = "Fail closed check")
        val res = service.sendActualReply(sbn, wearFromSbn(sbn), "Reply", null, ReplySourceType.DEFAULT)

        assertEquals(ReplyExecutionStatus.FAILED, res.status)
        assertEquals(ReplyExecutionManager.FAILURE_CLAIM_PERSISTENCE_ERROR, res.failureCategory)
        assertFalse(res.dispatchAttempted)
        assertEquals(0, sendCount.get())
    }

    @Test
    fun `service onCreate recovers interrupted records left from previous crash before handling new traffic`() {
        // Seed DB with interrupted DISPATCHING and GENERATED records
        val sbnInterrupted = buildSbn(id = 120, sender = "Harry", message = "Were we disconnected?")
        val interruptedKey = NotificationEventKeyBuilder.buildEventKey(sbnInterrupted)!!
        logsDb.replyAttemptDao().upsertAttemptSafely(
            ReplyAttemptEntity(
                attemptId = "prev-dispatching",
                eventKey = interruptedKey,
                packageName = "com.whatsapp",
                senderName = "Harry",
                incomingMessage = "Were we disconnected?",
                notificationKey = sbnInterrupted.key,
                replyText = "On my way",
                sourceType = "DEFAULT",
                sourceLabel = "Default",
                status = ReplyExecutionStatus.DISPATCHING.name,
                dispatchAttempted = true,
                dispatchedNormally = false,
                failureCategory = null,
                createdAtMs = 1000L,
                updatedAtMs = 1000L
            )
        )
        logsDb.notificationDispatchClaimDao().insertClaimIgnoreSync(
            NotificationDispatchClaimEntity(
                eventKey = interruptedKey,
                attemptId = "prev-dispatching",
                packageName = "com.whatsapp",
                senderKey = "harry",
                notificationKey = sbnInterrupted.key,
                claimStatus = ReplyExecutionStatus.DISPATCHING.name,
                failureCategory = null,
                dispatchAttempted = true,
                dispatchedNormally = false,
                createdAtMs = 1000L,
                updatedAtMs = 1000L,
                expiresAtMs = 999999999L
            )
        )

        // Destroy old service and create a fresh one simulating process restart -> triggers onCreate() recovery
        service.onDestroy()
        service = Robolectric.buildService(NotificationService::class.java).create().get()
        val sendCount = AtomicInteger(0)
        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )

        val recoveredAttempt = logsDb.replyAttemptDao().getAttemptByIdSync("prev-dispatching")!!
        assertEquals("UNKNOWN", recoveredAttempt.status)
        assertEquals(ReplyExecutionManager.FAILURE_DISPATCHING_INTERRUPTED, recoveredAttempt.failureCategory)
        assertEquals("UNKNOWN", logsDb.notificationDispatchClaimDao().getClaimByEventKeySync(interruptedKey)?.claimStatus)

        // Attempting to replay the interrupted notification must be blocked as UNKNOWN_OUTCOME_LOCKED
        val replay = service.sendActualReply(sbnInterrupted, wearFromSbn(sbnInterrupted), "On my way", null, ReplySourceType.DEFAULT)
        assertEquals(ReplyExecutionStatus.CANCELLED, replay.status)
        assertEquals(ReplyExecutionManager.FAILURE_UNKNOWN_OUTCOME_LOCKED, replay.failureCategory)
        assertEquals(0, sendCount.get())
        assertEquals(0L, dbUtils.numReplies)
    }

    @Test
    fun `logs never leak secrets or incoming private text during suppression or errors`() {
        val secretMsg = "Private OTP 884921 for bank login"
        val secretApiKey = "sk-live-very-secret-key-999"
        prefs.saveOpenAIApiKey(secretApiKey)

        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> })
        )
        val sbn = buildSbn(id = 130, sender = "ConfidentialUser", message = secretMsg)
        service.sendActualReply(sbn, wearFromSbn(sbn), "Received", null, ReplySourceType.DEFAULT)
        service.sendActualReply(sbn, wearFromSbn(sbn), "Received", null, ReplySourceType.DEFAULT)

        val allLogs = ShadowLog.getLogs().joinToString("\n") { "${it.msg} ${it.throwable}" }
        assertFalse(allLogs.contains(secretMsg))
        assertFalse(allLogs.contains("884921"))
        assertFalse(allLogs.contains(secretApiKey))
        assertFalse(allLogs.contains("ConfidentialUser"))
    }

    @Test
    fun `all Stage 025 failure categories map to valid reply history localized string resources`() {
        val categories = listOf(
            ReplyExecutionManager.FAILURE_DUPLICATE_EVENT,
            ReplyExecutionManager.FAILURE_UNKNOWN_OUTCOME_LOCKED,
            ReplyExecutionManager.FAILURE_GENERATED_INTERRUPTED,
            ReplyExecutionManager.FAILURE_DISPATCHING_INTERRUPTED,
            ReplyExecutionManager.FAILURE_CLAIM_PERSISTENCE_ERROR,
            ReplyExecutionManager.FAILURE_INVALID_ATTEMPT_STATE
        )
        for (category in categories) {
            val resId = ReplyHistoryAdapter.safeStatusNoteResFor(ReplyExecutionStatus.UNKNOWN.name, category)
            assertNotNull("Expected string resource for $category", resId)
            val resolved = context.getString(resId!!)
            assertTrue(resolved.isNotBlank())
        }
        assertEquals(
            com.parishod.watomatic.R.string.history_note_reason_invalid_attempt_state,
            ReplyHistoryAdapter.safeStatusNoteResFor(
                ReplyExecutionStatus.FAILED.name,
                ReplyExecutionManager.FAILURE_INVALID_ATTEMPT_STATE
            )
        )
    }

    @Test
    fun `concurrent RuleNotificationProcessor submissions suppress duplicate and preserve single dispatch`() = runBlocking {
        withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Quick Rule",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("ping"),
                    replyMessage = "pong"
                )
            )
        }
        val executor = Executors.newFixedThreadPool(4)
        val ruleExecutor = Executors.newSingleThreadScheduledExecutor()
        val processor = RuleNotificationProcessor(context, ruleExecutor, dbUtils)
        val dispatchCount = AtomicInteger(0)
        val startGate = CountDownLatch(1)
        try {
            val sbn = buildSbn(id = 140, sender = "ConcurrentSender", message = "ping now")
            val cb = object : RuleNotificationProcessor.ReplyDispatchCallback {
                override fun onRuleMatchedSendReply(
                    sbn: StatusBarNotification,
                    notificationWear: NotificationWear,
                    replyText: String,
                    matchedRule: AutoReplyRule
                ) {
                    val token = PendingClaimHandoff.consume(NotificationEventKeyBuilder.buildEventKey(sbn))
                    val res = service.sendActualReply(sbn, notificationWear, replyText, matchedRule.name, ReplySourceType.RULE_STATIC, matchedRule, token)
                    if (res.isDispatched()) {
                        dispatchCount.incrementAndGet()
                    }
                }
                override fun onFallbackToLegacy(sbn: StatusBarNotification) {}
            }
            service.setReplyExecutionManagerForTesting(
                ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> })
            )
            val futures = (1..6).map {
                executor.submit {
                    startGate.await(5, TimeUnit.SECONDS)
                    processor.processNotification(sbn, cb)
                }
            }
            startGate.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            ruleExecutor.submit { }.get(5, TimeUnit.SECONDS)
            assertEquals(1, dispatchCount.get())
            assertEquals(1L, dbUtils.numReplies)
        } finally {
            processor.shutdown()
            ruleExecutor.shutdownNow()
            executor.shutdownNow()
        }
    }

    @Test
    fun `PendingClaimHandoff preserves over 512 active tokens and cleans up expired entries`() {
        PendingClaimHandoff.clear()
        val now = 10_000L
        for (i in 1..600) {
            PendingClaimHandoff.register("evt-handoff-$i", "tok-$i", nowMs = now, ttlMs = 60_000L)
        }
        // Active entries beyond 512 MUST NOT be evicted
        assertEquals("tok-1", PendingClaimHandoff.peek("evt-handoff-1", nowMs = now + 1_000L))
        assertEquals("tok-600", PendingClaimHandoff.consume("evt-handoff-600", nowMs = now + 1_000L))
        assertEquals("tok-599", PendingClaimHandoff.consume("evt-handoff-599", nowMs = now + 1_000L))
        // Expired entries are purged safely once TTL elapses
        val purged = PendingClaimHandoff.purgeExpired(nowMs = now + 60_001L)
        assertEquals(598, purged)
        assertNull(PendingClaimHandoff.peek("evt-handoff-1", nowMs = now + 60_001L))
        PendingClaimHandoff.clear()
    }

    @Test
    fun `missing DB claim for preClaimedToken re-acquires atomically and blocks replay`() {
        val sendCount = AtomicInteger(0)
        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )
        val sbn = buildSbn(id = 150, sender = "Hannah", message = "Missing row check")
        val syntheticToken = "preclaimed-missing-row-token"

        val first = service.sendActualReply(sbn, wearFromSbn(sbn), "Reply once", null, ReplySourceType.DEFAULT, null, syntheticToken)
        assertEquals(ReplyExecutionStatus.DISPATCHED, first.status)
        assertEquals(1, sendCount.get())

        val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn)!!
        assertEquals("DISPATCHED", logsDb.notificationDispatchClaimDao().getClaimByEventKeySync(eventKey)?.claimStatus)

        val second = service.sendActualReply(sbn, wearFromSbn(sbn), "Reply once", null, ReplySourceType.DEFAULT)
        assertEquals(ReplyExecutionStatus.CANCELLED, second.status)
        assertEquals(ReplyExecutionManager.FAILURE_DUPLICATE_EVENT, second.failureCategory)
        assertEquals(1, sendCount.get())
    }

    @Test
    fun `zero-second cooldown allows distinct messages while duplicate prevention still blocks identical redelivery`() {
        prefs.setServicePref(true)
        prefs.saveEnabledApps("com.whatsapp", true)
        prefs.setAutoReplyDelay(0L)

        val decider = NotificationReplyDecider(
            prefs,
            dbUtils,
            com.parishod.watomatic.model.utils.ContactsHelper.Companion.getInstance(context)
        )
        val sendCount = AtomicInteger(0)
        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )

        val now = System.currentTimeMillis()
        val sbn1 = buildSbn(id = 160, sender = "Ivan", message = "First question", whenMs = now)
        assertTrue(decider.canReply(sbn1))
        val res1 = service.sendActualReply(sbn1, wearFromSbn(sbn1), "Default reply", null, ReplySourceType.DEFAULT)
        assertEquals(ReplyExecutionStatus.DISPATCHED, res1.status)

        // Identical redelivery blocked by persistent event claim
        val dup = service.sendActualReply(sbn1, wearFromSbn(sbn1), "Default reply", null, ReplySourceType.DEFAULT)
        assertEquals(ReplyExecutionStatus.CANCELLED, dup.status)
        assertEquals(ReplyExecutionManager.FAILURE_DUPLICATE_EVENT, dup.failureCategory)

        // Distinct message from same sender 1ms later allowed with 0s cooldown
        val sbn2 = buildSbn(id = 161, sender = "Ivan", message = "Second question", whenMs = now + 1L)
        assertTrue(decider.canReply(sbn2))
        val res2 = service.sendActualReply(sbn2, wearFromSbn(sbn2), "Default reply", null, ReplySourceType.DEFAULT)
        assertEquals(ReplyExecutionStatus.DISPATCHED, res2.status)
        assertEquals(2, sendCount.get())
    }

    @Test
    fun `malformed custom OpenAI URL falls back safely without leaving claim stuck in CLAIMED`() {
        prefs.setEnableByokReplies(true)
        prefs.saveOpenAIApiKey("sk-test-key-123456")
        prefs.saveOpenApiSource(Constants.CUSTOM_AI_PROVIDER_NAME)
        prefs.saveCustomOpenAIApiUrl("not a valid url")
        prefs.setAiMemoryEnabled(false)
        prefs.setAiAutoNotesEnabled(false)

        val sendCount = AtomicInteger(0)
        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendCount.incrementAndGet()
            })
        )
        val sbn = buildSbn(id = 170, sender = "Julia", message = "Custom URL failure test")
        val method = NotificationService::class.java.getDeclaredMethod(
            "sendReply",
            StatusBarNotification::class.java
        )
        method.isAccessible = true
        method.invoke(service, sbn)
        service.awaitAiMemoryIdleForTesting()

        val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn)!!
        val claim = logsDb.notificationDispatchClaimDao().getClaimByEventKeySync(eventKey)!!
        assertEquals("DISPATCHED", claim.claimStatus)
        assertEquals(1, sendCount.get())
        assertEquals(1L, dbUtils.numReplies)
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
}
