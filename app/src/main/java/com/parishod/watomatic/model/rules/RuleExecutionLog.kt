package com.parishod.watomatic.model.rules

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "rule_execution_logs",
    foreignKeys = [
        ForeignKey(
            entity = AutoReplyRule::class,
            parentColumns = ["id"],
            childColumns = ["rule_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["rule_id"]),
        Index(value = ["rule_id", "package_name", "sender_name", "replied_at"]),
        Index(value = ["notification_key", "message_timestamp"])
    ]
)
data class RuleExecutionLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "rule_id")
    val ruleId: Long,

    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "sender_name")
    val senderName: String,

    @ColumnInfo(name = "notification_key")
    val notificationKey: String,

    @ColumnInfo(name = "message_timestamp")
    val messageTimestamp: Long,

    @ColumnInfo(name = "replied_at")
    val repliedAt: Long = System.currentTimeMillis()
)
