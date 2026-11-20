package com.parishod.watomatic.service.reply

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import com.parishod.watomatic.NotificationWear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReplyExecutionManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ShadowLog.clear()
    }

    private fun realBroadcastPendingIntent(requestCode: Int = 100): PendingIntent {
        val intent = Intent("com.parishod.watomatic.TEST_REPLY_ACTION").setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun freeFormRemoteInput(resultKey: String = "key_text_reply"): RemoteInput =
        RemoteInput.Builder(resultKey)
            .setLabel("Reply")
            .setAllowFreeFormInput(true)
            .build()

    private fun nonFreeFormRemoteInput(resultKey: String = "key_choice_only"): RemoteInput =
        RemoteInput.Builder(resultKey)
            .setLabel("Choices")
            .setAllowFreeFormInput(false)
            .build()

    private fun sampleWear(
        pendingIntent: PendingIntent? = realBroadcastPendingIntent(),
        remoteInputs: List<RemoteInput> = listOf(freeFormRemoteInput()),
        id: String = "wear-1"
    ): NotificationWear = NotificationWear(
        packageName = "com.whatsapp",
        pendingIntent = pendingIntent,
        remoteInputs = remoteInputs,
        bundle = null,
        tag = "tag-1",
        id = id
    )

    @Test
    fun `valid RemoteInput and PendingIntent dispatches normally and populates RemoteInput bundle`() {
        val capturedIntents = mutableListOf<Intent>()
        val manager = ReplyExecutionManager(
            context = context,
            pendingIntentSender = ReplyExecutionManager.PendingIntentSender { ctx, pi, fillIn ->
                capturedIntents.add(fillIn)
                pi.send(ctx, 0, fillIn)
            }
        )
        val transitions = mutableListOf<ReplyExecutionStatus>()
        val pi = realBroadcastPendingIntent(101)
        val wear = sampleWear(pendingIntent = pi)
        val attempt = ReplyAttempt.create(
            sourceType = ReplySourceType.DEFAULT,
            sourceLabel = "Default",
            packageName = "com.whatsapp",
            notificationKey = "0|com.whatsapp|1|tag|0",
            replyText = "On my way!"
        )

        val result = manager.executeReply(attempt, wear) { updated ->
            transitions.add(updated.status)
        }

        assertEquals(ReplyExecutionStatus.DISPATCHED, result.status)
        assertTrue(result.dispatchAttempted)
        assertTrue(result.dispatchedNormally)
        assertTrue(result.isDispatched())
        assertFalse(result.isUnknown())
        assertFalse(result.isFailedOrCancelled())
        assertNull(result.failureCategory)
        assertEquals(attempt.attemptId, result.executionId)
        assertEquals(
            listOf(
                ReplyExecutionStatus.GENERATED,
                ReplyExecutionStatus.DISPATCHING,
                ReplyExecutionStatus.DISPATCHED
            ),
            transitions
        )
        assertEquals(1, capturedIntents.size)
        val sentIntent = capturedIntents[0]
        assertTrue((sentIntent.flags and Intent.FLAG_RECEIVER_FOREGROUND) != 0)
        val extractedBundle = RemoteInput.getResultsFromIntent(sentIntent)
        assertNotNull(extractedBundle)
        assertEquals("On my way!", extractedBundle?.getCharSequence("key_text_reply")?.toString())

        val shadowPi = Shadows.shadowOf(pi)
        assertEquals(1, shadowPi.savedIntents?.size ?: 1)
        assertEquals(0, manager.activeInFlightCount())
    }

    @Test
    fun `default PendingIntentSender executes real PendingIntent send normally`() {
        val manager = ReplyExecutionManager(context)
        val pi = realBroadcastPendingIntent(102)
        val wear = sampleWear(pendingIntent = pi)
        val attempt = ReplyAttempt.create(
            sourceType = ReplySourceType.RULE_STATIC,
            sourceLabel = "VIP",
            packageName = "com.whatsapp",
            notificationKey = "0|com.whatsapp|2|tag|0",
            replyText = "Hello!"
        )

        val result = manager.executeReply(attempt, wear)

        assertEquals(ReplyExecutionStatus.DISPATCHED, result.status)
        assertTrue(result.dispatchAttempted)
        assertTrue(result.dispatchedNormally)
        assertTrue(result.isDispatched())
    }

    @Test
    fun `missing NotificationWear returns FAILED without attempting dispatch`() {
        val sendCount = AtomicInteger(0)
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> sendCount.incrementAndGet() })
        val attempt = ReplyAttempt.create(replyText = "Hello", notificationKey = "k1")

        val result = manager.executeReply(attempt, null)

        assertEquals(ReplyExecutionStatus.FAILED, result.status)
        assertFalse(result.dispatchAttempted)
        assertFalse(result.dispatchedNormally)
        assertFalse(result.isDispatched())
        assertEquals(ReplyExecutionManager.FAILURE_MISSING_WEAR_DATA, result.failureCategory)
        assertEquals(0, sendCount.get())
    }

    @Test
    fun `missing PendingIntent returns FAILED without attempting dispatch`() {
        val sendCount = AtomicInteger(0)
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> sendCount.incrementAndGet() })
        val wear = sampleWear(pendingIntent = null)
        val attempt = ReplyAttempt.create(replyText = "Hello", notificationKey = "k2")

        val result = manager.executeReply(attempt, wear)

        assertEquals(ReplyExecutionStatus.FAILED, result.status)
        assertFalse(result.dispatchAttempted)
        assertFalse(result.dispatchedNormally)
        assertFalse(result.isDispatched())
        assertEquals(ReplyExecutionManager.FAILURE_MISSING_PENDING_INTENT, result.failureCategory)
        assertEquals(0, sendCount.get())
    }

    @Test
    fun `missing free-form RemoteInput returns FAILED without attempting dispatch`() {
        val sendCount = AtomicInteger(0)
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> sendCount.incrementAndGet() })

        val emptyWear = sampleWear(remoteInputs = emptyList())
        val emptyResult = manager.executeReply(
            ReplyAttempt.create(replyText = "Hello", notificationKey = "k3"),
            emptyWear
        )
        assertEquals(ReplyExecutionStatus.FAILED, emptyResult.status)
        assertFalse(emptyResult.dispatchAttempted)
        assertEquals(ReplyExecutionManager.FAILURE_MISSING_REMOTE_INPUT, emptyResult.failureCategory)

        val nonFreeFormWear = sampleWear(remoteInputs = listOf(nonFreeFormRemoteInput()))
        val nonFreeFormResult = manager.executeReply(
            ReplyAttempt.create(replyText = "Hello", notificationKey = "k4"),
            nonFreeFormWear
        )
        assertEquals(ReplyExecutionStatus.FAILED, nonFreeFormResult.status)
        assertFalse(nonFreeFormResult.dispatchAttempted)
        assertEquals(ReplyExecutionManager.FAILURE_MISSING_REMOTE_INPUT, nonFreeFormResult.failureCategory)
        assertEquals(0, sendCount.get())
    }

    @Test
    fun `null reply text returns FAILED without attempting dispatch`() {
        val sendCount = AtomicInteger(0)
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> sendCount.incrementAndGet() })
        val wear = sampleWear()

        val result = manager.executeReply(
            ReplyAttempt.create(replyText = null, notificationKey = "k5"),
            wear
        )

        assertEquals(ReplyExecutionStatus.FAILED, result.status)
        assertFalse(result.dispatchAttempted)
        assertFalse(result.dispatchedNormally)
        assertEquals(ReplyExecutionManager.FAILURE_INVALID_REPLY_TEXT, result.failureCategory)
        assertEquals(0, sendCount.get())
    }

    @Test
    fun `CanceledException from PendingIntent returns CANCELLED`() {
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
            throw PendingIntent.CanceledException("expired")
        })
        val wear = sampleWear()
        val attempt = ReplyAttempt.create(replyText = "Reply", notificationKey = "k6")

        val result = manager.executeReply(attempt, wear)

        assertEquals(ReplyExecutionStatus.CANCELLED, result.status)
        assertTrue(result.dispatchAttempted)
        assertFalse(result.dispatchedNormally)
        assertFalse(result.isDispatched())
        assertTrue(result.isFailedOrCancelled())
        assertEquals(ReplyExecutionManager.FAILURE_PENDING_INTENT_CANCELED, result.failureCategory)
        assertEquals(0, manager.activeInFlightCount())
    }

    @Test
    fun `deterministic pre-dispatch SecurityException from validator returns FAILED before calling PendingIntentSender`() {
        val sendAttempts = AtomicInteger(0)
        val manager = ReplyExecutionManager(
            context = context,
            pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendAttempts.incrementAndGet()
            },
            preDispatchValidator = ReplyExecutionManager.PreDispatchValidator { _, _, _ ->
                throw SecurityException("missing permission before IPC")
            }
        )
        val wear = sampleWear()
        val attempt = ReplyAttempt.create(replyText = "Reply", notificationKey = "k7-validator")

        val result = manager.executeReply(attempt, wear)

        assertEquals(ReplyExecutionStatus.FAILED, result.status)
        assertFalse(result.dispatchAttempted)
        assertFalse(result.dispatchedNormally)
        assertFalse(result.isDispatched())
        assertTrue(result.isFailedOrCancelled())
        assertEquals(ReplyExecutionManager.FAILURE_SECURITY_EXCEPTION, result.failureCategory)
        assertEquals(0, sendAttempts.get())
        assertEquals(0, manager.activeInFlightCount())
    }

    @Test
    fun `explicit PreDispatchSecurityException returns FAILED without marking dispatch attempted`() {
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
            throw ReplyExecutionManager.PreDispatchSecurityException("rejected locally before send")
        })
        val wear = sampleWear()
        val attempt = ReplyAttempt.create(replyText = "Reply", notificationKey = "k7-pre")

        val result = manager.executeReply(attempt, wear)

        assertEquals(ReplyExecutionStatus.FAILED, result.status)
        assertFalse(result.dispatchAttempted)
        assertFalse(result.dispatchedNormally)
        assertFalse(result.isDispatched())
        assertTrue(result.isFailedOrCancelled())
        assertEquals(ReplyExecutionManager.FAILURE_SECURITY_EXCEPTION, result.failureCategory)
        assertEquals(0, manager.activeInFlightCount())
    }

    @Test
    fun `ambiguous SecurityException from PendingIntentSender returns UNKNOWN and is not retried`() {
        val sendAttempts = AtomicInteger(0)
        val sensitiveReply = "SECRET_REPLY_SECURITY_CASE"
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
            sendAttempts.incrementAndGet()
            throw SecurityException("opaque binder SecurityException with $sensitiveReply")
        })
        val wear = sampleWear()
        val attempt = ReplyAttempt.create(replyText = sensitiveReply, notificationKey = "k7-binder")

        val result = manager.executeReply(attempt, wear)

        assertEquals(ReplyExecutionStatus.UNKNOWN, result.status)
        assertTrue(result.isUnknown())
        assertTrue(result.dispatchAttempted)
        assertFalse(result.dispatchedNormally)
        assertFalse(result.isDispatched())
        assertFalse(result.isFailedOrCancelled())
        assertEquals(ReplyExecutionManager.FAILURE_AMBIGUOUS_SECURITY_EXCEPTION, result.failureCategory)
        assertEquals("Ambiguous SecurityException must not be auto-retried", 1, sendAttempts.get())
        assertFalse(result.diagnosticMessage.orEmpty().contains(sensitiveReply))
        val logs = ShadowLog.getLogsForTag("ReplyExecutionManager").joinToString("\n") { it.msg.orEmpty() }
        assertFalse(logs.contains(sensitiveReply))
        assertEquals(0, manager.activeInFlightCount())
    }

    @Test
    fun `transition listener throwing before dispatch does not abort execution or trigger duplicate send`() {
        val sendAttempts = AtomicInteger(0)
        val sensitiveReply = "TOP_SECRET_BEFORE_DISPATCH"
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
            sendAttempts.incrementAndGet()
        })
        val seenStatuses = mutableListOf<ReplyExecutionStatus>()

        val result = manager.executeReply(
            ReplyAttempt.create(replyText = sensitiveReply, notificationKey = "k-listener-pre"),
            sampleWear()
        ) { attempt ->
            seenStatuses.add(attempt.status)
            if (attempt.status == ReplyExecutionStatus.GENERATED || attempt.status == ReplyExecutionStatus.DISPATCHING) {
                throw IllegalStateException("listener boom for $sensitiveReply")
            }
        }

        assertEquals(ReplyExecutionStatus.DISPATCHED, result.status)
        assertTrue(result.isDispatched())
        assertTrue(result.dispatchedNormally)
        assertEquals(1, sendAttempts.get())
        assertEquals(
            listOf(ReplyExecutionStatus.GENERATED, ReplyExecutionStatus.DISPATCHING, ReplyExecutionStatus.DISPATCHED),
            seenStatuses
        )
        val logs = ShadowLog.getLogsForTag("ReplyExecutionManager").joinToString("\n") { it.msg.orEmpty() }
        assertFalse(logs.contains(sensitiveReply))
        assertEquals(0, manager.activeInFlightCount())
    }

    @Test
    fun `transition listener throwing after DISPATCHED never reclassifies result or triggers second dispatch`() {
        val sendAttempts = AtomicInteger(0)
        val sensitiveReply = "TOP_SECRET_AFTER_DISPATCH"
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
            sendAttempts.incrementAndGet()
        })

        val result = manager.executeReply(
            ReplyAttempt.create(replyText = sensitiveReply, notificationKey = "k-listener-post"),
            sampleWear()
        ) { attempt ->
            if (attempt.status == ReplyExecutionStatus.DISPATCHED) {
                throw RuntimeException("UI callback crash with $sensitiveReply")
            }
        }

        assertEquals(ReplyExecutionStatus.DISPATCHED, result.status)
        assertTrue(result.isDispatched())
        assertTrue(result.dispatchAttempted)
        assertTrue(result.dispatchedNormally)
        assertNull(result.failureCategory)
        assertEquals(1, sendAttempts.get())
        val logs = ShadowLog.getLogsForTag("ReplyExecutionManager").joinToString("\n") { it.msg.orEmpty() }
        assertFalse(logs.contains(sensitiveReply))
        assertEquals(0, manager.activeInFlightCount())
    }

    @Test
    fun `transition listener throwing on failure states preserves FAILED, CANCELLED, and UNKNOWN results`() {
        val throwingListener = ReplyExecutionManager.StatusTransitionListener { attempt ->
            if (attempt.status != ReplyExecutionStatus.GENERATED && attempt.status != ReplyExecutionStatus.DISPATCHING) {
                throw IllegalArgumentException("broken observer on ${attempt.status} with ${attempt.replyText}")
            }
        }
        val sensitiveReply = "SENSITIVE_FAILURE_TEXT"

        // FAILED (missing PendingIntent)
        val managerFailed = ReplyExecutionManager(context)
        val failedResult = managerFailed.executeReply(
            ReplyAttempt.create(replyText = sensitiveReply, notificationKey = "k-fail-listener"),
            sampleWear(pendingIntent = null),
            throwingListener
        )
        assertEquals(ReplyExecutionStatus.FAILED, failedResult.status)
        assertEquals(ReplyExecutionManager.FAILURE_MISSING_PENDING_INTENT, failedResult.failureCategory)

        // CANCELLED (PendingIntent.CanceledException)
        val managerCancelled = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
            throw PendingIntent.CanceledException("expired")
        })
        val cancelledResult = managerCancelled.executeReply(
            ReplyAttempt.create(replyText = sensitiveReply, notificationKey = "k-cancel-listener"),
            sampleWear(),
            throwingListener
        )
        assertEquals(ReplyExecutionStatus.CANCELLED, cancelledResult.status)
        assertEquals(ReplyExecutionManager.FAILURE_PENDING_INTENT_CANCELED, cancelledResult.failureCategory)

        // UNKNOWN (RuntimeException)
        val managerUnknown = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
            throw IllegalStateException("ipc fail")
        })
        val unknownResult = managerUnknown.executeReply(
            ReplyAttempt.create(replyText = sensitiveReply, notificationKey = "k-unknown-listener"),
            sampleWear(),
            throwingListener
        )
        assertEquals(ReplyExecutionStatus.UNKNOWN, unknownResult.status)
        assertEquals(ReplyExecutionManager.FAILURE_AMBIGUOUS_SEND_EXCEPTION, unknownResult.failureCategory)

        val logs = ShadowLog.getLogsForTag("ReplyExecutionManager").joinToString("\n") { it.msg.orEmpty() }
        assertFalse(logs.contains(sensitiveReply))
    }

    @Test
    fun `unexpected RuntimeException returns UNKNOWN, is not treated as success, is not retried, and leaks no reply text`() {
        val sendAttempts = AtomicInteger(0)
        val sensitiveReply = "CONFIDENTIAL_SECRET_REPLY_12345"
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
            sendAttempts.incrementAndGet()
            throw IllegalStateException("unexpected IPC binder anomaly with $sensitiveReply")
        })
        val wear = sampleWear()
        val attempt = ReplyAttempt.create(replyText = sensitiveReply, notificationKey = "k8")

        val result = manager.executeReply(attempt, wear)

        assertEquals(ReplyExecutionStatus.UNKNOWN, result.status)
        assertTrue(result.isUnknown())
        assertTrue(result.dispatchAttempted)
        assertFalse(result.dispatchedNormally)
        assertFalse(result.isDispatched())
        assertFalse(result.isFailedOrCancelled())
        assertEquals(ReplyExecutionManager.FAILURE_AMBIGUOUS_SEND_EXCEPTION, result.failureCategory)
        assertEquals("Ambiguous outcome must not be auto-retried", 1, sendAttempts.get())

        val diagnostic = result.diagnosticMessage.orEmpty()
        assertFalse(diagnostic.contains(sensitiveReply))
        val logs = ShadowLog.getLogsForTag("ReplyExecutionManager").joinToString("\n") { it.msg.orEmpty() }
        assertFalse(logs.contains(sensitiveReply))
        assertEquals(0, manager.activeInFlightCount())
    }

    @Test
    fun `shutdown manager cancels execution before PendingIntent send`() {
        val sendAttempts = AtomicInteger(0)
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
            sendAttempts.incrementAndGet()
        })
        manager.shutdown()
        assertTrue(manager.isShutDown())

        val result = manager.executeReply(
            ReplyAttempt.create(replyText = "Hi", notificationKey = "k9"),
            sampleWear()
        )

        assertEquals(ReplyExecutionStatus.CANCELLED, result.status)
        assertFalse(result.dispatchAttempted)
        assertFalse(result.dispatchedNormally)
        assertEquals(ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN, result.failureCategory)
        assertEquals(0, sendAttempts.get())
    }

    @Test
    fun `concurrent execution for same notificationKey prevents double send and releases lock`() {
        val enteredFirstSend = CountDownLatch(1)
        val releaseFirstSend = CountDownLatch(1)
        val sendAttempts = AtomicInteger(0)

        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
            sendAttempts.incrementAndGet()
            enteredFirstSend.countDown()
            assertTrue(releaseFirstSend.await(5, TimeUnit.SECONDS))
        })
        val wear = sampleWear()
        val sharedKey = "0|com.whatsapp|same-thread|tag|0"

        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstFuture = executor.submit<ReplyExecutionResult> {
                manager.executeReply(
                    ReplyAttempt.create(replyText = "First", notificationKey = sharedKey),
                    wear
                )
            }
            assertTrue(enteredFirstSend.await(5, TimeUnit.SECONDS))

            val secondFuture = executor.submit<ReplyExecutionResult> {
                manager.executeReply(
                    ReplyAttempt.create(replyText = "Second", notificationKey = sharedKey),
                    wear
                )
            }
            val secondResult = secondFuture.get(5, TimeUnit.SECONDS)
            assertEquals(ReplyExecutionStatus.CANCELLED, secondResult.status)
            assertFalse(secondResult.dispatchAttempted)
            assertEquals(ReplyExecutionManager.FAILURE_CONCURRENT_IN_FLIGHT, secondResult.failureCategory)

            releaseFirstSend.countDown()
            val firstResult = firstFuture.get(5, TimeUnit.SECONDS)
            assertEquals(ReplyExecutionStatus.DISPATCHED, firstResult.status)
            assertEquals(1, sendAttempts.get())
            assertEquals(0, manager.activeInFlightCount())

            // Lock has been released, so a subsequent attempt on the same key works normally
            val thirdResult = manager.executeReply(
                ReplyAttempt.create(replyText = "Third", notificationKey = sharedKey),
                wear
            )
            assertEquals(ReplyExecutionStatus.DISPATCHED, thirdResult.status)
            assertEquals(2, sendAttempts.get())
        } finally {
            releaseFirstSend.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent execution across distinct notification keys is thread-safe`() {
        val count = 12
        val sendAttempts = AtomicInteger(0)
        val manager = ReplyExecutionManager(context, pendingIntentSender = ReplyExecutionManager.PendingIntentSender { ctx, pi, fillIn ->
            sendAttempts.incrementAndGet()
            pi.send(ctx, 0, fillIn)
        })
        val executor = Executors.newFixedThreadPool(4)
        val startGate = CountDownLatch(1)
        try {
            val futures = (1..count).map { index ->
                val pi = realBroadcastPendingIntent(200 + index)
                val wear = sampleWear(pendingIntent = pi, id = "wear-$index")
                val attempt = ReplyAttempt.create(
                    replyText = "Reply $index",
                    notificationKey = "0|com.whatsapp|$index|tag|0"
                )
                executor.submit<ReplyExecutionResult> {
                    assertTrue(startGate.await(5, TimeUnit.SECONDS))
                    manager.executeReply(attempt, wear)
                }
            }
            startGate.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(count, results.size)
            assertTrue(results.all { it.isDispatched() })
            assertEquals(count, sendAttempts.get())
            assertEquals(0, manager.activeInFlightCount())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `same DISPATCHED attempt cannot be executed twice`() {
        val sendAttempts = AtomicInteger(0)
        val manager = ReplyExecutionManager(
            context = context,
            pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendAttempts.incrementAndGet()
            }
        )
        val transitions = mutableListOf<ReplyExecutionStatus>()
        val dispatchedAttempt = ReplyAttempt.create(
            replyText = "Already sent",
            notificationKey = "0|com.whatsapp|term-dispatched|tag|0"
        ).transitionTo(ReplyExecutionStatus.DISPATCHED, 1_000L)

        assertTrue(dispatchedAttempt.isTerminal())
        assertFalse(dispatchedAttempt.isExecutable())

        val result = manager.executeReply(dispatchedAttempt, sampleWear()) { updated ->
            transitions.add(updated.status)
        }

        assertEquals(ReplyExecutionStatus.FAILED, result.status)
        assertFalse(result.dispatchAttempted)
        assertFalse(result.dispatchedNormally)
        assertFalse(result.isDispatched())
        assertTrue(result.isFailedOrCancelled())
        assertEquals(ReplyExecutionManager.FAILURE_INVALID_ATTEMPT_STATE, result.failureCategory)
        assertEquals(dispatchedAttempt.attemptId, result.executionId)
        assertEquals(0, sendAttempts.get())
        assertTrue("Terminal attempt must not silently reset to GENERATED", transitions.isEmpty())
        assertEquals(ReplyExecutionStatus.DISPATCHED, dispatchedAttempt.status)
        assertEquals(0, manager.activeInFlightCount())
    }

    @Test
    fun `same FAILED attempt cannot be automatically replayed`() {
        val sendAttempts = AtomicInteger(0)
        val manager = ReplyExecutionManager(
            context = context,
            pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendAttempts.incrementAndGet()
            }
        )
        val transitions = mutableListOf<ReplyExecutionStatus>()
        val failedAttempt = ReplyAttempt.create(
            replyText = "Retry me?",
            notificationKey = "0|com.whatsapp|term-failed|tag|0"
        ).transitionTo(ReplyExecutionStatus.FAILED, 1_000L)

        val result = manager.executeReply(failedAttempt, sampleWear()) { updated ->
            transitions.add(updated.status)
        }

        assertEquals(ReplyExecutionStatus.FAILED, result.status)
        assertFalse(result.dispatchAttempted)
        assertFalse(result.dispatchedNormally)
        assertFalse(result.isDispatched())
        assertEquals(ReplyExecutionManager.FAILURE_INVALID_ATTEMPT_STATE, result.failureCategory)
        assertEquals(0, sendAttempts.get())
        assertTrue(transitions.isEmpty())
        assertEquals(ReplyExecutionStatus.FAILED, failedAttempt.status)
        assertEquals(0, manager.activeInFlightCount())
    }

    @Test
    fun `same CANCELLED attempt cannot be executed again`() {
        val sendAttempts = AtomicInteger(0)
        val manager = ReplyExecutionManager(
            context = context,
            pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendAttempts.incrementAndGet()
            }
        )
        val transitions = mutableListOf<ReplyExecutionStatus>()
        val cancelledAttempt = ReplyAttempt.create(
            replyText = "Cancelled message",
            notificationKey = "0|com.whatsapp|term-cancelled|tag|0"
        ).transitionTo(ReplyExecutionStatus.CANCELLED, 1_000L)

        val result = manager.executeReply(cancelledAttempt, sampleWear()) { updated ->
            transitions.add(updated.status)
        }

        assertEquals(ReplyExecutionStatus.FAILED, result.status)
        assertFalse(result.dispatchAttempted)
        assertFalse(result.dispatchedNormally)
        assertFalse(result.isDispatched())
        assertEquals(ReplyExecutionManager.FAILURE_INVALID_ATTEMPT_STATE, result.failureCategory)
        assertEquals(0, sendAttempts.get())
        assertTrue(transitions.isEmpty())
        assertEquals(ReplyExecutionStatus.CANCELLED, cancelledAttempt.status)
        assertEquals(0, manager.activeInFlightCount())
    }

    @Test
    fun `same UNKNOWN attempt cannot be automatically retried`() {
        val sendAttempts = AtomicInteger(0)
        val manager = ReplyExecutionManager(
            context = context,
            pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendAttempts.incrementAndGet()
            }
        )
        val transitions = mutableListOf<ReplyExecutionStatus>()
        val unknownAttempt = ReplyAttempt.create(
            replyText = "Uncertain outcome",
            notificationKey = "0|com.whatsapp|term-unknown|tag|0"
        ).transitionTo(ReplyExecutionStatus.UNKNOWN, 1_000L)

        val result = manager.executeReply(unknownAttempt, sampleWear()) { updated ->
            transitions.add(updated.status)
        }

        assertEquals(ReplyExecutionStatus.FAILED, result.status)
        assertFalse(result.dispatchAttempted)
        assertFalse(result.dispatchedNormally)
        assertFalse(result.isDispatched())
        assertEquals(ReplyExecutionManager.FAILURE_UNKNOWN_OUTCOME_LOCKED, result.failureCategory)
        assertEquals(0, sendAttempts.get())
        assertTrue(transitions.isEmpty())
        assertEquals(ReplyExecutionStatus.UNKNOWN, unknownAttempt.status)
        assertEquals(0, manager.activeInFlightCount())
    }

    @Test
    fun `DISPATCHING attempt is not treated as a fresh attempt`() {
        val sendAttempts = AtomicInteger(0)
        val manager = ReplyExecutionManager(
            context = context,
            pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendAttempts.incrementAndGet()
            }
        )
        val transitions = mutableListOf<ReplyExecutionStatus>()
        val inProgressAttempt = ReplyAttempt.create(
            replyText = "Already in progress",
            notificationKey = "0|com.whatsapp|non-fresh|tag|0"
        ).transitionTo(ReplyExecutionStatus.DISPATCHING, 1_000L)

        val result = manager.executeReply(inProgressAttempt, sampleWear()) { updated ->
            transitions.add(updated.status)
        }

        assertEquals(ReplyExecutionStatus.FAILED, result.status)
        assertFalse(result.dispatchAttempted)
        assertFalse(result.dispatchedNormally)
        assertEquals(ReplyExecutionManager.FAILURE_CONCURRENT_IN_FLIGHT, result.failureCategory)
        assertEquals(0, sendAttempts.get())
        assertTrue(transitions.isEmpty())
        assertEquals(0, manager.activeInFlightCount())
    }

    @Test
    fun `shutdown during preDispatchValidator prevents dispatch before PendingIntent send`() {
        val enteredValidator = CountDownLatch(1)
        val releaseValidator = CountDownLatch(1)
        val sendAttempts = AtomicInteger(0)
        val manager = ReplyExecutionManager(
            context = context,
            pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendAttempts.incrementAndGet()
            },
            preDispatchValidator = ReplyExecutionManager.PreDispatchValidator { _, _, _ ->
                enteredValidator.countDown()
                assertTrue(releaseValidator.await(5, TimeUnit.SECONDS))
            }
        )

        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<ReplyExecutionResult> {
                manager.executeReply(
                    ReplyAttempt.create(replyText = "Hello", notificationKey = "race-validator"),
                    sampleWear()
                )
            }
            assertTrue(enteredValidator.await(5, TimeUnit.SECONDS))

            manager.shutdown()
            assertTrue(manager.isShutDown())
            releaseValidator.countDown()

            val result = future.get(5, TimeUnit.SECONDS)
            assertEquals(ReplyExecutionStatus.CANCELLED, result.status)
            assertFalse(result.dispatchAttempted)
            assertFalse(result.dispatchedNormally)
            assertEquals(ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN, result.failureCategory)
            assertEquals(0, sendAttempts.get())
            assertEquals(0, manager.activeInFlightCount())
        } finally {
            releaseValidator.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `shutdown during DISPATCHING transition prevents PendingIntent send without deadlocking`() {
        val enteredDispatchingListener = CountDownLatch(1)
        val releaseDispatchingListener = CountDownLatch(1)
        val sendAttempts = AtomicInteger(0)
        val manager = ReplyExecutionManager(
            context = context,
            pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendAttempts.incrementAndGet()
            }
        )

        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<ReplyExecutionResult> {
                manager.executeReply(
                    ReplyAttempt.create(replyText = "Hello", notificationKey = "race-listener"),
                    sampleWear()
                ) { updated ->
                    if (updated.status == ReplyExecutionStatus.DISPATCHING) {
                        enteredDispatchingListener.countDown()
                        assertTrue(releaseDispatchingListener.await(5, TimeUnit.SECONDS))
                    }
                }
            }
            assertTrue(enteredDispatchingListener.await(5, TimeUnit.SECONDS))

            manager.shutdown()
            releaseDispatchingListener.countDown()

            val result = future.get(5, TimeUnit.SECONDS)
            assertEquals(ReplyExecutionStatus.CANCELLED, result.status)
            assertFalse(result.dispatchAttempted)
            assertFalse(result.dispatchedNormally)
            assertEquals(ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN, result.failureCategory)
            assertEquals(0, sendAttempts.get())
            assertEquals(0, manager.activeInFlightCount())
        } finally {
            releaseDispatchingListener.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `shutdown does not block on in-flight PendingIntent send and blocks subsequent dispatches`() {
        val enteredFirstSend = CountDownLatch(1)
        val releaseFirstSend = CountDownLatch(1)
        val sendAttempts = AtomicInteger(0)
        val manager = ReplyExecutionManager(
            context = context,
            pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                val callNum = sendAttempts.incrementAndGet()
                if (callNum == 1) {
                    enteredFirstSend.countDown()
                    assertTrue(releaseFirstSend.await(5, TimeUnit.SECONDS))
                }
            }
        )

        val executor = Executors.newSingleThreadExecutor()
        try {
            val inFlightFuture = executor.submit<ReplyExecutionResult> {
                manager.executeReply(
                    ReplyAttempt.create(replyText = "First", notificationKey = "k-inflight"),
                    sampleWear()
                )
            }
            assertTrue(enteredFirstSend.await(5, TimeUnit.SECONDS))

            // shutdown() completes immediately even while first PendingIntent.send() is still running
            manager.shutdown()
            assertTrue(manager.isShutDown())

            // Any new dispatch after shutdown is cancelled immediately
            val blockedAfterShutdown = manager.executeReply(
                ReplyAttempt.create(replyText = "Second", notificationKey = "k-after-shutdown"),
                sampleWear()
            )
            assertEquals(ReplyExecutionStatus.CANCELLED, blockedAfterShutdown.status)
            assertFalse(blockedAfterShutdown.dispatchAttempted)
            assertEquals(ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN, blockedAfterShutdown.failureCategory)

            releaseFirstSend.countDown()
            val firstResult = inFlightFuture.get(5, TimeUnit.SECONDS)
            assertEquals(ReplyExecutionStatus.DISPATCHED, firstResult.status)
            assertEquals(1, sendAttempts.get())
            assertEquals(0, manager.activeInFlightCount())
        } finally {
            releaseFirstSend.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `distinct notification keys execute concurrently without blocking each other`() {
        val enteredKeyA = CountDownLatch(1)
        val releaseKeyA = CountDownLatch(1)
        val sendAttempts = AtomicInteger(0)
        val manager = ReplyExecutionManager(
            context = context,
            pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, fillIn ->
                sendAttempts.incrementAndGet()
                val text = RemoteInput.getResultsFromIntent(fillIn)?.getCharSequence("key_text_reply")?.toString()
                if (text == "KeyA") {
                    enteredKeyA.countDown()
                    assertTrue(releaseKeyA.await(5, TimeUnit.SECONDS))
                }
            }
        )

        val executor = Executors.newFixedThreadPool(2)
        try {
            val futureA = executor.submit<ReplyExecutionResult> {
                manager.executeReply(
                    ReplyAttempt.create(replyText = "KeyA", notificationKey = "0|com.whatsapp|A|tag|0"),
                    sampleWear(pendingIntent = realBroadcastPendingIntent(301), id = "wear-a")
                )
            }
            assertTrue(enteredKeyA.await(5, TimeUnit.SECONDS))

            // While KeyA is blocked inside PendingIntentSender.send(), KeyB finishes independently
            val futureB = executor.submit<ReplyExecutionResult> {
                manager.executeReply(
                    ReplyAttempt.create(replyText = "KeyB", notificationKey = "0|com.whatsapp|B|tag|0"),
                    sampleWear(pendingIntent = realBroadcastPendingIntent(302), id = "wear-b")
                )
            }
            val resultB = futureB.get(5, TimeUnit.SECONDS)
            assertEquals(ReplyExecutionStatus.DISPATCHED, resultB.status)
            assertTrue(resultB.isDispatched())

            releaseKeyA.countDown()
            val resultA = futureA.get(5, TimeUnit.SECONDS)
            assertEquals(ReplyExecutionStatus.DISPATCHED, resultA.status)
            assertEquals(2, sendAttempts.get())
            assertEquals(0, manager.activeInFlightCount())
        } finally {
            releaseKeyA.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `unexpected exception in preDispatchValidator releases in-flight lock`() {
        val shouldThrow = AtomicInteger(1)
        val sendAttempts = AtomicInteger(0)
        val manager = ReplyExecutionManager(
            context = context,
            pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                sendAttempts.incrementAndGet()
            },
            preDispatchValidator = ReplyExecutionManager.PreDispatchValidator { _, _, _ ->
                if (shouldThrow.getAndSet(0) == 1) {
                    throw IllegalStateException("unexpected validator crash")
                }
            }
        )
        val sharedKey = "0|com.whatsapp|exception-unlock|tag|0"

        var threw = false
        try {
            manager.executeReply(
                ReplyAttempt.create(replyText = "First", notificationKey = sharedKey),
                sampleWear()
            )
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals(0, manager.activeInFlightCount())

        val subsequentResult = manager.executeReply(
            ReplyAttempt.create(replyText = "Second", notificationKey = sharedKey),
            sampleWear()
        )
        assertEquals(ReplyExecutionStatus.DISPATCHED, subsequentResult.status)
        assertEquals(1, sendAttempts.get())
        assertEquals(0, manager.activeInFlightCount())
    }
}

