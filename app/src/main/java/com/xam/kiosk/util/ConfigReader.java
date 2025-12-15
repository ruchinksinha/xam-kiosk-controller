package com.xam.kiosk.util;

import android.content.Context;
import android.util.Log;

import com.xam.kiosk.model.AdminMetadata;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

public class ConfigReader {
    private static final String TAG = "ConfigReader";
    private static final String CONFIG_FILENAME = "admin_metadata.json";

    public static AdminMetadata readAdminMetadata(Context context) {
        File configFile = new File(context.getFilesDir(), CONFIG_FILENAME);

        if (!configFile.exists()) {
            Log.w(TAG, "Config file not found at: " + configFile.getAbsolutePath());
            return null;
        }

        try {
            FileInputStream fis = new FileInputStream(configFile);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
            StringBuilder jsonBuilder = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                jsonBuilder.append(line);
            }

            reader.close();
            fis.close();

            JSONObject jsonObject = new JSONObject(jsonBuilder.toString());

            AdminMetadata metadata = new AdminMetadata();

            if (jsonObject.has("ssid")) {
                metadata.setSsid(jsonObject.getString("ssid"));
            }

            if (jsonObject.has("nodeapp_apk_path")) {
                metadata.setNodeappApkPath(jsonObject.getString("nodeapp_apk_path"));
            }

            Log.i(TAG, "Successfully loaded config: SSID=" + metadata.getSsid() +
                      ", APK Path=" + metadata.getNodeappApkPath());

            return metadata;

        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse JSON config file", e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to read config file", e);
            return null;
        }
    }
}
