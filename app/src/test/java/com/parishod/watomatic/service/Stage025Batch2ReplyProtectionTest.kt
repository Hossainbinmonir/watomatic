package com.parishod.watomatic.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.Parcelable
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.parishod.watomatic.NotificationWear
import com.parishod.watomatic.model.ai.AiMemoryDatabase
import com.parishod.watomatic.model.logs.MessageLog
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.logs.NotificationDispatchClaimEntity
import com.parishod.watomatic.model.logs.ReplyAttemptEntity
import com.parishod.watomatic.model.logs.ReplyHistoryStatus
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.rules.AutoReplyRule
import com.parishod.watomatic.model.rules.AutoReplyRulesDatabase
import com.parishod.watomatic.model.rules.MatchType
import com.parishod.watomatic.model.rules.RuleExecutionLog
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
import com.parishod.watomatic.service.reply.ReplyAttempt
import com.parishod.watomatic.service.reply.ReplyExecutionManager
import com.parishod.watomatic.service.reply.ReplyExecutionResult
import com.parishod.watomatic.service.reply.ReplyExecutionStatus
import com.parishod.watomatic.service.reply.ReplySourceType
import com.parishod.watomatic.service.rules.RoomRuleExecutionLogStore
import com.parishod.watomatic.service.rules.RuleEvaluator
import com.parishod.watomatic.service.rules.RuleExecutionReservationCoordinator
import com.parishod.watomatic.service.rules.RuleNotificationProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Stage025Batch2ReplyProtectionTest {

    private lateinit var context: Context
    private lateinit var prefs: PreferencesManager
    private lateinit var logsDb: MessageLogsDB
    private lateinit var rulesDb: AutoReplyRulesDatabase
    private lateinit var dbUtils: DbUtils
    private val createdServices = mutableListOf<NotificationService>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferencesManager.resetInstance()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        val enc = context.getSharedPreferences("test_stage025_b2_enc", Context.MODE_PRIVATE)
        enc.edit().clear().commit()
        PreferencesManager.setEncryptedPrefsProviderForTesting { enc }
        prefs = PreferencesManager.getPreferencesInstance(context)
        prefs.setServicePref(true)
        prefs.saveEnabledApps("com.whatsapp", true)
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
    }

    @After
    fun tearDown() {
        for (svc in createdServices) {
            runCatching { svc.onDestroy() }
            runCatching { svc.awaitAiMemoryIdleForTesting() }
        }
        createdServices.clear()
        PendingClaimHandoff.clear()
        MessageLogsDB.resetInstance()
        context.deleteDatabase(Constants.LOGS_DB_NAME)
        AutoReplyRulesDatabase.resetInstance()
        context.deleteDatabase(AutoReplyRulesDatabase.DB_NAME)
        AiMemoryDatabase.resetInstance()
        context.deleteDatabase(AiMemoryDatabase.DB_NAME)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("test_stage025_b2_enc", Context.MODE_PRIVATE).edit().clear().commit()
        PreferencesManager.resetInstance()
    }

    private fun buildService(sendCounter: AtomicInteger = AtomicInteger(0)): NotificationService {
        val svc = Robolectric.buildService(NotificationService::class.java).create().get()
        svc.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(
                context,
                pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                    sendCounter.incrementAndGet()
                }
            )
        )
        createdServices.add(svc)
        return svc
    }

    private fun buildSbn(
        pkg: String = "com.whatsapp",
        id: Int = 100,
        tag: String? = "tag-$id",
        sender: String? = "Alice",
        message: String? = "Hello",
        whenMs: Long = System.currentTimeMillis(),
        postTimeMs: Long = whenMs,
        isGroup: Boolean = false,
        messagingStyleEntries: List<Pair<String, Long>>? = null
    ): StatusBarNotification {
        val replyPi = PendingIntent.getBroadcast(
            context,
            id,
            Intent("com.parishod.watomatic.BATCH2_REPLY").setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val ri = RemoteInput.Builder("reply_key").setLabel("Reply").setAllowFreeFormInput(true).build()
        val action = NotificationCompat.Action.Builder(0, "Reply", replyPi).addRemoteInput(ri).build()
        val builder = NotificationCompat.Builder(context, "ch")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setWhen(whenMs)
            .addAction(action)
        if (sender != null) {
            builder.setContentTitle(sender)
        }
        if (message != null) {
            builder.setContentText(message)
        }
        val notification = builder.build().apply {
            extras.putBoolean("android.isGroupConversation", isGroup)
            if (isGroup && sender != null) {
                extras.putString("android.hiddenConversationTitle", sender)
            }
            if (messagingStyleEntries != null) {
                val bundles = messagingStyleEntries.map { (msgText, msgTime) ->
                    Bundle().apply {
                        putCharSequence("text", msgText)
                        putLong("time", msgTime)
                        putCharSequence("sender", sender ?: "Unknown")
                    }
                }.toTypedArray<Parcelable>()
                extras.putParcelableArray("android.messages", bundles)
            }
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

    private fun wearFromSbn(sbn: StatusBarNotification): NotificationWear {
        val ri = RemoteInput.Builder("reply_key").setLabel("Reply").setAllowFreeFormInput(true).build()
        val pi = PendingIntent.getBroadcast(
            context,
            sbn.id + 5000,
            Intent("com.parishod.watomatic.BATCH2_WEAR").setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        return NotificationWear(sbn.packageName, pi, listOf(ri), null, sbn.tag, sbn.id.toString())
    }

    private fun testKeyedCallback(
        service: NotificationService,
        coordinator: RuleExecutionReservationCoordinator,
        onDispatched: (AutoReplyRule, String?) -> Unit = { _, _ -> }
    ) = object : RuleNotificationProcessor.ClaimAwareReplyDispatchCallback() {
        override fun onRuleMatchedSendReply(
            sbn: StatusBarNotification,
            notificationWear: NotificationWear,
            replyText: String,
            matchedRule: AutoReplyRule,
            claimToken: String?,
            eventKey: String?,
            reservation: RuleExecutionReservationCoordinator.RuleReservation?
        ) {
            val res = service.sendActualReply(
                sbn,
                notificationWear,
                replyText,
                matchedRule.name,
                ReplySourceType.RULE_STATIC,
                matchedRule,
                claimToken,
                reservation
            )
            if (!res.isDispatched() && reservation != null) {
                if (res.isUnknown()) {
                    coordinator.markUnknown(reservation.reservationId, res.timestampMs)
                } else {
                    coordinator.release(reservation.reservationId)
                }
            } else if (res.isDispatched() && reservation != null) {
                // Wire Reservation coordinator on service directly is used for service-managed flows,
                // while explicit external coordinators confirm/release here cleanly.
                coordinator.confirmDispatched(reservation.reservationId, res.timestampMs)
            }
            onDispatched(matchedRule, claimToken)
        }

        override fun onFallbackToLegacy(
            sbn: StatusBarNotification,
            claimToken: String?,
            eventKey: String?
        ) {
            if (eventKey != null && claimToken != null) {
                dbUtils.releaseUnusedNotificationClaim(eventKey, claimToken)
            }
        }
    }

    // =====================================================================
    // F-025-04: Multi-Rule Reservation & Cooldown Bypass
    // =====================================================================

    @Test
    fun `F025-04 concurrent matching notifications for same rule with cooldown produce single reply`() = runBlocking {
        withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Cooldown Rule",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("price"),
                    replyMessage = "Check catalog",
                    cooldownSeconds = 120
                )
            )
        }
        val sendCount = AtomicInteger(0)
        val service = buildService(sendCount)
        val ruleExecutor = Executors.newScheduledThreadPool(4)
        val callerPool = Executors.newFixedThreadPool(4)
        val processor = RuleNotificationProcessor(
            context,
            ruleExecutor,
            dbUtils,
            service.getRuleReservationCoordinatorForTesting()
        )
        val startGate = CountDownLatch(1)
        val cb = testKeyedCallback(service, processor.reservationCoordinator)
        try {
            val now = System.currentTimeMillis()
            val sbn1 = buildSbn(id = 101, sender = "Alice", message = "What is price 1?", whenMs = now)
            val sbn2 = buildSbn(id = 102, sender = "Alice", message = "What is price 2?", whenMs = now + 50L)
            val f1 = callerPool.submit {
                startGate.await(5, TimeUnit.SECONDS)
                processor.processNotification(sbn1, cb)
            }
            val f2 = callerPool.submit {
                startGate.await(5, TimeUnit.SECONDS)
                processor.processNotification(sbn2, cb)
            }
            startGate.countDown()
            f1.get(5, TimeUnit.SECONDS)
            f2.get(5, TimeUnit.SECONDS)
            ruleExecutor.shutdown()
            assertTrue(ruleExecutor.awaitTermination(5, TimeUnit.SECONDS))
            service.awaitAiMemoryIdleForTesting()
            assertEquals(1, sendCount.get())
            assertEquals(1L, dbUtils.numReplies)
        } finally {
            processor.shutdown()
            ruleExecutor.shutdownNow()
            callerPool.shutdownNow()
        }
    }

    @Test
    fun `F025-04 concurrent matching notifications for same rule with maxRepliesPerSender 1 produce single reply`() = runBlocking {
        withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Frequency Limit Rule",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("quote"),
                    replyMessage = "Quote coming",
                    cooldownSeconds = 0,
                    maxRepliesPerSender = 1,
                    frequencyWindowMinutes = 60
                )
            )
        }
        val sendCount = AtomicInteger(0)
        val service = buildService(sendCount)
        val ruleExecutor = Executors.newScheduledThreadPool(4)
        val callerPool = Executors.newFixedThreadPool(4)
        val processor = RuleNotificationProcessor(
            context,
            ruleExecutor,
            dbUtils,
            service.getRuleReservationCoordinatorForTesting()
        )
        val startGate = CountDownLatch(1)
        val cb = testKeyedCallback(service, processor.reservationCoordinator)
        try {
            val now = System.currentTimeMillis()
            val sbn1 = buildSbn(id = 111, sender = "Alice", message = "Send quote A", whenMs = now)
            val sbn2 = buildSbn(id = 112, sender = "Alice", message = "Send quote B", whenMs = now + 10L)
            val f1 = callerPool.submit {
                startGate.await(5, TimeUnit.SECONDS)
                processor.processNotification(sbn1, cb)
            }
            val f2 = callerPool.submit {
                startGate.await(5, TimeUnit.SECONDS)
                processor.processNotification(sbn2, cb)
            }
            startGate.countDown()
            f1.get(5, TimeUnit.SECONDS)
            f2.get(5, TimeUnit.SECONDS)
            ruleExecutor.shutdown()
            assertTrue(ruleExecutor.awaitTermination(5, TimeUnit.SECONDS))
            service.awaitAiMemoryIdleForTesting()
            assertEquals(1, sendCount.get())
            assertEquals(1L, dbUtils.numReplies)
        } finally {
            processor.shutdown()
            ruleExecutor.shutdownNow()
            callerPool.shutdownNow()
        }
    }

    @Test
    fun `F025-04 delayed rule reserves slot early so second notification arriving during delay is blocked`() = runBlocking {
        withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Delayed Rule",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("delay"),
                    replyMessage = "Delayed ack",
                    replyDelaySeconds = 10,
                    cooldownSeconds = 60,
                    maxRepliesPerSender = 1,
                    frequencyWindowMinutes = 60
                )
            )
        }
        val scheduledTasks = mutableListOf<Runnable>()
        val deferredScheduler = CapturingScheduledExecutor(scheduledTasks)
        val sendCount = AtomicInteger(0)
        val service = buildService(sendCount)
        val processor = RuleNotificationProcessor(
            context,
            deferredScheduler,
            dbUtils,
            service.getRuleReservationCoordinatorForTesting()
        )
        val matchedCount = AtomicInteger(0)
        val cb = testKeyedCallback(service, processor.reservationCoordinator) { _, _ ->
            matchedCount.incrementAndGet()
        }
        try {
            val now = System.currentTimeMillis()
            val sbn1 = buildSbn(id = 121, sender = "Bob", message = "delay msg 1", whenMs = now)
            val sbn2 = buildSbn(id = 122, sender = "Bob", message = "delay msg 2", whenMs = now + 500L)

            // First notification matches and queues delayed dispatch; reservation MUST already block sbn2!
            processor.processNotification(sbn1, cb)
            assertEquals(1, scheduledTasks.size)
            assertEquals(1, processor.reservationCoordinator.activeReservedCount())

            // Second notification arrives BEFORE first delayed task executes -> MUST be blocked
            processor.processNotification(sbn2, cb)
            assertEquals(1, scheduledTasks.size)
            assertEquals(0, matchedCount.get())

            // Now fire the single scheduled delayed task
            scheduledTasks.single().run()
            service.awaitAiMemoryIdleForTesting()
            assertEquals(1, matchedCount.get())
            assertEquals(1, sendCount.get())
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `F025-04 async AI rule reserves slot during AI network call and releases cleanly on pre-dispatch failure`() = runBlocking {
        val ruleId = withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Async AI Rule",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("booking"),
                    replyMessage = "",
                    useAiReply = true,
                    cooldownSeconds = 120,
                    maxRepliesPerSender = 1,
                    frequencyWindowMinutes = 60
                )
            )
        }
        val rule = withContext(Dispatchers.IO) { rulesDb.ruleDao().getRuleById(ruleId)!! }
        val sendCount = AtomicInteger(0)
        val service = buildService(sendCount)
        val coordinator = service.getRuleReservationCoordinatorForTesting()
        val logStore = RoomRuleExecutionLogStore(rulesDb.executionLogDao())

        val now = System.currentTimeMillis()
        val ctx1 = com.parishod.watomatic.service.rules.IncomingMessageContext(
            packageName = "com.whatsapp",
            senderName = "Carol",
            messageText = "booking request 1",
            isGroup = false,
            notificationKey = "k-c1",
            messageTimestamp = now
        )
        val res1 = withContext(Dispatchers.IO) {
            coordinator.evaluateAndReserve(listOf(rule), ctx1, logStore, now)
        }
        assertNotNull(res1)

        // Second notification during pending AI work MUST be blocked while reservation is RESERVED
        val ctx2 = com.parishod.watomatic.service.rules.IncomingMessageContext(
            packageName = "com.whatsapp",
            senderName = "Carol",
            messageText = "booking request 2",
            isGroup = false,
            notificationKey = "k-c2",
            messageTimestamp = now + 200L
        )
        assertNull(
            withContext(Dispatchers.IO) {
                coordinator.evaluateAndReserve(listOf(rule), ctx2, logStore, now + 200L)
            }
        )

        // Simulate pre-dispatch failure (e.g., manager shut down before send)
        val shutdownManager = ReplyExecutionManager(
            context,
            pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ -> sendCount.incrementAndGet() }
        ).apply { shutdown() }
        service.setReplyExecutionManagerForTesting(shutdownManager)

        val sbnFail = buildSbn(id = 131, sender = "Carol", message = "booking request 1", whenMs = now)
        val failOutcome = service.sendActualReply(
            sbnFail,
            wearFromSbn(sbnFail),
            "Confirmed",
            rule.name,
            ReplySourceType.RULE_AI,
            rule,
            null,
            res1!!.reservation
        )
        assertEquals(ReplyExecutionStatus.CANCELLED, failOutcome.status)
        assertFalse(failOutcome.dispatchAttempted)
        assertEquals(0, sendCount.get())

        // Pre-dispatch failure MUST release the reservation so subsequent distinct message succeeds
        val res2AfterRelease = withContext(Dispatchers.IO) {
            coordinator.evaluateAndReserve(listOf(rule), ctx2, logStore, now + 500L)
        }
        assertNotNull(res2AfterRelease)
        coordinator.release(res2AfterRelease!!.reservation.reservationId)
    }

    @Test
    fun `F025-04 direct sender and group sender with identical name are isolated and preserve legacy direct logs`() = runBlocking {
        val ruleId = withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Group Isolation Rule",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("status"),
                    replyMessage = "Working on it",
                    cooldownSeconds = 300,
                    maxRepliesPerSender = 1,
                    frequencyWindowMinutes = 60
                )
            )
        }
        val rule = withContext(Dispatchers.IO) { rulesDb.ruleDao().getRuleById(ruleId)!! }
        val sendCount = AtomicInteger(0)
        val service = buildService(sendCount)
        val logStore = RoomRuleExecutionLogStore(rulesDb.executionLogDao())
        val coordinator = service.getRuleReservationCoordinatorForTesting()

        val now = System.currentTimeMillis()
        // 1. Seed a legacy direct rule log ("Alice" without group prefix) -> must still enforce cooldown for DM "Alice"
        withContext(Dispatchers.IO) {
            logStore.recordExecution(ruleId, "com.whatsapp", "Alice", "legacy-dm-key", now - 10_000L, now - 10_000L)
        }

        val dmCtx = com.parishod.watomatic.service.rules.IncomingMessageContext(
            packageName = "com.whatsapp",
            senderName = "Alice",
            messageText = "status?",
            isGroup = false,
            notificationKey = "dm-2",
            messageTimestamp = now
        )
        assertNull(
            "Legacy DM log should enforce cooldown on DM Alice",
            withContext(Dispatchers.IO) { coordinator.evaluateAndReserve(listOf(rule), dmCtx, logStore, now) }
        )

        // 2. Group named "Alice" MUST NOT be blocked by DM "Alice"
        val groupCtx = com.parishod.watomatic.service.rules.IncomingMessageContext(
            packageName = "com.whatsapp",
            senderName = "Alice",
            messageText = "status?",
            isGroup = true,
            notificationKey = "grp-1",
            messageTimestamp = now
        )
        val groupMatch = withContext(Dispatchers.IO) {
            coordinator.evaluateAndReserve(listOf(rule), groupCtx, logStore, now)
        }
        assertNotNull("Group Alice must not collide with DM Alice", groupMatch)

        val sbnGroup = buildSbn(id = 141, sender = "Alice", message = "status?", whenMs = now, isGroup = true)
        val dispatchRes = service.sendActualReply(
            sbnGroup,
            wearFromSbn(sbnGroup),
            "Working on it",
            rule.name,
            ReplySourceType.RULE_STATIC,
            rule,
            null,
            groupMatch!!.reservation
        )
        service.awaitAiMemoryIdleForTesting()
        assertEquals(ReplyExecutionStatus.DISPATCHED, dispatchRes.status)
        assertEquals(1, sendCount.get())

        // Group log is stored under group-scoped sender key, separate from DM "Alice"
        withContext(Dispatchers.IO) {
            assertEquals(1, logStore.getReplyCountForRuleAndSenderSince(ruleId, "com.whatsapp", "Alice", 0L))
            assertEquals(1, logStore.getReplyCountForRuleAndSenderSince(ruleId, "com.whatsapp", "group:Alice", 0L))
        }
    }

    @Test
    fun `F025-04 UNKNOWN dispatch locks reservation conservatively against duplicate rule execution`() = runBlocking {
        val ruleId = withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Ambiguous Lock Rule",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("help"),
                    replyMessage = "Help is coming",
                    cooldownSeconds = 300,
                    maxRepliesPerSender = 1,
                    frequencyWindowMinutes = 60
                )
            )
        }
        val rule = withContext(Dispatchers.IO) { rulesDb.ruleDao().getRuleById(ruleId)!! }
        val service = Robolectric.buildService(NotificationService::class.java).create().get()
        createdServices.add(service)
        service.setReplyExecutionManagerForTesting(
            ReplyExecutionManager(
                context,
                pendingIntentSender = ReplyExecutionManager.PendingIntentSender { _, _, _ ->
                    throw RuntimeException("Binder IPC ambiguous error")
                }
            )
        )

        val coordinator = service.getRuleReservationCoordinatorForTesting()
        val logStore = RoomRuleExecutionLogStore(rulesDb.executionLogDao())
        val now = System.currentTimeMillis()
        val ctx1 = com.parishod.watomatic.service.rules.IncomingMessageContext(
            packageName = "com.whatsapp",
            senderName = "Dan",
            messageText = "help please",
            isGroup = false,
            notificationKey = "unk-1",
            messageTimestamp = now
        )
        val reserved = withContext(Dispatchers.IO) {
            coordinator.evaluateAndReserve(listOf(rule), ctx1, logStore, now)
        }!!

        val sbnFail = buildSbn(id = 151, sender = "Dan", message = "help please", whenMs = now)
        val unkResult = service.sendActualReply(
            sbnFail,
            wearFromSbn(sbnFail),
            "Help is coming",
            rule.name,
            ReplySourceType.RULE_STATIC,
            rule,
            null,
            reserved.reservation
        )
        service.awaitAiMemoryIdleForTesting()
        assertEquals(ReplyExecutionStatus.UNKNOWN, unkResult.status)
        assertEquals(
            RuleExecutionReservationCoordinator.ReservationStatus.LOCKED_UNKNOWN,
            coordinator.getStatus(reserved.reservation.reservationId)
        )
        // DB rule_execution_logs remains unpolluted (only confirmed DISPATCHED writes DB logs)
        withContext(Dispatchers.IO) {
            assertEquals(0, logStore.getReplyCountForRuleAndSenderSince(ruleId, "com.whatsapp", "Dan", 0L))
        }
        // Yet another message from Dan for the same rule within cooldown/window is conservatively blocked
        val ctx2 = com.parishod.watomatic.service.rules.IncomingMessageContext(
            packageName = "com.whatsapp",
            senderName = "Dan",
            messageText = "help again",
            isGroup = false,
            notificationKey = "unk-2",
            messageTimestamp = now + 2_000L
        )
        assertNull(
            withContext(Dispatchers.IO) {
                coordinator.evaluateAndReserve(listOf(rule), ctx2, logStore, now + 2_000L)
            }
        )
    }

    @Test
    fun `F025-04 accepted rule log task scheduled on main looper is not dropped by service onDestroy`() = runBlocking {
        val ruleId = withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Shutdown Drain Rule",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("order"),
                    replyMessage = "Order received",
                    cooldownSeconds = 60
                )
            )
        }
        val rule = withContext(Dispatchers.IO) { rulesDb.ruleDao().getRuleById(ruleId)!! }
        val service = buildService(AtomicInteger(0))

        // Ensure task runs on postDispatchPersistenceExecutor (not dropped by aiMemoryExecutor.shutdownNow())
        val queuedExec = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        queuedExec.execute { gate.await(5, TimeUnit.SECONDS) }
        service.setPostDispatchPersistenceExecutorForTesting(queuedExec)

        val now = System.currentTimeMillis()
        val sbn = buildSbn(id = 161, sender = "Eva", message = "order 123", whenMs = now)
        assertTrue(Looper.myLooper() == Looper.getMainLooper())
        val res = service.sendActualReply(
            sbn,
            wearFromSbn(sbn),
            "Order received",
            rule.name,
            ReplySourceType.RULE_STATIC,
            rule,
            null
        )
        assertEquals(ReplyExecutionStatus.DISPATCHED, res.status)

        // Destroy service BEFORE queued persistence runs -> postDispatch executor gracefully drains, NOT shutdownNow()
        service.onDestroy()
        gate.countDown()
        queuedExec.shutdown()
        assertTrue(queuedExec.awaitTermination(5, TimeUnit.SECONDS))

        withContext(Dispatchers.IO) {
            val logStore = RoomRuleExecutionLogStore(rulesDb.executionLogDao())
            assertEquals(1, logStore.getReplyCountForRuleAndSenderSince(ruleId, "com.whatsapp", "Eva", 0L))
        }
    }

    // =====================================================================
    // F-025-05: DbUtils Split-State Claim Reconciliation
    // =====================================================================

    @Test
    fun `F025-05 terminal FAILED attempt rejects DISPATCHED override and reconciles claim without leaving stuck CLAIMED`() {
        val eventKey = "evt_f02505_failed_attempt"
        val attemptId = "att-f02505-1"
        assertTrue(
            dbUtils.tryAcquireNotificationClaim(
                eventKey, attemptId, "com.whatsapp", "alice", "k-1", 1000L, false
            ).acquired
        )

        // Pre-existing terminal FAILED attempt row in reply_attempts
        logsDb.replyAttemptDao().upsertAttemptSafely(
            ReplyAttemptEntity(
                attemptId = attemptId,
                packageName = "com.whatsapp",
                senderName = "Alice",
                incomingMessage = "Hi",
                notificationKey = "k-1",
                replyText = "Reply",
                sourceType = ReplySourceType.DEFAULT.name,
                sourceLabel = "Default",
                status = ReplyExecutionStatus.FAILED.name,
                dispatchAttempted = false,
                dispatchedNormally = false,
                failureCategory = ReplyExecutionManager.FAILURE_MISSING_PENDING_INTENT,
                createdAtMs = 1000L,
                updatedAtMs = 1500L,
                eventKey = eventKey
            )
        )

        val attempt = ReplyAttempt.createWithAttemptId(
            attemptId, ReplySourceType.DEFAULT, "Default", "com.whatsapp", "k-1", "Reply", eventKey
        )
        val conflictingDispatched = ReplyExecutionResult(
            attemptId, ReplyExecutionStatus.DISPATCHED, true, true, null, "Conflicting DISPATCHED", 2000L
        )
        val outcome = dbUtils.recordAttemptAndClaimResult(
            eventKey, attempt, conflictingDispatched, "Alice", "Hi", buildSbn(id = 201)
        )

        assertEquals(ReplyExecutionStatus.FAILED, outcome.effectiveAttemptStatus)
        assertEquals(ReplyExecutionStatus.FAILED.name, outcome.effectiveClaimStatus)
        assertFalse(outcome.dispatchedPersisted)
        assertTrue(outcome.claimUpdated)
        assertTrue(outcome.reconciledFromAuthoritativeAttempt)

        // Claim MUST NOT be stuck in CLAIMED or falsely promoted to DISPATCHED
        val storedClaim = dbUtils.getNotificationClaim(eventKey)!!
        assertEquals(ReplyExecutionStatus.FAILED.name, storedClaim.claimStatus)
        assertEquals(ReplyExecutionManager.FAILURE_MISSING_PENDING_INTENT, storedClaim.failureCategory)
        assertEquals(0L, dbUtils.numReplies)
    }

    @Test
    fun `F025-05 terminal DISPATCHED attempt ignores conflicting FAILED update and reconciles claim to DISPATCHED`() {
        val eventKey = "evt_f02505_dispatched_attempt"
        val attemptId = "att-f02505-2"
        assertTrue(
            dbUtils.tryAcquireNotificationClaim(
                eventKey, attemptId, "com.whatsapp", "alice", "k-2", 1000L, false
            ).acquired
        )

        val attempt = ReplyAttempt.createWithAttemptId(
            attemptId, ReplySourceType.DEFAULT, "Default", "com.whatsapp", "k-2", "Reply", eventKey
        )
        val firstDispatched = ReplyExecutionResult(
            attemptId, ReplyExecutionStatus.DISPATCHED, true, true, null, "OK", 1500L
        )
        dbUtils.recordAttemptResult(attempt, firstDispatched, "Alice", "Hi", buildSbn(id = 202))
        assertTrue(dbUtils.hasConsistentDispatchedPersistence(attemptId))

        // Conflicting stale FAILED update arrives while claim is still CLAIMED
        val conflictingFailed = ReplyExecutionResult(
            attemptId, ReplyExecutionStatus.FAILED, false, false, ReplyExecutionManager.FAILURE_SECURITY_EXCEPTION, "Stale failure", 2000L
        )
        val outcome = dbUtils.recordAttemptAndClaimResult(
            eventKey, attempt, conflictingFailed, "Alice", "Hi", buildSbn(id = 202)
        )

        assertEquals(ReplyExecutionStatus.DISPATCHED, outcome.effectiveAttemptStatus)
        assertEquals(ReplyExecutionStatus.DISPATCHED.name, outcome.effectiveClaimStatus)
        assertTrue(outcome.dispatchedPersisted)
        assertTrue(outcome.claimUpdated)
        assertTrue(outcome.reconciledFromAuthoritativeAttempt)

        val claim = dbUtils.getNotificationClaim(eventKey)!!
        assertEquals(ReplyExecutionStatus.DISPATCHED.name, claim.claimStatus)
        assertTrue(claim.dispatchedNormally)
    }

    @Test
    fun `F025-05 DISPATCHED attempt without MessageLog reconciles claim to UNKNOWN and reports dispatchedPersisted false`() {
        val eventKey = "evt_f02505_missing_log"
        val attemptId = "att-f02505-3"
        assertTrue(
            dbUtils.tryAcquireNotificationClaim(
                eventKey, attemptId, "com.whatsapp", "alice", "k-3", 1000L, false
            ).acquired
        )

        // Seed corrupted DISPATCHED attempt without any MessageLog row
        logsDb.replyAttemptDao().upsertAttemptSafely(
            ReplyAttemptEntity(
                attemptId = attemptId,
                packageName = "com.whatsapp",
                senderName = "Alice",
                incomingMessage = "Hi",
                notificationKey = "k-3",
                replyText = "Reply",
                sourceType = ReplySourceType.DEFAULT.name,
                sourceLabel = "Default",
                status = ReplyExecutionStatus.DISPATCHED.name,
                dispatchAttempted = true,
                dispatchedNormally = true,
                failureCategory = null,
                createdAtMs = 1000L,
                updatedAtMs = 1500L,
                eventKey = eventKey
            )
        )

        val attempt = ReplyAttempt.createWithAttemptId(
            attemptId, ReplySourceType.DEFAULT, "Default", "com.whatsapp", "k-3", "Reply", eventKey
        )
        val nonDispatchedRes = ReplyExecutionResult(
            attemptId,
            ReplyExecutionStatus.FAILED,
            false,
            false,
            ReplyExecutionManager.FAILURE_SECURITY_EXCEPTION,
            "Late error after partial DISPATCHED write",
            2000L
        )
        val outcome = dbUtils.recordAttemptAndClaimResult(
            eventKey, attempt, nonDispatchedRes, "Alice", "Hi", buildSbn(id = 203)
        )

        assertEquals(ReplyExecutionStatus.UNKNOWN, outcome.effectiveAttemptStatus)
        assertEquals(ReplyExecutionStatus.UNKNOWN.name, outcome.effectiveClaimStatus)
        assertFalse(outcome.dispatchedPersisted)
        assertTrue(outcome.claimUpdated)
        assertEquals(ReplyExecutionManager.FAILURE_CLAIM_PERSISTENCE_ERROR, outcome.failureCategory)
        assertEquals(ReplyExecutionStatus.UNKNOWN.name, dbUtils.getNotificationClaim(eventKey)!!.claimStatus)
    }

    // =====================================================================
    // F-025-06: PendingClaimHandoff & Explicit Claim Token Propagation
    // =====================================================================

    @Test
    fun `F025-06 delayed rule callback retains original claimToken explicitly even when handoff registry receives 700 other entries`() = runBlocking {
        withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Explicit Token Rule",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("token"),
                    replyMessage = "Token intact",
                    replyDelaySeconds = 5
                )
            )
        }
        val scheduledTasks = mutableListOf<Runnable>()
        val deferredExecutor = CapturingScheduledExecutor(scheduledTasks)
        val service = buildService(AtomicInteger(0))
        val processor = RuleNotificationProcessor(
            context,
            deferredExecutor,
            dbUtils,
            service.getRuleReservationCoordinatorForTesting()
        )

        val receivedToken = AtomicReference<String?>(null)
        val receivedReservation = AtomicReference<RuleExecutionReservationCoordinator.RuleReservation?>(null)
        val sbn = buildSbn(id = 301, sender = "Frank", message = "check token handoff", whenMs = System.currentTimeMillis())
        val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn)!!

        val callback = object : RuleNotificationProcessor.ClaimAwareReplyDispatchCallback() {
            override fun onRuleMatchedSendReply(
                sbn: StatusBarNotification,
                notificationWear: NotificationWear,
                replyText: String,
                matchedRule: AutoReplyRule,
                claimToken: String?,
                eventKey: String?,
                reservation: RuleExecutionReservationCoordinator.RuleReservation?
            ) {
                receivedToken.set(claimToken)
                receivedReservation.set(reservation)
                val res = service.sendActualReply(
                    sbn, notificationWear, replyText, matchedRule.name, ReplySourceType.RULE_STATIC, matchedRule, claimToken, reservation
                )
                assertEquals(ReplyExecutionStatus.DISPATCHED, res.status)
            }

            override fun onFallbackToLegacy(sbn: StatusBarNotification, claimToken: String?, eventKey: String?) {}
        }

        processor.processNotification(sbn, callback)
        assertEquals(1, scheduledTasks.size)
        val originalClaim = dbUtils.getNotificationClaim(eventKey)!!
        assertEquals(NotificationDispatchClaimEntity.STATUS_CLAIMED, originalClaim.claimStatus)

        // Register 700 other handoffs while the delayed callback is waiting
        for (i in 1..700) {
            PendingClaimHandoff.register("evt-noise-$i", "noise-tok-$i")
        }

        scheduledTasks.single().run()
        service.awaitAiMemoryIdleForTesting()

        // Delayed callback received the EXACT original claimToken explicitly
        assertEquals(originalClaim.attemptId, receivedToken.get())
        assertNotNull(receivedReservation.get())
        assertEquals(ReplyExecutionStatus.DISPATCHED.name, dbUtils.getNotificationClaim(eventKey)!!.claimStatus)
        assertEquals(originalClaim.attemptId, dbUtils.getNotificationClaim(eventKey)!!.attemptId)

        // Duplicate delivery MUST be suppressed and never dispatch twice
        processor.processNotification(sbn, callback)
        assertEquals(1, scheduledTasks.size)
        assertEquals(1L, dbUtils.numReplies)
        processor.shutdown()
    }

    @Test
    fun `F025-06 cancelled delayed callbacks do not leave stuck CLAIMED claims and token-guarded cleanup protects other notifications`() = runBlocking {
        withContext(Dispatchers.IO) {
            rulesDb.ruleDao().insertRule(
                AutoReplyRule(
                    name = "Cancel Delay Rule",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("cancel"),
                    replyMessage = "Should cancel",
                    replyDelaySeconds = 10
                )
            )
        }
        val scheduledTasks = mutableListOf<Runnable>()
        val deferredExecutor = CapturingScheduledExecutor(scheduledTasks)
        val processor = RuleNotificationProcessor(context, deferredExecutor, dbUtils)
        val sbn = buildSbn(id = 310, sender = "Grace", message = "cancel me", whenMs = System.currentTimeMillis())
        val eventKey = NotificationEventKeyBuilder.buildEventKey(sbn)!!

        val cb = object : RuleNotificationProcessor.ClaimAwareReplyDispatchCallback() {
            override fun onRuleMatchedSendReply(
                sbn: StatusBarNotification,
                notificationWear: NotificationWear,
                replyText: String,
                matchedRule: AutoReplyRule,
                claimToken: String?,
                eventKey: String?,
                reservation: RuleExecutionReservationCoordinator.RuleReservation?
            ) {}
            override fun onFallbackToLegacy(sbn: StatusBarNotification, claimToken: String?, eventKey: String?) {}
        }

        processor.processNotification(sbn, cb)
        assertEquals(1, scheduledTasks.size)
        assertEquals(NotificationDispatchClaimEntity.STATUS_CLAIMED, dbUtils.getNotificationClaim(eventKey)!!.claimStatus)

        // Shutdown cancels pending delayed callbacks cleanly
        processor.shutdown()
        val claimAfterShutdown = dbUtils.getNotificationClaim(eventKey)!!
        assertEquals(ReplyExecutionStatus.CANCELLED.name, claimAfterShutdown.claimStatus)
        assertEquals(ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN, claimAfterShutdown.failureCategory)
        assertEquals(0, processor.reservationCoordinator.activeReservedCount())

        // Token-guarded PendingClaimHandoff.complete/cancel cannot wipe another notification's token
        PendingClaimHandoff.register("evt-A", "token-A-2")
        PendingClaimHandoff.register("evt-B", "token-B-1")
        assertFalse(PendingClaimHandoff.cancel("evt-A", "stale-token-A-1"))
        assertEquals("token-A-2", PendingClaimHandoff.peek("evt-A"))
        assertEquals("token-B-1", PendingClaimHandoff.peek("evt-B"))
        assertTrue(PendingClaimHandoff.complete("evt-A", "token-A-2"))
        assertNull(PendingClaimHandoff.peek("evt-A"))
        assertEquals("token-B-1", PendingClaimHandoff.consume("evt-B"))
    }

    // =====================================================================
    // F-025-07: Event Key Stability
    // =====================================================================

    @Test
    fun `F025-07 notification with when=0 and stable MessagingStyle timestamp yields identical key across changing postTime`() {
        val msgTime = 1700000500000L
        val firstDelivery = buildSbn(
            id = 401,
            tag = "wa-chat",
            sender = "Hank",
            message = "Can we meet at 5?",
            whenMs = 0L,
            postTimeMs = 1700000500100L,
            messagingStyleEntries = listOf("Can we meet at 5?" to msgTime)
        )
        val repostedWithNewPostTime = buildSbn(
            id = 401,
            tag = "wa-chat",
            sender = "Hank",
            message = "Can we meet at 5?",
            whenMs = 0L,
            postTimeMs = 1700000512999L,
            messagingStyleEntries = listOf("Can we meet at 5?" to msgTime)
        )

        val key1 = NotificationEventKeyBuilder.buildEventKey(firstDelivery)
        val key2 = NotificationEventKeyBuilder.buildEventKey(repostedWithNewPostTime)
        assertNotNull(key1)
        assertEquals(key1, key2)

        // End-to-end suppression check in NotificationService: second reposted delivery MUST be suppressed
        val sendCount = AtomicInteger(0)
        val service = buildService(sendCount)
        val res1 = service.sendActualReply(firstDelivery, wearFromSbn(firstDelivery), "Sure!", null, ReplySourceType.DEFAULT)
        val res2 = service.sendActualReply(repostedWithNewPostTime, wearFromSbn(repostedWithNewPostTime), "Sure!", null, ReplySourceType.DEFAULT)
        assertEquals(ReplyExecutionStatus.DISPATCHED, res1.status)
        assertEquals(ReplyExecutionStatus.CANCELLED, res2.status)
        assertEquals(ReplyExecutionManager.FAILURE_DUPLICATE_EVENT, res2.failureCategory)
        assertEquals(1, sendCount.get())
    }

    @Test
    fun `F025-07 distinct messages or distinct MessagingStyle timestamps from same sender are never falsely deduplicated`() {
        val msgTime1 = 1700000600000L
        val msgTime2 = 1700000605000L

        val firstMsg = buildSbn(
            id = 402,
            tag = "wa-chat",
            sender = "Hank",
            message = "Ok",
            whenMs = 0L,
            postTimeMs = 1700000600100L,
            messagingStyleEntries = listOf("Ok" to msgTime1)
        )
        val secondSameTextNewTime = buildSbn(
            id = 402,
            tag = "wa-chat",
            sender = "Hank",
            message = "Ok",
            whenMs = 0L,
            postTimeMs = 1700000605100L,
            messagingStyleEntries = listOf("Ok" to msgTime1, "Ok" to msgTime2)
        )
        val secondDifferentTextSameTime = buildSbn(
            id = 402,
            tag = "wa-chat",
            sender = "Hank",
            message = "See you soon",
            whenMs = 0L,
            postTimeMs = 1700000600100L,
            messagingStyleEntries = listOf("See you soon" to msgTime1)
        )

        val k1 = NotificationEventKeyBuilder.buildEventKey(firstMsg)
        val k2 = NotificationEventKeyBuilder.buildEventKey(secondSameTextNewTime)
        val k3 = NotificationEventKeyBuilder.buildEventKey(secondDifferentTextSameTime)

        assertEquals(3, setOf(k1, k2, k3).size)
        assertNotEquals(k1, k2)
        assertNotEquals(k1, k3)
    }

    private class CapturingScheduledExecutor(
        private val scheduled: MutableList<Runnable>
    ) : AbstractExecutorService(), ScheduledExecutorService {
        private var isShutdownFlag = false
        private val worker = Executors.newSingleThreadExecutor()
        override fun shutdown() {
            isShutdownFlag = true
            worker.shutdown()
        }
        override fun shutdownNow(): MutableList<Runnable> {
            isShutdownFlag = true
            worker.shutdownNow()
            return scheduled.toMutableList().also { scheduled.clear() }
        }
        override fun isShutdown(): Boolean = isShutdownFlag
        override fun isTerminated(): Boolean = isShutdownFlag && worker.isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = worker.awaitTermination(timeout, unit)
        override fun execute(command: Runnable) {
            worker.submit(command).get(5, TimeUnit.SECONDS)
        }
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            scheduled.add(command)
            return NoOpScheduledFuture<Any?>()
        }
        override fun <V> schedule(callable: java.util.concurrent.Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> =
            throw UnsupportedOperationException()
        override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException()
        override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException()
    }

    private class NoOpScheduledFuture<V> : ScheduledFuture<V> {
        override fun compareTo(other: java.util.concurrent.Delayed?): Int = 0
        override fun getDelay(unit: TimeUnit): Long = 0L
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        override fun isCancelled(): Boolean = false
        override fun isDone(): Boolean = false
        override fun get(): V = throw UnsupportedOperationException()
        override fun get(timeout: Long, unit: TimeUnit): V = throw UnsupportedOperationException()
    }
}
