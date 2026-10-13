package com.vocalix.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

public class NotificationHelper {
    public static final String KEY_BG_PROCESSING = "pref_bg_processing_notifications";
    public static final String CHANNEL_ID = "vocalix_transcribe_channel";
    public static final int NOTIF_ID_TRANSCRIBING = 4001;
    public static final int NOTIF_ID_COMPLETE = 4002;

    private static PowerManager.WakeLock wakeLock = null;

    public static boolean isBgProcessingEnabled(Context context) {
        SharedPreferences sp = context.getSharedPreferences("vocalix_settings", Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_BG_PROCESSING, false);
    }

    public static void setBgProcessingEnabled(Context context, boolean enabled) {
        SharedPreferences sp = context.getSharedPreferences("vocalix_settings", Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_BG_PROCESSING, enabled).apply();
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = nm.getNotificationChannel(CHANNEL_ID);
                if (channel == null) {
                    channel = new NotificationChannel(
                            CHANNEL_ID,
                            context.getString(R.string.notif_channel_name),
                            NotificationManager.IMPORTANCE_LOW
                    );
                    channel.setDescription(context.getString(R.string.notif_channel_desc));
                    channel.setShowBadge(false);
                    nm.createNotificationChannel(channel);
                }
            }
        }
    }

    public static void showTranscribingNotification(Context context) {
        if (!isBgProcessingEnabled(context)) return;

        // Check POST_NOTIFICATIONS permission on Android 13+ (API 33)
        if (Build.VERSION.SDK_INT >= 33) {
            if (context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        acquireWakeLock(context);
        ensureChannel(context);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent appIntent = new Intent(context, MainActivity.class);
        appIntent.setAction(Intent.ACTION_MAIN);
        appIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, appIntent, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context);
        }

        builder.setContentTitle(context.getString(R.string.notif_transcribing_title))
               .setContentText(context.getString(R.string.notif_transcribing_desc))
               .setSmallIcon(R.drawable.ic_sparkle)
               .setOngoing(true)
               .setAutoCancel(false)
               .setProgress(0, 0, true)
               .setContentIntent(pendingIntent);

        nm.notify(NOTIF_ID_TRANSCRIBING, builder.build());
    }

    public static void dismissTranscribingNotification(Context context, boolean showCompletionNotice) {
        releaseWakeLock();
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        nm.cancel(NOTIF_ID_TRANSCRIBING);

        if (showCompletionNotice && isBgProcessingEnabled(context)) {
            if (Build.VERSION.SDK_INT >= 33) {
                if (context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
            }

            Intent appIntent = new Intent(context, MainActivity.class);
            appIntent.setAction(Intent.ACTION_MAIN);
            appIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, appIntent, flags);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(context, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(context);
            }

            builder.setContentTitle(context.getString(R.string.notif_complete_title))
                   .setContentText(context.getString(R.string.notif_complete_desc))
                   .setSmallIcon(R.drawable.ic_sparkle)
                   .setOngoing(false)
                   .setAutoCancel(true)
                   .setContentIntent(pendingIntent);

            nm.notify(NOTIF_ID_COMPLETE, builder.build());
        }
    }

    private static synchronized void acquireWakeLock(Context context) {
        if (wakeLock == null) {
            try {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vocalix:transcription_wakelock");
                    wakeLock.acquire(10 * 60 * 1000L); // 10 minutes safety timeout
                }
            } catch (Exception ignored) {}
        }
    }

    private static synchronized void releaseWakeLock() {
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } catch (Exception ignored) {}
            wakeLock = null;
        }
    }
}

