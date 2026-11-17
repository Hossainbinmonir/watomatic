package com.parishod.watomatic.model.ai

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_conversation_messages",
    indices = [
        Index(value = ["package_name", "sender_key", "timestamp"])
    ]
)
data class AiConversationMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "sender_key")
    val senderKey: String,

    @ColumnInfo(name = "sender_display_name")
    val senderDisplayName: String,

    /**
     * Normalized role: "user" (incoming message from contact) or "assistant" (AI reply).
     */
    @ColumnInfo(name = "role")
    val role: String,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis()
)
