package com.parishod.watomatic.activity.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.parishod.watomatic.model.logs.MessageLogsDB
import com.parishod.watomatic.model.logs.ReplyHistoryDetailItem
import com.parishod.watomatic.model.logs.ReplyHistoryRepository
import com.parishod.watomatic.model.logs.ReplyHistoryStatus
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

enum class HistoryStatusFilter {
    ALL,
    DISPATCHED,
    FAILED,
    CANCELLED,
    UNKNOWN,
    LEGACY
}

sealed class ReplyHistoryRow {
    data class Grouped(val item: ReplyHistorySummaryItem) : ReplyHistoryRow()
    data class Timeline(val item: ReplyHistoryDetailItem) : ReplyHistoryRow()
}

data class ReplyHistoryUiState(
    val totalReplies: Int = 0,
    val totalAttempts: Int = 0,
    val uniqueMessages: Int = 0,
    val mode: HistoryViewMode = HistoryViewMode.GROUPED,
    val statusFilter: HistoryStatusFilter = HistoryStatusFilter.ALL,
    val searchQuery: String = "",
    val displayedRows: List<ReplyHistoryRow> = emptyList()
)

class ReplyHistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ReplyHistoryRepository by lazy {
        val db = MessageLogsDB.getInstance(application)
        ReplyHistoryRepository(db.logsDao(), db.replyAttemptDao())
    }

    private val searchQuery = MutableStateFlow("")
    private val viewMode = MutableStateFlow(HistoryViewMode.GROUPED)
    private val statusFilter = MutableStateFlow(HistoryStatusFilter.ALL)

    private data class FilterInputs(
        val query: String,
        val mode: HistoryViewMode,
        val status: HistoryStatusFilter
    )

    private val filterInputs = combine(searchQuery, viewMode, statusFilter) { query, mode, status ->
        FilterInputs(query, mode, status)
    }

    val uiState: StateFlow<ReplyHistoryUiState> = combine(
        repository.groupedReplyHistory,
        repository.allReplyHistory,
        repository.totalReplyCount,
        repository.totalAttemptCount,
        filterInputs
    ) { grouped, timeline, totalReplies, totalAttempts, filters ->
        val trimmedQuery = filters.query.trim().lowercase()

        val rows: List<ReplyHistoryRow> = when (filters.mode) {
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
                        matchesStatusFilter(item.executionStatus, filters.status) &&
                            (trimmedQuery.isEmpty() ||
                                (item.incomingMessage?.lowercase()?.contains(trimmedQuery) == true) ||
                                item.repliedMessage.lowercase().contains(trimmedQuery) ||
                                (item.senderName?.lowercase()?.contains(trimmedQuery) == true) ||
                                (item.matchedRuleName?.lowercase()?.contains(trimmedQuery) == true))
                    }
                    .map { ReplyHistoryRow.Timeline(it) }
            }
        }

        ReplyHistoryUiState(
            totalReplies = totalReplies,
            totalAttempts = totalAttempts,
            uniqueMessages = grouped.size,
            mode = filters.mode,
            statusFilter = filters.status,
            searchQuery = filters.query,
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

    fun setStatusFilter(filter: HistoryStatusFilter) {
        statusFilter.value = filter
    }

    fun deleteEntry(logId: Int) {
        viewModelScope.launch {
            repository.deleteReplyById(logId)
        }
    }

    fun deleteTimelineEntry(item: ReplyHistoryDetailItem) {
        viewModelScope.launch {
            repository.deleteTimelineItem(item)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllHistory()
        }
    }

    private fun matchesStatusFilter(status: String, filter: HistoryStatusFilter): Boolean =
        when (filter) {
            HistoryStatusFilter.ALL -> true
            HistoryStatusFilter.DISPATCHED -> status == ReplyHistoryStatus.DISPATCHED
            HistoryStatusFilter.FAILED -> status == ReplyHistoryStatus.FAILED
            HistoryStatusFilter.CANCELLED -> status == ReplyHistoryStatus.CANCELLED
            HistoryStatusFilter.UNKNOWN -> status == ReplyHistoryStatus.UNKNOWN
            HistoryStatusFilter.LEGACY -> status == ReplyHistoryStatus.LEGACY
        }
}
