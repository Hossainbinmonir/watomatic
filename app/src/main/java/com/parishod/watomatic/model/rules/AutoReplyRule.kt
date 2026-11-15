package com.parishod.watomatic.model.rules

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "auto_reply_rules")
data class AutoReplyRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,

    @ColumnInfo(name = "selected_packages")
    val selectedPackages: List<String> = emptyList(),

    @ColumnInfo(name = "match_type")
    val matchType: MatchType = MatchType.CONTAINS,

    @ColumnInfo(name = "keywords")
    val keywords: List<String> = emptyList(),

    @ColumnInfo(name = "is_case_sensitive")
    val isCaseSensitive: Boolean = false,

    @ColumnInfo(name = "reply_message")
    val replyMessage: String,

    @ColumnInfo(name = "priority")
    val priority: Int = 10,

    @ColumnInfo(name = "reply_delay_seconds")
    val replyDelaySeconds: Int = 0,

    @ColumnInfo(name = "cooldown_seconds")
    val cooldownSeconds: Int = 10,

    @ColumnInfo(name = "max_replies_per_sender")
    val maxRepliesPerSender: Int = 0,

    @ColumnInfo(name = "frequency_window_minutes")
    val frequencyWindowMinutes: Int = 1440,

    @ColumnInfo(name = "contact_filter_mode")
    val contactFilterMode: ContactFilterMode = ContactFilterMode.ALL,

    @ColumnInfo(name = "filter_contacts")
    val filterContacts: List<String> = emptyList(),

    @ColumnInfo(name = "group_filter_mode")
    val groupFilterMode: GroupFilterMode = GroupFilterMode.BOTH,

    @ColumnInfo(name = "schedule_enabled")
    val scheduleEnabled: Boolean = false,

    @ColumnInfo(name = "start_time_minutes")
    val startTimeMinutes: Int = 9 * 60,

    @ColumnInfo(name = "end_time_minutes")
    val endTimeMinutes: Int = 18 * 60,

    /**
     * ISO-8601 day numbers: 1 = Monday .. 7 = Sunday.
     */
    @ColumnInfo(name = "active_days")
    val activeDays: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
