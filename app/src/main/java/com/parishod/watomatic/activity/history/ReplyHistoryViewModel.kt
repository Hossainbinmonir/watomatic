package com.parishod.watomatic.activity.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.logs.ReplyHistoryDetailItem
import com.parishod.watomatic.model.logs.ReplyHistoryRepository
import com.parishod.watomatic.model.logs.ReplyHistorySummaryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class HistoryViewMode {
    GROUPED,
    TIMELINE
}

sealed class ReplyHistoryRow {
    data class Grouped(val item: ReplyHistorySummaryItem) : ReplyHistoryRow()
    data class Timeline(val item: ReplyHistoryDetailItem) : ReplyHistoryRow()
}

data class ReplyHistoryUiState(
    val totalReplies: Int = 0,
    val uniqueMessages: Int = 0,
    val mode: HistoryViewMode = HistoryViewMode.GROUPED,
    val searchQuery: String = "",
    val displayedRows: List<ReplyHistoryRow> = emptyList()
)

class ReplyHistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ReplyHistoryRepository by lazy {
        val db = MessageLogsDB.getInstance(application)
        ReplyHistoryRepository(db.logsDao())
    }

    private val searchQuery = MutableStateFlow("")
    private val viewMode = MutableStateFlow(HistoryViewMode.GROUPED)

    val uiState: StateFlow<ReplyHistoryUiState> = combine(
        repository.groupedReplyHistory,
        repository.allReplyHistory,
        repository.totalReplyCount,
        searchQuery,
        viewMode
    ) { grouped, timeline, totalReplies, query, mode ->
        val trimmedQuery = query.trim().lowercase()

        val rows: List<ReplyHistoryRow> = when (mode) {
            HistoryViewMode.GROUPED -> {
                grouped
                    .filter { item ->
                        trimmedQuery.isEmpty() ||
                            item.incomingMessage.lowercase().contains(trimmedQuery) ||
                            item.repliedMessage.lowercase().contains(trimmedQuery) ||
                            (item.lastSender?.lowercase()?.contains(trimmedQuery) == true) ||
                            (item.matchedRuleName?.lowercase()?.contains(trimmedQuery) == true)
                    }
                    .map { ReplyHistoryRow.Grouped(it) }
            }
            HistoryViewMode.TIMELINE -> {
                timeline
                    .filter { item ->
                        trimmedQuery.isEmpty() ||
                            (item.incomingMessage?.lowercase()?.contains(trimmedQuery) == true) ||
                            item.repliedMessage.lowercase().contains(trimmedQuery) ||
                            (item.senderName?.lowercase()?.contains(trimmedQuery) == true) ||
                            (item.matchedRuleName?.lowercase()?.contains(trimmedQuery) == true)
                    }
                    .map { ReplyHistoryRow.Timeline(it) }
            }
        }

        ReplyHistoryUiState(
            totalReplies = totalReplies,
            uniqueMessages = grouped.size,
            mode = mode,
            searchQuery = query,
            displayedRows = rows
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ReplyHistoryUiState()
    )

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setViewMode(mode: HistoryViewMode) {
        viewMode.value = mode
    }

    fun deleteEntry(logId: Int) {
        viewModelScope.launch {
            repository.deleteReplyById(logId)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllHistory()
        }
    }
}
