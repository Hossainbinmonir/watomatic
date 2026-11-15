package com.parishod.watomatic.activity.rules

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.parishod.watomatic.R
import com.parishod.watomatic.model.rules.AutoReplyRule
import com.parishod.watomatic.model.rules.ContactFilterMode
import com.parishod.watomatic.model.rules.GroupFilterMode
import com.parishod.watomatic.model.rules.MatchType
import com.parishod.watomatic.model.utils.Constants
import java.util.Locale

class RuleListAdapter(
    private val listener: RuleActionListener
) : ListAdapter<AutoReplyRule, RuleListAdapter.RuleViewHolder>(RuleDiffCallback()) {

    interface RuleActionListener {
        fun onToggleEnabled(rule: AutoReplyRule, enabled: Boolean)
        fun onEditRule(rule: AutoReplyRule)
        fun onDeleteRule(rule: AutoReplyRule)
        fun onDuplicateRule(rule: AutoReplyRule)
        fun onAdjustPriority(rule: AutoReplyRule, delta: Int)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_rule_card, parent, false)
        return RuleViewHolder(view)
    }

    override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RuleViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView.findViewById(R.id.rule_card)
        private val tvStatusBadge: TextView = itemView.findViewById(R.id.tv_rule_status_badge)
        private val tvPriorityBadge: TextView = itemView.findViewById(R.id.tv_rule_priority_badge)
        private val tvName: TextView = itemView.findViewById(R.id.tv_rule_name)
        private val switchEnabled: MaterialSwitch = itemView.findViewById(R.id.switch_rule_enabled)
        private val tvApps: TextView = itemView.findViewById(R.id.tv_rule_apps)
        private val tvCondition: TextView = itemView.findViewById(R.id.tv_rule_condition)
        private val tvReplyPreview: TextView = itemView.findViewById(R.id.tv_rule_reply_preview)
        private val tvMeta: TextView = itemView.findViewById(R.id.tv_rule_meta)
        private val btnPriorityUp: ImageButton = itemView.findViewById(R.id.btn_priority_up)
        private val btnPriorityDown: ImageButton = itemView.findViewById(R.id.btn_priority_down)
        private val btnDuplicate: ImageButton = itemView.findViewById(R.id.btn_duplicate_rule)
        private val btnEdit: MaterialButton = itemView.findViewById(R.id.btn_edit_rule)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete_rule)

        fun bind(rule: AutoReplyRule) {
            val ctx = itemView.context

            tvName.text = rule.name
            tvPriorityBadge.text = ctx.getString(R.string.rule_priority_badge, rule.priority)

            if (rule.isEnabled) {
                tvStatusBadge.text = ctx.getString(R.string.rule_status_active).uppercase(Locale.getDefault())
                tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_active)
                card.alpha = 1.0f
            } else {
                tvStatusBadge.text = ctx.getString(R.string.rule_status_inactive).uppercase(Locale.getDefault())
                tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_gray)
                card.alpha = 0.82f
            }

            switchEnabled.setOnCheckedChangeListener(null)
            switchEnabled.isChecked = rule.isEnabled
            switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                listener.onToggleEnabled(rule, isChecked)
            }

            tvApps.text = formatSelectedApps(ctx, rule.selectedPackages)
            tvCondition.text = formatCondition(ctx, rule)
            tvReplyPreview.text = rule.replyMessage

            val metaSummary = buildMetaSummary(ctx, rule)
            if (metaSummary.isNotEmpty()) {
                tvMeta.visibility = View.VISIBLE
                tvMeta.text = metaSummary
            } else {
                tvMeta.visibility = View.GONE
            }

            card.setOnClickListener { listener.onEditRule(rule) }
            btnEdit.setOnClickListener { listener.onEditRule(rule) }
            btnDelete.setOnClickListener { listener.onDeleteRule(rule) }
            btnDuplicate.setOnClickListener { listener.onDuplicateRule(rule) }
            btnPriorityUp.setOnClickListener { listener.onAdjustPriority(rule, +5) }
            btnPriorityDown.setOnClickListener { listener.onAdjustPriority(rule, -5) }

            btnPriorityUp.isEnabled = rule.priority < 100
            btnPriorityDown.isEnabled = rule.priority > 0
            btnPriorityUp.alpha = if (btnPriorityUp.isEnabled) 1.0f else 0.35f
            btnPriorityDown.alpha = if (btnPriorityDown.isEnabled) 1.0f else 0.35f
            tvStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.white))
        }

        private fun formatSelectedApps(ctx: Context, packages: List<String>): String {
            if (packages.isEmpty()) return ctx.getString(R.string.error_select_one_app)
            val appNameMap = Constants.SUPPORTED_APPS.associate { it.packageName to it.name }
            return packages.joinToString(" • ") { pkg -> appNameMap[pkg] ?: pkg }
        }

        private fun formatCondition(ctx: Context, rule: AutoReplyRule): String {
            val matchLabel = when (rule.matchType) {
                MatchType.EXACT -> ctx.getString(R.string.match_exact)
                MatchType.CONTAINS -> ctx.getString(R.string.match_contains)
                MatchType.STARTS_WITH -> ctx.getString(R.string.match_starts_with)
                MatchType.ENDS_WITH -> ctx.getString(R.string.match_ends_with)
                MatchType.DOES_NOT_CONTAIN -> ctx.getString(R.string.match_does_not_contain)
                MatchType.ANY_KEYWORD -> ctx.getString(R.string.match_any_keyword)
                MatchType.ALL_KEYWORDS -> ctx.getString(R.string.match_all_keywords)
                MatchType.REGEX -> ctx.getString(R.string.match_regex)
            }
            val caseNote = if (rule.isCaseSensitive) " (Aa)" else ""
            val kwText = if (rule.keywords.isEmpty()) "-" else rule.keywords.joinToString(", ")
            return "$matchLabel$caseNote: $kwText"
        }

        private fun buildMetaSummary(ctx: Context, rule: AutoReplyRule): String {
            val parts = mutableListOf<String>()
            if (rule.scheduleEnabled) {
                parts.add("${formatMinutes(rule.startTimeMinutes)}–${formatMinutes(rule.endTimeMinutes)}")
            }
            if (rule.replyDelaySeconds > 0) {
                parts.add("Delay ${rule.replyDelaySeconds}s")
            }
            if (rule.cooldownSeconds > 0) {
                parts.add("Cooldown ${rule.cooldownSeconds}s")
            }
            if (rule.maxRepliesPerSender > 0) {
                parts.add("Max ${rule.maxRepliesPerSender}x")
            }
            when (rule.groupFilterMode) {
                GroupFilterMode.DIRECT_ONLY -> parts.add(ctx.getString(R.string.group_filter_direct))
                GroupFilterMode.GROUP_ONLY -> parts.add(ctx.getString(R.string.group_filter_group))
                GroupFilterMode.BOTH -> Unit
            }
            when (rule.contactFilterMode) {
                ContactFilterMode.INCLUDE_ONLY -> parts.add("Contacts: +${rule.filterContacts.size}")
                ContactFilterMode.EXCLUDE -> parts.add("Excluded: ${rule.filterContacts.size}")
                ContactFilterMode.ALL -> Unit
            }
            return parts.joinToString(" • ")
        }

        private fun formatMinutes(minutes: Int): String {
            val clamped = minutes.coerceIn(0, 1439)
            val h = clamped / 60
            val m = clamped % 60
            return String.format(Locale.US, "%02d:%02d", h, m)
        }
    }

    private class RuleDiffCallback : DiffUtil.ItemCallback<AutoReplyRule>() {
        override fun areItemsTheSame(oldItem: AutoReplyRule, newItem: AutoReplyRule): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AutoReplyRule, newItem: AutoReplyRule): Boolean {
            return oldItem == newItem
        }
    }
}
