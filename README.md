# Blind Guide Optimized — build and use

This ZIP contains the complete Android Studio project (`BlindGuideAndroid`), the ESP32 PlatformIO project (`firmware-platformio`), and a prebuilt debug APK (`BlindGuideOptimized-debug.apk`). Do **not** create a new Android Studio project or copy individual Java files into an old project. Open the supplied project folder itself.

## What changed

- The Android app explicitly connects over Bluetooth Low Energy (BLE), waits for service discovery and alert subscription, times out failed attempts, closes stale connections, retries with backoff, and displays the actual connection state. A **Retry ESP32** button is included.
- The first destination prompt no longer waits for a slow GPS reverse-address lookup. You can speak a destination or type one. The screen has larger controls and separate navigation, ESP32, and voice statuses.
- Obstacle reports are grouped briefly so simultaneous front/left/right warnings say **Obstacle everywhere**. Repeats of the same condition have a **10-second cooldown**. A changed/new direction can be announced sooner.
- **Stop everything**, closing the app task, or saying **stop everything** while the app is listening stops its BLE service and obstacle speech. Saying **start location** while the app is listening asks for a new destination. The phone cannot listen for these phrases after the app is closed or while another app owns the microphone.
- The included ESP32 firmware has the matching service/characteristic UUIDs and restarts advertising outside its BLE callback after a disconnect.

## Important safety note

This is an experimental prototype, **not a dependable mobility or collision-avoidance device**. Do not rely on it in traffic, near stairs, ledges, water, or other hazards. Ultrasonic sensors can miss glass, thin or angled objects, fabric, drop-offs, and moving objects. Test indoors with a sighted helper and continue using your usual mobility aid. The 10-second repeat interval is not a safety guarantee.

## Step 1 — extract into New folder

1. Right-click `BlindGuideOptimized.zip` and choose **Extract All**.
2. Choose `C:\Users\Fahimur\Desktop\New folder` as the destination. You should end up with `C:\Users\Fahimur\Desktop\New folder\BlindGuideOptimized\BlindGuideAndroid\settings.gradle.kts` and the `firmware-platformio` folder beside it. If Windows makes a second nested `BlindGuideOptimized` folder, open the inner folder.
3. Keep this ZIP private. `BlindGuideAndroid\local.properties` includes the Maps API key and the Android SDK path copied from your PC. If you share the project, remove the key first.

## Step 2 — install on the phone (easiest)

1. Connect the Samsung phone with a **data-capable USB cable**, unlock it, and accept the phone's **Allow USB debugging** prompt. Leave Bluetooth and Location turned on.
2. In the extracted folder, double-click `BlindGuideOptimized-debug.apk`. If Windows cannot send it to the phone directly, use Android Studio in Step 3, or open a terminal in `BlindGuideAndroid` and run:

   ```powershell
   & "C:\Users\Fahimur\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices
   & "C:\Users\Fahimur\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r "..\BlindGuideOptimized-debug.apk"
   ```

3. On the phone, open **Blind Guide Prototype**, allow **Precise location**, **Nearby devices**, **Microphone**, and **Notifications** if prompted. You do not need to pair the ESP32 in Android Bluetooth Settings. The app scans for `BlindGuide-ESP32` and connects automatically.
4. Wait for **ESP32 connected; obstacle alerts active**. If it shows a retry message, tap **Retry ESP32** after checking the ESP32 power.

## Step 3 — build the Android project in Android Studio

1. Open Android Studio and choose **File > Open**. Select `C:\Users\Fahimur\Desktop\New folder\BlindGuideOptimized\BlindGuideAndroid`, **not** the outer folder and not an individual `.java` file.
2. Trust the project if asked. Click **Sync Now** in the blue banner, then wait for the sync to finish. The Gradle version notice is informational; do not change Gradle just because a newer version is offered.
3. Make sure the phone is unlocked and appears in the device selector at the top. Select the `app` run configuration and click the green **Run ▶** button. Android Studio will build and install the app. You do **not** upload the Android app to the ESP32.
4. To create an APK without running it, use **Build > Build Bundle(s) / APK(s) > Build APK(s)**. The output is `BlindGuideAndroid\app\build\outputs\apk\debug\app-debug.apk`.
5. If the device selector says **offline**, reconnect the USB cable, unlock the phone, accept the USB debugging prompt, and try `adb devices` again. A Gradle **BUILD SUCCESSFUL** message means compilation succeeded; it does not by itself mean installation succeeded.

The package name remains `com.example.blindguideprototype1`, so this is an update to the prior app and keeps its permissions. This project is configured for the Android SDK installed on this PC. On a different PC, update `BlindGuideAndroid\local.properties` with that PC's SDK path and Maps API key.

## Step 4 — ESP32 firmware (optional for the currently connected board)

I verified the new Android APK connected and subscribed to alerts from the ESP32 with its **existing firmware**. Therefore, you do **not** need to upload the firmware again just to fix this connection. The updated firmware source is included for a clean matching build or a replacement ESP32.

If you choose to upload it:

1. Install **VS Code** and its **PlatformIO IDE** extension.
2. In VS Code, choose **PlatformIO: Open Project** and select `C:\Users\Fahimur\Desktop\New folder\BlindGuideOptimized\firmware-platformio`.
3. Connect the ESP32 itself by USB. Check Windows Device Manager for its COM port. The phone and the ESP32 are different USB devices.
4. Click PlatformIO **Build** first, then **Upload**. If upload cannot find the port, add `upload_port = COMx` under `[env:esp32dev]` in `platformio.ini`, replacing `COMx` with the ESP32's actual port. Some ESP32 boards need the **BOOT** button held briefly when upload begins.
5. Open PlatformIO **Serial Monitor** at **115200 baud**. It should print `BlindGuide sensor unit ready`, sensor distances, and `Phone connected` when the app connects.

The board setting is `esp32dev`. If you own a different ESP32 board, change the `board` entry to its correct PlatformIO board ID. **Do not use a 5 V ultrasonic ECHO pin directly on the ESP32's 3.3 V input.** Use the resistor dividers described below.

| Sensor | TRIG | ECHO |
|---|---:|---:|
| Front | GPIO 21 | GPIO 19 |
| Left | GPIO 33 | GPIO 39 |
| Right | GPIO 32 | GPIO 36 |
| Low | GPIO 5 | GPIO 18 |

For common 5 V HC-SR04-style sensors, share GND with the ESP32 and put a divider on **each** ECHO: ECHO → 1 kΩ → ESP32 GPIO; ESP32 GPIO → 2 kΩ → GND. Confirm your exact sensor's voltage requirements. GPIO 36 and 39 are input-only, suitable for ECHO.

## Use and troubleshooting

- Start the ESP32 first, then open the app. The app will ask where you want to go. Say a clear destination with an area, confirm it, or type it and tap **Start walking directions**. If in-app navigation is unavailable, the app opens Google Maps walking directions.
- While the app is on screen and listening, say **start location** to set a new destination or **stop everything** to close and silence it. Use the visible buttons whenever voice recognition misses a phrase. Android speech recognition is not a guaranteed always-on wake-word system.
- **Searching for BlindGuide-ESP32…**: check ESP32 power and proximity. Only one phone can be connected to this BLE service at a time. Close another BLE scanner/app that may own it.
- **Connection failed (GATT 133)** or **connection timed out**: the app retries automatically. Power-cycle the ESP32, wait for its advertising to restart, turn phone Bluetooth off/on, then tap **Retry ESP32**. The app and firmware must use the matching UUIDs supplied here.
- **ESP32 connected; obstacle alerts active** means the phone connected to the service and enabled BLE notifications. It does not prove every sensor is wired/calibrated correctly. Test each sensor separately and confirm left/right are not swapped.
- **Walking directions do not start**: grant precise Location, turn on GPS, check mobile data/Google Maps, and enter a more specific destination. The Maps API key must be enabled and authorized for this app if using in-app Navigation SDK; the Google Maps URL fallback can still open directions.
- **Voice does not hear you**: grant Microphone, check the phone's speech-recognition service/language and internet connection, lower background noise, or type a destination. Voice commands are available only when the app is active and listening; they cannot wake an app that has been closed.

Official technical references used: [Android BLE scan guidance](https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices), [Android GATT connections](https://developer.android.com/develop/connectivity/bluetooth/ble/connect-gatt-server), [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions), [Android SpeechRecognizer limitations](https://developer.android.com/reference/android/speech/SpeechRecognizer), [Google Maps walking URLs](https://developers.google.com/maps/documentation/urls/get-started), and [Espressif Arduino BLE](https://docs.espressif.com/projects/arduino-esp32/en/latest/api/ble.html).
