package com.parishod.watomatic.model.utils

import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.parishod.watomatic.R
import com.parishod.watomatic.activity.customreplyeditor.CustomReplyEditorActivity
import com.parishod.watomatic.activity.history.HistoryViewMode
import com.parishod.watomatic.activity.history.ReplyHistoryActivity
import com.parishod.watomatic.activity.history.ReplyHistoryRow
import com.parishod.watomatic.activity.history.ReplyHistoryUiState
import com.parishod.watomatic.activity.notes.AiNotesActivity
import com.parishod.watomatic.activity.notes.AiNotesUiState
import com.parishod.watomatic.activity.notes.NotesFilterMode
import com.parishod.watomatic.model.ai.AiMemoryDatabase
import com.parishod.watomatic.model.ai.AiNote
import com.parishod.watomatic.model.logs.AppPackage
import com.parishod.watomatic.model.logs.MessageLog
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.logs.ReplyHistoryDetailItem
import com.parishod.watomatic.model.logs.ReplyHistorySummaryItem
import com.parishod.watomatic.model.preferences.PreferencesManager
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReportCopyHelperTest {

    private lateinit var context: Context
    private val fixedFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferencesManager.resetInstance()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        val enc = context.getSharedPreferences("test_report_copy_enc", Context.MODE_PRIVATE)
        enc.edit().clear().commit()
        PreferencesManager.setEncryptedPrefsProviderForTesting { enc }
        MessageLogsDB.getInstance(context).clearAllTables()
        clearAiMemoryDb()
    }

    @After
    fun tearDown() {
        MessageLogsDB.getInstance(context).clearAllTables()
        clearAiMemoryDb()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("test_report_copy_enc", Context.MODE_PRIVATE).edit().clear().commit()
        PreferencesManager.resetInstance()
    }

    private fun clearAiMemoryDb() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit {
                AiMemoryDatabase.getInstance(context).clearAllTables()
            }.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `formatReplyHistoryEntry formats grouped and timeline rows cleanly`() {
        val grouped = ReplyHistoryRow.Grouped(
            ReplyHistorySummaryItem(
                incomingMessage = "Price please?",
                repliedMessage = "Starts at $10",
                packageName = "com.whatsapp",
                matchedRuleName = "Pricing",
                lastSender = "Rahim",
                replyCount = 3,
                lastReplyTime = 1_700_000_000_000L
            )
        )
        val groupedText = ReportCopyHelper.formatReplyHistoryEntry(context, grouped, fixedFormatter)
        assertTrue(groupedText.contains("WhatsApp"))
        assertTrue(groupedText.contains("[Pricing]"))
        assertTrue(groupedText.contains("3x"))
        assertTrue(groupedText.contains("From: Rahim"))
        assertTrue(groupedText.contains("Incoming: Price please?"))
        assertTrue(groupedText.contains("Reply: Starts at $10"))

        val timeline = ReplyHistoryRow.Timeline(
            ReplyHistoryDetailItem(
                id = 7,
                packageName = "org.telegram.messenger",
                senderName = "Karim",
                incomingMessage = "Hello",
                repliedMessage = "Hi there!",
                matchedRuleName = "AI Reply",
                replyTime = 1_700_000_000_000L
            )
        )
        val timelineText = ReportCopyHelper.formatReplyHistoryEntry(context, timeline, fixedFormatter)
        assertTrue(timelineText.contains("Telegram"))
        assertTrue(timelineText.contains("[AI Reply]"))
        assertTrue(timelineText.contains("From: Karim"))
        assertTrue(timelineText.contains("Incoming: Hello"))
        assertTrue(timelineText.contains("Reply: Hi there!"))
    }

    @Test
    fun `formatReplyHistoryReport includes totals, filter query, and numbered entries`() {
        val state = ReplyHistoryUiState(
            totalReplies = 5,
            uniqueMessages = 2,
            mode = HistoryViewMode.TIMELINE,
            searchQuery = "price",
            displayedRows = listOf(
                ReplyHistoryRow.Timeline(
                    ReplyHistoryDetailItem(
                        id = 1,
                        packageName = "com.whatsapp",
                        senderName = "Alice",
                        incomingMessage = "Price?",
                        repliedMessage = "$25",
                        matchedRuleName = "Sales",
                        replyTime = 1_700_000_000_000L
                    )
                )
            )
        )

        val report = ReportCopyHelper.formatReplyHistoryReport(context, state, fixedFormatter)
        assertTrue(report.contains("=== Reply History ==="))
        assertTrue(report.contains("Total Replies: 5 | Unique Messages: 2"))
        assertTrue(report.contains("Search: \"price\""))
        assertTrue(report.contains("#1 WhatsApp [Sales]"))
        assertTrue(report.contains("Reply: $25"))
    }

    @Test
    fun `formatAiNotesReport includes pending and completed counts and note details`() {
        val note1 = AiNote(
            id = 1L,
            senderName = "Boss",
            packageName = "com.whatsapp",
            noteText = "Send monthly report tomorrow",
            sourceMessage = "Please send me the monthly report tomorrow morning",
            createdAt = 1_700_000_000_000L,
            isCompleted = false
        )
        val note2 = AiNote(
            id = 2L,
            senderName = "",
            packageName = "",
            noteText = "Buy coffee",
            sourceMessage = "",
            createdAt = 1_700_000_060_000L,
            isCompleted = true
        )
        val state = AiNotesUiState(
            pendingCount = 1,
            completedCount = 1,
            totalCount = 2,
            filterMode = NotesFilterMode.ALL,
            searchQuery = "",
            displayedNotes = listOf(note1, note2)
        )

        val report = ReportCopyHelper.formatAiNotesReport(context, state, fixedFormatter)
        assertTrue(report.contains("=== AI Notes & Reminders ==="))
        assertTrue(report.contains("Pending: 1 | Completed: 1"))
        assertTrue(report.contains("#1 [PENDING] Send monthly report tomorrow"))
        assertTrue(report.contains("From: Boss (WhatsApp)"))
        assertTrue(report.contains("Message: Please send me the monthly report tomorrow morning"))
        assertTrue(report.contains("#2 [DONE] Buy coffee"))
        assertTrue(report.contains("From: Manual Note"))
    }

    @Test
    fun `copyToClipboard ignores blank text and writes primary clip for non-blank text`() {
        assertFalse(ReportCopyHelper.copyToClipboard(context, "Report", "   "))
        assertTrue(ReportCopyHelper.copyToClipboard(context, "Report", "Sample Report Text"))

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("Sample Report Text", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `ReplyHistoryActivity supports one-click full report copy and per-card copy`() {
        val db = MessageLogsDB.getInstance(context)
        db.appPackageDao().insertAppPackage(AppPackage("com.whatsapp"))
        val pkgIndex = db.appPackageDao().getPackageIndex("com.whatsapp")
        db.logsDao().logReply(
            MessageLog(
                pkgIndex,
                "Rahim",
                "Where is the office?",
                1_700_000_000_000L,
                "Dhaka, Banani",
                "Location Rule",
                1_700_000_001_000L
            )
        )

        val controller = Robolectric.buildActivity(ReplyHistoryActivity::class.java).setup()
        val activity = controller.get()

        // Allow Room Flow + lifecycle collector to populate UI
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()

        val copyReportBtn = activity.findViewById<MaterialButton>(R.id.btn_copy_history_report)
        assertEquals(View.VISIBLE, copyReportBtn.visibility)
        copyReportBtn.performClick()

        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copiedReport = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(copiedReport.contains("Reply History"))
        assertTrue(copiedReport.contains("Where is the office?"))
        assertTrue(copiedReport.contains("Dhaka, Banani"))

        // Verify per-card one-click copy button in RecyclerView
        val recycler = activity.findViewById<RecyclerView>(R.id.history_recycler_view)
        recycler.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST)
        )
        recycler.layout(0, 0, 1080, 1920)
        val holder = recycler.findViewHolderForAdapterPosition(0)
        assertNotNull(holder)
        val itemCopyBtn = holder!!.itemView.findViewById<ImageButton>(R.id.btn_copy_history_item)
        itemCopyBtn.performClick()

        val copiedItem = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(copiedItem.contains("Where is the office?"))
        assertTrue(copiedItem.contains("Dhaka, Banani"))
        assertFalse(copiedItem.contains("=== Reply History ==="))
    }

    @Test
    fun `AiNotesActivity supports one-click full report copy`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit {
                AiMemoryDatabase.getInstance(context).aiMemoryDao().insertNoteSync(
                    AiNote(
                        senderName = "Client X",
                        packageName = "com.whatsapp",
                        noteText = "Follow up on quotation",
                        sourceMessage = "Remind me to follow up on quotation",
                        createdAt = 1_700_000_000_000L,
                        isCompleted = false
                    )
                )
            }.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        val controller = Robolectric.buildActivity(AiNotesActivity::class.java).setup()
        val activity = controller.get()

        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()

        val copyReportBtn = activity.findViewById<MaterialButton>(R.id.btn_copy_notes_report)
        assertEquals(View.VISIBLE, copyReportBtn.visibility)
        copyReportBtn.performClick()

        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copiedReport = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(copiedReport.contains("AI Notes & Reminders"))
        assertTrue(copiedReport.contains("Follow up on quotation"))
        assertTrue(copiedReport.contains("Client X"))
    }

    @Test
    fun `CustomReplyEditorActivity copies AI status report on one click`() {
        val prefs = PreferencesManager.getPreferencesInstance(context)
        prefs.saveOpenAIApiKeySecurely("sk-test-copy-123")
        prefs.saveOpenApiSource("OpenAI")
        prefs.saveSelectedOpenAIModel("gpt-4o-mini")
        prefs.saveOpenAILastPersistentError("HTTP 429 (Rate limit or quota exceeded)", System.currentTimeMillis())
        prefs.setEnableByokReplies(true)

        val controller = Robolectric.buildActivity(CustomReplyEditorActivity::class.java).setup()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()

        val copyStatusBtn = activity.findViewById<ImageButton>(R.id.btn_copy_ai_status)
        copyStatusBtn.performClick()

        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(copied.contains("OpenAI"))
        assertTrue(copied.contains("HTTP 429 (Rate limit or quota exceeded)"))
    }
}
