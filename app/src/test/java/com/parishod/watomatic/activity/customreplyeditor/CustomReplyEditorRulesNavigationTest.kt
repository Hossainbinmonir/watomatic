package com.parishod.watomatic.activity.customreplyeditor

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.parishod.watomatic.R
import com.parishod.watomatic.activity.rules.CreateEditRuleActivity
import com.parishod.watomatic.activity.rules.RuleDashboardActivity
import com.parishod.watomatic.activity.rules.RuleReplyTypeFilter
import com.parishod.watomatic.model.CustomRepliesData
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.rules.AutoReplyRule
import com.parishod.watomatic.model.rules.AutoReplyRulesDatabase
import com.parishod.watomatic.model.rules.MatchType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `ManualReplyConfigurationActivity allows creating separate manual rules and managing manual rules`() {
        val controller = Robolectric.buildActivity(ManualReplyConfigurationActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_WaReply)
        controller.create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()

        val createBtn = activity.findViewById<View>(R.id.btn_create_manual_rule)
        assertNotNull(createBtn)
        assertEquals(View.VISIBLE, createBtn.visibility)
        createBtn.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val createIntent = shadowOf(activity).nextStartedActivity
        assertNotNull("Expected CreateEditRuleActivity to be launched", createIntent)
        assertEquals(CreateEditRuleActivity::class.java.name, createIntent.component?.className)
        assertTrue(
            "Expected EXTRA_MANUAL_RULE_ONLY=true when creating rule from Manual Reply settings",
            createIntent.getBooleanExtra(CreateEditRuleActivity.EXTRA_MANUAL_RULE_ONLY, false)
        )

        val manageBtn = activity.findViewById<View>(R.id.btn_manage_manual_rules)
        assertNotNull(manageBtn)
        manageBtn.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val dashboardIntent = shadowOf(activity).nextStartedActivity
        assertNotNull("Expected RuleDashboardActivity to be launched", dashboardIntent)
        assertEquals(RuleDashboardActivity::class.java.name, dashboardIntent.component?.className)
        assertEquals(
            RuleReplyTypeFilter.MANUAL.name,
            dashboardIntent.getStringExtra(RuleDashboardActivity.EXTRA_REPLY_TYPE_FILTER)
        )

        controller.pause().stop().destroy()
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

    @Test
    fun `ManualReplyConfigurationActivity saves default manual reply message and lists only manual rules`() {
        val db = AutoReplyRulesDatabase.getInstance(context)
        val manualRuleId: Long = runBlocking {
            withContext(Dispatchers.IO) {
                val id = db.ruleDao().insertRule(
                    AutoReplyRule(
                        name = "Price List",
                        isEnabled = true,
                        selectedPackages = listOf("com.whatsapp"),
                        matchType = MatchType.CONTAINS,
                        keywords = listOf("price"),
                        replyMessage = "Pricing starts at $10.",
                        useAiReply = false
                    )
                )
                db.ruleDao().insertRule(
                    AutoReplyRule(
                        name = "AI Support Rule",
                        isEnabled = true,
                        selectedPackages = listOf("com.whatsapp"),
                        matchType = MatchType.CONTAINS,
                        keywords = listOf("help"),
                        replyMessage = "Be friendly.",
                        useAiReply = true
                    )
                )
                id
            }
        }

        val controller = Robolectric.buildActivity(ManualReplyConfigurationActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_WaReply)
        controller.create().start().resume()

        val listContainer = activity.findViewById<LinearLayout>(R.id.manual_rules_list_container)
        val deadline = System.currentTimeMillis() + 5000L
        while (listContainer.childCount == 0 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (listContainer.childCount == 0) {
                Thread.sleep(25)
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, listContainer.childCount)
        val firstCard = listContainer.getChildAt(0)
        assertEquals("Price List", firstCard.findViewById<TextView>(R.id.tv_compact_rule_name).text.toString())

        // Tapping the rule card opens CreateEditRuleActivity for editing in manual rule mode
        firstCard.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val editIntent = shadowOf(activity).nextStartedActivity
        assertNotNull(editIntent)
        assertEquals(manualRuleId, editIntent.getLongExtra(CreateEditRuleActivity.EXTRA_RULE_ID, -1L))
        assertTrue(editIntent.getBooleanExtra(CreateEditRuleActivity.EXTRA_MANUAL_RULE_ONLY, false))

        // Saving default manual reply persists cleanly
        val replyInput = activity.findViewById<TextInputEditText>(R.id.et_default_manual_reply)
        replyInput.setText("In a meeting, will reply soon.")
        activity.findViewById<View>(R.id.btn_save_default_manual_reply).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "In a meeting, will reply soon.",
            CustomRepliesData.getInstance(context).get()
        )

        controller.pause().stop().destroy()
    }
}
