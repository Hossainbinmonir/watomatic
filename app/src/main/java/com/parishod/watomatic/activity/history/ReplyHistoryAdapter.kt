package com.parishod.watomatic.activity.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.parishod.watomatic.R
import com.parishod.watomatic.model.utils.Constants
import java.text.DateFormat
import java.util.Date

class ReplyHistoryAdapter(
    private val onCopyEntry: (ReplyHistoryRow) -> Unit = {},
    private val onDeleteEntry: (Int) -> Unit
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
        private val tvCountBadge: TextView = itemView.findViewById(R.id.tv_history_count_badge)
        private val tvIncomingMsg: TextView = itemView.findViewById(R.id.tv_history_incoming_msg)
        private val tvRepliedMsg: TextView = itemView.findViewById(R.id.tv_history_replied_msg)
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

                    tvCountBadge.visibility = View.VISIBLE
                    tvCountBadge.text = ctx.getString(R.string.history_reply_count_badge, item.replyCount)

                    val senderDisplay = item.lastSender?.takeIf { it.isNotBlank() }
                        ?: ctx.getString(R.string.history_unknown_sender)
                    tvIncomingMsg.text = item.incomingMessage.takeIf { it.isNotBlank() }
                        ?: ctx.getString(R.string.history_no_incoming_text, senderDisplay)

                    tvRepliedMsg.text = item.repliedMessage

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

                    tvCountBadge.visibility = View.GONE

                    val senderDisplay = item.senderName?.takeIf { it.isNotBlank() }
                        ?: ctx.getString(R.string.history_unknown_sender)
                    tvIncomingMsg.text = item.incomingMessage?.takeIf { it.isNotBlank() }
                        ?: ctx.getString(R.string.history_no_incoming_text, senderDisplay)

                    tvRepliedMsg.text = item.repliedMessage

                    val timeStr = formatter.format(Date(item.replyTime))
                    val senderPart = ctx.getString(R.string.history_sender_label, senderDisplay)
                    tvMeta.text = "$senderPart • $timeStr"

                    btnCopy.setOnClickListener { onCopyEntry(row) }
                    btnDelete.visibility = View.VISIBLE
                    btnDelete.setOnClickListener { onDeleteEntry(item.id) }
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
                    oldItem.item.id == newItem.item.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: ReplyHistoryRow, newItem: ReplyHistoryRow): Boolean {
            return oldItem == newItem
        }
    }
}
