package com.xam.kiosk.usb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.util.Log;
import android.widget.Toast;

import com.xam.kiosk.model.AdminMetadata;
import com.xam.kiosk.util.ConfigReader;

import java.util.List;

public class UsbConnectionReceiver extends BroadcastReceiver {
    private static final String TAG = "UsbConnectionReceiver";
    private static final String ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (ACTION_USB_STATE.equals(action)) {
            boolean connected = intent.getBooleanExtra("connected", false);
            boolean configured = intent.getBooleanExtra("configured", false);

            if (connected) {
                Log.i(TAG, "USB connected - File transfer enabled");
            } else {
                Log.i(TAG, "USB disconnected - Reading config and connecting to WiFi");
                handleUsbDisconnected(context);
            }
        }
    }

    private void handleUsbDisconnected(Context context) {
        AdminMetadata metadata = ConfigReader.readAdminMetadata(context);

        if (metadata == null) {
            Log.w(TAG, "No config file found");
            return;
        }

        if (metadata.getSsid() == null || metadata.getSsid().isEmpty()) {
            Log.w(TAG, "No SSID in config file");
            return;
        }

        Log.i(TAG, "Connecting to WiFi: " + metadata.getSsid());
        connectToWifi(context, metadata.getSsid());
    }

    private void connectToWifi(Context context, String ssid) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);

        if (wifiManager == null) {
            Log.e(TAG, "WifiManager not available");
            return;
        }

        String quotedSsid = "\"" + ssid + "\"";

        if (!wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
        }

        WifiConfiguration targetConfig = null;
        List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();

        if (configs != null) {
            for (WifiConfiguration c : configs) {
                if (quotedSsid.equals(c.SSID)) {
                    targetConfig = c;
                    break;
                }
            }
        }

        if (targetConfig == null) {
            WifiConfiguration wc = new WifiConfiguration();
            wc.SSID = quotedSsid;
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
                Log.e(TAG, "Failed to add WiFi network: " + ssid);
                return;
            }
            boolean enabled = wifiManager.enableNetwork(netId, true);
            if (!enabled) {
                Log.e(TAG, "Failed to enable new WiFi network: " + ssid);
                return;
            }
            Log.i(TAG, "Added new WiFi network: " + ssid);
        } else {
            boolean enabled = wifiManager.enableNetwork(targetConfig.networkId, true);
            if (!enabled) {
                Log.e(TAG, "Failed to enable existing WiFi network: " + ssid);
                return;
            }
            Log.i(TAG, "Enabled existing WiFi network: " + ssid);
        }

        wifiManager.reconnect();
        Log.i(TAG, "WiFi connection initiated");
    }
}

