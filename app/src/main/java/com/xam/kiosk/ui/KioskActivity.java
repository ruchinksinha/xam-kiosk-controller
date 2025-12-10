package com.xam.kiosk.ui;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Toast;
import android.content.pm.PackageManager;
import android.media.AudioManager;

import com.xam.kiosk.R;
import com.xam.kiosk.admin.KioskDeviceAdminReceiver;

import java.util.List;

public class KioskActivity extends Activity {

    // NodeApp details
    private static final String NODE_APP_PACKAGE = "com.xam.nodeapp";
    private static final String NODE_APP_MAIN_ACTIVITY = "com.xam.nodeapp.MainActivity";

    // Hub WiFi settings (match WifiConfig-Hub.xml)
    private static final String HUB_SSID = "\"XAM-HUB\"";
    private static final String HUB_PSK  = "\"XamHubPassword123\"";

    private static final long NODEAPP_RECHECK_MS = 5000; // 5 seconds

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kiosk);

        // Kiosk visual/UX locks
        forceMaxBrightness();
        keepScreenOn();

        // Kiosk policy/device-owner locks
        ensureDeviceOwnerAndLockTask();
        lockVolumeToMax();

        // Optionally: auto-connect to Hub WiFi
        // ensureWifiConnectedToHub();

        launchNodeAppSmart();
    }

    private boolean isNodeAppInstalled() {
        try {
            getPackageManager().getPackageInfo(NODE_APP_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void ensureDeviceOwnerAndLockTask() {
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, KioskDeviceAdminReceiver.class);

        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
            String[] packages = new String[]{ NODE_APP_PACKAGE, getPackageName() };
            dpm.setLockTaskPackages(admin, packages);

            // Disallow volume adjustment at OS level (extra safety)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_ADJUST_VOLUME);

            // Try to disable status bar / notification shade (may require privileged / system app)
            try {
                dpm.setStatusBarDisabled(admin, true);
            } catch (Exception ignored) {
                // Ignore if not supported
            }

            try {
                startLockTask();
            } catch (IllegalStateException e) {
                Toast.makeText(this, "LockTask failed: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this,
                    "Device is not device-owner. Run dpm set-device-owner.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void ensureWifiConnectedToHub() {
        WifiManager wifiManager = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return;

        if (!wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
        }

        WifiConfiguration targetConfig = null;
        List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
        if (configs != null) {
            for (WifiConfiguration c : configs) {
                if (HUB_SSID.equals(c.SSID)) {
                    targetConfig = c;
                    break;
                }
            }
        }

        if (targetConfig == null) {
            WifiConfiguration wc = new WifiConfiguration();
            wc.SSID = HUB_SSID;
            wc.preSharedKey = HUB_PSK;
            wc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            int netId = wifiManager.addNetwork(wc);
            wifiManager.enableNetwork(netId, true);
        } else {
            wifiManager.enableNetwork(targetConfig.networkId, true);
        }

        wifiManager.reconnect();
    }

    private void launchNodeApp() {
        Intent intent = new Intent();
        intent.setClassName(NODE_APP_PACKAGE, NODE_APP_MAIN_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this,
                    "Failed to launch NodeApp: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void launchNodeAppSmart() {
        if (isNodeAppInstalled()) {
            launchNodeApp();
        } else {
            // Optional: show a minimal “provisioning” UI or toast
            Toast.makeText(this,
                    "Waiting for NodeApp to be installed...",
                    Toast.LENGTH_SHORT).show();

            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (isNodeAppInstalled()) {
                        launchNodeApp();
                    } else {
                        handler.postDelayed(this, NODEAPP_RECHECK_MS);
                    }
                }
            }, NODEAPP_RECHECK_MS);
        }
    }

    private void forceMaxBrightness() {
        try {
            // Disable auto-brightness
            Settings.System.putInt(
                    getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            );

            // Set brightness to max (255)
            Settings.System.putInt(
                    getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS,
                    255
            );

            // Apply immediately to this window
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            layoutParams.screenBrightness = 1.0f; // 100%
            getWindow().setAttributes(layoutParams);

        } catch (Exception e) {
            Toast.makeText(this, "Brightness lock failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void keepScreenOn() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void lockVolumeToMax() {
        AudioManager audioManager =
                (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;

        int maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int maxRing  = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING);
        int maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0);
        audioManager.setStreamVolume(AudioManager.STREAM_RING,  maxRing,  0);
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            // Swallow volume keys
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}
