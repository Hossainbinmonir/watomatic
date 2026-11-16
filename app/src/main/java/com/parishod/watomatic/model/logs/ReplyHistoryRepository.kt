package com.parishod.watomatic.model.logs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ReplyHistoryRepository(private val logsDao: MessageLogsDao) {

    val groupedReplyHistory: Flow<List<ReplyHistorySummaryItem>> =
        logsDao.observeGroupedReplyHistory()

    val allReplyHistory: Flow<List<ReplyHistoryDetailItem>> =
        logsDao.observeAllReplyHistory()

    val totalReplyCount: Flow<Int> =
        logsDao.observeTotalReplyCount()

    suspend fun deleteReplyById(logId: Int) = withContext(Dispatchers.IO) {
        logsDao.deleteReplyByIdSync(logId)
    }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        logsDao.clearAllLogsSync()
    }
}
