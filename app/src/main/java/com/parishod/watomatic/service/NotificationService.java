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
import androidx.annotation.VisibleForTesting;

import com.parishod.watomatic.NotificationWear;
import com.parishod.watomatic.R;
import com.parishod.watomatic.model.CustomRepliesData;
import com.parishod.watomatic.model.ai.AiConversationMessage;
import com.parishod.watomatic.model.ai.AiMemoryDao;
import com.parishod.watomatic.model.ai.AiMemoryDatabase;
import com.parishod.watomatic.model.ai.AiNote;
import com.parishod.watomatic.model.ai.AiReplyLog;
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
import com.parishod.watomatic.service.reply.ReplyAttempt;
import com.parishod.watomatic.service.reply.ReplyExecutionManager;
import com.parishod.watomatic.service.reply.ReplyExecutionResult;
import com.parishod.watomatic.service.reply.ReplySourceType;
import com.parishod.watomatic.service.rules.RuleNotificationProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;


public class NotificationService extends NotificationListenerService {
    private final String TAG = NotificationService.class.getSimpleName();
    private DbUtils dbUtils;
    private NotificationReplyDecider replyDecider;
    private RuleNotificationProcessor ruleNotificationProcessor;
    private ExecutorService aiMemoryExecutor;
    private volatile ReplyExecutionManager replyExecutionManager;

    private synchronized DbUtils getDbUtils() {
        if (dbUtils == null) {
            dbUtils = new DbUtils(getApplicationContext());
        }
        return dbUtils;
    }

    synchronized ReplyExecutionManager getReplyExecutionManager() {
        if (replyExecutionManager == null) {
            replyExecutionManager = new ReplyExecutionManager(getApplicationContext());
        }
        return replyExecutionManager;
    }

    @VisibleForTesting
    synchronized void setReplyExecutionManagerForTesting(ReplyExecutionManager manager) {
        this.replyExecutionManager = manager;
    }

    private ExecutorService getAiMemoryExecutor() {
        if (aiMemoryExecutor == null || aiMemoryExecutor.isShutdown()) {
            aiMemoryExecutor = Executors.newSingleThreadExecutor();
        }
        return aiMemoryExecutor;
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
        if (ruleNotificationProcessor == null) {
            ruleNotificationProcessor = new RuleNotificationProcessor(getApplicationContext());
        }
        return ruleNotificationProcessor;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        if (sbn == null) {
            return;
        }
        getRuleNotificationProcessor().processNotification(sbn, new RuleNotificationProcessor.ReplyDispatchCallback() {
            @Override
            public void onRuleMatchedSendReply(
                    @NonNull StatusBarNotification statusBarNotification,
                    @NonNull NotificationWear notificationWear,
                    @NonNull String replyText,
                    @NonNull AutoReplyRule matchedRule
            ) {
                if (matchedRule.getUseAiReply()) {
                    Log.d(TAG, "Rule matched with AI enabled (" + matchedRule.getName() + "), dispatching AI reply.");
                    dispatchRuleAiReply(statusBarNotification, notificationWear, matchedRule);
                } else {
                    Log.d(TAG, "Rule matched (" + matchedRule.getName() + "), dispatching reply.");
                    sendActualReply(statusBarNotification, notificationWear, replyText, matchedRule.getName(), ReplySourceType.RULE_STATIC);
                }
            }

            @Override
            public void onFallbackToLegacy(@NonNull StatusBarNotification statusBarNotification) {
                NotificationReplyDecider decider = getReplyDecider();
                PreferencesManager prefs = PreferencesManager.getPreferencesInstance(NotificationService.this);
                if (prefs.isByokRepliesEnabled()) {
                    // Check base service, supported app, notification freshness, non-self sender, and contact whitelist/blacklist;
                    // AI-specific group/cooldown/frequency limits are evaluated in sendReply -> prepareAndFetchAiReply.
                    if (decider.isServiceEnabled()
                            && decider.isSupportedPackage(statusBarNotification)
                            && NotificationUtils.isNewNotification(statusBarNotification)
                            && !isSelfMessage(statusBarNotification)
                            && decider.shouldReply(statusBarNotification)) {
                        sendReply(statusBarNotification);
                    }
                } else if (decider.canReply(statusBarNotification) && decider.shouldReply(statusBarNotification)) {
                    sendReply(statusBarNotification);
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
        return sendActualReply(sbn, notificationWear, replyText, null, ReplySourceType.DEFAULT);
    }

    ReplyExecutionResult sendActualReply(StatusBarNotification sbn, NotificationWear notificationWear, String replyText, String ruleOrSourceName) {
        return sendActualReply(sbn, notificationWear, replyText, ruleOrSourceName, ReplySourceType.DEFAULT);
    }

    ReplyExecutionResult sendActualReply(
            StatusBarNotification sbn,
            NotificationWear notificationWear,
            String replyText,
            String ruleOrSourceName,
            ReplySourceType sourceType
    ) {
        String sourceLabel = ruleOrSourceName != null ? ruleOrSourceName : getString(R.string.history_source_default);
        String packageName = (sbn != null && sbn.getPackageName() != null) ? sbn.getPackageName() : "";
        String notificationKey = (sbn != null) ? sbn.getKey() : null;
        ReplySourceType resolvedSourceType = sourceType != null ? sourceType : ReplySourceType.DEFAULT;

        ReplyAttempt attempt = ReplyAttempt.create(
                resolvedSourceType,
                sourceLabel,
                packageName,
                notificationKey,
                replyText
        );

        ReplyExecutionResult result = getReplyExecutionManager().executeReply(attempt, notificationWear);
        if (result.isDispatched()) {
            DbUtils db = getDbUtils();
            String incomingMsg = extractIncomingMessage(sbn);
            db.logReply(sbn, NotificationUtils.getTitle(sbn), incomingMsg, replyText, sourceLabel);

            if (PreferencesManager.getPreferencesInstance(this).isShowNotificationEnabled()) {
                Bundle extras = (sbn != null && sbn.getNotification() != null) ? sbn.getNotification().extras : null;
                String notifTitle = extras != null ? extras.getString("android.title") : NotificationUtils.getTitle(sbn);
                String notifText = extras != null ? extras.getString("android.text") : incomingMsg;
                NotificationHelper.getInstance(getApplicationContext()).sendNotification(notifTitle, notifText, packageName);
            }
            if (notificationKey != null) {
                try {
                    cancelNotification(notificationKey);
                } catch (Exception e) {
                    Log.w(TAG, "Unable to cancel notification (" + e.getClass().getSimpleName() + ")");
                }
            }
            if (canPurgeMessages()) {
                db.purgeMessageLogs();
                PreferencesManager.getPreferencesInstance(this).setPurgeMessageTime(System.currentTimeMillis());
            }
        } else {
            Log.w(TAG, "Reply dispatch not completed: status=" + result.getStatus()
                    + ", category=" + result.getFailureCategory());
        }
        return result;
    }

    private void dispatchRuleAiReply(
            @NonNull StatusBarNotification sbn,
            @NonNull NotificationWear notificationWear,
            @NonNull AutoReplyRule matchedRule
    ) {
        PreferencesManager prefs = PreferencesManager.getPreferencesInstance(this);
        String incomingMessage = extractIncomingMessage(sbn);
        String fallback = (matchedRule.getReplyMessage() != null && !matchedRule.getReplyMessage().trim().isEmpty())
                ? matchedRule.getReplyMessage().trim()
                : getDefaultFallbackReply(prefs);

        String apiKey = prefs.getOpenAIApiKey();
        boolean hasApiKey = apiKey != null && !apiKey.trim().isEmpty() && !"PENDING_CONFIGURATION".equals(apiKey);
        if (!hasApiKey || incomingMessage == null || incomingMessage.trim().isEmpty()) {
            sendActualReply(sbn, notificationWear, fallback, matchedRule.getName(), ReplySourceType.AI_FALLBACK);
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
                false // Multi-Rule already evaluated its own cooldown/maxReplies/group filters
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
        final NotificationWear notificationWear = NotificationUtils.extractWearNotification(sbn);
        if (notificationWear.getRemoteInputs().isEmpty()) {
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
            Log.d(TAG, "AI conditions met. Checking AI policy & memory before dispatch.");
            prepareAndFetchAiReply(
                    sbn,
                    notificationWear,
                    incomingMessage,
                    fallbackReplyText,
                    null,
                    getString(R.string.history_source_ai),
                    true
            );
        } else {
            Log.d(TAG, "AI conditions not met. Using default reply.");
            sendActualReply(sbn, notificationWear, fallbackReplyText, null, ReplySourceType.DEFAULT);
        }
    }

    private void prepareAndFetchAiReply(
            StatusBarNotification sbn,
            NotificationWear notificationWear,
            String incomingMessage,
            String fallbackReplyText,
            String ruleSpecificGuidance,
            String historySourceLabel,
            boolean enforceGlobalAiPolicy
    ) {
        getAiMemoryExecutor().execute(() -> {
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
                    Log.d(TAG, "AI reply blocked by global AI policy: " + decision + " for " + senderDisplay);
                    if (decision != AiPolicyDecision.BLOCKED_GROUP_FILTER && !prefs.isAiSkipWhenLimitReached()) {
                        sendActualReply(sbn, notificationWear, fallbackReplyText, null, ReplySourceType.AI_FALLBACK);
                    }
                    return;
                }
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
                    fallbackReplyText
            );
        });
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
            String fallbackReplyText
    ) {
        PreferencesManager prefs = PreferencesManager.getPreferencesInstance(this);

        if (ruleSpecificGuidance == null && !prefs.isByokRepliesEnabled()) {
            sendActualReply(sbn, notificationWear, fallbackReplyText, null, ReplySourceType.AI_FALLBACK);
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
            sendActualReply(sbn, notificationWear, fallbackReplyText, null, ReplySourceType.AI_FALLBACK);
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

        OpenAIService service = RetrofitInstance.getOpenAIRetrofitInstance(baseUrl).create(OpenAIService.class);
        int maxTurnsToKeep = Math.max(20, prefs.getAiMemoryMaxTurns() * 2);

        if ("Claude".equalsIgnoreCase(provider)) {
            fetchClaudeReply(
                    prefs, service, baseUrl, apiKey, model, systemPrompt,
                    packageName, senderKey, senderDisplay, incomingMessage, historyAsc,
                    memoryEnabled, notesEnabled, maxTurnsToKeep, historySourceLabel,
                    sbn, notificationWear, fallbackReplyText
            );
        } else if ("Gemini".equalsIgnoreCase(provider)) {
            fetchGeminiReply(
                    prefs, service, baseUrl, apiKey, model, systemPrompt,
                    packageName, senderKey, senderDisplay, incomingMessage, historyAsc,
                    memoryEnabled, notesEnabled, maxTurnsToKeep, historySourceLabel,
                    sbn, notificationWear, fallbackReplyText
            );
        } else {
            fetchOpenAiCompatibleReply(
                    prefs, service, baseUrl, apiKey, model, systemPrompt,
                    packageName, senderKey, senderDisplay, incomingMessage, historyAsc,
                    memoryEnabled, notesEnabled, maxTurnsToKeep, historySourceLabel,
                    sbn, notificationWear, fallbackReplyText
            );
        }
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
        prefs.clearOpenAILastPersistentError();

        ParsedAiResponse parsed = notesEnabled
                ? AiPromptAndPolicyHelper.parseAndStripNotes(rawAiReply)
                : new ParsedAiResponse(rawAiReply.trim(), Collections.emptyList());

        String finalReply = parsed.getCleanReply();
        List<String> extractedNotes = parsed.getExtractedNotes();

        String globalAiLabel = getString(R.string.history_source_ai);
        ReplySourceType aiSourceType = (historySourceLabel != null && !historySourceLabel.equals(globalAiLabel))
                ? ReplySourceType.RULE_AI
                : ReplySourceType.GLOBAL_AI;
        sendActualReply(sbn, notificationWear, finalReply, historySourceLabel, aiSourceType);

        getAiMemoryExecutor().execute(() -> {
            try {
                AiMemoryDao dao = AiMemoryDatabase.getInstance(getApplicationContext()).aiMemoryDao();
                long now = System.currentTimeMillis();
                dao.insertReplyLogSync(new AiReplyLog(0L, packageName, senderKey, now));

                if (memoryEnabled) {
                    dao.insertConversationMessageSync(
                            new AiConversationMessage(0L, packageName, senderKey, senderDisplay, "user", incomingMessage, now)
                    );
                    dao.insertConversationMessageSync(
                            new AiConversationMessage(0L, packageName, senderKey, senderDisplay, "assistant", finalReply, now + 1L)
                    );
                    dao.trimConversationMessagesSync(packageName, senderKey, maxTurnsToKeep);
                }

                if (notesEnabled && !extractedNotes.isEmpty()) {
                    for (int i = 0; i < extractedNotes.size(); i++) {
                        String noteText = extractedNotes.get(i);
                        dao.insertNoteSync(
                                new AiNote(0L, senderDisplay, packageName, noteText, incomingMessage, now + i, false)
                        );
                    }
                    Log.i(TAG, "Saved " + extractedNotes.size() + " AI note(s) from " + senderDisplay);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed persisting AI memory/notes (" + e.getClass().getSimpleName() + ")");
            }
        });
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

        service.getClaudeCompletion(url, headers, requestBody).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
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
                                handleSuccessfulAiReply(
                                        prefs, sbn, notificationWear, packageName, senderKey, senderDisplay,
                                        incomingMessage, reply, memoryEnabled, notesEnabled, maxTurnsToKeep, historySourceLabel
                                );
                                return;
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing Claude response (" + e.getClass().getSimpleName() + ")");
                    }
                }
                String err = response.isSuccessful() ? "Empty Claude response" : formatSafeHttpError(response);
                Log.e(TAG, "Claude API failed: " + err);
                recordAiError(prefs, err);
                sendActualReply(sbn, notificationWear, fallbackReplyText, null, ReplySourceType.AI_FALLBACK);
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                Log.e(TAG, "Claude API network error (" + t.getClass().getSimpleName() + ")");
                recordAiError(prefs, "Network error (" + t.getClass().getSimpleName() + ")");
                sendActualReply(sbn, notificationWear, fallbackReplyText, null, ReplySourceType.AI_FALLBACK);
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

        service.getGeminiCompletion(url, apiKey, requestBody).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
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
                                    handleSuccessfulAiReply(
                                            prefs, sbn, notificationWear, packageName, senderKey, senderDisplay,
                                            incomingMessage, reply, memoryEnabled, notesEnabled, maxTurnsToKeep, historySourceLabel
                                    );
                                    return;
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing Gemini response (" + e.getClass().getSimpleName() + ")");
                    }
                }
                String err = response.isSuccessful() ? "Empty Gemini response" : formatSafeHttpError(response);
                Log.e(TAG, "Gemini API failed: " + err);
                recordAiError(prefs, err);
                sendActualReply(sbn, notificationWear, fallbackReplyText, null, ReplySourceType.AI_FALLBACK);
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                Log.e(TAG, "Gemini API network error (" + t.getClass().getSimpleName() + ")");
                recordAiError(prefs, "Network error (" + t.getClass().getSimpleName() + ")");
                sendActualReply(sbn, notificationWear, fallbackReplyText, null, ReplySourceType.AI_FALLBACK);
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

        service.getChatCompletion(completionsUrl, bearerToken, request).enqueue(new Callback<OpenAIResponse>() {
            @Override
            public void onResponse(@NonNull Call<OpenAIResponse> call, @NonNull Response<OpenAIResponse> response) {
                if (response.isSuccessful() && response.body() != null &&
                    response.body().getChoices() != null && !response.body().getChoices().isEmpty() &&
                    response.body().getChoices().get(0).getMessage() != null &&
                    response.body().getChoices().get(0).getMessage().getContent() != null) {

                    String aiReply = response.body().getChoices().get(0).getMessage().getContent().trim();
                    if (!aiReply.isEmpty()) {
                        Log.i(TAG, "OpenAI/Compatible successful response received.");
                        handleSuccessfulAiReply(
                                prefs, sbn, notificationWear, packageName, senderKey, senderDisplay,
                                incomingMessage, aiReply, memoryEnabled, notesEnabled, maxTurnsToKeep, historySourceLabel
                        );
                        return;
                    }
                }
                String err = response.isSuccessful() ? "Empty AI response" : formatSafeHttpError(response);
                Log.e(TAG, "OpenAI/Compatible API failed: " + err);
                recordAiError(prefs, err);
                sendActualReply(sbn, notificationWear, fallbackReplyText, null, ReplySourceType.AI_FALLBACK);
            }

            @Override
            public void onFailure(@NonNull Call<OpenAIResponse> call, @NonNull Throwable t) {
                Log.e(TAG, "OpenAI/Compatible API network error (" + t.getClass().getSimpleName() + ")");
                recordAiError(prefs, "Network error (" + t.getClass().getSimpleName() + ")");
                sendActualReply(sbn, notificationWear, fallbackReplyText, null, ReplySourceType.AI_FALLBACK);
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
        Toast.makeText(getApplicationContext(), "Listener connected!", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (replyExecutionManager != null) {
            replyExecutionManager.shutdown();
            replyExecutionManager = null;
        }
        if (ruleNotificationProcessor != null) {
            ruleNotificationProcessor.shutdown();
            ruleNotificationProcessor = null;
        }
        if (aiMemoryExecutor != null) {
            aiMemoryExecutor.shutdownNow();
            aiMemoryExecutor = null;
        }
    }

}
