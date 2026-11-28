package com.parishod.watomatic.testing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight, zero-dependency local HTTP mock server bound to `127.0.0.1` on an ephemeral port
 * for instrumentation tests exercising Retrofit/OkHttp AI providers (`OpenAIService`).
 *
 * Supports queued OpenAI-compatible 200 JSON responses, HTTP error codes (e.g., 429, 500),
 * artificial response delays, connection-failure simulation, request inspection (count,
 * timestamps, headers, body), and deterministic shutdown.
 */
class LocalAiMockServer : AutoCloseable {

    data class RecordedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
        val receivedAtMs: Long
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    sealed class MockResponseSpec(val delayMs: Long) {
        class HttpResponse(
            val statusCode: Int,
            val statusReason: String,
            val body: String,
            val contentType: String = "application/json; charset=utf-8",
            delayMs: Long = 0L
        ) : MockResponseSpec(delayMs)

        class ConnectionFailure(
            val resetSocket: Boolean = true,
            delayMs: Long = 0L
        ) : MockResponseSpec(delayMs)
    }

    private val lifecycleLock = Any()
    private val requestLock = Object()

    private val responseQueue = LinkedBlockingQueue<MockResponseSpec>()
    private val recordedRequests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val requestCountAtomic = AtomicInteger(0)
    private val activeClientSockets = ConcurrentHashMap.newKeySet<Socket>()

    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var workerPool: ExecutorService? = null

    fun start(): LocalAiMockServer {
        synchronized(lifecycleLock) {
            check(!stopped.get()) { "LocalAiMockServer cannot be restarted after stop/close" }
            if (started.get()) return this

            val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket

            val pool = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "LocalAiMockServer-worker").apply { isDaemon = true }
            }
            workerPool = pool

            val thread = Thread({ acceptLoop(socket, pool) }, "LocalAiMockServer-accept").apply {
                isDaemon = true
            }
            acceptThread = thread
            started.set(true)
            thread.start()
        }
        return this
    }

    fun isRunning(): Boolean {
        val sock = serverSocket ?: return false
        val thread = acceptThread ?: return false
        return started.get() && !stopped.get() && !sock.isClosed && thread.isAlive
    }

    fun port(): Int {
        val sock = serverSocket ?: throw IllegalStateException("LocalAiMockServer has not been started")
        return sock.localPort
    }

    fun baseUrl(): String = "http://127.0.0.1:${port()}/"

    fun enqueue(spec: MockResponseSpec): LocalAiMockServer {
        responseQueue.add(spec)
        return this
    }

    fun enqueueSuccess(
        replyText: String,
        model: String = "gpt-4o-mini",
        delayMs: Long = 0L
    ): LocalAiMockServer {
        val json = buildOpenAiCompletionJson(replyText = replyText, model = model)
        return enqueue(
            MockResponseSpec.HttpResponse(
                statusCode = 200,
                statusReason = "OK",
                body = json,
                delayMs = delayMs
            )
        )
    }

    fun enqueueHttpError(
        statusCode: Int,
        errorBody: String? = null,
        delayMs: Long = 0L
    ): LocalAiMockServer {
        val body = errorBody ?: buildOpenAiErrorJson(statusCode)
        return enqueue(
            MockResponseSpec.HttpResponse(
                statusCode = statusCode,
                statusReason = reasonPhraseFor(statusCode),
                body = body,
                delayMs = delayMs
            )
        )
    }

    fun enqueueRawJson(
        statusCode: Int,
        jsonBody: String,
        delayMs: Long = 0L
    ): LocalAiMockServer {
        return enqueue(
            MockResponseSpec.HttpResponse(
                statusCode = statusCode,
                statusReason = reasonPhraseFor(statusCode),
                body = jsonBody,
                delayMs = delayMs
            )
        )
    }

    fun enqueueConnectionFailure(
        delayMs: Long = 0L,
        resetSocket: Boolean = true
    ): LocalAiMockServer {
        return enqueue(
            MockResponseSpec.ConnectionFailure(
                resetSocket = resetSocket,
                delayMs = delayMs
            )
        )
    }

    fun requestCount(): Int = requestCountAtomic.get()

    fun getRecordedRequests(): List<RecordedRequest> {
        synchronized(requestLock) {
            return recordedRequests.toList()
        }
    }

    fun getLastRequest(): RecordedRequest? {
        synchronized(requestLock) {
            return recordedRequests.lastOrNull()
        }
    }

    fun awaitRequests(expectedCount: Int, timeoutMs: Long = 5_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(requestLock) {
            while (requestCountAtomic.get() < expectedCount) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) return false
                requestLock.wait(remaining)
            }
            return true
        }
    }

    fun clearRequests() {
        synchronized(requestLock) {
            recordedRequests.clear()
            requestCountAtomic.set(0)
        }
    }

    fun clearResponses() {
        responseQueue.clear()
    }

    fun stop() {
        close()
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) {
            return
        }
        val sock: ServerSocket?
        val thread: Thread?
        val pool: ExecutorService?
        synchronized(lifecycleLock) {
            sock = serverSocket
            thread = acceptThread
            pool = workerPool
        }
        runCatching { sock?.close() }
        for (client in activeClientSockets.toList()) {
            runCatching { client.close() }
        }
        activeClientSockets.clear()
        runCatching { thread?.interrupt() }
        runCatching { thread?.join(2_000L) }
        runCatching {
            pool?.shutdownNow()
            pool?.awaitTermination(2_000L, TimeUnit.MILLISECONDS)
        }
        synchronized(requestLock) {
            requestLock.notifyAll()
        }
    }

    private fun acceptLoop(sock: ServerSocket, pool: ExecutorService) {
        while (!stopped.get() && !sock.isClosed) {
            val client = try {
                sock.accept()
            } catch (_: IOException) {
                break
            }
            if (stopped.get()) {
                runCatching { client.close() }
                break
            }
            activeClientSockets.add(client)
            try {
                pool.execute { handleClient(client) }
            } catch (_: Exception) {
                activeClientSockets.remove(client)
                runCatching { client.close() }
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())
            val recorded = readHttpRequest(input) ?: return

            synchronized(requestLock) {
                recordedRequests.add(recorded)
                requestCountAtomic.incrementAndGet()
                requestLock.notifyAll()
            }

            val spec = responseQueue.poll() ?: MockResponseSpec.HttpResponse(
                statusCode = 503,
                statusReason = "Service Unavailable",
                body = """{"error":{"message":"No mock response enqueued","type":"mock_queue_empty","code":"503"}}"""
            )

            if (spec.delayMs > 0L) {
                try {
                    Thread.sleep(spec.delayMs)
                } catch (_: InterruptedException) {
                    return
                }
            }

            if (stopped.get() || client.isClosed) return

            when (spec) {
                is MockResponseSpec.ConnectionFailure -> {
                    if (spec.resetSocket) {
                        runCatching { client.setSoLinger(true, 0) }
                    }
                    runCatching { client.close() }
                }
                is MockResponseSpec.HttpResponse -> {
                    val bodyBytes = spec.body.toByteArray(StandardCharsets.UTF_8)
                    val headerText = buildString {
                        append("HTTP/1.1 ").append(spec.statusCode).append(' ').append(spec.statusReason).append("\r\n")
                        append("Content-Type: ").append(spec.contentType).append("\r\n")
                        append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                    }
                    val out = client.getOutputStream()
                    out.write(headerText.toByteArray(StandardCharsets.US_ASCII))
                    out.write(bodyBytes)
                    out.flush()
                }
            }
        } catch (_: IOException) {
            // Expected when clients disconnect or connection failure is simulated.
        } finally {
            activeClientSockets.remove(client)
            runCatching { client.close() }
        }
    }

    private fun readHttpRequest(input: BufferedInputStream): RecordedRequest? {
        val requestLine = readAsciiLine(input) ?: return null
        if (requestLine.isEmpty()) return null
        val receivedAtMs = System.currentTimeMillis()

        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0)?.trim().orEmpty()
        val rawPath = parts.getOrNull(1)?.trim().orEmpty()
        val path = rawPath.substringBefore('?')

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val name = line.substring(0, colonIdx).trim().lowercase()
                val value = line.substring(colonIdx + 1).trim()
                headers[name] = value
            }
        }

        val transferEncoding = headers["transfer-encoding"]?.lowercase().orEmpty()
        val bodyBytes = when {
            transferEncoding.contains("chunked") -> readChunkedBody(input)
            headers["content-length"] != null -> {
                val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                readFixedBytes(input, contentLength)
            }
            else -> ByteArray(0)
        }

        return RecordedRequest(
            method = method,
            path = path,
            headers = headers,
            body = String(bodyBytes, StandardCharsets.UTF_8),
            receivedAtMs = receivedAtMs
        )
    }

    private fun readAsciiLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var b = input.read()
        if (b == -1) return null
        while (b != -1) {
            if (b == '\n'.code) {
                break
            }
            if (b != '\r'.code) {
                buffer.write(b)
            }
            b = input.read()
        }
        return String(buffer.toByteArray(), StandardCharsets.US_ASCII)
    }

    private fun readFixedBytes(input: InputStream, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(out, offset, length - offset)
            if (read == -1) break
            offset += read
        }
        return if (offset == length) out else out.copyOf(offset)
    }

    private fun readChunkedBody(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readAsciiLine(input) ?: break
            val chunkSize = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (chunkSize == 0) {
                // Consume optional trailing headers until blank line.
                while (true) {
                    val trailer = readAsciiLine(input)
                    if (trailer.isNullOrEmpty()) break
                }
                break
            }
            val chunk = readFixedBytes(input, chunkSize)
            out.write(chunk)
            // Consume CRLF following chunk payload.
            readAsciiLine(input)
        }
        return out.toByteArray()
    }

    companion object {
        fun buildOpenAiCompletionJson(
            replyText: String,
            model: String = "gpt-4o-mini"
        ): String {
            val message = JsonObject().apply {
                addProperty("role", "assistant")
                addProperty("content", replyText)
            }
            val choice = JsonObject().apply {
                addProperty("index", 0)
                add("message", message)
                addProperty("finish_reason", "stop")
            }
            val choices = JsonArray().apply { add(choice) }
            val root = JsonObject().apply {
                addProperty("id", "chatcmpl-mock-${UUID.randomUUID().toString().take(8)}")
                addProperty("object", "chat.completion")
                addProperty("created", System.currentTimeMillis() / 1000L)
                addProperty("model", model)
                add("choices", choices)
            }
            return root.toString()
        }

        fun buildOpenAiErrorJson(
            statusCode: Int,
            message: String = "Mock HTTP $statusCode error"
        ): String {
            val errorObj = JsonObject().apply {
                addProperty("message", message)
                addProperty("type", "mock_http_error")
                addProperty("code", statusCode.toString())
            }
            return JsonObject().apply { add("error", errorObj) }.toString()
        }

        private fun reasonPhraseFor(statusCode: Int): String = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "Status $statusCode"
        }
    }
}
