package com.parishod.watomatic.model.logs

import android.app.Notification
import android.content.Context
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.view.View
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.chip.Chip
import com.parishod.watomatic.R
import com.parishod.watomatic.activity.history.HistoryStatusFilter
import com.parishod.watomatic.activity.history.HistoryViewMode
import com.parishod.watomatic.activity.history.ReplyHistoryActivity
import com.parishod.watomatic.activity.history.ReplyHistoryAdapter
import com.parishod.watomatic.activity.history.ReplyHistoryRow
import com.parishod.watomatic.activity.history.ReplyHistoryViewModel
import com.parishod.watomatic.model.utils.Constants
import com.parishod.watomatic.model.utils.DbUtils
import com.parishod.watomatic.model.utils.ReportCopyHelper
import com.parishod.watomatic.service.reply.ReplyAttempt
import com.parishod.watomatic.service.reply.ReplyExecutionManager
import com.parishod.watomatic.service.reply.ReplyExecutionResult
import com.parishod.watomatic.service.reply.ReplyExecutionStatus
import com.parishod.watomatic.service.reply.ReplySourceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReplyAttemptPersistenceTest {

    private lateinit var context: Context
    private lateinit var db: MessageLogsDB
    private lateinit var dbUtils: DbUtils
    private lateinit var repository: ReplyHistoryRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        MessageLogsDB.resetInstance()
        context.deleteDatabase(Constants.LOGS_DB_NAME)
        db = MessageLogsDB.getInstance(context)
        dbUtils = DbUtils(context)
        repository = ReplyHistoryRepository(db.logsDao(), db.replyAttemptDao())
    }

    @After
    fun tearDown() {
        MessageLogsDB.resetInstance()
        context.deleteDatabase(Constants.LOGS_DB_NAME)
    }

    private fun buildSbn(
        packageName: String = "com.whatsapp",
        sender: String = "Alice",
        message: String = "Are you available?",
        whenMs: Long = 1_700_000_000_000L
    ): StatusBarNotification {
        val notification = NotificationCompat.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(sender)
            .setContentText(message)
            .setWhen(whenMs)
            .build()
        val sbn = mock<StatusBarNotification>()
        whenever(sbn.packageName).thenReturn(packageName)
        whenever(sbn.notification).thenReturn(notification)
        whenever(sbn.key).thenReturn("key-$sender-$whenMs")
        return sbn
    }

    @Test
    fun `attempt updates existing row across GENERATED, DISPATCHING, DISPATCHED without duplicates`() = runBlocking {
        val sbn = buildSbn(sender = "Alice", message = "Hello?")
        val initial = ReplyAttempt(
            attemptId = "attempt-lifecycle-1",
            sourceType = ReplySourceType.RULE_STATIC,
            sourceLabel = "Greeting Rule",
            packageName = "com.whatsapp",
            notificationKey = sbn.key,
            replyText = "Hi Alice!",
            status = ReplyExecutionStatus.GENERATED,
            createdAtMs = 1_000L,
            updatedAtMs = 1_000L
        )
        dbUtils.recordAttemptTransition(initial, "Alice", "Hello?")
        assertEquals(1, dbUtils.totalAttemptCount)
        assertEquals(0L, dbUtils.numReplies) // NOT logged as sent yet!

        val dispatching = initial.transitionTo(ReplyExecutionStatus.DISPATCHING, 1_100L)
        dbUtils.recordAttemptTransition(dispatching, "Alice", "Hello?")
        assertEquals(1, dbUtils.totalAttemptCount)
        assertEquals(0L, dbUtils.numReplies) // Still NOT logged as sent!

        val result = ReplyExecutionResult(
            executionId = initial.attemptId,
            status = ReplyExecutionStatus.DISPATCHED,
            dispatchAttempted = true,
            dispatchedNormally = true,
            failureCategory = null,
            diagnosticMessage = "PendingIntent dispatch accepted",
            timestampMs = 1_250L
        )
        dbUtils.recordAttemptResult(dispatching, result, "Alice", "Hello?", sbn)

        assertEquals(1, dbUtils.totalAttemptCount)
        assertEquals(1L, dbUtils.numReplies)

        val stored = dbUtils.getAttemptById(initial.attemptId)
        assertNotNull(stored)
        assertEquals(ReplyHistoryStatus.DISPATCHED, stored!!.status)
        assertEquals(1_000L, stored.createdAtMs)
        assertEquals(1_250L, stored.updatedAtMs)
        assertTrue(stored.dispatchAttempted)
        assertTrue(stored.dispatchedNormally)
        assertNull(stored.failureCategory)

        // Transitioning backwards after terminal DISPATCHED must be ignored
        dbUtils.recordAttemptTransition(initial, "Alice", "Hello?")
        assertEquals(ReplyHistoryStatus.DISPATCHED, dbUtils.getAttemptById(initial.attemptId)!!.status)
    }

    @Test
    fun `FAILED attempt is persisted with safe category and never counted as sent or grouped`() = runBlocking {
        val sbn = buildSbn(sender = "Bob", message = "Ping")
        val attempt = ReplyAttempt.create(
            ReplySourceType.DEFAULT,
            "Default Reply",
            "com.whatsapp",
            sbn.key,
            "I am busy"
        )
        val result = ReplyExecutionResult(
            executionId = attempt.attemptId,
            status = ReplyExecutionStatus.FAILED,
            dispatchAttempted = false,
            dispatchedNormally = false,
            failureCategory = ReplyExecutionManager.FAILURE_MISSING_PENDING_INTENT,
            diagnosticMessage = "PendingIntent was null",
            timestampMs = 2_000L
        )

        dbUtils.recordAttemptResult(attempt, result, "Bob", "Ping", sbn)

        assertEquals(1, dbUtils.totalAttemptCount)
        assertEquals(0L, dbUtils.numReplies)
        assertEquals(0L, dbUtils.getLastRepliedTime("com.whatsapp", "Bob"))
        assertTrue(repository.groupedReplyHistory.first().isEmpty())

        val timeline = repository.allReplyHistory.first()
        assertEquals(1, timeline.size)
        assertEquals(ReplyHistoryStatus.FAILED, timeline.first().executionStatus)
        assertEquals(ReplyExecutionManager.FAILURE_MISSING_PENDING_INTENT, timeline.first().failureCategory)
    }

    @Test
    fun `CANCELLED attempt is persisted accurately and never counted as sent`() = runBlocking {
        val sbn = buildSbn(sender = "Carol", message = "Help")
        val attempt = ReplyAttempt.create(
            ReplySourceType.GLOBAL_AI,
            "AI Reply",
            "com.whatsapp",
            sbn.key,
            "Sure, how can I help?"
        )
        val result = ReplyExecutionResult(
            executionId = attempt.attemptId,
            status = ReplyExecutionStatus.CANCELLED,
            dispatchAttempted = true,
            dispatchedNormally = false,
            failureCategory = ReplyExecutionManager.FAILURE_PENDING_INTENT_CANCELED,
            diagnosticMessage = "Canceled",
            timestampMs = 3_000L
        )

        dbUtils.recordAttemptResult(attempt, result, "Carol", "Help", sbn)

        assertEquals(1, dbUtils.totalAttemptCount)
        assertEquals(0L, dbUtils.numReplies)
        assertEquals(0L, dbUtils.getLastRepliedTime("com.whatsapp", "Carol"))

        val timeline = repository.allReplyHistory.first()
        assertEquals(1, timeline.size)
        assertEquals(ReplyHistoryStatus.CANCELLED, timeline.first().executionStatus)
        assertEquals(ReplyExecutionManager.FAILURE_PENDING_INTENT_CANCELED, timeline.first().failureCategory)
    }

    @Test
    fun `UNKNOWN attempt stays uncertain, is never counted as sent, and cannot be overwritten as DISPATCHED`() = runBlocking {
        val sbn = buildSbn(sender = "Dave", message = "Status?")
        val attempt = ReplyAttempt.create(
            ReplySourceType.RULE_AI,
            "Support • AI",
            "com.whatsapp",
            sbn.key,
            "Checking now"
        )
        val unknownResult = ReplyExecutionResult(
            executionId = attempt.attemptId,
            status = ReplyExecutionStatus.UNKNOWN,
            dispatchAttempted = true,
            dispatchedNormally = false,
            failureCategory = ReplyExecutionManager.FAILURE_AMBIGUOUS_SEND_EXCEPTION,
            diagnosticMessage = "Ambiguous IPC exception",
            timestampMs = 4_000L
        )

        dbUtils.recordAttemptResult(attempt, unknownResult, "Dave", "Status?", sbn)
        assertEquals(ReplyHistoryStatus.UNKNOWN, dbUtils.getAttemptById(attempt.attemptId)!!.status)
        assertEquals(0L, dbUtils.numReplies)

        // Even if someone attempts to upsert the same attemptId as DISPATCHED later,
        // DAO invariant refuses to turn UNKNOWN into DISPATCHED.
        val forgedDispatched = ReplyAttemptEntity.fromTransition(
            attempt.transitionTo(ReplyExecutionStatus.DISPATCHED, 4_500L),
            "Dave",
            "Status?"
        ).copy(dispatchAttempted = true, dispatchedNormally = true)
        db.replyAttemptDao().upsertAttemptSafely(forgedDispatched)

        assertEquals(ReplyHistoryStatus.UNKNOWN, dbUtils.getAttemptById(attempt.attemptId)!!.status)
    }

    @Test
    fun `failureCategory sanitization blocks raw URLs, keys, notes, and messages from persistence`() {
        val rawLeaks = listOf(
            "https://custom.api.example.com/v1?api_key=sk-secret-123",
            "Authorization: Bearer sk-live-987654321",
            "Exception: Customer message 'call me at 555-0199' failed",
            "  "
        )

        assertNull(ReplyAttemptEntity.sanitizeFailureCategory(null))
        assertNull(ReplyAttemptEntity.sanitizeFailureCategory("   "))
        for (leak in rawLeaks.take(3)) {
            val sanitized = ReplyAttemptEntity.sanitizeFailureCategory(leak)
            assertEquals("UNCLASSIFIED_FAILURE", sanitized)
            assertFalse(sanitized!!.contains("sk-"))
            assertFalse(sanitized.contains("https://"))
        }

        val sbn = buildSbn(sender = "Eve", message = "Private note")
        val attempt = ReplyAttempt.create(ReplySourceType.DEFAULT, "Default", "com.whatsapp", sbn.key, "Reply")
        val taintedResult = ReplyExecutionResult(
            executionId = attempt.attemptId,
            status = ReplyExecutionStatus.FAILED,
            dispatchAttempted = false,
            dispatchedNormally = false,
            failureCategory = "https://secret.internal/token=sk-123456",
            diagnosticMessage = "Raw message leak sk-123456 https://secret.internal/",
            timestampMs = 5_000L
        )
        dbUtils.recordAttemptResult(attempt, taintedResult, "Eve", "Private note", sbn)

        val stored = dbUtils.getAttemptById(attempt.attemptId)!!
        assertEquals("UNCLASSIFIED_FAILURE", stored.failureCategory)
        val formatted = ReportCopyHelper.formatReplyHistoryEntry(
            context,
            ReplyHistoryRow.Timeline(repository.allReplyHistory.run { runBlocking { first() } }.first())
        )
        assertFalse(formatted.contains("sk-123456"))
        assertFalse(formatted.contains("https://secret.internal"))
    }

    @Test
    fun `grouped history reports DISPATCHED, LEGACY, and MIXED accurately`() = runBlocking {
        val sbn1 = buildSbn(sender = "Alice", message = "Hours?")
        val attempt1 = ReplyAttempt.create(ReplySourceType.DEFAULT, "Default", "com.whatsapp", sbn1.key, "9-5")
        dbUtils.recordAttemptResult(
            attempt1,
            ReplyExecutionResult(attempt1.attemptId, ReplyExecutionStatus.DISPATCHED, true, true, null, "ok", 10_000L),
            "Alice",
            "Hours?",
            sbn1
        )

        // Same pair logged as legacy -> becomes MIXED in grouped view
        dbUtils.logReply(buildSbn(sender = "Bob", message = "Hours?"), "Bob", "Hours?", "9-5", "Default")

        // Another pair strictly DISPATCHED
        val sbn2 = buildSbn(sender = "Carl", message = "Price?")
        val attempt2 = ReplyAttempt.create(ReplySourceType.RULE_STATIC, "Pricing", "com.whatsapp", sbn2.key, "$10")
        dbUtils.recordAttemptResult(
            attempt2,
            ReplyExecutionResult(attempt2.attemptId, ReplyExecutionStatus.DISPATCHED, true, true, null, "ok", 11_000L),
            "Carl",
            "Price?",
            sbn2
        )

        // Another pair strictly LEGACY
        dbUtils.logReply(buildSbn(sender = "Dan", message = "Where?"), "Dan", "Where?", "Dhaka", "Loc")

        val grouped = repository.groupedReplyHistory.first()
        assertEquals(3, grouped.size)
        assertEquals(ReplyHistoryStatus.MIXED, grouped.first { it.incomingMessage == "Hours?" }.executionStatus)
        assertEquals(ReplyHistoryStatus.DISPATCHED, grouped.first { it.incomingMessage == "Price?" }.executionStatus)
        assertEquals(ReplyHistoryStatus.LEGACY, grouped.first { it.incomingMessage == "Where?" }.executionStatus)
    }

    @Test
    fun `ReplyHistoryActivity displays status badges and filters Timeline by execution status accurately`() {
        // Seed 1 DISPATCHED, 1 FAILED, 1 CANCELLED, 1 UNKNOWN, 1 LEGACY
        val sbnOk = buildSbn(sender = "OkUser", message = "Ok?")
        val okAttempt = ReplyAttempt.create(ReplySourceType.DEFAULT, "Default", "com.whatsapp", sbnOk.key, "Dispatched reply")
        dbUtils.recordAttemptResult(
            okAttempt,
            ReplyExecutionResult(okAttempt.attemptId, ReplyExecutionStatus.DISPATCHED, true, true, null, "ok", 50_000L),
            "OkUser", "Ok?", sbnOk
        )

        val sbnFail = buildSbn(sender = "FailUser", message = "Fail?")
        val failAttempt = ReplyAttempt.create(ReplySourceType.GLOBAL_AI, "AI Reply", "com.whatsapp", sbnFail.key, "Failed reply")
        dbUtils.recordAttemptResult(
            failAttempt,
            ReplyExecutionResult(failAttempt.attemptId, ReplyExecutionStatus.FAILED, false, false, ReplyExecutionManager.FAILURE_MISSING_REMOTE_INPUT, "missing", 51_000L),
            "FailUser", "Fail?", sbnFail
        )

        val sbnCancel = buildSbn(sender = "CancelUser", message = "Cancel?")
        val cancelAttempt = ReplyAttempt.create(ReplySourceType.RULE_STATIC, "Rule", "com.whatsapp", sbnCancel.key, "Cancelled reply")
        dbUtils.recordAttemptResult(
            cancelAttempt,
            ReplyExecutionResult(cancelAttempt.attemptId, ReplyExecutionStatus.CANCELLED, true, false, ReplyExecutionManager.FAILURE_PENDING_INTENT_CANCELED, "canceled", 52_000L),
            "CancelUser", "Cancel?", sbnCancel
        )

        val sbnUnknown = buildSbn(sender = "UnknownUser", message = "Unknown?")
        val unknownAttempt = ReplyAttempt.create(ReplySourceType.AI_FALLBACK, "AI Fallback", "com.whatsapp", sbnUnknown.key, "Uncertain reply")
        dbUtils.recordAttemptResult(
            unknownAttempt,
            ReplyExecutionResult(unknownAttempt.attemptId, ReplyExecutionStatus.UNKNOWN, true, false, ReplyExecutionManager.FAILURE_AMBIGUOUS_SEND_EXCEPTION, "ambiguous", 53_000L),
            "UnknownUser", "Unknown?", sbnUnknown
        )

        dbUtils.logReply(buildSbn(sender = "LegacyUser", message = "Legacy?"), "LegacyUser", "Legacy?", "Legacy reply", "Old Rule")

        val controller = Robolectric.buildActivity(ReplyHistoryActivity::class.java).setup()
        val activity = controller.get()

        fun waitUntil(condition: () -> Boolean) {
            val deadlineMs = System.currentTimeMillis() + 3_000L
            while (!condition() && System.currentTimeMillis() < deadlineMs) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(25)
            }
            shadowOf(Looper.getMainLooper()).idle()
        }

        // Total replies excludes FAILED, CANCELLED, UNKNOWN (counts only DISPATCHED + LEGACY = 2)
        waitUntil { activity.findViewById<TextView>(R.id.tv_total_replies_count).text.toString() == "2" }
        assertEquals("2", activity.findViewById<TextView>(R.id.tv_total_replies_count).text.toString())

        // Switch to All Replies (Timeline)
        activity.findViewById<Chip>(R.id.chip_mode_timeline).performClick()
        val recycler = activity.findViewById<RecyclerView>(R.id.history_recycler_view)
        waitUntil { recycler.adapter?.itemCount == 5 }
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.status_filter_scroll).visibility)
        assertEquals(5, recycler.adapter!!.itemCount)

        // Filter to Uncertain (UNKNOWN)
        activity.findViewById<Chip>(R.id.chip_status_unknown).performClick()
        waitUntil { recycler.adapter?.itemCount == 1 }
        assertEquals(1, recycler.adapter!!.itemCount)

        recycler.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST)
        )
        recycler.layout(0, 0, 1080, 1920)
        val unknownHolder = recycler.findViewHolderForAdapterPosition(0)!!
        assertEquals(
            context.getString(R.string.history_status_unknown),
            unknownHolder.itemView.findViewById<TextView>(R.id.tv_history_status_badge).text.toString()
        )
        assertEquals(
            context.getString(R.string.history_reply_unknown_label),
            unknownHolder.itemView.findViewById<TextView>(R.id.tv_history_reply_box_label).text.toString()
        )
        assertEquals(
            View.VISIBLE,
            unknownHolder.itemView.findViewById<TextView>(R.id.tv_history_status_note).visibility
        )

        // Filter to Failed
        activity.findViewById<Chip>(R.id.chip_status_failed).performClick()
        waitUntil {
            recycler.adapter?.itemCount == 1 && run {
                recycler.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST)
                )
                recycler.layout(0, 0, 1080, 1920)
                recycler.findViewHolderForAdapterPosition(0)?.itemView
                    ?.findViewById<TextView>(R.id.tv_history_status_badge)?.text?.toString() ==
                    context.getString(R.string.history_status_failed)
            }
        }
        assertEquals(1, recycler.adapter!!.itemCount)

        // Delete the failed attempt card and ensure list updates without deleting DISPATCHED logs
        recycler.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<View>(R.id.btn_delete_history_item).performClick()
        waitUntil { recycler.adapter?.itemCount == 0 }
        assertEquals(0, recycler.adapter!!.itemCount)
        assertEquals(2L, dbUtils.numReplies)
    }
}
