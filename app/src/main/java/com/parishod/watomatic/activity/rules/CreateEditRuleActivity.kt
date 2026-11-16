package com.parishod.watomatic.activity.rules

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.parishod.watomatic.R
import com.parishod.watomatic.activity.BaseActivity
import com.parishod.watomatic.databinding.ActivityCreateEditRuleBinding
import com.parishod.watomatic.model.App
import com.parishod.watomatic.model.rules.AutoReplyRule
import com.parishod.watomatic.model.rules.AutoReplyRulesDatabase
import com.parishod.watomatic.model.rules.ContactFilterMode
import com.parishod.watomatic.model.rules.GroupFilterMode
import com.parishod.watomatic.model.rules.MatchType
import com.parishod.watomatic.model.rules.RuleRepository
import com.parishod.watomatic.model.utils.AppUtils
import com.parishod.watomatic.model.utils.Constants
import com.parishod.watomatic.model.utils.ContactsHelper
import com.parishod.watomatic.service.rules.RuleMatchingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class CreateEditRuleActivity : BaseActivity() {

    private lateinit var binding: ActivityCreateEditRuleBinding
    private lateinit var repository: RuleRepository

    private var editingRuleId: Long = -1L
    private var existingRule: AutoReplyRule? = null

    private val selectedPackages = linkedSetOf<String>()
    private val appCheckBoxes = mutableMapOf<String, MaterialCheckBox>()
    private val keywords = mutableListOf<String>()
    private val filterContacts = mutableListOf<String>()

    private var selectedMatchType: MatchType = MatchType.CONTAINS
    private var selectedFrequencyWindowMinutes: Int = 1440
    private var startTimeMinutes: Int = 9 * 60
    private var endTimeMinutes: Int = 18 * 60
    private var isDirty: Boolean = false
    private var isPopulating: Boolean = false

    private val contactPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showDeviceContactsPicker()
        } else {
            Toast.makeText(this, R.string.contacts_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityCreateEditRuleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = AutoReplyRulesDatabase.getInstance(this)
        repository = RuleRepository(db.ruleDao(), db.executionLogDao())

        ViewCompat.setOnApplyWindowInsetsListener(binding.createEditRuleRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        editingRuleId = intent.getLongExtra(EXTRA_RULE_ID, -1L)

        setupToolbar()
        setupCategorySection()
        setupApplicationsSection()
        setupMatchConditionsSection()
        setupKeywordsSection()
        setupAdvancedSection()
        setupContactAndGroupSection()
        setupScheduleSection()
        setupSaveAndBackHandling()

        if (editingRuleId > 0L) {
            loadExistingRule(editingRuleId)
        } else {
            // Default to WhatsApp for new rules if available
            selectedPackages.add("com.whatsapp")
            syncAppCheckBoxes()
            updateTimeButtons()
            isDirty = false
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(
            if (editingRuleId > 0L) R.string.edit_rule_title else R.string.create_rule_title
        )
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupCategorySection() {
        val defaultSuggestions = listOf(
            getString(R.string.category_business),
            getString(R.string.category_support),
            getString(R.string.category_sales),
            getString(R.string.category_personal),
            getString(R.string.category_office)
        )
        lifecycleScope.launch {
            val savedCategories = withContext(Dispatchers.IO) {
                repository.getAllRulesOnce()
                    .map { it.category.trim() }
                    .filter { it.isNotEmpty() }
            }
            val merged = (savedCategories + defaultSuggestions)
                .distinctBy { it.lowercase() }
            renderCategorySuggestions(merged)
        }
    }

    private fun renderCategorySuggestions(suggestions: List<String>) {
        binding.chipGroupCategorySuggestions.removeAllViews()
        val currentCat = binding.etRuleCategory.text?.toString()?.trim().orEmpty()
        for (cat in suggestions) {
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = cat
                isCheckable = true
                isChecked = currentCat.equals(cat, ignoreCase = true)
                setOnClickListener {
                    isPopulating = true
                    if (binding.etRuleCategory.text?.toString()?.trim().equals(cat, ignoreCase = true)) {
                        binding.etRuleCategory.setText("")
                        isChecked = false
                    } else {
                        binding.etRuleCategory.setText(cat)
                        binding.etRuleCategory.setSelection(cat.length)
                    }
                    isPopulating = false
                    markDirty()
                }
            }
            binding.chipGroupCategorySuggestions.addView(chip)
        }
    }

    private fun setupApplicationsSection() {
        val appUtils = AppUtils.getInstance(this)
        val inflater = LayoutInflater.from(this)
        binding.appsContainer.removeAllViews()
        appCheckBoxes.clear()

        val sortedApps = Constants.SUPPORTED_APPS.sortedBy { it.name }
        for (app in sortedApps) {
            val row = inflater.inflate(R.layout.item_rule_app_selector, binding.appsContainer, false)
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
                if (isChecked) {
                    selectedPackages.add(app.packageName)
                    binding.tvAppsError.visibility = View.GONE
                } else {
                    selectedPackages.remove(app.packageName)
                }
                updateSelectAllAppsButtonText()
                markDirty()
            }

            row.setOnClickListener {
                checkBox.isChecked = !checkBox.isChecked
            }
            binding.appsContainer.addView(row)
        }

        binding.btnSelectAllApps.setOnClickListener {
            val allSelected = selectedPackages.size == Constants.SUPPORTED_APPS.size
            if (allSelected) {
                selectedPackages.clear()
            } else {
                Constants.SUPPORTED_APPS.forEach { selectedPackages.add(it.packageName) }
            }
            syncAppCheckBoxes()
            markDirty()
        }
    }

    private fun loadAppIconOrFallback(app: App): Drawable? {
        return try {
            packageManager.getApplicationIcon(app.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            ContextCompat.getDrawable(this, R.drawable.ic_app_window)
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
        binding.btnSelectAllApps.setText(if (allSelected) R.string.clear_all_apps else R.string.select_all_apps)
    }

    private fun setupMatchConditionsSection() {
        val matchOptions = MatchType.entries.map { matchType ->
            matchType to getMatchTypeLabel(matchType)
        }
        val labels = matchOptions.map { it.second }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        binding.actvMatchType.setAdapter(adapter)
        binding.actvMatchType.setText(getMatchTypeLabel(selectedMatchType), false)
        updateMatchTypeDescription()

        binding.actvMatchType.setOnItemClickListener { _, _, position, _ ->
            selectedMatchType = matchOptions[position].first
            updateMatchTypeDescription()
            validateKeywordsLive()
            markDirty()
        }

        binding.switchCaseSensitive.setOnCheckedChangeListener { _, _ -> markDirty() }
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

    private fun updateMatchTypeDescription() {
        val descRes = when (selectedMatchType) {
            MatchType.EXACT -> R.string.match_desc_exact
            MatchType.CONTAINS -> R.string.match_desc_contains
            MatchType.STARTS_WITH -> R.string.match_desc_starts_with
            MatchType.ENDS_WITH -> R.string.match_desc_ends_with
            MatchType.DOES_NOT_CONTAIN -> R.string.match_desc_does_not_contain
            MatchType.ANY_KEYWORD -> R.string.match_desc_any_keyword
            MatchType.ALL_KEYWORDS -> R.string.match_desc_all_keywords
            MatchType.REGEX -> R.string.match_desc_regex
        }
        binding.tvMatchTypeDesc.setText(descRes)
    }

    private fun setupKeywordsSection() {
        binding.btnAddKeyword.setOnClickListener {
            addKeywordsFromInput()
        }
        binding.etKeywordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addKeywordsFromInput()
                true
            } else {
                false
            }
        }
    }

    private fun addKeywordsFromInput() {
        val raw = binding.etKeywordInput.text?.toString().orEmpty()
        if (raw.isBlank()) return

        val parts = if (selectedMatchType == MatchType.REGEX) {
            listOf(raw.trim())
        } else {
            raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }

        for (part in parts) {
            if (selectedMatchType == MatchType.REGEX && !RuleMatchingEngine.isValidRegex(part)) {
                binding.tilKeywordInput.error = getString(R.string.regex_invalid_error)
                return
            }
            if (keywords.none { it == part }) {
                keywords.add(part)
            }
        }
        binding.tilKeywordInput.error = null
        binding.etKeywordInput.setText("")
        binding.tvKeywordsError.visibility = View.GONE
        renderKeywordChips()
        markDirty()
    }

    private fun renderKeywordChips() {
        binding.chipGroupKeywords.removeAllViews()
        for (kw in keywords) {
            val chip = Chip(this).apply {
                text = kw
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    keywords.remove(kw)
                    renderKeywordChips()
                    markDirty()
                }
            }
            binding.chipGroupKeywords.addView(chip)
        }
    }

    private fun validateKeywordsLive(): Boolean {
        if (selectedMatchType == MatchType.REGEX) {
            val invalid = keywords.firstOrNull { !RuleMatchingEngine.isValidRegex(it) }
            if (invalid != null) {
                binding.tvKeywordsError.text = getString(R.string.regex_invalid_error) + ": $invalid"
                binding.tvKeywordsError.visibility = View.VISIBLE
                return false
            }
        }
        binding.tvKeywordsError.visibility = View.GONE
        return true
    }

    private fun setupAdvancedSection() {
        val windowOptions = listOf(
            60 to getString(R.string.window_hourly),
            1440 to getString(R.string.window_daily),
            0 to getString(R.string.window_all_time)
        )
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            windowOptions.map { it.second }
        )
        binding.actvFrequencyWindow.setAdapter(adapter)
        binding.actvFrequencyWindow.setText(getString(R.string.window_daily), false)
        binding.actvFrequencyWindow.setOnItemClickListener { _, _, position, _ ->
            selectedFrequencyWindowMinutes = windowOptions[position].first
            markDirty()
        }

        val simpleWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = markDirty()
        }
        binding.etRuleName.addTextChangedListener(simpleWatcher)
        binding.etRuleCategory.addTextChangedListener(simpleWatcher)
        binding.etReplyMessage.addTextChangedListener(simpleWatcher)
        binding.etPriority.addTextChangedListener(simpleWatcher)
        binding.etReplyDelay.addTextChangedListener(simpleWatcher)
        binding.etCooldown.addTextChangedListener(simpleWatcher)
        binding.etFrequencyLimit.addTextChangedListener(simpleWatcher)
    }

    private fun setupContactAndGroupSection() {
        binding.chipGroupConversationType.setOnCheckedStateChangeListener { _, _ -> markDirty() }

        binding.chipGroupContactMode.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: R.id.chip_contact_all
            val showContacts = id != R.id.chip_contact_all
            binding.contactNamesContainer.visibility = if (showContacts) View.VISIBLE else View.GONE
            markDirty()
        }

        binding.btnAddContactName.setOnClickListener { addContactFromInput() }
        binding.etContactNameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addContactFromInput()
                true
            } else {
                false
            }
        }

        binding.btnPickDeviceContacts.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                showDeviceContactsPicker()
            } else {
                contactPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }
    }

    private fun addContactFromInput() {
        val raw = binding.etContactNameInput.text?.toString().orEmpty()
        if (raw.isBlank()) return
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { name ->
            if (filterContacts.none { it.equals(name, ignoreCase = true) }) {
                filterContacts.add(name)
            }
        }
        binding.etContactNameInput.setText("")
        renderContactChips()
        markDirty()
    }

    private fun showDeviceContactsPicker() {
        lifecycleScope.launch {
            val contacts = withContext(Dispatchers.IO) {
                ContactsHelper.getInstance(this@CreateEditRuleActivity).getContactList()
                    .mapNotNull { it.contactName?.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .sorted()
            }

            if (contacts.isEmpty()) {
                Toast.makeText(this@CreateEditRuleActivity, R.string.no_contacts_found, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val namesArray = contacts.toTypedArray()
            val checkedItems = BooleanArray(namesArray.size) { index ->
                filterContacts.any { it.equals(namesArray[index], ignoreCase = true) }
            }

            MaterialAlertDialogBuilder(this@CreateEditRuleActivity)
                .setTitle(R.string.select_contacts)
                .setMultiChoiceItems(namesArray, checkedItems) { _, which, isChecked ->
                    checkedItems[which] = isChecked
                }
                .setPositiveButton(R.string.save) { _, _ ->
                    for (i in namesArray.indices) {
                        val name = namesArray[i]
                        if (checkedItems[i]) {
                            if (filterContacts.none { it.equals(name, ignoreCase = true) }) {
                                filterContacts.add(name)
                            }
                        } else {
                            filterContacts.removeAll { it.equals(name, ignoreCase = true) }
                        }
                    }
                    renderContactChips()
                    markDirty()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun renderContactChips() {
        binding.chipGroupContacts.removeAllViews()
        for (name in filterContacts) {
            val chip = Chip(this).apply {
                text = name
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    filterContacts.remove(name)
                    renderContactChips()
                    markDirty()
                }
            }
            binding.chipGroupContacts.addView(chip)
        }
    }

    private fun setupScheduleSection() {
        binding.switchScheduleEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.scheduleDetailsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            markDirty()
        }

        binding.btnStartTime.setOnClickListener {
            showTimePicker(
                titleRes = R.string.start_time_label,
                initialMinutes = startTimeMinutes
            ) { newMinutes ->
                startTimeMinutes = newMinutes
                updateTimeButtons()
                markDirty()
            }
        }

        binding.btnEndTime.setOnClickListener {
            showTimePicker(
                titleRes = R.string.end_time_label,
                initialMinutes = endTimeMinutes
            ) { newMinutes ->
                endTimeMinutes = newMinutes
                updateTimeButtons()
                markDirty()
            }
        }

        val dayChips = listOf(
            binding.chipDayMon,
            binding.chipDayTue,
            binding.chipDayWed,
            binding.chipDayThu,
            binding.chipDayFri,
            binding.chipDaySat,
            binding.chipDaySun
        )
        dayChips.forEach { chip ->
            chip.setOnCheckedChangeListener { _, _ ->
                binding.tvScheduleDaysError.visibility = View.GONE
                markDirty()
            }
        }
    }

    private fun showTimePicker(
        titleRes: Int,
        initialMinutes: Int,
        onSelected: (Int) -> Unit
    ) {
        val hour = (initialMinutes / 60).coerceIn(0, 23)
        val minute = (initialMinutes % 60).coerceIn(0, 59)
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(hour)
            .setMinute(minute)
            .setTitleText(titleRes)
            .build()

        picker.addOnPositiveButtonClickListener {
            onSelected(picker.hour * 60 + picker.minute)
        }
        picker.show(supportFragmentManager, "rule_time_picker_$titleRes")
    }

    private fun updateTimeButtons() {
        binding.btnStartTime.text = "${getString(R.string.start_time_label)}: ${formatTime(startTimeMinutes)}"
        binding.btnEndTime.text = "${getString(R.string.end_time_label)}: ${formatTime(endTimeMinutes)}"
    }

    private fun formatTime(minutes: Int): String {
        val clamped = minutes.coerceIn(0, 1439)
        return String.format(Locale.US, "%02d:%02d", clamped / 60, clamped % 60)
    }

    private fun loadExistingRule(ruleId: Long) {
        lifecycleScope.launch {
            val rule = withContext(Dispatchers.IO) {
                repository.getRuleById(ruleId)
            }
            if (rule == null) {
                finish()
                return@launch
            }
            existingRule = rule
            populateForm(rule)
        }
    }

    private fun populateForm(rule: AutoReplyRule) {
        isPopulating = true
        binding.etRuleName.setText(rule.name)
        binding.etRuleCategory.setText(rule.category)
        syncCategorySuggestionSelection(rule.category)
        binding.switchRuleActive.isChecked = rule.isEnabled

        selectedPackages.clear()
        selectedPackages.addAll(rule.selectedPackages)
        syncAppCheckBoxes()

        selectedMatchType = rule.matchType
        binding.actvMatchType.setText(getMatchTypeLabel(rule.matchType), false)
        updateMatchTypeDescription()
        binding.switchCaseSensitive.isChecked = rule.isCaseSensitive

        keywords.clear()
        keywords.addAll(rule.keywords)
        renderKeywordChips()

        binding.etReplyMessage.setText(rule.replyMessage)
        binding.etPriority.setText(rule.priority.toString())
        binding.etReplyDelay.setText(rule.replyDelaySeconds.toString())
        binding.etCooldown.setText(rule.cooldownSeconds.toString())
        binding.etFrequencyLimit.setText(rule.maxRepliesPerSender.toString())

        selectedFrequencyWindowMinutes = rule.frequencyWindowMinutes
        val windowLabel = when (rule.frequencyWindowMinutes) {
            60 -> getString(R.string.window_hourly)
            0 -> getString(R.string.window_all_time)
            else -> getString(R.string.window_daily)
        }
        binding.actvFrequencyWindow.setText(windowLabel, false)

        when (rule.groupFilterMode) {
            GroupFilterMode.BOTH -> binding.chipGroupBoth.isChecked = true
            GroupFilterMode.DIRECT_ONLY -> binding.chipGroupDirect.isChecked = true
            GroupFilterMode.GROUP_ONLY -> binding.chipGroupOnly.isChecked = true
        }

        when (rule.contactFilterMode) {
            ContactFilterMode.ALL -> {
                binding.chipContactAll.isChecked = true
                binding.contactNamesContainer.visibility = View.GONE
            }
            ContactFilterMode.INCLUDE_ONLY -> {
                binding.chipContactInclude.isChecked = true
                binding.contactNamesContainer.visibility = View.VISIBLE
            }
            ContactFilterMode.EXCLUDE -> {
                binding.chipContactExclude.isChecked = true
                binding.contactNamesContainer.visibility = View.VISIBLE
            }
        }
        filterContacts.clear()
        filterContacts.addAll(rule.filterContacts)
        renderContactChips()

        binding.switchScheduleEnabled.isChecked = rule.scheduleEnabled
        binding.scheduleDetailsContainer.visibility = if (rule.scheduleEnabled) View.VISIBLE else View.GONE
        startTimeMinutes = rule.startTimeMinutes
        endTimeMinutes = rule.endTimeMinutes
        updateTimeButtons()

        binding.chipDayMon.isChecked = 1 in rule.activeDays
        binding.chipDayTue.isChecked = 2 in rule.activeDays
        binding.chipDayWed.isChecked = 3 in rule.activeDays
        binding.chipDayThu.isChecked = 4 in rule.activeDays
        binding.chipDayFri.isChecked = 5 in rule.activeDays
        binding.chipDaySat.isChecked = 6 in rule.activeDays
        binding.chipDaySun.isChecked = 7 in rule.activeDays

        isPopulating = false
        isDirty = false
    }

    private fun setupSaveAndBackHandling() {
        binding.btnSaveRule.setOnClickListener { saveRule() }
        binding.switchRuleActive.setOnCheckedChangeListener { _, _ -> markDirty() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isDirty) {
                    MaterialAlertDialogBuilder(this@CreateEditRuleActivity)
                        .setTitle(R.string.unsaved_changes_title)
                        .setMessage(R.string.unsaved_changes_message)
                        .setPositiveButton(R.string.discard_changes) { _, _ ->
                            isDirty = false
                            finish()
                        }
                        .setNegativeButton(R.string.stay_on_page, null)
                        .show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun saveRule() {
        // Flush any unsubmitted keyword/contact text currently typed in the inputs
        if (!binding.etKeywordInput.text.isNullOrBlank()) {
            addKeywordsFromInput()
            if (binding.tilKeywordInput.error != null) return
        }
        if (!binding.etContactNameInput.text.isNullOrBlank()) {
            addContactFromInput()
        }

        val ruleName = binding.etRuleName.text?.toString()?.trim().orEmpty()
        val replyMsg = binding.etReplyMessage.text?.toString()?.trim().orEmpty()

        var valid = true
        if (ruleName.isEmpty()) {
            binding.tilRuleName.error = getString(R.string.error_rule_name_required)
            valid = false
        } else {
            binding.tilRuleName.error = null
        }

        if (selectedPackages.isEmpty()) {
            binding.tvAppsError.visibility = View.VISIBLE
            valid = false
        } else {
            binding.tvAppsError.visibility = View.GONE
        }

        if (keywords.isEmpty()) {
            binding.tvKeywordsError.setText(R.string.error_keyword_required)
            binding.tvKeywordsError.visibility = View.VISIBLE
            valid = false
        } else if (!validateKeywordsLive()) {
            valid = false
        }

        if (replyMsg.isEmpty()) {
            binding.tilReplyMessage.error = getString(R.string.error_reply_message_required)
            valid = false
        } else {
            binding.tilReplyMessage.error = null
        }

        val scheduleEnabled = binding.switchScheduleEnabled.isChecked
        val activeDays = collectActiveDays()
        if (scheduleEnabled && activeDays.isEmpty()) {
            binding.tvScheduleDaysError.visibility = View.VISIBLE
            valid = false
        } else {
            binding.tvScheduleDaysError.visibility = View.GONE
        }

        if (!valid) return

        val priority = binding.etPriority.text?.toString()?.toIntOrNull()?.coerceIn(0, 100) ?: 10
        val replyDelay = binding.etReplyDelay.text?.toString()?.toIntOrNull()?.coerceIn(0, 300) ?: 0
        val cooldown = binding.etCooldown.text?.toString()?.toIntOrNull()?.coerceIn(0, 86400) ?: 10
        val freqLimit = binding.etFrequencyLimit.text?.toString()?.toIntOrNull()?.coerceIn(0, 1000) ?: 0

        val groupFilter = when (binding.chipGroupConversationType.checkedChipId) {
            R.id.chip_group_direct -> GroupFilterMode.DIRECT_ONLY
            R.id.chip_group_only -> GroupFilterMode.GROUP_ONLY
            else -> GroupFilterMode.BOTH
        }

        val contactMode = when (binding.chipGroupContactMode.checkedChipId) {
            R.id.chip_contact_include -> ContactFilterMode.INCLUDE_ONLY
            R.id.chip_contact_exclude -> ContactFilterMode.EXCLUDE
            else -> ContactFilterMode.ALL
        }

        val ruleCategory = binding.etRuleCategory.text?.toString()?.trim().orEmpty()

        val ruleToSave = AutoReplyRule(
            id = existingRule?.id ?: 0L,
            name = ruleName,
            category = ruleCategory,
            isEnabled = binding.switchRuleActive.isChecked,
            selectedPackages = selectedPackages.toList(),
            matchType = selectedMatchType,
            keywords = keywords.toList(),
            isCaseSensitive = binding.switchCaseSensitive.isChecked,
            replyMessage = replyMsg,
            priority = priority,
            replyDelaySeconds = replyDelay,
            cooldownSeconds = cooldown,
            maxRepliesPerSender = freqLimit,
            frequencyWindowMinutes = selectedFrequencyWindowMinutes,
            contactFilterMode = contactMode,
            filterContacts = filterContacts.toList(),
            groupFilterMode = groupFilter,
            scheduleEnabled = scheduleEnabled,
            startTimeMinutes = startTimeMinutes,
            endTimeMinutes = endTimeMinutes,
            activeDays = activeDays,
            createdAt = existingRule?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        binding.btnSaveRule.isEnabled = false
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (ruleToSave.id > 0L) {
                    repository.updateRule(ruleToSave)
                } else {
                    repository.insertRule(ruleToSave)
                }
            }
            isDirty = false
            Toast.makeText(this@CreateEditRuleActivity, R.string.rule_saved_toast, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun collectActiveDays(): Set<Int> {
        val days = linkedSetOf<Int>()
        if (binding.chipDayMon.isChecked) days.add(1)
        if (binding.chipDayTue.isChecked) days.add(2)
        if (binding.chipDayWed.isChecked) days.add(3)
        if (binding.chipDayThu.isChecked) days.add(4)
        if (binding.chipDayFri.isChecked) days.add(5)
        if (binding.chipDaySat.isChecked) days.add(6)
        if (binding.chipDaySun.isChecked) days.add(7)
        return days
    }

    private fun syncCategorySuggestionSelection(category: String) {
        val trimmed = category.trim()
        for (i in 0 until binding.chipGroupCategorySuggestions.childCount) {
            val chip = binding.chipGroupCategorySuggestions.getChildAt(i) as? Chip ?: continue
            chip.isChecked = trimmed.isNotEmpty() && chip.text.toString().equals(trimmed, ignoreCase = true)
        }
    }

    private fun markDirty() {
        if (!isPopulating) {
            isDirty = true
        }
    }

    companion object {
        const val EXTRA_RULE_ID = "extra_rule_id"
    }
}
