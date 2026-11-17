package com.parishod.watomatic.model.ai

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_reply_logs",
    indices = [
        Index(value = ["package_name", "sender_key", "replied_at"])
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
    val repliedAt: Long = System.currentTimeMillis()
)
