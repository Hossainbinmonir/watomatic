package com.parishod.watomatic.model.utils;

import android.content.Context;
import android.service.notification.StatusBarNotification;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.parishod.watomatic.model.CustomRepliesData;
import com.parishod.watomatic.model.ai.AiMemoryDatabase;
import com.parishod.watomatic.model.ai.AiMemoryRepository;
import com.parishod.watomatic.model.logs.AppPackage;
import com.parishod.watomatic.model.logs.AttemptClaimPersistOutcome;
import com.parishod.watomatic.model.logs.ClaimAcquireOutcome;
import com.parishod.watomatic.model.logs.MessageLog;
import com.parishod.watomatic.model.logs.MessageLogsDB;
import com.parishod.watomatic.model.logs.NotificationDispatchClaimEntity;
import com.parishod.watomatic.model.logs.ReplyAttemptEntity;
import com.parishod.watomatic.model.logs.ReplyHistoryStatus;
import com.parishod.watomatic.model.preferences.PreferencesManager;
import com.parishod.watomatic.model.rules.AutoReplyRulesDatabase;
import com.parishod.watomatic.service.reply.PreDispatchRetryCoordinator;
import com.parishod.watomatic.service.reply.PreDispatchRetryStateStore;
import com.parishod.watomatic.service.reply.ReplyAttempt;
import com.parishod.watomatic.service.reply.ReplyExecutionManager;
import com.parishod.watomatic.service.reply.ReplyExecutionResult;
import com.parishod.watomatic.service.reply.ReplyExecutionStatus;
import com.parishod.watomatic.service.reply.ReplyRetryPolicy;
import com.parishod.watomatic.service.reply.RetryDecision;

public class DbUtils {
    private static final Object RETENTION_CLEANUP_LOCK = new Object();
    public static final long CLAIM_PURGE_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    public static final long HISTORY_PURGE_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    public static final long HISTORY_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;

    public static final class RetentionCleanupSummary {
        private final boolean claimsRan;
        private final boolean historyRan;
        private final boolean claimsSucceeded;
        private final boolean historySucceeded;
        private final int deletedClaimsCount;
        private final int deletedHistoryCount;

        public RetentionCleanupSummary(
                boolean claimsRan,
                boolean historyRan,
                boolean claimsSucceeded,
                boolean historySucceeded,
                int deletedClaimsCount,
                int deletedHistoryCount
        ) {
            this.claimsRan = claimsRan;
            this.historyRan = historyRan;
            this.claimsSucceeded = claimsSucceeded;
            this.historySucceeded = historySucceeded;
            this.deletedClaimsCount = deletedClaimsCount;
            this.deletedHistoryCount = deletedHistoryCount;
        }

        public boolean isClaimsRan() { return claimsRan; }
        public boolean isHistoryRan() { return historyRan; }
        public boolean isClaimsSucceeded() { return claimsSucceeded; }
        public boolean isHistorySucceeded() { return historySucceeded; }
        public int getDeletedClaimsCount() { return deletedClaimsCount; }
        public int getDeletedHistoryCount() { return deletedHistoryCount; }
    }
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
        purgeExpiredHistory(System.currentTimeMillis());
    }

    public int purgeExpiredHistory(long nowMs) {
        return purgeExpiredHistoryInternal(nowMs, new boolean[1]);
    }

    private int purgeExpiredHistoryInternal(long nowMs, @NonNull boolean[] allSubsystemsSucceededOut) {
        long cutoffMs = nowMs - HISTORY_RETENTION_MS;
        Context appCtx = mContext.getApplicationContext();
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(appCtx);
        final int[] deletedCount = new int[1];
        boolean logsOk = false;
        boolean aiOk = false;
        boolean rulesOk = false;
        messageLogsDB.runInTransaction(() -> {
            int deletedLogs = messageLogsDB.logsDao().purgeMessageLogsBeforeSync(cutoffMs);
            int deletedAttempts = messageLogsDB.replyAttemptDao().purgeResolvedAttemptsBeforeSync(cutoffMs);
            deletedCount[0] = deletedLogs + deletedAttempts;
        });
        logsOk = true;
        try {
            AiMemoryRepository aiRepo = new AiMemoryRepository(AiMemoryDatabase.getInstance(appCtx).aiMemoryDao());
            deletedCount[0] += aiRepo.purgeExpiredMemory(appCtx, nowMs);
            aiOk = true;
        } catch (Exception ignored) {
            // Keep MessageLogsDB purge independent if AI memory DB is unavailable
        }
        try {
            int deletedRuleLogs = AutoReplyRulesDatabase.getInstance(appCtx)
                    .executionLogDao()
                    .purgeOldLogsSync(cutoffMs);
            deletedCount[0] += Math.max(0, deletedRuleLogs);
            rulesOk = true;
        } catch (Exception ignored) {
            // Keep MessageLogsDB purge independent if Rules DB is unavailable or on main thread
        }
        allSubsystemsSucceededOut[0] = logsOk && aiOk && rulesOk;
        return deletedCount[0];
    }

    public boolean isRetentionCleanupDue(long nowMs) {
        PreferencesManager prefs = PreferencesManager.getPreferencesInstance(mContext.getApplicationContext());
        return isIntervalElapsed(prefs.getLastClaimsPurgedTime(), nowMs, CLAIM_PURGE_INTERVAL_MS)
                || isIntervalElapsed(prefs.getLastPurgedTime(), nowMs, HISTORY_PURGE_INTERVAL_MS);
    }

    private static boolean isIntervalElapsed(long lastRunMs, long nowMs, long intervalMs) {
        if (lastRunMs <= 0L) {
            return true;
        }
        if (nowMs < lastRunMs) {
            return false;
        }
        return (nowMs - lastRunMs) >= intervalMs;
    }

    @NonNull
    public RetentionCleanupSummary runPeriodicRetentionCleanupIfDue(long nowMs) {
        return runPeriodicRetentionCleanupIfDue(nowMs, false);
    }

    @NonNull
    public RetentionCleanupSummary runPeriodicRetentionCleanupIfDue(long nowMs, boolean force) {
        synchronized (RETENTION_CLEANUP_LOCK) {
            Context appCtx = mContext.getApplicationContext();
            PreferencesManager prefs = PreferencesManager.getPreferencesInstance(appCtx);
            boolean claimsDue = force || isIntervalElapsed(prefs.getLastClaimsPurgedTime(), nowMs, CLAIM_PURGE_INTERVAL_MS);
            boolean historyDue = force || isIntervalElapsed(prefs.getLastPurgedTime(), nowMs, HISTORY_PURGE_INTERVAL_MS);
            if (!claimsDue && !historyDue) {
                return new RetentionCleanupSummary(false, false, true, true, 0, 0);
            }

            boolean claimsSucceeded = true;
            int deletedClaims = 0;
            if (claimsDue) {
                try {
                    deletedClaims = purgeExpiredNotificationClaims(nowMs);
                    PreDispatchRetryStateStore.pruneExpired(
                            appCtx,
                            nowMs,
                            NotificationDispatchClaimEntity.DEFAULT_UNRESOLVED_TTL_MS
                    );
                    prefs.setLastClaimsPurgedTime(nowMs);
                } catch (Exception e) {
                    claimsSucceeded = false;
                }
            }

            boolean historySucceeded = true;
            int deletedHistory = 0;
            if (historyDue) {
                try {
                    boolean[] allOk = new boolean[1];
                    deletedHistory = purgeExpiredHistoryInternal(nowMs, allOk);
                    historySucceeded = allOk[0];
                    if (historySucceeded) {
                        prefs.setPurgeMessageTime(nowMs);
                    }
                } catch (Exception e) {
                    historySucceeded = false;
                }
            }

            return new RetentionCleanupSummary(
                    claimsDue,
                    historyDue,
                    claimsSucceeded,
                    historySucceeded,
                    deletedClaims,
                    deletedHistory
            );
        }
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

    @NonNull
    public ClaimAcquireOutcome tryAcquireAuthorizedPreDispatchClaim(
            @NonNull String eventKey,
            @NonNull String claimToken,
            @NonNull String packageName,
            @NonNull String senderKey,
            @Nullable String notificationKey,
            long nowMs,
            int priorRetryCount,
            int maxRetries
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
        return PreDispatchRetryCoordinator.tryAcquireAuthorizedPreDispatchClaimInDb(
                messageLogsDB,
                claim,
                priorRetryCount,
                maxRetries
        );
    }

    public int updateNotificationClaimTransition(
            @NonNull String eventKey,
            @NonNull String expectedAttemptId,
            @NonNull String newAttemptId,
            @NonNull ReplyExecutionStatus status,
            long updatedAtMs
    ) {
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        int updated = messageLogsDB.notificationDispatchClaimDao().updateClaimStateByOwnerSync(
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
        if (updated > 0) {
            com.parishod.watomatic.model.logs.NotificationDispatchClaimDao
                    .clearPriorFailedSnapshot(eventKey, expectedAttemptId);
        }
        return updated;
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
        int updated = messageLogsDB.notificationDispatchClaimDao().updateClaimStateByOwnerSync(
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
        if (updated > 0) {
            com.parishod.watomatic.model.logs.NotificationDispatchClaimDao
                    .clearPriorFailedSnapshot(eventKey, expectedAttemptId);
        }
        return updated;
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
        com.parishod.watomatic.model.logs.NotificationDispatchClaimDao
                .clearPriorFailedSnapshot(eventKey, claimToken);
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

    @NonNull
    public java.util.List<ReplyAttemptEntity> getAttemptsForEventKey(@NonNull String eventKey) {
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        return messageLogsDB.replyAttemptDao().getAttemptsByEventKeySync(eventKey);
    }

    @Nullable
    public RetryDecision getBlockingAttemptDecisionForEvent(@NonNull String eventKey) {
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        java.util.List<ReplyAttemptEntity> attempts =
                messageLogsDB.replyAttemptDao().getAttemptsByEventKeySync(eventKey);
        return ReplyRetryPolicy.evaluateAttemptsBlockingStatus(attempts);
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

    @NonNull
    public AttemptClaimPersistOutcome recordAttemptAndClaimResult(
            @Nullable String eventKey,
            @NonNull ReplyAttempt attempt,
            @NonNull ReplyExecutionResult result,
            @Nullable String senderName,
            @Nullable String incomingMsg,
            @Nullable StatusBarNotification sbn
    ) {
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        final AttemptClaimPersistOutcome[] outcomeHolder = new AttemptClaimPersistOutcome[1];
        messageLogsDB.runInTransaction(() -> {
            recordAttemptResult(attempt, result, senderName, incomingMsg, sbn);
            ReplyAttemptEntity authoritativeAttempt =
                    messageLogsDB.replyAttemptDao().getAttemptByIdSync(attempt.getAttemptId());

            long fallbackTs = result.getTimestampMs() > 0L
                    ? result.getTimestampMs()
                    : System.currentTimeMillis();

            ReplyExecutionStatus effectiveAttemptStatus;
            ReplyExecutionResult authoritativeClaimResult;
            boolean reconciled;

            if (authoritativeAttempt == null) {
                effectiveAttemptStatus = ReplyExecutionStatus.UNKNOWN;
                authoritativeClaimResult = new ReplyExecutionResult(
                        attempt.getAttemptId(),
                        ReplyExecutionStatus.UNKNOWN,
                        result.getDispatchAttempted() || result.isDispatched(),
                        false,
                        ReplyExecutionManager.FAILURE_CLAIM_PERSISTENCE_ERROR,
                        "Authoritative attempt row missing after persistence",
                        fallbackTs
                );
                reconciled = true;
            } else if (ReplyExecutionStatus.DISPATCHED.name().equals(authoritativeAttempt.getStatus())) {
                boolean hasMessageLog = messageLogsDB.logsDao().countByAttemptIdSync(attempt.getAttemptId()) > 0;
                if (hasMessageLog) {
                    effectiveAttemptStatus = ReplyExecutionStatus.DISPATCHED;
                    authoritativeClaimResult = buildResultFromAuthoritativeEntity(authoritativeAttempt, fallbackTs);
                    reconciled = !result.isDispatched();
                } else {
                    effectiveAttemptStatus = ReplyExecutionStatus.UNKNOWN;
                    authoritativeClaimResult = new ReplyExecutionResult(
                            attempt.getAttemptId(),
                            ReplyExecutionStatus.UNKNOWN,
                            true,
                            false,
                            ReplyExecutionManager.FAILURE_CLAIM_PERSISTENCE_ERROR,
                            "DISPATCHED attempt missing consistent MessageLog entry",
                            authoritativeAttempt.getUpdatedAtMs() > 0L
                                    ? authoritativeAttempt.getUpdatedAtMs()
                                    : fallbackTs
                    );
                    reconciled = true;
                }
            } else {
                ReplyExecutionStatus parsedStatus = parseAttemptStatus(authoritativeAttempt.getStatus());
                boolean isTerminalNonDispatched = parsedStatus == ReplyExecutionStatus.FAILED
                        || parsedStatus == ReplyExecutionStatus.CANCELLED
                        || parsedStatus == ReplyExecutionStatus.UNKNOWN;
                if (isTerminalNonDispatched) {
                    effectiveAttemptStatus = parsedStatus;
                    authoritativeClaimResult = buildResultFromAuthoritativeEntity(authoritativeAttempt, fallbackTs);
                    reconciled = !parsedStatus.name().equals(result.getStatus().name());
                } else {
                    effectiveAttemptStatus = ReplyExecutionStatus.UNKNOWN;
                    authoritativeClaimResult = new ReplyExecutionResult(
                            attempt.getAttemptId(),
                            ReplyExecutionStatus.UNKNOWN,
                            authoritativeAttempt.getDispatchAttempted() || result.getDispatchAttempted(),
                            false,
                            ReplyExecutionManager.FAILURE_CLAIM_PERSISTENCE_ERROR,
                            "Non-terminal attempt reconciled conservatively to UNKNOWN",
                            authoritativeAttempt.getUpdatedAtMs() > 0L
                                    ? authoritativeAttempt.getUpdatedAtMs()
                                    : fallbackTs
                    );
                    reconciled = true;
                }
            }

            boolean claimUpdated = false;
            boolean claimMissing = false;
            String effectiveClaimStatus = null;
            if (eventKey != null) {
                int updatedRows = updateNotificationClaimResult(
                        eventKey,
                        attempt.getAttemptId(),
                        authoritativeClaimResult
                );
                claimUpdated = updatedRows > 0;
                NotificationDispatchClaimEntity finalClaim =
                        messageLogsDB.notificationDispatchClaimDao().getClaimByEventKeySync(eventKey);
                if (finalClaim == null) {
                    claimMissing = true;
                } else {
                    effectiveClaimStatus = finalClaim.getClaimStatus();
                }
            }

            boolean dispatchedPersisted = effectiveAttemptStatus == ReplyExecutionStatus.DISPATCHED
                    && hasConsistentDispatchedPersistence(attempt.getAttemptId())
                    && (eventKey == null || ReplyExecutionStatus.DISPATCHED.name().equals(effectiveClaimStatus));

            outcomeHolder[0] = new AttemptClaimPersistOutcome(
                    effectiveAttemptStatus,
                    effectiveClaimStatus,
                    dispatchedPersisted,
                    claimUpdated,
                    claimMissing,
                    reconciled,
                    authoritativeClaimResult.getFailureCategory()
            );
        });
        return outcomeHolder[0];
    }

    @NonNull
    private static ReplyExecutionStatus parseAttemptStatus(@Nullable String status) {
        if (status == null) {
            return ReplyExecutionStatus.UNKNOWN;
        }
        try {
            return ReplyExecutionStatus.valueOf(status);
        } catch (IllegalArgumentException ignored) {
            return ReplyExecutionStatus.UNKNOWN;
        }
    }

    @NonNull
    private static ReplyExecutionResult buildResultFromAuthoritativeEntity(
            @NonNull ReplyAttemptEntity entity,
            long fallbackTimestampMs
    ) {
        ReplyExecutionStatus status = parseAttemptStatus(entity.getStatus());
        long ts = entity.getUpdatedAtMs() > 0L ? entity.getUpdatedAtMs() : fallbackTimestampMs;
        return new ReplyExecutionResult(
                entity.getAttemptId(),
                status,
                entity.getDispatchAttempted(),
                entity.getDispatchedNormally(),
                entity.getFailureCategory(),
                "Reconciled from authoritative persisted attempt",
                ts
        );
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
        messageLogsDB.runInTransaction(() -> {
            messageLogsDB.replyAttemptDao().upsertAttemptSafely(entity);

            if (result.isDispatched()) {
                ReplyAttemptEntity persisted = messageLogsDB.replyAttemptDao().getAttemptByIdSync(attempt.getAttemptId());
                if (persisted != null
                        && ReplyExecutionStatus.DISPATCHED.name().equals(persisted.getStatus())
                        && messageLogsDB.logsDao().countByAttemptIdSync(attempt.getAttemptId()) == 0) {
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
        });
    }

    public boolean hasConsistentDispatchedPersistence(@NonNull String attemptId) {
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        ReplyAttemptEntity persistedAttempt = messageLogsDB.replyAttemptDao().getAttemptByIdSync(attemptId);
        return persistedAttempt != null
                && ReplyExecutionStatus.DISPATCHED.name().equals(persistedAttempt.getStatus())
                && messageLogsDB.logsDao().countByAttemptIdSync(attemptId) > 0;
    }

    public void recordFallbackAttemptOutcome(
            @NonNull ReplyAttempt attempt,
            @NonNull ReplyExecutionResult result,
            @Nullable String senderName,
            @Nullable String incomingMsg
    ) {
        if (result.isDispatched()) {
            throw new IllegalArgumentException("Fallback outcome persistence must not record DISPATCHED without history");
        }
        MessageLogsDB messageLogsDB = MessageLogsDB.getInstance(mContext.getApplicationContext());
        ReplyAttemptEntity entity = ReplyAttemptEntity.fromResult(attempt, result, senderName, incomingMsg);
        messageLogsDB.replyAttemptDao().upsertAttemptSafely(entity);
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
        synchronized (AppPackage.class) {
            messageLogsDB.runInTransaction(() -> {
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
            });
        }
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
