package com.parishod.watomatic.service.reply

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import com.parishod.watomatic.model.logs.NotificationDispatchClaimEntity
import com.parishod.watomatic.model.logs.ReplyAttemptEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationEventKeyAndRetryPolicyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun makeSbn(
        pkg: String = "com.whatsapp",
        id: Int = 10,
        tag: String? = "chat-tag",
        sender: String? = "Alice",
        message: String? = "Hello world",
        whenMs: Long = 1700000000000L,
        postTimeMs: Long = 1700000000100L,
        isGroup: Boolean = false
    ): StatusBarNotification {
        val pi = PendingIntent.getBroadcast(
            context,
            id,
            Intent("com.parishod.watomatic.EVENT_KEY_TEST").setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val ri = RemoteInput.Builder("reply_key").setLabel("Reply").build()
        val action = NotificationCompat.Action.Builder(0, "Reply", pi).addRemoteInput(ri).build()
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
            tag,
            0,
            0,
            0,
            notification,
            Process.myUserHandle(),
            postTimeMs
        )
    }

    @Test
    fun `event key is deterministic SHA-256 and never exposes raw sender or message text`() {
        val sbn1 = makeSbn(sender = "SecretSender99", message = "Confidential PIN 9911")
        val sbn2 = makeSbn(sender = "SecretSender99", message = "Confidential PIN 9911")

        val key1 = NotificationEventKeyBuilder.buildEventKey(sbn1)
        val key2 = NotificationEventKeyBuilder.buildEventKey(sbn2)

        assertNotNull(key1)
        assertEquals(key1, key2)
        assertTrue(key1!!.startsWith("evt_"))
        assertEquals(68, key1.length) // "evt_" + 64 hex chars
        assertFalse(key1.contains("SecretSender99"))
        assertFalse(key1.contains("Confidential"))
    }

    @Test
    fun `distinct messages or senders or timestamps produce distinct event keys`() {
        val base = NotificationEventKeyBuilder.buildEventKey(makeSbn())
        val diffMessage = NotificationEventKeyBuilder.buildEventKey(makeSbn(message = "Second message"))
        val diffSender = NotificationEventKeyBuilder.buildEventKey(makeSbn(sender = "Bob"))
        val diffTimestamp = NotificationEventKeyBuilder.buildEventKey(makeSbn(whenMs = 1700000009999L))
        val diffPkg = NotificationEventKeyBuilder.buildEventKey(makeSbn(pkg = "org.telegram.messenger"))
        val diffGroup = NotificationEventKeyBuilder.buildEventKey(makeSbn(isGroup = true))
        val diffId = NotificationEventKeyBuilder.buildEventKey(makeSbn(id = 99))

        val keys = setOf(base, diffMessage, diffSender, diffTimestamp, diffPkg, diffGroup, diffId)
        assertEquals(7, keys.size)
        assertNotEquals(base, diffMessage)
    }

    @Test
    fun `missing sender or missing message or null tag still produces stable distinct keys`() {
        val noSender = makeSbn(sender = null, message = "Ping", tag = null, id = 1)
        val noMsg = makeSbn(sender = "Alice", message = null, tag = null, id = 2)
        val neither = makeSbn(sender = null, message = null, tag = null, id = 3)

        val kNoSenderA = NotificationEventKeyBuilder.buildEventKey(noSender)
        val kNoSenderB = NotificationEventKeyBuilder.buildEventKey(noSender)
        val kNoMsg = NotificationEventKeyBuilder.buildEventKey(noMsg)
        val kNeither = NotificationEventKeyBuilder.buildEventKey(neither)

        assertNotNull(kNoSenderA)
        assertEquals(kNoSenderA, kNoSenderB)
        assertEquals(3, setOf(kNoSenderA, kNoMsg, kNeither).size)
    }

    @Test
    fun `retry policy enforces explicit rule per ReplyExecutionStatus and preconditions`() {
        fun claim(status: String, dispatchAttempted: Boolean = false) = NotificationDispatchClaimEntity(
            eventKey = "evt_test",
            attemptId = "att-1",
            packageName = "com.whatsapp",
            senderKey = "alice",
            notificationKey = "k-1",
            claimStatus = status,
            failureCategory = null,
            dispatchAttempted = dispatchAttempted,
            dispatchedNormally = status == ReplyExecutionStatus.DISPATCHED.name,
            createdAtMs = 100L,
            updatedAtMs = 100L,
            expiresAtMs = 999999L
        )

        assertEquals(RetryDecision.ALLOW_NEW_EVENT, ReplyRetryPolicy.evaluateClaimConflict(null))
        assertEquals(
            RetryDecision.DENY_ALREADY_DISPATCHED,
            ReplyRetryPolicy.evaluateClaimConflict(claim(ReplyExecutionStatus.DISPATCHED.name, dispatchAttempted = true))
        )
        assertEquals(
            RetryDecision.DENY_UNKNOWN_OUTCOME,
            ReplyRetryPolicy.evaluateClaimConflict(claim(ReplyExecutionStatus.UNKNOWN.name, dispatchAttempted = true), allowRetryOnPreDispatchFailure = true)
        )
        assertEquals(
            RetryDecision.DENY_CANCELLED_OR_STALE,
            ReplyRetryPolicy.evaluateClaimConflict(claim(ReplyExecutionStatus.CANCELLED.name))
        )
        assertEquals(
            RetryDecision.DENY_IN_FLIGHT,
            ReplyRetryPolicy.evaluateClaimConflict(claim(ReplyExecutionStatus.GENERATED.name))
        )
        assertEquals(
            RetryDecision.DENY_IN_FLIGHT,
            ReplyRetryPolicy.evaluateClaimConflict(claim(ReplyExecutionStatus.DISPATCHING.name, dispatchAttempted = true))
        )
        assertEquals(
            RetryDecision.DENY_IN_FLIGHT,
            ReplyRetryPolicy.evaluateClaimConflict(claim(NotificationDispatchClaimEntity.STATUS_CLAIMED))
        )
        assertEquals(
            RetryDecision.DENY_DUPLICATE_PRE_DISPATCH_FAILURE,
            ReplyRetryPolicy.evaluateClaimConflict(claim(ReplyExecutionStatus.FAILED.name, dispatchAttempted = false), allowRetryOnPreDispatchFailure = false)
        )
        assertEquals(
            RetryDecision.ALLOW_AUTHORIZED_RETRY,
            ReplyRetryPolicy.evaluateClaimConflict(claim(ReplyExecutionStatus.FAILED.name, dispatchAttempted = false), allowRetryOnPreDispatchFailure = true)
        )
        assertEquals(
            RetryDecision.DENY_DUPLICATE_PRE_DISPATCH_FAILURE,
            ReplyRetryPolicy.evaluateClaimConflict(claim(ReplyExecutionStatus.FAILED.name, dispatchAttempted = true), allowRetryOnPreDispatchFailure = true)
        )
        assertEquals(
            RetryDecision.DENY_SERVICE_SHUTDOWN,
            ReplyRetryPolicy.evaluateClaimConflict(null, isServiceShutdown = true)
        )
        assertEquals(
            RetryDecision.DENY_DUPLICATE_CALLBACK,
            ReplyRetryPolicy.evaluateClaimConflict(null, isDuplicateCallback = true)
        )
        assertEquals(
            RetryDecision.DENY_CANCELLED_OR_STALE,
            ReplyRetryPolicy.evaluateClaimConflict(null, isStaleNotification = true)
        )
        assertEquals(
            RetryDecision.DENY_MISSING_CAPABILITY,
            ReplyRetryPolicy.evaluateClaimConflict(null, hasValidReplyCapability = false)
        )

        val attemptUnknown = ReplyAttemptEntity(
            attemptId = "att-u",
            eventKey = "evt_test",
            packageName = "com.whatsapp",
            senderName = "Alice",
            incomingMessage = "Hi",
            notificationKey = "k",
            replyText = "Yo",
            sourceType = "DEFAULT",
            sourceLabel = "Default",
            status = ReplyExecutionStatus.UNKNOWN.name,
            dispatchAttempted = true,
            dispatchedNormally = false,
            failureCategory = ReplyExecutionManager.FAILURE_AMBIGUOUS_SEND_EXCEPTION,
            createdAtMs = 10L,
            updatedAtMs = 20L
        )
        assertEquals(
            RetryDecision.DENY_UNKNOWN_OUTCOME,
            ReplyRetryPolicy.evaluateAttemptStatus(attemptUnknown, allowRetryOnPreDispatchFailure = true)
        )
        assertEquals(
            ReplyExecutionManager.FAILURE_UNKNOWN_OUTCOME_LOCKED,
            ReplyRetryPolicy.failureCategoryForDenial(RetryDecision.DENY_UNKNOWN_OUTCOME)
        )
        assertEquals(
            ReplyExecutionManager.FAILURE_DUPLICATE_EVENT,
            ReplyRetryPolicy.failureCategoryForDenial(RetryDecision.DENY_ALREADY_DISPATCHED)
        )
    }
}
