package com.xam.kiosk.boot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

public class BootCompletedReceiver extends BroadcastReceiver {

    private static final String TAG = "BootCompletedReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;

        String action = intent.getAction();
        Log.i(TAG, "onReceive action=" + action);

        // We only want to launch the real boot flow when the user is UNLOCKED.
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            Log.w(TAG, "LOCKED_BOOT_COMPLETED: device in Direct Boot, not launching kiosk yet.");
            // Optionally start a Direct-Boot-safe service here ONLY if you truly need it.
            // But do NOT touch /storage/emulated/0 or CE storage, and do NOT trigger MTP-dependent stuff.
            return;
        }

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_USER_UNLOCKED.equals(action)) {
            if (!isUserUnlocked(context)) {
                Log.w(TAG, action + ": user still not unlocked, skipping launch (will wait for USER_UNLOCKED).");
                return;
            }

            Intent svc = new Intent(context, BootLaunchService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to start BootLaunchService", t);
            }
        }
    }

    private boolean isUserUnlocked(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true;
        try {
            UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
            return um != null && um.isUserUnlocked();
        } catch (Throwable t) {
            Log.w(TAG, "isUserUnlocked check failed; assume locked", t);
            return false;
        }
    }
}

