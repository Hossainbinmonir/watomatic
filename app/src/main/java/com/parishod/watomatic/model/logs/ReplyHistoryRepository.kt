package com.parishod.watomatic.model.logs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

class ReplyHistoryRepository @JvmOverloads constructor(
    private val logsDao: MessageLogsDao,
    private val attemptDao: ReplyAttemptDao? = null,
    private val db: MessageLogsDB? = null
) {

    val groupedReplyHistory: Flow<List<ReplyHistorySummaryItem>> =
        logsDao.observeGroupedReplyHistory()

    val allReplyHistory: Flow<List<ReplyHistoryDetailItem>> =
        logsDao.observeAllReplyHistory()

    val totalReplyCount: Flow<Int> =
        logsDao.observeTotalReplyCount()

    val totalAttemptCount: Flow<Int> =
        attemptDao?.observeTotalAttemptCount() ?: flowOf(0)

    suspend fun deleteReplyById(logId: Int) = withContext(Dispatchers.IO) {
        logsDao.deleteReplyByIdSync(logId)
    }

    suspend fun deleteTimelineItem(item: ReplyHistoryDetailItem) = withContext(Dispatchers.IO) {
        val attemptId = item.attemptId
        if (!attemptId.isNullOrBlank()) {
            val action = Runnable {
                attemptDao?.deleteByAttemptIdSync(attemptId)
                logsDao.deleteByAttemptIdSync(attemptId)
            }
            if (db != null) {
                db.runInTransaction(action)
            } else {
                action.run()
            }
        } else if (item.id > 0) {
            logsDao.deleteReplyByIdSync(item.id)
        }
    }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        val action = Runnable {
            logsDao.clearAllLogsSync()
            attemptDao?.clearAllAttemptsSync()
        }
        if (db != null) {
            db.runInTransaction(action)
        } else {
            action.run()
        }
    }
}
