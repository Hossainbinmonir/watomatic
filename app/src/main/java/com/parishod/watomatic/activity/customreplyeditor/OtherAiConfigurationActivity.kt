package com.parishod.watomatic.activity.customreplyeditor

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Filter
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.JsonElement
import com.parishod.watomatic.R
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.utils.Constants
import com.parishod.watomatic.model.utils.Constants.CUSTOM_AI_PROVIDER_NAME
import com.parishod.watomatic.model.utils.UrlValidator
import com.parishod.watomatic.network.OpenAIService
import com.parishod.watomatic.network.RetrofitInstance
import com.parishod.watomatic.network.model.openai.OpenAIModelsResponse
import com.parishod.watomatic.utils.UnsavedChangesDialog
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class OtherAiConfigurationActivity : AppCompatActivity() {

    private lateinit var providerInput: AutoCompleteTextView
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var baseUrlInput: TextInputEditText
    private lateinit var baseUrlLayout: TextInputLayout
    private lateinit var modelInput: AutoCompleteTextView
    private lateinit var systemPromptInput: TextInputEditText
    private lateinit var modelFetchProgress: ProgressBar
    private lateinit var modelFetchStatus: TextView
    private lateinit var btnRefreshModels: View

    private val providers = listOf("OpenAI", "Claude", "Grok", "Gemini", "DeepSeek", "Mistral", CUSTOM_AI_PROVIDER_NAME)
    private val providerUrls = Constants.PROVIDER_URLS

    private lateinit var preferencesManager: PreferencesManager

    // Debounce handler for fetch requests
    private val fetchHandler = Handler(Looper.getMainLooper())
    private var fetchRunnable: Runnable? = null
    private var currentCall: Call<OpenAIModelsResponse>? = null
    private var currentDynamicCall: Call<JsonElement>? = null
    private var lastFetchedUrl: String? = null
    private var isPopulating = false
    private var currentModelsList: List<String> = emptyList()

    private companion object {
        const val FETCH_DEBOUNCE_MS = 500L

        val RECOMMENDED_MODELS: Map<String, List<String>> = mapOf(
            "OpenAI" to listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1", "o4-mini"),
            "Claude" to listOf(
                "claude-sonnet-4-20250514",
                "claude-3-7-sonnet-20250219",
                "claude-3-5-haiku-20241022",
                "claude-3-5-sonnet-20241022"
            ),
            "Gemini" to listOf(
                "gemini-2.5-flash",
                "gemini-2.5-pro",
                "gemini-2.0-flash",
                "gemini-1.5-flash"
            ),
            "Grok" to listOf("grok-3", "grok-3-mini", "grok-2-latest"),
            "DeepSeek" to listOf("deepseek-chat", "deepseek-reasoner"),
            "Mistral" to listOf("mistral-small-latest", "mistral-large-latest", "open-mistral-nemo")
        )
    }

    // Track initial state for unsaved changes detection
    private var initialProvider: String = ""
    private var initialApiKey: String = ""
    private var initialBaseUrl: String = ""
    private var initialModel: String = ""
    private var initialSystemPrompt: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_other_ai_configuration)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hasUnsavedChanges()) {
                    showUnsavedChangesDialog()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        providerInput = findViewById(R.id.llmProviderAutoCompleteTextView)
        apiKeyInput = findViewById(R.id.apiKeyEditText)
        baseUrlInput = findViewById(R.id.baseUrlEditText)
        baseUrlLayout = findViewById(R.id.baseUrlInputLayout)
        modelInput = findViewById(R.id.modelAutoCompleteTextView)
        systemPromptInput = findViewById(R.id.systemPromptEditText)
        modelFetchProgress = findViewById(R.id.pb_model_fetch)
        modelFetchStatus = findViewById(R.id.tv_model_fetch_status)
        btnRefreshModels = findViewById(R.id.btn_refresh_models)

        preferencesManager = PreferencesManager.getPreferencesInstance(this)

        setupProviderDropdown()
        prefillData()
        setupListeners()

        findViewById<View>(R.id.saveConfigBtn).setOnClickListener {
            saveConfiguration()
        }
    }

    private fun normalizeProviderName(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return providers[0]
        return providers.firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: trimmed
    }

    private fun prefillData() {
        isPopulating = true
        val savedProvider = normalizeProviderName(preferencesManager.openApiSource)
        providerInput.setText(savedProvider, false)
        updateBaseUrlVisibility(savedProvider)

        val savedApiKey = preferencesManager.openAIApiKey
        if (!savedApiKey.isNullOrEmpty()) {
            apiKeyInput.setText(savedApiKey)
        }

        val savedBaseUrl = preferencesManager.customOpenAIApiUrl
        if (!savedBaseUrl.isNullOrEmpty()) {
            baseUrlInput.setText(savedBaseUrl)
        }

        val savedModel = preferencesManager.selectedOpenAIModel
        if (!savedModel.isNullOrEmpty()) {
            modelInput.setText(savedModel, false)
        }

        val savedPrompt = preferencesManager.openAICustomPrompt
        if (!savedPrompt.isNullOrEmpty()) {
            systemPromptInput.setText(savedPrompt)
        }

        initialProvider = providerInput.text.toString()
        initialApiKey = apiKeyInput.text.toString()
        initialBaseUrl = baseUrlInput.text.toString()
        initialModel = modelInput.text.toString()
        initialSystemPrompt = systemPromptInput.text.toString()

        applyRecommendedModels(providerInput.text.toString(), updateHint = true)
        isPopulating = false
    }

    private fun setupProviderDropdown() {
        val adapter = NoFilterArrayAdapter(this, providers)
        providerInput.setAdapter(adapter)
        if (providerInput.text.isEmpty()) {
            providerInput.setText(providers[0], false)
        }
    }

    private fun setupListeners() {
        providerInput.setOnItemClickListener { _, _, _, _ ->
            val provider = providerInput.text.toString()
            updateBaseUrlVisibility(provider)
            lastFetchedUrl = null
            modelInput.setText("", false)
            applyRecommendedModels(provider, updateHint = true)
            fetchModels(openDropdownOnSuccess = false, showErrorToast = false)
        }

        providerInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isPopulating) return
                val provider = s?.toString().orEmpty()
                updateBaseUrlVisibility(provider)
                applyRecommendedModels(provider, updateHint = false)
                debouncedFetchModels()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        apiKeyInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isPopulating) return
                lastFetchedUrl = null
                if (!s.isNullOrBlank()) {
                    debouncedFetchModels()
                } else {
                    setStatusMessage(getString(R.string.ai_models_enter_key_hint), isError = false)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        baseUrlInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isPopulating) return
                lastFetchedUrl = null
                if (providerInput.text.toString() == CUSTOM_AI_PROVIDER_NAME) {
                    debouncedFetchModels()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        btnRefreshModels.setOnClickListener {
            val apiKey = apiKeyInput.text?.toString()?.trim().orEmpty()
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "Enter an API key to fetch available models.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lastFetchedUrl = null
            fetchModels(openDropdownOnSuccess = true, showErrorToast = true)
        }

        modelInput.setOnClickListener {
            handleModelDropdownInteraction()
        }

        modelInput.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                handleModelDropdownInteraction()
            }
            false
        }
    }

    private fun handleModelDropdownInteraction() {
        val provider = normalizeProviderName(providerInput.text?.toString())
        val apiKey = apiKeyInput.text?.toString()?.trim().orEmpty()

        if (provider.isEmpty()) {
            Toast.makeText(this, "Please select an LLM provider first.", Toast.LENGTH_SHORT).show()
            return
        }

        if (currentModelsList.isEmpty()) {
            applyRecommendedModels(provider, updateHint = false)
        }

        if (currentModelsList.isNotEmpty()) {
            modelInput.post {
                if (!isFinishing && !isDestroyed) {
                    modelInput.showDropDown()
                }
            }
        }

        if (apiKey.isNotEmpty() && lastFetchedUrl == null) {
            fetchModels(openDropdownOnSuccess = true, showErrorToast = false)
        }
    }

    private fun applyRecommendedModels(provider: String, updateHint: Boolean) {
        val normalized = normalizeProviderName(provider)
        val recommended = RECOMMENDED_MODELS[normalized].orEmpty()
        if (recommended.isNotEmpty()) {
            updateModelDropdown(recommended)
            if (updateHint && apiKeyInput.text.isNullOrBlank()) {
                setStatusMessage(getString(R.string.ai_models_enter_key_hint), isError = false)
            }
        } else {
            updateModelDropdown(emptyList())
        }
    }

    private fun updateBaseUrlVisibility(provider: String) {
        baseUrlLayout.visibility = if (provider == CUSTOM_AI_PROVIDER_NAME) View.VISIBLE else View.GONE
    }

    private fun isValidUrl(url: String): Boolean = UrlValidator.isValidUrl(url)

    private fun debouncedFetchModels() {
        fetchRunnable?.let { fetchHandler.removeCallbacks(it) }
        fetchRunnable = Runnable { fetchModels(openDropdownOnSuccess = false, showErrorToast = false) }
        fetchHandler.postDelayed(fetchRunnable!!, FETCH_DEBOUNCE_MS)
    }

    private fun fetchModels(openDropdownOnSuccess: Boolean = false, showErrorToast: Boolean = false) {
        val provider = normalizeProviderName(providerInput.text?.toString())
        val apiKey = apiKeyInput.text?.toString()?.trim().orEmpty()

        if (apiKey.isEmpty()) return

        val rawBaseUrl = if (provider == CUSTOM_AI_PROVIDER_NAME) {
            baseUrlInput.text?.toString()?.trim().orEmpty()
        } else {
            providerUrls[provider].orEmpty()
        }

        if (rawBaseUrl.isEmpty()) return
        if (!isValidUrl(rawBaseUrl)) {
            Log.d("OtherAiConfig", "Skipping fetch — invalid URL: $rawBaseUrl")
            return
        }

        val fetchKey = "$rawBaseUrl|$apiKey"
        if (fetchKey == lastFetchedUrl) return

        currentCall?.cancel()
        currentDynamicCall?.cancel()

        val normalizedBaseUrl = if (rawBaseUrl.endsWith("/")) rawBaseUrl else "$rawBaseUrl/"
        val modelsEndpointUrl = buildModelsEndpointUrl(provider, normalizedBaseUrl)

        val retrofit = try {
            RetrofitInstance.getOpenAIRetrofitInstance(normalizedBaseUrl)
        } catch (e: IllegalArgumentException) {
            Log.e("OtherAiConfig", "Invalid base URL for Retrofit: $normalizedBaseUrl", e)
            return
        }
        val service = retrofit.create(OpenAIService::class.java)

        val headers = HashMap<String, String>()
        when (provider) {
            "Claude" -> {
                headers["x-api-key"] = apiKey
                headers["anthropic-version"] = "2023-06-01"
            }
            "Gemini" -> {
                headers["x-goog-api-key"] = apiKey
            }
            else -> {
                headers["Authorization"] = "Bearer $apiKey"
            }
        }

        setLoadingState(true)

        val call = service.getModelsDynamic(modelsEndpointUrl, headers)
        currentDynamicCall = call

        call.enqueue(object : Callback<JsonElement> {
            override fun onResponse(call: Call<JsonElement>, response: Response<JsonElement>) {
                if (call.isCanceled || isFinishing || isDestroyed) return
                setLoadingState(false)

                if (response.isSuccessful && response.body() != null) {
                    val fetched = parseModelsResponse(provider, response.body()!!)
                    if (fetched.isNotEmpty()) {
                        lastFetchedUrl = fetchKey
                        val recommended = RECOMMENDED_MODELS[provider].orEmpty()
                        val merged = (fetched + recommended).distinct()
                        updateModelDropdown(merged)
                        setStatusMessage(
                            getString(R.string.ai_models_loaded, fetched.size, provider),
                            isError = false
                        )
                        if (openDropdownOnSuccess && modelInput.hasFocus()) {
                            modelInput.showDropDown()
                        }
                        return
                    }
                }

                val codeMsg = if (!response.isSuccessful) "HTTP ${response.code()}" else "Empty list"
                Log.w("OtherAiConfig", "Model fetch fallback ($provider): $codeMsg")
                handleModelFetchFallback(provider, codeMsg, showErrorToast)
            }

            override fun onFailure(call: Call<JsonElement>, t: Throwable) {
                if (call.isCanceled || isFinishing || isDestroyed) return
                setLoadingState(false)
                val reason = t.localizedMessage ?: "Network error"
                Log.w("OtherAiConfig", "Failed to fetch models ($provider): $reason", t)
                handleModelFetchFallback(provider, reason, showErrorToast)
            }
        })
    }

    private fun handleModelFetchFallback(provider: String, reason: String, showToast: Boolean) {
        val recommended = RECOMMENDED_MODELS[provider].orEmpty()
        if (recommended.isNotEmpty()) {
            updateModelDropdown(recommended)
            setStatusMessage(getString(R.string.ai_models_fallback_notice, reason), isError = true)
        } else {
            setStatusMessage("Could not fetch models ($reason). Type a model ID manually.", isError = true)
        }
        if (showToast) {
            Toast.makeText(this, "Fetch failed ($reason). Using recommended/manual entry.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setLoadingState(loading: Boolean) {
        modelFetchProgress.visibility = if (loading) View.VISIBLE else View.GONE
        btnRefreshModels.isEnabled = !loading
        if (loading) {
            setStatusMessage(getString(R.string.ai_models_fetching), isError = false)
        }
    }

    private fun setStatusMessage(message: String, isError: Boolean) {
        modelFetchStatus.text = message
        val colorRes = if (isError) R.color.rule_danger else R.color.text_secondary
        modelFetchStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun updateModelDropdown(models: List<String>) {
        currentModelsList = models
        val adapter = NoFilterArrayAdapter(this, models)
        modelInput.setAdapter(adapter)
    }

    private fun saveConfiguration() {
        val provider = normalizeProviderName(providerInput.text.toString())
        val apiKey = apiKeyInput.text.toString().trim()
        val model = modelInput.text.toString().trim()
        val baseUrl = baseUrlInput.text.toString().trim()

        if (apiKey.isEmpty()) {
            Toast.makeText(this, "API key cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }
        if (provider == CUSTOM_AI_PROVIDER_NAME && !isValidUrl(baseUrl)) {
            Toast.makeText(this, getString(R.string.error_invalid_url), Toast.LENGTH_SHORT).show()
            return
        }
        if (model.isEmpty()) {
            Toast.makeText(this, "Select or enter a model", Toast.LENGTH_SHORT).show()
            return
        }

        val resultIntent = Intent()
        resultIntent.putExtra("provider", provider)
        resultIntent.putExtra("apiKey", apiKey)
        resultIntent.putExtra("model", model)
        resultIntent.putExtra("systemPrompt", systemPromptInput.text.toString().trim())
        if (provider == CUSTOM_AI_PROVIDER_NAME) {
            resultIntent.putExtra("baseUrl", baseUrl)
        }

        initialProvider = provider
        initialApiKey = apiKey
        initialModel = model
        initialSystemPrompt = systemPromptInput.text.toString()
        initialBaseUrl = if (provider == CUSTOM_AI_PROVIDER_NAME) baseUrl else ""

        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun hasUnsavedChanges(): Boolean {
        val currentProvider = providerInput.text.toString()
        val currentApiKey = apiKeyInput.text.toString()
        val currentBaseUrl = baseUrlInput.text.toString()
        val currentModel = modelInput.text.toString()
        val currentSystemPrompt = systemPromptInput.text.toString()

        return currentProvider != initialProvider ||
            currentApiKey != initialApiKey ||
            currentBaseUrl != initialBaseUrl ||
            currentModel != initialModel ||
            currentSystemPrompt != initialSystemPrompt
    }

    private fun showUnsavedChangesDialog() {
        UnsavedChangesDialog.show(
            context = this,
            onDiscard = { finish() }
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_other_ai_config, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            R.id.action_save -> {
                saveConfiguration()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fetchRunnable?.let { fetchHandler.removeCallbacks(it) }
        currentCall?.cancel()
        currentDynamicCall?.cancel()
    }

    private class NoFilterArrayAdapter(
        context: android.content.Context,
        private val items: List<String>
    ) : ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line, items) {

        private val noFilter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                return FilterResults().apply {
                    values = items
                    count = items.size
                }
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                notifyDataSetChanged()
            }
        }

        override fun getFilter(): Filter = noFilter
    }

    internal object ModelUrlAndParser {
        private val VERSION_SUFFIX_REGEX = Regex(".*/v\\d+[a-z0-9]*/$")

        fun buildModelsEndpointUrl(provider: String, normalizedBaseUrl: String): String {
            val base = if (normalizedBaseUrl.endsWith("/")) normalizedBaseUrl else "$normalizedBaseUrl/"
            return if (provider == "Gemini") {
                if (base.endsWith("/v1beta/") || base.endsWith("/v1/")) {
                    "${base}models"
                } else {
                    "${base}v1beta/models"
                }
            } else {
                if (VERSION_SUFFIX_REGEX.matches(base) || base.endsWith("/openai/") || base.endsWith("/v1beta/openai/")) {
                    "${base}models"
                } else {
                    "${base}v1/models"
                }
            }
        }

        fun parseModelsResponse(provider: String, root: JsonElement): List<String> {
            val ids = mutableListOf<String>()
            if (root.isJsonArray) {
                for (el in root.asJsonArray) {
                    if (el.isJsonPrimitive) {
                        ids.add(el.asString)
                    } else if (el.isJsonObject) {
                        val obj = el.asJsonObject
                        val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                            ?: obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                        if (!id.isNullOrBlank()) ids.add(id)
                    }
                }
                return ids.distinct()
            }

            if (!root.isJsonObject) return emptyList()
            val obj = root.asJsonObject

            val dataArray = obj.getAsJsonArray("data")
            if (dataArray != null) {
                for (el in dataArray) {
                    if (el.isJsonObject) {
                        val item = el.asJsonObject
                        val id = item.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                            ?: item.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                        if (!id.isNullOrBlank()) ids.add(id)
                    }
                }
            }

            val modelsArray = obj.getAsJsonArray("models")
            if (modelsArray != null) {
                for (el in modelsArray) {
                    if (el.isJsonObject) {
                        val item = el.asJsonObject
                        if (provider == "Gemini") {
                            val methods = item.getAsJsonArray("supportedGenerationMethods")
                            if (methods != null && methods.size() > 0) {
                                val supportsGenContent = methods.any {
                                    it.isJsonPrimitive && it.asString == "generateContent"
                                }
                                if (!supportsGenContent) continue
                            }
                        }
                        val rawName = item.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                            ?: item.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                        if (!rawName.isNullOrBlank()) {
                            val cleanName = if (rawName.startsWith("models/")) {
                                rawName.removePrefix("models/")
                            } else {
                                rawName
                            }
                            ids.add(cleanName)
                        }
                    }
                }
            }

            return ids.distinct()
        }
    }

    private fun buildModelsEndpointUrl(provider: String, normalizedBaseUrl: String): String =
        ModelUrlAndParser.buildModelsEndpointUrl(provider, normalizedBaseUrl)

    private fun parseModelsResponse(provider: String, root: JsonElement): List<String> =
        ModelUrlAndParser.parseModelsResponse(provider, root)
}
