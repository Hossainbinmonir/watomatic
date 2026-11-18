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
    fun `service onDestroy shuts down active ReplyExecutionManager`() {
        val manager = service.replyExecutionManager
        assertFalse(manager.isShutDown())

        service.onDestroy()
        assertTrue(manager.isShutDown())
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
