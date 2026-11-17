package com.parishod.watomatic.model.ai

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiMemoryDao {

    // ---- Reactive Flows for UI ----

    @Query("SELECT * FROM ai_notes ORDER BY is_completed ASC, created_at DESC, id DESC")
    fun observeAllNotes(): Flow<List<AiNote>>

    @Query("SELECT COUNT(*) FROM ai_notes WHERE is_completed = 0")
    fun observePendingNotesCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM ai_conversation_messages")
    fun observeConversationMessageCount(): Flow<Int>

    // ---- Suspend Operations for ViewModels ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: AiNote): Long

    @Query("UPDATE ai_notes SET is_completed = :completed WHERE id = :noteId")
    suspend fun setNoteCompleted(noteId: Long, completed: Boolean)

    @Query("DELETE FROM ai_notes WHERE id = :noteId")
    suspend fun deleteNoteById(noteId: Long)

    @Query("DELETE FROM ai_notes WHERE is_completed = 1")
    suspend fun clearCompletedNotes()

    @Query("DELETE FROM ai_notes")
    suspend fun clearAllNotes()

    @Query("DELETE FROM ai_conversation_messages")
    suspend fun clearAllConversationMessages()

    // ---- Background Service Synchronous Queries ----

    @Query(
        "SELECT * FROM ai_conversation_messages " +
            "WHERE package_name = :packageName AND sender_key = :senderKey " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit"
    )
    fun getRecentMessagesDescSync(
        packageName: String,
        senderKey: String,
        limit: Int
    ): List<AiConversationMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertConversationMessageSync(message: AiConversationMessage): Long

    @Query(
        "DELETE FROM ai_conversation_messages " +
            "WHERE package_name = :packageName AND sender_key = :senderKey " +
            "AND id NOT IN (" +
            "  SELECT id FROM ai_conversation_messages " +
            "  WHERE package_name = :packageName AND sender_key = :senderKey " +
            "  ORDER BY timestamp DESC, id DESC LIMIT :keepCount" +
            ")"
    )
    fun trimConversationMessagesSync(
        packageName: String,
        senderKey: String,
        keepCount: Int
    )

    @Query("SELECT COUNT(*) FROM ai_conversation_messages")
    fun getConversationMessageCountSync(): Int

    @Query("SELECT COUNT(*) FROM ai_notes WHERE is_completed = 0")
    fun getPendingNoteCountSync(): Int

    @Query("SELECT COUNT(*) FROM ai_notes")
    fun getTotalNoteCountSync(): Int

    @Query(
        "SELECT * FROM ai_notes " +
            "WHERE is_completed = 0 AND package_name = :packageName " +
            "AND LOWER(TRIM(sender_name)) = LOWER(TRIM(:senderName)) " +
            "ORDER BY created_at DESC LIMIT :limit"
    )
    fun getPendingNotesForSenderSync(
        packageName: String,
        senderName: String,
        limit: Int
    ): List<AiNote>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertNoteSync(note: AiNote): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertReplyLogSync(log: AiReplyLog): Long

    @Query(
        "SELECT MAX(replied_at) FROM ai_reply_logs " +
            "WHERE package_name = :packageName AND sender_key = :senderKey"
    )
    fun getLastAiReplyTimeSync(
        packageName: String,
        senderKey: String
    ): Long?

    @Query(
        "SELECT COUNT(*) FROM ai_reply_logs " +
            "WHERE package_name = :packageName AND sender_key = :senderKey AND replied_at >= :sinceTime"
    )
    fun getAiReplyCountSinceSync(
        packageName: String,
        senderKey: String,
        sinceTime: Long
    ): Int
}
