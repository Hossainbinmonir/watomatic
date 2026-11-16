package com.parishod.watomatic.service;

import static com.parishod.watomatic.model.utils.Constants.CUSTOM_AI_PROVIDER_NAME;
import static com.parishod.watomatic.model.utils.Constants.DEFAULT_LLM_MODEL;
import static com.parishod.watomatic.model.utils.Constants.DEFAULT_LLM_PROMPT;

import android.app.PendingIntent;
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
import androidx.core.app.RemoteInput;

import com.parishod.watomatic.NotificationWear;
import com.parishod.watomatic.R;
import com.parishod.watomatic.model.CustomRepliesData;
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
import com.parishod.watomatic.service.rules.RuleNotificationProcessor;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;


public class NotificationService extends NotificationListenerService {
    private final String TAG = NotificationService.class.getSimpleName();
    private DbUtils dbUtils;
    private NotificationReplyDecider replyDecider;
    private RuleNotificationProcessor ruleNotificationProcessor;

    private NotificationReplyDecider getReplyDecider() {
        if (replyDecider == null) {
            if (dbUtils == null) {
                dbUtils = new DbUtils(getApplicationContext());
            }
            replyDecider = new NotificationReplyDecider(
                    PreferencesManager.getPreferencesInstance(this),
                    dbUtils,
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
                Log.d(TAG, "Rule matched (" + matchedRule.getName() + "), dispatching reply.");
                sendActualReply(statusBarNotification, notificationWear, replyText, matchedRule.getName());
            }

            @Override
            public void onFallbackToLegacy(@NonNull StatusBarNotification statusBarNotification) {
                NotificationReplyDecider decider = getReplyDecider();
                if (decider.canReply(statusBarNotification) && decider.shouldReply(statusBarNotification)) {
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

    private void sendActualReply(StatusBarNotification sbn, NotificationWear notificationWear, String replyText) {
        sendActualReply(sbn, notificationWear, replyText, null);
    }

    private void sendActualReply(StatusBarNotification sbn, NotificationWear notificationWear, String replyText, String ruleOrSourceName) {
        // customRepliesData = CustomRepliesData.getInstance(this); // Initialize if other methods from it are needed beyond replyText

        RemoteInput finalRemoteIn = null;
        Intent localIntent = new Intent();
        localIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        Bundle localBundle = new Bundle(); // notificationWear.bundle;
        for (RemoteInput remoteIn : notificationWear.getRemoteInputs()) {
            if(remoteIn.getAllowFreeFormInput()) {
                finalRemoteIn = remoteIn;
                localBundle.putCharSequence(finalRemoteIn.getResultKey(), replyText);
                break;
            }
        }

        if(finalRemoteIn == null) return;

        RemoteInput.addResultsToIntent(new RemoteInput[]{ finalRemoteIn }, localIntent, localBundle);
        try {
            if (notificationWear.getPendingIntent() != null) {
                if (dbUtils == null) {
                    dbUtils = new DbUtils(getApplicationContext());
                }
                String incomingMsg = extractIncomingMessage(sbn);
                String sourceLabel = ruleOrSourceName != null ? ruleOrSourceName : getString(R.string.history_source_default);
                dbUtils.logReply(sbn, NotificationUtils.getTitle(sbn), incomingMsg, replyText, sourceLabel);
                
                // Use ReplyService to send the reply in foreground
                /*Intent replyServiceIntent = new Intent(this, ReplyService.class);
                replyServiceIntent.putExtra("pendingIntent", notificationWear.getPendingIntent());
                replyServiceIntent.putExtra("fillInIntent", localIntent);
                
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(replyServiceIntent);
                    } else {
                        startService(replyServiceIntent);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start ReplyService: " + e.getMessage());
                    // Fallback to direct send if service start fails (though likely to fail for FB)
                    notificationWear.getPendingIntent().send(this, 0, localIntent);
                }*/

                notificationWear.getPendingIntent().send(this, 0, localIntent);
                if (PreferencesManager.getPreferencesInstance(this).isShowNotificationEnabled()) {
                    NotificationHelper.getInstance(getApplicationContext()).sendNotification(sbn.getNotification().extras.getString("android.title"), sbn.getNotification().extras.getString("android.text"), sbn.getPackageName());
                }
                cancelNotification(sbn.getKey());
                if (canPurgeMessages()) {
                    dbUtils.purgeMessageLogs();
                    PreferencesManager.getPreferencesInstance(this).setPurgeMessageTime(System.currentTimeMillis());
                }
            }
        } catch (PendingIntent.CanceledException e) {
            Log.e(TAG, "sendActualReply error: " + e.getLocalizedMessage());
        }
    }

    private void sendReply(StatusBarNotification sbn) {
        final NotificationWear notificationWear = NotificationUtils.extractWearNotification(sbn);
        if (notificationWear.getRemoteInputs().isEmpty()) {
            return;
        }

        PreferencesManager preferencesManager = PreferencesManager.getPreferencesInstance(this);
        CustomRepliesData customRepliesData = CustomRepliesData.getInstance(this); // For fallback
        String replyText = customRepliesData.getTextToSendOrElse();
        if (preferencesManager.isByokRepliesEnabled()) {
            try {
                replyText = getString(R.string.auto_reply_default_message);
            } catch (Resources.NotFoundException e) {
                replyText = "I am currently busy. Will reply later.";
            }
        }
        String fallbackReplyText = replyText; // needs to be final to access in inner class hence one more variable

        String incomingMessage = extractIncomingMessage(sbn);

        // Determine if BYOK AI should be used
        boolean shouldUseAI = false;

        if (preferencesManager.isByokRepliesEnabled()) {
            // BYOK: requires API key
            String apiKey = preferencesManager.getOpenAIApiKey();
            shouldUseAI = apiKey != null && !apiKey.trim().isEmpty() &&
                         !apiKey.equals("PENDING_CONFIGURATION") &&
                         incomingMessage != null && !incomingMessage.trim().isEmpty();
            Log.d(TAG, "BYOK mode - API key configured: " + (apiKey != null && !apiKey.trim().isEmpty())
                    + ", incomingMessage present: " + (incomingMessage != null && !incomingMessage.trim().isEmpty()));
        }

        if (shouldUseAI) {
            Log.d(TAG, "AI conditions met. Attempting to get AI reply.");
            fetchAiReply(sbn, notificationWear, incomingMessage, fallbackReplyText);
        } else {
            Log.d(TAG, "AI conditions not met. Using default reply.");
            sendActualReply(sbn, notificationWear, fallbackReplyText);
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

    private void recordAiError(PreferencesManager prefs, String errorDetail) {
        prefs.saveOpenAILastPersistentError(errorDetail, System.currentTimeMillis());
    }

    private String extractErrorSnippet(Response<?> response) {
        String msg = "HTTP " + response.code();
        try {
            if (response.errorBody() != null) {
                String body = response.errorBody().string();
                if (body != null && !body.trim().isEmpty()) {
                    body = body.trim().replaceAll("\\s+", " ");
                    if (body.length() > 140) body = body.substring(0, 140) + "...";
                    msg = msg + ": " + body;
                }
            }
        } catch (Exception ignored) {
        }
        return msg;
    }

    private void fetchAiReply(StatusBarNotification sbn, NotificationWear notificationWear, String incomingMessage, String fallbackReplyText) {
        PreferencesManager prefs = PreferencesManager.getPreferencesInstance(this);

        if (!prefs.isByokRepliesEnabled()) {
            sendActualReply(sbn, notificationWear, fallbackReplyText);
            return;
        }

        // BYOK: Use user's own API key with configured provider
        Log.d(TAG, "Using BYOK (client-based) backend");
        String apiKey = prefs.getOpenAIApiKey();
        if (apiKey != null) apiKey = apiKey.trim();
        String provider = prefs.getOpenApiSource();
        if (provider == null || provider.trim().isEmpty()) {
            provider = "OpenAI";
        } else {
            provider = provider.trim();
        }

        // BYOK flow continues here
        String model = prefs.getSelectedOpenAIModel();
        String systemPrompt = prefs.getOpenAICustomPrompt();
        if (systemPrompt == null || systemPrompt.trim().isEmpty()) systemPrompt = DEFAULT_LLM_PROMPT;
        if (model == null || model.trim().isEmpty()) {
            model = getDefaultModelForProvider(provider);
        }
        model = model.trim();

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

        if ("Claude".equalsIgnoreCase(provider)) {
            fetchClaudeReply(prefs, service, baseUrl, apiKey, model, systemPrompt, incomingMessage, sbn, notificationWear, fallbackReplyText);
        } else if ("Gemini".equalsIgnoreCase(provider)) {
            fetchGeminiReply(prefs, service, baseUrl, apiKey, model, systemPrompt, incomingMessage, sbn, notificationWear, fallbackReplyText);
        } else {
            // OpenAI, Grok, DeepSeek, Mistral, Custom
            fetchOpenAiCompatibleReply(prefs, service, baseUrl, apiKey, model, systemPrompt, incomingMessage, sbn, notificationWear, fallbackReplyText);
        }
    }

    private void fetchClaudeReply(PreferencesManager prefs, OpenAIService service, String baseUrl, String apiKey, String model, String systemPrompt, String incomingMessage, StatusBarNotification sbn, NotificationWear notificationWear, String fallbackReplyText) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("max_tokens", 1024);
        requestBody.addProperty("system", systemPrompt);

        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", incomingMessage);
        messages.add(userMessage);
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
                                prefs.clearOpenAILastPersistentError();
                                sendActualReply(sbn, notificationWear, reply, getString(R.string.history_source_ai));
                                return;
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing Claude response", e);
                    }
                }
                String err = response.isSuccessful() ? "Empty Claude response" : extractErrorSnippet(response);
                Log.e(TAG, "Claude API failed: " + err);
                recordAiError(prefs, err);
                sendActualReply(sbn, notificationWear, fallbackReplyText);
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                Log.e(TAG, "Claude API network error", t);
                recordAiError(prefs, t.getLocalizedMessage() != null ? t.getLocalizedMessage() : "Network error");
                sendActualReply(sbn, notificationWear, fallbackReplyText);
            }
        });
    }

    private void fetchGeminiReply(PreferencesManager prefs, OpenAIService service, String baseUrl, String apiKey, String model, String systemPrompt, String incomingMessage, StatusBarNotification sbn, NotificationWear notificationWear, String fallbackReplyText) {
        JsonObject requestBody = new JsonObject();
        JsonArray contents = new JsonArray();
        JsonObject contentObj = new JsonObject();
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", systemPrompt + "\n\nUser: " + incomingMessage);
        parts.add(part);
        contentObj.add("parts", parts);
        contents.add(contentObj);
        requestBody.add("contents", contents);

        String cleanModel = model.startsWith("models/") ? model.substring("models/".length()) : model;
        String url;
        if (baseUrl.endsWith(":generateContent/")) {
            url = baseUrl.substring(0, baseUrl.length() - 1);
        } else {
            url = baseUrl.matches(".*/v1(beta|alpha)?/$")
                    ? baseUrl + "models/" + cleanModel + ":generateContent"
                    : baseUrl + "v1beta/models/" + cleanModel + ":generateContent";
        }

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
                                    prefs.clearOpenAILastPersistentError();
                                    sendActualReply(sbn, notificationWear, reply, getString(R.string.history_source_ai));
                                    return;
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing Gemini response", e);
                    }
                }
                String err = response.isSuccessful() ? "Empty Gemini response" : extractErrorSnippet(response);
                Log.e(TAG, "Gemini API failed: " + err);
                recordAiError(prefs, err);
                sendActualReply(sbn, notificationWear, fallbackReplyText);
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                Log.e(TAG, "Gemini API network error", t);
                recordAiError(prefs, t.getLocalizedMessage() != null ? t.getLocalizedMessage() : "Network error");
                sendActualReply(sbn, notificationWear, fallbackReplyText);
            }
        });
    }

    private void fetchOpenAiCompatibleReply(PreferencesManager prefs, OpenAIService service, String baseUrl, String apiKey, String model, String systemPrompt, String incomingMessage, StatusBarNotification sbn, NotificationWear notificationWear, String fallbackReplyText) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", systemPrompt));
        messages.add(new Message("user", incomingMessage));

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
                        Log.i(TAG, "OpenAI/Compatible successful response: " + aiReply);
                        prefs.clearOpenAILastPersistentError();
                        sendActualReply(sbn, notificationWear, aiReply, getString(R.string.history_source_ai));
                        return;
                    }
                }
                String err = response.isSuccessful() ? "Empty AI response" : extractErrorSnippet(response);
                Log.e(TAG, "OpenAI/Compatible API failed: " + err);
                recordAiError(prefs, err);
                // Fallback to default reply
                sendActualReply(sbn, notificationWear, fallbackReplyText);
            }

            @Override
            public void onFailure(@NonNull Call<OpenAIResponse> call, @NonNull Throwable t) {
                Log.e(TAG, "OpenAI/Compatible API network error", t);
                recordAiError(prefs, t.getLocalizedMessage() != null ? t.getLocalizedMessage() : "Network error");
                sendActualReply(sbn, notificationWear, fallbackReplyText);
            }
        });
    }

    private boolean canPurgeMessages() {
        //Added L to avoid numeric overflow expression
        //https://stackoverflow.com/questions/43801874/numeric-overflow-in-expression-manipulating-timestamps
        long daysBeforePurgeInMS = 30 * 24 * 60 * 60 * 1000L;
        return (System.currentTimeMillis() - PreferencesManager.getPreferencesInstance(this).getLastPurgedTime()) > daysBeforePurgeInMS;
    }

    // Decision logic (canReply, shouldReply, isSupportedPackage, canSendReplyNow,
    // isGroupMessageAndReplyAllowed, isServiceEnabled) is in NotificationReplyDecider.

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
        if (ruleNotificationProcessor != null) {
            ruleNotificationProcessor.shutdown();
            ruleNotificationProcessor = null;
        }
    }

}
