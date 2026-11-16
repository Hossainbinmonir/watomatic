package com.parishod.watomatic.model.logs

import android.app.Notification
import android.content.Context
import android.os.Looper
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.parishod.watomatic.activity.history.ReplyHistoryActivity
import com.parishod.watomatic.model.utils.DbUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReplyHistoryTest {

    private lateinit var context: Context
    private lateinit var db: MessageLogsDB
    private lateinit var dbUtils: DbUtils
    private lateinit var repository: ReplyHistoryRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetDbSingleton()
        context.deleteDatabase("logs_messages_db")
        db = MessageLogsDB.getInstance(context)
        dbUtils = DbUtils(context)
        repository = ReplyHistoryRepository(db.logsDao())
    }

    @After
    fun tearDown() {
        resetDbSingleton()
        context.deleteDatabase("logs_messages_db")
    }

    private fun resetDbSingleton() {
        val field = MessageLogsDB::class.java.getDeclaredField("_instance")
        field.isAccessible = true
        val existing = field.get(null) as? MessageLogsDB
        existing?.close()
        field.set(null, null)
    }

    private fun buildSbn(
        packageName: String = "com.whatsapp",
        sender: String = "Rahim",
        message: String = "Price please?"
    ): StatusBarNotification {
        val notification = NotificationCompat.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(sender)
            .setContentText(message)
            .setWhen(System.currentTimeMillis())
            .build()

        val sbn = mock<StatusBarNotification>()
        whenever(sbn.packageName).thenReturn(packageName)
        whenever(sbn.notification).thenReturn(notification)
        return sbn
    }

    @Test
    fun `grouped reply history aggregates exact reply count per incoming message and reply`() = runBlocking {
        dbUtils.logReply(buildSbn(sender = "Rahim", message = "Price?"), "Rahim", "Price?", "Starts at $10", "Price Rule")
        dbUtils.logReply(buildSbn(sender = "Karim", message = "Price?"), "Karim", "Price?", "Starts at $10", "Price Rule")
        dbUtils.logReply(buildSbn(sender = "Salam", message = "Price?"), "Salam", "Price?", "Starts at $10", "Price Rule")
        dbUtils.logReply(buildSbn(sender = "Nadia", message = "Location?"), "Nadia", "Location?", "Dhaka", "Address Rule")

        val grouped = repository.groupedReplyHistory.first()
        assertEquals(2, grouped.size)
        assertEquals(4, repository.totalReplyCount.first())
        assertEquals(2, dbUtils.uniqueReplyPairCount)

        val priceEntry = grouped.first { it.incomingMessage == "Price?" }
        assertEquals(3, priceEntry.replyCount)
        assertEquals("Starts at $10", priceEntry.repliedMessage)
        assertEquals("Price Rule", priceEntry.matchedRuleName)

        val locationEntry = grouped.first { it.incomingMessage == "Location?" }
        assertEquals(1, locationEntry.replyCount)
        assertEquals("Dhaka", locationEntry.repliedMessage)
    }

    @Test
    fun `timeline reply history stores individual messages and supports single delete and clear all`() = runBlocking {
        dbUtils.logReply(buildSbn(sender = "Alice", message = "Hi"), "Alice", "Hi", "Hello!", "Greeting")
        dbUtils.logReply(buildSbn(sender = "Bob", message = "Busy?"), "Bob", "Busy?", "Yes, later.", "Default Reply")

        var timeline = repository.allReplyHistory.first()
        assertEquals(2, timeline.size)

        val aliceEntry = timeline.first { it.senderName == "Alice" }
        assertEquals("Hi", aliceEntry.incomingMessage)
        assertEquals("Hello!", aliceEntry.repliedMessage)
        assertEquals("Greeting", aliceEntry.matchedRuleName)

        repository.deleteReplyById(aliceEntry.id)
        timeline = repository.allReplyHistory.first()
        assertEquals(1, timeline.size)
        assertEquals("Bob", timeline.first().senderName)

        repository.clearAllHistory()
        assertEquals(0, repository.totalReplyCount.first())
        assertTrue(repository.allReplyHistory.first().isEmpty())
    }

    @Test
    fun `ReplyHistoryActivity inflates cleanly`() {
        val controller = Robolectric.buildActivity(ReplyHistoryActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(controller.get())
        assertTrue(!controller.get().isFinishing)
    }
}
