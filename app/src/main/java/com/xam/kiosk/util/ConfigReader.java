package com.xam.kiosk.util;

import android.util.Log;

import com.xam.kiosk.model.Config;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

public class ConfigReader {

    // Host will copy config here via MTP:
    // /sdcard/xam/config.json
    public static final String CONFIG_PATH = "/sdcard/xam/config.json";

    public static Config readConfig() {
        try {
            File f = new File(CONFIG_PATH);
            if (!f.exists()) {
                Log.i("ConfigReader", "Config not found: " + CONFIG_PATH);
                return null;
            }

            byte[] data;
            try (FileInputStream fis = new FileInputStream(f)) {
                data = new byte[(int) f.length()];
                int read = fis.read(data);
                if (read <= 0) return null;
            }

            String json = new String(data, StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(json);

            String ssid = o.optString("ssid", "").trim();
            String apkPath = o.optString("nodeapp_apk_path", "").trim();

            if (ssid.isEmpty() || apkPath.isEmpty()) {
                Log.e("ConfigReader", "Config missing required fields: " + json);
                return null;
            }

            return new Config(ssid, apkPath);

        } catch (Exception e) {
            Log.e("ConfigReader", "Failed to read config: " + e.getMessage(), e);
            return null;
        }
    }
}

