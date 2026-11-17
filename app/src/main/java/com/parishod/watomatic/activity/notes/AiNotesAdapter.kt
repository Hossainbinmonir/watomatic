package com.parishod.watomatic.activity.notes

import android.content.Context
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.parishod.watomatic.R
import com.parishod.watomatic.model.ai.AiNote
import com.parishod.watomatic.model.utils.Constants
import java.text.DateFormat
import java.util.Date

class AiNotesAdapter(
    private val onToggleCompleted: (Long, Boolean) -> Unit,
    private val onCopyNote: (AiNote) -> Unit,
    private val onDeleteNote: (AiNote) -> Unit
) : ListAdapter<AiNote, AiNotesAdapter.NoteViewHolder>(NoteDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ai_note_card, parent, false)
        return NoteViewHolder(view)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView.findViewById(R.id.ai_note_card)
        private val cbCompleted: MaterialCheckBox = itemView.findViewById(R.id.cb_note_completed)
        private val tvSender: TextView = itemView.findViewById(R.id.tv_note_sender)
        private val tvAppAndTime: TextView = itemView.findViewById(R.id.tv_note_app_and_time)
        private val tvStatusBadge: TextView = itemView.findViewById(R.id.tv_note_status_badge)
        private val tvNoteText: TextView = itemView.findViewById(R.id.tv_note_text)
        private val sourceContainer: View = itemView.findViewById(R.id.note_source_container)
        private val tvSourceMessage: TextView = itemView.findViewById(R.id.tv_note_source_message)
        private val btnCopy: ImageButton = itemView.findViewById(R.id.btn_copy_note)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete_note)

        fun bind(note: AiNote) {
            val ctx = itemView.context

            tvSender.text = note.senderName.ifBlank { ctx.getString(R.string.ai_notes_manual_label) }

            val appName = resolveAppName(ctx, note.packageName)
            val dateStr = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(note.createdAt))
            tvAppAndTime.text = if (appName != null) "$appName • $dateStr" else dateStr

            tvNoteText.text = note.noteText

            if (note.isCompleted) {
                tvStatusBadge.setText(R.string.ai_notes_status_done)
                tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_active)
                tvStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_active_text))
                tvNoteText.paintFlags = tvNoteText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                card.alpha = 0.78f
            } else {
                tvStatusBadge.setText(R.string.ai_notes_status_pending)
                tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_category)
                tvStatusBadge.setTextColor(ContextCompat.getColor(ctx, R.color.badge_category_text))
                tvNoteText.paintFlags = tvNoteText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                card.alpha = 1.0f
            }

            if (note.sourceMessage.isNotBlank()) {
                sourceContainer.visibility = View.VISIBLE
                tvSourceMessage.text = note.sourceMessage
            } else {
                sourceContainer.visibility = View.GONE
            }

            cbCompleted.setOnCheckedChangeListener(null)
            cbCompleted.isChecked = note.isCompleted
            cbCompleted.setOnCheckedChangeListener { _, isChecked ->
                onToggleCompleted(note.id, isChecked)
            }

            btnCopy.setOnClickListener { onCopyNote(note) }
            btnDelete.setOnClickListener { onDeleteNote(note) }
        }

        private fun resolveAppName(ctx: Context, packageName: String): String? {
            if (packageName.isBlank()) return null
            val known = Constants.SUPPORTED_APPS.firstOrNull {
                it.packageName.equals(packageName, ignoreCase = true)
            }
            if (known != null) return known.name
            return try {
                val pm = ctx.packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                packageName
            }
        }
    }

    private class NoteDiffCallback : DiffUtil.ItemCallback<AiNote>() {
        override fun areItemsTheSame(oldItem: AiNote, newItem: AiNote): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: AiNote, newItem: AiNote): Boolean = oldItem == newItem
    }
}
