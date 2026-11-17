package com.parishod.watomatic.activity.notes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.parishod.watomatic.model.ai.AiMemoryDatabase
import com.parishod.watomatic.model.ai.AiMemoryRepository
import com.parishod.watomatic.model.ai.AiNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class NotesFilterMode {
    ALL, PENDING, COMPLETED
}

data class AiNotesUiState(
    val pendingCount: Int = 0,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val filterMode: NotesFilterMode = NotesFilterMode.ALL,
    val searchQuery: String = "",
    val displayedNotes: List<AiNote> = emptyList()
)

class AiNotesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AiMemoryRepository(
        AiMemoryDatabase.getInstance(application).aiMemoryDao()
    )

    private val searchQuery = MutableStateFlow("")
    private val filterMode = MutableStateFlow(NotesFilterMode.ALL)

    val uiState: StateFlow<AiNotesUiState> = combine(
        repository.allNotes,
        searchQuery,
        filterMode
    ) { allNotes, query, mode ->
        val pendingCount = allNotes.count { !it.isCompleted }
        val completedCount = allNotes.count { it.isCompleted }

        val q = query.trim().lowercase()
        val filtered = allNotes.filter { note ->
            val matchesFilter = when (mode) {
                NotesFilterMode.ALL -> true
                NotesFilterMode.PENDING -> !note.isCompleted
                NotesFilterMode.COMPLETED -> note.isCompleted
            }
            if (!matchesFilter) return@filter false
            if (q.isEmpty()) return@filter true

            note.noteText.lowercase().contains(q) ||
                note.senderName.lowercase().contains(q) ||
                note.sourceMessage.lowercase().contains(q)
        }

        AiNotesUiState(
            pendingCount = pendingCount,
            completedCount = completedCount,
            totalCount = allNotes.size,
            filterMode = mode,
            searchQuery = query,
            displayedNotes = filtered
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AiNotesUiState()
    )

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setFilterMode(mode: NotesFilterMode) {
        filterMode.value = mode
    }

    fun setNoteCompleted(noteId: Long, completed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setNoteCompleted(noteId, completed)
        }
    }

    fun deleteNote(noteId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteNoteById(noteId)
        }
    }

    fun clearCompletedNotes() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearCompletedNotes()
        }
    }

    fun addManualNote(noteText: String, senderLabel: String) {
        val cleanText = noteText.trim()
        if (cleanText.isEmpty()) return
        val cleanSender = senderLabel.trim()
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertNote(
                AiNote(
                    senderName = cleanSender,
                    packageName = "",
                    noteText = cleanText,
                    sourceMessage = "",
                    createdAt = System.currentTimeMillis(),
                    isCompleted = false
                )
            )
        }
    }
}
