package com.parishod.watomatic.activity.customreplyeditor

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.parishod.watomatic.R
import com.parishod.watomatic.activity.BaseActivity
import com.parishod.watomatic.activity.contactselector.ContactSelectorActivity
import com.parishod.watomatic.activity.rules.RuleDashboardActivity
import com.parishod.watomatic.model.App
import com.parishod.watomatic.model.CustomRepliesData
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.rules.AutoReplyRulesDatabase
import com.parishod.watomatic.model.utils.AppUtils
import com.parishod.watomatic.model.utils.Constants
import com.parishod.watomatic.utils.UnsavedChangesDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ManualReplyConfigurationActivity : BaseActivity() {

    companion object {
        private const val MIN_COOLDOWN_SECONDS = 10L
    }

    private lateinit var customRepliesData: CustomRepliesData
    private lateinit var preferencesManager: PreferencesManager

    private lateinit var cardRulesOverrideNotice: View
    private lateinit var btnOpenRulesFallback: MaterialButton

    private lateinit var tilDefaultReply: TextInputLayout
    private lateinit var etDefaultReply: TextInputEditText

    private lateinit var tilCooldown: TextInputLayout
    private lateinit var etCooldownSeconds: TextInputEditText
    private lateinit var chipGroupCooldownPresets: ChipGroup

    private lateinit var chipGroupConversationType: ChipGroup
    private lateinit var chipConvDirect: Chip
    private lateinit var chipConvBoth: Chip

    private lateinit var tvContactsStatus: TextView
    private lateinit var switchContactFilter: MaterialSwitch
    private lateinit var btnChooseContacts: MaterialButton

    private lateinit var btnSelectAllApps: MaterialButton
    private lateinit var tvAppsError: TextView
    private lateinit var appsContainer: LinearLayout

    private lateinit var btnSaveConfig: MaterialButton

    private val selectedPackages = linkedSetOf<String>()
    private val appCheckBoxes = linkedMapOf<String, MaterialCheckBox>()
    private var isPopulating = false

    private var initialReplyText: String = ""
    private var initialCooldownSeconds: Long = MIN_COOLDOWN_SECONDS
    private var initialGroupEnabled: Boolean = false
    private var initialContactFilterEnabled: Boolean = false
    private var initialSelectedPackages: Set<String> = emptySet()

    private val contactSelectorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshContactsFromPrefs()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_manual_reply_configuration)

        customRepliesData = CustomRepliesData.getInstance(this)
        preferencesManager = PreferencesManager.getPreferencesInstance(this)

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
        setupApplicationsSection()
        prefillState()
        setupListeners()
        setupBackNavigation()
    }

    override fun onResume() {
        super.onResume()
        refreshContactsFromPrefs()
        refreshOverrideNotice()
    }

    private fun bindViews() {
        cardRulesOverrideNotice = findViewById(R.id.card_rules_override_notice)
        btnOpenRulesFallback = findViewById(R.id.btn_open_rules_fallback)

        tilDefaultReply = findViewById(R.id.til_default_manual_reply)
        etDefaultReply = findViewById(R.id.et_default_manual_reply)

        tilCooldown = findViewById(R.id.til_manual_cooldown)
        etCooldownSeconds = findViewById(R.id.et_manual_cooldown_seconds)
        chipGroupCooldownPresets = findViewById(R.id.chip_group_manual_cooldown_presets)

        chipGroupConversationType = findViewById(R.id.chip_group_manual_conversation_type)
        chipConvDirect = findViewById(R.id.chip_manual_conv_direct)
        chipConvBoth = findViewById(R.id.chip_manual_conv_both)

        tvContactsStatus = findViewById(R.id.tv_manual_contacts_status)
        switchContactFilter = findViewById(R.id.switch_manual_contact_filter)
        btnChooseContacts = findViewById(R.id.btn_choose_manual_contacts)

        btnSelectAllApps = findViewById(R.id.btn_manual_select_all_apps)
        tvAppsError = findViewById(R.id.tv_manual_apps_error)
        appsContainer = findViewById(R.id.manual_apps_container)

        btnSaveConfig = findViewById(R.id.btn_save_manual_config)
    }

    private fun setupApplicationsSection() {
        val appUtils = AppUtils.getInstance(this)
        val inflater = LayoutInflater.from(this)
        appsContainer.removeAllViews()
        appCheckBoxes.clear()

        val sortedApps = Constants.SUPPORTED_APPS.sortedBy { it.name }
        for (app in sortedApps) {
            val row = inflater.inflate(R.layout.item_rule_app_selector, appsContainer, false)
            val ivIcon = row.findViewById<ImageView>(R.id.iv_app_icon)
            val tvName = row.findViewById<TextView>(R.id.tv_app_name)
            val tvStatus = row.findViewById<TextView>(R.id.tv_app_status)
            val checkBox = row.findViewById<MaterialCheckBox>(R.id.cb_app_selected)

            val installed = appUtils.isPackageInstalled(app.packageName)
            tvName.text = app.name
            tvStatus.text = getString(if (installed) R.string.app_installed else R.string.app_not_installed)
            ivIcon.setImageDrawable(loadAppIconOrFallback(app))

            appCheckBoxes[app.packageName] = checkBox
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (isPopulating) return@setOnCheckedChangeListener
                if (isChecked) {
                    selectedPackages.add(app.packageName)
                    tvAppsError.visibility = View.GONE
                } else {
                    selectedPackages.remove(app.packageName)
                }
                updateSelectAllAppsButtonText()
            }

            row.setOnClickListener {
                checkBox.isChecked = !checkBox.isChecked
            }
            appsContainer.addView(row)
        }
    }

    private fun loadAppIconOrFallback(app: App): Drawable? {
        return try {
            packageManager.getApplicationIcon(app.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            ContextCompat.getDrawable(this, R.drawable.ic_app_window)
        }
    }

    private fun prefillState() {
        isPopulating = true

        initialReplyText = customRepliesData.get().orEmpty()
        etDefaultReply.setText(initialReplyText)

        val savedDelaySec = (preferencesManager.autoReplyDelay / 1000L).coerceAtLeast(MIN_COOLDOWN_SECONDS)
        initialCooldownSeconds = savedDelaySec
        etCooldownSeconds.setText(savedDelaySec.toString())
        syncCooldownPresetChip(savedDelaySec)

        initialGroupEnabled = preferencesManager.isGroupReplyEnabled
        if (initialGroupEnabled) {
            chipConvBoth.isChecked = true
        } else {
            chipConvDirect.isChecked = true
        }

        initialContactFilterEnabled = preferencesManager.isContactReplyEnabled
        switchContactFilter.isChecked = initialContactFilterEnabled
        updateContactsStatusText(initialContactFilterEnabled)

        val enabledApps = preferencesManager.enabledApps?.toSet().orEmpty()
        selectedPackages.clear()
        selectedPackages.addAll(enabledApps)
        initialSelectedPackages = selectedPackages.toSet()
        syncAppCheckBoxes()

        isPopulating = false
    }

    private fun setupListeners() {
        btnOpenRulesFallback.setOnClickListener {
            startActivity(Intent(this, RuleDashboardActivity::class.java))
        }

        chipGroupCooldownPresets.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isPopulating) return@setOnCheckedStateChangeListener
            val presetSeconds = when (checkedIds.firstOrNull()) {
                R.id.chip_manual_cd_min -> 10L
                R.id.chip_manual_cd_30s -> 30L
                R.id.chip_manual_cd_1m -> 60L
                R.id.chip_manual_cd_5m -> 300L
                R.id.chip_manual_cd_30m -> 1800L
                R.id.chip_manual_cd_1h -> 3600L
                else -> null
            }
            if (presetSeconds != null) {
                isPopulating = true
                etCooldownSeconds.setText(presetSeconds.toString())
                etCooldownSeconds.setSelection(etCooldownSeconds.text?.length ?: 0)
                isPopulating = false
            }
        }

        etCooldownSeconds.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isPopulating) return
                val sec = s?.toString()?.toLongOrNull() ?: -1L
                isPopulating = true
                syncCooldownPresetChip(sec)
                isPopulating = false
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        switchContactFilter.setOnCheckedChangeListener { _, isChecked ->
            if (isPopulating) return@setOnCheckedChangeListener
            updateContactsStatusText(isChecked)
        }

        btnChooseContacts.setOnClickListener {
            preferencesManager.setContactReplyEnabled(switchContactFilter.isChecked)
            contactSelectorLauncher.launch(Intent(this, ContactSelectorActivity::class.java))
        }

        btnSelectAllApps.setOnClickListener {
            val allSelected = selectedPackages.size == Constants.SUPPORTED_APPS.size
            if (allSelected) {
                selectedPackages.clear()
            } else {
                Constants.SUPPORTED_APPS.forEach { selectedPackages.add(it.packageName) }
                tvAppsError.visibility = View.GONE
            }
            syncAppCheckBoxes()
        }

        btnSaveConfig.setOnClickListener {
            saveConfiguration()
        }
    }

    private fun refreshContactsFromPrefs() {
        val enabled = preferencesManager.isContactReplyEnabled
        isPopulating = true
        switchContactFilter.isChecked = enabled
        initialContactFilterEnabled = enabled
        updateContactsStatusText(enabled)
        isPopulating = false
    }

    private fun updateContactsStatusText(filterEnabled: Boolean) {
        if (!filterEnabled) {
            tvContactsStatus.text = getString(R.string.manual_contacts_all)
            return
        }
        val selectedCount =
            (preferencesManager.replyToNames?.size ?: 0) + (preferencesManager.customReplyNames?.size ?: 0)
        tvContactsStatus.text = if (preferencesManager.isContactReplyBlacklistMode()) {
            getString(R.string.manual_contacts_blacklist_summary, selectedCount)
        } else {
            getString(R.string.manual_contacts_whitelist_summary, selectedCount)
        }
    }

    private fun refreshOverrideNotice() {
        lifecycleScope.launch {
            val hasOverridingRules = withContext(Dispatchers.IO) {
                val totalRules = AutoReplyRulesDatabase.getInstance(applicationContext)
                    .ruleDao()
                    .getAllRulesOnce()
                    .size
                totalRules > 0 && !preferencesManager.isRuleFallbackToGlobalEnabled
            }
            cardRulesOverrideNotice.visibility = if (hasOverridingRules) View.VISIBLE else View.GONE
        }
    }

    private fun syncCooldownPresetChip(seconds: Long) {
        val targetId = when (seconds) {
            10L -> R.id.chip_manual_cd_min
            30L -> R.id.chip_manual_cd_30s
            60L -> R.id.chip_manual_cd_1m
            300L -> R.id.chip_manual_cd_5m
            1800L -> R.id.chip_manual_cd_30m
            3600L -> R.id.chip_manual_cd_1h
            else -> View.NO_ID
        }
        if (targetId != View.NO_ID) {
            findViewById<Chip>(targetId).isChecked = true
        } else {
            chipGroupCooldownPresets.clearCheck()
        }
    }

    private fun syncAppCheckBoxes() {
        isPopulating = true
        appCheckBoxes.forEach { (pkg, cb) ->
            cb.isChecked = pkg in selectedPackages
        }
        updateSelectAllAppsButtonText()
        isPopulating = false
    }

    private fun updateSelectAllAppsButtonText() {
        val allSelected = selectedPackages.size == Constants.SUPPORTED_APPS.size
        btnSelectAllApps.setText(if (allSelected) R.string.clear_all_apps else R.string.select_all_apps)
    }

    private fun saveConfiguration() {
        val replyText = etDefaultReply.text
        if (!CustomRepliesData.isValidCustomReply(replyText)) {
            tilDefaultReply.error = getString(R.string.manual_reply_message_invalid_toast)
            return
        }
        tilDefaultReply.error = null

        if (selectedPackages.isEmpty()) {
            tvAppsError.visibility = View.VISIBLE
            Toast.makeText(this, R.string.error_select_one_app, Toast.LENGTH_SHORT).show()
            return
        }
        tvAppsError.visibility = View.GONE

        val rawCooldown = etCooldownSeconds.text?.toString()?.trim()?.toLongOrNull() ?: MIN_COOLDOWN_SECONDS
        val effectiveCooldown = rawCooldown.coerceAtLeast(MIN_COOLDOWN_SECONDS)
        if (rawCooldown < MIN_COOLDOWN_SECONDS) {
            isPopulating = true
            etCooldownSeconds.setText(effectiveCooldown.toString())
            syncCooldownPresetChip(effectiveCooldown)
            isPopulating = false
            Toast.makeText(this, R.string.manual_cooldown_min_adjusted_toast, Toast.LENGTH_SHORT).show()
        }

        val groupReplyEnabled = chipGroupConversationType.checkedChipId == R.id.chip_manual_conv_both
        val contactFilterEnabled = switchContactFilter.isChecked

        customRepliesData.set(replyText)
        preferencesManager.setAutoReplyDelay(effectiveCooldown * 1000L)
        preferencesManager.setGroupReplyPref(groupReplyEnabled)
        preferencesManager.setContactReplyEnabled(contactFilterEnabled)

        // Enable selected apps first so enabledApps JSON never temporarily becomes "[]"
        // (which triggers PreferencesManager.getEnabledApps() legacy WhatsApp fallback).
        selectedPackages.forEach { pkg ->
            preferencesManager.saveEnabledApps(pkg, true)
        }
        Constants.SUPPORTED_APPS
            .filter { it.packageName !in selectedPackages }
            .forEach { app ->
                preferencesManager.saveEnabledApps(app.packageName, false)
            }

        initialReplyText = replyText?.toString().orEmpty()
        initialCooldownSeconds = effectiveCooldown
        initialGroupEnabled = groupReplyEnabled
        initialContactFilterEnabled = contactFilterEnabled
        initialSelectedPackages = selectedPackages.toSet()

        setResult(RESULT_OK)
        Toast.makeText(this, R.string.manual_config_saved_toast, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun hasUnsavedChanges(): Boolean {
        val currentText = etDefaultReply.text?.toString().orEmpty()
        val currentCooldown = etCooldownSeconds.text?.toString()?.trim()?.toLongOrNull() ?: -1L
        val currentGroupEnabled = chipGroupConversationType.checkedChipId == R.id.chip_manual_conv_both
        val currentContactEnabled = switchContactFilter.isChecked
        return currentText != initialReplyText ||
            currentCooldown != initialCooldownSeconds ||
            currentGroupEnabled != initialGroupEnabled ||
            currentContactEnabled != initialContactFilterEnabled ||
            selectedPackages != initialSelectedPackages
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hasUnsavedChanges()) {
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
}
