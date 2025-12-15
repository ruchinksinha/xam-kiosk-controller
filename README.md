# xam-kiosk-controller

Android Kiosk Controller with USB Configuration Support

## Features

- Device lockdown in kiosk mode
- Auto-launch on boot
- USB file transfer support
- Auto WiFi connection from config file
- Maximum brightness and volume enforcement
- Node.js app launcher

## USB Configuration Workflow

### 1. Connect USB Cable
When you connect a USB cable to the device:
- USB file transfer (MTP) is automatically enabled
- Device is ready to receive files via USB

### 2. Transfer Configuration File
While USB is connected, place the `admin_metadata.json` file in:
```
/data/data/com.xam.kiosk/files/admin_metadata.json
```

Using ADB:
```bash
adb push admin_metadata.json /data/data/com.xam.kiosk/files/
```

### 3. Configuration File Format
```json
{
  "ssid": "Office_WiFi_5G",
  "nodeapp_apk_path": "/home/user/builds/nodeapp-release.apk"
}
```

### 4. Disconnect USB
When you disconnect the USB cable:
- App automatically reads `admin_metadata.json`
- Connects to the WiFi network specified in the `ssid` field
- Device is ready for kiosk operation

## Device Setup

1. Install app as device owner:
```bash
adb shell dpm set-device-owner com.xam.kiosk/.admin.KioskDeviceAdminReceiver
```

2. Grant required permissions:
```bash
adb shell pm grant com.xam.kiosk android.permission.WRITE_SETTINGS
adb shell pm grant com.xam.kiosk android.permission.WRITE_SECURE_SETTINGS
```

3. Connect USB and transfer config file
4. Disconnect USB to activate configuration