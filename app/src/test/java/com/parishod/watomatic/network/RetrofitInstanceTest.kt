package com.parishod.watomatic.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory

/**
 * Unit tests for [RetrofitInstance].
 *
 * Verifies base URLs, converter factories, and singleton behavior
 * for all three Retrofit instances (GitHub, OpenAI, Atomatic AI).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RetrofitInstanceTest {

    // --- Base URL constants ---

    @Test
    fun `OPENAI_BASE_URL ends with slash`() {
        assertTrue(
            "OpenAI base URL must end with /",
            RetrofitInstance.OPENAI_BASE_URL.endsWith("/")
        )
    }

    @Test
    fun `OPENAI_BASE_URL is https`() {
        assertTrue(RetrofitInstance.OPENAI_BASE_URL.startsWith("https://"))
    }

    @Test
    fun `ATOMATIC_AI_BASE_URL ends with slash`() {
        assertTrue(
            "Atomatic AI base URL must end with /",
            RetrofitInstance.ATOMATIC_AI_BASE_URL.endsWith("/")
        )
    }

    @Test
    fun `ATOMATIC_AI_BASE_URL is https`() {
        assertTrue(RetrofitInstance.ATOMATIC_AI_BASE_URL.startsWith("https://"))
    }

    // --- GitHub Retrofit instance ---

    @Test
    fun `getRetrofitInstance returns non-null`() {
        val instance = RetrofitInstance.getRetrofitInstance()
        assertNotNull(instance)
    }

    @Test
    fun `getRetrofitInstance uses GitHub base URL`() {
        val instance = RetrofitInstance.getRetrofitInstance()
        assertEquals("https://api.github.com/", instance.baseUrl().toString())
    }

    @Test
    fun `getRetrofitInstance has ScalarsConverterFactory`() {
        val instance = RetrofitInstance.getRetrofitInstance()
        val factories = instance.converterFactories()
        assertTrue(
            "Should contain ScalarsConverterFactory",
            factories.any { it is ScalarsConverterFactory }
        )
    }

    @Test
    fun `getRetrofitInstance has GsonConverterFactory`() {
        val instance = RetrofitInstance.getRetrofitInstance()
        val factories = instance.converterFactories()
        assertTrue(
            "Should contain GsonConverterFactory",
            factories.any { it is GsonConverterFactory }
        )
    }

    @Test
    fun `getRetrofitInstance returns same singleton`() {
        val first = RetrofitInstance.getRetrofitInstance()
        val second = RetrofitInstance.getRetrofitInstance()
        assertSame(first, second)
    }

    // --- OpenAI Retrofit instance ---

    @Test
    fun `getOpenAIRetrofitInstance returns non-null`() {
        assertNotNull(RetrofitInstance.getOpenAIRetrofitInstance())
    }

    @Test
    fun `getOpenAIRetrofitInstance uses OpenAI base URL`() {
        val instance = RetrofitInstance.getOpenAIRetrofitInstance()
        assertEquals("https://api.openai.com/", instance.baseUrl().toString())
    }

    @Test
    fun `getOpenAIRetrofitInstance has GsonConverterFactory`() {
        val instance = RetrofitInstance.getOpenAIRetrofitInstance()
        assertTrue(
            "Should contain GsonConverterFactory",
            instance.converterFactories().any { it is GsonConverterFactory }
        )
    }

    @Test
    fun `getOpenAIRetrofitInstance does not have ScalarsConverterFactory`() {
        val instance = RetrofitInstance.getOpenAIRetrofitInstance()
        assertFalse(
            "OpenAI instance should not have ScalarsConverterFactory",
            instance.converterFactories().any { it is ScalarsConverterFactory }
        )
    }

    @Test
    fun `getOpenAIRetrofitInstance with custom base URL`() {
        val customUrl = "https://custom.openai.proxy.com/"
        val instance = RetrofitInstance.getOpenAIRetrofitInstance(customUrl)
        assertEquals(customUrl, instance.baseUrl().toString())
    }

    @Test
    fun `getOpenAIRetrofitInstance with custom URL returns different instance each call`() {
        val first = RetrofitInstance.getOpenAIRetrofitInstance("https://a.com/")
        val second = RetrofitInstance.getOpenAIRetrofitInstance("https://b.com/")
        assertNotSame(first, second)
    }

    // --- Atomatic AI Retrofit instance ---

    @Test
    fun `getAtomaticAIRetrofitInstance returns non-null`() {
        assertNotNull(RetrofitInstance.getAtomaticAIRetrofitInstance())
    }

    @Test
    fun `getAtomaticAIRetrofitInstance uses Atomatic base URL`() {
        val instance = RetrofitInstance.getAtomaticAIRetrofitInstance()
        assertEquals(RetrofitInstance.ATOMATIC_AI_BASE_URL, instance.baseUrl().toString())
    }

    @Test
    fun `getAtomaticAIRetrofitInstance has GsonConverterFactory`() {
        val instance = RetrofitInstance.getAtomaticAIRetrofitInstance()
        assertTrue(
            "Should contain GsonConverterFactory",
            instance.converterFactories().any { it is GsonConverterFactory }
        )
    }

    // --- Error parsing ---

    @Test
    fun `parseOpenAIError returns null for null error body`() {
        val response = retrofit2.Response.success<String>("ok")
        assertNull(RetrofitInstance.parseOpenAIError(response))
    }

    @Test
    fun `parseAtomaticAIError returns null for null error body`() {
        val response = retrofit2.Response.success<String>("ok")
        assertNull(RetrofitInstance.parseAtomaticAIError(response))
    }

    // --- Network logging security & timeout preservation ---

    private fun assertNoSensitiveHttpLogging(retrofit: Retrofit, clientName: String) {
        val callFactory = retrofit.callFactory()
        assertTrue("$clientName should use OkHttpClient", callFactory is OkHttpClient)
        val client = callFactory as OkHttpClient
        val allInterceptors = client.interceptors + client.networkInterceptors
        for (interceptor in allInterceptors) {
            if (interceptor is HttpLoggingInterceptor) {
                assertEquals(
                    "$clientName HttpLoggingInterceptor must be NONE if present",
                    HttpLoggingInterceptor.Level.NONE,
                    interceptor.level
                )
            }
        }
        assertFalse(
            "$clientName must not attach HttpLoggingInterceptor",
            allInterceptors.any { it is HttpLoggingInterceptor }
        )
    }

    @Test
    fun `github Retrofit client disables HTTP BODY logging`() {
        assertNoSensitiveHttpLogging(RetrofitInstance.getRetrofitInstance(), "GitHub")
    }

    @Test
    fun `openAI default Retrofit client disables HTTP BODY logging`() {
        assertNoSensitiveHttpLogging(RetrofitInstance.getOpenAIRetrofitInstance(), "OpenAI default")
    }

    @Test
    fun `openAI custom base URL Retrofit client disables HTTP BODY logging`() {
        val customClient = RetrofitInstance.getOpenAIRetrofitInstance("https://custom.llm.example.com/v1/")
        assertNoSensitiveHttpLogging(customClient, "OpenAI custom")
    }

    @Test
    fun `atomatic AI Retrofit client disables HTTP BODY logging`() {
        assertNoSensitiveHttpLogging(RetrofitInstance.getAtomaticAIRetrofitInstance(), "Atomatic AI")
    }

    @Test
    fun `openAI and atomatic AI clients preserve existing timeout configurations`() {
        val openAiClient = RetrofitInstance.getOpenAIRetrofitInstance().callFactory() as OkHttpClient
        assertEquals(30_000, openAiClient.connectTimeoutMillis)
        assertEquals(40_000, openAiClient.readTimeoutMillis)
        assertEquals(30_000, openAiClient.writeTimeoutMillis)
        assertEquals(45_000, openAiClient.callTimeoutMillis)

        val atomaticClient = RetrofitInstance.getAtomaticAIRetrofitInstance().callFactory() as OkHttpClient
        assertEquals(30_000, atomaticClient.connectTimeoutMillis)
        assertEquals(40_000, atomaticClient.readTimeoutMillis)
        assertEquals(30_000, atomaticClient.writeTimeoutMillis)
        assertEquals(45_000, atomaticClient.callTimeoutMillis)
    }

    @Test
    fun `AI Retrofit instances share thread-safe OkHttpClient with retryOnConnectionFailure disabled`() {
        val shared = RetrofitInstance.getSharedAiOkHttpClient()
        val defaultOpenAi = RetrofitInstance.getOpenAIRetrofitInstance().callFactory() as OkHttpClient
        val customOpenAi = RetrofitInstance.getOpenAIRetrofitInstance("https://cached.example.com/v1/").callFactory() as OkHttpClient
        val atomatic = RetrofitInstance.getAtomaticAIRetrofitInstance().callFactory() as OkHttpClient

        assertSame(shared, defaultOpenAi)
        assertSame(shared, customOpenAi)
        assertSame(shared, atomatic)
        assertFalse(
            "AI client must disable automatic retries for non-idempotent POST completions",
            shared.retryOnConnectionFailure
        )
    }

    @Test
    fun `getOpenAIRetrofitInstance reuses cached Retrofit instance for identical custom URL`() {
        val url = "https://repeat-cache-test.example.com/v1/"
        val first = RetrofitInstance.getOpenAIRetrofitInstance(url)
        val second = RetrofitInstance.getOpenAIRetrofitInstance(" $url ")
        assertSame(first, second)
    }

    @Test
    fun `shared AI OkHttpClient does not retry non-idempotent POST when socket closes prematurely`() {
        val serverHits = java.util.concurrent.atomic.AtomicInteger(0)
        val serverSocket = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        pool.execute {
            while (running.get()) {
                try {
                    val sock = serverSocket.accept()
                    serverHits.incrementAndGet()
                    sock.use {
                        // Drop socket without HTTP response to simulate transport failure after POST write
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }

        try {
            val baseUrl = "http://127.0.0.1:${serverSocket.localPort}/"
            val service = RetrofitInstance.getOpenAIRetrofitInstance(baseUrl).create(OpenAIService::class.java)
            val request = com.parishod.watomatic.network.model.openai.OpenAIRequest(
                "mock-model",
                listOf(com.parishod.watomatic.network.model.openai.Message("user", "Hello"))
            )
            var threwIo = false
            try {
                service.getChatCompletion("${baseUrl}v1/chat/completions", "Bearer test-key", request).execute()
            } catch (_: java.io.IOException) {
                threwIo = true
            }
            assertTrue("Premature socket close must fail rather than silently succeeding", threwIo)
            assertEquals("OkHttp must hit non-idempotent AI POST endpoint only once", 1, serverHits.get())
        } finally {
            running.set(false)
            runCatching { serverSocket.close() }
            pool.shutdownNow()
        }
    }
}
