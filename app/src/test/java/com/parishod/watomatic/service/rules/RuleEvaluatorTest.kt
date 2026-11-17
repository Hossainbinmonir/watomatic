package com.parishod.watomatic.service.rules

import com.parishod.watomatic.model.rules.AutoReplyRule
import com.parishod.watomatic.model.rules.ContactFilterMode
import com.parishod.watomatic.model.rules.GroupFilterMode
import com.parishod.watomatic.model.rules.MatchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RuleEvaluatorTest {

    private class FakeExecutionLogStore : RuleExecutionLogStore {
        private data class Key(val ruleId: Long, val pkg: String, val sender: String)
        private val replyTimestamps = mutableMapOf<Key, MutableList<Long>>()
        private val repliedNotifications = mutableSetOf<Pair<String, Long>>()

        override fun getLastReplyTimeForRuleAndSender(
            ruleId: Long,
            packageName: String,
            senderName: String
        ): Long? {
            return replyTimestamps[Key(ruleId, packageName, senderName)]?.maxOrNull()
        }

        override fun getReplyCountForRuleAndSenderSince(
            ruleId: Long,
            packageName: String,
            senderName: String,
            sinceTime: Long
        ): Int {
            return replyTimestamps[Key(ruleId, packageName, senderName)]
                ?.count { it >= sinceTime }
                ?: 0
        }

        override fun hasRepliedToNotification(notificationKey: String, messageTimestamp: Long): Boolean {
            return (notificationKey to messageTimestamp) in repliedNotifications
        }

        override fun recordExecution(
            ruleId: Long,
            packageName: String,
            senderName: String,
            notificationKey: String,
            messageTimestamp: Long,
            repliedAt: Long
        ) {
            replyTimestamps.getOrPut(Key(ruleId, packageName, senderName)) { mutableListOf() }.add(repliedAt)
            if (notificationKey.isNotBlank() && messageTimestamp > 0L) {
                repliedNotifications.add(notificationKey to messageTimestamp)
            }
        }
    }

    private lateinit var logStore: FakeExecutionLogStore
    private lateinit var evaluator: RuleEvaluator
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    @Before
    fun setUp() {
        logStore = FakeExecutionLogStore()
        evaluator = RuleEvaluator(logStore, utc)
    }

    private fun baseRule(
        id: Long = 1L,
        name: String = "Rule",
        enabled: Boolean = true,
        packages: List<String> = listOf("com.whatsapp"),
        matchType: MatchType = MatchType.CONTAINS,
        keywords: List<String> = listOf("hello"),
        reply: String = "Auto reply",
        useAiReply: Boolean = false,
        priority: Int = 10,
        cooldownSeconds: Int = 0,
        maxRepliesPerSender: Int = 0,
        frequencyWindowMinutes: Int = 1440,
        contactFilterMode: ContactFilterMode = ContactFilterMode.ALL,
        filterContacts: List<String> = emptyList(),
        groupFilterMode: GroupFilterMode = GroupFilterMode.BOTH,
        scheduleEnabled: Boolean = false,
        startTimeMinutes: Int = 0,
        endTimeMinutes: Int = 1439,
        activeDays: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7)
    ) = AutoReplyRule(
        id = id,
        name = name,
        isEnabled = enabled,
        selectedPackages = packages,
        matchType = matchType,
        keywords = keywords,
        isCaseSensitive = false,
        replyMessage = reply,
        useAiReply = useAiReply,
        priority = priority,
        replyDelaySeconds = 0,
        cooldownSeconds = cooldownSeconds,
        maxRepliesPerSender = maxRepliesPerSender,
        frequencyWindowMinutes = frequencyWindowMinutes,
        contactFilterMode = contactFilterMode,
        filterContacts = filterContacts,
        groupFilterMode = groupFilterMode,
        scheduleEnabled = scheduleEnabled,
        startTimeMinutes = startTimeMinutes,
        endTimeMinutes = endTimeMinutes,
        activeDays = activeDays
    )

    private fun context(
        packageName: String = "com.whatsapp",
        senderName: String? = "Alice",
        messageText: String? = "Hello there",
        isGroup: Boolean = false,
        notificationKey: String = "key-1",
        messageTimestamp: Long = 1000L,
        selfDisplayName: String? = "Me"
    ) = IncomingMessageContext(
        packageName = packageName,
        senderName = senderName,
        messageText = messageText,
        isGroup = isGroup,
        notificationKey = notificationKey,
        messageTimestamp = messageTimestamp,
        selfDisplayName = selfDisplayName
    )

    @Test
    fun `one active rule returns expected reply`() {
        val rule = baseRule(id = 1L, keywords = listOf("price"), reply = "Price is $20")
        val result = evaluator.evaluate(
            rules = listOf(rule),
            context = context(messageText = "What is the price?")
        )
        assertNotNull(result)
        assertEquals(1L, result?.rule?.id)
        assertEquals("Price is $20", result?.replyText)
    }

    @Test
    fun `multiple active rules selects the matching rule`() {
        val r1 = baseRule(id = 1L, keywords = listOf("hours"), reply = "Open 9-5")
        val r2 = baseRule(id = 2L, keywords = listOf("location"), reply = "123 Main St")
        val result = evaluator.evaluate(
            rules = listOf(r1, r2),
            context = context(messageText = "Where is your location?")
        )
        assertNotNull(result)
        assertEquals(2L, result?.rule?.id)
        assertEquals("123 Main St", result?.replyText)
    }

    @Test
    fun `disabled rule is never triggered`() {
        val disabledRule = baseRule(id = 1L, enabled = false, keywords = listOf("hello"))
        val result = evaluator.evaluate(
            rules = listOf(disabledRule),
            context = context(messageText = "hello")
        )
        assertNull(result)
    }

    @Test
    fun `multiple matching rules select highest priority rule`() {
        val lowPriority = baseRule(
            id = 1L,
            name = "Generic Greeting",
            matchType = MatchType.CONTAINS,
            keywords = listOf("help"),
            reply = "General support",
            priority = 5
        )
        val highPriority = baseRule(
            id = 2L,
            name = "Urgent VIP",
            matchType = MatchType.CONTAINS,
            keywords = listOf("help"),
            reply = "Priority VIP support",
            priority = 90
        )

        val result = evaluator.evaluate(
            rules = listOf(lowPriority, highPriority),
            context = context(messageText = "I need help now")
        )
        assertNotNull(result)
        assertEquals(2L, result?.rule?.id)
        assertEquals("Priority VIP support", result?.replyText)
    }

    @Test
    fun `cooldown restriction blocks repeat replies until duration elapses`() {
        val rule = baseRule(id = 10L, cooldownSeconds = 60, keywords = listOf("ping"), reply = "pong")
        val t0 = 1_000_000L

        val first = evaluator.evaluate(listOf(rule), context(messageText = "ping", notificationKey = "k1", messageTimestamp = t0), t0)
        assertNotNull(first)
        logStore.recordExecution(10L, "com.whatsapp", "Alice", "k1", t0, t0)

        // 30 seconds later: blocked by 60s cooldown
        val blocked = evaluator.evaluate(listOf(rule), context(messageText = "ping", notificationKey = "k2", messageTimestamp = t0 + 30_000L), t0 + 30_000L)
        assertNull(blocked)

        // Different sender is not blocked
        val otherSender = evaluator.evaluate(listOf(rule), context(senderName = "Bob", messageText = "ping", notificationKey = "k3", messageTimestamp = t0 + 30_000L), t0 + 30_000L)
        assertNotNull(otherSender)

        // 61 seconds later for Alice: allowed again
        val allowed = evaluator.evaluate(listOf(rule), context(messageText = "ping", notificationKey = "k4", messageTimestamp = t0 + 61_000L), t0 + 61_000L)
        assertNotNull(allowed)
    }

    @Test
    fun `frequency limit caps total replies per sender in window`() {
        val rule = baseRule(
            id = 11L,
            cooldownSeconds = 0,
            maxRepliesPerSender = 2,
            frequencyWindowMinutes = 60,
            keywords = listOf("hi")
        )
        val t0 = 5_000_000L
        logStore.recordExecution(11L, "com.whatsapp", "Alice", "k1", t0, t0)
        logStore.recordExecution(11L, "com.whatsapp", "Alice", "k2", t0 + 1000, t0 + 1000)

        // 3rd attempt within 60 mins -> blocked
        val third = evaluator.evaluate(listOf(rule), context(messageText = "hi", notificationKey = "k3", messageTimestamp = t0 + 5000), t0 + 5000)
        assertNull(third)

        // After 61 minutes -> allowed again
        val afterWindow = evaluator.evaluate(listOf(rule), context(messageText = "hi", notificationKey = "k4", messageTimestamp = t0 + 61 * 60_000L), t0 + 61 * 60_000L)
        assertNotNull(afterWindow)
    }

    @Test
    fun `schedule restriction skips rule outside active window and runs inside window`() {
        // Monday 2026-06-15
        val insideTime = Calendar.getInstance(utc).apply {
            set(2026, Calendar.JUNE, 15, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val outsideTime = Calendar.getInstance(utc).apply {
            set(2026, Calendar.JUNE, 15, 20, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val rule = baseRule(
            id = 5L,
            scheduleEnabled = true,
            startTimeMinutes = 9 * 60,
            endTimeMinutes = 17 * 60,
            activeDays = setOf(1)
        )

        assertNotNull(evaluator.evaluate(listOf(rule), context(), insideTime))
        assertNull(evaluator.evaluate(listOf(rule), context(), outsideTime))
    }

    @Test
    fun `different source applications match only configured packages`() {
        val whatsappOnly = baseRule(id = 1L, packages = listOf("com.whatsapp"), reply = "WA reply")
        val telegramOnly = baseRule(id = 2L, packages = listOf("org.telegram.messenger"), reply = "TG reply")

        val waResult = evaluator.evaluate(
            listOf(whatsappOnly, telegramOnly),
            context(packageName = "com.whatsapp", messageText = "hello")
        )
        assertEquals("WA reply", waResult?.replyText)

        val tgResult = evaluator.evaluate(
            listOf(whatsappOnly, telegramOnly),
            context(packageName = "org.telegram.messenger", messageText = "hello")
        )
        assertEquals("TG reply", tgResult?.replyText)

        val linkedInResult = evaluator.evaluate(
            listOf(whatsappOnly, telegramOnly),
            context(packageName = "com.linkedin.android", messageText = "hello")
        )
        assertNull(linkedInResult)
    }

    @Test
    fun `contact and group filters enforce include exclude and direct vs group`() {
        val includeVip = baseRule(
            id = 1L,
            contactFilterMode = ContactFilterMode.INCLUDE_ONLY,
            filterContacts = listOf("Alice"),
            groupFilterMode = GroupFilterMode.DIRECT_ONLY,
            reply = "VIP"
        )

        assertNotNull(evaluator.evaluate(listOf(includeVip), context(senderName = "Alice", isGroup = false)))
        assertNull(evaluator.evaluate(listOf(includeVip), context(senderName = "Bob", isGroup = false)))
        assertNull(evaluator.evaluate(listOf(includeVip), context(senderName = "Alice", isGroup = true)))

        val excludeBlocked = baseRule(
            id = 2L,
            contactFilterMode = ContactFilterMode.EXCLUDE,
            filterContacts = listOf("SpamBot"),
            groupFilterMode = GroupFilterMode.GROUP_ONLY,
            reply = "Group OK"
        )
        assertNotNull(evaluator.evaluate(listOf(excludeBlocked), context(senderName = "Team", isGroup = true)))
        assertNull(evaluator.evaluate(listOf(excludeBlocked), context(senderName = "SpamBot", isGroup = true)))
        assertNull(evaluator.evaluate(listOf(excludeBlocked), context(senderName = "Alice", isGroup = false)))
    }

    @Test
    fun `self message and already replied notification are rejected`() {
        val rule = baseRule(id = 1L)
        assertNull(
            evaluator.evaluate(
                listOf(rule),
                context(senderName = "Me", selfDisplayName = "Me", messageText = "hello")
            )
        )

        logStore.recordExecution(1L, "com.whatsapp", "Alice", "notif-99", 5555L, 5555L)
        assertNull(
            evaluator.evaluate(
                listOf(rule),
                context(notificationKey = "notif-99", messageTimestamp = 5555L, messageText = "hello")
            )
        )
    }

    @Test
    fun `ai powered rule matches even when static replyMessage is blank`() {
        val aiRule = baseRule(
            id = 55L,
            keywords = listOf("booking"),
            reply = "",
            useAiReply = true
        )
        val staticBlankRule = baseRule(
            id = 56L,
            keywords = listOf("booking"),
            reply = "",
            useAiReply = false
        )

        assertNull(evaluator.evaluate(listOf(staticBlankRule), context(messageText = "I want a booking")))
        val matched = evaluator.evaluate(listOf(aiRule), context(messageText = "I want a booking"))
        assertNotNull(matched)
        assertEquals(55L, matched?.rule?.id)
        assertEquals(true, matched?.rule?.useAiReply)
    }
}
