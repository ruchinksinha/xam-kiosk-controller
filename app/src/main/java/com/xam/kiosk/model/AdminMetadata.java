package com.xam.kiosk.model;

public class AdminMetadata {
    private String ssid;
    private String nodeappApkPath;

    public AdminMetadata() {
    }

    public AdminMetadata(String ssid, String nodeappApkPath) {
        this.ssid = ssid;
        this.nodeappApkPath = nodeappApkPath;
    }

    public String getSsid() {
        return ssid;
    }

    public void setSsid(String ssid) {
        this.ssid = ssid;
    }

    public String getNodeappApkPath() {
        return nodeappApkPath;
    }

    public void setNodeappApkPath(String nodeappApkPath) {
        this.nodeappApkPath = nodeappApkPath;
    }
}
