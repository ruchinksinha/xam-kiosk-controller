package com.xam.kiosk.boot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.xam.kiosk.ui.KioskActivity;

public class BootCompletedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent i = new Intent(context, KioskActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }
}
