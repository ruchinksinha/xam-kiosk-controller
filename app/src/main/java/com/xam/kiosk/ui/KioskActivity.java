package com.xam.kiosk.ui;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import com.xam.kiosk.R;
import com.xam.kiosk.admin.KioskDeviceAdminReceiver;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.List;

public class KioskActivity extends Activity {

    private static final String TAG = "KioskActivity";

    // NodeApp details
    private static final String NODE_APP_PACKAGE = "com.xam.nodeapp";
    private static final String NODE_APP_MAIN_ACTIVITY = "com.xam.nodeapp.MainActivity";

    // Provisioning file pushed via MTP
    private static final String DEFAULT_CONFIG_PATH = "/sdcard/config.json";

    // Retry pacing
    private static final long CONFIG_RECHECK_MS  = 3000;
    private static final long WIFI_RECHECK_MS    = 5000;
    private static final long INSTALL_RECHECK_MS = 5000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private String ssidFromConfig;
    private String nodeApkPathFromConfig; // relative or absolute

    private boolean launchAttempted = false;
    private boolean installTriggered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // IMPORTANT: Ensure decor view exists before immersive APIs (fixes Lenovo A13 NPE)
        setContentView(R.layout.activity_kiosk);

        keepScreenOn();
        forceMaxBrightness();

        // Do NOT call immersive synchronously here on some OEM builds; post it.
        handler.post(this::enableImmersiveModeSafe);

        // Do NOT disable USB file transfer here; MTP provisioning depends on it.

        // If device owner: force kiosk as HOME + allow locktask packages
        ensurePoliciesIfDeviceOwner();

        // Start provisioning flow
        waitForConfigThenProceed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enableImmersiveModeSafe();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enableImmersiveModeSafe();
    }

    // =========================
    // Provisioning workflow
    // =========================

    private void waitForConfigThenProceed() {
        if (!readConfig(DEFAULT_CONFIG_PATH)) {
            Log.i(TAG, "config.json not found/invalid yet. Waiting for MTP push...");
            handler.postDelayed(this::waitForConfigThenProceed, CONFIG_RECHECK_MS);
            return;
        }

        Log.i(TAG, "Config loaded: ssid=" + ssidFromConfig + ", nodeapp_apk_path=" + nodeApkPathFromConfig);

        // Step 1: connect to WiFi if SSID present
        if (ssidFromConfig != null && !ssidFromConfig.trim().isEmpty()) {
            ensureWifiConnected(ssidFromConfig.trim(), this::ensureNodeAppInstalledThenLaunch);
        } else {
            ensureNodeAppInstalledThenLaunch();
        }
    }

    private void ensureNodeAppInstalledThenLaunch() {
        if (!isPackageInstalled(NODE_APP_PACKAGE)) {
            File apk = resolveNodeApkFile(nodeApkPathFromConfig);
            if (apk == null || !apk.exists()) {
                Log.i(TAG, "NodeApp not installed and APK not found yet. Waiting...");
                handler.postDelayed(this::ensureNodeAppInstalledThenLaunch, INSTALL_RECHECK_MS);
                return;
            }

            // Trigger installer only once; then just poll until installed.
            if (!installTriggered) {
                installTriggered = true;
                Log.i(TAG, "Found NodeApp APK at: " + apk.getAbsolutePath());
                installApk(apk);
            }

            handler.postDelayed(this::ensureNodeAppInstalledThenLaunch, INSTALL_RECHECK_MS);
            return;
        }

        // Step 3: launch NodeApp
        launchNodeApp();

        // Step 4: after success, switch USB to charging-only + locktask
        finalizeKioskAfterSuccess();
    }

    // =========================
    // Config
    // =========================

    /**
     * Reads config JSON like:
     * {"ssid": "Office_WiFi_5G","nodeapp_apk_path": "somepath/NodeApp.apk"}
     */
    private boolean readConfig(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return false;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }

            JSONObject obj = new JSONObject(sb.toString());
            ssidFromConfig = obj.optString("ssid", null);
            nodeApkPathFromConfig = obj.optString("nodeapp_apk_path", null);

            return nodeApkPathFromConfig != null && !nodeApkPathFromConfig.trim().isEmpty();

        } catch (Exception e) {
            Log.e(TAG, "Failed to read config.json: " + e.getMessage(), e);
            return false;
        }
    }

    private File resolveNodeApkFile(String nodeappApkPath) {
        try {
            if (nodeappApkPath == null) return null;
            String p = nodeappApkPath.trim();
            if (p.isEmpty()) return null;

            // absolute path
            if (p.startsWith("/")) return new File(p);

            // relative to /sdcard
            File external = Environment.getExternalStorageDirectory(); // /sdcard
            return new File(external, p);

        } catch (Exception e) {
            Log.e(TAG, "resolveNodeApkFile error: " + e.getMessage(), e);
            return null;
        }
    }

    // =========================
    // WiFi (legacy API; open network)
    // =========================

    private interface SimpleCallback { void run(); }

    private void ensureWifiConnected(String ssidPlain, SimpleCallback onConnected) {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            Log.e(TAG, "WifiManager is null");
            onConnected.run();
            return;
        }

        if (!wifi.isWifiEnabled()) {
            wifi.setWifiEnabled(true);
        }

        final String quotedSsid = "\"" + ssidPlain + "\"";

        // already connected?
        try {
            String cur = (wifi.getConnectionInfo() != null) ? wifi.getConnectionInfo().getSSID() : null;
            if (cur != null && cur.equals(quotedSsid)) {
                Log.i(TAG, "Already connected to WiFi: " + ssidPlain);
                onConnected.run();
                return;
            }
        } catch (Exception ignored) {}

        int netId = findOrAddOpenNetwork(wifi, quotedSsid);
        if (netId == -1) {
            Log.e(TAG, "Failed to find/add WiFi network: " + ssidPlain);
            handler.postDelayed(() -> ensureWifiConnected(ssidPlain, onConnected), WIFI_RECHECK_MS);
            return;
        }

        boolean enabled = wifi.enableNetwork(netId, true);
        wifi.reconnect();

        Log.i(TAG, "WiFi enableNetwork(" + netId + ")=" + enabled + ", reconnect requested");

        handler.postDelayed(() -> {
            try {
                String cur = (wifi.getConnectionInfo() != null) ? wifi.getConnectionInfo().getSSID() : null;
                if (cur != null && cur.equals(quotedSsid)) {
                    Log.i(TAG, "Connected to WiFi: " + ssidPlain);
                    onConnected.run();
                    return;
                }
            } catch (Exception ignored) {}

            ensureWifiConnected(ssidPlain, onConnected);
        }, WIFI_RECHECK_MS);
    }

    private int findOrAddOpenNetwork(WifiManager wifi, String quotedSsid) {
        try {
            List<WifiConfiguration> configs = wifi.getConfiguredNetworks();
            if (configs != null) {
                for (WifiConfiguration c : configs) {
                    if (quotedSsid.equals(c.SSID)) return c.networkId;
                }
            }

            WifiConfiguration wc = new WifiConfiguration();
            wc.SSID = quotedSsid;
            wc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            return wifi.addNetwork(wc);

        } catch (Exception e) {
            Log.e(TAG, "findOrAddOpenNetwork error: " + e.getMessage(), e);
            return -1;
        }
    }

    // =========================
    // Install & Launch NodeApp
    // =========================

    private boolean isPackageInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void installApk(File apkFile) {
        try {
            // Standard installer UI (no ADB; no silent install)
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.i(TAG, "Triggered installer for: " + apkFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "installApk failed: " + e.getMessage(), e);
            installTriggered = false; // allow retry if it failed to even start
        }
    }

    private void launchNodeApp() {
        if (launchAttempted) return;
        launchAttempted = true;

        try {
            Intent intent = new Intent();
            intent.setClassName(NODE_APP_PACKAGE, NODE_APP_MAIN_ACTIVITY);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            Log.i(TAG, "Launching NodeApp...");
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch NodeApp: " + e.getMessage(), e);
            launchAttempted = false;
        }
    }

    // =========================
    // Device Owner / Kiosk
    // =========================

    private void ensurePoliciesIfDeviceOwner() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, KioskDeviceAdminReceiver.class);

        if (dpm == null) return;

        if (!dpm.isDeviceOwnerApp(getPackageName())) {
            Log.w(TAG, "Not device owner. Cannot force HOME or LockTask packages.");
            return;
        }

        // 1) Force our activity as default HOME
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
            filter.addCategory(Intent.CATEGORY_HOME);
            filter.addCategory(Intent.CATEGORY_DEFAULT);
            ComponentName home = new ComponentName(getPackageName(), KioskActivity.class.getName());
            dpm.addPersistentPreferredActivity(admin, filter, home);
            Log.i(TAG, "Set persistent preferred HOME to KioskActivity");
        } catch (Exception e) {
            Log.e(TAG, "addPersistentPreferredActivity failed: " + e.getMessage(), e);
        }

        // 2) Allow LockTask for our app + NodeApp
        try {
            dpm.setLockTaskPackages(admin, new String[]{ getPackageName(), NODE_APP_PACKAGE });
        } catch (Exception e) {
            Log.e(TAG, "setLockTaskPackages failed: " + e.getMessage(), e);
        }

        // 3) Optional restrictions (do NOT touch USB file transfer here!)
        try {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_ADJUST_VOLUME);
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_BRIGHTNESS);
        } catch (Exception ignored) {}
    }

    private void finalizeKioskAfterSuccess() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, KioskDeviceAdminReceiver.class);

        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
            // Disable USB file transfer only AFTER provisioning is done
            try {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER);
                Log.i(TAG, "USB file transfer disabled (charging-only behavior).");
            } catch (Exception e) {
                Log.e(TAG, "DISALLOW_USB_FILE_TRANSFER failed: " + e.getMessage(), e);
            }

            // Start lock task (kiosk)
            try {
                startLockTask();
                Log.i(TAG, "LockTask started.");
            } catch (Exception e) {
                Log.e(TAG, "startLockTask failed: " + e.getMessage(), e);
            }
        }
    }

    // =========================
    // UI / Hardening
    // =========================

    private void keepScreenOn() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void forceMaxBrightness() {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = 1.0f;
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {}
    }

    /**
     * FIXED: Lenovo Android 13 can return null insets controller early in onCreate.
     * We use decorView.getWindowInsetsController() and retry if null.
     */
    private void enableImmersiveModeSafe() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                View decor = getWindow().getDecorView();
                if (decor == null) return;

                WindowInsetsController controller = decor.getWindowInsetsController();
                if (controller == null) {
                    // Not ready yet on some OEM builds; retry shortly
                    handler.postDelayed(this::enableImmersiveModeSafe, 200);
                    return;
                }

                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            } else {
                View decorView = getWindow().getDecorView();
                if (decorView == null) return;

                decorView.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                );
            }
        } catch (Throwable t) {
            Log.e(TAG, "enableImmersiveModeSafe failed", t);
        }
    }

    // swallow volume keys
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

