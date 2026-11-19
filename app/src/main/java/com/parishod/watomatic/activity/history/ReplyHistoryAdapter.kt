package com.parishod.watomatic.activity.history

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.parishod.watomatic.R
import com.parishod.watomatic.model.logs.ReplyHistoryDetailItem
import com.parishod.watomatic.model.logs.ReplyHistoryStatus
import com.parishod.watomatic.model.utils.Constants
import com.parishod.watomatic.service.reply.ReplyExecutionManager
import java.text.DateFormat
import java.util.Date

class ReplyHistoryAdapter(
    private val onCopyEntry: (ReplyHistoryRow) -> Unit = {},
    private val onDeleteEntry: (Int) -> Unit = {},
    private val onDeleteTimelineEntry: (ReplyHistoryDetailItem) -> Unit = { item -> onDeleteEntry(item.id) }
) : ListAdapter<ReplyHistoryRow, ReplyHistoryAdapter.HistoryViewHolder>(DiffCallback()) {

    private val appNameMap: Map<String, String> by lazy {
        Constants.SUPPORTED_APPS.associate { it.packageName to it.name }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reply_history_card, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvApp: TextView = itemView.findViewById(R.id.tv_history_app)
        private val tvRuleBadge: TextView = itemView.findViewById(R.id.tv_history_rule_badge)
        private val tvStatusBadge: TextView = itemView.findViewById(R.id.tv_history_status_badge)
        private val tvCountBadge: TextView = itemView.findViewById(R.id.tv_history_count_badge)
        private val tvIncomingMsg: TextView = itemView.findViewById(R.id.tv_history_incoming_msg)
        private val tvReplyBoxLabel: TextView = itemView.findViewById(R.id.tv_history_reply_box_label)
        private val tvRepliedMsg: TextView = itemView.findViewById(R.id.tv_history_replied_msg)
        private val tvStatusNote: TextView = itemView.findViewById(R.id.tv_history_status_note)
        private val tvMeta: TextView = itemView.findViewById(R.id.tv_history_meta)
        private val btnCopy: ImageButton = itemView.findViewById(R.id.btn_copy_history_item)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete_history_item)

        fun bind(row: ReplyHistoryRow) {
            val ctx = itemView.context
            val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

            when (row) {
                is ReplyHistoryRow.Grouped -> {
                    val item = row.item
                    tvApp.text = appNameMap[item.packageName] ?: item.packageName

                    val ruleName = item.matchedRuleName?.takeIf { it.isNotBlank() }
                    if (ruleName != null) {
                        tvRuleBadge.visibility = View.VISIBLE
                        tvRuleBadge.text = ruleName
                    } else {
                        tvRuleBadge.visibility = View.GONE
                    }

                    bindStatusBadge(ctx, item.executionStatus)
                    tvReplyBoxLabel.setText(replyBoxLabelResFor(item.executionStatus))
                    tvStatusNote.visibility = View.GONE

                    tvCountBadge.visibility = View.VISIBLE
                    tvCountBadge.text = ctx.getString(R.string.history_reply_count_badge, item.replyCount)

                    val senderDisplay = item.lastSender?.takeIf { it.isNotBlank() }
                        ?: ctx.getString(R.string.history_unknown_sender)
                    tvIncomingMsg.text = item.incomingMessage.takeIf { it.isNotBlank() }
                        ?: ctx.getString(R.string.history_no_incoming_text, senderDisplay)

                    tvRepliedMsg.text = item.repliedMessage.takeIf { it.isNotBlank() }
                        ?: ctx.getString(R.string.history_no_reply_text)

                    val timeStr = formatter.format(Date(item.lastReplyTime))
                    val senderPart = ctx.getString(R.string.history_last_sender, senderDisplay)
                    tvMeta.text = "$senderPart • $timeStr"

                    btnCopy.setOnClickListener { onCopyEntry(row) }
                    btnDelete.visibility = View.GONE
                    btnDelete.setOnClickListener(null)
                }

                is ReplyHistoryRow.Timeline -> {
                    val item = row.item
                    tvApp.text = appNameMap[item.packageName] ?: item.packageName

                    val ruleName = item.matchedRuleName?.takeIf { it.isNotBlank() }
                    if (ruleName != null) {
                        tvRuleBadge.visibility = View.VISIBLE
                        tvRuleBadge.text = ruleName
                    } else {
                        tvRuleBadge.visibility = View.GONE
                    }

                    bindStatusBadge(ctx, item.executionStatus)
                    tvReplyBoxLabel.setText(replyBoxLabelResFor(item.executionStatus))

                    val safeNoteRes = safeStatusNoteResFor(item.executionStatus, item.failureCategory)
                    if (safeNoteRes != null) {
                        tvStatusNote.visibility = View.VISIBLE
                        tvStatusNote.setText(safeNoteRes)
                    } else {
                        tvStatusNote.visibility = View.GONE
                    }

                    tvCountBadge.visibility = View.GONE

                    val senderDisplay = item.senderName?.takeIf { it.isNotBlank() }
                        ?: ctx.getString(R.string.history_unknown_sender)
                    tvIncomingMsg.text = item.incomingMessage?.takeIf { it.isNotBlank() }
                        ?: ctx.getString(R.string.history_no_incoming_text, senderDisplay)

                    tvRepliedMsg.text = item.repliedMessage.takeIf { it.isNotBlank() }
                        ?: ctx.getString(R.string.history_no_reply_text)

                    val timeStr = formatter.format(Date(item.replyTime))
                    val senderPart = ctx.getString(R.string.history_sender_label, senderDisplay)
                    tvMeta.text = "$senderPart • $timeStr"

                    btnCopy.setOnClickListener { onCopyEntry(row) }
                    btnDelete.visibility = View.VISIBLE
                    btnDelete.setOnClickListener { onDeleteTimelineEntry(item) }
                }
            }
        }

        private fun bindStatusBadge(ctx: Context, status: String) {
            tvStatusBadge.visibility = View.VISIBLE
            when (status) {
                ReplyHistoryStatus.DISPATCHED -> {
                    tvStatusBadge.setText(R.string.history_status_dispatched)
                    tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_active)
                    tvStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_active_text))
                }
                ReplyHistoryStatus.FAILED -> {
                    tvStatusBadge.setText(R.string.history_status_failed)
                    tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_danger)
                    tvStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_danger_text))
                }
                ReplyHistoryStatus.CANCELLED -> {
                    tvStatusBadge.setText(R.string.history_status_cancelled)
                    tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_gray)
                    tvStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_gray_text))
                }
                ReplyHistoryStatus.UNKNOWN -> {
                    tvStatusBadge.setText(R.string.history_status_unknown)
                    tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_warning)
                    tvStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_warning_text))
                }
                ReplyHistoryStatus.DISPATCHING -> {
                    tvStatusBadge.setText(R.string.history_status_dispatching)
                    tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_warning)
                    tvStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_warning_text))
                }
                ReplyHistoryStatus.GENERATED -> {
                    tvStatusBadge.setText(R.string.history_status_pending)
                    tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_gray)
                    tvStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_gray_text))
                }
                ReplyHistoryStatus.MIXED -> {
                    tvStatusBadge.setText(R.string.history_status_mixed)
                    tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_gray)
                    tvStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_gray_text))
                }
                else -> {
                    tvStatusBadge.setText(R.string.history_status_legacy)
                    tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_gray)
                    tvStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_gray_text))
                }
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<ReplyHistoryRow>() {
        override fun areItemsTheSame(oldItem: ReplyHistoryRow, newItem: ReplyHistoryRow): Boolean {
            return when {
                oldItem is ReplyHistoryRow.Grouped && newItem is ReplyHistoryRow.Grouped ->
                    oldItem.item.packageName == newItem.item.packageName &&
                        oldItem.item.incomingMessage == newItem.item.incomingMessage &&
                        oldItem.item.repliedMessage == newItem.item.repliedMessage
                oldItem is ReplyHistoryRow.Timeline && newItem is ReplyHistoryRow.Timeline ->
                    if (oldItem.item.attemptId != null || newItem.item.attemptId != null) {
                        oldItem.item.attemptId == newItem.item.attemptId
                    } else {
                        oldItem.item.id == newItem.item.id
                    }
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: ReplyHistoryRow, newItem: ReplyHistoryRow): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        @StringRes
        fun replyBoxLabelResFor(status: String): Int = when (status) {
            ReplyHistoryStatus.DISPATCHED -> R.string.history_reply_dispatched_label
            ReplyHistoryStatus.FAILED -> R.string.history_reply_failed_label
            ReplyHistoryStatus.CANCELLED -> R.string.history_reply_cancelled_label
            ReplyHistoryStatus.UNKNOWN -> R.string.history_reply_unknown_label
            ReplyHistoryStatus.GENERATED,
            ReplyHistoryStatus.DISPATCHING -> R.string.history_reply_pending_label
            else -> R.string.history_reply_legacy_label
        }

        @StringRes
        fun safeStatusNoteResFor(status: String, failureCategory: String?): Int? {
            if (status == ReplyHistoryStatus.DISPATCHED ||
                status == ReplyHistoryStatus.LEGACY ||
                status == ReplyHistoryStatus.MIXED ||
                status == ReplyHistoryStatus.GENERATED ||
                status == ReplyHistoryStatus.DISPATCHING
            ) {
                return null
            }
            return when (failureCategory) {
                ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN -> R.string.history_note_reason_shutdown
                ReplyExecutionManager.FAILURE_INVALID_REPLY_TEXT -> R.string.history_note_reason_invalid_text
                ReplyExecutionManager.FAILURE_MISSING_WEAR_DATA -> R.string.history_note_reason_missing_wear
                ReplyExecutionManager.FAILURE_MISSING_REMOTE_INPUT -> R.string.history_note_reason_missing_remote_input
                ReplyExecutionManager.FAILURE_MISSING_PENDING_INTENT -> R.string.history_note_reason_missing_pending_intent
                ReplyExecutionManager.FAILURE_CONCURRENT_IN_FLIGHT -> R.string.history_note_reason_concurrent
                ReplyExecutionManager.FAILURE_PENDING_INTENT_CANCELED -> R.string.history_note_reason_canceled
                ReplyExecutionManager.FAILURE_SECURITY_EXCEPTION -> R.string.history_note_reason_security
                ReplyExecutionManager.FAILURE_AMBIGUOUS_SECURITY_EXCEPTION -> R.string.history_note_reason_ambiguous_security
                ReplyExecutionManager.FAILURE_AMBIGUOUS_SEND_EXCEPTION -> R.string.history_note_reason_ambiguous_send
                ReplyExecutionManager.FAILURE_DUPLICATE_EVENT -> R.string.history_note_reason_duplicate_event
                ReplyExecutionManager.FAILURE_UNKNOWN_OUTCOME_LOCKED -> R.string.history_note_reason_unknown_locked
                ReplyExecutionManager.FAILURE_GENERATED_INTERRUPTED -> R.string.history_note_reason_generated_interrupted
                ReplyExecutionManager.FAILURE_DISPATCHING_INTERRUPTED -> R.string.history_note_reason_dispatching_interrupted
                ReplyExecutionManager.FAILURE_CLAIM_PERSISTENCE_ERROR -> R.string.history_note_reason_claim_persistence
                else -> if (status == ReplyHistoryStatus.UNKNOWN) {
                    R.string.history_note_unknown_general
                } else {
                    R.string.history_note_reason_unclassified
                }
            }
        }
    }
}
