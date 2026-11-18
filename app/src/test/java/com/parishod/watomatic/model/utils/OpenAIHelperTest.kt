package com.parishod.watomatic.model.utils

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.network.model.openai.ModelData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.lang.reflect.Field

/**
 * Tests for OpenAIHelper cache logic.
 * Uses Robolectric because OpenAIHelper.invalidateCache() calls Log.d().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OpenAIHelperTest {

    private fun createModelData(id: String): ModelData {
        val model = ModelData()
        model.id = id
        return model
    }

    @Before
    fun setUp() {
        OpenAIHelper.invalidateCache()
        ShadowLog.clear()
        PreferencesManager.resetInstance()
    }

    @After
    fun tearDown() {
        OpenAIHelper.invalidateCache()
        PreferencesManager.resetInstance()
        ShadowLog.clear()
    }

    // --- getCachedModels ---

    @Test
    fun `getCachedModels returns null after invalidation`() {
        assertNull(OpenAIHelper.getCachedModels())
    }

    @Test
    fun `getCachedModels returns models after setting cache via reflection`() {
        val models = listOf(createModelData("gpt-4"), createModelData("gpt-3.5-turbo"))
        setCachedModelsViaReflection(models)
        setLastFetchTimeViaReflection(System.currentTimeMillis())

        val result = OpenAIHelper.getCachedModels()
        assertEquals(2, result?.size)
        assertEquals("gpt-4", result?.get(0)?.id)
    }

    // --- isCacheValid ---

    @Test
    fun `isCacheValid returns false when cache is null`() {
        assertFalse(OpenAIHelper.isCacheValid())
    }

    @Test
    fun `isCacheValid returns false when cache is empty list`() {
        setCachedModelsViaReflection(emptyList())
        setLastFetchTimeViaReflection(System.currentTimeMillis())
        assertFalse(OpenAIHelper.isCacheValid())
    }

    @Test
    fun `isCacheValid returns false when cache is older than 1 hour`() {
        val models = listOf(createModelData("gpt-4"))
        setCachedModelsViaReflection(models)
        // Set fetch time to 2 hours ago
        setLastFetchTimeViaReflection(System.currentTimeMillis() - 2 * 60 * 60 * 1000)
        assertFalse(OpenAIHelper.isCacheValid())
    }

    @Test
    fun `isCacheValid returns true when cache is fresh`() {
        val models = listOf(createModelData("gpt-4"))
        setCachedModelsViaReflection(models)
        setLastFetchTimeViaReflection(System.currentTimeMillis())
        assertTrue(OpenAIHelper.isCacheValid())
    }

    @Test
    fun `isCacheValid returns true when cache is 59 minutes old`() {
        val models = listOf(createModelData("gpt-4"))
        setCachedModelsViaReflection(models)
        setLastFetchTimeViaReflection(System.currentTimeMillis() - 59 * 60 * 1000)
        assertTrue(OpenAIHelper.isCacheValid())
    }

    // --- invalidateCache ---

    @Test
    fun `invalidateCache clears models`() {
        val models = listOf(createModelData("gpt-4"))
        setCachedModelsViaReflection(models)
        setLastFetchTimeViaReflection(System.currentTimeMillis())

        assertTrue(OpenAIHelper.isCacheValid())
        OpenAIHelper.invalidateCache()
        assertNull(OpenAIHelper.getCachedModels())
        assertFalse(OpenAIHelper.isCacheValid())
    }

    @Test
    fun `invalidateCache resets fetch timestamp to 0`() {
        setLastFetchTimeViaReflection(System.currentTimeMillis())
        OpenAIHelper.invalidateCache()
        val field = OpenAIHelper::class.java.getDeclaredField("lastModelsFetchTimeMillis")
        field.isAccessible = true
        assertEquals(0L, field.getLong(null))
    }

    @Test
    fun `invalidateCache is safe to call when already empty`() {
        OpenAIHelper.invalidateCache()
        OpenAIHelper.invalidateCache() // Should not throw
        assertNull(OpenAIHelper.getCachedModels())
    }

    // --- Helpers ---

    private fun setCachedModelsViaReflection(models: List<ModelData>?) {
        val field: Field = OpenAIHelper::class.java.getDeclaredField("cachedModels")
        field.isAccessible = true
        field.set(null, models)
    }

    private fun setLastFetchTimeViaReflection(timeMillis: Long) {
        val field: Field = OpenAIHelper::class.java.getDeclaredField("lastModelsFetchTimeMillis")
        field.isAccessible = true
        field.setLong(null, timeMillis)
    }

    @Test
    fun `fetchModels never logs customApiUrl or credentials`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val encryptedStore = context.getSharedPreferences("test_openai_helper_prefs", Context.MODE_PRIVATE)
        encryptedStore.edit().clear().commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        PreferencesManager.setEncryptedPrefsProviderForTesting { encryptedStore }

        val prefs = PreferencesManager.getPreferencesInstance(context)
        prefs.saveOpenAIApiKey("sk-helper-secret-999")
        prefs.saveOpenApiSource(Constants.CUSTOM_AI_PROVIDER_NAME)
        val sensitiveCustomUrl = "https://user:secretpass@custom-llm.internal.invalid/v1?api_token=tok999"
        prefs.saveCustomOpenAIApiUrl(sensitiveCustomUrl)

        ShadowLog.clear()
        try {
            OpenAIHelper.fetchModels(context, null)
        } catch (_: IllegalArgumentException) {
            // URL with query may be rejected by Retrofit; check logs regardless
        }

        val logs = ShadowLog.getLogsForTag("OpenAIHelper").joinToString("\n") { it.msg ?: "" }
        assertFalse("Log must not contain custom URL", logs.contains("custom-llm.internal.invalid"))
        assertFalse("Log must not contain credentials", logs.contains("secretpass"))
        assertFalse("Log must not contain query token", logs.contains("tok999"))
        assertFalse("Log must not contain API key", logs.contains("sk-helper-secret-999"))

        encryptedStore.edit().clear().commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }
}
