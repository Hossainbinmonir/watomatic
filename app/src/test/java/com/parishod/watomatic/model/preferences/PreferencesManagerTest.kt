package com.parishod.watomatic.model.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.parishod.watomatic.model.App
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.GeneralSecurityException
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PreferencesManagerTest {

    private lateinit var context: Context
    private lateinit var prefs: PreferencesManager

    @Before
    fun setUp() {
        PreferencesManager.resetInstance()
        context = ApplicationProvider.getApplicationContext()
        // Clear default shared prefs and test prefs to get a truly fresh state each test
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences(PreferencesManager.LEGACY_FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(PreferencesManager.SECURE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("test_simulated_encrypted_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs = PreferencesManager.getPreferencesInstance(context)
    }

    @After
    fun tearDown() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences(PreferencesManager.LEGACY_FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(PreferencesManager.SECURE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("test_simulated_encrypted_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        PreferencesManager.resetInstance()
    }

    // --- Service enabled ---

    @Test
    fun `isServiceEnabled defaults to false`() {
        assertFalse(prefs.isServiceEnabled)
    }

    @Test
    fun `setServicePref to true enables service`() {
        prefs.setServicePref(true)
        assertTrue(prefs.isServiceEnabled)
    }

    @Test
    fun `setServicePref to false disables service`() {
        prefs.setServicePref(true)
        prefs.setServicePref(false)
        assertFalse(prefs.isServiceEnabled)
    }

    // --- Group reply ---

    @Test
    fun `isGroupReplyEnabled defaults to false`() {
        assertFalse(prefs.isGroupReplyEnabled)
    }

    @Test
    fun `setGroupReplyPref to true enables group reply`() {
        prefs.setGroupReplyPref(true)
        assertTrue(prefs.isGroupReplyEnabled)
    }

    @Test
    fun `setGroupReplyPref to false disables group reply`() {
        prefs.setGroupReplyPref(true)
        prefs.setGroupReplyPref(false)
        assertFalse(prefs.isGroupReplyEnabled)
    }

    // --- Auto reply delay ---

    @Test
    fun `getAutoReplyDelay defaults to 0`() {
        assertEquals(0L, prefs.autoReplyDelay)
    }

    @Test
    fun `setAutoReplyDelay persists the value`() {
        prefs.setAutoReplyDelay(5000L)
        assertEquals(5000L, prefs.autoReplyDelay)
    }

    @Test
    fun `setAutoReplyDelay stores zero correctly`() {
        prefs.setAutoReplyDelay(5000L)
        prefs.setAutoReplyDelay(0L)
        assertEquals(0L, prefs.autoReplyDelay)
    }

    // --- Watomatic attribution ---

    @Test
    fun `isAppendWatomaticAttributionEnabled defaults to false`() {
        assertFalse(prefs.isAppendWatomaticAttributionEnabled)
    }

    @Test
    fun `setAppendWatomaticAttribution to true enables it`() {
        prefs.setAppendWatomaticAttribution(true)
        assertTrue(prefs.isAppendWatomaticAttributionEnabled)
    }

    // --- Contact reply ---

    @Test
    fun `isContactReplyEnabled defaults to false`() {
        assertFalse(prefs.isContactReplyEnabled)
    }

    @Test
    fun `setContactReplyEnabled persists value`() {
        prefs.setContactReplyEnabled(true)
        assertTrue(prefs.isContactReplyEnabled)
    }

    // --- Contact reply mode (blacklist vs whitelist) ---

    @Test
    fun `isContactReplyBlacklistMode defaults to true`() {
        assertTrue(prefs.isContactReplyBlacklistMode)
    }

    @Test
    fun `setContactReplyBlacklistMode false switches to whitelist mode`() {
        prefs.setContactReplyBlacklistMode(false)
        assertFalse(prefs.isContactReplyBlacklistMode)
    }

    @Test
    fun `setContactReplyBlacklistMode true restores blacklist mode`() {
        prefs.setContactReplyBlacklistMode(false)
        prefs.setContactReplyBlacklistMode(true)
        assertTrue(prefs.isContactReplyBlacklistMode)
    }

    // --- AI replies ---

    @Test
    fun `isAutomaticAiRepliesEnabled defaults to false`() {
        assertFalse(prefs.isAutomaticAiRepliesEnabled)
    }

    @Test
    fun `setEnableAutomaticAiReplies enables automatic AI`() {
        prefs.setEnableAutomaticAiReplies(true)
        assertTrue(prefs.isAutomaticAiRepliesEnabled)
    }

    @Test
    fun `setEnableAutomaticAiReplies disables automatic AI`() {
        prefs.setEnableAutomaticAiReplies(true)
        prefs.setEnableAutomaticAiReplies(false)
        assertFalse(prefs.isAutomaticAiRepliesEnabled)
    }

    @Test
    fun `isByokRepliesEnabled defaults to false`() {
        assertFalse(prefs.isByokRepliesEnabled)
    }

    @Test
    fun `setEnableByokReplies enables BYOK`() {
        prefs.setEnableByokReplies(true)
        assertTrue(prefs.isByokRepliesEnabled)
    }

    @Test
    fun `isAnyAiRepliesEnabled returns false when both disabled`() {
        assertFalse(prefs.isAnyAiRepliesEnabled)
    }

    @Test
    fun `isAnyAiRepliesEnabled returns true when automatic AI enabled`() {
        prefs.setEnableAutomaticAiReplies(true)
        assertTrue(prefs.isAnyAiRepliesEnabled)
    }

    @Test
    fun `isAnyAiRepliesEnabled returns true when BYOK enabled`() {
        prefs.setEnableByokReplies(true)
        assertTrue(prefs.isAnyAiRepliesEnabled)
    }

    @Test
    fun `isAnyAiRepliesEnabled returns true when both enabled`() {
        prefs.setEnableAutomaticAiReplies(true)
        prefs.setEnableByokReplies(true)
        assertTrue(prefs.isAnyAiRepliesEnabled)
    }

    // --- Login state ---

    @Test
    fun `isLoggedIn defaults to false`() {
        assertFalse(prefs.isLoggedIn)
    }

    @Test
    fun `setLoggedIn to true persists`() {
        prefs.setLoggedIn(true)
        assertTrue(prefs.isLoggedIn)
    }

    @Test
    fun `setLoggedIn to false persists`() {
        prefs.setLoggedIn(true)
        prefs.setLoggedIn(false)
        assertFalse(prefs.isLoggedIn)
    }

    // --- Guest mode ---

    @Test
    fun `isGuestMode defaults to false`() {
        assertFalse(prefs.isGuestMode)
    }

    @Test
    fun `setGuestMode to true persists`() {
        prefs.setGuestMode(true)
        assertTrue(prefs.isGuestMode)
    }

    // --- shouldShowLogin ---

    @Test
    fun `shouldShowLogin returns true when not logged in and not in guest mode`() {
        assertTrue(prefs.shouldShowLogin())
    }

    @Test
    fun `shouldShowLogin returns false when logged in`() {
        prefs.setLoggedIn(true)
        assertFalse(prefs.shouldShowLogin())
    }

    @Test
    fun `shouldShowLogin returns false in guest mode`() {
        prefs.setGuestMode(true)
        assertFalse(prefs.shouldShowLogin())
    }

    // --- Subscription ---

    @Test
    fun `isSubscriptionActive defaults to false`() {
        assertFalse(prefs.isSubscriptionActive)
    }

    @Test
    fun `isSubscriptionActive returns true when active with zero expiry time`() {
        prefs.setSubscriptionActive(true)
        // expiry = 0 means lifetime/unknown → considered active
        assertTrue(prefs.isSubscriptionActive)
    }

    @Test
    fun `isSubscriptionActive returns true when active and not yet expired`() {
        prefs.setSubscriptionActive(true)
        prefs.setSubscriptionExpiryTime(System.currentTimeMillis() + 60_000L) // expires in 1 min
        assertTrue(prefs.isSubscriptionActive)
    }

    @Test
    fun `isSubscriptionActive returns false when active but already expired`() {
        prefs.setSubscriptionActive(true)
        prefs.setSubscriptionExpiryTime(System.currentTimeMillis() - 1_000L) // expired 1 sec ago
        assertFalse(prefs.isSubscriptionActive)
    }

    @Test
    fun `isSubscriptionActive returns false when inactive regardless of expiry`() {
        prefs.setSubscriptionActive(false)
        prefs.setSubscriptionExpiryTime(System.currentTimeMillis() + 60_000L)
        assertFalse(prefs.isSubscriptionActive)
    }

    @Test
    fun `getSubscriptionExpiryTime defaults to 0`() {
        assertEquals(0L, prefs.subscriptionExpiryTime)
    }

    @Test
    fun `setSubscriptionExpiryTime persists value`() {
        val time = System.currentTimeMillis() + 86_400_000L
        prefs.setSubscriptionExpiryTime(time)
        assertEquals(time, prefs.subscriptionExpiryTime)
    }

    @Test
    fun `getSubscriptionProductId defaults to empty string`() {
        assertEquals("", prefs.subscriptionProductId)
    }

    @Test
    fun `setSubscriptionProductId persists value`() {
        prefs.setSubscriptionProductId("pro_monthly")
        assertEquals("pro_monthly", prefs.subscriptionProductId)
    }

    @Test
    fun `isSubscriptionAutoRenewing defaults to false`() {
        assertFalse(prefs.isSubscriptionAutoRenewing)
    }

    @Test
    fun `setSubscriptionAutoRenewing persists value`() {
        prefs.setSubscriptionAutoRenewing(true)
        assertTrue(prefs.isSubscriptionAutoRenewing)
    }

    // --- Remaining atoms ---

    @Test
    fun `getRemainingAtoms defaults to minus one`() {
        assertEquals(-1, prefs.getRemainingAtoms())
    }

    @Test
    fun `setRemainingAtoms persists value`() {
        prefs.setRemainingAtoms(100)
        assertEquals(100, prefs.getRemainingAtoms())
    }

    @Test
    fun `setRemainingAtoms persists zero`() {
        prefs.setRemainingAtoms(0)
        assertEquals(0, prefs.getRemainingAtoms())
    }

    // --- Quota notification ---

    @Test
    fun `getQuotaNotificationLastShown defaults to 0`() {
        assertEquals(0L, prefs.getQuotaNotificationLastShown())
    }

    @Test
    fun `setQuotaNotificationLastShown persists value`() {
        val time = System.currentTimeMillis()
        prefs.setQuotaNotificationLastShown(time)
        assertEquals(time, prefs.getQuotaNotificationLastShown())
    }

    // --- Firebase token ---

    @Test
    fun `getFirebaseToken defaults to empty string`() {
        assertEquals("", prefs.firebaseToken)
    }

    @Test
    fun `setFirebaseToken persists value`() {
        prefs.setFirebaseToken("test-firebase-token-123")
        assertEquals("test-firebase-token-123", prefs.firebaseToken)
    }

    // --- Fallback message ---

    @Test
    fun `getFallbackMessage defaults to empty string`() {
        assertEquals("", prefs.fallbackMessage)
    }

    @Test
    fun `saveFallbackMessage persists value`() {
        prefs.saveFallbackMessage("Sorry, I'm away right now")
        assertEquals("Sorry, I'm away right now", prefs.fallbackMessage)
    }

    // --- AI custom prompts ---

    @Test
    fun `getOpenAICustomPrompt returns null by default`() {
        assertNull(prefs.openAICustomPrompt)
    }

    @Test
    fun `saveOpenAICustomPrompt persists value`() {
        prefs.saveOpenAICustomPrompt("Reply concisely")
        assertEquals("Reply concisely", prefs.openAICustomPrompt)
    }

    @Test
    fun `getAtomaticAICustomPrompt defaults to empty string`() {
        assertEquals("", prefs.atomaticAICustomPrompt)
    }

    @Test
    fun `saveAtomaticAICustomPrompt persists value`() {
        prefs.saveAtomaticAICustomPrompt("Be brief and friendly")
        assertEquals("Be brief and friendly", prefs.atomaticAICustomPrompt)
    }

    // --- AI provider source ---

    @Test
    fun `getOpenApiSource defaults to openai`() {
        assertEquals("openai", prefs.openApiSource)
    }

    @Test
    fun `saveOpenApiSource persists value`() {
        prefs.saveOpenApiSource("Claude")
        assertEquals("Claude", prefs.openApiSource)
    }

    // --- Custom OpenAI URL ---

    @Test
    fun `getCustomOpenAIApiUrl defaults to null`() {
        assertNull(prefs.customOpenAIApiUrl)
    }

    @Test
    fun `saveCustomOpenAIApiUrl persists value`() {
        prefs.saveCustomOpenAIApiUrl("https://my-api.example.com/")
        assertEquals("https://my-api.example.com/", prefs.customOpenAIApiUrl)
    }

    // --- OpenAI model ---

    @Test
    fun `getSelectedOpenAIModel defaults to null`() {
        assertNull(prefs.selectedOpenAIModel)
    }

    @Test
    fun `saveSelectedOpenAIModel persists value`() {
        prefs.saveSelectedOpenAIModel("gpt-4o")
        assertEquals("gpt-4o", prefs.selectedOpenAIModel)
    }

    // --- User email ---

    @Test
    fun `getUserEmail defaults to empty string`() {
        assertEquals("", prefs.userEmail)
    }

    @Test
    fun `setUserEmail persists value`() {
        prefs.setUserEmail("user@example.com")
        assertEquals("user@example.com", prefs.userEmail)
    }

    // --- Locale parsing ---

    @Test
    fun `getSelectedLocale returns device default when no language set`() {
        val locale = prefs.selectedLocale
        assertNotNull(locale)
        assertEquals(Locale.getDefault(), locale)
    }

    @Test
    fun `getSelectedLocale parses language-region format correctly`() {
        prefs.setLanguageStr("en-US")
        val locale = prefs.selectedLocale
        assertEquals("en", locale.language)
        assertEquals("US", locale.country)
    }

    @Test
    fun `getSelectedLocale parses language-only format correctly`() {
        prefs.setLanguageStr("de")
        val locale = prefs.selectedLocale
        assertEquals("de", locale.language)
    }

    @Test
    fun `getSelectedLocale parses Chinese simplified correctly`() {
        prefs.setLanguageStr("zh-CN")
        val locale = prefs.selectedLocale
        assertEquals("zh", locale.language)
        assertEquals("CN", locale.country)
    }

    // --- Legacy language key migration ---

    @Test
    fun `updateLegacyLanguageKey migrates old format with r prefix`() {
        prefs.setLanguageStr("zh-rCN")
        prefs.updateLegacyLanguageKey()
        assertEquals("zh-CN", prefs.getSelectedLanguageStr(null))
    }

    @Test
    fun `updateLegacyLanguageKey does not change modern language-country format`() {
        prefs.setLanguageStr("zh-CN")
        prefs.updateLegacyLanguageKey()
        assertEquals("zh-CN", prefs.getSelectedLanguageStr(null))
    }

    @Test
    fun `updateLegacyLanguageKey does nothing when no language set`() {
        // Should not throw when called with no language stored
        prefs.updateLegacyLanguageKey()
        assertNull(prefs.getSelectedLanguageStr(null))
    }

    @Test
    fun `updateLegacyLanguageKey does not change language-only value`() {
        prefs.setLanguageStr("de")
        prefs.updateLegacyLanguageKey()
        assertEquals("de", prefs.getSelectedLanguageStr(null))
    }

    // --- Persistent AI errors ---

    @Test
    fun `getOpenAILastPersistentErrorMessage returns null by default`() {
        assertNull(prefs.openAILastPersistentErrorMessage)
    }

    @Test
    fun `saveOpenAILastPersistentError persists message and timestamp`() {
        val ts = System.currentTimeMillis()
        prefs.saveOpenAILastPersistentError("Rate limit exceeded", ts)
        assertEquals("Rate limit exceeded", prefs.openAILastPersistentErrorMessage)
        assertEquals(ts, prefs.openAILastPersistentErrorTimestamp)
    }

    @Test
    fun `clearOpenAILastPersistentError removes both message and timestamp`() {
        prefs.saveOpenAILastPersistentError("Some error", System.currentTimeMillis())
        prefs.clearOpenAILastPersistentError()
        assertNull(prefs.openAILastPersistentErrorMessage)
        assertEquals(0L, prefs.openAILastPersistentErrorTimestamp)
    }

    // --- Last verified time ---

    @Test
    fun `getLastVerifiedTime defaults to 0`() {
        assertEquals(0L, prefs.lastVerifiedTime)
    }

    @Test
    fun `setLastVerifiedTime persists value`() {
        val time = System.currentTimeMillis()
        prefs.setLastVerifiedTime(time)
        assertEquals(time, prefs.lastVerifiedTime)
    }

    // --- Subscription plan ---

    @Test
    fun `getSubscriptionPlanType defaults to empty string`() {
        assertEquals("", prefs.subscriptionPlanType)
    }

    @Test
    fun `setSubscriptionPlanType persists value`() {
        prefs.setSubscriptionPlanType("pro")
        assertEquals("pro", prefs.subscriptionPlanType)
    }

    // --- Show notification preference ---

    @Test
    fun `isShowNotificationEnabled is true after new install`() {
        // init() calls setShowNotificationPref(true) for new installs (fresh cleared prefs)
        assertTrue(prefs.isShowNotificationEnabled)
    }

    @Test
    fun `setShowNotificationPref persists false`() {
        prefs.setShowNotificationPref(false)
        assertFalse(prefs.isShowNotificationEnabled)
    }

    @Test
    fun `setShowNotificationPref persists true`() {
        prefs.setShowNotificationPref(false)
        prefs.setShowNotificationPref(true)
        assertTrue(prefs.isShowNotificationEnabled)
    }

    // --- GitHub release notes ID ---

    @Test
    fun `getGithubReleaseNotesId defaults to 0`() {
        assertEquals(0, prefs.getGithubReleaseNotesId())
    }

    @Test
    fun `setGithubReleaseNotesId persists value`() {
        prefs.setGithubReleaseNotesId(42)
        assertEquals(42, prefs.getGithubReleaseNotesId())
    }

    @Test
    fun `setGithubReleaseNotesId overwrites previous value`() {
        prefs.setGithubReleaseNotesId(100)
        prefs.setGithubReleaseNotesId(200)
        assertEquals(200, prefs.getGithubReleaseNotesId())
    }

    // --- Last purged time ---

    @Test
    fun `getLastPurgedTime defaults to 0`() {
        assertEquals(0L, prefs.getLastPurgedTime())
    }

    @Test
    fun `setPurgeMessageTime persists value`() {
        val time = 1_234_567_890L
        prefs.setPurgeMessageTime(time)
        assertEquals(time, prefs.getLastPurgedTime())
    }

    // --- Play store rating ---

    @Test
    fun `getPlayStoreRatingStatus defaults to empty string`() {
        assertEquals("", prefs.getPlayStoreRatingStatus())
    }

    @Test
    fun `setPlayStoreRatingStatus persists value`() {
        prefs.setPlayStoreRatingStatus("done")
        assertEquals("done", prefs.getPlayStoreRatingStatus())
    }

    @Test
    fun `getPlayStoreRatingLastTime defaults to 0`() {
        assertEquals(0L, prefs.getPlayStoreRatingLastTime())
    }

    @Test
    fun `setPlayStoreRatingLastTime persists value`() {
        val time = 9_876_543_210L
        prefs.setPlayStoreRatingLastTime(time)
        assertEquals(time, prefs.getPlayStoreRatingLastTime())
    }

    // --- Foreground service notification ---

    @Test
    fun `isForegroundServiceNotificationEnabled defaults to false`() {
        assertFalse(prefs.isForegroundServiceNotificationEnabled)
    }

    @Test
    fun `setShowForegroundServiceNotification persists true`() {
        prefs.setShowForegroundServiceNotification(true)
        assertTrue(prefs.isForegroundServiceNotificationEnabled)
    }

    @Test
    fun `setShowForegroundServiceNotification persists false`() {
        prefs.setShowForegroundServiceNotification(true)
        prefs.setShowForegroundServiceNotification(false)
        assertFalse(prefs.isForegroundServiceNotificationEnabled)
    }

    // --- Reply to names ---

    @Test
    fun `getReplyToNames defaults to empty set`() {
        assertTrue(prefs.getReplyToNames().isEmpty())
    }

    @Test
    fun `setReplyToNames persists single name`() {
        prefs.setReplyToNames(setOf("Alice"))
        assertEquals(setOf("Alice"), prefs.getReplyToNames())
    }

    @Test
    fun `setReplyToNames persists multiple names`() {
        val names = setOf("Alice", "Bob", "Charlie")
        prefs.setReplyToNames(names)
        assertEquals(names, prefs.getReplyToNames())
    }

    @Test
    fun `setReplyToNames can overwrite with empty set`() {
        prefs.setReplyToNames(setOf("Alice"))
        prefs.setReplyToNames(emptySet())
        assertTrue(prefs.getReplyToNames().isEmpty())
    }

    // --- Custom reply names ---

    @Test
    fun `getCustomReplyNames defaults to empty set`() {
        assertTrue(prefs.getCustomReplyNames().isEmpty())
    }

    @Test
    fun `setCustomReplyNames persists names`() {
        val names = setOf("Alice", "Bob")
        prefs.setCustomReplyNames(names)
        assertEquals(names, prefs.getCustomReplyNames())
    }

    // --- Generic getString / saveString ---

    @Test
    fun `saveString and getString round trip`() {
        prefs.saveString("test_custom_key", "test_custom_value")
        assertEquals("test_custom_value", prefs.getString("test_custom_key", "default"))
    }

    @Test
    fun `getString returns default when key not present`() {
        assertEquals("my_default", prefs.getString("nonexistent_key_xyz_abc", "my_default"))
    }

    @Test
    fun `saveString overwrites previous value`() {
        prefs.saveString("overwrite_key", "first_value")
        prefs.saveString("overwrite_key", "second_value")
        assertEquals("second_value", prefs.getString("overwrite_key", "default"))
    }

    // --- Subscription status last checked ---

    @Test
    fun `getSubscriptionStatusLastChecked defaults to 0`() {
        assertEquals(0L, prefs.getSubscriptionStatusLastChecked())
    }

    @Test
    fun `setSubscriptionStatusLastChecked persists value`() {
        val time = System.currentTimeMillis()
        prefs.setSubscriptionStatusLastChecked(time)
        assertEquals(time, prefs.getSubscriptionStatusLastChecked())
    }

    // --- Subscription product name ---

    @Test
    fun `getSubscriptionProductName defaults to empty string`() {
        assertEquals("", prefs.getSubscriptionProductName())
    }

    @Test
    fun `setSubscriptionProductName persists value`() {
        prefs.setSubscriptionProductName("Watomatic Pro Monthly")
        assertEquals("Watomatic Pro Monthly", prefs.getSubscriptionProductName())
    }

    // --- Deprecated isOpenAIRepliesEnabled ---

    @Test
    fun `isOpenAIRepliesEnabled defaults to false`() {
        @Suppress("DEPRECATION")
        assertFalse(prefs.isOpenAIRepliesEnabled())
    }

    @Test
    fun `setEnableOpenAIReplies persists value`() {
        prefs.setEnableOpenAIReplies(true)
        @Suppress("DEPRECATION")
        assertTrue(prefs.isOpenAIRepliesEnabled())
    }

    // --- Enabled apps ---

    @Test
    fun `saveEnabledApps by package name adds package`() {
        prefs.saveEnabledApps("com.whatsapp", true)
        assertTrue(prefs.isAppEnabled("com.whatsapp"))
    }

    @Test
    fun `saveEnabledApps by package name removes package`() {
        prefs.saveEnabledApps("com.whatsapp", true)
        prefs.saveEnabledApps("com.whatsapp", false)
        assertFalse(prefs.isAppEnabled("com.whatsapp"))
    }

    @Test
    fun `saveEnabledApps can add multiple packages`() {
        prefs.saveEnabledApps("com.whatsapp", true)
        prefs.saveEnabledApps("org.telegram.messenger", true)
        assertTrue(prefs.isAppEnabled("com.whatsapp"))
        assertTrue(prefs.isAppEnabled("org.telegram.messenger"))
    }

    @Test
    fun `saveEnabledApps with App object adds package`() {
        val app = App("WhatsApp", "com.whatsapp", false)
        prefs.saveEnabledApps(app, true)
        assertTrue(prefs.isAppEnabled(app))
    }

    @Test
    fun `saveEnabledApps with App object removes package`() {
        val app = App("WhatsApp", "com.whatsapp", false)
        prefs.saveEnabledApps(app, true)
        prefs.saveEnabledApps(app, false)
        assertFalse(prefs.isAppEnabled(app))
    }

    @Test
    fun `isAppEnabled returns false for package not in list`() {
        assertFalse(prefs.isAppEnabled("com.nonexistent.app.xyz"))
    }

    @Test
    fun `getEnabledApps returns set containing explicitly added package`() {
        prefs.saveEnabledApps("com.whatsapp", true)
        assertTrue(prefs.getEnabledApps().contains("com.whatsapp"))
    }

    // --- isFirstInstall ---

    @Test
    fun `isFirstInstall returns true in Robolectric test environment`() {
        // In Robolectric, firstInstallTime == lastUpdateTime (both 0), so returns true
        assertTrue(PreferencesManager.isFirstInstall(context))
    }

    // --- Edge cases ---

    @Test
    fun `getReplyToNames returns empty set when prefs have no value`() {
        val result = prefs.replyToNames
        assertNotNull(result)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `subscription timestamps handle Long MAX_VALUE`() {
        prefs.setSubscriptionStatusLastChecked(Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, prefs.getSubscriptionStatusLastChecked())
    }

    @Test
    fun `subscription timestamps handle 0`() {
        prefs.setSubscriptionStatusLastChecked(0L)
        assertEquals(0L, prefs.getSubscriptionStatusLastChecked())
    }

    @Test
    fun `getSelectedLanguage handles empty locale string`() {
        prefs.saveString("KEY_SELECTED_APP_LANGUAGE", "")
        val result = prefs.getString("KEY_SELECTED_APP_LANGUAGE", "en")
        assertNotNull(result)
    }

    @Test
    fun `resetInstance does not throw when called multiple times`() {
        PreferencesManager.resetInstance()
        PreferencesManager.resetInstance()
        prefs = PreferencesManager.getPreferencesInstance(context)
        assertNotNull(prefs)
    }

    // --- Phase 1 Security Hardening: Encrypted API key storage & legacy migration ---

    private fun simulatedEncryptedPrefs(): SharedPreferences =
        context.getSharedPreferences("test_simulated_encrypted_prefs", Context.MODE_PRIVATE)

    @Test
    fun `api key is stored only through encrypted storage and never in plaintext fallback`() {
        PreferencesManager.resetInstance()
        val encryptedStore = simulatedEncryptedPrefs()
        PreferencesManager.setEncryptedPrefsProviderForTesting { encryptedStore }
        val securePrefs = PreferencesManager.getPreferencesInstance(context)

        assertTrue(securePrefs.isSecureStorageAvailable)
        val saved = securePrefs.saveOpenAIApiKeySecurely("sk-encrypted-only-secret-123")
        assertTrue(saved)

        // Stored in encrypted store
        assertEquals("sk-encrypted-only-secret-123", encryptedStore.getString("pref_openai_api_key", null))
        // NOT stored in legacy fallback or default preferences
        val legacyPrefs = context.getSharedPreferences(PreferencesManager.LEGACY_FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        assertNull(legacyPrefs.getString("pref_openai_api_key", null))
        assertFalse(legacyPrefs.contains("pref_openai_api_key"))
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        assertNull(defaultPrefs.getString("pref_openai_api_key", null))
    }

    @Test
    fun `api key can be retrieved after successful storage and across instance reset`() {
        PreferencesManager.resetInstance()
        val encryptedStore = simulatedEncryptedPrefs()
        PreferencesManager.setEncryptedPrefsProviderForTesting { encryptedStore }
        var securePrefs = PreferencesManager.getPreferencesInstance(context)

        securePrefs.saveOpenAIApiKey("sk-retrieve-test-456")
        assertEquals("sk-retrieve-test-456", securePrefs.openAIApiKey)

        // Re-initialize singleton with same encrypted backing store
        PreferencesManager.resetInstance()
        PreferencesManager.setEncryptedPrefsProviderForTesting { encryptedStore }
        securePrefs = PreferencesManager.getPreferencesInstance(context)
        assertEquals("sk-retrieve-test-456", securePrefs.openAIApiKey)
    }

    @Test
    fun `api key deletion removes key from encrypted storage`() {
        PreferencesManager.resetInstance()
        val encryptedStore = simulatedEncryptedPrefs()
        PreferencesManager.setEncryptedPrefsProviderForTesting { encryptedStore }
        val securePrefs = PreferencesManager.getPreferencesInstance(context)

        securePrefs.saveOpenAIApiKey("sk-to-delete-789")
        assertEquals("sk-to-delete-789", securePrefs.openAIApiKey)

        val deleted = securePrefs.deleteOpenAIApiKeySecurely()
        assertTrue(deleted)
        assertNull(securePrefs.openAIApiKey)
        assertNull(encryptedStore.getString("pref_openai_api_key", null))
    }

    @Test
    fun `secure storage initialization failure does not trigger plaintext fallback`() {
        PreferencesManager.resetInstance()
        PreferencesManager.setEncryptedPrefsProviderForTesting {
            throw GeneralSecurityException("Keystore unavailable")
        }
        val failingPrefs = PreferencesManager.getPreferencesInstance(context)

        assertFalse(failingPrefs.isSecureStorageAvailable)
        assertNotNull(failingPrefs.secureStorageError)

        val secretKey = "sk-must-never-hit-plaintext"
        val saved = failingPrefs.saveOpenAIApiKeySecurely(secretKey)
        assertFalse(saved)
        assertNull(failingPrefs.openAIApiKey)
        assertFalse(failingPrefs.secureStorageError.orEmpty().contains(secretKey))

        val legacyPrefs = context.getSharedPreferences(PreferencesManager.LEGACY_FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        assertNull(legacyPrefs.getString("pref_openai_api_key", null))
        assertFalse(legacyPrefs.contains("pref_openai_api_key"))
    }

    @Test
    fun `legacy credential migration succeeds and preserves unrelated settings`() {
        val legacyPrefs = context.getSharedPreferences(PreferencesManager.LEGACY_FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        legacyPrefs.edit()
            .putString("pref_openai_api_key", "sk-legacy-migrated-key")
            .putString("unrelated_legacy_setting", "preserve-value")
            .commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString("pref_fallback_message", "Custom away message")
            .commit()

        PreferencesManager.resetInstance()
        val encryptedStore = simulatedEncryptedPrefs()
        PreferencesManager.setEncryptedPrefsProviderForTesting { encryptedStore }
        val securePrefs = PreferencesManager.getPreferencesInstance(context)

        assertTrue(securePrefs.isSecureStorageAvailable)
        assertEquals("sk-legacy-migrated-key", securePrefs.openAIApiKey)
        assertEquals("sk-legacy-migrated-key", encryptedStore.getString("pref_openai_api_key", null))

        // Migrated plaintext key removed after verification
        assertFalse(legacyPrefs.contains("pref_openai_api_key"))
        assertFalse(securePrefs.hasUnmigratedLegacyApiKey())
        // Unrelated settings preserved
        assertEquals("preserve-value", legacyPrefs.getString("unrelated_legacy_setting", null))
        assertEquals("Custom away message", securePrefs.fallbackMessage)
    }

    @Test
    fun `legacy credential is not deleted when secure storage is unavailable and recovers when restored`() {
        val legacyPrefs = context.getSharedPreferences(PreferencesManager.LEGACY_FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        legacyPrefs.edit().putString("pref_openai_api_key", "sk-legacy-pending-recovery").commit()

        PreferencesManager.resetInstance()
        PreferencesManager.setEncryptedPrefsProviderForTesting { null }
        val unavailablePrefs = PreferencesManager.getPreferencesInstance(context)

        // Must NOT silently use plaintext credential and must NOT delete it
        assertFalse(unavailablePrefs.isSecureStorageAvailable)
        assertNull(unavailablePrefs.openAIApiKey)
        assertTrue(unavailablePrefs.hasUnmigratedLegacyApiKey())
        assertEquals("sk-legacy-pending-recovery", legacyPrefs.getString("pref_openai_api_key", null))

        // Recover secure storage and verify safe migration completes
        val encryptedStore = simulatedEncryptedPrefs()
        PreferencesManager.setEncryptedPrefsProviderForTesting { encryptedStore }
        assertTrue(unavailablePrefs.retrySecureStorageInitialization())
        assertEquals("sk-legacy-pending-recovery", unavailablePrefs.openAIApiKey)
        assertFalse(legacyPrefs.contains("pref_openai_api_key"))
    }

    @Test
    fun `legacy credential is not deleted when encrypted write verification fails`() {
        val legacyPrefs = context.getSharedPreferences(PreferencesManager.LEGACY_FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        legacyPrefs.edit().putString("pref_openai_api_key", "sk-legacy-verify-fail").commit()

        // Fake encrypted prefs whose commit() fails so verification cannot succeed
        val failingCommitEditor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor = this
            override fun clear(): SharedPreferences.Editor = this
            override fun commit(): Boolean = false
            override fun apply() {}
        }
        val failingEncryptedPrefs = org.mockito.Mockito.mock(SharedPreferences::class.java)
        org.mockito.Mockito.`when`(failingEncryptedPrefs.getString("pref_openai_api_key", null)).thenReturn(null)
        org.mockito.Mockito.`when`(failingEncryptedPrefs.edit()).thenReturn(failingCommitEditor)

        PreferencesManager.resetInstance()
        PreferencesManager.setEncryptedPrefsProviderForTesting { failingEncryptedPrefs }
        val securePrefs = PreferencesManager.getPreferencesInstance(context)

        assertFalse(securePrefs.isSecureStorageAvailable)
        assertNull(securePrefs.openAIApiKey)
        // Plaintext legacy key must remain intact for future recovery
        assertEquals("sk-legacy-verify-fail", legacyPrefs.getString("pref_openai_api_key", null))
    }

    @Test
    fun `existing secure credentials are not destroyed during initialization failure`() {
        val encryptedStore = simulatedEncryptedPrefs()
        encryptedStore.edit().putString("pref_openai_api_key", "sk-existing-encrypted-key").commit()

        // Also seed watomatic_secure_prefs to verify constructor never clears the underlying prefs file
        val rawSecureFile = context.getSharedPreferences(PreferencesManager.SECURE_PREFS_NAME, Context.MODE_PRIVATE)
        rawSecureFile.edit().putString("encrypted_blob_sentinel", "do-not-wipe").commit()

        // Simulate initialization failure
        PreferencesManager.resetInstance()
        PreferencesManager.setEncryptedPrefsProviderForTesting {
            throw GeneralSecurityException("Transient keystore lock")
        }
        val failedInitPrefs = PreferencesManager.getPreferencesInstance(context)
        assertFalse(failedInitPrefs.isSecureStorageAvailable)
        assertNull(failedInitPrefs.openAIApiKey)

        // Underlying prefs file and encrypted credential must remain untouched
        assertEquals("do-not-wipe", rawSecureFile.getString("encrypted_blob_sentinel", null))
        assertEquals("sk-existing-encrypted-key", encryptedStore.getString("pref_openai_api_key", null))

        // Recovering keystore restores access to existing credential
        PreferencesManager.setEncryptedPrefsProviderForTesting { encryptedStore }
        assertTrue(failedInitPrefs.retrySecureStorageInitialization())
        assertEquals("sk-existing-encrypted-key", failedInitPrefs.openAIApiKey)
    }

    @Test
    fun `legacy migration preserves existing encrypted credential instead of overwriting it`() {
        val encryptedStore = simulatedEncryptedPrefs()
        encryptedStore.edit().putString("pref_openai_api_key", "sk-newer-encrypted-key").commit()

        val legacyPrefs = context.getSharedPreferences(PreferencesManager.LEGACY_FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        legacyPrefs.edit().putString("pref_openai_api_key", "sk-older-legacy-key").commit()

        PreferencesManager.resetInstance()
        PreferencesManager.setEncryptedPrefsProviderForTesting { encryptedStore }
        val securePrefs = PreferencesManager.getPreferencesInstance(context)

        assertEquals("sk-newer-encrypted-key", securePrefs.openAIApiKey)
        assertFalse(legacyPrefs.contains("pref_openai_api_key"))
    }

    @Test
    fun `saveOpenAIApiKeySecurely returns false and marks storage unavailable when commit returns false`() {
        val failingCommitEditor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor = this
            override fun clear(): SharedPreferences.Editor = this
            override fun commit(): Boolean = false
            override fun apply() {}
        }
        val encryptedStore = simulatedEncryptedPrefs()
        val spyStore = object : SharedPreferences by encryptedStore {
            override fun edit(): SharedPreferences.Editor = failingCommitEditor
        }

        PreferencesManager.resetInstance()
        PreferencesManager.setEncryptedPrefsProviderForTesting { spyStore }
        val securePrefs = PreferencesManager.getPreferencesInstance(context)
        assertTrue(securePrefs.isSecureStorageAvailable)

        val secretKey = "sk-commit-false-secret"
        assertFalse(securePrefs.saveOpenAIApiKeySecurely(secretKey))
        assertFalse(securePrefs.isSecureStorageAvailable)
        assertNotNull(securePrefs.secureStorageError)
        assertFalse(securePrefs.secureStorageError.orEmpty().contains(secretKey))
        assertNull(securePrefs.openAIApiKey)
        val legacyPrefs = context.getSharedPreferences(PreferencesManager.LEGACY_FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        assertFalse(legacyPrefs.contains("pref_openai_api_key"))
    }

    @Test
    fun `saveOpenAIApiKeySecurely returns false and marks storage unavailable when read-back mismatches`() {
        val backing = simulatedEncryptedPrefs()
        val mismatchingEditor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor = this
            override fun clear(): SharedPreferences.Editor = this
            override fun commit(): Boolean {
                // Commit claims true, but corrupts stored value so read-back mismatches
                backing.edit().putString("pref_openai_api_key", "corrupted-value").commit()
                return true
            }
            override fun apply() {}
        }
        val storeWithCorruptWrite = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor = mismatchingEditor
        }

        PreferencesManager.resetInstance()
        PreferencesManager.setEncryptedPrefsProviderForTesting { storeWithCorruptWrite }
        val securePrefs = PreferencesManager.getPreferencesInstance(context)

        val secretKey = "sk-expected-secret-key"
        assertFalse(securePrefs.saveOpenAIApiKeySecurely(secretKey))
        assertFalse(securePrefs.isSecureStorageAvailable)
        assertNotNull(securePrefs.secureStorageError)
        assertFalse(securePrefs.secureStorageError.orEmpty().contains(secretKey))
        assertNull(securePrefs.openAIApiKey)
        val legacyPrefs = context.getSharedPreferences(PreferencesManager.LEGACY_FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        assertFalse(legacyPrefs.contains("pref_openai_api_key"))
    }

    @Test
    fun `saveOpenAIApiKeySecurely returns false when write throws exception`() {
        val backing = simulatedEncryptedPrefs()
        val throwingStore = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor {
                throw SecurityException("Keystore write failure for secret payload")
            }
        }

        PreferencesManager.resetInstance()
        PreferencesManager.setEncryptedPrefsProviderForTesting { throwingStore }
        val securePrefs = PreferencesManager.getPreferencesInstance(context)

        val secretKey = "sk-exception-secret-key"
        assertFalse(securePrefs.saveOpenAIApiKeySecurely(secretKey))
        assertFalse(securePrefs.isSecureStorageAvailable)
        assertNotNull(securePrefs.secureStorageError)
        assertFalse(securePrefs.secureStorageError.orEmpty().contains("secret payload"))
        assertFalse(securePrefs.secureStorageError.orEmpty().contains(secretKey))
        assertNull(securePrefs.openAIApiKey)
    }

    @Test
    fun `saveOpenAIApiKeySecurely rejects empty or whitespace keys without polluting secureStorageError`() {
        PreferencesManager.resetInstance()
        val encryptedStore = simulatedEncryptedPrefs()
        PreferencesManager.setEncryptedPrefsProviderForTesting { encryptedStore }
        val securePrefs = PreferencesManager.getPreferencesInstance(context)

        assertFalse(securePrefs.saveOpenAIApiKeySecurely(""))
        assertFalse(securePrefs.saveOpenAIApiKeySecurely("   "))
        assertFalse(securePrefs.saveOpenAIApiKeySecurely(null))
        assertTrue(securePrefs.isSecureStorageAvailable)
        assertNull(securePrefs.secureStorageError)
        assertNull(securePrefs.openAIApiKey)
    }

    @Test
    fun `retrySecureStorageInitialization clears stale error after commit failure recovery`() {
        val backing = simulatedEncryptedPrefs()
        var failCommit = true
        val toggleableEditor = object : SharedPreferences.Editor by backing.edit() {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (!failCommit) {
                    backing.edit().putString(key, value).commit()
                }
                return this
            }
            override fun commit(): Boolean = !failCommit
        }
        val toggleableStore = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor = if (failCommit) toggleableEditor else backing.edit()
        }

        PreferencesManager.resetInstance()
        PreferencesManager.setEncryptedPrefsProviderForTesting { toggleableStore }
        val securePrefs = PreferencesManager.getPreferencesInstance(context)

        assertFalse(securePrefs.saveOpenAIApiKeySecurely("sk-transient-fail"))
        assertFalse(securePrefs.isSecureStorageAvailable)
        assertNotNull(securePrefs.secureStorageError)

        failCommit = false
        assertTrue(securePrefs.retrySecureStorageInitialization())
        assertTrue(securePrefs.isSecureStorageAvailable)
        assertNull(securePrefs.secureStorageError)
    }
}
