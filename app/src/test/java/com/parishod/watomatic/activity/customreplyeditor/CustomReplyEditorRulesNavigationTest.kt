package com.parishod.watomatic.activity.customreplyeditor

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.parishod.watomatic.R
import com.parishod.watomatic.activity.contactselector.ContactSelectorActivity
import com.parishod.watomatic.activity.rules.CreateEditRuleActivity
import com.parishod.watomatic.model.CustomRepliesData
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.rules.AutoReplyRulesDatabase
import com.parishod.watomatic.model.utils.Constants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class CustomReplyEditorRulesNavigationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CustomRepliesData.resetInstance()
        PreferencesManager.resetInstance()
        AutoReplyRulesDatabase.resetInstance()
        context.deleteDatabase(AutoReplyRulesDatabase.DB_NAME)
        context.getSharedPreferences("CustomRepliesData", Context.MODE_PRIVATE).edit().clear().commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    @After
    fun tearDown() {
        AutoReplyRulesDatabase.resetInstance()
        context.deleteDatabase(AutoReplyRulesDatabase.DB_NAME)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("CustomRepliesData", Context.MODE_PRIVATE).edit().clear().commit()
        CustomRepliesData.resetInstance()
        PreferencesManager.resetInstance()
    }

    @Test
    fun `clicking Manual Replies gear opens ManualReplyConfigurationActivity`() {
        val controller = Robolectric.buildActivity(CustomReplyEditorActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_WaReply)
        controller.create().start().resume()

        val gearIcon = activity.findViewById<View>(R.id.manual_settings_icon)
        assertNotNull(gearIcon)
        gearIcon.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val startedIntent = shadowOf(activity).nextStartedActivityForResult?.intent
            ?: shadowOf(activity).nextStartedActivity
        assertNotNull("Expected ManualReplyConfigurationActivity to be launched", startedIntent)
        assertEquals(
            ManualReplyConfigurationActivity::class.java.name,
            startedIntent?.component?.className
        )

        controller.pause().stop().destroy()
    }

    @Test
    fun `ManualReplyConfigurationActivity shows Default Manual Reply rules without keywords`() {
        val controller = Robolectric.buildActivity(ManualReplyConfigurationActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_WaReply)
        controller.create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(activity.findViewById<View>(R.id.et_default_manual_reply))
        assertNotNull(activity.findViewById<View>(R.id.et_manual_cooldown_seconds))
        assertNotNull(activity.findViewById<View>(R.id.chip_group_manual_conversation_type))
        assertNotNull(activity.findViewById<View>(R.id.switch_manual_contact_filter))
        assertNotNull(activity.findViewById<View>(R.id.btn_choose_manual_contacts))
        assertEquals(
            Constants.SUPPORTED_APPS.size,
            activity.findViewById<LinearLayout>(R.id.manual_apps_container).childCount
        )
        assertNull(activity.findViewById<View>(R.id.chip_group_keywords))

        controller.pause().stop().destroy()
    }

    @Test
    fun `ManualReplyConfigurationActivity saves Default Manual Reply message and all four rules`() {
        val controller = Robolectric.buildActivity(ManualReplyConfigurationActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_WaReply)
        controller.create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()

        // 1. Default reply message
        activity.findViewById<TextInputEditText>(R.id.et_default_manual_reply)
            .setText("In a meeting, will reply soon.")

        // 2. Reply cooldown -> 1m chip (60 seconds)
        activity.findViewById<Chip>(R.id.chip_manual_cd_1m).performClick()

        // 3. Conversation type -> Direct & Groups
        activity.findViewById<Chip>(R.id.chip_manual_conv_both).performClick()

        // 4. Contacts filter -> enabled
        activity.findViewById<MaterialSwitch>(R.id.switch_manual_contact_filter).isChecked = true

        // 5. Supported apps -> Clear all, then select WhatsApp and Telegram only
        activity.findViewById<View>(R.id.btn_manual_select_all_apps).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val appsContainer = activity.findViewById<LinearLayout>(R.id.manual_apps_container)
        val sortedApps = Constants.SUPPORTED_APPS.sortedBy { it.name }
        sortedApps.forEachIndexed { index, app ->
            val checkBox = appsContainer.getChildAt(index)
                .findViewById<MaterialCheckBox>(R.id.cb_app_selected)
            checkBox.isChecked =
                app.packageName == "com.whatsapp" || app.packageName == "org.telegram.messenger"
        }

        activity.findViewById<View>(R.id.btn_save_manual_config).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(activity.isFinishing)

        assertEquals(
            "In a meeting, will reply soon.",
            CustomRepliesData.getInstance(context).get()
        )
        val prefs = PreferencesManager.getPreferencesInstance(context)
        assertEquals(60_000L, prefs.autoReplyDelay)
        assertTrue(prefs.isGroupReplyEnabled)
        assertTrue(prefs.isContactReplyEnabled)
        assertEquals(
            setOf("com.whatsapp", "org.telegram.messenger"),
            prefs.enabledApps
        )

        // Verify CustomReplyEditorActivity displays updated Default Manual Reply rule summary
        val editorController = Robolectric.buildActivity(CustomReplyEditorActivity::class.java)
        val editorActivity = editorController.get()
        editorActivity.setTheme(R.style.Theme_WaReply)
        editorController.create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()

        val statusText = editorActivity.findViewById<TextView>(R.id.manual_status_text).text.toString()
        assertTrue(statusText.contains("2 apps"))
        assertTrue(statusText.contains("Direct & groups"))
        assertTrue(statusText.contains("1m cooldown"))
        assertTrue(statusText.contains("Contacts filtered"))

        editorController.pause().stop().destroy()
    }

    @Test
    fun `ManualReplyConfigurationActivity allows 0s and sub-10s cooldown and launches ContactSelectorActivity`() {
        PreferencesManager.getPreferencesInstance(context).saveEnabledApps("com.whatsapp", true)
        val controller = Robolectric.buildActivity(ManualReplyConfigurationActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_WaReply)
        controller.create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()

        // Default starts at 0 seconds with Off chip selected
        assertEquals("0", activity.findViewById<TextInputEditText>(R.id.et_manual_cooldown_seconds).text.toString())
        assertTrue(activity.findViewById<Chip>(R.id.chip_manual_cd_off).isChecked)

        activity.findViewById<MaterialSwitch>(R.id.switch_manual_contact_filter).isChecked = true
        activity.findViewById<View>(R.id.btn_choose_manual_contacts).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val contactsIntent = shadowOf(activity).nextStartedActivityForResult?.intent
            ?: shadowOf(activity).nextStartedActivity
        assertNotNull("Expected ContactSelectorActivity to be launched", contactsIntent)
        assertEquals(ContactSelectorActivity::class.java.name, contactsIntent?.component?.className)
        assertTrue(PreferencesManager.getPreferencesInstance(context).isContactReplyEnabled)

        // Sub-10s cooldown (2 seconds) is saved directly without clamping to 10s
        activity.findViewById<TextInputEditText>(R.id.et_default_manual_reply)
            .setText("Busy right now.")
        activity.findViewById<TextInputEditText>(R.id.et_manual_cooldown_seconds)
            .setText("2")
        activity.findViewById<View>(R.id.btn_save_manual_config).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2_000L, PreferencesManager.getPreferencesInstance(context).autoReplyDelay)

        // Off chip resets to 0s and shows "No cooldown" in CustomReplyEditorActivity
        val controller2 = Robolectric.buildActivity(ManualReplyConfigurationActivity::class.java)
        val activity2 = controller2.get()
        activity2.setTheme(R.style.Theme_WaReply)
        controller2.create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()

        activity2.findViewById<Chip>(R.id.chip_manual_cd_off).performClick()
        activity2.findViewById<View>(R.id.btn_save_manual_config).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0L, PreferencesManager.getPreferencesInstance(context).autoReplyDelay)

        val editorController = Robolectric.buildActivity(CustomReplyEditorActivity::class.java)
        val editorActivity = editorController.get()
        editorActivity.setTheme(R.style.Theme_WaReply)
        editorController.create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()

        val statusText = editorActivity.findViewById<TextView>(R.id.manual_status_text).text.toString()
        assertTrue(statusText.contains("No cooldown"))
        editorController.pause().stop().destroy()
    }

    @Test
    fun `CreateEditRuleActivity in manual rule mode hides AI toggle and keeps AI unchecked`() {
        val launchIntent = Intent(context, CreateEditRuleActivity::class.java).apply {
            putExtra(CreateEditRuleActivity.EXTRA_MANUAL_RULE_ONLY, true)
        }
        val controller = Robolectric.buildActivity(CreateEditRuleActivity::class.java, launchIntent)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_WaReply)
        controller.create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()

        val aiToggleContainer = activity.findViewById<View>(R.id.layout_use_ai_toggle)
        val aiSwitch = activity.findViewById<MaterialSwitch>(R.id.switch_use_ai_reply)
        assertEquals(View.GONE, aiToggleContainer.visibility)
        assertFalse(aiSwitch.isChecked)
        assertEquals(
            context.getString(R.string.create_manual_rule_title),
            activity.supportActionBar?.title?.toString()
        )

        controller.pause().stop().destroy()
    }
}
