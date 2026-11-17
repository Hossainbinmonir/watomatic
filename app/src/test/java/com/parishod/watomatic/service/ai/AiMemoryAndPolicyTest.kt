package com.parishod.watomatic.service.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.parishod.watomatic.model.ai.AiConversationMessage
import com.parishod.watomatic.model.ai.AiMemoryDatabase
import com.parishod.watomatic.model.ai.AiNote
import com.parishod.watomatic.model.ai.AiReplyLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AiMemoryAndPolicyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AiMemoryDatabase.resetInstance()
        context.deleteDatabase(AiMemoryDatabase.DB_NAME)
    }

    @After
    fun tearDown() {
        AiMemoryDatabase.resetInstance()
        context.deleteDatabase(AiMemoryDatabase.DB_NAME)
    }

    @Test
    fun `parseAndStripNotes handles normal reply, single tag, multiple tags, and tag-only reply`() {
        val plain = AiPromptAndPolicyHelper.parseAndStripNotes("Hello! How can I help you?")
        assertEquals("Hello! How can I help you?", plain.cleanReply)
        assertTrue(plain.extractedNotes.isEmpty())

        val single = AiPromptAndPolicyHelper.parseAndStripNotes(
            "Sure, I will let him know to call you at 5 PM.\n[[SAVE_NOTE: Call Alice at 5 PM about invoice]]"
        )
        assertEquals("Sure, I will let him know to call you at 5 PM.", single.cleanReply)
        assertEquals(listOf("Call Alice at 5 PM about invoice"), single.extractedNotes)

        val multi = AiPromptAndPolicyHelper.parseAndStripNotes(
            "Got it, noted both items.\n[[save_note: Send brochure\nby tomorrow]]\n[[SAVE_NOTE: Schedule demo at 11 AM]]"
        )
        assertEquals("Got it, noted both items.", multi.cleanReply)
        assertEquals(listOf("Send brochure\nby tomorrow", "Schedule demo at 11 AM"), multi.extractedNotes)

        val tagOnly = AiPromptAndPolicyHelper.parseAndStripNotes("[[SAVE_NOTE: Remember to renew plan]]")
        assertEquals(AiPromptAndPolicyHelper.DEFAULT_NOTE_CONFIRMATION, tagOnly.cleanReply)
        assertEquals(listOf("Remember to renew plan"), tagOnly.extractedNotes)
    }

    @Test
    fun `shouldAllowAiReply enforces cooldown, frequency limits, and group modes`() {
        val now = 10_000_000L

        // Group filter modes
        assertEquals(
            AiPolicyDecision.BLOCKED_GROUP_FILTER,
            AiPromptAndPolicyHelper.shouldAllowAiReply(
                cooldownSeconds = 0,
                maxRepliesPerSender = 0,
                groupFilterMode = "DIRECT_ONLY",
                isGroup = true,
                lastReplyTimeMillis = null,
                recentReplyCount = 0,
                nowMillis = now
            )
        )
        assertEquals(
            AiPolicyDecision.BLOCKED_GROUP_FILTER,
            AiPromptAndPolicyHelper.shouldAllowAiReply(
                cooldownSeconds = 0,
                maxRepliesPerSender = 0,
                groupFilterMode = "GROUP_ONLY",
                isGroup = false,
                lastReplyTimeMillis = null,
                recentReplyCount = 0,
                nowMillis = now
            )
        )
        assertEquals(
            AiPolicyDecision.ALLOWED,
            AiPromptAndPolicyHelper.shouldAllowAiReply(
                cooldownSeconds = 0,
                maxRepliesPerSender = 0,
                groupFilterMode = "BOTH",
                isGroup = true,
                lastReplyTimeMillis = null,
                recentReplyCount = 0,
                nowMillis = now
            )
        )

        // Cooldown enforcement
        assertEquals(
            AiPolicyDecision.BLOCKED_COOLDOWN,
            AiPromptAndPolicyHelper.shouldAllowAiReply(
                cooldownSeconds = 60,
                maxRepliesPerSender = 0,
                groupFilterMode = "DIRECT_ONLY",
                isGroup = false,
                lastReplyTimeMillis = now - 30_000L,
                recentReplyCount = 1,
                nowMillis = now
            )
        )
        assertEquals(
            AiPolicyDecision.ALLOWED,
            AiPromptAndPolicyHelper.shouldAllowAiReply(
                cooldownSeconds = 60,
                maxRepliesPerSender = 0,
                groupFilterMode = "DIRECT_ONLY",
                isGroup = false,
                lastReplyTimeMillis = now - 61_000L,
                recentReplyCount = 1,
                nowMillis = now
            )
        )

        // Frequency limit enforcement
        assertEquals(
            AiPolicyDecision.BLOCKED_FREQUENCY_LIMIT,
            AiPromptAndPolicyHelper.shouldAllowAiReply(
                cooldownSeconds = 0,
                maxRepliesPerSender = 3,
                groupFilterMode = "DIRECT_ONLY",
                isGroup = false,
                lastReplyTimeMillis = now - 120_000L,
                recentReplyCount = 3,
                nowMillis = now
            )
        )
        assertEquals(
            0L,
            AiPromptAndPolicyHelper.computeWindowSinceMillis(0, now)
        )
        assertEquals(
            now - 60 * 60_000L,
            AiPromptAndPolicyHelper.computeWindowSinceMillis(60, now)
        )
    }

    @Test
    fun `payload builders normalize alternating turns and drop leading assistant message`() {
        val history = listOf(
            AiConversationMessage(packageName = "com.whatsapp", senderKey = "alice", senderDisplayName = "Alice", role = "assistant", content = "Orphan greeting", timestamp = 1L),
            AiConversationMessage(packageName = "com.whatsapp", senderKey = "alice", senderDisplayName = "Alice", role = "user", content = "Hi", timestamp = 2L),
            AiConversationMessage(packageName = "com.whatsapp", senderKey = "alice", senderDisplayName = "Alice", role = "user", content = "Are you there?", timestamp = 3L),
            AiConversationMessage(packageName = "com.whatsapp", senderKey = "alice", senderDisplayName = "Alice", role = "assistant", content = "Yes, how can I help?", timestamp = 4L)
        )

        val openAiMessages = AiPromptAndPolicyHelper.buildOpenAiMessages(
            systemPrompt = "System",
            historyAsc = history,
            currentIncomingMessage = "What is the price?"
        )
        assertEquals(6, openAiMessages.size)
        assertEquals("system", openAiMessages[0].role)
        assertEquals("user", openAiMessages[5].role)
        assertEquals("What is the price?", openAiMessages[5].content)

        val claudeMessages = AiPromptAndPolicyHelper.buildClaudeMessagesJson(history, "What is the price?")
        assertEquals(3, claudeMessages.size())
        assertEquals("user", claudeMessages[0].asJsonObject.get("role").asString)
        assertEquals("Hi\nAre you there?", claudeMessages[0].asJsonObject.get("content").asString)
        assertEquals("assistant", claudeMessages[1].asJsonObject.get("role").asString)
        assertEquals("user", claudeMessages[2].asJsonObject.get("role").asString)
        assertEquals("What is the price?", claudeMessages[2].asJsonObject.get("content").asString)

        val geminiContents = AiPromptAndPolicyHelper.buildGeminiContentsJson(history, "What is the price?")
        assertEquals(3, geminiContents.size())
        assertEquals("user", geminiContents[0].asJsonObject.get("role").asString)
        assertEquals("model", geminiContents[1].asJsonObject.get("role").asString)
        assertEquals("user", geminiContents[2].asJsonObject.get("role").asString)
    }

    @Test
    fun `AiMemoryDatabase persists conversation history trimming notes and reply logs`() = runBlocking {
        val db = AiMemoryDatabase.getInstance(context)
        val dao = db.aiMemoryDao()

        withContext(Dispatchers.IO) {
            for (i in 1..6) {
                dao.insertConversationMessageSync(
                    AiConversationMessage(
                        packageName = "com.whatsapp",
                        senderKey = "alice",
                        senderDisplayName = "Alice",
                        role = if (i % 2 == 1) "user" else "assistant",
                        content = "Message $i",
                        timestamp = i * 1000L
                    )
                )
            }
            dao.insertConversationMessageSync(
                AiConversationMessage(
                    packageName = "com.whatsapp",
                    senderKey = "bob",
                    senderDisplayName = "Bob",
                    role = "user",
                    content = "Bob msg",
                    timestamp = 9000L
                )
            )

            dao.trimConversationMessagesSync("com.whatsapp", "alice", 4)
            val aliceRecentAsc = dao.getRecentMessagesDescSync("com.whatsapp", "alice", 10).asReversed()
            assertEquals(4, aliceRecentAsc.size)
            assertEquals("Message 3", aliceRecentAsc.first().content)
            assertEquals("Message 6", aliceRecentAsc.last().content)
            assertEquals(1, dao.getRecentMessagesDescSync("com.whatsapp", "bob", 10).size)

            val noteId = dao.insertNoteSync(
                AiNote(
                    senderName = "Alice",
                    packageName = "com.whatsapp",
                    noteText = "Call Alice at 5 PM",
                    sourceMessage = "Please remind owner to call me at 5 PM",
                    createdAt = 100L,
                    isCompleted = false
                )
            )
            assertEquals(1, dao.getPendingNoteCountSync())
            assertEquals(1, dao.getPendingNotesForSenderSync("com.whatsapp", " alice ", 5).size)

            dao.setNoteCompleted(noteId, true)
            assertEquals(0, dao.getPendingNoteCountSync())
            assertTrue(dao.observeAllNotes().first().first().isCompleted)

            assertNull(dao.getLastAiReplyTimeSync("com.whatsapp", "alice"))
            dao.insertReplyLogSync(AiReplyLog(packageName = "com.whatsapp", senderKey = "alice", repliedAt = 1000L))
            dao.insertReplyLogSync(AiReplyLog(packageName = "com.whatsapp", senderKey = "alice", repliedAt = 5000L))
            assertEquals(5000L, dao.getLastAiReplyTimeSync("com.whatsapp", "alice"))
            assertEquals(1, dao.getAiReplyCountSinceSync("com.whatsapp", "alice", 3000L))
            assertEquals(2, dao.getAiReplyCountSinceSync("com.whatsapp", "alice", 0L))
        }
    }
}
