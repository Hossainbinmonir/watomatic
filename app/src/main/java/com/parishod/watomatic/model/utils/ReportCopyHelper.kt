package com.parishod.watomatic.model.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.parishod.watomatic.R
import com.parishod.watomatic.activity.history.HistoryViewMode
import com.parishod.watomatic.activity.history.ReplyHistoryRow
import com.parishod.watomatic.activity.history.ReplyHistoryUiState
import com.parishod.watomatic.activity.notes.AiNotesUiState
import com.parishod.watomatic.activity.notes.NotesFilterMode
import com.parishod.watomatic.model.ai.AiNote
import java.text.DateFormat
import java.util.Date

/**
 * Formats user-facing reports (Reply History, AI Notes & Reminders, AI Status)
 * into clean plain text and copies them to the Android clipboard in one tap.
 */
object ReportCopyHelper {

    private val appNameMap: Map<String, String> by lazy {
        Constants.SUPPORTED_APPS.associate { it.packageName to it.name }
    }

    @JvmStatic
    fun copyToClipboard(context: Context, label: String, text: String): Boolean {
        if (text.isBlank()) return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        return true
    }

    fun resolveAppName(context: Context, packageName: String): String {
        if (packageName.isBlank()) return context.getString(R.string.ai_notes_manual_label)
        appNameMap[packageName]?.let { return it }
        Constants.SUPPORTED_APPS.firstOrNull {
            it.packageName.equals(packageName, ignoreCase = true)
        }?.let { return it.name }
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    fun formatReplyHistoryEntry(
        context: Context,
        row: ReplyHistoryRow,
        dateFormatter: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    ): String = when (row) {
        is ReplyHistoryRow.Grouped -> {
            val item = row.item
            val appName = resolveAppName(context, item.packageName)
            val sender = item.lastSender?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.history_unknown_sender)
            val incoming = item.incomingMessage.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.history_no_incoming_text, sender)
            val source = item.matchedRuleName?.takeIf { it.isNotBlank() }
            val timeStr = dateFormatter.format(Date(item.lastReplyTime))

            buildString {
                append(appName)
                if (source != null) append(" [").append(source).append(']')
                append(" • ").append(item.replyCount).append("x")
                append('\n')
                append("From: ").append(sender).append(" • ").append(timeStr).append('\n')
                append("Incoming: ").append(incoming).append('\n')
                append("Reply: ").append(item.repliedMessage)
            }
        }
        is ReplyHistoryRow.Timeline -> {
            val item = row.item
            val appName = resolveAppName(context, item.packageName)
            val sender = item.senderName?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.history_unknown_sender)
            val incoming = item.incomingMessage?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.history_no_incoming_text, sender)
            val source = item.matchedRuleName?.takeIf { it.isNotBlank() }
            val timeStr = dateFormatter.format(Date(item.replyTime))

            buildString {
                append(appName)
                if (source != null) append(" [").append(source).append(']')
                append('\n')
                append("From: ").append(sender).append(" • ").append(timeStr).append('\n')
                append("Incoming: ").append(incoming).append('\n')
                append("Reply: ").append(item.repliedMessage)
            }
        }
    }

    fun formatReplyHistoryReport(
        context: Context,
        state: ReplyHistoryUiState,
        dateFormatter: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    ): String {
        val modeLabel = when (state.mode) {
            HistoryViewMode.GROUPED -> context.getString(R.string.history_tab_grouped)
            HistoryViewMode.TIMELINE -> context.getString(R.string.history_tab_timeline)
        }
        return buildString {
            append("=== ").append(context.getString(R.string.reply_history_title)).append(" ===").append('\n')
            append("Total Replies: ").append(state.totalReplies)
                .append(" | Unique Messages: ").append(state.uniqueMessages).append('\n')
            append("View: ").append(modeLabel)
            if (state.searchQuery.isNotBlank()) {
                append(" | Search: \"").append(state.searchQuery.trim()).append('"')
            }
            append('\n')
            if (state.displayedRows.isEmpty()) {
                append('\n').append(context.getString(R.string.empty_history_title))
            } else {
                state.displayedRows.forEachIndexed { index, row ->
                    append('\n')
                    append('#').append(index + 1).append(' ')
                    append(formatReplyHistoryEntry(context, row, dateFormatter))
                    if (index < state.displayedRows.lastIndex) {
                        append('\n')
                    }
                }
            }
        }
    }

    fun formatAiNoteEntry(
        context: Context,
        note: AiNote,
        dateFormatter: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    ): String {
        val status = if (note.isCompleted) {
            context.getString(R.string.ai_notes_status_done)
        } else {
            context.getString(R.string.ai_notes_status_pending)
        }
        val sender = note.senderName.ifBlank { context.getString(R.string.ai_notes_manual_label) }
        val appName = if (note.packageName.isNotBlank()) resolveAppName(context, note.packageName) else null
        val timeStr = dateFormatter.format(Date(note.createdAt))

        return buildString {
            append('[').append(status).append("] ").append(note.noteText).append('\n')
            append("From: ").append(sender)
            if (appName != null) append(" (").append(appName).append(')')
            append(" • ").append(timeStr)
            if (note.sourceMessage.isNotBlank()) {
                append('\n').append("Message: ").append(note.sourceMessage)
            }
        }
    }

    fun formatAiNotesReport(
        context: Context,
        state: AiNotesUiState,
        dateFormatter: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    ): String {
        val filterLabel = when (state.filterMode) {
            NotesFilterMode.ALL -> context.getString(R.string.ai_notes_tab_all)
            NotesFilterMode.PENDING -> context.getString(R.string.ai_notes_tab_pending)
            NotesFilterMode.COMPLETED -> context.getString(R.string.ai_notes_tab_completed)
        }
        return buildString {
            append("=== ").append(context.getString(R.string.ai_notes_title)).append(" ===").append('\n')
            append("Pending: ").append(state.pendingCount)
                .append(" | Completed: ").append(state.completedCount).append('\n')
            append("Filter: ").append(filterLabel)
            if (state.searchQuery.isNotBlank()) {
                append(" | Search: \"").append(state.searchQuery.trim()).append('"')
            }
            append('\n')
            if (state.displayedNotes.isEmpty()) {
                append('\n').append(context.getString(R.string.ai_notes_empty_title))
            } else {
                state.displayedNotes.forEachIndexed { index, note ->
                    append('\n')
                    append('#').append(index + 1).append(' ')
                    append(formatAiNoteEntry(context, note, dateFormatter))
                    if (index < state.displayedNotes.lastIndex) {
                        append('\n')
                    }
                }
            }
        }
    }
}
