package com.parishod.watomatic.model.ai

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_notes",
    indices = [
        Index(value = ["is_completed", "created_at"])
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
    val isCompleted: Boolean = false
)
