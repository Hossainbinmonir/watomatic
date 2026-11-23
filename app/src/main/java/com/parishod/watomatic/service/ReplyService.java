package com.parishod.watomatic.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * @deprecated Dormant legacy service disabled in {@code AndroidManifest.xml}.
 * Direct {@link PendingIntent#send} dispatch is intentionally disabled here; any future
 * foreground reply dispatch must route through {@link com.parishod.watomatic.service.reply.ReplyExecutionManager}.
 */
@Deprecated
public class ReplyService extends Service {
    private static final String TAG = "ReplyService";
    private static final String CHANNEL_ID = "watomatic_reply_channel";

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.w(TAG, "Ignoring deprecated ReplyService start; replies must dispatch via ReplyExecutionManager.");
        stopForeground(true);
        stopSelf();
        return START_NOT_STICKY;
    }

    private void startForegroundReply() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                    NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Reply Service", NotificationManager.IMPORTANCE_LOW);
                    notificationManager.createNotificationChannel(channel);
                }

                Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("Sending Reply")
                        .setContentText("Watomatic is replying...")
                        .setSmallIcon(android.R.drawable.stat_notify_chat)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .build();

                if (Build.VERSION.SDK_INT >= 34) { // Android 14
                    startForeground(102, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
                } else {
                    startForeground(102, notification);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start foreground service: " + e.getMessage());
            }
        }
    }
}
