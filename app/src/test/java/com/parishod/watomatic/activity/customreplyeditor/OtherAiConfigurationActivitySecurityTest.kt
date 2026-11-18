package com.parishod.watomatic.activity.customreplyeditor

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.textfield.TextInputEditText
import com.parishod.watomatic.R
import com.parishod.watomatic.model.preferences.PreferencesManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowToast
import java.security.GeneralSecurityException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OtherAiConfigurationActivitySecurityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferencesManager.resetInstance()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences(PreferencesManager.LEGACY_FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("test_ui_encrypted_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences(PreferencesManager.LEGACY_FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("test_ui_encrypted_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        PreferencesManager.resetInstance()
    }

    @Test
    fun `shows secure storage error banner and blocks save without exposing key when keystore unavailable`() {
        PreferencesManager.setEncryptedPrefsProviderForTesting {
            throw GeneralSecurityException("Keystore unavailable")
        }

        val controller = Robolectric.buildActivity(
            OtherAiConfigurationActivity::class.java,
            Intent(context, OtherAiConfigurationActivity::class.java)
        )
        val activity = controller.get()
        activity.setTheme(R.style.Theme_WaReply)
        controller.create().start().resume()

        val errorBanner = activity.findViewById<TextView>(R.id.tv_secure_storage_error)
        assertEquals(View.VISIBLE, errorBanner.visibility)
        assertTrue(errorBanner.text.toString().isNotBlank())

        val secretKey = "sk-ui-secret-key-do-not-expose"
        activity.findViewById<AutoCompleteTextView>(R.id.llmProviderAutoCompleteTextView)
            .setText("OpenAI", false)
        activity.findViewById<TextInputEditText>(R.id.apiKeyEditText)
            .setText(secretKey)
        activity.findViewById<AutoCompleteTextView>(R.id.modelAutoCompleteTextView)
            .setText("gpt-4o-mini", false)

        activity.findViewById<View>(R.id.saveConfigBtn).performClick()

        // Activity must stay open and not falsely finish with RESULT_OK
        assertFalse(activity.isFinishing)
        val latestToast = ShadowToast.getTextOfLatestToast() ?: ""
        assertTrue(latestToast.isNotBlank())
        assertFalse("Toast must never display API key", latestToast.contains(secretKey))
        assertFalse("Error banner must never display API key", errorBanner.text.toString().contains(secretKey))
        assertNull(PreferencesManager.getPreferencesInstance(context).openAIApiKey)

        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }

    @Test
    fun `hides secure storage error banner and saves cleanly when secure storage is available`() {
        val encryptedStore = context.getSharedPreferences("test_ui_encrypted_prefs", Context.MODE_PRIVATE)
        PreferencesManager.setEncryptedPrefsProviderForTesting { encryptedStore }

        val controller = Robolectric.buildActivity(
            OtherAiConfigurationActivity::class.java,
            Intent(context, OtherAiConfigurationActivity::class.java)
        )
        val activity = controller.get()
        activity.setTheme(R.style.Theme_WaReply)
        controller.create().start().resume()

        val errorBanner = activity.findViewById<TextView>(R.id.tv_secure_storage_error)
        assertEquals(View.GONE, errorBanner.visibility)

        activity.findViewById<AutoCompleteTextView>(R.id.llmProviderAutoCompleteTextView)
            .setText("OpenAI", false)
        activity.findViewById<TextInputEditText>(R.id.apiKeyEditText)
            .setText("sk-valid-encrypted-ui-key")
        activity.findViewById<AutoCompleteTextView>(R.id.modelAutoCompleteTextView)
            .setText("gpt-4o-mini", false)

        activity.findViewById<View>(R.id.saveConfigBtn).performClick()

        assertTrue(activity.isFinishing)
        assertEquals("sk-valid-encrypted-ui-key", PreferencesManager.getPreferencesInstance(context).openAIApiKey)

        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }

    @Test
    fun `invalid custom base URL handling never logs raw URL or Throwable payload`() {
        val encryptedStore = context.getSharedPreferences("test_ui_encrypted_prefs", Context.MODE_PRIVATE)
        PreferencesManager.setEncryptedPrefsProviderForTesting { encryptedStore }

        val controller = Robolectric.buildActivity(
            OtherAiConfigurationActivity::class.java,
            Intent(context, OtherAiConfigurationActivity::class.java)
        )
        val activity = controller.get()
        activity.setTheme(R.style.Theme_WaReply)
        controller.create().start().resume()

        ShadowLog.clear()
        val sensitiveBadUrl = "ftp://user:secretpass@internal-llm.secret.local/v1?token=leak123"
        activity.findViewById<AutoCompleteTextView>(R.id.llmProviderAutoCompleteTextView)
            .setText("OpenAI Compatible", false)
        activity.findViewById<TextInputEditText>(R.id.apiKeyEditText)
            .setText("sk-ui-key-123")
        activity.findViewById<TextInputEditText>(R.id.baseUrlEditText)
            .setText(sensitiveBadUrl)

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))

        val logs = ShadowLog.getLogsForTag("OtherAiConfig")
        val combinedMessages = logs.joinToString("\n") { "${it.msg ?: ""}\n${it.throwable?.message ?: ""}" }
        assertFalse("Log must not contain raw invalid URL", combinedMessages.contains("internal-llm.secret.local"))
        assertFalse("Log must not contain credentials", combinedMessages.contains("secretpass"))
        assertFalse("Log must not contain query token", combinedMessages.contains("leak123"))
        assertTrue("No Throwable should be attached to OtherAiConfig logs", logs.all { it.throwable == null })

        controller.pause().stop().destroy()
    }

    @Test
    fun `shows error banner and stays open when encrypted commit returns false on save`() {
        val backing = context.getSharedPreferences("test_ui_encrypted_prefs", Context.MODE_PRIVATE)
        val failingCommitEditor = object : android.content.SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor = this
            override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor = this
            override fun remove(key: String?): android.content.SharedPreferences.Editor = this
            override fun clear(): android.content.SharedPreferences.Editor = this
            override fun commit(): Boolean = false
            override fun apply() {}
        }
        val failingWritePrefs = object : android.content.SharedPreferences by backing {
            override fun edit(): android.content.SharedPreferences.Editor = failingCommitEditor
        }
        PreferencesManager.setEncryptedPrefsProviderForTesting { failingWritePrefs }

        val controller = Robolectric.buildActivity(
            OtherAiConfigurationActivity::class.java,
            Intent(context, OtherAiConfigurationActivity::class.java)
        )
        val activity = controller.get()
        activity.setTheme(R.style.Theme_WaReply)
        controller.create().start().resume()

        val secretKey = "sk-must-not-report-saved"
        activity.findViewById<AutoCompleteTextView>(R.id.llmProviderAutoCompleteTextView)
            .setText("OpenAI", false)
        activity.findViewById<TextInputEditText>(R.id.apiKeyEditText)
            .setText(secretKey)
        activity.findViewById<AutoCompleteTextView>(R.id.modelAutoCompleteTextView)
            .setText("gpt-4o-mini", false)

        activity.findViewById<View>(R.id.saveConfigBtn).performClick()

        assertFalse("Activity must stay open when save verification fails", activity.isFinishing)
        val errorBanner = activity.findViewById<TextView>(R.id.tv_secure_storage_error)
        assertEquals(View.VISIBLE, errorBanner.visibility)
        assertFalse(errorBanner.text.toString().contains(secretKey))
        val latestToast = ShadowToast.getTextOfLatestToast() ?: ""
        assertFalse(latestToast.contains(secretKey))
        assertNull(PreferencesManager.getPreferencesInstance(context).openAIApiKey)

        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }

    @Test
    fun `recovers from initial secure storage failure, migrates legacy key, clears stale error, and hides error banner on resume`() {
        val legacyPrefs = context.getSharedPreferences(PreferencesManager.LEGACY_FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        val legacySecret = "sk-legacy-ui-recovery-key"
        legacyPrefs.edit().putString("pref_openai_api_key", legacySecret).commit()

        PreferencesManager.setEncryptedPrefsProviderForTesting {
            throw GeneralSecurityException("Transient keystore lock")
        }

        val controller = Robolectric.buildActivity(
            OtherAiConfigurationActivity::class.java,
            Intent(context, OtherAiConfigurationActivity::class.java)
        )
        val activity = controller.get()
        activity.setTheme(R.style.Theme_WaReply)
        controller.create().start().resume()

        val prefs = PreferencesManager.getPreferencesInstance(context)
        val errorBanner = activity.findViewById<TextView>(R.id.tv_secure_storage_error)
        val apiKeyInput = activity.findViewById<TextInputEditText>(R.id.apiKeyEditText)

        // Steps 1 & 2: Initial failure shows banner and leaves legacy key untouched
        assertFalse(prefs.isSecureStorageAvailable)
        assertTrue(!prefs.secureStorageError.isNullOrEmpty())
        assertEquals(View.VISIBLE, errorBanner.visibility)
        assertTrue(apiKeyInput.text.isNullOrEmpty())
        assertTrue(prefs.hasUnmigratedLegacyApiKey())

        // Simulate keystore becoming available and resuming the screen
        controller.pause()
        val encryptedStore = context.getSharedPreferences("test_ui_encrypted_prefs", Context.MODE_PRIVATE)
        PreferencesManager.setEncryptedPrefsProviderForTesting { encryptedStore }
        controller.resume()

        // Steps 3-7: Recovery succeeds, error cleared, banner removed, legacy key safely migrated
        assertTrue(prefs.isSecureStorageAvailable)
        assertNull(prefs.secureStorageError)
        assertEquals(View.GONE, errorBanner.visibility)
        assertEquals(legacySecret, prefs.openAIApiKey)
        assertEquals(legacySecret, apiKeyInput.text?.toString())
        assertFalse(prefs.hasUnmigratedLegacyApiKey())
        assertFalse(legacyPrefs.contains("pref_openai_api_key"))

        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }

    @Test
    fun `external retrySecureStorageInitialization before resume clears stale error, removes banner, and populates migrated key`() {
        val legacyPrefs = context.getSharedPreferences(PreferencesManager.LEGACY_FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        val legacySecret = "sk-legacy-external-recovery"
        legacyPrefs.edit().putString("pref_openai_api_key", legacySecret).commit()

        PreferencesManager.setEncryptedPrefsProviderForTesting {
            throw GeneralSecurityException("Keystore unavailable")
        }

        val controller = Robolectric.buildActivity(
            OtherAiConfigurationActivity::class.java,
            Intent(context, OtherAiConfigurationActivity::class.java)
        )
        val activity = controller.get()
        activity.setTheme(R.style.Theme_WaReply)
        controller.create().start().resume()

        val prefs = PreferencesManager.getPreferencesInstance(context)
        val errorBanner = activity.findViewById<TextView>(R.id.tv_secure_storage_error)
        val apiKeyInput = activity.findViewById<TextInputEditText>(R.id.apiKeyEditText)
        assertEquals(View.VISIBLE, errorBanner.visibility)

        controller.pause()
        val encryptedStore = context.getSharedPreferences("test_ui_encrypted_prefs", Context.MODE_PRIVATE)
        PreferencesManager.setEncryptedPrefsProviderForTesting { encryptedStore }

        // External recovery call before UI resumes
        assertTrue(prefs.retrySecureStorageInitialization())
        assertTrue(prefs.isSecureStorageAvailable)
        assertNull(prefs.secureStorageError)
        assertEquals(legacySecret, prefs.openAIApiKey)
        assertFalse(prefs.hasUnmigratedLegacyApiKey())

        controller.resume()
        assertEquals(View.GONE, errorBanner.visibility)
        assertEquals(legacySecret, apiKeyInput.text?.toString())

        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }
}
