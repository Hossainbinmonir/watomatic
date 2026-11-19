package com.parishod.watomatic.model.logs;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

import kotlinx.coroutines.flow.Flow;

@Dao
public interface MessageLogsDao {
    @Query("SELECT message_logs.notif_reply_time FROM MESSAGE_LOGS " +
            "INNER JOIN app_packages ON app_packages.`index` = message_logs.`index` " +
            "WHERE app_packages.package_name=:packageName AND message_logs.notif_title=:title " +
            "AND message_logs.execution_status IN ('DISPATCHED', 'LEGACY') " +
            "ORDER BY notif_reply_time DESC LIMIT 1"
    )
    long getLastReplyTimeStamp(String title, String packageName);

    @Insert
    void logReply(MessageLog log);

    @Query("SELECT COUNT(id) FROM MESSAGE_LOGS WHERE execution_status IN ('DISPATCHED', 'LEGACY')")
    long getNumReplies();

    //https://stackoverflow.com/questions/11771580/deleting-android-sqlite-rows-older-than-x-days
    @Query("DELETE FROM message_logs WHERE notif_reply_time <= strftime('%s', datetime('now', '-30 days'));")
    void purgeMessageLogs();

    @Query("SELECT notif_reply_time FROM MESSAGE_LOGS WHERE execution_status IN ('DISPATCHED', 'LEGACY') ORDER BY notif_reply_time DESC LIMIT 1")
    long getFirstRepliedTime();

    @Query("SELECT " +
            "COALESCE(NULLIF(TRIM(m.notif_incoming_msg), ''), '') AS incomingMessage, " +
            "COALESCE(m.notif_replied_msg, '') AS repliedMessage, " +
            "p.package_name AS packageName, " +
            "MAX(m.matched_rule_name) AS matchedRuleName, " +
            "MAX(m.notif_title) AS lastSender, " +
            "COUNT(m.id) AS replyCount, " +
            "MAX(m.notif_reply_time) AS lastReplyTime, " +
            "CASE " +
            "WHEN SUM(CASE WHEN m.execution_status = 'DISPATCHED' THEN 1 ELSE 0 END) = COUNT(m.id) THEN 'DISPATCHED' " +
            "WHEN SUM(CASE WHEN m.execution_status = 'LEGACY' THEN 1 ELSE 0 END) = COUNT(m.id) THEN 'LEGACY' " +
            "ELSE 'MIXED' " +
            "END AS executionStatus " +
            "FROM message_logs m " +
            "INNER JOIN app_packages p ON p.`index` = m.`index` " +
            "WHERE m.execution_status IN ('DISPATCHED', 'LEGACY') " +
            "GROUP BY " +
            "COALESCE(NULLIF(TRIM(m.notif_incoming_msg), ''), ''), " +
            "COALESCE(m.notif_replied_msg, ''), " +
            "p.package_name " +
            "ORDER BY lastReplyTime DESC")
    Flow<List<ReplyHistorySummaryItem>> observeGroupedReplyHistory();

    @Query("SELECT " +
            "0 AS id, " +
            "r.attempt_id AS attemptId, " +
            "r.package_name AS packageName, " +
            "r.sender_name AS senderName, " +
            "r.incoming_message AS incomingMessage, " +
            "COALESCE(r.reply_text, '') AS repliedMessage, " +
            "r.source_label AS matchedRuleName, " +
            "r.updated_at_ms AS replyTime, " +
            "r.created_at_ms AS createdAtMs, " +
            "r.status AS executionStatus, " +
            "r.source_type AS sourceType, " +
            "r.failure_category AS failureCategory " +
            "FROM reply_attempts r " +
            "UNION ALL " +
            "SELECT " +
            "m.id AS id, " +
            "m.attempt_id AS attemptId, " +
            "p.package_name AS packageName, " +
            "m.notif_title AS senderName, " +
            "m.notif_incoming_msg AS incomingMessage, " +
            "COALESCE(m.notif_replied_msg, '') AS repliedMessage, " +
            "m.matched_rule_name AS matchedRuleName, " +
            "m.notif_reply_time AS replyTime, " +
            "m.notif_reply_time AS createdAtMs, " +
            "m.execution_status AS executionStatus, " +
            "NULL AS sourceType, " +
            "NULL AS failureCategory " +
            "FROM message_logs m " +
            "INNER JOIN app_packages p ON p.`index` = m.`index` " +
            "WHERE m.attempt_id IS NULL " +
            "ORDER BY replyTime DESC")
    Flow<List<ReplyHistoryDetailItem>> observeAllReplyHistory();

    @Query("SELECT COUNT(id) FROM message_logs WHERE execution_status IN ('DISPATCHED', 'LEGACY')")
    Flow<Integer> observeTotalReplyCount();

    @Query("SELECT COUNT(*) FROM (" +
            "SELECT 1 FROM message_logs m " +
            "INNER JOIN app_packages p ON p.`index` = m.`index` " +
            "WHERE m.execution_status IN ('DISPATCHED', 'LEGACY') " +
            "GROUP BY " +
            "COALESCE(NULLIF(TRIM(m.notif_incoming_msg), ''), ''), " +
            "COALESCE(m.notif_replied_msg, ''), " +
            "p.package_name)")
    int getUniqueReplyPairCountSync();

    @Query("SELECT COUNT(id) FROM message_logs WHERE attempt_id = :attemptId")
    int countByAttemptIdSync(String attemptId);

    @Query("DELETE FROM message_logs WHERE attempt_id = :attemptId")
    void deleteByAttemptIdSync(String attemptId);

    @Query("DELETE FROM message_logs WHERE id = :logId")
    void deleteReplyByIdSync(int logId);

    @Query("DELETE FROM message_logs")
    void clearAllLogsSync();
}
