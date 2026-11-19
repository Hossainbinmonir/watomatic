package com.parishod.watomatic.model.ai

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_reply_logs",
    indices = [
        Index(value = ["package_name", "sender_key", "replied_at"]),
        Index(value = ["attempt_id"], unique = true)
    ]
)
data class AiReplyLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "sender_key")
    val senderKey: String,

    @ColumnInfo(name = "replied_at")
    val repliedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "attempt_id")
    val attemptId: String? = null
) {
    @androidx.room.Ignore
    constructor(
        id: Long,
        packageName: String,
        senderKey: String,
        repliedAt: Long
    ) : this(
        id = id,
        packageName = packageName,
        senderKey = senderKey,
        repliedAt = repliedAt,
        attemptId = null
    )
}
