package com.parishod.watomatic.model.ai

import kotlinx.coroutines.flow.Flow

class AiMemoryRepository(private val dao: AiMemoryDao) {
    val allNotes: Flow<List<AiNote>> = dao.observeAllNotes()
    val pendingNotesCount: Flow<Int> = dao.observePendingNotesCount()
    val conversationMessageCount: Flow<Int> = dao.observeConversationMessageCount()

    suspend fun insertNote(note: AiNote): Long = dao.insertNote(note)

    suspend fun setNoteCompleted(noteId: Long, completed: Boolean) {
        dao.setNoteCompleted(noteId, completed)
    }

    suspend fun deleteNoteById(noteId: Long) {
        dao.deleteNoteById(noteId)
    }

    suspend fun clearCompletedNotes() {
        dao.clearCompletedNotes()
    }

    suspend fun clearAllNotes() {
        dao.clearAllNotes()
    }

    suspend fun clearAllConversationMemory() {
        dao.clearAllConversationMessages()
    }
}
