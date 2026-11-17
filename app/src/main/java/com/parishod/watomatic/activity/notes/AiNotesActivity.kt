package com.parishod.watomatic.activity.notes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.parishod.watomatic.R
import com.parishod.watomatic.activity.BaseActivity
import com.parishod.watomatic.databinding.ActivityAiNotesBinding
import kotlinx.coroutines.launch

class AiNotesActivity : BaseActivity() {
    private lateinit var binding: ActivityAiNotesBinding
    private val viewModel: AiNotesViewModel by viewModels()
    private lateinit var adapter: AiNotesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityAiNotesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.aiNotesRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupToolbar()
        setupRecyclerView()
        setupControls()
        observeUiState()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupRecyclerView() {
        adapter = AiNotesAdapter(
            onToggleCompleted = { noteId, completed ->
                viewModel.setNoteCompleted(noteId, completed)
            },
            onCopyNote = { note ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("AI Note", note.noteText))
                Toast.makeText(this, R.string.ai_notes_copied_toast, Toast.LENGTH_SHORT).show()
            },
            onDeleteNote = { note ->
                viewModel.deleteNote(note.id)
                Toast.makeText(this, R.string.ai_notes_deleted_toast, Toast.LENGTH_SHORT).show()
            }
        )
        binding.notesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.notesRecyclerView.adapter = adapter
    }

    private fun setupControls() {
        binding.etSearchNotes.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.chipGroupNotesFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val mode = when (checkedIds.firstOrNull()) {
                R.id.chip_notes_pending -> NotesFilterMode.PENDING
                R.id.chip_notes_completed -> NotesFilterMode.COMPLETED
                else -> NotesFilterMode.ALL
            }
            viewModel.setFilterMode(mode)
        }

        binding.btnClearDoneNotes.setOnClickListener {
            viewModel.clearCompletedNotes()
            Toast.makeText(this, R.string.ai_notes_cleared_done_toast, Toast.LENGTH_SHORT).show()
        }

        binding.fabAddNote.setOnClickListener {
            showAddManualNoteDialog()
        }
    }

    private fun showAddManualNoteDialog() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, 0)
        }

        val noteTil = TextInputLayout(this).apply {
            hint = getString(R.string.ai_notes_note_hint)
        }
        val noteInput = TextInputEditText(this).apply {
            minLines = 2
        }
        noteTil.addView(noteInput)

        val senderTil = TextInputLayout(this).apply {
            hint = getString(R.string.ai_notes_sender_hint)
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
        val senderInput = TextInputEditText(this).apply {
            maxLines = 1
        }
        senderTil.addView(senderInput)

        container.addView(noteTil)
        container.addView(senderTil)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_notes_add_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = noteInput.text?.toString()?.trim().orEmpty()
                val sender = senderInput.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    viewModel.addManualNote(text, sender)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.tvPendingNotesCount.text = state.pendingCount.toString()
                    binding.tvCompletedNotesCount.text = state.completedCount.toString()
                    binding.btnClearDoneNotes.visibility =
                        if (state.completedCount > 0) View.VISIBLE else View.GONE

                    adapter.submitList(state.displayedNotes)

                    val showEmpty = state.displayedNotes.isEmpty()
                    binding.emptyNotesContainer.visibility = if (showEmpty) View.VISIBLE else View.GONE
                    binding.notesRecyclerView.visibility = if (showEmpty) View.GONE else View.VISIBLE

                    if (state.totalCount == 0) {
                        binding.tvEmptyNotesTitle.setText(R.string.ai_notes_empty_title)
                        binding.tvEmptyNotesSubtitle.setText(R.string.ai_notes_empty_subtitle)
                    } else {
                        binding.tvEmptyNotesTitle.setText(R.string.ai_notes_empty_filtered)
                        binding.tvEmptyNotesSubtitle.text = ""
                    }
                }
            }
        }
    }
}
