package com.parishod.watomatic.model.ai

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_conversation_messages",
    indices = [
        Index(value = ["package_name", "sender_key", "timestamp"]),
        Index(value = ["attempt_id", "role"], unique = true),
        Index(value = ["incoming_event_key"], unique = true)
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
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "attempt_id")
    val attemptId: String? = null,

    @ColumnInfo(name = "incoming_event_key")
    val incomingEventKey: String? = null,

    @ColumnInfo(name = "execution_status", defaultValue = "LEGACY")
    val executionStatus: String = STATUS_LEGACY
) {
    @androidx.room.Ignore
    constructor(
        id: Long,
        packageName: String,
        senderKey: String,
        senderDisplayName: String,
        role: String,
        content: String,
        timestamp: Long
    ) : this(
        id = id,
        packageName = packageName,
        senderKey = senderKey,
        senderDisplayName = senderDisplayName,
        role = role,
        content = content,
        timestamp = timestamp,
        attemptId = null,
        incomingEventKey = null,
        executionStatus = STATUS_LEGACY
    )

    companion object {
        const val STATUS_LEGACY = "LEGACY"
        const val STATUS_DISPATCHED = "DISPATCHED"
        const val STATUS_USER_RECEIVED = "USER_RECEIVED"
    }
}
