package com.parishod.watomatic.activity.rules

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
import com.parishod.watomatic.databinding.ActivityRuleDashboardBinding
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.rules.AutoReplyRule
import com.parishod.watomatic.service.NotificationService
import kotlinx.coroutines.launch

class RuleDashboardActivity : BaseActivity(), RuleListAdapter.RuleActionListener {
    private lateinit var binding: ActivityRuleDashboardBinding
    private val viewModel: RuleDashboardViewModel by viewModels()
    private lateinit var adapter: RuleListAdapter
    private lateinit var preferencesManager: PreferencesManager
    private var renderedCategories: List<String> = emptyList()
    private var isUpdatingCategoryChips: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityRuleDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferencesManager = PreferencesManager.getPreferencesInstance(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.ruleDashboardRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupToolbar()
        setupRecyclerView()
        setupControls()
        observeUiState()
    }

    override fun onResume() {
        super.onResume()
        updateListenerWarning()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupRecyclerView() {
        adapter = RuleListAdapter(this)
        binding.rulesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.rulesRecyclerView.adapter = adapter
    }

    private fun setupControls() {
        binding.switchGlobalFallback.isChecked = preferencesManager.isRuleFallbackToGlobalEnabled()
        binding.switchGlobalFallback.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.setRuleFallbackToGlobalEnabled(isChecked)
        }

        binding.etSearchRules.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.chipGroupRuleFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val selectedId = checkedIds.firstOrNull() ?: R.id.chip_filter_all
            val tab = when (selectedId) {
                R.id.chip_filter_active -> RuleFilterTab.ACTIVE
                R.id.chip_filter_inactive -> RuleFilterTab.INACTIVE
                else -> RuleFilterTab.ALL
            }
            viewModel.setFilterTab(tab)
        }

        val initialReplyType = intent.getStringExtra(EXTRA_REPLY_TYPE_FILTER)
            ?.let { runCatching { RuleReplyTypeFilter.valueOf(it) }.getOrNull() }
            ?: RuleReplyTypeFilter.ALL
        when (initialReplyType) {
            RuleReplyTypeFilter.MANUAL -> binding.chipTypeManual.isChecked = true
            RuleReplyTypeFilter.AI -> binding.chipTypeAi.isChecked = true
            RuleReplyTypeFilter.ALL -> binding.chipTypeAll.isChecked = true
        }
        viewModel.setReplyTypeFilter(initialReplyType)

        binding.chipGroupReplyTypeFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val selectedId = checkedIds.firstOrNull() ?: R.id.chip_type_all
            val filter = when (selectedId) {
                R.id.chip_type_manual -> RuleReplyTypeFilter.MANUAL
                R.id.chip_type_ai -> RuleReplyTypeFilter.AI
                else -> RuleReplyTypeFilter.ALL
            }
            viewModel.setReplyTypeFilter(filter)
        }

        binding.chipGroupCategoryFilter.setOnCheckedStateChangeListener { group, checkedIds ->
            if (isUpdatingCategoryChips) return@setOnCheckedStateChangeListener
            val checkedId = checkedIds.firstOrNull()
            val chip = if (checkedId != null) group.findViewById<com.google.android.material.chip.Chip>(checkedId) else null
            val category = chip?.tag as? String
            viewModel.setSelectedCategory(category)
        }

        binding.fabAddRule.setOnClickListener { openCreateRule() }
        binding.btnEmptyCreateRule.setOnClickListener { openCreateRule() }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.tvTotalRulesCount.text = state.totalRules.toString()
                    binding.tvActiveRulesCount.text = state.activeRules.toString()
                    binding.tvInactiveRulesCount.text = state.inactiveRules.toString()

                    renderCategoryChipsIfNeeded(state.availableCategories, state.selectedCategory)
                    updateCategoryBulkBar(state)

                    adapter.submitList(state.displayedRules)

                    val showEmpty = state.displayedRules.isEmpty()
                    binding.emptyStateContainer.visibility = if (showEmpty) View.VISIBLE else View.GONE
                    binding.rulesRecyclerView.visibility = if (showEmpty) View.GONE else View.VISIBLE

                    if (state.totalRules == 0) {
                        binding.tvEmptyTitle.setText(R.string.empty_rules_title)
                        binding.tvEmptySubtitle.setText(R.string.empty_rules_subtitle)
                        binding.btnEmptyCreateRule.visibility = View.VISIBLE
                    } else {
                        binding.tvEmptyTitle.setText(R.string.empty_filtered_rules)
                        binding.tvEmptySubtitle.text = ""
                        binding.btnEmptyCreateRule.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun renderCategoryChipsIfNeeded(categories: List<String>, selectedCategory: String?) {
        if (categories.isEmpty()) {
            binding.categoryFilterScroll.visibility = View.GONE
            renderedCategories = emptyList()
            return
        }

        binding.categoryFilterScroll.visibility = View.VISIBLE
        if (renderedCategories == categories) {
            isUpdatingCategoryChips = true
            for (i in 0 until binding.chipGroupCategoryFilter.childCount) {
                val chip = binding.chipGroupCategoryFilter.getChildAt(i) as? com.google.android.material.chip.Chip ?: continue
                val chipTag = chip.tag as? String
                chip.isChecked = if (selectedCategory == null) {
                    chipTag == null
                } else {
                    chipTag != null && chipTag.equals(selectedCategory, ignoreCase = true)
                }
            }
            isUpdatingCategoryChips = false
            return
        }

        renderedCategories = categories
        isUpdatingCategoryChips = true
        binding.chipGroupCategoryFilter.removeAllViews()

        val allChip = com.google.android.material.chip.Chip(this).apply {
            id = View.generateViewId()
            text = getString(R.string.rule_category_all)
            tag = null
            isCheckable = true
            isChecked = selectedCategory == null
        }
        binding.chipGroupCategoryFilter.addView(allChip)

        for (category in categories) {
            val chip = com.google.android.material.chip.Chip(this).apply {
                id = View.generateViewId()
                text = category
                tag = category
                isCheckable = true
                isChecked = selectedCategory != null && selectedCategory.equals(category, ignoreCase = true)
            }
            binding.chipGroupCategoryFilter.addView(chip)
        }
        isUpdatingCategoryChips = false
    }

    private fun updateCategoryBulkBar(state: RuleDashboardUiState) {
        val cat = state.selectedCategory
        if (cat.isNullOrBlank()) {
            binding.categoryBulkBar.visibility = View.GONE
            return
        }
        binding.categoryBulkBar.visibility = View.VISIBLE
        val shouldPauseAll = state.selectedCategoryHasActiveRules
        binding.btnToggleCategoryRules.text = getString(
            if (shouldPauseAll) R.string.rule_category_pause_all else R.string.rule_category_enable_all,
            cat
        )
        binding.btnToggleCategoryRules.setOnClickListener {
            val enableTarget = !shouldPauseAll
            viewModel.setCategoryRulesEnabled(cat, enableTarget)
            Toast.makeText(
                this,
                getString(
                    if (enableTarget) R.string.rule_category_enabled_toast else R.string.rule_category_paused_toast,
                    cat
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateListenerWarning() {
        val enabled = isNotificationListenerEnabled() && preferencesManager.isServiceEnabled
        binding.listenerWarningCard.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, NotificationService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun openCreateRule() {
        val intent = Intent(this, CreateEditRuleActivity::class.java).apply {
            if (viewModel.uiState.value.replyTypeFilter == RuleReplyTypeFilter.MANUAL) {
                putExtra(CreateEditRuleActivity.EXTRA_MANUAL_RULE_ONLY, true)
            }
        }
        startActivity(intent)
    }

    override fun onToggleEnabled(rule: AutoReplyRule, enabled: Boolean) {
        viewModel.toggleRuleEnabled(rule, enabled)
    }

    override fun onEditRule(rule: AutoReplyRule) {
        val intent = Intent(this, CreateEditRuleActivity::class.java).apply {
            putExtra(CreateEditRuleActivity.EXTRA_RULE_ID, rule.id)
        }
        startActivity(intent)
    }

    override fun onDeleteRule(rule: AutoReplyRule) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_rule_title)
            .setMessage(getString(R.string.delete_rule_message, rule.name))
            .setPositiveButton(R.string.rule_delete) { _, _ ->
                viewModel.deleteRule(rule)
                Toast.makeText(this, R.string.rule_deleted_toast, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDuplicateRule(rule: AutoReplyRule) {
        viewModel.duplicateRule(rule)
        Toast.makeText(this, R.string.rule_duplicated_toast, Toast.LENGTH_SHORT).show()
    }

    override fun onAdjustPriority(rule: AutoReplyRule, delta: Int) {
        viewModel.adjustPriority(rule, delta)
    }

    companion object {
        const val EXTRA_REPLY_TYPE_FILTER = "extra_reply_type_filter"
    }
}
