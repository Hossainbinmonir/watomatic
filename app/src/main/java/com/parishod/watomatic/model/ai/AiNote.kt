package com.parishod.watomatic.model.ai

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_notes",
    indices = [
        Index(value = ["is_completed", "created_at"]),
        Index(value = ["attempt_id", "note_dedup_key"], unique = true),
        Index(value = ["package_name", "sender_key", "is_completed"])
    ]
)
data class AiNote(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "sender_name")
    val senderName: String,

    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "note_text")
    val noteText: String,

    @ColumnInfo(name = "source_message")
    val sourceMessage: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "is_completed")
    val isCompleted: Boolean = false,

    @ColumnInfo(name = "attempt_id")
    val attemptId: String? = null,

    @ColumnInfo(name = "note_dedup_key")
    val noteDedupKey: String? = null,

    @ColumnInfo(name = "sender_key", defaultValue = "")
    val senderKey: String = ""
) {
    @androidx.room.Ignore
    constructor(
        id: Long,
        senderName: String,
        packageName: String,
        noteText: String,
        sourceMessage: String,
        createdAt: Long,
        isCompleted: Boolean
    ) : this(
        id = id,
        senderName = senderName,
        packageName = packageName,
        noteText = noteText,
        sourceMessage = sourceMessage,
        createdAt = createdAt,
        isCompleted = isCompleted,
        attemptId = null,
        noteDedupKey = null,
        senderKey = ""
    )
}
