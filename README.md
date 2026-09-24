# Path Finder (Blind Guide Prototype)

Path Finder is an experimental assistive-navigation prototype made from two connected parts:

1. An **ESP32 sensor unit** reads four ultrasonic distance sensors and sends obstacle information over Bluetooth Low Energy (BLE).
2. An **Android app** connects to the ESP32, speaks obstacle warnings, accepts a destination by voice or text, and provides walking directions with the Google Navigation SDK (falling back to Google Maps when necessary).

A prebuilt Android package, `PATH FINDER.apk`, is included for quick testing. The complete Android and ESP32 source code is included so the system can also be rebuilt or modified from scratch.

> [!CAUTION]
> This is a prototype, **not a certified mobility or safety device**. Never rely on it as the only way to detect traffic, stairs, drop-offs, glass, water, moving objects, or other hazards. Ultrasonic sensors have blind spots and can miss thin, soft, angled, or sound-absorbing objects. Test indoors with a sighted helper and continue using an appropriate primary mobility aid.

## Table of contents

- [What the project does](#what-the-project-does)
- [How the complete system works](#how-the-complete-system-works)
- [Project structure](#project-structure)
- [Hardware required](#hardware-required)
- [Wire the ESP32 and sensors](#wire-the-esp32-and-sensors)
- [Upload the ESP32 firmware](#upload-the-esp32-firmware)
- [Configure and build the Android app](#configure-and-build-the-android-app)
- [Install the included APK](#install-the-included-apk)
- [Use the system](#use-the-system)
- [BLE message protocol](#ble-message-protocol)
- [How the code works](#how-the-code-works)
- [Test the project safely](#test-the-project-safely)
- [Calibration and customization](#calibration-and-customization)
- [Troubleshooting](#troubleshooting)
- [Known limitations](#known-limitations)
- [Official references](#official-references)

## What the project does

- Detects obstacles in four zones: **front**, **left**, **right**, and **low/front**.
- Announces warnings such as “Obstacle ahead,” “Obstacle left,” or “Obstacles ahead and right.”
- Combines nearly simultaneous alerts into one useful spoken warning.
- Automatically searches for and reconnects to the ESP32.
- Accepts a walking destination by speech or by typing.
- Confirms spoken destinations before starting navigation.
- Shows an in-app map and starts voice walking guidance when the Navigation SDK is available.
- Opens Google Maps walking directions as a fallback if in-app navigation cannot start.
- Recognizes commands such as **“end session,” “start location,”** and **“stop everything”** while the app is active and listening.

## How the complete system works

```text
Four ultrasonic sensors
        |
        | distance measurements
        v
ESP32 firmware
  - checks one sensor at a time
  - applies obstacle thresholds and hysteresis
  - creates messages such as FRONT,45
        |
        | BLE notifications
        v
Android SensorService
  - scans for BlindGuide-ESP32
  - connects and subscribes to alerts
  - remembers which directions are blocked
  - speaks combined obstacle warnings
        |
        | connection status broadcasts
        v
Android MainActivity
  - asks for a destination
  - converts the name to coordinates
  - starts walking guidance
  - handles buttons and voice commands
```

The obstacle-warning system and the route-guidance system are independent. The app can continue trying to connect to the ESP32 while the user enters a destination. Similarly, a valid BLE connection does not prove that the sensors are wired or aimed correctly.

## Project structure

```text
path_finder/
├── README.md                       # This guide
├── PATH FINDER.apk                 # Prebuilt Android app
├── firmware-platformio/            # ESP32 PlatformIO project
│   ├── platformio.ini              # Board, framework, upload, and monitor settings
│   └── src/
│       └── main.cpp                # Sensor readings, thresholds, BLE server, messages
└── PathFinderAndroid/              # Android Studio/Gradle project
    ├── settings.gradle.kts
    ├── build.gradle.kts
    ├── gradlew / gradlew.bat       # Gradle wrapper scripts
    ├── gradle/                     # Gradle version and dependency catalog
    └── app/
        ├── build.gradle.kts        # Android versions and dependencies
        └── src/main/
            ├── AndroidManifest.xml # Permissions, API key placeholder, service registration
            ├── java/com/example/blindguideprototype1/
            │   ├── MainActivity.java
            │   └── SensorService.java
            └── res/layout/
                └── activity_navigation.xml
```

`activity_navigation.xml` is the screen used by the app. `activity_main.xml` is an unused starter layout and can be removed later if desired.

## Hardware required

The existing firmware is written for the following setup:

- 1 ESP32 development board compatible with PlatformIO's `esp32dev` board definition
- 4 ultrasonic distance sensors (for example, HC-SR04-style 5 V modules)
- 8 resistors for four ECHO voltage dividers:
  - 4 × 1 kΩ
  - 4 × 2 kΩ
- Breadboard or a secure soldered/prototyping board
- Jumper wires
- A stable power supply suitable for the ESP32 and all sensors
- A data-capable USB cable for programming the ESP32
- An Android phone with:
  - Android 8.0 (API 26) or newer
  - Bluetooth Low Energy
  - Google Play services for in-app Navigation SDK features
  - GPS/location, microphone, speaker, and internet access
- A Windows, macOS, or Linux computer for building the projects

The Android project currently uses Java 11 source compatibility, Android Gradle Plugin 9.4.1, Gradle 9.6.0, compile/target SDK 37, and minimum SDK 26.

## Wire the ESP32 and sensors

### ESP32 pin assignment

| Sensor position | TRIG pin | ECHO pin | Obstacle starts below | Clears above |
|---|---:|---:|---:|---:|
| Front | GPIO 21 | GPIO 19 | 70 cm | 82 cm |
| Left | GPIO 33 | GPIO 39 | 40 cm | 50 cm |
| Right | GPIO 32 | GPIO 36 | 40 cm | 50 cm |
| Low/front | GPIO 5 | GPIO 18 | 95 cm | 110 cm |

The different start and clear distances are intentional. This is called **hysteresis**: once an obstacle is detected, it must move farther away than the original trigger distance before the alert clears. That prevents rapid on/off warnings when a measurement moves slightly around a threshold.

### Connect each sensor

For each common 5 V HC-SR04-style sensor:

1. Connect the sensor's `VCC` to a suitable 5 V supply.
2. Connect the sensor's `GND` to ground.
3. Connect its `TRIG` pin to the ESP32 GPIO shown in the table.
4. **Do not connect a 5 V ECHO signal directly to an ESP32 input.** The ESP32 uses 3.3 V logic.
5. Make a voltage divider for ECHO:

```text
Sensor ECHO ---- 1 kΩ ----+---- ESP32 ECHO GPIO
                          |
                         2 kΩ
                          |
                         GND
```

6. Make sure the sensor supply and ESP32 share a **common ground**.
7. Repeat the divider for every 5 V ECHO output.

GPIO 36 and GPIO 39 are input-only pins, which is appropriate for ECHO. Confirm the voltage, pinout, current requirement, and timing of the exact sensors and ESP32 board you own; not every ultrasonic module is identical.

### Physical placement

- Aim the front sensor horizontally ahead.
- Aim the left and right sensors outward enough to cover their intended sides without pointing at the user's body.
- Mount the low sensor to cover low obstacles in front. Its best angle and threshold depend on mounting height.
- Keep sensors separated and firmly mounted. Loose sensors produce unstable readings.
- Do not permanently assemble the unit until every sensor has been tested individually.

## Upload the ESP32 firmware

### Beginner-friendly method: VS Code and PlatformIO

1. Install [Visual Studio Code](https://code.visualstudio.com/).
2. In VS Code, open **Extensions**, search for **PlatformIO IDE**, and install it.
3. Choose **File → Open Folder** and select `firmware-platformio`.
4. Connect the ESP32 to the computer with a data-capable USB cable.
5. In the PlatformIO toolbar, click **Build**. The first build downloads the ESP32 toolchain and can take several minutes.
6. Click **Upload**.
7. If PlatformIO cannot find the board, open Device Manager (Windows) or list the serial devices on your operating system. Add the detected port to `platformio.ini`, for example:

   ```ini
   upload_port = COM5
   ```

8. Some ESP32 boards require holding the **BOOT** button when the upload begins and releasing it after writing starts.
9. Open **Serial Monitor** at `115200` baud.

Expected output includes:

```text
BlindGuide sensor unit ready
FRONT: ... cm
LEFT: ... cm
RIGHT: ... cm
LOW: ... cm
```

When the phone connects, the monitor prints `Phone connected`. Move an object toward one sensor at a time and verify that its distance falls and the word `OBSTACLE` appears.

### PlatformIO command-line method

From the `firmware-platformio` directory:

```bash
pio run
pio run --target upload
pio device monitor --baud 115200
```

The project uses the Arduino framework. Its BLE headers come with the Espressif Arduino platform, so there is no separate BLE dependency in `platformio.ini`.

## Configure and build the Android app

### 1. Install Android Studio

Install [Android Studio](https://developer.android.com/studio) and allow its Setup Wizard to install the recommended Android SDK, platform tools, and build tools. Internet access is required the first time Gradle downloads dependencies.

### 2. Open the supplied project

1. Start Android Studio.
2. Choose **Open** (or **File → Open**).
3. Select the `PathFinderAndroid` folder—the folder containing `settings.gradle.kts`.
4. Do not create a blank project and do not open only a `.java` file.
5. Trust the project if prompted and wait for Gradle sync to finish.

### 3. Create a Google Maps Platform key

In-app walking navigation needs a Google Cloud project with billing configured, the **Navigation SDK for Android** enabled, and an API key. Google can change its console screens and pricing, so check the current [Navigation SDK setup guide](https://developers.google.com/maps/documentation/navigation/android-sdk/get-api-key) while doing this.

For this source code:

- Android package/application ID: `com.example.blindguideprototype1`
- The dependency is `com.google.android.libraries.navigation:navigation:7.9.0`.
- The API key is inserted into the manifest through the `MAPS_API_KEY` property.

Recommended setup:

1. Create or select a Google Cloud project.
2. Attach an appropriate billing account.
3. Enable **Navigation SDK for Android**.
4. Create an API key.
5. Restrict the key to Android apps.
6. Add the package name `com.example.blindguideprototype1` and the SHA-1 certificate fingerprint for the key used to sign the app.
7. Restrict the key's API access to the required Navigation SDK.

To obtain the debug SHA-1 fingerprint, open a terminal in `PathFinderAndroid` and run:

```powershell
.\gradlew.bat signingReport
```

On macOS or Linux, use:

```bash
./gradlew signingReport
```

Copy the SHA-1 shown under the `debug` variant. A release build has a different signing certificate and therefore needs its release SHA-1 added to the API-key restriction.

### 4. Store the key locally

Open `PathFinderAndroid/local.properties`. Android Studio normally creates the `sdk.dir` entry automatically. Add this separate line:

```properties
MAPS_API_KEY=PASTE_YOUR_KEY_HERE
```

Do not add quotation marks. Never publish `local.properties`, screenshots of the key, or a real unrestricted key. Before committing the project, make sure the repository's `.gitignore` contains:

```gitignore
/PathFinderAndroid/local.properties
```

If Git already tracks that file, adding it to `.gitignore` is not enough: remove it from Git's index without deleting the local copy, then rotate any exposed key. If a key has already been shared publicly, restricting or rotating it in Google Cloud is necessary; merely deleting it from the latest commit does not remove it from history.

If no valid key is provided, the app is designed to try opening Google Maps walking directions when in-app navigation is unavailable, but the complete in-app experience will not work.

### 5. Build the app

In Android Studio, choose **Build → Build APK(s)**. Alternatively, from `PathFinderAndroid` run:

```powershell
.\gradlew.bat assembleDebug
```

On macOS or Linux:

```bash
./gradlew assembleDebug
```

The generated APK is normally located at:

```text
PathFinderAndroid/app/build/outputs/apk/debug/app-debug.apk
```

### 6. Run on a physical phone

A real phone is strongly recommended because the project needs BLE, GPS, a microphone, speech output, and physical sensor hardware.

1. On the phone, enable **Developer options** and **USB debugging**.
2. Connect it with a data-capable USB cable.
3. Unlock the phone and accept the **Allow USB debugging** prompt.
4. Select the phone in Android Studio's device selector.
5. Select the `app` run configuration and click **Run ▶**.
6. Grant **Precise location**, **Nearby devices**, **Microphone**, and **Notifications** when requested.

The application ID is `com.example.blindguideprototype1`; installing another build with the same ID and compatible signature updates the existing installation.

## Install the included APK

If you only want to try the current build, copy `PATH FINDER.apk` to the phone and open it. Android may ask permission to install apps from the file manager or browser used to open it.

You can also install it with Android Debug Bridge. From `PathFinderAndroid` on Windows:

```powershell
adb devices
adb install -r "..\PATH FINDER.apk"
```

If `adb` is not on your `PATH`, use the copy inside your Android SDK's `platform-tools` directory or install through Android Studio.

Only install APKs that came from a source you trust. A prebuilt APK cannot automatically receive source-code changes; rebuild and reinstall after modifying the Android project.

## Use the system

1. Power the programmed ESP32.
2. Turn on Bluetooth, Location, and internet access on the phone.
3. Open **Blind Guide Prototype**.
4. Allow all requested permissions. BLE scanning on Android 12+ uses the **Nearby devices** permission; older supported Android versions also rely on location permission for BLE scanning.
5. The ESP32 does **not** need to be paired manually in Android's Bluetooth settings. The app searches for the advertised name `BlindGuide-ESP32` or the matching service UUID.
6. Wait for `ESP32 connected; obstacle alerts active` on the screen.
7. Say a destination after the prompt, or type a destination and press **Start walking directions**.
8. If using speech, answer **yes** or **no** when the app confirms the location.
9. Follow the walking guidance while a sighted helper supervises early tests.

Available controls:

| Control or phrase | Result |
|---|---|
| **Start walking directions** | Geocodes the typed destination and starts a walking route |
| **Speak destination / command** | Opens speech input or listens for a session command |
| **Retry ESP32** | Closes the current BLE connection and immediately starts a fresh attempt |
| **Stop everything** | Stops guidance, BLE service, speech, and closes the app task |
| “end session” | Stops the current route and asks whether to choose another destination |
| “start location” / “start navigation” | Starts the destination flow again |
| “stop everything” / “close app” | Stops all app activity and closes it |

Voice commands are not a system-wide wake word. The activity must be open, have focus, have microphone permission, and be in a state where command listening is enabled.

## BLE message protocol

The ESP32 is a BLE peripheral/server and the Android phone is the BLE central/client.

| Item | Value |
|---|---|
| Advertised device name | `BlindGuide-ESP32` |
| Service UUID | `7b7d1000-3f9b-4f6c-9d8c-2d4a7e901001` |
| Alert characteristic UUID | `7b7d1001-3f9b-4f6c-9d8c-2d4a7e901001` |
| Characteristic properties | Read and Notify |
| Client Configuration descriptor | Standard UUID `00002902-0000-1000-8000-00805f9b34fb` |

Messages are UTF-8 text:

```text
FRONT,45
LEFT,27
RIGHT,32
LOW,71
CLEAR_FRONT,85
CLEAR_LEFT,55
CLEAR_RIGHT,54
CLEAR_LOW,114
```

The number is the rounded distance in centimetres. The current Android app uses the message type before the comma and does not display the numeric distance.

Keep both source files synchronized if UUIDs or message names are changed:

- `firmware-platformio/src/main.cpp`
- `PathFinderAndroid/app/src/main/java/com/example/blindguideprototype1/SensorService.java`

## How the code works

### ESP32 firmware: `main.cpp`

1. Defines each sensor's name, TRIG pin, ECHO pin, enter distance, exit distance, blocked state, and last alert time.
2. Starts a BLE server named `BlindGuide-ESP32`.
3. Advertises the custom service and exposes one readable/notifiable characteristic.
4. Triggers only one ultrasonic sensor at a time to reduce cross-talk.
5. Converts echo time to distance with:

   ```text
   distance = echo duration × 0.0343 / 2
   ```

   `0.0343 cm/µs` approximates the speed of sound, and division by 2 accounts for the trip to the obstacle and back.

6. Treats a missing echo after 30 ms as `999 cm`, not as a nearby obstacle.
7. Sends an alert when a valid reading from 2 cm upward crosses below the enter threshold.
8. Repeats a still-blocked alert every 2.5 seconds.
9. Sends a direction-specific clear message after the reading crosses above the exit threshold.
10. Restarts advertising shortly after a phone disconnects.

### Android BLE service: `SensorService.java`

1. Runs as a foreground service and shows a persistent status notification.
2. Scans for up to 10 seconds at a time.
3. Matches the ESP32 by service UUID or advertised name.
4. Forces the BLE transport, opens a GATT connection, discovers services, and enables notifications.
5. Times out incomplete connections after 12 seconds.
6. Closes stale connections and retries failures with increasing delays up to 16 seconds.
7. Stores active directions as bits in `blockedMask`.
8. Waits 300 ms before speaking so alerts from several sensors can be combined.
9. Prevents the same unchanged combination from being spoken more than once every 10 seconds.
10. Broadcasts connection status to `MainActivity` and also stores the latest status in `SharedPreferences`.

### Android activity: `MainActivity.java`

1. Loads `activity_navigation.xml` and registers for ESP32 status updates.
2. Requests the runtime permissions needed for location, microphone, BLE, and notifications.
3. Initializes text-to-speech, location services, and the Google Navigation SDK.
4. Prompts for a destination immediately instead of waiting for a slow GPS address lookup.
5. Uses Android's `Geocoder` to turn a place name into coordinates.
6. Confirms spoken destinations with the user.
7. Requests a walking route and starts voice alerts and guidance.
8. Falls back to a Google Maps walking URL when the Navigation SDK or route is unavailable.
9. Stops navigation and clears destinations on arrival, session end, or full shutdown.

## Test the project safely

Build confidence one layer at a time instead of testing everything at once.

### Test 1: electrical checks

- Keep the phone and navigation app out of the test initially.
- Confirm correct supply voltage and common ground with a multimeter.
- Confirm every 5 V ECHO signal passes through a divider before reaching the ESP32.
- Check for short circuits before applying power.

### Test 2: individual sensor readings

- Upload the firmware and open the serial monitor.
- Test one sensor at a time with a large, flat object.
- Compare the printed value with a ruler or tape measure.
- Verify that front, left, right, and low are not swapped.

### Test 3: BLE connection

- Power the ESP32, open the Android app, and wait for the connected status.
- If possible, use a BLE inspection app during development to confirm the advertised name, service, characteristic, and notifications. Disconnect that app before testing Path Finder because only one client may be able to use the ESP32 at a time.

### Test 4: spoken obstacle alerts

- Keep the phone unlocked and the app open.
- Move a large object into one zone at a time.
- Then block two or three side sensors together and verify the combined warning.
- Move objects beyond the clear thresholds and confirm warnings stop.

### Test 5: navigation

- Test destination typing first, then speech.
- Use a short, familiar, low-risk indoor or controlled route with a sighted helper.
- Confirm that arrival, end-session, new-destination, and stop-everything flows behave as expected.

## Calibration and customization

### Change obstacle distances

Edit the `sensors` array near the top of `firmware-platformio/src/main.cpp`:

```cpp
{"FRONT", 21, 19, 70.0, 82.0, false, 0}
```

The two floating-point values are the **enter distance** and **exit distance** in centimetres. Keep the exit distance larger than the enter distance. Change one value at a time, upload again, and retest.

### Change pins

Change TRIG and ECHO numbers in the same array, then rewire the hardware. Check the ESP32 board's pin restrictions first. Avoid boot-strapping pins or pins connected to flash on your particular board unless you understand their behavior.

### Change the ESP32 name or UUIDs

Update both firmware and `SensorService.java`. If only one side is changed, the app will not discover or subscribe to the ESP32 correctly.

### Add another obstacle zone

Adding a sensor requires changes on both sides:

1. Add a `Sensor` entry and unique message name in the firmware.
2. Add a new bit flag in `SensorService`.
3. Handle its alert and clear message in `processAlert()`.
4. Decide how it combines with other directions in `announceObstacle()`.
5. Add wiring documentation and tests.

### Change app text or layout

- Main screen: `PathFinderAndroid/app/src/main/res/layout/activity_navigation.xml`
- Most runtime status and spoken phrases: `MainActivity.java` and `SensorService.java`
- Application label: `AndroidManifest.xml` and `res/values/strings.xml`

Many user-facing strings are currently hard-coded. Moving them into `res/values/strings.xml` would make translation and maintenance easier.

## Troubleshooting

### PlatformIO cannot upload

- Use a data-capable USB cable, not a charge-only cable.
- Close Serial Monitor before uploading if the port is busy.
- Select the correct COM/serial port.
- Install the USB-to-serial driver used by the board (commonly CP210x or CH340).
- Try holding **BOOT** when upload begins.
- If the board is not an `esp32dev`-compatible board, select its correct PlatformIO board ID.

### Serial Monitor shows `999.0 cm`

No valid echo arrived within 30 ms. Check VCC, ground, TRIG/ECHO wiring, the voltage divider, sensor direction, and whether the target is within range. A disconnected ECHO wire commonly causes this value.

### Readings jump or sensors trigger each other

- Increase physical separation or change sensor angles.
- Test sensors individually.
- Use a stable supply and short, secure wires.
- Increase the 55 ms delay after each reading if necessary.
- Soft, narrow, rounded, or angled objects may not return a stable echo.

### App says `Searching for BlindGuide-ESP32…`

- Confirm that the ESP32 is powered and running the supplied firmware.
- Check Serial Monitor for `BlindGuide sensor unit ready`.
- Turn on phone Bluetooth and grant Nearby devices/location permission.
- Move the phone closer.
- Disconnect BLE scanner apps or another phone.
- Tap **Retry ESP32**.

### `Connection failed (GATT 133)` or connection timeout

The service retries automatically. Power-cycle the ESP32, wait a few seconds, toggle Bluetooth off and on, and press **Retry ESP32**. Verify that Android and firmware use exactly the same service and characteristic UUIDs.

### Connected, but there are no obstacle warnings

- Confirm Serial Monitor shows the correct sensor becoming `OBSTACLE`.
- Raise media/navigation volume and verify Android text-to-speech works.
- Wait for the app to report `ESP32 connected; obstacle alerts active`, which means notification subscription finished.
- Remember that the app suppresses an unchanged spoken combination for 10 seconds even though firmware messages repeat every 2.5 seconds.

### Android build fails during Gradle sync

- Confirm that `PathFinderAndroid`, not the repository root, is open in Android Studio.
- Use Android Studio's bundled JDK unless the build specifically reports another requirement.
- Install the SDK platform requested by the project.
- Check internet access, because Gradle must download Google and Android dependencies.
- Do not replace the Gradle or plugin versions merely because an update is offered.
- Run `.\gradlew.bat assembleDebug --stacktrace` (Windows) to obtain a detailed error.

### Map is blank or Navigation SDK reports an error

- Check that `MAPS_API_KEY` exists in `local.properties` with no quotes.
- Confirm billing and Navigation SDK are enabled in the same Google Cloud project as the key.
- Confirm the key restriction contains the exact package name and correct signing SHA-1.
- Make sure the phone has Google Play services, location enabled, and internet access.
- Rebuild after changing `local.properties`.

### Phone does not appear in Android Studio

- Unlock it and accept the USB-debugging prompt.
- Try another data cable or USB port.
- Run `adb devices`.
- Install the phone manufacturer's Windows USB driver if needed.
- In Android Studio, open **Tools → Troubleshoot Device Connections**.

### Voice input does not work

- Grant microphone permission.
- Confirm a speech recognition service is installed and enabled.
- Check the phone's speech language and internet connection.
- Reduce background noise.
- Type the destination as a reliable fallback.
- Voice commands only run while the app is active and listening.

## Known limitations

- This is not a certified assistive, medical, or safety product.
- Ultrasonic ranging cannot reliably detect every material, shape, edge, or moving hazard.
- The system does not reliably detect drop-offs or stairs going down.
- Speed-of-sound changes with temperature and humidity, so distances are approximate.
- Four sensors can still interfere acoustically despite sequential triggering.
- Android speech recognition is not a guaranteed continuous wake-word service.
- The foreground BLE service stops when the app task is removed.
- Route quality, availability, usage limits, and billing depend on Google services.
- A physical Android phone is needed for meaningful end-to-end BLE testing.
- The included unit and instrumented test files are only starter examples; the important behavior is not yet covered by automated tests.
- The repository currently does not include an explicit open-source license. Add one before inviting reuse or redistribution, and verify Google Navigation SDK attribution and licensing requirements before release.

## Official references

- [Navigation SDK for Android overview and requirements](https://developers.google.com/maps/documentation/navigation/android-sdk/setup-overview)
- [Set up the Navigation SDK and API key](https://developers.google.com/maps/documentation/navigation/android-sdk/get-api-key)
- [Android BLE scanning](https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices)
- [Android GATT connections](https://developer.android.com/develop/connectivity/bluetooth/ble/connect-gatt-server)
- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Run an Android app on a hardware device](https://developer.android.com/studio/run/device)
- [PlatformIO IDE for VS Code](https://docs.platformio.org/en/stable/integration/ide/vscode.html)
- [Arduino-ESP32 BLE API](https://docs.espressif.com/projects/arduino-esp32/en/latest/api/ble.html)

---

For a first successful build, use this order: **wire one sensor → upload firmware → verify Serial Monitor → install the Android app → verify BLE → add and test the remaining sensors → configure navigation**. This makes faults much easier to find than assembling and debugging every part at once.
