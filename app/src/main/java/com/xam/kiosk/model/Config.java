package com.xam.kiosk.model;

public class Config {
    public final String ssid;
    public final String nodeappApkPath; // relative to /sdcard/

    public Config(String ssid, String nodeappApkPath) {
        this.ssid = ssid;
        this.nodeappApkPath = nodeappApkPath;
    }
}

