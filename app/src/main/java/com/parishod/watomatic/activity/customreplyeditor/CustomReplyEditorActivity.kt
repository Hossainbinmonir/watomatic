package com.parishod.watomatic.activity.customreplyeditor

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.parishod.watomatic.R
import com.parishod.watomatic.activity.BaseActivity
import com.parishod.watomatic.activity.notes.AiNotesActivity
import com.parishod.watomatic.model.utils.ReportCopyHelper
import com.parishod.watomatic.model.CustomRepliesData
import com.parishod.watomatic.model.ai.AiMemoryDatabase
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.utils.UnsavedChangesDialog
import com.parishod.watomatic.viewmodel.SwipeToKillAppDetectViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CustomReplyEditorActivity : BaseActivity() {
    companion object {
        private const val REPLY_METHOD_MANUAL = "manual"
        private const val REPLY_METHOD_BYOK = "byok"
    }

    private var autoReplyText: TextInputEditText? = null
    private var saveAutoReplyTextBtn: Button? = null
    private var customRepliesData: CustomRepliesData? = null
    private var preferencesManager: PreferencesManager? = null
    private var manualRepliesCard: MaterialCardView? = null
    private var manualStatusText: TextView? = null
    private var manualSettingsIcon: android.widget.ImageView? = null
    private var manualCheckIcon: android.widget.ImageView? = null
    private var otherAiProviderCard: MaterialCardView? = null
    private var btnOtherAiEdit: android.widget.ImageView? = null
    private var otherAiCheckIcon: android.widget.ImageView? = null
    private var otherAiExpandedContent: android.view.View? = null
    private var btnOpenAiNotes: MaterialButton? = null
    private var btnOpenAiRulesMemory: MaterialButton? = null
    private var btnCopyAiStatus: android.widget.ImageButton? = null

    private var selectedReplyMethod = REPLY_METHOD_MANUAL
    private var initialSelectedReplyMethod = REPLY_METHOD_MANUAL

    private val manualConfigLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        autoReplyText?.setText(customRepliesData?.get() ?: "")
        refreshManualRulesSummary()
    }

    private val otherAiConfigLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            if (data != null) {
                val provider = data.getStringExtra("provider")?.trim()
                val apiKey = data.getStringExtra("apiKey")?.trim()
                val model = data.getStringExtra("model")?.trim()
                val systemPrompt = data.getStringExtra("systemPrompt")?.trim()
                val baseUrl = data.getStringExtra("baseUrl")?.trim()

                if (!apiKey.isNullOrEmpty()) {
                    val saved = preferencesManager?.saveOpenAIApiKeySecurely(apiKey) == true
                    if (!saved) {
                        Toast.makeText(
                            this,
                            R.string.secure_storage_unavailable_save_error,
                            Toast.LENGTH_LONG
                        ).show()
                        updateCardExpansionState(selectedReplyMethod)
                        return@registerForActivityResult
                    }
                }
                if (!provider.isNullOrEmpty()) preferencesManager?.saveOpenApiSource(provider)
                if (!model.isNullOrEmpty()) preferencesManager?.saveSelectedOpenAIModel(model)
                if (systemPrompt != null) preferencesManager?.saveOpenAICustomPrompt(systemPrompt)
                if (baseUrl != null) preferencesManager?.saveCustomOpenAIApiUrl(baseUrl)

                preferencesManager?.clearOpenAILastPersistentError()

                if (!apiKey.isNullOrEmpty() && apiKey != "PENDING_CONFIGURATION") {
                    preferencesManager?.setEnableAutomaticAiReplies(false)
                    preferencesManager?.setEnableByokReplies(true)
                    selectedReplyMethod = REPLY_METHOD_BYOK
                    initialSelectedReplyMethod = REPLY_METHOD_BYOK
                }

                Toast.makeText(this, "AI Configuration Saved", Toast.LENGTH_SHORT).show()
                updateCardExpansionState(selectedReplyMethod)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_custom_reply_editor)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Setup back button handler for unsaved selection changes
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hasSelectionChanged()) {
                    showUnsavedChangesDialog()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        ViewModelProvider(this)[SwipeToKillAppDetectViewModel::class.java]

        customRepliesData = CustomRepliesData.getInstance(this)
        preferencesManager = PreferencesManager.getPreferencesInstance(this)

        initializeViews()
        setupClickListeners()
        handleDeepLink()
        restoreSelectedReplyMethod()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.custom_reply_editor_scroll_view)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        updateCardExpansionState(selectedReplyMethod)
        refreshPendingNotesBadge()
        refreshManualRulesSummary()
    }

    private fun refreshManualRulesSummary() {
        val prefs = preferencesManager ?: return
        val appsCount = prefs.enabledApps?.size ?: 0
        val convLabel = getString(
            if (prefs.isGroupReplyEnabled) {
                R.string.manual_card_summary_groups
            } else {
                R.string.manual_card_summary_direct
            }
        )
        val cooldownSec = maxOf(prefs.autoReplyDelay / 1000L, 10L)
        val cooldownLabel = formatCooldownCompact(cooldownSec)
        val contactsLabel = getString(
            if (prefs.isContactReplyEnabled) {
                R.string.manual_card_summary_contacts_filtered
            } else {
                R.string.manual_card_summary_contacts_all
            }
        )
        manualStatusText?.visibility = android.view.View.VISIBLE
        manualStatusText?.text = getString(
            R.string.manual_card_rules_summary,
            appsCount,
            convLabel,
            cooldownLabel,
            contactsLabel
        )
    }

    private fun formatCooldownCompact(seconds: Long): String {
        return when {
            seconds % 3600L == 0L -> "${seconds / 3600L}h"
            seconds % 60L == 0L -> "${seconds / 60L}m"
            else -> "${seconds}s"
        }
    }

    private fun refreshPendingNotesBadge() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                AiMemoryDatabase.getInstance(applicationContext).aiMemoryDao().getPendingNoteCountSync()
            }
            btnOpenAiNotes?.text = getString(R.string.ai_view_notes_btn, count)
        }
    }

    private fun initializeViews() {
        autoReplyText = findViewById(R.id.autoReplyTextInputEditText)
        saveAutoReplyTextBtn = findViewById(R.id.saveCustomReplyBtn)
        manualRepliesCard = findViewById(R.id.manual_replies_card)
        manualStatusText = findViewById(R.id.manual_status_text)
        manualSettingsIcon = findViewById(R.id.manual_settings_icon)
        manualCheckIcon = findViewById(R.id.manual_check_icon)
        otherAiProviderCard = findViewById(R.id.other_ai_provider_card)
        btnOtherAiEdit = findViewById(R.id.btn_other_ai_edit)
        otherAiCheckIcon = findViewById(R.id.other_ai_check_icon)
        otherAiExpandedContent = findViewById(R.id.other_ai_expanded_content)
        btnOpenAiNotes = findViewById(R.id.btn_open_ai_notes)
        btnOpenAiRulesMemory = findViewById(R.id.btn_open_ai_rules_memory)
        btnCopyAiStatus = findViewById(R.id.btn_copy_ai_status)
    }

    private fun handleDeepLink() {
        val data = intent.data
        autoReplyText?.setText(
            if (data != null)
                data.getQueryParameter("message")
            else
                customRepliesData?.get() ?: ""
        )
    }

    private fun setupClickListeners() {
        saveAutoReplyTextBtn?.setOnClickListener {
            handleSaveClick()
        }

        // Manual Replies Card - Select this reply method
        manualRepliesCard?.setOnClickListener {
            selectReplyMethod(REPLY_METHOD_MANUAL)
        }

        // Manual Settings Icon - Open Manual Reply Settings (separate rules & default reply)
        manualSettingsIcon?.setOnClickListener {
            val intent = Intent(this, ManualReplyConfigurationActivity::class.java)
            manualConfigLauncher.launch(intent)
        }

        // Other AI Card - Select BYOK method
        otherAiProviderCard?.setOnClickListener {
            selectReplyMethod(REPLY_METHOD_BYOK)
        }

        // Other AI Settings Icon - Navigate to configuration
        btnOtherAiEdit?.setOnClickListener {
            val intent = Intent(this, OtherAiConfigurationActivity::class.java)
            otherAiConfigLauncher.launch(intent)
        }

        btnOpenAiNotes?.setOnClickListener {
            startActivity(Intent(this, AiNotesActivity::class.java))
        }

        btnOpenAiRulesMemory?.setOnClickListener {
            val intent = Intent(this, OtherAiConfigurationActivity::class.java)
            otherAiConfigLauncher.launch(intent)
        }

        btnCopyAiStatus?.setOnClickListener {
            val statusText = findViewById<TextView>(R.id.other_ai_status_text)?.text?.toString().orEmpty()
            if (ReportCopyHelper.copyToClipboard(this, getString(R.string.ai_status_copy_desc), statusText)) {
                Toast.makeText(this, R.string.ai_status_copied_toast, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun selectReplyMethod(method: String) {
        Log.d("CustomReplyEditor", "selectReplyMethod reply method: $method")
        selectedReplyMethod = method
        updateCardExpansionState(method)
    }

    private fun getSelectedReplyMethod(): String {
        if (preferencesManager?.isByokRepliesEnabled == true) {
            return REPLY_METHOD_BYOK
        }
        return REPLY_METHOD_MANUAL
    }

    private fun restoreSelectedReplyMethod() {
        val savedMethod = getSelectedReplyMethod()
        Log.d("CustomReplyEditor", "Restoring selected reply method: $savedMethod")
        initialSelectedReplyMethod = savedMethod
        selectReplyMethod(savedMethod)
    }

    private fun handleSaveClick() {
        when (selectedReplyMethod) {
            REPLY_METHOD_MANUAL -> {
                val replyText = autoReplyText?.text
                if (!CustomRepliesData.isValidCustomReply(replyText)) {
                    Toast.makeText(
                        this,
                        "Please enter a valid auto reply message",
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }
                customRepliesData?.set(replyText)
                preferencesManager?.setEnableAutomaticAiReplies(false)
                preferencesManager?.setEnableByokReplies(false)
            }
            REPLY_METHOD_BYOK -> {
                if (preferencesManager?.isSecureStorageAvailable != true) {
                    Toast.makeText(
                        this,
                        R.string.secure_storage_unavailable_notice,
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
                val apiKey = preferencesManager?.openAIApiKey?.trim()
                if (apiKey.isNullOrEmpty() || apiKey == "PENDING_CONFIGURATION") {
                    Toast.makeText(
                        this,
                        "Please configure your AI provider settings",
                        Toast.LENGTH_LONG
                    ).show()
                    val intent = Intent(this, OtherAiConfigurationActivity::class.java)
                    otherAiConfigLauncher.launch(intent)
                    return
                }
                preferencesManager?.setEnableAutomaticAiReplies(false)
                preferencesManager?.setEnableByokReplies(true)
            }
        }

        initialSelectedReplyMethod = selectedReplyMethod
        onNavigateUp()
    }

    private fun updateCardExpansionState(selectedMethod: String) {
        Log.d("CustomReplyEditor", "updateCardExpansionState reply method: $selectedMethod")
        val rawApiKey = preferencesManager?.openAIApiKey?.trim()
        val hasApiKey = !rawApiKey.isNullOrEmpty() && rawApiKey != "PENDING_CONFIGURATION"

        val selectedStrokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            2f,
            resources.displayMetrics
        ).toInt()
        val unselectedStrokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            1f,
            resources.displayMetrics
        ).toInt()

        val primaryColor = getThemeColor(androidx.appcompat.R.attr.colorPrimary)
        val defaultStrokeColor = ContextCompat.getColor(this, R.color.rule_card_stroke)

        val isManualSelected = selectedMethod == REPLY_METHOD_MANUAL
        val isByokSelected = selectedMethod == REPLY_METHOD_BYOK

        manualRepliesCard?.apply {
            strokeWidth = if (isManualSelected) selectedStrokeWidth else unselectedStrokeWidth
            strokeColor = if (isManualSelected) primaryColor else defaultStrokeColor
        }
        manualCheckIcon?.visibility = if (isManualSelected) android.view.View.VISIBLE else android.view.View.GONE

        if (isManualSelected) {
            autoReplyText?.visibility = android.view.View.VISIBLE
            autoReplyText?.setText(customRepliesData?.get())
        } else {
            autoReplyText?.visibility = android.view.View.GONE
        }

        otherAiProviderCard?.apply {
            strokeWidth = if (isByokSelected) selectedStrokeWidth else unselectedStrokeWidth
            strokeColor = if (isByokSelected) primaryColor else defaultStrokeColor
        }
        otherAiCheckIcon?.visibility = if (isByokSelected) android.view.View.VISIBLE else android.view.View.GONE

        if (isByokSelected || hasApiKey) {
            otherAiExpandedContent?.visibility = android.view.View.VISIBLE
            val statusView = findViewById<TextView>(R.id.other_ai_status_text)
            if (preferencesManager?.isSecureStorageAvailable != true) {
                statusView?.text = getString(R.string.secure_storage_unavailable_status)
                statusView?.setTextColor(ContextCompat.getColor(this@CustomReplyEditorActivity, R.color.rule_danger))
            } else if (hasApiKey) {
                val provider = preferencesManager?.openApiSource?.takeIf { it.isNotBlank() } ?: "OpenAI"
                val model = preferencesManager?.selectedOpenAIModel?.takeIf { it.isNotBlank() } ?: "Default"
                val lastError = preferencesManager?.openAILastPersistentErrorMessage?.takeIf { it.isNotBlank() }
                if (lastError != null) {
                    statusView?.text = "Configured • $provider ($model)\nLast error: $lastError"
                    statusView?.setTextColor(ContextCompat.getColor(this@CustomReplyEditorActivity, R.color.rule_danger))
                } else {
                    statusView?.text = "Configured • $provider ($model)"
                    statusView?.setTextColor(ContextCompat.getColor(this@CustomReplyEditorActivity, R.color.rule_active_accent))
                }
            } else {
                statusView?.text = "Status: Not Configured"
                statusView?.setTextColor(ContextCompat.getColor(this@CustomReplyEditorActivity, R.color.rule_danger))
            }
        } else {
            otherAiExpandedContent?.visibility = android.view.View.GONE
        }
    }

    private fun getThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private fun hasSelectionChanged(): Boolean {
        val changed = selectedReplyMethod != initialSelectedReplyMethod
        Log.d("CustomReplyEditor", "hasSelectionChanged: $changed (current: $selectedReplyMethod, initial: $initialSelectedReplyMethod)")
        return changed
    }

    private fun showUnsavedChangesDialog() {
        UnsavedChangesDialog.show(
            context = this,
            onDiscard = {
                finish()
            }
        )
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
