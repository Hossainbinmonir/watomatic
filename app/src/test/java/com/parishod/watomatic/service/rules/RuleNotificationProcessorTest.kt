package com.parishod.watomatic.service.rules

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import com.parishod.watomatic.NotificationWear
import com.parishod.watomatic.activity.rules.CreateEditRuleActivity
import com.parishod.watomatic.activity.rules.RuleDashboardActivity
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.rules.AutoReplyRule
import com.parishod.watomatic.model.rules.AutoReplyRulesDatabase
import com.parishod.watomatic.model.rules.MatchType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RuleNotificationProcessorTest {

    private lateinit var context: Context
    private lateinit var db: AutoReplyRulesDatabase
    private lateinit var executor: ScheduledExecutorService
    private lateinit var processor: RuleNotificationProcessor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferencesManager.resetInstance()
        AutoReplyRulesDatabase.resetInstance()
        context.deleteDatabase(AutoReplyRulesDatabase.DB_NAME)

        PreferencesManager.getPreferencesInstance(context).setServicePref(true)
        PreferencesManager.getPreferencesInstance(context).setAppendWatomaticAttribution(false)
        PreferencesManager.getPreferencesInstance(context).setRuleFallbackToGlobalEnabled(false)

        db = AutoReplyRulesDatabase.getInstance(context)
        executor = Executors.newSingleThreadScheduledExecutor()
        processor = RuleNotificationProcessor(context, executor)
    }

    @After
    fun tearDown() {
        processor.shutdown()
        executor.shutdownNow()
        AutoReplyRulesDatabase.resetInstance()
        context.deleteDatabase(AutoReplyRulesDatabase.DB_NAME)
        PreferencesManager.resetInstance()
    }

    private fun awaitBackgroundTasks() {
        executor.submit { }.get(5, TimeUnit.SECONDS)
    }

    private fun buildNotificationSbn(
        packageName: String = "com.whatsapp",
        key: String = "notif-key-1",
        title: String = "Alice",
        messageText: String = "Hello there",
        hasReplyAction: Boolean = true,
        isGroup: Boolean = false,
        whenMs: Long = System.currentTimeMillis(),
        selfDisplayName: String? = "Me"
    ): StatusBarNotification {
        val builder = NotificationCompat.Builder(context, "test_ch")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(messageText)
            .setWhen(whenMs)

        if (hasReplyAction) {
            val pi = PendingIntent.getBroadcast(
                context,
                1,
                Intent("com.parishod.watomatic.TEST_REPLY"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val remoteInput = RemoteInput.Builder("key_text_reply")
                .setLabel("Reply")
                .setAllowFreeFormInput(true)
                .build()
            val action = NotificationCompat.Action.Builder(0, "Reply", pi)
                .addRemoteInput(remoteInput)
                .build()
            builder.addAction(action)
        }

        val notification: Notification = builder.build().apply {
            extras.putBoolean("android.isGroupConversation", isGroup)
            if (selfDisplayName != null) {
                extras.putString("android.selfDisplayName", selfDisplayName)
            }
        }

        val sbn = mock<StatusBarNotification>()
        whenever(sbn.packageName).thenReturn(packageName)
        whenever(sbn.key).thenReturn(key)
        whenever(sbn.postTime).thenReturn(whenMs)
        whenever(sbn.notification).thenReturn(notification)
        whenever(sbn.isOngoing).thenReturn(false)
        return sbn
    }

    @Test
    fun `missing notification reply action does not trigger rule or fallback`() = runBlocking {
        withContext(Dispatchers.IO) {
            db.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Greeting",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("hello"),
                    replyMessage = "Hi!"
                )
            )
        }

        var ruleReplies = 0
        var fallbacks = 0
        val callback = object : RuleNotificationProcessor.ReplyDispatchCallback {
            override fun onRuleMatchedSendReply(
                sbn: StatusBarNotification,
                notificationWear: NotificationWear,
                replyText: String,
                matchedRule: AutoReplyRule
            ) { ruleReplies++ }
            override fun onFallbackToLegacy(sbn: StatusBarNotification) { fallbacks++ }
        }

        val sbn = buildNotificationSbn(hasReplyAction = false, messageText = "hello")
        processor.processNotification(sbn, callback)
        awaitBackgroundTasks()

        assertEquals(0, ruleReplies)
        assertEquals(0, fallbacks)
    }

    @Test
    fun `duplicate notification is suppressed and only triggers once`() = runBlocking {
        withContext(Dispatchers.IO) {
            db.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Order Status",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("order"),
                    replyMessage = "Checking your order"
                )
            )
        }

        val dispatchedReplies = mutableListOf<String>()
        val callback = object : RuleNotificationProcessor.ReplyDispatchCallback {
            override fun onRuleMatchedSendReply(
                sbn: StatusBarNotification,
                notificationWear: NotificationWear,
                replyText: String,
                matchedRule: AutoReplyRule
            ) { dispatchedReplies.add(replyText) }
            override fun onFallbackToLegacy(sbn: StatusBarNotification) {}
        }

        val now = System.currentTimeMillis()
        val sbn1 = buildNotificationSbn(key = "dup-1", messageText = "Where is my order?", whenMs = now)
        val sbn2 = buildNotificationSbn(key = "dup-1", messageText = "Where is my order?", whenMs = now)

        processor.processNotification(sbn1, callback)
        processor.processNotification(sbn2, callback)
        awaitBackgroundTasks()

        assertEquals(1, dispatchedReplies.size)
        assertEquals("Checking your order", dispatchedReplies.first())
    }

    @Test
    fun `different source applications dispatch to matching rule only`() = runBlocking {
        withContext(Dispatchers.IO) {
            db.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Telegram Only",
                    isEnabled = true,
                    selectedPackages = listOf("org.telegram.messenger"),
                    matchType = MatchType.EXACT,
                    keywords = listOf("ping"),
                    replyMessage = "Pong from Telegram"
                )
            )
        }

        val dispatched = mutableListOf<Pair<String, String>>()
        val callback = object : RuleNotificationProcessor.ReplyDispatchCallback {
            override fun onRuleMatchedSendReply(
                sbn: StatusBarNotification,
                notificationWear: NotificationWear,
                replyText: String,
                matchedRule: AutoReplyRule
            ) { dispatched.add(sbn.packageName to replyText) }
            override fun onFallbackToLegacy(sbn: StatusBarNotification) {}
        }

        val waSbn = buildNotificationSbn(packageName = "com.whatsapp", key = "k-wa", messageText = "ping")
        val tgSbn = buildNotificationSbn(packageName = "org.telegram.messenger", key = "k-tg", messageText = "ping")

        processor.processNotification(waSbn, callback)
        processor.processNotification(tgSbn, callback)
        awaitBackgroundTasks()

        assertEquals(1, dispatched.size)
        assertEquals("org.telegram.messenger" to "Pong from Telegram", dispatched.first())
    }

    @Test
    fun `falls back to legacy when no rules exist and ignores self package`() {
        var fallbacks = 0
        val callback = object : RuleNotificationProcessor.ReplyDispatchCallback {
            override fun onRuleMatchedSendReply(
                sbn: StatusBarNotification,
                notificationWear: NotificationWear,
                replyText: String,
                matchedRule: AutoReplyRule
            ) {}
            override fun onFallbackToLegacy(sbn: StatusBarNotification) { fallbacks++ }
        }

        val selfSbn = buildNotificationSbn(packageName = context.packageName, key = "k-self", messageText = "hello")
        val normalSbn = buildNotificationSbn(packageName = "com.whatsapp", key = "k-wa", messageText = "hello")

        processor.processNotification(selfSbn, callback)
        processor.processNotification(normalSbn, callback)
        awaitBackgroundTasks()

        assertEquals(1, fallbacks)
    }

    @Test
    fun `rule dashboard and create edit activities inflate cleanly`() {
        val dashboardController = Robolectric.buildActivity(RuleDashboardActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(dashboardController.get())
        assertTrue(!dashboardController.get().isFinishing)

        val editorController = Robolectric.buildActivity(CreateEditRuleActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(editorController.get())
        assertTrue(!editorController.get().isFinishing)
    }
}
