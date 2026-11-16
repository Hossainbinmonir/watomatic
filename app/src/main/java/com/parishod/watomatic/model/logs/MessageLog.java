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
                @Index(value = "index")
        })
public class MessageLog {
    @PrimaryKey(autoGenerate = true)
    @NonNull
    private int id;
    @NonNull
    private int index;
    @ColumnInfo(name = "notif_id")
    private String notifId;
    @ColumnInfo(name = "notif_title")
    private String notifTitle;
    @ColumnInfo(name = "notif_incoming_msg")
    @Nullable
    private String notifIncomingMsg;
    @ColumnInfo(name = "notif_arrived_time")
    private long notifArrivedTime;
    @ColumnInfo(name = "notif_is_replied")
    private boolean notifIsReplied;
    @ColumnInfo(name = "notif_replied_msg")
    private String notifRepliedMsg;
    @ColumnInfo(name = "matched_rule_name")
    @Nullable
    private String matchedRuleName;
    @ColumnInfo(name = "notif_reply_time")
    private long notifReplyTime;

    public MessageLog(int index,
                      String notifTitle,
                      long notifArrivedTime,
                      String notifRepliedMsg,
                      long notifReplyTime
    ) {
        this.index = index;
        this.notifId = java.util.UUID.randomUUID().toString();
        this.notifTitle = notifTitle;
        this.notifArrivedTime = notifArrivedTime;
        this.notifRepliedMsg = notifRepliedMsg;
        this.notifReplyTime = notifReplyTime;
        this.notifIsReplied = true;
    }

    @Ignore
    public MessageLog(int index,
                      String notifTitle,
                      @Nullable String notifIncomingMsg,
                      long notifArrivedTime,
                      String notifRepliedMsg,
                      @Nullable String matchedRuleName,
                      long notifReplyTime
    ) {
        this(index, notifTitle, notifArrivedTime, notifRepliedMsg, notifReplyTime);
        this.notifIncomingMsg = notifIncomingMsg;
        this.matchedRuleName = matchedRuleName;
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

    public String getNotifId() {
        return notifId;
    }

    public void setNotifId(String notifId) {
        this.notifId = notifId;
    }

    public String getNotifTitle() {
        return notifTitle;
    }

    public void setNotifTitle(String notifTitle) {
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

    public String getNotifRepliedMsg() {
        return notifRepliedMsg;
    }

    public void setNotifRepliedMsg(String notifRepliedMsg) {
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
}
