package com.parishod.watomatic.model.logs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "message_logs",
        foreignKeys = {@ForeignKey(
                entity = AppPackage.class,
                parentColumns = "index",
                childColumns = "index",
                onDelete = ForeignKey.CASCADE
        )},
        indices = {
                @Index(value = "index"),
                @Index(value = "attempt_id")
        })
public class MessageLog {
    @PrimaryKey(autoGenerate = true)
    @NonNull
    private int id;
    @NonNull
    private int index;
    @ColumnInfo(name = "notif_id")
    @Nullable
    private String notifId;
    @ColumnInfo(name = "notif_title")
    @Nullable
    private String notifTitle;
    @ColumnInfo(name = "notif_incoming_msg")
    @Nullable
    private String notifIncomingMsg;
    @ColumnInfo(name = "notif_arrived_time")
    private long notifArrivedTime;
    @ColumnInfo(name = "notif_is_replied")
    private boolean notifIsReplied;
    @ColumnInfo(name = "notif_replied_msg")
    @Nullable
    private String notifRepliedMsg;
    @ColumnInfo(name = "matched_rule_name")
    @Nullable
    private String matchedRuleName;
    @ColumnInfo(name = "notif_reply_time")
    private long notifReplyTime;
    @NonNull
    @ColumnInfo(name = "execution_status", defaultValue = "LEGACY")
    private String executionStatus = ReplyHistoryStatus.LEGACY;
    @Nullable
    @ColumnInfo(name = "attempt_id")
    private String attemptId;

    public MessageLog(int index,
                      @Nullable String notifTitle,
                      long notifArrivedTime,
                      @Nullable String notifRepliedMsg,
                      long notifReplyTime
    ) {
        this.index = index;
        this.notifId = java.util.UUID.randomUUID().toString();
        this.notifTitle = notifTitle;
        this.notifArrivedTime = notifArrivedTime;
        this.notifRepliedMsg = notifRepliedMsg;
        this.notifReplyTime = notifReplyTime;
        this.notifIsReplied = true;
        this.executionStatus = ReplyHistoryStatus.LEGACY;
        this.attemptId = null;
    }

    @Ignore
    public MessageLog(int index,
                      @Nullable String notifTitle,
                      @Nullable String notifIncomingMsg,
                      long notifArrivedTime,
                      @Nullable String notifRepliedMsg,
                      @Nullable String matchedRuleName,
                      long notifReplyTime
    ) {
        this(index, notifTitle, notifArrivedTime, notifRepliedMsg, notifReplyTime);
        this.notifIncomingMsg = notifIncomingMsg;
        this.matchedRuleName = matchedRuleName;
    }

    @Ignore
    public MessageLog(int index,
                      @Nullable String notifTitle,
                      @Nullable String notifIncomingMsg,
                      long notifArrivedTime,
                      @Nullable String notifRepliedMsg,
                      @Nullable String matchedRuleName,
                      long notifReplyTime,
                      @NonNull String executionStatus,
                      @Nullable String attemptId
    ) {
        this(index, notifTitle, notifIncomingMsg, notifArrivedTime, notifRepliedMsg, matchedRuleName, notifReplyTime);
        this.executionStatus = executionStatus;
        this.attemptId = attemptId;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    @Nullable
    public String getNotifId() {
        return notifId;
    }

    public void setNotifId(@Nullable String notifId) {
        this.notifId = notifId;
    }

    @Nullable
    public String getNotifTitle() {
        return notifTitle;
    }

    public void setNotifTitle(@Nullable String notifTitle) {
        this.notifTitle = notifTitle;
    }

    @Nullable
    public String getNotifIncomingMsg() {
        return notifIncomingMsg;
    }

    public void setNotifIncomingMsg(@Nullable String notifIncomingMsg) {
        this.notifIncomingMsg = notifIncomingMsg;
    }

    public long getNotifArrivedTime() {
        return notifArrivedTime;
    }

    public void setNotifArrivedTime(long notifArrivedTime) {
        this.notifArrivedTime = notifArrivedTime;
    }

    public boolean isNotifIsReplied() {
        return notifIsReplied;
    }

    public void setNotifIsReplied(boolean notifIsReplied) {
        this.notifIsReplied = notifIsReplied;
    }

    @Nullable
    public String getNotifRepliedMsg() {
        return notifRepliedMsg;
    }

    public void setNotifRepliedMsg(@Nullable String notifRepliedMsg) {
        this.notifRepliedMsg = notifRepliedMsg;
    }

    @Nullable
    public String getMatchedRuleName() {
        return matchedRuleName;
    }

    public void setMatchedRuleName(@Nullable String matchedRuleName) {
        this.matchedRuleName = matchedRuleName;
    }

    public long getNotifReplyTime() {
        return notifReplyTime;
    }

    public void setNotifReplyTime(long notifReplyTime) {
        this.notifReplyTime = notifReplyTime;
    }

    @NonNull
    public String getExecutionStatus() {
        return executionStatus;
    }

    public void setExecutionStatus(@NonNull String executionStatus) {
        this.executionStatus = executionStatus;
    }

    @Nullable
    public String getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(@Nullable String attemptId) {
        this.attemptId = attemptId;
    }
}
