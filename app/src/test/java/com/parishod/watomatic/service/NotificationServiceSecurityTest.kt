package com.parishod.watomatic.service

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.parishod.watomatic.NotificationWear
import com.parishod.watomatic.model.ai.AiConversationMessage
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.network.OpenAIService
import com.parishod.watomatic.network.model.openai.OpenAIModelsResponse
import com.parishod.watomatic.network.model.openai.OpenAIRequest
import com.parishod.watomatic.network.model.openai.OpenAIResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationServiceSecurityTest {

    private lateinit var context: Context
    private lateinit var service: NotificationService
    private lateinit var prefs: PreferencesManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferencesManager.resetInstance()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        val encryptedStore = context.getSharedPreferences("test_notif_service_enc", Context.MODE_PRIVATE)
        encryptedStore.edit().clear().commit()
        PreferencesManager.setEncryptedPrefsProviderForTesting { encryptedStore }
        prefs = PreferencesManager.getPreferencesInstance(context)
        service = Robolectric.buildService(NotificationService::class.java).create().get()
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        ShadowLog.clear()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("test_notif_service_enc", Context.MODE_PRIVATE).edit().clear().commit()
        PreferencesManager.resetInstance()
    }

    @Test
    fun `formatSafeHttpError omits raw provider error bodies and returns safe categories`() {
        val sensitiveErrorBody = """
            {
              "error": {
                "message": "Invalid key sk-secret999 at https://custom.ai.internal/v1?key=AIzaLeak999 for user prompt private message",
                "type": "invalid_request_error"
              }
            }
        """.trimIndent().toResponseBody("application/json".toMediaType())

        val unauthorized = Response.error<JsonObject>(401, sensitiveErrorBody)
        val formatted401 = NotificationService.formatSafeHttpError(unauthorized)
        assertEquals("HTTP 401 (Authentication failed)", formatted401)
        assertFalse(formatted401.contains("sk-secret999"))
        assertFalse(formatted401.contains("AIzaLeak999"))
        assertFalse(formatted401.contains("custom.ai.internal"))
        assertFalse(formatted401.contains("private message"))

        assertEquals("HTTP 400 (Bad request)", NotificationService.formatSafeHttpError(400))
        assertEquals("HTTP 403 (Access denied)", NotificationService.formatSafeHttpError(403))
        assertEquals("HTTP 404 (Endpoint or model not found)", NotificationService.formatSafeHttpError(404))
        assertEquals("HTTP 429 (Rate limit or quota exceeded)", NotificationService.formatSafeHttpError(429))
        assertEquals("HTTP 503 (Provider server error)", NotificationService.formatSafeHttpError(503))
    }

    @Test
    fun `sanitizeErrorMessageForPersistence allows only predefined safe error categories`() {
        assertEquals("HTTP 401 (Authentication failed)", NotificationService.sanitizeErrorMessageForPersistence("HTTP 401 (Authentication failed)"))
        assertEquals("HTTP 429 (Rate limit or quota exceeded)", NotificationService.sanitizeErrorMessageForPersistence("HTTP 429 (Rate limit or quota exceeded)"))
        assertEquals("HTTP 502 (Provider server error)", NotificationService.sanitizeErrorMessageForPersistence("HTTP 502 (Provider server error)"))
        assertEquals("Network error (SocketTimeoutException)", NotificationService.sanitizeErrorMessageForPersistence("Network error (SocketTimeoutException)"))
        assertEquals("Empty Claude response", NotificationService.sanitizeErrorMessageForPersistence("Empty Claude response"))
        assertEquals("API key unavailable", NotificationService.sanitizeErrorMessageForPersistence("API key unavailable"))
        assertEquals(
            "Secure encrypted credential storage is unavailable (GeneralSecurityException).",
            NotificationService.sanitizeErrorMessageForPersistence("Secure encrypted credential storage is unavailable (GeneralSecurityException).")
        )
        assertEquals("AI request failed", NotificationService.sanitizeErrorMessageForPersistence(null))
        assertEquals("AI request failed", NotificationService.sanitizeErrorMessageForPersistence("   "))
    }

    @Test
    fun `recordAiError never persists sensitive or arbitrary provider error text`() {
        val recordAiError = NotificationService::class.java.getDeclaredMethod(
            "recordAiError",
            PreferencesManager::class.java,
            String::class.java
        ).apply { isAccessible = true }

        val unexpectedSensitiveInputs = listOf(
            // Bearer tokens
            "Auth failed with Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.secret",
            "Bearer sk-live-bearer-token-999 rejected",
            // OpenAI-style API keys
            "Incorrect API key provided: sk-proj-abc123XYZsecretOpenAiKey",
            "Key sk-ant-api03-secret-key invalid",
            // Google API keys
            "API key not valid. Please pass a valid API key: AIzaSyDummyGoogleSecretKey12345",
            // URLs with query parameters
            "POST https://custom.ai.internal/v1/chat?api_key=supersecret&user=alice failed",
            "Failed connecting to proxy.local:8443/completions?token=leak987",
            // Arbitrary provider error text, exception messages, conversation messages, personal notes
            "{\"error\":{\"message\":\"User Alice said: meet me at 5pm\",\"code\":\"invalid_request\"}}",
            "IllegalStateException: Personal note - renew passport before Friday",
            "Conversation snippet: Hey, my bank PIN is 4829",
            "Arbitrary provider error text from upstream gateway"
        )

        for (sensitiveInput in unexpectedSensitiveInputs) {
            recordAiError.invoke(service, prefs, sensitiveInput)
            val persisted = prefs.openAILastPersistentErrorMessage
            assertEquals(
                "Unexpected input should collapse to safe generic category: $sensitiveInput",
                "Provider request failed",
                persisted
            )
            assertFalse(persisted!!.contains("Bearer"))
            assertFalse(persisted.contains("sk-"))
            assertFalse(persisted.contains("AIza"))
            assertFalse(persisted.contains("custom.ai.internal"))
            assertFalse(persisted.contains("proxy.local"))
            assertFalse(persisted.contains("api_key="))
            assertFalse(persisted.contains("Alice"))
            assertFalse(persisted.contains("4829"))
            assertFalse(persisted.contains("passport"))
            assertFalse(persisted.contains("upstream gateway"))
        }
    }

    @Test
    fun `claude and gemini parsing exceptions do not log Throwable objects or payloads`() {
        val malformedBody = JsonObject().apply {
            // "content" and "candidates" as primitive instead of array triggers IllegalStateException on getAsJsonArray
            addProperty("content", "sensitive-claude-payload-should-not-log")
            addProperty("candidates", "sensitive-gemini-payload-should-not-log")
        }

        val fakeApiService = immediateCallbackService(
            claudeResponse = Response.success(malformedBody),
            geminiResponse = Response.success(malformedBody),
            openAiResponse = Response.success(OpenAIResponse())
        )

        val wear = NotificationWear("com.whatsapp", null, emptyList(), null, null, "1")
        val sbn = dummySbn()

        val fetchClaude = NotificationService::class.java.getDeclaredMethod(
            "fetchClaudeReply",
            PreferencesManager::class.java, OpenAIService::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            String::class.java, List::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, String::class.java, StatusBarNotification::class.java,
            NotificationWear::class.java, String::class.java
        ).apply { isAccessible = true }

        fetchClaude.invoke(
            service, prefs, fakeApiService, "https://api.anthropic.com/", "sk-ant-secret",
            "claude-sonnet-4-20250514", "system prompt", "com.whatsapp", "alice", "Alice",
            "hello", emptyList<AiConversationMessage>(), false, false, 10, "AI", sbn, wear, "fallback"
        )

        val fetchGemini = NotificationService::class.java.getDeclaredMethod(
            "fetchGeminiReply",
            PreferencesManager::class.java, OpenAIService::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            String::class.java, List::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, String::class.java, StatusBarNotification::class.java,
            NotificationWear::class.java, String::class.java
        ).apply { isAccessible = true }

        fetchGemini.invoke(
            service, prefs, fakeApiService, "https://generativelanguage.googleapis.com/", "AIzaSecret",
            "gemini-2.5-flash", "system prompt", "com.whatsapp", "alice", "Alice",
            "hello", emptyList<AiConversationMessage>(), false, false, 10, "AI", sbn, wear, "fallback"
        )

        val logs = ShadowLog.getLogsForTag("NotificationService")
        assertTrue(
            "Expected sanitized Claude parsing log without Throwable, got: ${logs.map { it.msg }}",
            logs.any { (it.msg ?: "").contains("Error parsing Claude response (ClassCastException)") }
        )
        assertTrue(
            "Expected sanitized Gemini parsing log without Throwable, got: ${logs.map { it.msg }}",
            logs.any { (it.msg ?: "").contains("Error parsing Gemini response (ClassCastException)") }
        )
        assertTrue("Parsing logs must not attach Throwable objects", logs.all { it.throwable == null })
        val allLogText = logs.joinToString("\n") { it.msg ?: "" }
        assertFalse(allLogText.contains("sensitive-claude-payload-should-not-log"))
        assertFalse(allLogText.contains("sensitive-gemini-payload-should-not-log"))
        assertFalse(allLogText.contains("sk-ant-secret"))
        assertFalse(allLogText.contains("AIzaSecret"))
    }

    @Test
    fun `provider HTTP error bodies are neither logged nor persisted by NotificationService`() {
        val sensitiveJson = """{"error":{"message":"Rejected key sk-live-secret-987 at https://proxy.example.net/v1?key=AIza987"}}"""
        val claudeErr = Response.error<JsonObject>(429, sensitiveJson.toResponseBody("application/json".toMediaType()))
        val geminiErr = Response.error<JsonObject>(401, sensitiveJson.toResponseBody("application/json".toMediaType()))
        val openAiErr = Response.error<OpenAIResponse>(500, sensitiveJson.toResponseBody("application/json".toMediaType()))

        val fakeApiService = immediateCallbackService(claudeErr, geminiErr, openAiErr)
        val wear = NotificationWear("com.whatsapp", null, emptyList(), null, null, "1")
        val sbn = dummySbn()

        val fetchOpenAi = NotificationService::class.java.getDeclaredMethod(
            "fetchOpenAiCompatibleReply",
            PreferencesManager::class.java, OpenAIService::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            String::class.java, List::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, String::class.java, StatusBarNotification::class.java,
            NotificationWear::class.java, String::class.java
        ).apply { isAccessible = true }

        fetchOpenAi.invoke(
            service, prefs, fakeApiService, "https://api.openai.com/", "sk-live-secret-987",
            "gpt-4o-mini", "system prompt", "com.whatsapp", "alice", "Alice",
            "hello", emptyList<AiConversationMessage>(), false, false, 10, "AI", sbn, wear, "fallback"
        )

        val persistedError = prefs.openAILastPersistentErrorMessage
        assertNotNull(persistedError)
        assertEquals("HTTP 500 (Provider server error)", persistedError)
        assertFalse(persistedError!!.contains("sk-live-secret-987"))
        assertFalse(persistedError.contains("AIza987"))
        assertFalse(persistedError.contains("proxy.example.net"))

        val allLogText = ShadowLog.getLogsForTag("NotificationService").joinToString("\n") { it.msg ?: "" }
        assertFalse(allLogText.contains("sk-live-secret-987"))
        assertFalse(allLogText.contains("AIza987"))
        assertFalse(allLogText.contains("proxy.example.net"))
        assertFalse(allLogText.contains("Rejected key"))
    }

    private fun dummySbn(): StatusBarNotification {
        val notification = Notification()
        return StatusBarNotification(
            "com.whatsapp", "com.whatsapp", 1, "tag", 0, 0, 0, notification, android.os.Process.myUserHandle(), 0L
        )
    }

    private fun immediateCallbackService(
        claudeResponse: Response<JsonObject>,
        geminiResponse: Response<JsonObject>,
        openAiResponse: Response<OpenAIResponse>
    ): OpenAIService = object : OpenAIService {
        override fun getChatCompletion(authorization: String?, requestBody: OpenAIRequest?): Call<OpenAIResponse> =
            immediateCall(openAiResponse)

        override fun getChatCompletion(url: String?, authorization: String?, requestBody: OpenAIRequest?): Call<OpenAIResponse> =
            immediateCall(openAiResponse)

        override fun getModels(authorization: String?): Call<OpenAIModelsResponse> =
            immediateCall(Response.success(OpenAIModelsResponse()))

        override fun getModels(headers: MutableMap<String, String>?): Call<OpenAIModelsResponse> =
            immediateCall(Response.success(OpenAIModelsResponse()))

        override fun getModelsDynamic(url: String?, headers: MutableMap<String, String>?): Call<JsonElement> =
            immediateCall(Response.success(JsonObject()))

        override fun getClaudeCompletion(url: String?, headers: MutableMap<String, String>?, requestBody: JsonObject?): Call<JsonObject> =
            immediateCall(claudeResponse)

        override fun getGeminiCompletion(url: String?, apiKey: String?, requestBody: JsonObject?): Call<JsonObject> =
            immediateCall(geminiResponse)
    }

    private fun <T> immediateCall(response: Response<T>): Call<T> = object : Call<T> {
        override fun enqueue(callback: Callback<T>) {
            callback.onResponse(this, response)
        }
        override fun isExecuted(): Boolean = true
        override fun clone(): Call<T> = this
        override fun isCanceled(): Boolean = false
        override fun cancel() {}
        override fun execute(): Response<T> = response
        override fun request(): Request = Request.Builder().url("https://localhost/").build()
        override fun timeout(): Timeout = Timeout.NONE
    }
}
