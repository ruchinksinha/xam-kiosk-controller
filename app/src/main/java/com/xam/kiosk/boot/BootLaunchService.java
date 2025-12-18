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
import android.os.UserManager;
import android.util.Log;

import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import com.xam.kiosk.ui.KioskActivity;

import java.io.File;
import java.util.List;

public class BootLaunchService extends Service {

    private static final String TAG = "BootLaunchService";
    private static final String CHANNEL_ID = "kiosk_boot";
    private static final int NOTIF_ID = 1001;

    // Tuning
    private static final long POLL_EVERY_MS = 700;
    private static final long TIMEOUT_MS = 60_000;  // 60s max wait

    private final Handler h = new Handler(Looper.getMainLooper());
    private long startTs;

    @Override
    public void onCreate() {
        super.onCreate();
        startTs = System.currentTimeMillis();
        try {
            startForegroundCompat();
        } catch (Throwable t) {
            Log.e(TAG, "startForegroundCompat failed", t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand: waiting for user unlock + storage ready...");
        h.post(checkReadyRunnable);
        return START_NOT_STICKY;
    }

    private final Runnable checkReadyRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                boolean unlocked = isUserUnlocked();
                boolean storageReady = isStorageReady();

                Log.i(TAG, "readyCheck: unlocked=" + unlocked + " storageReady=" + storageReady);

                if (unlocked && storageReady) {
                    launchKiosk();
                    return;
                }

                if (System.currentTimeMillis() - startTs > TIMEOUT_MS) {
                    Log.w(TAG, "Timeout waiting for storage. Launching kiosk anyway.");
                    launchKiosk();
                    return;
                }

            } catch (Throwable t) {
                Log.e(TAG, "readyCheck failed", t);
                // keep retrying
            }

            h.postDelayed(this, POLL_EVERY_MS);
        }
    };

    private boolean isUserUnlocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true;
        try {
            UserManager um = (UserManager) getSystemService(USER_SERVICE);
            return um != null && um.isUserUnlocked();
        } catch (Throwable t) {
            Log.w(TAG, "isUserUnlocked check failed", t);
            return false;
        }
    }

    /**
     * Storage "ready" means:
     * - emulated storage exists and is readable
     * AND/OR
     * - StorageManager reports at least one usable public volume
     */
    private boolean isStorageReady() {
        // 1) Quick filesystem check (works across many builds)
        try {
            File emu0 = new File("/storage/emulated/0");
            if (emu0.exists() && emu0.canRead()) {
                return true;
            }
        } catch (Throwable ignored) {}

        // 2) StorageManager check (API 24+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                StorageManager sm = (StorageManager) getSystemService(STORAGE_SERVICE);
                if (sm != null) {
                    List<StorageVolume> vols = sm.getStorageVolumes();
                    if (vols != null) {
                        for (StorageVolume v : vols) {
                            // We just need at least one mounted-ish volume visible to the framework.
                            // getState() is hidden on some APIs; so we rely on directory existence where possible.
                            File dir = v.getDirectory();
                            if (dir != null && dir.exists() && dir.canRead()) {
                                return true;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "StorageManager check failed", t);
            }
        }

        return false;
    }

    private void launchKiosk() {
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
            try { stopForeground(true); } catch (Throwable ignored) {}
            stopSelf();
        }
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
                    .setContentText("Waiting for storage…")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .build();

            startForeground(NOTIF_ID, n);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
