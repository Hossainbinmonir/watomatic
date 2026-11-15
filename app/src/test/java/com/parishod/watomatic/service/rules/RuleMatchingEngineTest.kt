package com.parishod.watomatic.service.rules

import com.parishod.watomatic.model.rules.MatchType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleMatchingEngineTest {

    @Test
    fun `exact match matches trimmed text and respects case sensitivity`() {
        assertTrue(
            RuleMatchingEngine.matches(
                incomingMessage = "  Hello  ",
                matchType = MatchType.EXACT,
                keywords = listOf("hello"),
                caseSensitive = false
            )
        )
        assertFalse(
            RuleMatchingEngine.matches(
                incomingMessage = "Hello",
                matchType = MatchType.EXACT,
                keywords = listOf("hello"),
                caseSensitive = true
            )
        )
        assertFalse(
            RuleMatchingEngine.matches(
                incomingMessage = "Hello world",
                matchType = MatchType.EXACT,
                keywords = listOf("hello"),
                caseSensitive = false
            )
        )
    }

    @Test
    fun `contains match matches substring and respects case sensitivity`() {
        assertTrue(
            RuleMatchingEngine.matches(
                incomingMessage = "Can you send the PRICE list?",
                matchType = MatchType.CONTAINS,
                keywords = listOf("price"),
                caseSensitive = false
            )
        )
        assertFalse(
            RuleMatchingEngine.matches(
                incomingMessage = "Can you send the PRICE list?",
                matchType = MatchType.CONTAINS,
                keywords = listOf("price"),
                caseSensitive = true
            )
        )
    }

    @Test
    fun `starts with and ends with work accurately`() {
        assertTrue(
            RuleMatchingEngine.matches(
                incomingMessage = "  Urgent: please call back",
                matchType = MatchType.STARTS_WITH,
                keywords = listOf("urgent"),
                caseSensitive = false
            )
        )
        assertFalse(
            RuleMatchingEngine.matches(
                incomingMessage = "Not urgent right now",
                matchType = MatchType.STARTS_WITH,
                keywords = listOf("urgent"),
                caseSensitive = false
            )
        )
        assertTrue(
            RuleMatchingEngine.matches(
                incomingMessage = "Are you available today?",
                matchType = MatchType.ENDS_WITH,
                keywords = listOf("today?"),
                caseSensitive = false
            )
        )
    }

    @Test
    fun `does not contain prevents false positives on word boundaries`() {
        assertFalse(
            RuleMatchingEngine.matches(
                incomingMessage = "Please stop messaging me",
                matchType = MatchType.DOES_NOT_CONTAIN,
                keywords = listOf("stop"),
                caseSensitive = false
            )
        )
        // "hi" should not trigger as contained inside "this"
        assertTrue(
            RuleMatchingEngine.matches(
                incomingMessage = "This is an update",
                matchType = MatchType.DOES_NOT_CONTAIN,
                keywords = listOf("hi"),
                caseSensitive = false
            )
        )
    }

    @Test
    fun `any keyword avoids partial word false matches`() {
        assertTrue(
            RuleMatchingEngine.matches(
                incomingMessage = "Hi there, any update?",
                matchType = MatchType.ANY_KEYWORD,
                keywords = listOf("bye", "hi"),
                caseSensitive = false
            )
        )
        assertFalse(
            RuleMatchingEngine.matches(
                incomingMessage = "This shipment is ready",
                matchType = MatchType.ANY_KEYWORD,
                keywords = listOf("hi"),
                caseSensitive = false
            )
        )
    }

    @Test
    fun `all keywords requires every keyword to be present`() {
        assertTrue(
            RuleMatchingEngine.matches(
                incomingMessage = "Need order #42 invoice please",
                matchType = MatchType.ALL_KEYWORDS,
                keywords = listOf("order", "invoice"),
                caseSensitive = false
            )
        )
        assertFalse(
            RuleMatchingEngine.matches(
                incomingMessage = "Need order #42 status",
                matchType = MatchType.ALL_KEYWORDS,
                keywords = listOf("order", "invoice"),
                caseSensitive = false
            )
        )
    }

    @Test
    fun `regex matches valid patterns and rejects malformed patterns safely`() {
        assertTrue(
            RuleMatchingEngine.matches(
                incomingMessage = "Order ID: ORD-1234",
                matchType = MatchType.REGEX,
                keywords = listOf("ORD-\\d{4}"),
                caseSensitive = true
            )
        )
        assertFalse(
            RuleMatchingEngine.matches(
                incomingMessage = "Order ID: ord-1234",
                matchType = MatchType.REGEX,
                keywords = listOf("ORD-\\d{4}"),
                caseSensitive = true
            )
        )
        assertFalse(RuleMatchingEngine.isValidRegex("[unclosed"))
        assertFalse(
            RuleMatchingEngine.matches(
                incomingMessage = "Hello",
                matchType = MatchType.REGEX,
                keywords = listOf("[unclosed"),
                caseSensitive = false
            )
        )
    }

    @Test
    fun `blank message or empty keywords never match`() {
        assertFalse(RuleMatchingEngine.matches(null, MatchType.CONTAINS, listOf("hi"), false))
        assertFalse(RuleMatchingEngine.matches("   ", MatchType.CONTAINS, listOf("hi"), false))
        assertFalse(RuleMatchingEngine.matches("hi", MatchType.CONTAINS, emptyList(), false))
    }
}
