package com.parishod.watomatic.testing

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.parishod.watomatic.model.ai.AiMemoryDatabase
import com.parishod.watomatic.model.ai.AiMemoryRepository
import com.parishod.watomatic.model.ai.PendingPostDispatchMemoryStore
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.rules.AutoReplyRule
import com.parishod.watomatic.model.rules.AutoReplyRulesDatabase
import com.parishod.watomatic.model.utils.Constants
import com.parishod.watomatic.model.utils.DbUtils
import com.parishod.watomatic.model.utils.NotificationUtils
import com.parishod.watomatic.model.utils.UrlValidator
import com.parishod.watomatic.network.OpenAIService
import com.parishod.watomatic.network.RetrofitInstance
import com.parishod.watomatic.network.model.openai.Message
import com.parishod.watomatic.network.model.openai.OpenAIRequest
import com.parishod.watomatic.service.reply.ReplyAttempt
import com.parishod.watomatic.service.reply.ReplyExecutionManager
import com.parishod.watomatic.service.reply.ReplyExecutionResult
import com.parishod.watomatic.service.reply.ReplyExecutionStatus
import com.parishod.watomatic.service.reply.ReplySourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Infrastructure verification suite for F-025-12 Batch 1.
 * Verifies storage isolation, synthetic notification & RemoteInput dispatch capture,
 * and local loopback mock AI HTTP server behavior before runtime verification tests are built.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class RuntimeInfrastructureVerificationTest {

    @get:Rule
    val testRule = WatomaticTestRule(prefixTag = "infra_verify")

    @Test
    fun isolatedSharedPreferences_doNotAffectProductionPreferences() {
        val targetContext = testRule.targetContext
        val isolatedContext = testRule.isolatedContext

        val defaultPrefsName = IsolatedTestContext.defaultSharedPreferencesName(targetContext)
        assertNotEquals(
            defaultPrefsName,
            isolatedContext.resolvePrefixedSharedPrefsName(defaultPrefsName)
        )

        val prodDefaultPrefs = PreferenceManager.getDefaultSharedPreferences(targetContext)
        val prodSnapshotBefore = HashMap(prodDefaultPrefs.all)

        val customPrefName = "watomatic_infra_probe"
        val sentinelKey = "sentinel_${UUID.randomUUID()}"
        val sentinelUrl = "http://127.0.0.1:41234/"

        val prefsManager = PreferencesManager.getPreferencesInstance(isolatedContext)
        prefsManager.setServicePref(true)
        prefsManager.saveCustomOpenAIApiUrl(sentinelUrl)
        prefsManager.setAutoReplyDelay(98_765L)

        isolatedContext.getSharedPreferences(customPrefName, Context.MODE_PRIVATE)
            .edit()
            .putString(sentinelKey, "isolated-only")
            .commit()

        assertTrue(prefsManager.isServiceEnabled)
        assertEquals(sentinelUrl, prefsManager.customOpenAIApiUrl)
        assertEquals(98_765L, prefsManager.autoReplyDelay)
        assertEquals(
            "isolated-only",
            isolatedContext.getSharedPreferences(customPrefName, Context.MODE_PRIVATE)
                .getString(sentinelKey, null)
        )

        // Production default preferences must remain untouched.
        assertEquals(prodSnapshotBefore, prodDefaultPrefs.all)
        assertFalse(
            targetContext.getSharedPreferences(customPrefName, Context.MODE_PRIVATE)
                .contains(sentinelKey)
        )

        // Clean up isolated storage and verify production preferences remain intact.
        isolatedContext.cleanupOwnedStorage()
        assertEquals(prodSnapshotBefore, prodDefaultPrefs.all)
    }

    @Test
    fun isolatedRoomDatabases_doNotAffectProductionDatabases() {
        val targetContext = testRule.targetContext
        val isolatedContext = testRule.isolatedContext

        val prodLogsDbFile = targetContext.getDatabasePath(Constants.LOGS_DB_NAME)
        val isolatedLogsDbFile = isolatedContext.getDatabasePath(Constants.LOGS_DB_NAME)
        assertNotEquals(prodLogsDbFile.absolutePath, isolatedLogsDbFile.absolutePath)

        val prodAiDbFile = targetContext.getDatabasePath(AiMemoryDatabase.DB_NAME)
        val isolatedAiDbFile = isolatedContext.getDatabasePath(AiMemoryDatabase.DB_NAME)
        assertNotEquals(prodAiDbFile.absolutePath, isolatedAiDbFile.absolutePath)

        val prodRulesDbFile = targetContext.getDatabasePath(AutoReplyRulesDatabase.DB_NAME)
        val isolatedRulesDbFile = isolatedContext.getDatabasePath(AutoReplyRulesDatabase.DB_NAME)
        assertNotEquals(prodRulesDbFile.absolutePath, isolatedRulesDbFile.absolutePath)

        val prodLogsExistedBefore = prodLogsDbFile.exists()
        val prodAiExistedBefore = prodAiDbFile.exists()
        val prodRulesExistedBefore = prodRulesDbFile.exists()

        val attemptId = "infra-attempt-${UUID.randomUUID()}"
        val eventKey = "infra-event-${UUID.randomUUID()}"
        val nowMs = System.currentTimeMillis()

        val dbExecutor = Executors.newSingleThreadExecutor()
        try {
            dbExecutor.submit(Callable {
                val dbUtils = DbUtils(isolatedContext)
                val attempt = ReplyAttempt(
                    attemptId = attemptId,
                    sourceType = ReplySourceType.GLOBAL_AI,
                    sourceLabel = "AI",
                    packageName = SyntheticNotificationFixture.DEFAULT_SYNTHETIC_SOURCE_PACKAGE,
                    notificationKey = "notif-$attemptId",
                    replyText = "Isolated DB verification reply",
                    status = ReplyExecutionStatus.DISPATCHED,
                    createdAtMs = nowMs,
                    updatedAtMs = nowMs,
                    eventKey = eventKey
                )
                val result = ReplyExecutionResult(
                    executionId = attemptId,
                    status = ReplyExecutionStatus.DISPATCHED,
                    dispatchAttempted = true,
                    dispatchedNormally = true,
                    failureCategory = null,
                    diagnosticMessage = "Dispatched",
                    timestampMs = nowMs
                )
                dbUtils.recordAttemptResult(attempt, result, "Isolated Sender", "Hello DB", null)
                assertNotNull(dbUtils.getAttemptById(attemptId))
                assertTrue(dbUtils.hasConsistentDispatchedPersistence(attemptId))

                val aiDao = AiMemoryDatabase.getInstance(isolatedContext).aiMemoryDao()
                val aiRepo = AiMemoryRepository(aiDao)
                aiRepo.recordIncomingUserMessage(
                    packageName = SyntheticNotificationFixture.DEFAULT_SYNTHETIC_SOURCE_PACKAGE,
                    senderKey = "isolated sender",
                    senderDisplay = "Isolated Sender",
                    incomingMessage = "Hello AI DB",
                    incomingEventKey = eventKey,
                    keepCount = 10,
                    timestampMs = nowMs
                )
                assertEquals(1, aiDao.countMessagesByIncomingEventKeySync(eventKey))

                val rulesDb = AutoReplyRulesDatabase.getInstance(isolatedContext)
                val insertedRuleId = runBlocking {
                    rulesDb.ruleDao().insertRule(
                        AutoReplyRule(
                            name = "Isolated Rule",
                            replyMessage = "Test reply",
                            keywords = listOf("hello")
                        )
                    )
                }
                assertTrue(insertedRuleId > 0L)
                assertEquals(1, rulesDb.ruleDao().getTotalRuleCountSync())
            }).get(10, TimeUnit.SECONDS)
        } finally {
            dbExecutor.shutdownNow()
        }

        // If production DB files did not exist beforehand, isolated writes must never create them.
        if (!prodLogsExistedBefore) {
            assertFalse(prodLogsDbFile.exists())
        } else {
            // Read-only verification against pre-existing production DB without mutating it.
            IsolatedTestContext.resetAllAppSingletons(isolatedContext)
            val prodAttempt = MessageLogsDB.getInstance(targetContext)
                .replyAttemptDao()
                .getAttemptByIdSync(attemptId)
            assertEquals(null, prodAttempt)
            MessageLogsDB.resetInstance()
        }
        if (!prodAiExistedBefore) {
            assertFalse(prodAiDbFile.exists())
        }
        if (!prodRulesExistedBefore) {
            assertFalse(prodRulesDbFile.exists())
        }
        assertTrue(PendingPostDispatchMemoryStore.getPendingEntries(isolatedContext).isEmpty())
    }

    @Test
    fun syntheticNotification_canBePostedAndCaptured() {
        val fixture = testRule.createNotificationFixture()
        val posted = fixture.postNotification(
            title = "Alice Synthetic",
            text = "Are you available?",
            actionTitle = "Reply"
        )

        val activeSbn = fixture.awaitActiveNotification(posted.id, timeoutMs = 5_000L)
        assertNotNull("Posted synthetic notification must appear in activeNotifications", activeSbn)
        assertEquals("Alice Synthetic", NotificationUtils.getTitle(activeSbn))

        val wear = NotificationUtils.extractWearNotification(activeSbn!!)
        assertNotNull(wear.pendingIntent)
        assertEquals(1, wear.remoteInputs.size)
        assertTrue(wear.remoteInputs.first().allowFreeFormInput)
        assertEquals(SyntheticNotificationFixture.DEFAULT_REMOTE_INPUT_KEY, wear.remoteInputs.first().resultKey)
    }

    @Test
    fun remoteInputReplyText_isReceivedByTestBroadcastReceiver() {
        val fixture = testRule.createNotificationFixture()
        val syntheticSbn = fixture.buildSyntheticStatusBarNotification(
            sourcePackageName = SyntheticNotificationFixture.DEFAULT_SYNTHETIC_SOURCE_PACKAGE,
            title = "Bob Synthetic",
            text = "Please confirm receipt",
            actionTitle = "Reply"
        )
        val wear = NotificationUtils.extractWearNotification(syntheticSbn)
        val expectedReplyText = "Confirmed automated reply ${UUID.randomUUID().toString().take(6)}"

        val executionManager = ReplyExecutionManager(testRule.isolatedContext)
        val nowMs = System.currentTimeMillis()
        val attempt = ReplyAttempt(
            attemptId = "att-remote-input-${UUID.randomUUID()}",
            sourceType = ReplySourceType.DEFAULT,
            sourceLabel = "Test",
            packageName = syntheticSbn.packageName,
            notificationKey = syntheticSbn.key,
            replyText = expectedReplyText,
            status = ReplyExecutionStatus.GENERATED,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
            eventKey = "evt-remote-input-${UUID.randomUUID()}"
        )

        val result = executionManager.executeReply(attempt, wear)
        assertEquals(ReplyExecutionStatus.DISPATCHED, result.status)
        assertTrue(result.dispatchedNormally)

        assertTrue(
            "Test BroadcastReceiver did not receive PendingIntent broadcast in time",
            fixture.awaitInvocationCount(1, timeoutMs = 5_000L)
        )
        assertEquals(1, fixture.getInvocationCount())

        val captured = fixture.getLastCapturedReply()
        assertNotNull(captured)
        assertEquals(expectedReplyText, captured!!.replyText)
        assertTrue(captured.hasRemoteInputBundle)
        assertTrue(captured.receivedAtMs >= nowMs)
        assertEquals(fixture.replyAction, captured.action)
        assertTrue((captured.intentFlags and Intent.FLAG_RECEIVER_FOREGROUND) != 0)
    }

    @Test
    fun localMockServer_returnsSuccessfulResponse() {
        val server = testRule.createMockAiServer()
        assertTrue(UrlValidator.isValidUrl(server.baseUrl()))
        server.enqueueSuccess(replyText = "Mock AI response OK", model = "gpt-test")

        val service = RetrofitInstance.getOpenAIRetrofitInstance(server.baseUrl())
            .create(OpenAIService::class.java)
        val request = OpenAIRequest("gpt-test", listOf(Message("user", "Say hello")))

        val response = service.getChatCompletion("Bearer mock-secret", request).execute()
        assertTrue("Expected HTTP 200 success, got ${response.code()}", response.isSuccessful)
        assertEquals(200, response.code())

        val replyContent = response.body()?.choices?.firstOrNull()?.message?.content
        assertEquals("Mock AI response OK", replyContent)

        assertTrue(server.awaitRequests(1, timeoutMs = 2_000L))
        assertEquals(1, server.requestCount())
        val recorded = server.getLastRequest()
        assertNotNull(recorded)
        assertEquals("POST", recorded!!.method)
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer mock-secret", recorded.header("authorization"))
        assertTrue(recorded.body.contains("Say hello"))
        assertTrue(recorded.receivedAtMs > 0L)
    }

    @Test
    fun localMockServer_returns429And500_andSimulatesConnectionFailure() {
        val server = testRule.createMockAiServer()
        server.enqueueHttpError(429)
        server.enqueueHttpError(500)
        server.enqueueConnectionFailure()

        val service = RetrofitInstance.getOpenAIRetrofitInstance(server.baseUrl())
            .create(OpenAIService::class.java)
        val request = OpenAIRequest("gpt-test", listOf(Message("user", "Test errors")))

        val resp429 = service.getChatCompletion("Bearer k", request).execute()
        assertEquals(429, resp429.code())
        assertFalse(resp429.isSuccessful)
        assertEquals("429", RetrofitInstance.parseOpenAIError(resp429)?.error?.code)

        val resp500 = service.getChatCompletion("Bearer k", request).execute()
        assertEquals(500, resp500.code())
        assertFalse(resp500.isSuccessful)
        assertEquals("500", RetrofitInstance.parseOpenAIError(resp500)?.error?.code)

        try {
            service.getChatCompletion("Bearer k", request).execute()
            fail("Expected IOException from simulated connection failure")
        } catch (_: IOException) {
            // Expected failure path
        }

        assertEquals(3, server.requestCount())
    }

    @Test
    fun localMockServer_delayedResponseBehaviorWorks() {
        val server = testRule.createMockAiServer()
        server.enqueueSuccess(replyText = "Delayed mock reply", delayMs = 350L)

        val service = RetrofitInstance.getOpenAIRetrofitInstance(server.baseUrl())
            .create(OpenAIService::class.java)
        val request = OpenAIRequest("gpt-test", listOf(Message("user", "Slow check")))

        val startMs = SystemClock.elapsedRealtime()
        val response = service.getChatCompletion("Bearer k", request).execute()
        val elapsedMs = SystemClock.elapsedRealtime() - startMs

        assertTrue(response.isSuccessful)
        assertEquals("Delayed mock reply", response.body()?.choices?.firstOrNull()?.message?.content)
        assertTrue("Expected delayed response >= 300ms, was ${elapsedMs}ms", elapsedMs >= 300L)
        assertEquals(1, server.requestCount())
    }

    @Test
    fun localMockServer_shutsDownCleanly() {
        val server = LocalAiMockServer().start()
        assertTrue(server.isRunning())
        val port = server.port()
        assertTrue(port > 0)

        server.close()
        assertFalse(server.isRunning())

        // Repeated close/stop calls must be safe and idempotent.
        server.stop()
        assertFalse(server.isRunning())

        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
            }
            fail("Expected connection to closed LocalAiMockServer port $port to fail")
        } catch (_: IOException) {
            // Expected once ServerSocket is closed
        }
    }
}
