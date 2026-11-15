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
        startActivity(Intent(this, CreateEditRuleActivity::class.java))
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
}
