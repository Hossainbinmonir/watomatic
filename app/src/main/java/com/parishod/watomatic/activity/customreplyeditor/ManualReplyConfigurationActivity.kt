package com.parishod.watomatic.activity.customreplyeditor

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.parishod.watomatic.R
import com.parishod.watomatic.activity.BaseActivity
import com.parishod.watomatic.activity.rules.CreateEditRuleActivity
import com.parishod.watomatic.activity.rules.RuleDashboardActivity
import com.parishod.watomatic.activity.rules.RuleReplyTypeFilter
import com.parishod.watomatic.model.CustomRepliesData
import com.parishod.watomatic.model.rules.AutoReplyRule
import com.parishod.watomatic.model.rules.AutoReplyRulesDatabase
import com.parishod.watomatic.model.rules.MatchType
import com.parishod.watomatic.model.rules.RuleRepository
import com.parishod.watomatic.model.utils.Constants
import com.parishod.watomatic.utils.UnsavedChangesDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class ManualReplyConfigurationActivity : BaseActivity() {

    private lateinit var customRepliesData: CustomRepliesData
    private lateinit var repository: RuleRepository

    private lateinit var tilDefaultReply: TextInputLayout
    private lateinit var etDefaultReply: TextInputEditText
    private lateinit var btnSaveDefaultReply: MaterialButton
    private lateinit var btnCreateManualRule: MaterialButton
    private lateinit var btnManageManualRules: MaterialButton
    private lateinit var tvManualRulesSummary: TextView
    private lateinit var emptyStateContainer: View
    private lateinit var rulesListContainer: LinearLayout

    private var initialReplyText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_manual_reply_configuration)

        customRepliesData = CustomRepliesData.getInstance(this)
        val db = AutoReplyRulesDatabase.getInstance(applicationContext)
        repository = RuleRepository(db.ruleDao(), db.executionLogDao())

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.manual_config_scroll_view)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            insets
        }

        bindViews()
        setupListeners()
        setupBackNavigation()
        observeManualRules()
    }

    private fun bindViews() {
        tilDefaultReply = findViewById(R.id.til_default_manual_reply)
        etDefaultReply = findViewById(R.id.et_default_manual_reply)
        btnSaveDefaultReply = findViewById(R.id.btn_save_default_manual_reply)
        btnCreateManualRule = findViewById(R.id.btn_create_manual_rule)
        btnManageManualRules = findViewById(R.id.btn_manage_manual_rules)
        tvManualRulesSummary = findViewById(R.id.tv_manual_rules_summary)
        emptyStateContainer = findViewById(R.id.manual_rules_empty_state)
        rulesListContainer = findViewById(R.id.manual_rules_list_container)

        initialReplyText = customRepliesData.get().orEmpty()
        etDefaultReply.setText(initialReplyText)
    }

    private fun setupListeners() {
        btnCreateManualRule.setOnClickListener {
            val intent = Intent(this, CreateEditRuleActivity::class.java).apply {
                putExtra(CreateEditRuleActivity.EXTRA_MANUAL_RULE_ONLY, true)
            }
            startActivity(intent)
        }

        btnManageManualRules.setOnClickListener {
            val intent = Intent(this, RuleDashboardActivity::class.java).apply {
                putExtra(RuleDashboardActivity.EXTRA_REPLY_TYPE_FILTER, RuleReplyTypeFilter.MANUAL.name)
            }
            startActivity(intent)
        }

        btnSaveDefaultReply.setOnClickListener {
            saveDefaultReplyMessage()
        }
    }

    private fun saveDefaultReplyMessage() {
        val text = etDefaultReply.text
        if (!CustomRepliesData.isValidCustomReply(text)) {
            tilDefaultReply.error = getString(R.string.manual_reply_message_invalid_toast)
            return
        }
        tilDefaultReply.error = null
        customRepliesData.set(text)
        initialReplyText = text?.toString().orEmpty()
        setResult(RESULT_OK)
        Toast.makeText(this, R.string.manual_reply_message_saved_toast, Toast.LENGTH_SHORT).show()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentText = etDefaultReply.text?.toString().orEmpty()
                if (currentText != initialReplyText) {
                    UnsavedChangesDialog.show(
                        context = this@ManualReplyConfigurationActivity,
                        onDiscard = { finish() }
                    )
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun observeManualRules() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.allRules.collect { allRules ->
                    val manualRules = allRules.filter { !it.useAiReply }
                    renderManualRules(manualRules)
                }
            }
        }
    }

    private fun renderManualRules(manualRules: List<AutoReplyRule>) {
        val activeCount = manualRules.count { it.isEnabled }
        tvManualRulesSummary.text = getString(
            R.string.manual_reply_rules_count_summary,
            activeCount,
            manualRules.size
        )

        rulesListContainer.removeAllViews()
        if (manualRules.isEmpty()) {
            emptyStateContainer.visibility = View.VISIBLE
            rulesListContainer.visibility = View.GONE
            return
        }

        emptyStateContainer.visibility = View.GONE
        rulesListContainer.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(this)
        val appNameMap = Constants.SUPPORTED_APPS.associate { it.packageName to it.name }

        for (rule in manualRules) {
            val itemView = inflater.inflate(R.layout.item_manual_rule_compact, rulesListContainer, false)
            val card = itemView.findViewById<MaterialCardView>(R.id.compact_rule_card)
            val tvStatus = itemView.findViewById<TextView>(R.id.tv_compact_rule_status)
            val tvPriority = itemView.findViewById<TextView>(R.id.tv_compact_rule_priority)
            val tvName = itemView.findViewById<TextView>(R.id.tv_compact_rule_name)
            val tvCondition = itemView.findViewById<TextView>(R.id.tv_compact_rule_condition)
            val tvReply = itemView.findViewById<TextView>(R.id.tv_compact_rule_reply)
            val switchEnabled = itemView.findViewById<MaterialSwitch>(R.id.switch_compact_rule_enabled)

            tvName.text = rule.name
            tvPriority.text = getString(R.string.rule_priority_badge, rule.priority)

            if (rule.isEnabled) {
                tvStatus.text = getString(R.string.rule_status_active).uppercase(Locale.getDefault())
                tvStatus.setBackgroundResource(R.drawable.bg_badge_active)
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_active_text))
                card.alpha = 1.0f
            } else {
                tvStatus.text = getString(R.string.rule_status_inactive).uppercase(Locale.getDefault())
                tvStatus.setBackgroundResource(R.drawable.bg_badge_gray)
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_gray_text))
                card.alpha = 0.82f
            }

            val appsLabel = if (rule.selectedPackages.isEmpty()) {
                getString(R.string.error_select_one_app)
            } else {
                rule.selectedPackages.joinToString(", ") { pkg -> appNameMap[pkg] ?: pkg }
            }
            val matchLabel = getMatchTypeLabel(rule.matchType)
            val kwLabel = if (rule.keywords.isEmpty()) "-" else rule.keywords.joinToString(", ")
            tvCondition.text = "$appsLabel • $matchLabel: $kwLabel"
            tvReply.text = rule.replyMessage

            switchEnabled.setOnCheckedChangeListener(null)
            switchEnabled.isChecked = rule.isEnabled
            switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.setRuleEnabled(rule.id, isChecked)
                }
            }

            card.setOnClickListener {
                val intent = Intent(this, CreateEditRuleActivity::class.java).apply {
                    putExtra(CreateEditRuleActivity.EXTRA_RULE_ID, rule.id)
                    putExtra(CreateEditRuleActivity.EXTRA_MANUAL_RULE_ONLY, true)
                }
                startActivity(intent)
            }

            rulesListContainer.addView(itemView)
        }
    }

    private fun getMatchTypeLabel(type: MatchType): String {
        return when (type) {
            MatchType.EXACT -> getString(R.string.match_exact)
            MatchType.CONTAINS -> getString(R.string.match_contains)
            MatchType.STARTS_WITH -> getString(R.string.match_starts_with)
            MatchType.ENDS_WITH -> getString(R.string.match_ends_with)
            MatchType.DOES_NOT_CONTAIN -> getString(R.string.match_does_not_contain)
            MatchType.ANY_KEYWORD -> getString(R.string.match_any_keyword)
            MatchType.ALL_KEYWORDS -> getString(R.string.match_all_keywords)
            MatchType.REGEX -> getString(R.string.match_regex)
        }
    }
}
