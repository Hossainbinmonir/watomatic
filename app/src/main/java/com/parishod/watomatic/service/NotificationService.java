package com.parishod.watomatic.service;

import static com.parishod.watomatic.model.utils.Constants.CUSTOM_AI_PROVIDER_NAME;
import static com.parishod.watomatic.model.utils.Constants.DEFAULT_LLM_MODEL;
import static com.parishod.watomatic.model.utils.Constants.DEFAULT_LLM_PROMPT;

import android.content.res.Resources;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.widget.Toast;
// import Constants.kt
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import com.parishod.watomatic.model.utils.Constants;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.parishod.watomatic.NotificationWear;
import com.parishod.watomatic.R;
import com.parishod.watomatic.model.CustomRepliesData;
import com.parishod.watomatic.model.ai.AiConversationMessage;
import com.parishod.watomatic.model.ai.AiMemoryDao;
import com.parishod.watomatic.model.ai.AiMemoryDatabase;
import com.parishod.watomatic.model.ai.AiMemoryRepository;
import com.parishod.watomatic.model.ai.AiNote;
import com.parishod.watomatic.model.logs.ClaimAcquireOutcome;
import com.parishod.watomatic.model.logs.NotificationDispatchClaimEntity;
import com.parishod.watomatic.model.rules.AutoReplyRulesDatabase;
import com.parishod.watomatic.network.OpenAIService;
import com.parishod.watomatic.network.RetrofitInstance;
import com.parishod.watomatic.network.model.openai.Message;
import com.parishod.watomatic.network.model.openai.OpenAIRequest;
import com.parishod.watomatic.network.model.openai.OpenAIResponse;
import com.parishod.watomatic.model.preferences.PreferencesManager;
import com.parishod.watomatic.model.utils.ContactsHelper;
import com.parishod.watomatic.model.utils.DbUtils;
import com.parishod.watomatic.model.utils.NotificationHelper;
import com.parishod.watomatic.model.utils.NotificationUtils;
import com.parishod.watomatic.model.rules.AutoReplyRule;
import com.parishod.watomatic.service.ai.AiPolicyDecision;
import com.parishod.watomatic.service.ai.AiPromptAndPolicyHelper;
import com.parishod.watomatic.service.ai.ParsedAiResponse;
import com.parishod.watomatic.service.reply.NotificationEventKeyBuilder;
import com.parishod.watomatic.service.reply.NotificationReplyRecoveryManager;
import com.parishod.watomatic.service.reply.PendingClaimHandoff;
import com.parishod.watomatic.service.reply.ReplyAttempt;
import com.parishod.watomatic.service.reply.ReplyExecutionManager;
import com.parishod.watomatic.service.reply.ReplyExecutionResult;
import com.parishod.watomatic.service.reply.ReplyExecutionStatus;
import com.parishod.watomatic.service.reply.ReplyRetryPolicy;
import com.parishod.watomatic.service.reply.ReplySourceType;
import com.parishod.watomatic.service.reply.RetryDecision;
import com.parishod.watomatic.service.rules.RoomRuleExecutionLogStore;
import com.parishod.watomatic.service.rules.RuleEvaluator;
import com.parishod.watomatic.service.rules.RuleNotificationProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;


public class NotificationService extends NotificationListenerService {
    private final String TAG = NotificationService.class.getSimpleName();
    private DbUtils dbUtils;
    private AiMemoryRepository aiMemoryRepository;
    private NotificationReplyDecider replyDecider;
    private RuleNotificationProcessor ruleNotificationProcessor;
    private ExecutorService aiMemoryExecutor;
    private volatile ReplyExecutionManager replyExecutionManager;

    private final AtomicBoolean serviceDestroyed = new AtomicBoolean(false);
    private final AtomicBoolean startupRecoverySucceeded = new AtomicBoolean(false);
    private volatile long lastClaimPurgeAtMs = 0L;
    private final Object lifecycleLock = new Object();
    // Maps active async AI claimToken -> eventKey so service shutdown cancels interrupted claims cleanly
    private final ConcurrentHashMap<String, String> pendingAsyncClaims = new ConcurrentHashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        ensureStartupRecovery();
    }

    private void ensureStartupRecovery() {
        if (!startupRecoverySucceeded.get() && !serviceDestroyed.get()) {
            if (NotificationReplyRecoveryManager.tryRecoverOnStartup(getApplicationContext())) {
                startupRecoverySucceeded.set(true);
            }
        }
    }

    private synchronized DbUtils getDbUtils() {
        if (dbUtils == null) {
            dbUtils = new DbUtils(getApplicationContext());
        }
        return dbUtils;
    }

    @VisibleForTesting
    synchronized void setDbUtilsForTesting(DbUtils dbUtils) {
        this.dbUtils = dbUtils;
    }

    private synchronized AiMemoryRepository getAiMemoryRepository() {
        if (aiMemoryRepository == null) {
            aiMemoryRepository = new AiMemoryRepository(
                    AiMemoryDatabase.getInstance(getApplicationContext()).aiMemoryDao()
            );
        }
        return aiMemoryRepository;
    }

    @VisibleForTesting
    synchronized void setAiMemoryRepositoryForTesting(AiMemoryRepository repository) {
        this.aiMemoryRepository = repository;
    }

    @VisibleForTesting
    void awaitAiMemoryIdleForTesting() throws Exception {
        ExecutorService exec;
        synchronized (lifecycleLock) {
            exec = aiMemoryExecutor;
        }
        if (exec != null && !exec.isShutdown()) {
            exec.submit(() -> { }).get(5, TimeUnit.SECONDS);
        }
    }

    boolean isServiceDestroyed() {
        return serviceDestroyed.get();
    }

    ReplyExecutionManager getReplyExecutionManager() {
        synchronized (lifecycleLock) {
            if (serviceDestroyed.get()) {
                return null;
            }
            if (replyExecutionManager == null) {
                replyExecutionManager = new ReplyExecutionManager(getApplicationContext());
            }
            return replyExecutionManager;
        }
    }

    @VisibleForTesting
    void setReplyExecutionManagerForTesting(ReplyExecutionManager manager) {
        synchronized (lifecycleLock) {
            if (serviceDestroyed.get()) {
                if (manager != null) {
                    manager.shutdown();
                }
                return;
            }
            this.replyExecutionManager = manager;
        }
    }

    private ExecutorService getAiMemoryExecutor() {
        synchronized (lifecycleLock) {
            if (serviceDestroyed.get()) {
                return null;
            }
            if (aiMemoryExecutor == null || aiMemoryExecutor.isShutdown()) {
                aiMemoryExecutor = Executors.newSingleThreadExecutor();
            }
            return aiMemoryExecutor;
        }
    }

    private NotificationReplyDecider getReplyDecider() {
        if (replyDecider == null) {
            replyDecider = new NotificationReplyDecider(
                    PreferencesManager.getPreferencesInstance(this),
                    getDbUtils(),
                    ContactsHelper.Companion.getInstance(this));
        }
        return replyDecider;
    }

    private RuleNotificationProcessor getRuleNotificationProcessor() {
        synchronized (lifecycleLock) {
            if (serviceDestroyed.get()) {
                return null;
            }
            if (ruleNotificationProcessor == null) {
                ruleNotificationProcessor = new RuleNotificationProcessor(
                        getApplicationContext(),
                        Executors.newSingleThreadScheduledExecutor(),
                        getDbUtils()
                );
            }
            return ruleNotificationProcessor;
        }
    }

    @Nullable
    private String buildClaimEventKey(@Nullable StatusBarNotification sbn) {
        return NotificationEventKeyBuilder.buildEventKey(sbn, extractIncomingMessage(sbn));
    }

    @Nullable
    private String consumeHandedOffClaimToken(@Nullable StatusBarNotification sbn) {
        String eventKey = buildClaimEventKey(sbn);
        return eventKey != null ? PendingClaimHandoff.consume(eventKey) : null;
    }

    private void trackAsyncClaim(@Nullable String claimToken, @Nullable String eventKey) {
        if (claimToken != null && !claimToken.isEmpty() && eventKey != null && !eventKey.isEmpty()) {
            pendingAsyncClaims.put(claimToken, eventKey);
        }
    }

    private void untrackAsyncClaim(@Nullable String claimToken) {
        if (claimToken != null && !claimToken.isEmpty()) {
            pendingAsyncClaims.remove(claimToken);
        }
    }

    private void releaseUnusedClaimSafely(@Nullable String eventKey, @Nullable String claimToken) {
        untrackAsyncClaim(claimToken);
        if (eventKey == null || eventKey.isEmpty() || claimToken == null || claimToken.isEmpty()) {
            return;
        }
        try {
            getDbUtils().releaseUnusedNotificationClaim(eventKey, claimToken);
        } catch (Exception e) {
            Log.w(TAG, "Failed releasing unused notification claim (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void cancelPendingClaimSafely(
            @Nullable String eventKey,
            @Nullable String claimToken,
            @NonNull String failureCategory
    ) {
        untrackAsyncClaim(claimToken);
        if (eventKey == null || eventKey.isEmpty() || claimToken == null || claimToken.isEmpty()) {
            return;
        }
        try {
            getDbUtils().cancelPendingNotificationClaim(
                    eventKey,
                    claimToken,
                    failureCategory,
                    System.currentTimeMillis()
            );
        } catch (Exception e) {
            Log.w(TAG, "Failed cancelling pending notification claim (" + e.getClass().getSimpleName() + ")");
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        if (sbn == null || serviceDestroyed.get()) {
            return;
        }
        ensureStartupRecovery();
        RuleNotificationProcessor processor = getRuleNotificationProcessor();
        if (processor == null) {
            return;
        }
        processor.processNotification(sbn, new RuleNotificationProcessor.ReplyDispatchCallback() {
            @Override
            public void onRuleMatchedSendReply(
                    @NonNull StatusBarNotification statusBarNotification,
                    @NonNull NotificationWear notificationWear,
                    @NonNull String replyText,
                    @NonNull AutoReplyRule matchedRule
            ) {
                String eventKey = buildClaimEventKey(statusBarNotification);
                String claimToken = eventKey != null ? PendingClaimHandoff.consume(eventKey) : null;
                if (serviceDestroyed.get()) {
                    Log.d(TAG, "Ignoring rule dispatch after NotificationService destruction.");
                    cancelPendingClaimSafely(eventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
                    return;
                }
                if (matchedRule.getUseAiReply()) {
                    Log.d(TAG, "Rule matched with AI enabled (" + matchedRule.getName() + "), dispatching AI reply.");
                    dispatchRuleAiReply(statusBarNotification, notificationWear, matchedRule, claimToken);
                } else {
                    Log.d(TAG, "Rule matched (" + matchedRule.getName() + "), dispatching reply.");
                    sendActualReply(
                            statusBarNotification,
                            notificationWear,
                            replyText,
                            matchedRule.getName(),
                            ReplySourceType.RULE_STATIC,
                            matchedRule,
                            claimToken
                    );
                }
            }

            @Override
            public void onFallbackToLegacy(@NonNull StatusBarNotification statusBarNotification) {
                String eventKey = buildClaimEventKey(statusBarNotification);
                String claimToken = eventKey != null ? PendingClaimHandoff.consume(eventKey) : null;
                if (serviceDestroyed.get()) {
                    Log.d(TAG, "Ignoring legacy fallback after NotificationService destruction.");
                    cancelPendingClaimSafely(eventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
                    return;
                }
                NotificationReplyDecider decider = getReplyDecider();
                PreferencesManager prefs = PreferencesManager.getPreferencesInstance(NotificationService.this);
                boolean eligible;
                if (prefs.isByokRepliesEnabled()) {
                    // Check base service, supported app, notification freshness, non-self sender, and contact whitelist/blacklist;
                    // AI-specific group/cooldown/frequency limits are evaluated in sendReply -> prepareAndFetchAiReply.
                    eligible = decider.isServiceEnabled()
                            && decider.isSupportedPackage(statusBarNotification)
                            && NotificationUtils.isNewNotification(statusBarNotification)
                            && !isSelfMessage(statusBarNotification)
                            && decider.shouldReply(statusBarNotification);
                } else {
                    eligible = decider.canReply(statusBarNotification) && decider.shouldReply(statusBarNotification);
                }
                if (eligible) {
                    sendReply(statusBarNotification, claimToken);
                } else {
                    releaseUnusedClaimSafely(eventKey, claimToken);
                }
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        //START_STICKY  to order the system to restart your service as soon as possible when it was killed.
        return START_STICKY;
    }

    private boolean isSelfMessage(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null || sbn.getNotification().extras == null) {
            return false;
        }
        String title = NotificationUtils.getTitle(sbn);
        String selfDisplayName = sbn.getNotification().extras.getString("android.selfDisplayName");
        return title != null && selfDisplayName != null && title.equalsIgnoreCase(selfDisplayName);
    }

    private String extractIncomingMessage(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null || sbn.getNotification().extras == null) {
            return null;
        }
        Bundle extras = sbn.getNotification().extras;
        CharSequence text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT);
        if (text != null && !text.toString().trim().isEmpty()) {
            return text.toString();
        }
        Object rawText = extras.get("android.text");
        if (rawText != null && !rawText.toString().trim().isEmpty()) {
            return rawText.toString();
        }
        CharSequence bigText = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT);
        if (bigText != null && !bigText.toString().trim().isEmpty()) {
            return bigText.toString();
        }
        CharSequence[] textLines = extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES);
        if (textLines != null && textLines.length > 0) {
            for (int i = textLines.length - 1; i >= 0; i--) {
                if (textLines[i] != null && !textLines[i].toString().trim().isEmpty()) {
                    return textLines[i].toString();
                }
            }
        }
        android.os.Parcelable[] messages = extras.getParcelableArray("android.messages");
        if (messages != null && messages.length > 0) {
            for (int i = messages.length - 1; i >= 0; i--) {
                if (messages[i] instanceof Bundle) {
                    CharSequence msgText = ((Bundle) messages[i]).getCharSequence("text");
                    if (msgText != null && !msgText.toString().trim().isEmpty()) {
                        return msgText.toString();
                    }
                }
            }
        }
        return null;
    }

    ReplyExecutionResult sendActualReply(StatusBarNotification sbn, NotificationWear notificationWear, String replyText) {
        return sendActualReply(sbn, notificationWear, replyText, null, ReplySourceType.DEFAULT, null, null);
    }

    ReplyExecutionResult sendActualReply(StatusBarNotification sbn, NotificationWear notificationWear, String replyText, String ruleOrSourceName) {
        return sendActualReply(sbn, notificationWear, replyText, ruleOrSourceName, ReplySourceType.DEFAULT, null, null);
    }

    ReplyExecutionResult sendActualReply(
            StatusBarNotification sbn,
            NotificationWear notificationWear,
            String replyText,
            String ruleOrSourceName,
            ReplySourceType sourceType
    ) {
        return sendActualReply(sbn, notificationWear, replyText, ruleOrSourceName, sourceType, null, null);
    }

    ReplyExecutionResult sendActualReply(
            StatusBarNotification sbn,
            NotificationWear notificationWear,
            String replyText,
            String ruleOrSourceName,
            ReplySourceType sourceType,
            @Nullable AutoReplyRule matchedRule,
            @Nullable String preClaimedToken
    ) {
        String packageName = (sbn != null && sbn.getPackageName() != null) ? sbn.getPackageName() : "";
        String notificationKey = (sbn != null) ? sbn.getKey() : null;
        ReplySourceType resolvedSourceType = sourceType != null ? sourceType : ReplySourceType.DEFAULT;
        String sourceLabel;
        if (ruleOrSourceName != null) {
            sourceLabel = ruleOrSourceName;
        } else if (resolvedSourceType == ReplySourceType.AI_FALLBACK) {
            sourceLabel = matchedRule != null && matchedRule.getName() != null && !matchedRule.getName().trim().isEmpty()
                    ? matchedRule.getName().trim() + " • " + getString(R.string.history_source_ai_fallback)
                    : getString(R.string.history_source_ai_fallback);
        } else {
            sourceLabel = getString(R.string.history_source_default);
        }

        final DbUtils db = getDbUtils();
        final String senderName = sbn != null ? NotificationUtils.getTitle(sbn) : null;
        final String incomingMsg = extractIncomingMessage(sbn);
        final String eventKey = NotificationEventKeyBuilder.buildEventKey(sbn, incomingMsg);
        final String senderKey = NotificationEventKeyBuilder.normalizeSenderKey(senderName);

        String effectiveClaimToken = preClaimedToken;
        if (effectiveClaimToken == null && eventKey != null) {
            effectiveClaimToken = PendingClaimHandoff.consume(eventKey);
        }
        if (effectiveClaimToken != null) {
            untrackAsyncClaim(effectiveClaimToken);
        }

        ReplyExecutionManager manager = getReplyExecutionManager();
        if (serviceDestroyed.get() || manager == null) {
            Log.w(TAG, "Reply dispatch cancelled: NotificationService destroyed.");
            if (effectiveClaimToken != null && eventKey != null) {
                cancelPendingClaimSafely(eventKey, effectiveClaimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
            }
            return new ReplyExecutionResult(
                    effectiveClaimToken != null ? effectiveClaimToken : UUID.randomUUID().toString(),
                    ReplyExecutionStatus.CANCELLED,
                    false,
                    false,
                    ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN,
                    "Execution cancelled: NotificationService destroyed",
                    System.currentTimeMillis()
            );
        }

        String effectiveAttemptId = effectiveClaimToken;
        if (eventKey != null) {
            if (effectiveAttemptId == null) {
                effectiveAttemptId = UUID.randomUUID().toString();
                try {
                    ClaimAcquireOutcome outcome = db.tryAcquireNotificationClaim(
                            eventKey,
                            effectiveAttemptId,
                            packageName,
                            senderKey,
                            notificationKey,
                            System.currentTimeMillis(),
                            false
                    );
                    if (!outcome.getAcquired()) {
                        String denialCategory = ReplyRetryPolicy.failureCategoryForDenial(outcome.getDecision());
                        Log.i(TAG, "Suppressed reply dispatch by persistent claim: decision="
                                + outcome.getDecision() + ", category=" + denialCategory);
                        return new ReplyExecutionResult(
                                effectiveAttemptId,
                                ReplyExecutionStatus.CANCELLED,
                                false,
                                false,
                                denialCategory,
                                "Reply suppressed: " + outcome.getDecision(),
                                System.currentTimeMillis()
                        );
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed acquiring persistent claim before dispatch (" + e.getClass().getSimpleName() + ")");
                    return new ReplyExecutionResult(
                            effectiveAttemptId,
                            ReplyExecutionStatus.FAILED,
                            false,
                            false,
                            ReplyExecutionManager.FAILURE_CLAIM_PERSISTENCE_ERROR,
                            "Persistent claim acquisition failed",
                            System.currentTimeMillis()
                    );
                }
            } else {
                try {
                    NotificationDispatchClaimEntity existing = db.getNotificationClaim(eventKey);
                    if (existing != null) {
                        boolean ownerMatches = effectiveAttemptId.equals(existing.getAttemptId());
                        boolean claimOpen = NotificationDispatchClaimEntity.STATUS_CLAIMED.equals(existing.getClaimStatus());
                        if (!ownerMatches || !claimOpen) {
                            RetryDecision decision = !ownerMatches
                                    ? ReplyRetryPolicy.evaluateClaimConflict(existing, false)
                                    : ReplyRetryPolicy.evaluateClaimConflict(
                                            existing,
                                            false,
                                            false,
                                            false,
                                            ReplyExecutionStatus.CANCELLED.name().equals(existing.getClaimStatus()),
                                            true
                                    );
                            String denialCategory = ReplyRetryPolicy.failureCategoryForDenial(decision);
                            Log.i(TAG, "Pre-claimed token blocked at dispatch gate: decision="
                                    + decision + ", category=" + denialCategory);
                            return new ReplyExecutionResult(
                                    effectiveAttemptId,
                                    ReplyExecutionStatus.CANCELLED,
                                    false,
                                    false,
                                    denialCategory,
                                    "Pre-claimed token not eligible for dispatch: " + decision,
                                    System.currentTimeMillis()
                            );
                        }
                    } else {
                        // Never execute with a missing persistent claim row; re-acquire under the token atomically.
                        ClaimAcquireOutcome outcome = db.tryAcquireNotificationClaim(
                                eventKey,
                                effectiveAttemptId,
                                packageName,
                                senderKey,
                                notificationKey,
                                System.currentTimeMillis(),
                                false
                        );
                        if (!outcome.getAcquired()) {
                            String denialCategory = ReplyRetryPolicy.failureCategoryForDenial(outcome.getDecision());
                            Log.i(TAG, "Missing pre-claimed row blocked at dispatch gate: decision="
                                    + outcome.getDecision() + ", category=" + denialCategory);
                            return new ReplyExecutionResult(
                                    effectiveAttemptId,
                                    ReplyExecutionStatus.CANCELLED,
                                    false,
                                    false,
                                    denialCategory,
                                    "Reply suppressed: " + outcome.getDecision(),
                                    System.currentTimeMillis()
                            );
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed verifying persistent claim before dispatch (" + e.getClass().getSimpleName() + ")");
                    return new ReplyExecutionResult(
                            effectiveAttemptId,
                            ReplyExecutionStatus.FAILED,
                            false,
                            false,
                            ReplyExecutionManager.FAILURE_CLAIM_PERSISTENCE_ERROR,
                            "Persistent claim verification failed",
                            System.currentTimeMillis()
                    );
                }
            }
        }

        final ReplyAttempt attempt = (effectiveAttemptId != null)
                ? ReplyAttempt.createWithAttemptId(
                        effectiveAttemptId,
                        resolvedSourceType,
                        sourceLabel,
                        packageName,
                        notificationKey,
                        replyText,
                        eventKey
                )
                : ReplyAttempt.create(
                        resolvedSourceType,
                        sourceLabel,
                        packageName,
                        notificationKey,
                        replyText,
                        eventKey
                );

        ReplyExecutionResult result;
        try {
            result = manager.executeReply(attempt, notificationWear, updatedAttempt -> {
                if (updatedAttempt.getStatus() == ReplyExecutionStatus.GENERATED
                        || updatedAttempt.getStatus() == ReplyExecutionStatus.DISPATCHING) {
                    try {
                        db.recordAttemptTransition(updatedAttempt, senderName, incomingMsg);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed persisting reply transition (" + e.getClass().getSimpleName() + ")");
                    }
                    if (eventKey != null) {
                        try {
                            db.updateNotificationClaimTransition(
                                    eventKey,
                                    attempt.getAttemptId(),
                                    updatedAttempt.getAttemptId(),
                                    updatedAttempt.getStatus(),
                                    updatedAttempt.getUpdatedAtMs()
                            );
                        } catch (Exception e) {
                            Log.e(TAG, "Failed persisting claim transition (" + e.getClass().getSimpleName() + ")");
                        }
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Unexpected exception during reply execution (" + e.getClass().getSimpleName() + ")");
            boolean dispatchAttempted = false;
            try {
                com.parishod.watomatic.model.logs.ReplyAttemptEntity persistedAttempt = db.getAttemptById(attempt.getAttemptId());
                if (persistedAttempt != null && persistedAttempt.getDispatchAttempted()) {
                    dispatchAttempted = true;
                }
            } catch (Exception ignored) {
                dispatchAttempted = true;
            }
            result = new ReplyExecutionResult(
                    attempt.getAttemptId(),
                    dispatchAttempted ? ReplyExecutionStatus.UNKNOWN : ReplyExecutionStatus.FAILED,
                    dispatchAttempted,
                    false,
                    dispatchAttempted
                            ? ReplyExecutionManager.FAILURE_AMBIGUOUS_SEND_EXCEPTION
                            : ReplyExecutionManager.FAILURE_INVALID_ATTEMPT_STATE,
                    "Unexpected execution exception (" + e.getClass().getSimpleName() + ")",
                    System.currentTimeMillis()
            );
        }

        try {
            db.recordAttemptAndClaimResult(eventKey, attempt, result, senderName, incomingMsg, sbn);
        } catch (Exception e) {
            Log.e(TAG, "Failed persisting reply execution and claim result (" + e.getClass().getSimpleName() + ")");
            if (eventKey != null) {
                try {
                    db.updateNotificationClaimResult(eventKey, attempt.getAttemptId(), result);
                } catch (Exception claimEx) {
                    Log.e(TAG, "Failed persisting fallback notification claim result (" + claimEx.getClass().getSimpleName() + ")");
                }
            }
        }

        if (result.isDispatched()) {
            if (matchedRule != null) {
                final long ruleId = matchedRule.getId();
                final long arrivedTime = (sbn != null && sbn.getNotification() != null)
                        ? sbn.getNotification().when
                        : attempt.getCreatedAtMs();
                final String ruleSenderName = (senderName != null && !senderName.trim().isEmpty())
                        ? senderName.trim()
                        : RuleEvaluator.UNKNOWN_SENDER;
                final String safeNotifKey = notificationKey != null ? notificationKey : "";
                final long repliedAt = result.getTimestampMs();
                Runnable recordRuleLogTask = () -> {
                    try {
                        new RoomRuleExecutionLogStore(
                                AutoReplyRulesDatabase.getInstance(getApplicationContext()).executionLogDao()
                        ).recordExecution(
                                ruleId,
                                packageName,
                                ruleSenderName,
                                safeNotifKey,
                                arrivedTime,
                                repliedAt
                        );
                    } catch (Exception e) {
                        Log.e(TAG, "Failed recording rule execution log (" + e.getClass().getSimpleName() + ")");
                    }
                };
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                    ExecutorService exec = getAiMemoryExecutor();
                    if (exec != null) {
                        try {
                            exec.execute(recordRuleLogTask);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed scheduling rule execution log (" + e.getClass().getSimpleName() + ")");
                        }
                    }
                } else {
                    recordRuleLogTask.run();
                }
            }
            if (!serviceDestroyed.get() && PreferencesManager.getPreferencesInstance(this).isShowNotificationEnabled()) {
                Bundle extras = (sbn != null && sbn.getNotification() != null) ? sbn.getNotification().extras : null;
                String notifTitle = extras != null ? extras.getString("android.title") : senderName;
                String notifText = extras != null ? extras.getString("android.text") : incomingMsg;
                NotificationHelper.getInstance(getApplicationContext()).sendNotification(notifTitle, notifText, packageName);
            }
            if (!serviceDestroyed.get() && notificationKey != null) {
                try {
                    cancelNotification(notificationKey);
                } catch (Exception e) {
                    Log.w(TAG, "Unable to cancel notification (" + e.getClass().getSimpleName() + ")");
                }
            }
            if (!serviceDestroyed.get() && canPurgeMessages()) {
                try {
                    db.purgeMessageLogs();
                    PreferencesManager.getPreferencesInstance(this).setPurgeMessageTime(System.currentTimeMillis());
                } catch (Exception e) {
                    Log.w(TAG, "Unable to purge old message logs (" + e.getClass().getSimpleName() + ")");
                }
            }
            long nowMs = System.currentTimeMillis();
            if (!serviceDestroyed.get() && (nowMs - lastClaimPurgeAtMs) >= 24L * 60L * 60L * 1000L) {
                try {
                    db.purgeExpiredNotificationClaims(nowMs);
                    lastClaimPurgeAtMs = nowMs;
                } catch (Exception e) {
                    Log.w(TAG, "Unable to purge expired notification claims (" + e.getClass().getSimpleName() + ")");
                }
            }
        } else {
            Log.w(TAG, "Reply dispatch not completed: status=" + result.getStatus()
                    + ", category=" + result.getFailureCategory());
        }
        return result;
    }

    @Nullable
    private String resolveFallbackSourceLabel(
            @Nullable AutoReplyRule matchedRule,
            @Nullable String historySourceLabel
    ) {
        if (matchedRule != null && matchedRule.getName() != null && !matchedRule.getName().trim().isEmpty()) {
            return matchedRule.getName().trim() + " • " + getString(R.string.history_source_ai_fallback);
        }
        String globalAiLabel = getString(R.string.history_source_ai);
        if (historySourceLabel != null && !historySourceLabel.equals(globalAiLabel) && historySourceLabel.contains(" • ")) {
            String prefix = historySourceLabel.substring(0, historySourceLabel.indexOf(" • ")).trim();
            if (!prefix.isEmpty()) {
                return prefix + " • " + getString(R.string.history_source_ai_fallback);
            }
        }
        return null;
    }

    private void dispatchRuleAiReply(
            @NonNull StatusBarNotification sbn,
            @NonNull NotificationWear notificationWear,
            @NonNull AutoReplyRule matchedRule,
            @Nullable String claimToken
    ) {
        PreferencesManager prefs = PreferencesManager.getPreferencesInstance(this);
        String incomingMessage = extractIncomingMessage(sbn);
        String fallback = (matchedRule.getReplyMessage() != null && !matchedRule.getReplyMessage().trim().isEmpty())
                ? matchedRule.getReplyMessage().trim()
                : getDefaultFallbackReply(prefs);
        String fallbackSourceLabel = resolveFallbackSourceLabel(matchedRule, null);

        String apiKey = prefs.getOpenAIApiKey();
        boolean hasApiKey = apiKey != null && !apiKey.trim().isEmpty() && !"PENDING_CONFIGURATION".equals(apiKey);
        if (!hasApiKey || incomingMessage == null || incomingMessage.trim().isEmpty()) {
            sendActualReply(
                    sbn,
                    notificationWear,
                    fallback,
                    fallbackSourceLabel,
                    ReplySourceType.AI_FALLBACK,
                    matchedRule,
                    claimToken
            );
            return;
        }

        String ruleGuidance = (matchedRule.getReplyMessage() != null && !matchedRule.getReplyMessage().trim().isEmpty())
                ? matchedRule.getReplyMessage().trim()
                : null;
        String historySource = matchedRule.getName() + " • " + getString(R.string.history_source_ai);

        prepareAndFetchAiReply(
                sbn,
                notificationWear,
                incomingMessage,
                fallback,
                ruleGuidance,
                historySource,
                false, // Multi-Rule already evaluated its own cooldown/maxReplies/group filters
                matchedRule,
                claimToken
        );
    }

    private String getDefaultFallbackReply(PreferencesManager preferencesManager) {
        CustomRepliesData customRepliesData = CustomRepliesData.getInstance(this);
        String replyText = customRepliesData.getTextToSendOrElse();
        if (preferencesManager.isByokRepliesEnabled()) {
            try {
                replyText = getString(R.string.auto_reply_default_message);
            } catch (Resources.NotFoundException e) {
                replyText = "I am currently busy. Will reply later.";
            }
        }
        return replyText;
    }

    private void sendReply(StatusBarNotification sbn) {
        sendReply(sbn, null);
    }

    private void sendReply(StatusBarNotification sbn, @Nullable String preClaimedToken) {
        String claimToken = preClaimedToken;
        String eventKey = buildClaimEventKey(sbn);
        if (claimToken == null && eventKey != null) {
            claimToken = PendingClaimHandoff.consume(eventKey);
        }

        final NotificationWear notificationWear = NotificationUtils.extractWearNotification(sbn);
        if (notificationWear.getRemoteInputs().isEmpty()) {
            releaseUnusedClaimSafely(eventKey, claimToken);
            return;
        }

        PreferencesManager preferencesManager = PreferencesManager.getPreferencesInstance(this);
        String fallbackReplyText = getDefaultFallbackReply(preferencesManager);
        String incomingMessage = extractIncomingMessage(sbn);

        boolean shouldUseAI = false;
        if (preferencesManager.isByokRepliesEnabled()) {
            String apiKey = preferencesManager.getOpenAIApiKey();
            shouldUseAI = apiKey != null && !apiKey.trim().isEmpty() &&
                    !apiKey.equals("PENDING_CONFIGURATION") &&
                    incomingMessage != null && !incomingMessage.trim().isEmpty();
            Log.d(TAG, "BYOK mode - API key configured: " + (apiKey != null && !apiKey.trim().isEmpty())
                    + ", incomingMessage present: " + (incomingMessage != null && !incomingMessage.trim().isEmpty()));
        }

        if (shouldUseAI) {
            if (claimToken == null && eventKey != null) {
                String senderName = sbn != null ? NotificationUtils.getTitle(sbn) : null;
                String packageName = (sbn != null && sbn.getPackageName() != null) ? sbn.getPackageName() : "";
                claimToken = UUID.randomUUID().toString();
                try {
                    ClaimAcquireOutcome outcome = getDbUtils().tryAcquireNotificationClaim(
                            eventKey,
                            claimToken,
                            packageName,
                            NotificationEventKeyBuilder.normalizeSenderKey(senderName),
                            sbn != null ? sbn.getKey() : null,
                            System.currentTimeMillis(),
                            false
                    );
                    if (!outcome.getAcquired()) {
                        Log.i(TAG, "Suppressed async AI request by persistent claim: decision=" + outcome.getDecision());
                        return;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed acquiring persistent claim for async AI (" + e.getClass().getSimpleName() + ")");
                    return;
                }
            }
            Log.d(TAG, "AI conditions met. Checking AI policy & memory before dispatch.");
            prepareAndFetchAiReply(
                    sbn,
                    notificationWear,
                    incomingMessage,
                    fallbackReplyText,
                    null,
                    getString(R.string.history_source_ai),
                    true,
                    null,
                    claimToken
            );
        } else {
            Log.d(TAG, "AI conditions not met. Using default reply.");
            sendActualReply(sbn, notificationWear, fallbackReplyText, null, ReplySourceType.DEFAULT, null, claimToken);
        }
    }

    private void prepareAndFetchAiReply(
            StatusBarNotification sbn,
            NotificationWear notificationWear,
            String incomingMessage,
            String fallbackReplyText,
            String ruleSpecificGuidance,
            String historySourceLabel,
            boolean enforceGlobalAiPolicy,
            @Nullable AutoReplyRule matchedRule,
            @Nullable String claimToken
    ) {
        final String eventKey = NotificationEventKeyBuilder.buildEventKey(sbn, incomingMessage);
        if (serviceDestroyed.get()) {
            cancelPendingClaimSafely(eventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
            return;
        }
        ExecutorService memoryExec = getAiMemoryExecutor();
        if (memoryExec == null) {
            cancelPendingClaimSafely(eventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
            return;
        }
        trackAsyncClaim(claimToken, eventKey);
        try {
            memoryExec.execute(() -> {
                if (serviceDestroyed.get()) {
                    cancelPendingClaimSafely(eventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
                    return;
                }
                try {
                    PreferencesManager prefs = PreferencesManager.getPreferencesInstance(this);
                    String packageName = sbn.getPackageName() != null ? sbn.getPackageName() : "";
                    String senderDisplay = NotificationUtils.getTitle(sbn);
                    if (senderDisplay == null || senderDisplay.trim().isEmpty()) {
                        senderDisplay = "Unknown";
                    } else {
                        senderDisplay = senderDisplay.trim();
                    }
                    String senderKey = AiPromptAndPolicyHelper.normalizeSenderKey(senderDisplay);
                    boolean isGroup = sbn.getNotification() != null &&
                            sbn.getNotification().extras != null &&
                            sbn.getNotification().extras.getBoolean("android.isGroupConversation", false);

                    AiMemoryDao memoryDao = AiMemoryDatabase.getInstance(getApplicationContext()).aiMemoryDao();
                    long now = System.currentTimeMillis();

                    if (enforceGlobalAiPolicy) {
                        int cooldownSec = prefs.getAiCooldownSeconds();
                        int maxReplies = prefs.getAiMaxRepliesPerSender();
                        int windowMins = prefs.getAiFrequencyWindowMinutes();
                        String groupFilter = prefs.getAiGroupFilterMode();

                        Long lastReplyTime = memoryDao.getLastAiReplyTimeSync(packageName, senderKey);
                        long sinceTime = AiPromptAndPolicyHelper.computeWindowSinceMillis(windowMins, now);
                        int recentCount = (maxReplies > 0)
                                ? memoryDao.getAiReplyCountSinceSync(packageName, senderKey, sinceTime)
                                : 0;

                        AiPolicyDecision decision = AiPromptAndPolicyHelper.shouldAllowAiReply(
                                cooldownSec,
                                maxReplies,
                                groupFilter,
                                isGroup,
                                lastReplyTime,
                                recentCount,
                                now
                        );

                        if (decision != AiPolicyDecision.ALLOWED) {
                            Log.d(TAG, "AI reply blocked by global AI policy: " + decision);
                            if (!serviceDestroyed.get()
                                    && decision != AiPolicyDecision.BLOCKED_GROUP_FILTER
                                    && !prefs.isAiSkipWhenLimitReached()) {
                                untrackAsyncClaim(claimToken);
                                sendActualReply(
                                        sbn,
                                        notificationWear,
                                        fallbackReplyText,
                                        resolveFallbackSourceLabel(matchedRule, historySourceLabel),
                                        ReplySourceType.AI_FALLBACK,
                                        matchedRule,
                                        claimToken
                                );
                            } else if (serviceDestroyed.get()) {
                                cancelPendingClaimSafely(eventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
                            } else {
                                releaseUnusedClaimSafely(eventKey, claimToken);
                            }
                            return;
                        }
                    }

                    if (serviceDestroyed.get()) {
                        cancelPendingClaimSafely(eventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
                        return;
                    }

                    boolean memoryEnabled = prefs.isAiMemoryEnabled();
                    boolean notesEnabled = prefs.isAiAutoNotesEnabled();

                    List<AiConversationMessage> historyAsc = new ArrayList<>();
                    if (memoryEnabled) {
                        int maxTurns = prefs.getAiMemoryMaxTurns();
                        List<AiConversationMessage> desc = memoryDao.getRecentMessagesDescSync(packageName, senderKey, maxTurns);
                        historyAsc.addAll(desc);
                        Collections.reverse(historyAsc);
                    }

                    List<AiNote> pendingNotes = notesEnabled
                            ? memoryDao.getPendingNotesForSenderSync(packageName, senderDisplay, 5)
                            : Collections.emptyList();

                    if (serviceDestroyed.get()) {
                        cancelPendingClaimSafely(eventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
                        return;
                    }

                    fetchAiReply(
                            sbn,
                            notificationWear,
                            packageName,
                            senderKey,
                            senderDisplay,
                            incomingMessage,
                            historyAsc,
                            pendingNotes,
                            memoryEnabled,
                            notesEnabled,
                            ruleSpecificGuidance,
                            historySourceLabel,
                            fallbackReplyText,
                            matchedRule,
                            claimToken
                    );
                } catch (Exception e) {
                    Log.e(TAG, "Unexpected error preparing AI reply (" + e.getClass().getSimpleName() + ")");
                    if (serviceDestroyed.get()) {
                        cancelPendingClaimSafely(eventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
                    } else {
                        cancelPendingClaimSafely(eventKey, claimToken, ReplyExecutionManager.FAILURE_CLAIM_PERSISTENCE_ERROR);
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed scheduling AI reply preparation (" + e.getClass().getSimpleName() + ")");
            cancelPendingClaimSafely(eventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
        }
    }

    private String getDefaultModelForProvider(String provider) {
        if ("Gemini".equalsIgnoreCase(provider)) return "gemini-2.5-flash";
        if ("Claude".equalsIgnoreCase(provider)) return "claude-sonnet-4-20250514";
        if ("Grok".equalsIgnoreCase(provider)) return "grok-3-mini";
        if ("DeepSeek".equalsIgnoreCase(provider)) return "deepseek-chat";
        if ("Mistral".equalsIgnoreCase(provider)) return "mistral-small-latest";
        return DEFAULT_LLM_MODEL;
    }

    private static final java.util.regex.Pattern SAFE_HTTP_ERROR_PATTERN =
            java.util.regex.Pattern.compile(
                    "^HTTP [1-5][0-9]{2} \\((Bad request|Authentication failed|Access denied|Endpoint or model not found|Request timeout|Rate limit or quota exceeded|Provider server error|Provider request failed)\\)$"
            );
    private static final java.util.regex.Pattern SAFE_NETWORK_ERROR_PATTERN =
            java.util.regex.Pattern.compile("^Network error \\([A-Za-z0-9_$]{1,64}\\)$");
    private static final java.util.regex.Pattern SAFE_STORAGE_EXCEPTION_PATTERN =
            java.util.regex.Pattern.compile("^Secure encrypted credential storage is unavailable \\([A-Za-z0-9_$]{1,64}\\)\\.$");

    static String sanitizeErrorMessageForPersistence(String errorDetail) {
        if (errorDetail == null || errorDetail.trim().isEmpty()) {
            return "AI request failed";
        }
        String candidate = errorDetail.trim();
        if ("API key unavailable".equals(candidate)
                || "Empty AI response".equals(candidate)
                || "Empty Claude response".equals(candidate)
                || "Empty Gemini response".equals(candidate)
                || "Provider request failed".equals(candidate)
                || "AI request failed".equals(candidate)
                || "Secure encrypted credential storage is unavailable on this device.".equals(candidate)
                || "Failed to migrate legacy API key to secure storage.".equals(candidate)
                || "Failed to verify migrated API key in secure storage.".equals(candidate)
                || "Failed to commit API key to secure storage.".equals(candidate)
                || "Failed to verify API key in secure storage.".equals(candidate)
                || "Failed to write API key to secure storage.".equals(candidate)
                || "Failed to read API key from secure storage.".equals(candidate)
                || "Failed to delete API key from secure storage.".equals(candidate)) {
            return candidate;
        }
        if (SAFE_HTTP_ERROR_PATTERN.matcher(candidate).matches()
                || SAFE_NETWORK_ERROR_PATTERN.matcher(candidate).matches()
                || SAFE_STORAGE_EXCEPTION_PATTERN.matcher(candidate).matches()) {
            return candidate;
        }
        return "Provider request failed";
    }

    private void recordAiError(PreferencesManager prefs, String errorDetail) {
        prefs.saveOpenAILastPersistentError(sanitizeErrorMessageForPersistence(errorDetail), System.currentTimeMillis());
    }

    static String formatSafeHttpError(Response<?> response) {
        if (response == null) {
            return "Provider request failed";
        }
        return formatSafeHttpError(response.code());
    }

    static String formatSafeHttpError(int code) {
        if (code < 100 || code > 599) {
            return "Provider request failed";
        } else if (code == 400) {
            return "HTTP 400 (Bad request)";
        } else if (code == 401) {
            return "HTTP 401 (Authentication failed)";
        } else if (code == 403) {
            return "HTTP 403 (Access denied)";
        } else if (code == 404) {
            return "HTTP 404 (Endpoint or model not found)";
        } else if (code == 408) {
            return "HTTP 408 (Request timeout)";
        } else if (code == 429) {
            return "HTTP 429 (Rate limit or quota exceeded)";
        } else if (code >= 500) {
            return "HTTP " + code + " (Provider server error)";
        }
        return "HTTP " + code + " (Provider request failed)";
    }

    private void fetchAiReply(
            StatusBarNotification sbn,
            NotificationWear notificationWear,
            String packageName,
            String senderKey,
            String senderDisplay,
            String incomingMessage,
            List<AiConversationMessage> historyAsc,
            List<AiNote> pendingNotes,
            boolean memoryEnabled,
            boolean notesEnabled,
            String ruleSpecificGuidance,
            String historySourceLabel,
            String fallbackReplyText,
            @Nullable AutoReplyRule matchedRule,
            @Nullable String claimToken
    ) {
        final String claimEventKey = NotificationEventKeyBuilder.buildEventKey(sbn, incomingMessage);
        if (serviceDestroyed.get()) {
            cancelPendingClaimSafely(claimEventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
            return;
        }
        PreferencesManager prefs = PreferencesManager.getPreferencesInstance(this);

        if (ruleSpecificGuidance == null && matchedRule == null && !prefs.isByokRepliesEnabled()) {
            untrackAsyncClaim(claimToken);
            sendActualReply(
                    sbn,
                    notificationWear,
                    fallbackReplyText,
                    resolveFallbackSourceLabel(matchedRule, historySourceLabel),
                    ReplySourceType.AI_FALLBACK,
                    matchedRule,
                    claimToken
            );
            return;
        }

        Log.d(TAG, "Using BYOK backend with memoryTurns=" + historyAsc.size() + ", notesEnabled=" + notesEnabled);
        String apiKey = prefs.getOpenAIApiKey();
        if (apiKey != null) apiKey = apiKey.trim();
        if (!prefs.isSecureStorageAvailable() || apiKey == null || apiKey.isEmpty() || "PENDING_CONFIGURATION".equals(apiKey)) {
            Log.w(TAG, "Aborting AI request: API key unavailable or secure storage inaccessible.");
            recordAiError(prefs, prefs.getSecureStorageError() != null
                    ? prefs.getSecureStorageError()
                    : "API key unavailable");
            untrackAsyncClaim(claimToken);
            sendActualReply(
                    sbn,
                    notificationWear,
                    fallbackReplyText,
                    resolveFallbackSourceLabel(matchedRule, historySourceLabel),
                    ReplySourceType.AI_FALLBACK,
                    matchedRule,
                    claimToken
            );
            return;
        }
        String provider = prefs.getOpenApiSource();
        if (provider == null || provider.trim().isEmpty()) {
            provider = "OpenAI";
        } else {
            provider = provider.trim();
        }

        String model = prefs.getSelectedOpenAIModel();
        String basePrompt = prefs.getOpenAICustomPrompt();
        if (basePrompt == null || basePrompt.trim().isEmpty()) basePrompt = DEFAULT_LLM_PROMPT;
        if (model == null || model.trim().isEmpty()) {
            model = getDefaultModelForProvider(provider);
        }
        model = model.trim();

        String systemPrompt = AiPromptAndPolicyHelper.buildAugmentedSystemPrompt(
                basePrompt,
                notesEnabled,
                memoryEnabled,
                senderDisplay,
                pendingNotes,
                ruleSpecificGuidance,
                System.currentTimeMillis()
        );

        String baseUrl = null;
        for (Map.Entry<String, String> entry : Constants.INSTANCE.getPROVIDER_URLS().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(provider)) {
                provider = entry.getKey();
                baseUrl = entry.getValue();
                break;
            }
        }
        if (CUSTOM_AI_PROVIDER_NAME.equalsIgnoreCase(provider)) {
            baseUrl = prefs.getCustomOpenAIApiUrl();
        }
        if (baseUrl == null || baseUrl.trim().isEmpty()) baseUrl = "https://api.openai.com/";
        baseUrl = baseUrl.trim();
        if (!baseUrl.endsWith("/")) baseUrl += "/";

        OpenAIService service;
        try {
            service = RetrofitInstance.getOpenAIRetrofitInstance(baseUrl).create(OpenAIService.class);
        } catch (Exception e) {
            Log.e(TAG, "Invalid AI provider configuration (" + e.getClass().getSimpleName() + ")");
            recordAiError(prefs, "Provider request failed");
            untrackAsyncClaim(claimToken);
            sendActualReply(
                    sbn,
                    notificationWear,
                    fallbackReplyText,
                    resolveFallbackSourceLabel(matchedRule, historySourceLabel),
                    ReplySourceType.AI_FALLBACK,
                    matchedRule,
                    claimToken
            );
            return;
        }
        int maxTurnsToKeep = Math.max(20, prefs.getAiMemoryMaxTurns() * 2);

        if (memoryEnabled && incomingMessage != null && !incomingMessage.trim().isEmpty()) {
            final String incomingEventKey = buildIncomingEventKey(sbn, packageName, senderKey, incomingMessage);
            try {
                getAiMemoryRepository().recordIncomingUserMessage(
                        packageName,
                        senderKey,
                        senderDisplay,
                        incomingMessage,
                        incomingEventKey,
                        maxTurnsToKeep,
                        System.currentTimeMillis()
                );
            } catch (Exception e) {
                Log.e(TAG, "Failed persisting incoming user message (" + e.getClass().getSimpleName() + ")");
            }
        }

        try {
            if ("Claude".equalsIgnoreCase(provider)) {
                fetchClaudeReplyInternal(
                        prefs, service, baseUrl, apiKey, model, systemPrompt,
                        packageName, senderKey, senderDisplay, incomingMessage, historyAsc,
                        memoryEnabled, notesEnabled, maxTurnsToKeep, historySourceLabel,
                        sbn, notificationWear, fallbackReplyText, matchedRule, claimToken
                );
            } else if ("Gemini".equalsIgnoreCase(provider)) {
                fetchGeminiReplyInternal(
                        prefs, service, baseUrl, apiKey, model, systemPrompt,
                        packageName, senderKey, senderDisplay, incomingMessage, historyAsc,
                        memoryEnabled, notesEnabled, maxTurnsToKeep, historySourceLabel,
                        sbn, notificationWear, fallbackReplyText, matchedRule, claimToken
                );
            } else {
                fetchOpenAiCompatibleReplyInternal(
                        prefs, service, baseUrl, apiKey, model, systemPrompt,
                        packageName, senderKey, senderDisplay, incomingMessage, historyAsc,
                        memoryEnabled, notesEnabled, maxTurnsToKeep, historySourceLabel,
                        sbn, notificationWear, fallbackReplyText, matchedRule, claimToken
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed dispatching AI provider request (" + e.getClass().getSimpleName() + ")");
            recordAiError(prefs, "Provider request failed");
            untrackAsyncClaim(claimToken);
            if (serviceDestroyed.get()) {
                cancelPendingClaimSafely(claimEventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
            } else {
                sendActualReply(
                        sbn,
                        notificationWear,
                        fallbackReplyText,
                        resolveFallbackSourceLabel(matchedRule, historySourceLabel),
                        ReplySourceType.AI_FALLBACK,
                        matchedRule,
                        claimToken
                );
            }
        }
    }

    private String buildIncomingEventKey(
            StatusBarNotification sbn,
            String packageName,
            String senderKey,
            String incomingMessage
    ) {
        if (incomingMessage == null || incomingMessage.trim().isEmpty()) {
            return null;
        }
        String sbnKey = sbn != null ? sbn.getKey() : null;
        long postTime = sbn != null ? sbn.getPostTime() : 0L;
        long whenMs = (sbn != null && sbn.getNotification() != null) ? sbn.getNotification().when : 0L;
        return AiPromptAndPolicyHelper.buildIncomingEventKey(
                sbnKey,
                postTime,
                whenMs,
                packageName != null ? packageName : "",
                senderKey != null ? senderKey : AiPromptAndPolicyHelper.UNKNOWN_SENDER,
                incomingMessage
        );
    }

    private void persistIncomingUserMessageAsync(
            StatusBarNotification sbn,
            String packageName,
            String senderKey,
            String senderDisplay,
            String incomingMessage,
            boolean memoryEnabled,
            int maxTurnsToKeep
    ) {
        if (!memoryEnabled || serviceDestroyed.get() || incomingMessage == null || incomingMessage.trim().isEmpty()) {
            return;
        }
        ExecutorService memoryExec = getAiMemoryExecutor();
        if (memoryExec == null) {
            return;
        }
        final String incomingEventKey = buildIncomingEventKey(sbn, packageName, senderKey, incomingMessage);
        memoryExec.execute(() -> {
            if (serviceDestroyed.get()) {
                return;
            }
            try {
                getAiMemoryRepository().recordIncomingUserMessage(
                        packageName,
                        senderKey,
                        senderDisplay,
                        incomingMessage,
                        incomingEventKey,
                        maxTurnsToKeep,
                        System.currentTimeMillis()
                );
            } catch (Exception e) {
                Log.e(TAG, "Failed persisting incoming user message (" + e.getClass().getSimpleName() + ")");
            }
        });
    }

    private void handleSuccessfulAiReply(
            PreferencesManager prefs,
            StatusBarNotification sbn,
            NotificationWear notificationWear,
            String packageName,
            String senderKey,
            String senderDisplay,
            String incomingMessage,
            String rawAiReply,
            boolean memoryEnabled,
            boolean notesEnabled,
            int maxTurnsToKeep,
            String historySourceLabel
    ) {
        handleSuccessfulAiReplyWithResult(
                prefs,
                sbn,
                notificationWear,
                packageName,
                senderKey,
                senderDisplay,
                incomingMessage,
                rawAiReply,
                memoryEnabled,
                notesEnabled,
                maxTurnsToKeep,
                historySourceLabel,
                null,
                null
        );
    }

    @VisibleForTesting
    ReplyExecutionResult handleSuccessfulAiReplyWithResult(
            PreferencesManager prefs,
            StatusBarNotification sbn,
            NotificationWear notificationWear,
            String packageName,
            String senderKey,
            String senderDisplay,
            String incomingMessage,
            String rawAiReply,
            boolean memoryEnabled,
            boolean notesEnabled,
            int maxTurnsToKeep,
            String historySourceLabel
    ) {
        return handleSuccessfulAiReplyWithResult(
                prefs,
                sbn,
                notificationWear,
                packageName,
                senderKey,
                senderDisplay,
                incomingMessage,
                rawAiReply,
                memoryEnabled,
                notesEnabled,
                maxTurnsToKeep,
                historySourceLabel,
                null,
                null
        );
    }

    private ReplyExecutionResult handleSuccessfulAiReplyWithResult(
            PreferencesManager prefs,
            StatusBarNotification sbn,
            NotificationWear notificationWear,
            String packageName,
            String senderKey,
            String senderDisplay,
            String incomingMessage,
            String rawAiReply,
            boolean memoryEnabled,
            boolean notesEnabled,
            int maxTurnsToKeep,
            String historySourceLabel,
            @Nullable AutoReplyRule matchedRule,
            @Nullable String claimToken
    ) {
        final String claimEventKey = NotificationEventKeyBuilder.buildEventKey(sbn, incomingMessage);
        if (serviceDestroyed.get()) {
            Log.d(TAG, "Ignoring late AI success after NotificationService destruction.");
            cancelPendingClaimSafely(claimEventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
            return new ReplyExecutionResult(
                    claimToken != null ? claimToken : "",
                    ReplyExecutionStatus.CANCELLED,
                    false,
                    false,
                    ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN,
                    "Ignored after NotificationService destruction",
                    System.currentTimeMillis()
            );
        }

        untrackAsyncClaim(claimToken);
        ParsedAiResponse parsed = notesEnabled
                ? AiPromptAndPolicyHelper.parseAndStripNotes(rawAiReply)
                : new ParsedAiResponse(rawAiReply != null ? rawAiReply.trim() : "", Collections.emptyList());

        final String finalReply = parsed.getCleanReply();
        final List<String> extractedNotes = parsed.getExtractedNotes();

        String globalAiLabel = getString(R.string.history_source_ai);
        ReplySourceType aiSourceType = (matchedRule != null || (historySourceLabel != null && !historySourceLabel.equals(globalAiLabel)))
                ? ReplySourceType.RULE_AI
                : ReplySourceType.GLOBAL_AI;
        final ReplyExecutionResult dispatchResult = sendActualReply(
                sbn,
                notificationWear,
                finalReply,
                historySourceLabel,
                aiSourceType,
                matchedRule,
                claimToken
        );
        if (serviceDestroyed.get()) {
            return dispatchResult;
        }
        if (dispatchResult.isDispatched()) {
            prefs.clearOpenAILastPersistentError();
        }

        ExecutorService memoryExec = getAiMemoryExecutor();
        if (memoryExec == null) {
            return dispatchResult;
        }
        final String incomingEventKey = buildIncomingEventKey(sbn, packageName, senderKey, incomingMessage);
        memoryExec.execute(() -> {
            if (serviceDestroyed.get()) {
                return;
            }
            try {
                long eventTimestampMs = dispatchResult.getTimestampMs() > 0L
                        ? dispatchResult.getTimestampMs()
                        : System.currentTimeMillis();
                boolean persisted = getAiMemoryRepository().persistAuthoritativeAiReplyOutcome(
                        dispatchResult,
                        packageName,
                        senderKey,
                        senderDisplay,
                        incomingMessage,
                        incomingEventKey,
                        finalReply,
                        extractedNotes,
                        memoryEnabled,
                        notesEnabled,
                        maxTurnsToKeep,
                        eventTimestampMs
                );
                if (persisted && notesEnabled && !extractedNotes.isEmpty()) {
                    Log.i(TAG, "Saved AI note(s) for confirmed reply attempt.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed persisting AI memory/notes (" + e.getClass().getSimpleName() + ")");
            }
        });
        return dispatchResult;
    }

    private void fetchClaudeReply(
            PreferencesManager prefs, OpenAIService service, String baseUrl, String apiKey,
            String model, String systemPrompt,
            String packageName, String senderKey, String senderDisplay,
            String incomingMessage, List<AiConversationMessage> historyAsc,
            boolean memoryEnabled, boolean notesEnabled, int maxTurnsToKeep,
            String historySourceLabel,
            StatusBarNotification sbn, NotificationWear notificationWear, String fallbackReplyText
    ) {
        fetchClaudeReplyInternal(
                prefs, service, baseUrl, apiKey, model, systemPrompt,
                packageName, senderKey, senderDisplay, incomingMessage, historyAsc,
                memoryEnabled, notesEnabled, maxTurnsToKeep, historySourceLabel,
                sbn, notificationWear, fallbackReplyText, null, null
        );
    }

    private void fetchClaudeReplyInternal(
            PreferencesManager prefs, OpenAIService service, String baseUrl, String apiKey,
            String model, String systemPrompt,
            String packageName, String senderKey, String senderDisplay,
            String incomingMessage, List<AiConversationMessage> historyAsc,
            boolean memoryEnabled, boolean notesEnabled, int maxTurnsToKeep,
            String historySourceLabel,
            StatusBarNotification sbn, NotificationWear notificationWear, String fallbackReplyText,
            @Nullable AutoReplyRule matchedRule, @Nullable String claimToken
    ) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("max_tokens", 1024);
        requestBody.addProperty("system", systemPrompt);

        JsonArray messages = AiPromptAndPolicyHelper.buildClaudeMessagesJson(historyAsc, incomingMessage);
        requestBody.add("messages", messages);

        Map<String, String> headers = new HashMap<>();
        headers.put("x-api-key", apiKey);
        headers.put("anthropic-version", "2023-06-01");
        headers.put("content-type", "application/json");

        String url;
        if (baseUrl.endsWith("/messages/")) {
            url = baseUrl.substring(0, baseUrl.length() - 1);
        } else {
            url = baseUrl.matches(".*/v\\d+[a-z0-9]*/$") ? baseUrl + "messages" : baseUrl + "v1/messages";
        }

        final String claimEventKey = NotificationEventKeyBuilder.buildEventKey(sbn, incomingMessage);
        if (serviceDestroyed.get()) {
            cancelPendingClaimSafely(claimEventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
            return;
        }
        trackAsyncClaim(claimToken, claimEventKey);
        final AtomicBoolean callbackHandled = new AtomicBoolean(false);
        service.getClaudeCompletion(url, headers, requestBody).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (serviceDestroyed.get()) {
                    Log.d(TAG, "Ignoring late Claude response after NotificationService destruction.");
                    cancelPendingClaimSafely(claimEventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
                    return;
                }
                if (!callbackHandled.compareAndSet(false, true)) {
                    Log.d(TAG, "Ignoring duplicate Claude response callback.");
                    return;
                }
                untrackAsyncClaim(claimToken);
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        JsonArray content = response.body().getAsJsonArray("content");
                        if (content != null && content.size() > 0) {
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < content.size(); i++) {
                                JsonObject block = content.get(i).getAsJsonObject();
                                if (block.has("text") && !block.get("text").isJsonNull()) {
                                    if (sb.length() > 0) sb.append("\n");
                                    sb.append(block.get("text").getAsString());
                                }
                            }
                            String reply = sb.toString().trim();
                            if (!reply.isEmpty()) {
                                handleSuccessfulAiReplyWithResult(
                                        prefs, sbn, notificationWear, packageName, senderKey, senderDisplay,
                                        incomingMessage, reply, memoryEnabled, notesEnabled, maxTurnsToKeep,
                                        historySourceLabel, matchedRule, claimToken
                                );
                                return;
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing Claude response (" + e.getClass().getSimpleName() + ")");
                    }
                }
                if (serviceDestroyed.get()) {
                    cancelPendingClaimSafely(claimEventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
                    return;
                }
                persistIncomingUserMessageAsync(sbn, packageName, senderKey, senderDisplay, incomingMessage, memoryEnabled, maxTurnsToKeep);
                String err = response.isSuccessful() ? "Empty Claude response" : formatSafeHttpError(response);
                Log.e(TAG, "Claude API failed: " + err);
                recordAiError(prefs, err);
                sendActualReply(
                        sbn,
                        notificationWear,
                        fallbackReplyText,
                        resolveFallbackSourceLabel(matchedRule, historySourceLabel),
                        ReplySourceType.AI_FALLBACK,
                        matchedRule,
                        claimToken
                );
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                if (serviceDestroyed.get()) {
                    Log.d(TAG, "Ignoring late Claude failure after NotificationService destruction.");
                    cancelPendingClaimSafely(claimEventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
                    return;
                }
                if (!callbackHandled.compareAndSet(false, true)) {
                    Log.d(TAG, "Ignoring duplicate Claude failure callback.");
                    return;
                }
                untrackAsyncClaim(claimToken);
                persistIncomingUserMessageAsync(sbn, packageName, senderKey, senderDisplay, incomingMessage, memoryEnabled, maxTurnsToKeep);
                Log.e(TAG, "Claude API network error (" + t.getClass().getSimpleName() + ")");
                recordAiError(prefs, "Network error (" + t.getClass().getSimpleName() + ")");
                sendActualReply(
                        sbn,
                        notificationWear,
                        fallbackReplyText,
                        resolveFallbackSourceLabel(matchedRule, historySourceLabel),
                        ReplySourceType.AI_FALLBACK,
                        matchedRule,
                        claimToken
                );
            }
        });
    }

    private void fetchGeminiReply(
            PreferencesManager prefs, OpenAIService service, String baseUrl, String apiKey,
            String model, String systemPrompt,
            String packageName, String senderKey, String senderDisplay,
            String incomingMessage, List<AiConversationMessage> historyAsc,
            boolean memoryEnabled, boolean notesEnabled, int maxTurnsToKeep,
            String historySourceLabel,
            StatusBarNotification sbn, NotificationWear notificationWear, String fallbackReplyText
    ) {
        fetchGeminiReplyInternal(
                prefs, service, baseUrl, apiKey, model, systemPrompt,
                packageName, senderKey, senderDisplay, incomingMessage, historyAsc,
                memoryEnabled, notesEnabled, maxTurnsToKeep, historySourceLabel,
                sbn, notificationWear, fallbackReplyText, null, null
        );
    }

    private void fetchGeminiReplyInternal(
            PreferencesManager prefs, OpenAIService service, String baseUrl, String apiKey,
            String model, String systemPrompt,
            String packageName, String senderKey, String senderDisplay,
            String incomingMessage, List<AiConversationMessage> historyAsc,
            boolean memoryEnabled, boolean notesEnabled, int maxTurnsToKeep,
            String historySourceLabel,
            StatusBarNotification sbn, NotificationWear notificationWear, String fallbackReplyText,
            @Nullable AutoReplyRule matchedRule, @Nullable String claimToken
    ) {
        String cleanModel = model.startsWith("models/") ? model.substring("models/".length()) : model;
        String url;
        if (baseUrl.endsWith(":generateContent/")) {
            url = baseUrl.substring(0, baseUrl.length() - 1);
        } else {
            url = baseUrl.matches(".*/v1(beta|alpha)?/$")
                    ? baseUrl + "models/" + cleanModel + ":generateContent"
                    : baseUrl + "v1beta/models/" + cleanModel + ":generateContent";
        }

        JsonObject requestBody = new JsonObject();
        JsonArray contents;
        if (url.contains("/v1beta/") || url.contains("/v1alpha/")) {
            JsonObject sysObj = new JsonObject();
            JsonArray sysParts = new JsonArray();
            JsonObject sysText = new JsonObject();
            sysText.addProperty("text", systemPrompt);
            sysParts.add(sysText);
            sysObj.add("parts", sysParts);
            requestBody.add("system_instruction", sysObj);
            contents = AiPromptAndPolicyHelper.buildGeminiContentsJson(historyAsc, incomingMessage);
        } else {
            // Fallback for plain /v1/ endpoints without system_instruction
            contents = AiPromptAndPolicyHelper.buildGeminiContentsJson(historyAsc, incomingMessage);
            if (contents.size() > 0) {
                JsonObject first = contents.get(0).getAsJsonObject();
                JsonArray parts = first.getAsJsonArray("parts");
                if (parts != null && parts.size() > 0) {
                    String orig = parts.get(0).getAsJsonObject().get("text").getAsString();
                    parts.get(0).getAsJsonObject().addProperty("text", systemPrompt + "\n\n" + orig);
                }
            }
        }
        requestBody.add("contents", contents);

        final String claimEventKey = NotificationEventKeyBuilder.buildEventKey(sbn, incomingMessage);
        if (serviceDestroyed.get()) {
            cancelPendingClaimSafely(claimEventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
            return;
        }
        trackAsyncClaim(claimToken, claimEventKey);
        final AtomicBoolean callbackHandled = new AtomicBoolean(false);
        service.getGeminiCompletion(url, apiKey, requestBody).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (serviceDestroyed.get()) {
                    Log.d(TAG, "Ignoring late Gemini response after NotificationService destruction.");
                    cancelPendingClaimSafely(claimEventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
                    return;
                }
                if (!callbackHandled.compareAndSet(false, true)) {
                    Log.d(TAG, "Ignoring duplicate Gemini response callback.");
                    return;
                }
                untrackAsyncClaim(claimToken);
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        JsonArray candidates = response.body().getAsJsonArray("candidates");
                        if (candidates != null && candidates.size() > 0) {
                            JsonObject candidate = candidates.get(0).getAsJsonObject();
                            JsonObject content = candidate.getAsJsonObject("content");
                            JsonArray respParts = content != null ? content.getAsJsonArray("parts") : null;
                            if (respParts != null && respParts.size() > 0) {
                                StringBuilder replyBuilder = new StringBuilder();
                                String fallbackPartText = null;
                                for (int i = 0; i < respParts.size(); i++) {
                                    JsonObject p = respParts.get(i).getAsJsonObject();
                                    boolean isThought = p.has("thought") && !p.get("thought").isJsonNull() && p.get("thought").getAsBoolean();
                                    if (p.has("text") && !p.get("text").isJsonNull()) {
                                        String txt = p.get("text").getAsString();
                                        fallbackPartText = txt;
                                        if (!isThought) {
                                            replyBuilder.append(txt);
                                        }
                                    }
                                }
                                String reply = replyBuilder.toString().trim();
                                if (reply.isEmpty() && fallbackPartText != null) {
                                    reply = fallbackPartText.trim();
                                }
                                if (!reply.isEmpty()) {
                                    handleSuccessfulAiReplyWithResult(
                                            prefs, sbn, notificationWear, packageName, senderKey, senderDisplay,
                                            incomingMessage, reply, memoryEnabled, notesEnabled, maxTurnsToKeep,
                                            historySourceLabel, matchedRule, claimToken
                                    );
                                    return;
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing Gemini response (" + e.getClass().getSimpleName() + ")");
                    }
                }
                if (serviceDestroyed.get()) {
                    cancelPendingClaimSafely(claimEventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
                    return;
                }
                persistIncomingUserMessageAsync(sbn, packageName, senderKey, senderDisplay, incomingMessage, memoryEnabled, maxTurnsToKeep);
                String err = response.isSuccessful() ? "Empty Gemini response" : formatSafeHttpError(response);
                Log.e(TAG, "Gemini API failed: " + err);
                recordAiError(prefs, err);
                sendActualReply(
                        sbn,
                        notificationWear,
                        fallbackReplyText,
                        resolveFallbackSourceLabel(matchedRule, historySourceLabel),
                        ReplySourceType.AI_FALLBACK,
                        matchedRule,
                        claimToken
                );
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                if (serviceDestroyed.get()) {
                    Log.d(TAG, "Ignoring late Gemini failure after NotificationService destruction.");
                    cancelPendingClaimSafely(claimEventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
                    return;
                }
                if (!callbackHandled.compareAndSet(false, true)) {
                    Log.d(TAG, "Ignoring duplicate Gemini failure callback.");
                    return;
                }
                untrackAsyncClaim(claimToken);
                persistIncomingUserMessageAsync(sbn, packageName, senderKey, senderDisplay, incomingMessage, memoryEnabled, maxTurnsToKeep);
                Log.e(TAG, "Gemini API network error (" + t.getClass().getSimpleName() + ")");
                recordAiError(prefs, "Network error (" + t.getClass().getSimpleName() + ")");
                sendActualReply(
                        sbn,
                        notificationWear,
                        fallbackReplyText,
                        resolveFallbackSourceLabel(matchedRule, historySourceLabel),
                        ReplySourceType.AI_FALLBACK,
                        matchedRule,
                        claimToken
                );
            }
        });
    }

    private void fetchOpenAiCompatibleReply(
            PreferencesManager prefs, OpenAIService service, String baseUrl, String apiKey,
            String model, String systemPrompt,
            String packageName, String senderKey, String senderDisplay,
            String incomingMessage, List<AiConversationMessage> historyAsc,
            boolean memoryEnabled, boolean notesEnabled, int maxTurnsToKeep,
            String historySourceLabel,
            StatusBarNotification sbn, NotificationWear notificationWear, String fallbackReplyText
    ) {
        fetchOpenAiCompatibleReplyInternal(
                prefs, service, baseUrl, apiKey, model, systemPrompt,
                packageName, senderKey, senderDisplay, incomingMessage, historyAsc,
                memoryEnabled, notesEnabled, maxTurnsToKeep, historySourceLabel,
                sbn, notificationWear, fallbackReplyText, null, null
        );
    }

    private void fetchOpenAiCompatibleReplyInternal(
            PreferencesManager prefs, OpenAIService service, String baseUrl, String apiKey,
            String model, String systemPrompt,
            String packageName, String senderKey, String senderDisplay,
            String incomingMessage, List<AiConversationMessage> historyAsc,
            boolean memoryEnabled, boolean notesEnabled, int maxTurnsToKeep,
            String historySourceLabel,
            StatusBarNotification sbn, NotificationWear notificationWear, String fallbackReplyText,
            @Nullable AutoReplyRule matchedRule, @Nullable String claimToken
    ) {
        List<Message> messages = AiPromptAndPolicyHelper.buildOpenAiMessages(systemPrompt, historyAsc, incomingMessage);

        OpenAIRequest request = new OpenAIRequest(model, messages);
        String bearerToken = "Bearer " + apiKey;
        String completionsUrl;
        if (baseUrl.endsWith("/chat/completions/")) {
            completionsUrl = baseUrl.substring(0, baseUrl.length() - 1);
        } else {
            completionsUrl = (baseUrl.matches(".*/v\\d+[a-z0-9]*/$") || baseUrl.endsWith("/openai/"))
                    ? baseUrl + "chat/completions"
                    : baseUrl + "v1/chat/completions";
        }

        final String claimEventKey = NotificationEventKeyBuilder.buildEventKey(sbn, incomingMessage);
        if (serviceDestroyed.get()) {
            cancelPendingClaimSafely(claimEventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
            return;
        }
        trackAsyncClaim(claimToken, claimEventKey);
        final AtomicBoolean callbackHandled = new AtomicBoolean(false);
        service.getChatCompletion(completionsUrl, bearerToken, request).enqueue(new Callback<OpenAIResponse>() {
            @Override
            public void onResponse(@NonNull Call<OpenAIResponse> call, @NonNull Response<OpenAIResponse> response) {
                if (serviceDestroyed.get()) {
                    Log.d(TAG, "Ignoring late OpenAI/Compatible response after NotificationService destruction.");
                    cancelPendingClaimSafely(claimEventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
                    return;
                }
                if (!callbackHandled.compareAndSet(false, true)) {
                    Log.d(TAG, "Ignoring duplicate OpenAI/Compatible response callback.");
                    return;
                }
                untrackAsyncClaim(claimToken);
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        if (response.body().getChoices() != null && !response.body().getChoices().isEmpty() &&
                                response.body().getChoices().get(0).getMessage() != null &&
                                response.body().getChoices().get(0).getMessage().getContent() != null) {

                            String aiReply = response.body().getChoices().get(0).getMessage().getContent().trim();
                            if (!aiReply.isEmpty()) {
                                Log.i(TAG, "OpenAI/Compatible successful response received.");
                                handleSuccessfulAiReplyWithResult(
                                        prefs, sbn, notificationWear, packageName, senderKey, senderDisplay,
                                        incomingMessage, aiReply, memoryEnabled, notesEnabled, maxTurnsToKeep,
                                        historySourceLabel, matchedRule, claimToken
                                );
                                return;
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing OpenAI/Compatible response (" + e.getClass().getSimpleName() + ")");
                    }
                }
                if (serviceDestroyed.get()) {
                    cancelPendingClaimSafely(claimEventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
                    return;
                }
                persistIncomingUserMessageAsync(sbn, packageName, senderKey, senderDisplay, incomingMessage, memoryEnabled, maxTurnsToKeep);
                String err = response.isSuccessful() ? "Empty AI response" : formatSafeHttpError(response);
                Log.e(TAG, "OpenAI/Compatible API failed: " + err);
                recordAiError(prefs, err);
                sendActualReply(
                        sbn,
                        notificationWear,
                        fallbackReplyText,
                        resolveFallbackSourceLabel(matchedRule, historySourceLabel),
                        ReplySourceType.AI_FALLBACK,
                        matchedRule,
                        claimToken
                );
            }

            @Override
            public void onFailure(@NonNull Call<OpenAIResponse> call, @NonNull Throwable t) {
                if (serviceDestroyed.get()) {
                    Log.d(TAG, "Ignoring late OpenAI/Compatible failure after NotificationService destruction.");
                    cancelPendingClaimSafely(claimEventKey, claimToken, ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
                    return;
                }
                if (!callbackHandled.compareAndSet(false, true)) {
                    Log.d(TAG, "Ignoring duplicate OpenAI/Compatible failure callback.");
                    return;
                }
                untrackAsyncClaim(claimToken);
                persistIncomingUserMessageAsync(sbn, packageName, senderKey, senderDisplay, incomingMessage, memoryEnabled, maxTurnsToKeep);
                Log.e(TAG, "OpenAI/Compatible API network error (" + t.getClass().getSimpleName() + ")");
                recordAiError(prefs, "Network error (" + t.getClass().getSimpleName() + ")");
                sendActualReply(
                        sbn,
                        notificationWear,
                        fallbackReplyText,
                        resolveFallbackSourceLabel(matchedRule, historySourceLabel),
                        ReplySourceType.AI_FALLBACK,
                        matchedRule,
                        claimToken
                );
            }
        });
    }

    private boolean canPurgeMessages() {
        long daysBeforePurgeInMS = 30 * 24 * 60 * 60 * 1000L;
        return (System.currentTimeMillis() - PreferencesManager.getPreferencesInstance(this).getLastPurgedTime()) > daysBeforePurgeInMS;
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.d(TAG, "Listener disconnected! Requesting rebind...");
        ComponentName componentName = new ComponentName(this, NotificationService.class);
        requestRebind(componentName);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        ensureStartupRecovery();
        Toast.makeText(getApplicationContext(), "Listener connected!", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroy() {
        serviceDestroyed.set(true);
        List<Map.Entry<String, String>> pendingSnapshot = new ArrayList<>(pendingAsyncClaims.entrySet());
        pendingAsyncClaims.clear();
        for (Map.Entry<String, String> pending : pendingSnapshot) {
            cancelPendingClaimSafely(pending.getValue(), pending.getKey(), ReplyExecutionManager.FAILURE_SERVICE_SHUTDOWN);
        }
        ReplyExecutionManager managerToShutdown;
        RuleNotificationProcessor processorToShutdown;
        ExecutorService executorToShutdown;
        synchronized (lifecycleLock) {
            managerToShutdown = replyExecutionManager;
            replyExecutionManager = null;
            if (managerToShutdown != null) {
                managerToShutdown.shutdown();
            }
            processorToShutdown = ruleNotificationProcessor;
            ruleNotificationProcessor = null;
            executorToShutdown = aiMemoryExecutor;
            aiMemoryExecutor = null;
        }
        if (processorToShutdown != null) {
            processorToShutdown.shutdown();
        }
        if (executorToShutdown != null) {
            executorToShutdown.shutdownNow();
        }
        super.onDestroy();
    }

}
