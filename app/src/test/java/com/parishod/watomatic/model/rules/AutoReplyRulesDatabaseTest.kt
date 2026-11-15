package com.parishod.watomatic.model.rules

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.parishod.watomatic.service.rules.RoomRuleExecutionLogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutoReplyRulesDatabaseTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AutoReplyRulesDatabase.resetInstance()
        context.deleteDatabase(AutoReplyRulesDatabase.DB_NAME)
    }

    @After
    fun tearDown() {
        AutoReplyRulesDatabase.resetInstance()
        context.deleteDatabase(AutoReplyRulesDatabase.DB_NAME)
    }

    @Test
    fun `database persists rules across simulated app restart`() = runBlocking {
        val dbBeforeRestart = AutoReplyRulesDatabase.getInstance(context)
        val repoBeforeRestart = RuleRepository(dbBeforeRestart.ruleDao(), dbBeforeRestart.executionLogDao())

        val rule = AutoReplyRule(
            name = "Support Hours",
            isEnabled = true,
            selectedPackages = listOf("com.whatsapp", "org.telegram.messenger"),
            matchType = MatchType.ANY_KEYWORD,
            keywords = listOf("support", "help"),
            isCaseSensitive = false,
            replyMessage = "We will reply during business hours.",
            priority = 75,
            replyDelaySeconds = 2,
            cooldownSeconds = 120,
            maxRepliesPerSender = 3,
            frequencyWindowMinutes = 60,
            contactFilterMode = ContactFilterMode.INCLUDE_ONLY,
            filterContacts = listOf("Alice", "Bob"),
            groupFilterMode = GroupFilterMode.DIRECT_ONLY,
            scheduleEnabled = true,
            startTimeMinutes = 9 * 60,
            endTimeMinutes = 18 * 60,
            activeDays = setOf(1, 2, 3, 4, 5)
        )

        val insertedId = withContext(Dispatchers.IO) {
            val id = repoBeforeRestart.insertRule(rule)
            val logStoreBeforeRestart = RoomRuleExecutionLogStore(dbBeforeRestart.executionLogDao())
            logStoreBeforeRestart.recordExecution(
                ruleId = id,
                packageName = "com.whatsapp",
                senderName = "Alice",
                notificationKey = "notif-abc",
                messageTimestamp = 12345L,
                repliedAt = 99999L
            )
            id
        }
        assertTrue(insertedId > 0L)

        // Simulate closing the app process and relaunching Room singleton
        AutoReplyRulesDatabase.resetInstance()

        val dbAfterRestart = AutoReplyRulesDatabase.getInstance(context)
        val repoAfterRestart = RuleRepository(dbAfterRestart.ruleDao(), dbAfterRestart.executionLogDao())

        val loaded = withContext(Dispatchers.IO) {
            repoAfterRestart.getRuleById(insertedId)
        }
        assertNotNull(loaded)
        assertEquals("Support Hours", loaded!!.name)
        assertTrue(loaded.isEnabled)
        assertEquals(listOf("com.whatsapp", "org.telegram.messenger"), loaded.selectedPackages)
        assertEquals(MatchType.ANY_KEYWORD, loaded.matchType)
        assertEquals(listOf("support", "help"), loaded.keywords)
        assertEquals(75, loaded.priority)
        assertEquals(120, loaded.cooldownSeconds)
        assertEquals(3, loaded.maxRepliesPerSender)
        assertEquals(ContactFilterMode.INCLUDE_ONLY, loaded.contactFilterMode)
        assertEquals(listOf("Alice", "Bob"), loaded.filterContacts)
        assertEquals(GroupFilterMode.DIRECT_ONLY, loaded.groupFilterMode)
        assertTrue(loaded.scheduleEnabled)
        assertEquals(setOf(1, 2, 3, 4, 5), loaded.activeDays)

        withContext(Dispatchers.IO) {
            val logStoreAfterRestart = RoomRuleExecutionLogStore(dbAfterRestart.executionLogDao())
            assertEquals(99999L, logStoreAfterRestart.getLastReplyTimeForRuleAndSender(insertedId, "com.whatsapp", "Alice"))
            assertTrue(logStoreAfterRestart.hasRepliedToNotification("notif-abc", 12345L))
        }
    }

    @Test
    fun `crud duplicate priority and cascade delete work properly`() = runBlocking {
        val db = AutoReplyRulesDatabase.getInstance(context)
        val repo = RuleRepository(db.ruleDao(), db.executionLogDao())

        withContext(Dispatchers.IO) {
            val id1 = repo.insertRule(
                AutoReplyRule(
                    name = "Pricing",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("price"),
                    replyMessage = "Check our website.",
                    priority = 20
                )
            )

            val copyId = repo.duplicateRule(repo.getRuleById(id1)!!)
            val copied = repo.getRuleById(copyId)
            assertNotNull(copied)
            assertEquals("Pricing (Copy)", copied!!.name)
            assertTrue(copied.isEnabled)

            repo.setRuleEnabled(copyId, true)
            repo.updateRulePriority(copyId, 95)
            val enabledList = db.ruleDao().getEnabledRulesSync()
            assertEquals(2, enabledList.size)
            assertEquals(copyId, enabledList.first().id)

            RoomRuleExecutionLogStore(db.executionLogDao()).recordExecution(
                ruleId = id1,
                packageName = "com.whatsapp",
                senderName = "Bob",
                notificationKey = "n1",
                messageTimestamp = 100L,
                repliedAt = 200L
            )

            repo.deleteRule(repo.getRuleById(id1)!!)
            assertNull(repo.getRuleById(id1))
            assertNull(db.executionLogDao().getLastReplyTimeForRuleAndSender(id1, "com.whatsapp", "Bob"))
        }
    }
}
