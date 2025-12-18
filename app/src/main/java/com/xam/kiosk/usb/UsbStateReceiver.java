package com.xam.kiosk.usb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class UsbStateReceiver extends BroadcastReceiver {

    private static final String TAG = "UsbStateReceiver";
    private static final String ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        if (!ACTION_USB_STATE.equals(intent.getAction())) return;

        boolean connected = intent.getBooleanExtra("connected", false);
        if (!connected) return;

        Log.i(TAG, "USB connected -> attempting to force MTP");

        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c",
                    "setprop vendor.usb.config none; " +
                    "setprop sys.usb.config none; " +
                    "sleep 1; " +
                    "setprop vendor.usb.config mtp; " +
                    "setprop sys.usb.config mtp"
            });
        } catch (Exception e) {
            Log.e(TAG, "forceMtp failed", e);
        }
    }
}

