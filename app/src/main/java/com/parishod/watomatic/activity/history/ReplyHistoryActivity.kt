package com.parishod.watomatic.activity.history

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
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
import com.parishod.watomatic.R
import com.parishod.watomatic.activity.BaseActivity
import com.parishod.watomatic.databinding.ActivityReplyHistoryBinding
import com.parishod.watomatic.model.utils.ReportCopyHelper
import kotlinx.coroutines.launch

class ReplyHistoryActivity : BaseActivity() {
    private lateinit var binding: ActivityReplyHistoryBinding
    private val viewModel: ReplyHistoryViewModel by viewModels()
    private lateinit var adapter: ReplyHistoryAdapter
    private var latestUiState: ReplyHistoryUiState = ReplyHistoryUiState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityReplyHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.replyHistoryRoot) { v, insets ->
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
        adapter = ReplyHistoryAdapter(
            onCopyEntry = { row ->
                val formatted = ReportCopyHelper.formatReplyHistoryEntry(this, row)
                if (ReportCopyHelper.copyToClipboard(this, getString(R.string.reply_history_title), formatted)) {
                    Toast.makeText(this, R.string.history_entry_copied_toast, Toast.LENGTH_SHORT).show()
                }
            },
            onDeleteEntry = { logId ->
                viewModel.deleteEntry(logId)
            }
        )
        binding.historyRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.historyRecyclerView.adapter = adapter
    }

    private fun setupControls() {
        binding.etSearchHistory.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.chipGroupHistoryMode.setOnCheckedStateChangeListener { _, checkedIds ->
            val selectedId = checkedIds.firstOrNull() ?: R.id.chip_mode_grouped
            val mode = if (selectedId == R.id.chip_mode_timeline) {
                HistoryViewMode.TIMELINE
            } else {
                HistoryViewMode.GROUPED
            }
            viewModel.setViewMode(mode)
        }

        binding.btnClearHistory.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.history_clear_title)
                .setMessage(R.string.history_clear_message)
                .setPositiveButton(R.string.history_clear_all) { _, _ ->
                    viewModel.clearAllHistory()
                    Toast.makeText(this, R.string.history_cleared_toast, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        binding.btnCopyHistoryReport.setOnClickListener {
            val formattedReport = ReportCopyHelper.formatReplyHistoryReport(this, latestUiState)
            if (ReportCopyHelper.copyToClipboard(this, getString(R.string.reply_history_title), formattedReport)) {
                Toast.makeText(this, R.string.history_report_copied_toast, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    latestUiState = state
                    binding.tvTotalRepliesCount.text = state.totalReplies.toString()
                    binding.tvUniqueMessagesCount.text = state.uniqueMessages.toString()
                    binding.btnClearHistory.visibility =
                        if (state.totalReplies > 0) View.VISIBLE else View.GONE
                    binding.btnCopyHistoryReport.visibility =
                        if (state.displayedRows.isNotEmpty()) View.VISIBLE else View.GONE

                    adapter.submitList(state.displayedRows)

                    val showEmpty = state.displayedRows.isEmpty()
                    binding.emptyHistoryContainer.visibility = if (showEmpty) View.VISIBLE else View.GONE
                    binding.historyRecyclerView.visibility = if (showEmpty) View.GONE else View.VISIBLE

                    if (state.totalReplies == 0) {
                        binding.tvEmptyHistoryTitle.setText(R.string.empty_history_title)
                        binding.tvEmptyHistorySubtitle.setText(R.string.empty_history_subtitle)
                    } else {
                        binding.tvEmptyHistoryTitle.setText(R.string.empty_filtered_history)
                        binding.tvEmptyHistorySubtitle.text = ""
                    }
                }
            }
        }
    }
}
