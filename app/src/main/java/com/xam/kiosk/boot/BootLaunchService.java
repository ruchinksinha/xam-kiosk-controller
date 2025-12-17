package com.xam.kiosk.boot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.xam.kiosk.ui.KioskActivity;

public class BootLaunchService extends Service {

    private static final String TAG = "BootLaunchService";
    private static final String CHANNEL_ID = "kiosk_boot";
    private static final int NOTIF_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            startForegroundCompat();
        } catch (Throwable t) {
            Log.e(TAG, "startForegroundCompat failed", t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Give system a moment to finish boot UI/launcher readiness
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent i = new Intent(getApplicationContext(), KioskActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
                Log.i(TAG, "KioskActivity launched from BootLaunchService");
            } catch (Throwable t) {
                Log.e(TAG, "Failed to start KioskActivity", t);
            } finally {
                stopForeground(true);
                stopSelf();
            }
        }, 1500);

        return START_NOT_STICKY;
    }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Kiosk Boot",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);

            Notification n = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Starting Kiosk")
                    .setContentText("Launching kiosk controller…")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .build();

            startForeground(NOTIF_ID, n);
        } else {
            // Pre-O: foreground not required
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

