package com.aibale.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;

public class ScreenCaptureService extends Service {

    private static final String CHANNEL_ID = "screen_capture";
    private static final int NOTIFICATION_ID = 701;

    private MediaProjection mediaProjection;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent resultData;

        if (Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra(
                    "resultData", Intent.class
            );
        } else {
            resultData = intent.getParcelableExtra("resultData");
        }

        if (resultData == null || resultCode != RESULT_OK) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = buildNotification();

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                );
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(
                            Context.MEDIA_PROJECTION_SERVICE
                    );

            if (manager == null) {
                stopSelf();
                return START_NOT_STICKY;
            }

            mediaProjection = manager.getMediaProjection(
                    resultCode,
                    resultData
            );

            if (mediaProjection == null) {
                stopSelf();
                return START_NOT_STICKY;
            }

            // نقطهٔ اتصال برای افزودن ضبط واقعی صفحه.
            // در این نسخه هنوز فریم تصویری دریافت یا ارسال نمی‌شود.

        } catch (SecurityException | IllegalStateException e) {
            stopSelf();
            return START_NOT_STICKY;
        }

        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("AI Bale")
                .setContentText("اشتراک‌گذاری صفحه فعال است")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "اشتراک‌گذاری صفحه",
                    NotificationManager.IMPORTANCE_LOW
            );

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }

        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
              }
