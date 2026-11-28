package com.parishod.watomatic.service.reply

import android.content.Context
import android.content.SharedPreferences
import com.parishod.watomatic.model.logs.NotificationDispatchClaimEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent bounded retry counter store keyed by notification `eventKey`.
 * Ensures pre-dispatch retry limits survive service restarts without requiring
 * Room schema changes.
 */
object PreDispatchRetryStateStore {
    private const val PREFS_NAME = "watomatic_pre_dispatch_retry_state"
    private const val MAX_ENTRIES = 512

    interface StoreBackend {
        fun getRetryCount(eventKey: String): Int
        fun incrementRetryCount(eventKey: String, nowMs: Long): Int
        fun clear(eventKey: String)
        fun pruneExpired(nowMs: Long, maxAgeMs: Long): Int
    }

    class InMemoryBackend : StoreBackend {
        private data class Entry(val count: Int, val updatedAtMs: Long)
        private val map = ConcurrentHashMap<String, Entry>()

        override fun getRetryCount(eventKey: String): Int = map[eventKey]?.count ?: 0

        @Synchronized
        override fun incrementRetryCount(eventKey: String, nowMs: Long): Int {
            val next = (map[eventKey]?.count ?: 0) + 1
            map[eventKey] = Entry(next, nowMs)
            return next
        }

        override fun clear(eventKey: String) {
            map.remove(eventKey)
        }

        @Synchronized
        override fun pruneExpired(nowMs: Long, maxAgeMs: Long): Int {
            val cutoff = nowMs - maxAgeMs
            var removed = 0
            val iter = map.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (entry.value.updatedAtMs <= cutoff) {
                    iter.remove()
                    removed++
                }
            }
            return removed
        }
    }

    class SharedPrefsBackend(context: Context) : StoreBackend {
        private val prefs: SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        private val lock = Any()

        override fun getRetryCount(eventKey: String): Int {
            if (eventKey.isBlank()) return 0
            synchronized(lock) {
                return parseCount(prefs.getString(eventKey, null))
            }
        }

        override fun incrementRetryCount(eventKey: String, nowMs: Long): Int {
            if (eventKey.isBlank()) return 0
            synchronized(lock) {
                val next = parseCount(prefs.getString(eventKey, null)) + 1
                val editor = prefs.edit().putString(eventKey, "$next|$nowMs")
                trimIfOversizedLocked(editor, keepKey = eventKey)
                editor.apply()
                return next
            }
        }

        override fun clear(eventKey: String) {
            if (eventKey.isBlank()) return
            synchronized(lock) {
                if (prefs.contains(eventKey)) {
                    prefs.edit().remove(eventKey).apply()
                }
            }
        }

        override fun pruneExpired(nowMs: Long, maxAgeMs: Long): Int {
            val cutoff = nowMs - maxAgeMs
            synchronized(lock) {
                val all = prefs.all
                if (all.isEmpty()) return 0
                var removed = 0
                val editor = prefs.edit()
                for ((key, rawVal) in all) {
                    val ts = parseTimestamp(rawVal as? String)
                    if (ts <= cutoff) {
                        editor.remove(key)
                        removed++
                    }
                }
                if (removed > 0) {
                    editor.apply()
                }
                return removed
            }
        }

        private fun trimIfOversizedLocked(editor: SharedPreferences.Editor, keepKey: String) {
            val all = prefs.all
            if (all.size < MAX_ENTRIES) return
            val ordered = all.entries
                .filter { it.key != keepKey }
                .sortedBy { parseTimestamp(it.value as? String) }
            val excess = all.size - MAX_ENTRIES + 1
            for (i in 0 until minOf(excess, ordered.size)) {
                editor.remove(ordered[i].key)
            }
        }

        private fun parseCount(raw: String?): Int {
            if (raw.isNullOrBlank()) return 0
            val idx = raw.indexOf('|')
            val countStr = if (idx >= 0) raw.substring(0, idx) else raw
            return countStr.toIntOrNull()?.coerceAtLeast(0) ?: 0
        }

        private fun parseTimestamp(raw: String?): Long {
            if (raw.isNullOrBlank()) return 0L
            val idx = raw.indexOf('|')
            if (idx < 0 || idx + 1 >= raw.length) return 0L
            return raw.substring(idx + 1).toLongOrNull() ?: 0L
        }
    }

    @JvmStatic
    fun getRetryCount(context: Context, eventKey: String): Int =
        SharedPrefsBackend(context).getRetryCount(eventKey)

    @JvmStatic
    fun clear(context: Context, eventKey: String) {
        SharedPrefsBackend(context).clear(eventKey)
    }

    @JvmStatic
    @JvmOverloads
    fun pruneExpired(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = NotificationDispatchClaimEntity.DEFAULT_UNRESOLVED_TTL_MS
    ): Int = SharedPrefsBackend(context).pruneExpired(nowMs, maxAgeMs)
}
