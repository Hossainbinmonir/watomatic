package com.parishod.watomatic.model.rules

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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

    @Test
    fun `category persistence and bulk category toggle work properly`() = runBlocking {
        val db = AutoReplyRulesDatabase.getInstance(context)
        val repo = RuleRepository(db.ruleDao(), db.executionLogDao())

        withContext(Dispatchers.IO) {
            val bizId1 = repo.insertRule(
                AutoReplyRule(
                    name = "Business Hours",
                    category = "Business",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("hours"),
                    replyMessage = "Open 9-5"
                )
            )
            val bizId2 = repo.insertRule(
                AutoReplyRule(
                    name = "Business Pricing",
                    category = "Business",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("price"),
                    replyMessage = "Starts at $10"
                )
            )
            val personalId = repo.insertRule(
                AutoReplyRule(
                    name = "Family",
                    category = "Personal",
                    isEnabled = true,
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("hi"),
                    replyMessage = "Call me!"
                )
            )

            assertEquals("Business", repo.getRuleById(bizId1)!!.category)

            repo.setCategoryEnabled("Business", false)
            assertEquals(false, repo.getRuleById(bizId1)!!.isEnabled)
            assertEquals(false, repo.getRuleById(bizId2)!!.isEnabled)
            assertEquals(true, repo.getRuleById(personalId)!!.isEnabled)

            repo.setCategoryEnabled("Business", true)
            assertEquals(true, repo.getRuleById(bizId1)!!.isEnabled)
            assertEquals(true, repo.getRuleById(bizId2)!!.isEnabled)
        }
    }

    @Test
    fun `migration 1 to 2 adds category column with empty default`() = runBlocking {
        val dbFile = context.getDatabasePath(AutoReplyRulesDatabase.DB_NAME)
        dbFile.parentFile?.mkdirs()

        // Create a v1 database manually without the category column
        val rawDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        rawDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auto_reply_rules (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                is_enabled INTEGER NOT NULL,
                selected_packages TEXT NOT NULL,
                match_type TEXT NOT NULL,
                keywords TEXT NOT NULL,
                is_case_sensitive INTEGER NOT NULL,
                reply_message TEXT NOT NULL,
                priority INTEGER NOT NULL,
                reply_delay_seconds INTEGER NOT NULL,
                cooldown_seconds INTEGER NOT NULL,
                max_replies_per_sender INTEGER NOT NULL,
                frequency_window_minutes INTEGER NOT NULL,
                contact_filter_mode TEXT NOT NULL,
                filter_contacts TEXT NOT NULL,
                group_filter_mode TEXT NOT NULL,
                schedule_enabled INTEGER NOT NULL,
                start_time_minutes INTEGER NOT NULL,
                end_time_minutes INTEGER NOT NULL,
                active_days TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        rawDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS rule_execution_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                rule_id INTEGER NOT NULL,
                package_name TEXT NOT NULL,
                sender_name TEXT NOT NULL,
                notification_key TEXT NOT NULL,
                message_timestamp INTEGER NOT NULL,
                replied_at INTEGER NOT NULL,
                FOREIGN KEY(rule_id) REFERENCES auto_reply_rules(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        rawDb.execSQL("CREATE INDEX IF NOT EXISTS index_rule_execution_logs_rule_id ON rule_execution_logs(rule_id)")
        rawDb.execSQL("CREATE INDEX IF NOT EXISTS index_rule_execution_logs_rule_id_package_name_sender_name_replied_at ON rule_execution_logs(rule_id, package_name, sender_name, replied_at)")
        rawDb.execSQL("CREATE INDEX IF NOT EXISTS index_rule_execution_logs_notification_key_message_timestamp ON rule_execution_logs(notification_key, message_timestamp)")
        rawDb.execSQL(
            """
            INSERT INTO auto_reply_rules (
                id, name, is_enabled, selected_packages, match_type, keywords,
                is_case_sensitive, reply_message, priority, reply_delay_seconds,
                cooldown_seconds, max_replies_per_sender, frequency_window_minutes,
                contact_filter_mode, filter_contacts, group_filter_mode,
                schedule_enabled, start_time_minutes, end_time_minutes, active_days,
                created_at, updated_at
            ) VALUES (
                1, 'Legacy Rule', 1, '["com.whatsapp"]', 'CONTAINS', '["hello"]',
                0, 'Hi there!', 10, 0, 0, 0, 0, 'ALL', '[]', 'ALL',
                0, 540, 1080, '1,2,3,4,5', 1000, 1000
            )
            """.trimIndent()
        )
        rawDb.version = 1
        rawDb.close()

        val migratedDb = AutoReplyRulesDatabase.getInstance(context)
        val repo = RuleRepository(migratedDb.ruleDao(), migratedDb.executionLogDao())

        withContext(Dispatchers.IO) {
            val legacyRule = repo.getRuleById(1L)
            assertNotNull(legacyRule)
            assertEquals("Legacy Rule", legacyRule!!.name)
            assertEquals("", legacyRule.category)

            val newId = repo.insertRule(
                AutoReplyRule(
                    name = "Categorized Rule",
                    category = "Support",
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("help"),
                    replyMessage = "Support will reply."
                )
            )
            assertEquals("Support", repo.getRuleById(newId)!!.category)
        }
    }

    @Test
    fun `migration 2 to 3 adds use_ai_reply column with default false and persists true`() = runBlocking {
        val dbFile = context.getDatabasePath(AutoReplyRulesDatabase.DB_NAME)
        dbFile.parentFile?.mkdirs()

        val rawDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        rawDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS auto_reply_rules (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT '',
                is_enabled INTEGER NOT NULL,
                selected_packages TEXT NOT NULL,
                match_type TEXT NOT NULL,
                keywords TEXT NOT NULL,
                is_case_sensitive INTEGER NOT NULL,
                reply_message TEXT NOT NULL,
                priority INTEGER NOT NULL,
                reply_delay_seconds INTEGER NOT NULL,
                cooldown_seconds INTEGER NOT NULL,
                max_replies_per_sender INTEGER NOT NULL,
                frequency_window_minutes INTEGER NOT NULL,
                contact_filter_mode TEXT NOT NULL,
                filter_contacts TEXT NOT NULL,
                group_filter_mode TEXT NOT NULL,
                schedule_enabled INTEGER NOT NULL,
                start_time_minutes INTEGER NOT NULL,
                end_time_minutes INTEGER NOT NULL,
                active_days TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        rawDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS rule_execution_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                rule_id INTEGER NOT NULL,
                package_name TEXT NOT NULL,
                sender_name TEXT NOT NULL,
                notification_key TEXT NOT NULL,
                message_timestamp INTEGER NOT NULL,
                replied_at INTEGER NOT NULL,
                FOREIGN KEY(rule_id) REFERENCES auto_reply_rules(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        rawDb.execSQL("CREATE INDEX IF NOT EXISTS index_rule_execution_logs_rule_id ON rule_execution_logs(rule_id)")
        rawDb.execSQL("CREATE INDEX IF NOT EXISTS index_rule_execution_logs_rule_id_package_name_sender_name_replied_at ON rule_execution_logs(rule_id, package_name, sender_name, replied_at)")
        rawDb.execSQL("CREATE INDEX IF NOT EXISTS index_rule_execution_logs_notification_key_message_timestamp ON rule_execution_logs(notification_key, message_timestamp)")
        rawDb.execSQL(
            """
            INSERT INTO auto_reply_rules (
                id, name, category, is_enabled, selected_packages, match_type, keywords,
                is_case_sensitive, reply_message, priority, reply_delay_seconds,
                cooldown_seconds, max_replies_per_sender, frequency_window_minutes,
                contact_filter_mode, filter_contacts, group_filter_mode,
                schedule_enabled, start_time_minutes, end_time_minutes, active_days,
                created_at, updated_at
            ) VALUES (
                1, 'V2 Rule', 'Support', 1, '["com.whatsapp"]', 'CONTAINS', '["hello"]',
                0, 'Hi there!', 10, 0, 0, 0, 0, 'ALL', '[]', 'ALL',
                0, 540, 1080, '1,2,3,4,5', 1000, 1000
            )
            """.trimIndent()
        )
        rawDb.version = 2
        rawDb.close()

        val migratedDb = AutoReplyRulesDatabase.getInstance(context)
        val repo = RuleRepository(migratedDb.ruleDao(), migratedDb.executionLogDao())

        withContext(Dispatchers.IO) {
            val v2Rule = repo.getRuleById(1L)
            assertNotNull(v2Rule)
            assertEquals(false, v2Rule!!.useAiReply)

            val aiRuleId = repo.insertRule(
                AutoReplyRule(
                    name = "AI Concierge",
                    selectedPackages = listOf("com.whatsapp"),
                    matchType = MatchType.CONTAINS,
                    keywords = listOf("order"),
                    replyMessage = "",
                    useAiReply = true
                )
            )
            val savedAiRule = repo.getRuleById(aiRuleId)
            assertNotNull(savedAiRule)
            assertEquals(true, savedAiRule!!.useAiReply)
        }
    }
}
