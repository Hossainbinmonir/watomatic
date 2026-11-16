package com.parishod.watomatic.activity.customreplyeditor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.parishod.watomatic.R
import com.parishod.watomatic.model.preferences.PreferencesManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CustomReplyEditorActivityByokFlowTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferencesManager.resetInstance()
        context.getSharedPreferences("CustomRepliesData", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("watomatic_secure_prefs_fallback", Context.MODE_PRIVATE).edit().clear().commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    @After
    fun tearDown() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("watomatic_secure_prefs_fallback", Context.MODE_PRIVATE).edit().clear().commit()
        PreferencesManager.resetInstance()
    }

    @Test
    fun `preferencesManager persists api key even when keystore unavailable`() {
        val prefs = PreferencesManager.getPreferencesInstance(context)
        prefs.saveOpenAIApiKey("test-gemini-key-123")
        assertEquals("test-gemini-key-123", prefs.openAIApiKey)
    }

    @Test
    fun `saving AI configuration enables BYOK and survives onResume`() {
        val controller = Robolectric.buildActivity(CustomReplyEditorActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_WaReply)
        controller.create().start().resume()

        // Launch OtherAiConfigurationActivity via settings icon and simulate RESULT_OK
        activity.findViewById<android.view.View>(R.id.btn_other_ai_edit).performClick()
        val started = shadowOf(activity).nextStartedActivityForResult
        val resultData = Intent().apply {
            putExtra("provider", "Gemini")
            putExtra("apiKey", "AIzaTestKey12345")
            putExtra("model", "gemini-2.5-flash")
            putExtra("systemPrompt", "Be brief.")
        }
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, resultData)

        // Simulate Android calling onResume after ActivityResult delivery
        controller.pause().resume()

        val prefs = PreferencesManager.getPreferencesInstance(context)
        assertTrue("BYOK should be enabled immediately after saving AI config", prefs.isByokRepliesEnabled)
        assertEquals("AIzaTestKey12345", prefs.openAIApiKey)
        assertEquals("Gemini", prefs.openApiSource)
        assertEquals("gemini-2.5-flash", prefs.selectedOpenAIModel)

        val statusText = activity.findViewById<TextView>(R.id.other_ai_status_text).text.toString()
        assertTrue("Status should display provider and model: $statusText", statusText.contains("Gemini") && statusText.contains("gemini-2.5-flash"))

        controller.pause().stop().destroy()
    }
}
