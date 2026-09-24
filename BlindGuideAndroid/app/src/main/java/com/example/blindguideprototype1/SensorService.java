package com.example.blindguideprototype1;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * BLE connection and obstacle speech live only while the app's task is active.
 * All connection state is owned by the main thread; Android's GATT callbacks
 * are posted there before touching the current connection.
 */
public final class SensorService extends Service implements TextToSpeech.OnInitListener {
    public static final String ACTION_STATUS = "com.example.blindguideprototype1.SENSOR_STATUS";
    public static final String ACTION_RETRY = "com.example.blindguideprototype1.RETRY_SENSOR";
    public static final String ACTION_STOP = "com.example.blindguideprototype1.STOP_SENSOR";
    public static final String EXTRA_STATUS = "status";
    public static final String PREFS = "sensor_connection";
    public static final String PREF_STATUS = "status";

    private static final String TAG = "BlindGuideBLE";
    private static final String CHANNEL_ID = "blind_guide_sensor";
    private static final int NOTIFICATION_ID = 41;
    private static final long SCAN_MS = 10_000L;
    private static final long CONNECT_MS = 12_000L;
    private static final long ALERT_COOLDOWN_MS = 10_000L;
    private static final long AGGREGATION_MS = 300L;
    private static final int FRONT = 1;
    private static final int LEFT = 2;
    private static final int RIGHT = 4;
    private static final int LOW = 8;
    private static final UUID SERVICE_UUID =
            UUID.fromString("7b7d1000-3f9b-4f6c-9d8c-2d4a7e901001");
    private static final UUID ALERT_UUID =
            UUID.fromString("7b7d1001-3f9b-4f6c-9d8c-2d4a7e901001");
    private static final UUID CCC_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private boolean scanning;
    private boolean ready;
    private boolean stopped;
    private int failures;
    private int blockedMask;
    private int lastSpokenMask;
    private long lastSpokenAt;
    private TextToSpeech tts;
    private boolean ttsReady;

    private final Runnable scanTimeout = () -> {
        if (!scanning) return;
        stopScan();
        retryAfterFailure("ESP32 not found");
    };
    private final Runnable connectionTimeout = () -> {
        if (gatt != null && !ready) retryAfterFailure("ESP32 connection timed out");
    };
    private final Runnable scanAgain = this::startScan;
    private final Runnable announce = this::announceObstacle;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification("Searching for ESP32…"));
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        tts = new TextToSpeech(this, this);
        publish("Searching for ESP32…");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RETRY.equals(action)) {
            failures = 0;
            handler.removeCallbacks(scanAgain);
            stopScan();
            closeGatt();
        }
        if (!stopped && !scanning && gatt == null) startScan();
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null || stopped) return;
        ttsReady = true;
        tts.setLanguage(Locale.getDefault());
        tts.setSpeechRate(1.03f);
        tts.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        if (blockedMask != 0) handler.postDelayed(announce, AGGREGATION_MS);
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (stopped || scanning || gatt != null) return;
        if (!hasBlePermissions()) {
            publish("Allow Nearby devices and precise location");
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            publish("Turn on Bluetooth");
            scheduleRetry(4_000L);
            return;
        }
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            publish("Bluetooth scanner unavailable");
            scheduleRetry(4_000L);
            return;
        }
        try {
            scanning = true;
            publish("Searching for BlindGuide-ESP32…");
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(null, settings, scanCallback);
            handler.postDelayed(scanTimeout, SCAN_MS);
        } catch (RuntimeException error) {
            scanning = false;
            retryAfterFailure("Bluetooth scan could not start");
            Log.w(TAG, "startScan", error);
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        handler.removeCallbacks(scanTimeout);
        if (!scanning) return;
        scanning = false;
        try {
            if (adapter != null && adapter.getBluetoothLeScanner() != null) {
                adapter.getBluetoothLeScanner().stopScan(scanCallback);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "stopScan", error);
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, @NonNull ScanResult result) {
            handler.post(() -> {
                if (stopped || !scanning || !isOurSensor(result)) return;
                stopScan();
                connect(result.getDevice());
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            handler.post(() -> {
                if (stopped) return;
                scanning = false;
                handler.removeCallbacks(scanTimeout);
                retryAfterFailure("BLE scan error " + errorCode);
            });
        }
    };

    private boolean isOurSensor(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        if (record == null) return false;
        List<ParcelUuid> services = record.getServiceUuids();
        return (services != null && services.contains(new ParcelUuid(SERVICE_UUID)))
                || "BlindGuide-ESP32".equals(record.getDeviceName());
    }

    @SuppressLint("MissingPermission")
    private void connect(BluetoothDevice device) {
        if (stopped) return;
        publish("Connecting to ESP32…");
        try {
            // The ESP32 is dual-mode hardware. AUTO can choose the wrong
            // transport; the phone log showed AUTO followed by GATT 133.
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                retryAfterFailure("Could not open BLE connection");
                return;
            }
            handler.postDelayed(connectionTimeout, CONNECT_MS);
        } catch (RuntimeException error) {
            Log.w(TAG, "connectGatt", error);
            retryAfterFailure("Could not connect to ESP32");
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(@NonNull BluetoothGatt callbackGatt,
                                            int status, int newState) {
            handler.post(() -> handleConnection(callbackGatt, status, newState));
        }

        @Override
        public void onServicesDiscovered(@NonNull BluetoothGatt callbackGatt, int status) {
            handler.post(() -> handleServices(callbackGatt, status));
        }

        @Override
        public void onDescriptorWrite(@NonNull BluetoothGatt callbackGatt,
                                      @NonNull BluetoothGattDescriptor descriptor, int status) {
            handler.post(() -> {
                if (stopped || callbackGatt != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    ready = true;
                    failures = 0;
                    handler.removeCallbacks(connectionTimeout);
                    publish("ESP32 connected; obstacle alerts active");
                } else retryAfterFailure("Alert subscription failed (" + status + ")");
            });
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt callbackGatt,
                                            @NonNull BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value != null) postAlert(callbackGatt, value);
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt callbackGatt,
                                            @NonNull BluetoothGattCharacteristic characteristic,
                                            @NonNull byte[] value) {
            postAlert(callbackGatt, value);
        }
    };

    @SuppressLint("MissingPermission")
    private void handleConnection(BluetoothGatt callbackGatt, int status, int newState) {
        if (stopped || callbackGatt != gatt) {
            callbackGatt.close();
            return;
        }
        if (status == BluetoothGatt.GATT_SUCCESS
                && newState == BluetoothProfile.STATE_CONNECTED) {
            publish("ESP32 linked; enabling alerts…");
            if (!callbackGatt.discoverServices()) {
                retryAfterFailure("Service discovery could not start");
            }
        } else {
            retryAfterFailure("Connection failed (GATT " + status + ")");
        }
    }

    @SuppressLint("MissingPermission")
    private void handleServices(BluetoothGatt callbackGatt, int status) {
        if (stopped || callbackGatt != gatt) return;
        if (status != BluetoothGatt.GATT_SUCCESS
                || callbackGatt.getService(SERVICE_UUID) == null) {
            retryAfterFailure("ESP32 service not available");
            return;
        }
        BluetoothGattCharacteristic alerts =
                callbackGatt.getService(SERVICE_UUID).getCharacteristic(ALERT_UUID);
        if (alerts == null || !callbackGatt.setCharacteristicNotification(alerts, true)) {
            retryAfterFailure("ESP32 alert channel not available");
            return;
        }
        BluetoothGattDescriptor descriptor = alerts.getDescriptor(CCC_UUID);
        if (descriptor == null) {
            retryAfterFailure("ESP32 notification descriptor missing");
            return;
        }
        boolean started;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            started = callbackGatt.writeDescriptor(
                    descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0;
        } else {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            started = callbackGatt.writeDescriptor(descriptor);
        }
        if (!started) retryAfterFailure("Could not enable ESP32 alerts");
    }

    private void postAlert(BluetoothGatt callbackGatt, byte[] bytes) {
        String raw = new String(bytes, StandardCharsets.UTF_8);
        handler.post(() -> {
            if (!stopped && ready && callbackGatt == gatt) processAlert(raw);
        });
    }

    private void processAlert(String raw) {
        String type = raw.split(",", 2)[0];
        int oldMask = blockedMask;
        switch (type) {
            case "FRONT": blockedMask |= FRONT; break;
            case "LEFT": blockedMask |= LEFT; break;
            case "RIGHT": blockedMask |= RIGHT; break;
            case "LOW": blockedMask |= LOW; break;
            case "CLEAR_FRONT": blockedMask &= ~FRONT; break;
            case "CLEAR_LEFT": blockedMask &= ~LEFT; break;
            case "CLEAR_RIGHT": blockedMask &= ~RIGHT; break;
            case "CLEAR_LOW": blockedMask &= ~LOW; break;
            case "CLEAR": blockedMask = 0; break;
            default: return;
        }
        if (blockedMask == 0) {
            handler.removeCallbacks(announce);
            lastSpokenMask = 0;
            return;
        }
        if (blockedMask != oldMask || SystemClock.elapsedRealtime() - lastSpokenAt >= ALERT_COOLDOWN_MS) {
            handler.removeCallbacks(announce);
            handler.postDelayed(announce, AGGREGATION_MS);
        }
    }

    private void announceObstacle() {
        if (stopped || !ready || !ttsReady || tts == null || blockedMask == 0) return;
        long now = SystemClock.elapsedRealtime();
        if (blockedMask == lastSpokenMask && now - lastSpokenAt < ALERT_COOLDOWN_MS) return;
        String message;
        int sides = blockedMask & (FRONT | LEFT | RIGHT);
        if (sides == (FRONT | LEFT | RIGHT)) message = "Obstacle everywhere";
        else if (sides == (FRONT | LEFT)) message = "Obstacles ahead and left";
        else if (sides == (FRONT | RIGHT)) message = "Obstacles ahead and right";
        else if (sides == (LEFT | RIGHT)) message = "Obstacles left and right";
        else if (sides == FRONT) message = "Obstacle ahead";
        else if (sides == LEFT) message = "Obstacle left";
        else if (sides == RIGHT) message = "Obstacle right";
        else message = "Low obstacle ahead";
        lastSpokenMask = blockedMask;
        lastSpokenAt = now;
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "obstacle-" + now);
    }

    private void retryAfterFailure(String reason) {
        if (stopped) return;
        stopScan();
        closeGatt();
        blockedMask = 0;
        lastSpokenMask = 0;
        long delay = Math.min(16_000L, 2_000L << Math.min(failures, 3));
        failures++;
        publish(reason + "; retrying in " + delay / 1000 + "s");
        Log.w(TAG, reason);
        scheduleRetry(delay);
    }

    private void scheduleRetry(long delay) {
        handler.removeCallbacks(scanAgain);
        if (!stopped) handler.postDelayed(scanAgain, delay);
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        handler.removeCallbacks(connectionTimeout);
        ready = false;
        BluetoothGatt old = gatt;
        gatt = null;
        if (old != null) {
            try {
                old.disconnect();
                old.close();
            } catch (RuntimeException error) {
                Log.w(TAG, "closeGatt", error);
            }
        }
    }

    private void publish(String message) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_STATUS, message).apply();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(message));
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, message));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Blind Guide sensor", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification(String message) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent retry = new Intent(this, SensorService.class).setAction(ACTION_RETRY);
        PendingIntent retryIntent = PendingIntent.getService(this, 1, retry,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Blind Guide")
                .setContentText(message)
                .setOngoing(true)
                .setContentIntent(openIntent)
                .addAction(android.R.drawable.ic_popup_sync, "Retry ESP32", retryIntent)
                .build();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        stopScan();
        closeGatt();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_STATUS, "Stopped").apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
