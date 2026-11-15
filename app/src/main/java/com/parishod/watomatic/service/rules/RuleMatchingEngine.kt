package com.parishod.watomatic.service.rules

import com.parishod.watomatic.model.rules.MatchType
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

object RuleMatchingEngine {
    private const val MAX_REGEX_LENGTH = 500

    fun matches(
        incomingMessage: String?,
        matchType: MatchType,
        keywords: List<String>,
        caseSensitive: Boolean
    ): Boolean {
        val rawMessage = incomingMessage ?: return false
        if (rawMessage.isBlank()) return false

        val cleanKeywords = keywords
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (cleanKeywords.isEmpty()) {
            return false
        }

        return when (matchType) {
            MatchType.EXACT -> {
                val trimmedMsg = rawMessage.trim()
                cleanKeywords.any { kw ->
                    trimmedMsg.equals(kw, ignoreCase = !caseSensitive)
                }
            }

            MatchType.CONTAINS -> {
                cleanKeywords.any { kw ->
                    rawMessage.contains(kw, ignoreCase = !caseSensitive)
                }
            }

            MatchType.STARTS_WITH -> {
                val trimmedStart = rawMessage.trimStart()
                cleanKeywords.any { kw ->
                    trimmedStart.startsWith(kw, ignoreCase = !caseSensitive)
                }
            }

            MatchType.ENDS_WITH -> {
                val trimmedEnd = rawMessage.trimEnd()
                cleanKeywords.any { kw ->
                    trimmedEnd.endsWith(kw, ignoreCase = !caseSensitive)
                }
            }

            MatchType.DOES_NOT_CONTAIN -> {
                cleanKeywords.none { kw ->
                    containsKeywordTokenAware(rawMessage, kw, caseSensitive)
                }
            }

            MatchType.ANY_KEYWORD -> {
                cleanKeywords.any { kw ->
                    containsKeywordTokenAware(rawMessage, kw, caseSensitive)
                }
            }

            MatchType.ALL_KEYWORDS -> {
                cleanKeywords.all { kw ->
                    containsKeywordTokenAware(rawMessage, kw, caseSensitive)
                }
            }

            MatchType.REGEX -> {
                cleanKeywords.any { patternText ->
                    matchesRegexSafely(rawMessage, patternText, caseSensitive)
                }
            }
        }
    }

    /**
     * Uses word-boundary matching when a keyword starts/ends with alphanumeric characters
     * to avoid false positives (e.g., "hi" matching "this"), while still supporting
     * punctuation/emoji keywords via substring matching.
     */
    internal fun containsKeywordTokenAware(
        message: String,
        keyword: String,
        caseSensitive: Boolean
    ): Boolean {
        if (keyword.isEmpty()) return false
        val firstChar = keyword.first()
        val lastChar = keyword.last()
        val startsWithWordChar = firstChar.isLetterOrDigit() || firstChar == '_'
        val endsWithWordChar = lastChar.isLetterOrDigit() || lastChar == '_'

        if (!startsWithWordChar && !endsWithWordChar) {
            return message.contains(keyword, ignoreCase = !caseSensitive)
        }

        val prefix = if (startsWithWordChar) "(?<!\\w)" else ""
        val suffix = if (endsWithWordChar) "(?!\\w)" else ""
        val regexStr = prefix + Pattern.quote(keyword) + suffix
        val flags = if (caseSensitive) 0 else (Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        return try {
            Pattern.compile(regexStr, flags).matcher(message).find()
        } catch (_: Exception) {
            message.contains(keyword, ignoreCase = !caseSensitive)
        }
    }

    fun matchesRegexSafely(
        message: String,
        patternText: String,
        caseSensitive: Boolean
    ): Boolean {
        if (patternText.isBlank() || patternText.length > MAX_REGEX_LENGTH) {
            return false
        }
        return try {
            val flags = if (caseSensitive) 0 else (Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            val pattern = Pattern.compile(patternText, flags)
            pattern.matcher(message).find()
        } catch (_: PatternSyntaxException) {
            false
        } catch (_: StackOverflowError) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun isValidRegex(patternText: String): Boolean {
        if (patternText.isBlank() || patternText.length > MAX_REGEX_LENGTH) {
            return false
        }
        return try {
            Pattern.compile(patternText)
            true
        } catch (_: PatternSyntaxException) {
            false
        }
    }
}
