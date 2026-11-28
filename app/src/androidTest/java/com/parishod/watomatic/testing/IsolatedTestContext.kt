package com.parishod.watomatic.testing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import com.parishod.watomatic.model.CustomRepliesData
import com.parishod.watomatic.model.ai.AiMemoryDatabase
import com.parishod.watomatic.model.ai.PendingPostDispatchMemoryStore
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.logs.NotificationDispatchClaimDao
import com.parishod.watomatic.model.preferences.PreferencesManager
import com.parishod.watomatic.model.rules.AutoReplyRulesDatabase
import com.parishod.watomatic.model.utils.AppUtils
import com.parishod.watomatic.model.utils.Constants
import com.parishod.watomatic.model.utils.NotificationHelper
import com.parishod.watomatic.model.utils.OpenAIHelper
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Isolated [ContextWrapper] that namespaces all SharedPreferences and SQLite/Room databases
 * under a unique per-test prefix so instrumentation tests never read from or mutate real
 * user preferences or production databases.
 *
 * Also tracks every storage file opened during the test lifecycle and provides deterministic
 * cleanup and singleton-reset helpers so cached Room/Preference singletons never leak across
 * tests or bind back to the host application context.
 */
class IsolatedTestContext(
    base: Context,
    val prefix: String = generatePrefix()
) : ContextWrapper(base) {

    private val trackedSharedPrefNames = ConcurrentHashMap.newKeySet<String>()
    private val trackedDatabaseNames = ConcurrentHashMap.newKeySet<String>()

    init {
        require(prefix.isNotBlank()) { "IsolatedTestContext prefix must not be blank" }
    }

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        trackedSharedPrefNames.add(stripPrefix(name))
        return super.getSharedPreferences(prefixName(name), mode)
    }

    override fun deleteSharedPreferences(name: String): Boolean {
        trackedSharedPrefNames.remove(stripPrefix(name))
        return super.deleteSharedPreferences(prefixName(name))
    }

    override fun getDatabasePath(name: String): File {
        if (hasPathSeparator(name)) {
            return super.getDatabasePath(name)
        }
        trackedDatabaseNames.add(stripPrefix(name))
        return super.getDatabasePath(prefixName(name))
    }

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?
    ): SQLiteDatabase {
        val resolved = if (hasPathSeparator(name)) name else {
            trackedDatabaseNames.add(stripPrefix(name))
            prefixName(name)
        }
        return super.openOrCreateDatabase(resolved, mode, factory)
    }

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?
    ): SQLiteDatabase {
        val resolved = if (hasPathSeparator(name)) name else {
            trackedDatabaseNames.add(stripPrefix(name))
            prefixName(name)
        }
        return super.openOrCreateDatabase(resolved, mode, factory, errorHandler)
    }

    override fun deleteDatabase(name: String): Boolean {
        val resolved = if (hasPathSeparator(name)) name else {
            trackedDatabaseNames.remove(stripPrefix(name))
            prefixName(name)
        }
        return super.deleteDatabase(resolved)
    }

    fun resolvePrefixedSharedPrefsName(name: String): String = prefixName(name)

    fun resolvePrefixedDatabasePath(name: String): File = getDatabasePath(name)

    fun getTrackedSharedPrefNames(): Set<String> = trackedSharedPrefNames.toSet()

    fun getTrackedDatabaseNames(): Set<String> = trackedDatabaseNames.toSet()

    /**
     * Closes and clears all cached app singletons and deletes ONLY storage files owned by this
     * [IsolatedTestContext] instance. Never modifies unprefixed production files on [baseContext].
     */
    fun cleanupOwnedStorage() {
        resetAllAppSingletons(this)

        val dbNamesToDelete = LinkedHashSet<String>().apply {
            addAll(KNOWN_DATABASE_NAMES)
            addAll(trackedDatabaseNames)
        }
        for (dbName in dbNamesToDelete) {
            runCatching { deleteDatabase(dbName) }
        }

        val prefNamesToDelete = LinkedHashSet<String>().apply {
            add(defaultSharedPreferencesName(this@IsolatedTestContext))
            addAll(KNOWN_SHARED_PREF_NAMES)
            addAll(trackedSharedPrefNames)
        }
        for (prefName in prefNamesToDelete) {
            runCatching {
                super.getSharedPreferences(prefixName(prefName), MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
            }
            runCatching { deleteSharedPreferences(prefName) }
        }

        resetAllAppSingletons(this)
    }

    private fun prefixName(name: String): String {
        val marker = "${prefix}_"
        return if (name.startsWith(marker)) name else "$marker$name"
    }

    private fun stripPrefix(name: String): String {
        val marker = "${prefix}_"
        return if (name.startsWith(marker)) name.removePrefix(marker) else name
    }

    private fun hasPathSeparator(name: String): Boolean {
        return name.contains('/') || name.contains('\\')
    }

    companion object {
        private val KNOWN_DATABASE_NAMES = listOf(
            Constants.LOGS_DB_NAME,
            AiMemoryDatabase.DB_NAME,
            AutoReplyRulesDatabase.DB_NAME
        )

        private val KNOWN_SHARED_PREF_NAMES = listOf(
            PreferencesManager.SECURE_PREFS_NAME,
            PreferencesManager.LEGACY_FALLBACK_PREFS_NAME,
            CustomRepliesData::class.java.simpleName,
            "watomatic_pending_ai_memory",
            "watomatic_pre_dispatch_retry_state"
        )

        fun defaultSharedPreferencesName(context: Context): String {
            return "${context.packageName}_preferences"
        }

        fun generatePrefix(tag: String = "watotest"): String {
            val shortId = UUID.randomUUID().toString().replace("-", "").take(12)
            return "${tag}_$shortId"
        }

        /**
         * Resets every process-wide singleton and static cache used by Watomatic's storage,
         * preference, and AI pipelines so no test observes stale state or touches another context's DB.
         */
        @JvmStatic
        fun resetAllAppSingletons(contextForPendingStore: Context? = null) {
            runCatching { PendingPostDispatchMemoryStore.clearForTesting(contextForPendingStore) }
            runCatching { NotificationDispatchClaimDao.clearAllPriorFailedSnapshotsForTesting() }
            runCatching { AiMemoryDatabase.resetInstance() }
            runCatching { MessageLogsDB.resetInstance() }
            runCatching { AutoReplyRulesDatabase.resetInstance() }
            runCatching { PreferencesManager.resetInstance() }
            runCatching { CustomRepliesData.resetInstance() }
            runCatching { AppUtils.resetInstance() }
            runCatching { NotificationHelper.resetInstance() }
            runCatching { OpenAIHelper.invalidateCache() }
        }
    }
}
