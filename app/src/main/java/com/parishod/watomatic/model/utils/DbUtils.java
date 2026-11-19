package com.parishod.watomatic.model.utils;

import android.content.Context;
import android.service.notification.StatusBarNotification;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.parishod.watomatic.model.CustomRepliesData;
import com.parishod.watomatic.model.logs.AppPackage;
import com.parishod.watomatic.model.logs.ClaimAcquireOutcome;
import com.parishod.watomatic.model.logs.MessageLog;
import com.parishod.watomatic.model.logs.MessageLogsDB;
import com.parishod.watomatic.model.logs.NotificationDispatchClaimEntity;
import com.parishod.watomatic.model.logs.ReplyAttemptEntity;
import com.parishod.watomatic.model.logs.ReplyHistoryStatus;
import com.parishod.watomatic.service.reply.ReplyAttempt;
import com.parishod.watomatic.service.reply.ReplyExecutionResult;
import com.parishod.watomatic.service.reply.ReplyExecutionStatus;

public class DbUtils {
    private final Context mContext;

    public DbUtils(Context context) {
        mContext = context;
    }

    public long getNumReplies() {
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        return messageLogsDB.logsDao().getNumReplies();
    }

    public int getUniqueReplyPairCount() {
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        return messageLogsDB.logsDao().getUniqueReplyPairCountSync();
    }

    public void purgeMessageLogs() {
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        messageLogsDB.logsDao().purgeMessageLogs();
    }

    public ClaimAcquireOutcome tryAcquireNotificationClaim(
            @NonNull String eventKey,
            @NonNull String claimToken,
            @NonNull String packageName,
            @NonNull String senderKey,
            @Nullable String notificationKey,
            long nowMs,
            boolean allowRetryOnPreDispatchFailure
    ) {
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        NotificationDispatchClaimEntity claim = new NotificationDispatchClaimEntity(
                eventKey,
                claimToken,
                packageName,
                senderKey,
                notificationKey,
                NotificationDispatchClaimEntity.STATUS_CLAIMED,
                null,
                false,
                false,
                nowMs,
                nowMs,
                nowMs + NotificationDispatchClaimEntity.DEFAULT_UNRESOLVED_TTL_MS
        );
        return messageLogsDB.notificationDispatchClaimDao()
                .tryAcquireClaimSync(claim, allowRetryOnPreDispatchFailure);
    }

    public int updateNotificationClaimTransition(
            @NonNull String eventKey,
            @NonNull String expectedAttemptId,
            @NonNull String newAttemptId,
            @NonNull ReplyExecutionStatus status,
            long updatedAtMs
    ) {
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        return messageLogsDB.notificationDispatchClaimDao().updateClaimStateByOwnerSync(
                eventKey,
                expectedAttemptId,
                newAttemptId,
                status.name(),
                null,
                status == ReplyExecutionStatus.DISPATCHING,
                false,
                updatedAtMs,
                updatedAtMs + NotificationDispatchClaimEntity.DEFAULT_UNRESOLVED_TTL_MS
        );
    }

    public int updateNotificationClaimResult(
            @NonNull String eventKey,
            @NonNull String expectedAttemptId,
            @NonNull ReplyExecutionResult result
    ) {
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        long ttlMs = result.isUnknown()
                ? NotificationDispatchClaimEntity.DEFAULT_UNRESOLVED_TTL_MS
                : NotificationDispatchClaimEntity.DEFAULT_RESOLVED_TTL_MS;
        return messageLogsDB.notificationDispatchClaimDao().updateClaimStateByOwnerSync(
                eventKey,
                expectedAttemptId,
                result.getExecutionId(),
                result.getStatus().name(),
                ReplyAttemptEntity.sanitizeFailureCategory(result.getFailureCategory()),
                result.getDispatchAttempted(),
                result.getDispatchedNormally(),
                result.getTimestampMs(),
                result.getTimestampMs() + ttlMs
        );
    }

    public int releaseUnusedNotificationClaim(@NonNull String eventKey, @NonNull String claimToken) {
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        return messageLogsDB.notificationDispatchClaimDao().releaseUnusedClaimSync(eventKey, claimToken);
    }

    public int cancelPendingNotificationClaim(
            @NonNull String eventKey,
            @NonNull String claimToken,
            @Nullable String failureCategory,
            long nowMs
    ) {
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        return messageLogsDB.notificationDispatchClaimDao().cancelPendingClaimSync(
                eventKey,
                claimToken,
                ReplyAttemptEntity.sanitizeFailureCategory(failureCategory),
                nowMs,
                nowMs + NotificationDispatchClaimEntity.DEFAULT_RESOLVED_TTL_MS
        );
    }

    public int purgeExpiredNotificationClaims(long nowMs) {
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        return messageLogsDB.notificationDispatchClaimDao().purgeExpiredAndBoundedClaimsSync(
                nowMs,
                NotificationDispatchClaimEntity.MAX_RESOLVED_CLAIM_ROWS
        );
    }

    @Nullable
    public NotificationDispatchClaimEntity getNotificationClaim(@NonNull String eventKey) {
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        return messageLogsDB.notificationDispatchClaimDao().getClaimByEventKeySync(eventKey);
    }

    public void logReply(StatusBarNotification sbn, String title) {
        CustomRepliesData customRepliesData = CustomRepliesData.getInstance(mContext);
        logReply(sbn, title, customRepliesData.getTextToSendOrElse());
    }

    public void logReply(StatusBarNotification sbn, String title, String repliedMsg) {
        logReply(sbn, title, null, repliedMsg, null);
    }

    public void logReply(
            StatusBarNotification sbn,
            String title,
            @Nullable String incomingMsg,
            String repliedMsg,
            @Nullable String matchedRuleName
    ) {
        logReplyInternal(
                sbn != null ? sbn.getPackageName() : "",
                sbn != null && sbn.getNotification() != null ? sbn.getNotification().when : System.currentTimeMillis(),
                title,
                incomingMsg,
                repliedMsg,
                matchedRuleName,
                ReplyHistoryStatus.LEGACY,
                null,
                System.currentTimeMillis()
        );
    }

    public void recordAttemptTransition(
            @NonNull ReplyAttempt attempt,
            @Nullable String senderName,
            @Nullable String incomingMsg
    ) {
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        ReplyAttemptEntity entity = ReplyAttemptEntity.fromTransition(attempt, senderName, incomingMsg);
        messageLogsDB.replyAttemptDao().upsertAttemptSafely(entity);
    }

    public void recordAttemptResult(
            @NonNull ReplyAttempt attempt,
            @NonNull ReplyExecutionResult result,
            @Nullable String senderName,
            @Nullable String incomingMsg,
            @Nullable StatusBarNotification sbn
    ) {
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        ReplyAttemptEntity entity = ReplyAttemptEntity.fromResult(attempt, result, senderName, incomingMsg);
        messageLogsDB.replyAttemptDao().upsertAttemptSafely(entity);

        if (result.isDispatched()) {
            if (messageLogsDB.logsDao().countByAttemptIdSync(attempt.getAttemptId()) == 0) {
                String pkg = (sbn != null && sbn.getPackageName() != null)
                        ? sbn.getPackageName()
                        : attempt.getPackageName();
                long arrivedTime = (sbn != null && sbn.getNotification() != null)
                        ? sbn.getNotification().when
                        : attempt.getCreatedAtMs();
                logReplyInternal(
                        pkg,
                        arrivedTime,
                        senderName,
                        incomingMsg,
                        attempt.getReplyText() != null ? attempt.getReplyText() : "",
                        attempt.getSourceLabel(),
                        ReplyHistoryStatus.DISPATCHED,
                        attempt.getAttemptId(),
                        result.getTimestampMs()
                );
            }
        }
    }

    @Nullable
    public ReplyAttemptEntity getAttemptById(@NonNull String attemptId) {
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        return messageLogsDB.replyAttemptDao().getAttemptByIdSync(attemptId);
    }

    public int getTotalAttemptCount() {
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        return messageLogsDB.replyAttemptDao().getTotalAttemptCountSync();
    }

    private void logReplyInternal(
            String packageName,
            long arrivedTime,
            @Nullable String title,
            @Nullable String incomingMsg,
            String repliedMsg,
            @Nullable String matchedRuleName,
            @NonNull String executionStatus,
            @Nullable String attemptId,
            long replyTime
    ) {
        String safePackage = packageName != null ? packageName : "";
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        int packageIndex = messageLogsDB.appPackageDao().getPackageIndex(safePackage);
        if (packageIndex <= 0) {
            AppPackage appPackage = new AppPackage(safePackage);
            messageLogsDB.appPackageDao().insertAppPackage(appPackage);
            packageIndex = messageLogsDB.appPackageDao().getPackageIndex(safePackage);
        }
        MessageLog logs = new MessageLog(
                packageIndex,
                title,
                incomingMsg,
                arrivedTime,
                repliedMsg,
                matchedRuleName,
                replyTime,
                executionStatus,
                attemptId
        );
        messageLogsDB.logsDao().logReply(logs);
    }

    public long getLastRepliedTime(String packageName, String title) {
        if (title == null) {
            return 0;
        }
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        return messageLogsDB.logsDao().getLastReplyTimeStamp(title, packageName);
    }

    public long getFirstRepliedTime() {
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        return messageLogsDB.logsDao().getFirstRepliedTime();
    }
}
