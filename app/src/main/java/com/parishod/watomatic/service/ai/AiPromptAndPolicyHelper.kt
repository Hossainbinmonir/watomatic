package com.parishod.watomatic.service.ai

import android.service.notification.StatusBarNotification
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.parishod.watomatic.model.ai.AiConversationMessage
import com.parishod.watomatic.model.ai.AiNote
import com.parishod.watomatic.network.model.openai.Message
import com.parishod.watomatic.service.reply.NotificationEventKeyBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ParsedAiResponse(
    val cleanReply: String,
    val extractedNotes: List<String>
)

enum class AiPolicyDecision {
    ALLOWED,
    BLOCKED_GROUP_FILTER,
    BLOCKED_COOLDOWN,
    BLOCKED_FREQUENCY_LIMIT
}

object AiPromptAndPolicyHelper {
    const val UNKNOWN_SENDER = "__unknown_sender__"
    const val DEFAULT_NOTE_CONFIRMATION = "Noted! I have saved this reminder."

    private val SAVE_NOTE_REGEX = Regex("(?is)\\[\\[\\s*SAVE_NOTE\\s*:\\s*(.*?)\\s*(?:\\]\\])+")
    private val UNCLOSED_SAVE_NOTE_REGEX = Regex("(?is)\\[\\[\\s*SAVE_NOTE\\s*:.*$")
    private val MALFORMED_SINGLE_BRACKET_SAVE_NOTE_REGEX = Regex("(?is)\\[\\s*SAVE_NOTE\\s*:[^\\]]*(?:\\]|$)")
    private val ANY_HIDDEN_MARKER_REGEX = Regex("(?is)\\[\\[.*?\\]\\]")
    private val UNCLOSED_HIDDEN_MARKER_REGEX = Regex("(?is)\\[\\[.*$")
    private val TRAILING_BLANK_LINES = Regex("\n{3,}")
    private val SENSITIVE_TOKEN_REGEX = Regex(
        "(?i)(Bearer\\s+[A-Za-z0-9._\\-]{8,}|\\bsk-[A-Za-z0-9_\\-]{10,}|\\bAIza[0-9A-Za-z_\\-]{10,})"
    )
    private val INTERNAL_ERROR_PREFIX_REGEX = Regex(
        "(?i)^(HTTP\\s+[1-5][0-9]{2}\\s*\\(|Network\\s+error\\s*\\(|Secure\\s+encrypted\\s+credential\\s+storage|IllegalStateException:|SecurityException:|RuntimeException:|Exception:|Stacktrace:).*"
    )

    @JvmStatic
    @JvmOverloads
    fun normalizeSenderKey(senderName: String?, isGroup: Boolean = false): String {
        val trimmed = senderName?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (trimmed.isEmpty()) return UNKNOWN_SENDER
        return if (isGroup) "group:$trimmed" else trimmed
    }

    @JvmStatic
    fun isUnknownSender(senderKey: String?): Boolean {
        return senderKey.isNullOrBlank() || senderKey == UNKNOWN_SENDER
    }

    @JvmStatic
    fun sanitizeExtractedNote(rawNote: String?): String? {
        if (rawNote.isNullOrBlank()) return null
        val cleaned = ANY_HIDDEN_MARKER_REGEX.replace(rawNote, "")
            .replace(UNCLOSED_HIDDEN_MARKER_REGEX, "")
            .replace("[[", "")
            .replace("]]", "")
            .replace(TRAILING_BLANK_LINES, "\n\n")
            .trim()
        if (cleaned.isEmpty()) return null
        if (SENSITIVE_TOKEN_REGEX.containsMatchIn(cleaned)) return null
        if (INTERNAL_ERROR_PREFIX_REGEX.matches(cleaned.replace("\n", " ").trim())) return null
        return cleaned
    }

    @JvmStatic
    fun computeNoteDedupKey(noteText: String): String {
        val normalized = noteText.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        return sha256Hex(normalized)
    }

    @JvmStatic
    @JvmOverloads
    fun buildIncomingEventKey(
        sbn: StatusBarNotification?,
        fallbackIncomingMessage: String? = null
    ): String? {
        return NotificationEventKeyBuilder.buildEventKey(sbn, fallbackIncomingMessage)
    }

    @JvmStatic
    fun buildLegacyIncomingEventKey(
        sbnKey: String?,
        postTimeMs: Long,
        whenMs: Long,
        packageName: String,
        senderKey: String,
        incomingMessage: String
    ): String {
        val raw = "${sbnKey.orEmpty()}|$postTimeMs|$whenMs|$packageName|$senderKey|${incomingMessage.trim()}"
        return "in_${sha256Hex(raw)}"
    }

    @JvmStatic
    fun buildIncomingEventKey(
        sbnKey: String?,
        postTimeMs: Long,
        whenMs: Long,
        packageName: String,
        senderKey: String,
        incomingMessage: String
    ): String {
        return buildLegacyIncomingEventKey(
            sbnKey = sbnKey,
            postTimeMs = postTimeMs,
            whenMs = whenMs,
            packageName = packageName,
            senderKey = senderKey,
            incomingMessage = incomingMessage
        )
    }

    private fun sha256Hex(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    @JvmStatic
    fun sanitizeOutboundReply(rawAiReply: String?): String {
        val raw = rawAiReply?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        return SAVE_NOTE_REGEX.replace(raw, "")
            .replace(UNCLOSED_SAVE_NOTE_REGEX, "")
            .replace(ANY_HIDDEN_MARKER_REGEX, "")
            .replace(UNCLOSED_HIDDEN_MARKER_REGEX, "")
            .replace(MALFORMED_SINGLE_BRACKET_SAVE_NOTE_REGEX, "")
            .replace(TRAILING_BLANK_LINES, "\n\n")
            .trim()
    }

    @JvmStatic
    @JvmOverloads
    fun parseAndStripNotes(rawAiReply: String?, extractNotes: Boolean = true): ParsedAiResponse {
        val raw = rawAiReply?.trim().orEmpty()
        if (raw.isEmpty()) {
            return ParsedAiResponse(cleanReply = "", extractedNotes = emptyList())
        }

        val notes = mutableListOf<String>()
        if (extractNotes) {
            for (match in SAVE_NOTE_REGEX.findAll(raw)) {
                val captured = match.groupValues.getOrNull(1)
                val sanitized = sanitizeExtractedNote(captured)
                if (sanitized != null) {
                    notes.add(sanitized)
                }
            }
        }

        val stripped = sanitizeOutboundReply(raw)
        val distinctNotes = if (extractNotes) notes.distinct() else emptyList()

        val finalReply = if (extractNotes && stripped.isEmpty() && distinctNotes.isNotEmpty()) {
            DEFAULT_NOTE_CONFIRMATION
        } else {
            stripped
        }

        return ParsedAiResponse(
            cleanReply = finalReply,
            extractedNotes = distinctNotes
        )
    }

    @JvmStatic
    fun buildAugmentedSystemPrompt(
        basePrompt: String,
        notesEnabled: Boolean,
        memoryEnabled: Boolean,
        senderName: String?,
        pendingNotesForSender: List<AiNote>,
        ruleSpecificInstruction: String? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val sb = StringBuilder(basePrompt.trim())

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val formattedTime = dateFormat.format(Date(nowMillis))

        sb.append("\n\n[Context]")
        sb.append("\n- Current date and time: ").append(formattedTime)
        if (!senderName.isNullOrBlank()) {
            sb.append("\n- Current sender/chat: ").append(senderName.trim())
        }
        if (memoryEnabled) {
            sb.append("\n- Use previous conversation turns in this chat to maintain continuity without restarting from scratch or repeating earlier greetings.")
        }

        if (!ruleSpecificInstruction.isNullOrBlank()) {
            sb.append("\n- Specific rule guidance for this message: ").append(ruleSpecificInstruction.trim())
        }

        if (pendingNotesForSender.isNotEmpty()) {
            sb.append("\n- Open notes/reminders already saved from this sender:")
            pendingNotesForSender.take(5).forEach { note ->
                sb.append("\n  * ").append(note.noteText.trim())
            }
        }

        if (notesEnabled) {
            sb.append("\n\n[Notes & Reminders Tool]")
            sb.append(
                "\nIf the sender asks you to remember something, take a note, schedule a callback/meeting, " +
                    "pass a message/order, or remind the owner about anything later, append a hidden marker " +
                    "at the very end of your response on a new line using this EXACT format:\n" +
                    "[[SAVE_NOTE: concise summary of what to remember including date/time/details if mentioned]]\n" +
                    "Only include [[SAVE_NOTE: ...]] when there is a genuine reminder, request, or important note for the owner. " +
                    "Write your natural reply to the sender first before adding the tag."
            )
        }

        return sb.toString()
    }

    @JvmStatic
    fun shouldAllowAiReply(
        cooldownSeconds: Int,
        maxRepliesPerSender: Int,
        groupFilterMode: String?,
        isGroup: Boolean,
        lastReplyTimeMillis: Long?,
        recentReplyCount: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): AiPolicyDecision {
        val normalizedGroupMode = groupFilterMode?.trim()?.uppercase(Locale.US) ?: "BOTH"
        when (normalizedGroupMode) {
            "DIRECT_ONLY" -> if (isGroup) return AiPolicyDecision.BLOCKED_GROUP_FILTER
            "GROUP_ONLY" -> if (!isGroup) return AiPolicyDecision.BLOCKED_GROUP_FILTER
        }

        if (cooldownSeconds > 0 && lastReplyTimeMillis != null && lastReplyTimeMillis > 0L) {
            val elapsedMs = nowMillis - lastReplyTimeMillis
            if (elapsedMs < cooldownSeconds * 1000L) {
                return AiPolicyDecision.BLOCKED_COOLDOWN
            }
        }

        if (maxRepliesPerSender > 0 && recentReplyCount >= maxRepliesPerSender) {
            return AiPolicyDecision.BLOCKED_FREQUENCY_LIMIT
        }

        return AiPolicyDecision.ALLOWED
    }

    @JvmStatic
    fun computeWindowSinceMillis(frequencyWindowMinutes: Int, nowMillis: Long = System.currentTimeMillis()): Long {
        return if (frequencyWindowMinutes > 0) {
            nowMillis - frequencyWindowMinutes * 60_000L
        } else {
            0L
        }
    }

    /**
     * Converts recent history (chronological) + current incoming user message into OpenAI chat messages.
     */
    @JvmStatic
    fun buildOpenAiMessages(
        systemPrompt: String,
        historyAsc: List<AiConversationMessage>,
        currentIncomingMessage: String
    ): List<Message> {
        val result = ArrayList<Message>(historyAsc.size + 2)
        result.add(Message("system", systemPrompt))
        for (turn in historyAsc) {
            val role = if (turn.role.equals("assistant", ignoreCase = true)) "assistant" else "user"
            if (turn.content.isNotBlank()) {
                result.add(Message(role, turn.content))
            }
        }
        result.add(Message("user", currentIncomingMessage))
        return result
    }

    /**
     * Normalizes conversation turns so they start with "user" and strictly alternate roles
     * (required by Anthropic Claude & Google Gemini chat endpoints).
     */
    @JvmStatic
    fun normalizeAlternatingTurns(
        historyAsc: List<AiConversationMessage>,
        currentIncomingMessage: String
    ): List<Pair<String, String>> {
        val combined = ArrayList<Pair<String, String>>(historyAsc.size + 1)
        for (turn in historyAsc) {
            val normalizedRole = if (turn.role.equals("assistant", ignoreCase = true)) "assistant" else "user"
            val text = turn.content.trim()
            if (text.isNotEmpty()) {
                appendNormalizedTurn(combined, normalizedRole, text)
            }
        }
        appendNormalizedTurn(combined, "user", currentIncomingMessage.trim())
        return combined
    }

    private fun appendNormalizedTurn(
        list: MutableList<Pair<String, String>>,
        role: String,
        text: String
    ) {
        if (text.isEmpty()) return
        // Claude & Gemini require the conversation array to begin with a user turn.
        if (list.isEmpty() && role == "assistant") {
            return
        }
        if (list.isNotEmpty() && list.last().first == role) {
            val prev = list.removeAt(list.lastIndex)
            list.add(role to "${prev.second}\n$text")
        } else {
            list.add(role to text)
        }
    }

    @JvmStatic
    fun buildClaudeMessagesJson(
        historyAsc: List<AiConversationMessage>,
        currentIncomingMessage: String
    ): JsonArray {
        val messages = JsonArray()
        for ((role, text) in normalizeAlternatingTurns(historyAsc, currentIncomingMessage)) {
            val obj = JsonObject()
            obj.addProperty("role", role)
            obj.addProperty("content", text)
            messages.add(obj)
        }
        return messages
    }

    @JvmStatic
    fun buildGeminiContentsJson(
        historyAsc: List<AiConversationMessage>,
        currentIncomingMessage: String
    ): JsonArray {
        val contents = JsonArray()
        for ((role, text) in normalizeAlternatingTurns(historyAsc, currentIncomingMessage)) {
            val contentObj = JsonObject()
            contentObj.addProperty("role", if (role == "assistant") "model" else "user")
            val parts = JsonArray()
            val part = JsonObject()
            part.addProperty("text", text)
            parts.add(part)
            contentObj.add("parts", parts)
            contents.add(contentObj)
        }
        return contents
    }
}
