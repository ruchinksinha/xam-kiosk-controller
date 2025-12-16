package com.xam.kiosk.ui;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.hardware.usb.UsbManager;

import com.xam.kiosk.R;
import com.xam.kiosk.admin.KioskDeviceAdminReceiver;
import com.xam.kiosk.model.AdminMetadata;
import com.xam.kiosk.util.ConfigReader;

import java.util.List;

public class KioskActivity extends Activity {

    // NodeApp details
    private static final String NODE_APP_PACKAGE = "com.xam.nodeapp";
    private static final String NODE_APP_MAIN_ACTIVITY = "com.xam.nodeapp.MainActivity";

    private static final long NODEAPP_RECHECK_MS = 5000; // 5 seconds
    private final Handler handler = new Handler(Looper.getMainLooper());

    private AdminMetadata adminMetadata;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kiosk);

        adminMetadata = ConfigReader.readAdminMetadata(this);

        enableUsbFileTransfer();

        // Visual / UX kiosk behavior
        forceMaxBrightness();
        keepScreenOn();
        enableImmersiveMode();   // hide nav + status bar

        // Policy / device-owner kiosk behavior
        ensureDeviceOwnerAndLockTask();   // lock task, disable status bar, OS-level restrictions
        lockVolumeToMax();                // set volume to max (extra safety)

        if (adminMetadata != null && adminMetadata.getSsid() != null) {
            ensureWifiConnectedToHub();
        }

        launchNodeAppSmart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-apply in case system tries to restore UI
        enableImmersiveMode();
        lockVolumeToMax();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // If user somehow brings up system UI, slam it back down
        if (hasFocus) {
            enableImmersiveMode();
        }
    }

    private boolean isNodeAppInstalled() {
        try {
            getPackageManager().getPackageInfo(NODE_APP_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void enableUsbFileTransfer() {
        try {
            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (usbManager != null) {
                Log.i("KioskActivity", "USB file transfer ready");
            }
        } catch (Exception e) {
            Log.e("KioskActivity", "USB setup failed: " + e.getMessage());
        }
    }

    private void ensureDeviceOwnerAndLockTask() {
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, KioskDeviceAdminReceiver.class);

        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
            String[] packages = new String[]{ NODE_APP_PACKAGE, getPackageName() };
            dpm.setLockTaskPackages(admin, packages);

            // OS-level volume lock
            dpm.addUserRestriction(admin, UserManager.DISALLOW_ADJUST_VOLUME);

            // OS-level brightness config lock
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_BRIGHTNESS);

            // Try to disable status bar / notification shade
            try {
                dpm.setStatusBarDisabled(admin, true);
            } catch (Exception ignored) {
                // Not supported on all devices / Android versions
            }

            try {
                startLockTask();
            } catch (IllegalStateException e) {
                Log.e("KioskActivity", "LockTask failed: " + e.getMessage());
            }
        } else {
            Log.w("KioskActivity", "Device is not device-owner. Run dpm set-device-owner.");
        }
    }

    private void ensureWifiConnectedToHub() {
    WifiManager wifiManager = (WifiManager) getApplicationContext()
            .getSystemService(Context.WIFI_SERVICE);
    if (wifiManager == null) return;

    if (adminMetadata == null || adminMetadata.getSsid() == null ||
        adminMetadata.getSsid().isEmpty()) {
        return;
    }

    String ssid = "\"" + adminMetadata.getSsid() + "\"";

    if (!wifiManager.isWifiEnabled()) {
        wifiManager.setWifiEnabled(true);
    }

    WifiConfiguration targetConfig = null;

    List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
    if (configs != null) {
        for (WifiConfiguration c : configs) {
            if (ssid.equals(c.SSID)) {
                targetConfig = c;
                break;
            }
        }
    }

    if (targetConfig == null) {
        // OPEN NETWORK CONFIG
        WifiConfiguration wc = new WifiConfiguration();
        wc.SSID = ssid;   // must already be quoted
        wc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        wc.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
        wc.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
        wc.allowedAuthAlgorithms.clear();
        wc.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
        wc.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
        wc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
        wc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);

        int netId = wifiManager.addNetwork(wc);
        if (netId == -1) {
            Log.e("KioskActivity", "Failed to add WiFi network: " + ssid);
            return;
        }
        boolean enabled = wifiManager.enableNetwork(netId, true);
        if (!enabled) {
            Log.e("KioskActivity", "Failed to enable WiFi network: " + ssid);
            return;
        }
    } else {
        boolean enabled = wifiManager.enableNetwork(targetConfig.networkId, true);
        if (!enabled) {
            Log.e("KioskActivity", "Failed to enable existing WiFi network: " + ssid);
            return;
        }
    }

    wifiManager.reconnect();
}

    private void launchNodeApp() {
        Intent intent = new Intent();
        intent.setClassName(NODE_APP_PACKAGE, NODE_APP_MAIN_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(intent);
            Log.i("KioskActivity", "Launching NodeApp");
        } catch (Exception e) {
            Log.e("KioskActivity", "Failed to launch NodeApp: " + e.getMessage());
        }
    }

    private void launchNodeAppSmart() {
        if (isNodeAppInstalled()) {
            launchNodeApp();
        } else {
            Log.i("KioskActivity", "Waiting for NodeApp to be installed...");

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
            Log.e("KioskActivity", "Brightness lock failed: " + e.getMessage());
        }
    }

    private void keepScreenOn() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /**
     * Hide nav bar, status bar, recents button via immersive sticky mode.
     */
    private void enableImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
            );
        }
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

    /**
     * Swallow hardware volume keys so user can't change volume.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
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
