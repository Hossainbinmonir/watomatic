package com.parishod.watomatic.service;

import android.service.notification.StatusBarNotification;

import com.parishod.watomatic.model.preferences.PreferencesManager;
import com.parishod.watomatic.model.utils.ContactsHelper;
import com.parishod.watomatic.model.utils.DbUtils;
import com.parishod.watomatic.model.utils.NotificationUtils;

import static java.lang.Math.max;

/**
 * Encapsulates the decision logic for whether to auto-reply to a notification.
 * Extracted from NotificationService to enable unit testing without requiring
 * a NotificationListenerService instance.
 */
public class NotificationReplyDecider {

    private final PreferencesManager preferencesManager;
    private final DbUtils dbUtils;
    private final ContactsHelper contactsHelper;

    public NotificationReplyDecider(PreferencesManager preferencesManager,
                                    DbUtils dbUtils,
                                    ContactsHelper contactsHelper) {
        this.preferencesManager = preferencesManager;
        this.dbUtils = dbUtils;
        this.contactsHelper = contactsHelper;
    }

    /**
     * Checks all preconditions for replying: service enabled, supported app,
     * fresh notification, group rules, and cooldown.
     */
    public boolean canReply(StatusBarNotification sbn) {
        return isServiceEnabled() &&
                isSupportedPackage(sbn) &&
                NotificationUtils.isNewNotification(sbn) &&
                isGroupMessageAndReplyAllowed(sbn) &&
                canSendReplyNow(sbn);
    }

    /**
     * Checks contact-based reply filters (whitelist/blacklist).
     * Returns true if we should reply to this sender.
     */
    public boolean shouldReply(StatusBarNotification sbn) {
        boolean isGroup = sbn.getNotification().extras.getBoolean("android.isGroupConversation");

        if (preferencesManager.isContactReplyEnabled() && !isGroup) {
            String senderName = sbn.getNotification().extras.getString("android.title");

            boolean isNameSelected =
                    (contactsHelper.hasContactPermission()
                            && preferencesManager.getReplyToNames().contains(senderName)) ||
                            preferencesManager.getCustomReplyNames().contains(senderName);

            if ((isNameSelected && preferencesManager.isContactReplyBlacklistMode()) ||
                    (!isNameSelected && !preferencesManager.isContactReplyBlacklistMode())) {
                return false;
            }
        }

        return true;
    }

    boolean isServiceEnabled() {
        return preferencesManager.isServiceEnabled();
    }

    boolean isSupportedPackage(StatusBarNotification sbn) {
        return preferencesManager.getEnabledApps().contains(sbn.getPackageName());
    }

    boolean canSendReplyNow(StatusBarNotification sbn) {
        String title = NotificationUtils.getTitle(sbn);
        String selfDisplayName = sbn.getNotification().extras.getString("android.selfDisplayName");
        if (title != null && title.equalsIgnoreCase(selfDisplayName)) {
            return false;
        }
        long timeDelay = max(0L, preferencesManager.getAutoReplyDelay());
        if (timeDelay == 0L) {
            return true;
        }
        return (System.currentTimeMillis() - dbUtils.getLastRepliedTime(sbn.getPackageName(), title)
                >= timeDelay);
    }

    boolean isGroupMessageAndReplyAllowed(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null || sbn.getNotification().extras == null) {
            return true;
        }
        String rawTitle = NotificationUtils.getTitleRaw(sbn);
        Object rawTextObj = sbn.getNotification().extras.get("android.text");
        boolean isPossiblyAnImageGrpMsg = NotificationUtils.isPossiblyImageGroupMessage(rawTitle, rawTextObj);

        if (!sbn.getNotification().extras.getBoolean("android.isGroupConversation")) {
            return !isPossiblyAnImageGrpMsg;
        } else {
            return preferencesManager.isGroupReplyEnabled();
        }
    }
}
