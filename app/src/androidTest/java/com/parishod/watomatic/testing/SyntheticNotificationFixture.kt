package com.parishod.watomatic.testing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Self-contained Android notification and [RemoteInput] test fixture for instrumentation tests.
 *
 * Posts real notifications on a dedicated test channel and routes [RemoteInput] replies through
 * a test-owned mutable [PendingIntent] to an in-process test [BroadcastReceiver], eliminating any
 * dependency on third-party messaging apps.
 */
class SyntheticNotificationFixture(
    private val context: Context
) : AutoCloseable {

    data class CapturedReply(
        val requestCode: Int,
        val replyText: String?,
        val receivedAtMs: Long,
        val intentFlags: Int,
        val action: String?,
        val hasRemoteInputBundle: Boolean,
        val remoteInputKeys: Set<String>
    )

    data class PostedNotification(
        val id: Int,
        val tag: String,
        val packageName: String,
        val notification: Notification,
        val pendingIntent: PendingIntent,
        val remoteInput: RemoteInput,
        val postedAtMs: Long
    )

    private val appContext: Context = context.applicationContext
    private val notificationManager: NotificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val channelId: String = DEFAULT_CHANNEL_ID
    val notificationTag: String = "watomatic_test_${UUID.randomUUID().toString().take(8)}"
    val replyAction: String = "com.parishod.watomatic.testing.ACTION_SYNTHETIC_REPLY.${UUID.randomUUID()}"

    private val nextNotificationId = AtomicInteger(900_000)
    private val nextRequestCode = AtomicInteger(10_000)
    private val invocationCounter = AtomicInteger(0)
    private val closed = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)

    private val captureLock = Object()
    private val capturedReplies = Collections.synchronizedList(mutableListOf<CapturedReply>())
    private val postedNotificationIds = ConcurrentHashMap.newKeySet<Int>()
    private val activePendingIntents = Collections.synchronizedList(mutableListOf<PendingIntent>())
    private val resultKeysByRequestCode = ConcurrentHashMap<Int, String>()

    private val replyReceiver = object : BroadcastReceiver() {
        override fun onReceive(recvContext: Context?, intent: Intent?) {
            if (intent == null) return
            val receivedAt = System.currentTimeMillis()
            val reqCode = intent.getIntExtra(EXTRA_REQUEST_CODE, -1)
            val expectedResultKey = resultKeysByRequestCode[reqCode]
                ?: intent.getStringExtra(EXTRA_RESULT_KEY)
                ?: DEFAULT_REMOTE_INPUT_KEY

            val remoteInputResults: Bundle? = RemoteInput.getResultsFromIntent(intent)
            val replyText = remoteInputResults?.getCharSequence(expectedResultKey)?.toString()
                ?: remoteInputResults?.keySet()
                    ?.firstOrNull()
                    ?.let { fallbackKey -> remoteInputResults.getCharSequence(fallbackKey)?.toString() }

            val captured = CapturedReply(
                requestCode = reqCode,
                replyText = replyText,
                receivedAtMs = receivedAt,
                intentFlags = intent.flags,
                action = intent.action,
                hasRemoteInputBundle = remoteInputResults != null,
                remoteInputKeys = remoteInputResults?.keySet()?.toSet().orEmpty()
            )

            synchronized(captureLock) {
                capturedReplies.add(captured)
                invocationCounter.incrementAndGet()
                captureLock.notifyAll()
            }
        }
    }

    init {
        ensureNotificationChannel()
        registerReceiver()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = notificationManager.getNotificationChannel(channelId)
            if (existing == null) {
                val channel = NotificationChannel(
                    channelId,
                    "Watomatic Instrumentation Test Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Isolated notification channel for Watomatic instrumentation tests"
                    setSound(null, null)
                    enableVibration(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered.compareAndSet(false, true)) {
            ContextCompat.registerReceiver(
                appContext,
                replyReceiver,
                IntentFilter(replyAction),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    /**
     * Builds a real [Notification] containing a free-form [RemoteInput] reply action backed by a
     * test-owned mutable [PendingIntent].
     */
    fun buildReplyNotification(
        title: String = "Test Contact",
        text: String = "Hello from synthetic notification",
        actionTitle: String = "Reply",
        remoteInputResultKey: String = DEFAULT_REMOTE_INPUT_KEY,
        remoteInputLabel: String = "Reply",
        isGroupConversation: Boolean = false,
        selfDisplayName: String? = null,
        whenMs: Long = System.currentTimeMillis()
    ): Triple<Notification, PendingIntent, RemoteInput> {
        check(!closed.get()) { "SyntheticNotificationFixture has already been closed" }
        val requestCode = nextRequestCode.incrementAndGet()
        resultKeysByRequestCode[requestCode] = remoteInputResultKey

        val baseIntent = Intent(replyAction).apply {
            setPackage(appContext.packageName)
            putExtra(EXTRA_REQUEST_CODE, requestCode)
            putExtra(EXTRA_RESULT_KEY, remoteInputResultKey)
        }

        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            requestCode,
            baseIntent,
            pendingIntentFlags
        )
        activePendingIntents.add(pendingIntent)

        val remoteInput = RemoteInput.Builder(remoteInputResultKey)
            .setLabel(remoteInputLabel)
            .setAllowFreeFormInput(true)
            .build()

        val replyActionCompat = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            actionTitle,
            pendingIntent
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        val extras = Bundle().apply {
            putString("android.title", title)
            putCharSequence("android.text", text)
            putBoolean("android.isGroupConversation", isGroupConversation)
            if (selfDisplayName != null) {
                putString("android.selfDisplayName", selfDisplayName)
            }
        }

        val notification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setWhen(whenMs)
            .setShowWhen(true)
            .addExtras(extras)
            .addAction(replyActionCompat)
            .build()

        return Triple(notification, pendingIntent, remoteInput)
    }

    /**
     * Posts a synthetic notification with a free-form [RemoteInput] action to Android's
     * [NotificationManager] so it can be retrieved from [NotificationManager.getActiveNotifications].
     */
    fun postNotification(
        title: String = "Test Contact",
        text: String = "Hello from synthetic notification",
        actionTitle: String = "Reply",
        remoteInputResultKey: String = DEFAULT_REMOTE_INPUT_KEY,
        isGroupConversation: Boolean = false,
        selfDisplayName: String? = null,
        whenMs: Long = System.currentTimeMillis(),
        notificationId: Int = nextNotificationId.incrementAndGet()
    ): PostedNotification {
        val (notification, pendingIntent, remoteInput) = buildReplyNotification(
            title = title,
            text = text,
            actionTitle = actionTitle,
            remoteInputResultKey = remoteInputResultKey,
            isGroupConversation = isGroupConversation,
            selfDisplayName = selfDisplayName,
            whenMs = whenMs
        )
        postedNotificationIds.add(notificationId)
        notificationManager.notify(notificationTag, notificationId, notification)
        return PostedNotification(
            id = notificationId,
            tag = notificationTag,
            packageName = appContext.packageName,
            notification = notification,
            pendingIntent = pendingIntent,
            remoteInput = remoteInput,
            postedAtMs = whenMs
        )
    }

    /**
     * Waits for a posted synthetic notification to become visible in
     * [NotificationManager.getActiveNotifications].
     */
    fun awaitActiveNotification(
        notificationId: Int,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): StatusBarNotification? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val match = notificationManager.activeNotifications.firstOrNull { sbn ->
                sbn.id == notificationId && sbn.tag == notificationTag
            }
            if (match != null) {
                return match
            }
            SystemClock.sleep(25L)
        }
        return notificationManager.activeNotifications.firstOrNull { sbn ->
            sbn.id == notificationId && sbn.tag == notificationTag
        }
    }

    /**
     * Builds a synthetic [StatusBarNotification] under an arbitrary [sourcePackageName]
     * (defaulting to a non-production synthetic package) containing a real [RemoteInput] action
     * and test-owned mutable [PendingIntent].
     */
    fun buildSyntheticStatusBarNotification(
        sourcePackageName: String = DEFAULT_SYNTHETIC_SOURCE_PACKAGE,
        title: String = "Test Contact",
        text: String = "Hello from synthetic notification",
        actionTitle: String = "Reply",
        remoteInputResultKey: String = DEFAULT_REMOTE_INPUT_KEY,
        isGroupConversation: Boolean = false,
        selfDisplayName: String? = null,
        whenMs: Long = System.currentTimeMillis(),
        notificationId: Int = nextNotificationId.incrementAndGet(),
        tag: String? = notificationTag
    ): StatusBarNotification {
        val (notification, _, _) = buildReplyNotification(
            title = title,
            text = text,
            actionTitle = actionTitle,
            remoteInputResultKey = remoteInputResultKey,
            isGroupConversation = isGroupConversation,
            selfDisplayName = selfDisplayName,
            whenMs = whenMs
        )
        return StatusBarNotification(
            sourcePackageName,
            sourcePackageName,
            notificationId,
            tag,
            Process.myUid(),
            0,
            0,
            notification,
            UserHandle.getUserHandleForUid(Process.myUid()),
            whenMs
        )
    }

    fun getCapturedReplies(): List<CapturedReply> {
        synchronized(captureLock) {
            return capturedReplies.toList()
        }
    }

    fun getLastCapturedReply(): CapturedReply? {
        synchronized(captureLock) {
            return capturedReplies.lastOrNull()
        }
    }

    fun getInvocationCount(): Int = invocationCounter.get()

    fun awaitInvocationCount(
        expectedCount: Int,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        synchronized(captureLock) {
            while (invocationCounter.get() < expectedCount) {
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0L) {
                    return false
                }
                captureLock.wait(remaining)
            }
            return true
        }
    }

    fun cancelPostedNotifications() {
        val ids = postedNotificationIds.toList()
        postedNotificationIds.clear()
        for (id in ids) {
            runCatching { notificationManager.cancel(notificationTag, id) }
        }
    }

    /**
     * Clears captured reply state and cancels posted test notifications without unregistering
     * the receiver so the fixture can be reused across steps within a test.
     */
    fun reset() {
        cancelPostedNotifications()
        synchronized(captureLock) {
            capturedReplies.clear()
            invocationCounter.set(0)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        cancelPostedNotifications()
        val pendingIntents = synchronized(activePendingIntents) {
            val copy = activePendingIntents.toList()
            activePendingIntents.clear()
            copy
        }
        for (pi in pendingIntents) {
            runCatching { pi.cancel() }
        }
        resultKeysByRequestCode.clear()
        synchronized(captureLock) {
            capturedReplies.clear()
            invocationCounter.set(0)
            captureLock.notifyAll()
        }
        if (receiverRegistered.compareAndSet(true, false)) {
            runCatching { appContext.unregisterReceiver(replyReceiver) }
        }
    }

    companion object {
        const val DEFAULT_CHANNEL_ID = "watomatic_test_notification_channel"
        const val DEFAULT_REMOTE_INPUT_KEY = "key_text_reply"
        const val DEFAULT_SYNTHETIC_SOURCE_PACKAGE = "com.test.synthetic.messenger"
        private const val EXTRA_REQUEST_CODE = "com.parishod.watomatic.testing.EXTRA_REQUEST_CODE"
        private const val EXTRA_RESULT_KEY = "com.parishod.watomatic.testing.EXTRA_RESULT_KEY"
        private const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}
