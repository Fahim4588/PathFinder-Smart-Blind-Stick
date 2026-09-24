package com.example.blindguideprototype1;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.GoogleMap.CameraPerspective;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.libraries.navigation.AudioGuidanceSettings;
import com.google.android.libraries.navigation.ListenableResultFuture;
import com.google.android.libraries.navigation.NavigationApi;
import com.google.android.libraries.navigation.Navigator;
import com.google.android.libraries.navigation.RoutingOptions;
import com.google.android.libraries.navigation.SupportNavigationFragment;
import com.google.android.libraries.navigation.Waypoint;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {
    private static final int PERMISSION_REQUEST = 10;
    private static final int DESTINATION_REQUEST = 11;
    private static final int DESTINATION_CONFIRM_REQUEST = 12;
    private static final int NEW_SESSION_REQUEST = 13;
    private static final int MANUAL_COMMAND_REQUEST = 14;
    private static final String SAY_DESTINATION = "say-destination";
    private static final String CONFIRM_DESTINATION = "confirm-destination";
    private static final String ASK_NEW_SESSION = "ask-new-session";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService geocoderExecutor = Executors.newSingleThreadExecutor();
    private TextView status;
    private TextView sensorStatus;
    private Button voiceButton;
    private EditText destinationInput;
    private TextView voiceStatus;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean permissionsHandled;
    private boolean startupStarted;
    private FusedLocationProviderClient locationClient;
    private Navigator navigator;
    private SupportNavigationFragment navigationFragment;
    private RoutingOptions walkingOptions;
    private boolean navigationSdkUnavailable;
    private boolean navigationActive;
    private boolean destinationHasCoordinates;
    private double destinationLatitude;
    private double destinationLongitude;
    private String destinationName;
    private SpeechRecognizer commandRecognizer;
    private boolean commandRecognizerRunning;
    private boolean commandListeningAllowed;
    private boolean shuttingDown;
    private final Runnable startCommandListening = this::startCommandListener;
    private boolean sensorReceiverRegistered;
    private final BroadcastReceiver sensorStatusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(SensorService.EXTRA_STATUS);
            if (message != null) sensorStatus.setText("ESP32: " + message);
        }
    };

    private final Navigator.ArrivalListener arrivalListener = event -> {
        stopGuidance();
        speak("You have arrived. Do you want to set a new destination? Say yes or no.",
                ASK_NEW_SESSION);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_navigation);
        status = findViewById(R.id.status_text);
        sensorStatus = findViewById(R.id.sensor_status_text);
        IntentFilter sensorFilter = new IntentFilter(SensorService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sensorStatusReceiver, sensorFilter, Context.RECEIVER_NOT_EXPORTED);
        } else registerReceiver(sensorStatusReceiver, sensorFilter);
        sensorReceiverRegistered = true;
        voiceButton = findViewById(R.id.voice_command_button);
        voiceButton.setOnClickListener(view -> {
            if (navigationActive) startManualCommandRecognition();
            else startLocationSession();
        });
        destinationInput = findViewById(R.id.destination_input);
        voiceStatus = findViewById(R.id.voice_status_text);
        findViewById(R.id.navigate_button).setOnClickListener(view -> navigateTypedDestination());
        findViewById(R.id.retry_esp_button).setOnClickListener(view -> {
            Intent retry = new Intent(this, SensorService.class);
            retry.setAction(SensorService.ACTION_RETRY);
            ContextCompat.startForegroundService(this, retry);
        });
        findViewById(R.id.stop_button).setOnClickListener(view -> stopEverythingAndClose());
        sensorStatus.setText("ESP32: " + getSharedPreferences(SensorService.PREFS, MODE_PRIVATE)
                .getString(SensorService.PREF_STATUS, "Waiting to connect"));
        navigationFragment = (SupportNavigationFragment) getSupportFragmentManager()
                .findFragmentById(R.id.navigation_fragment);
        locationClient = LocationServices.getFusedLocationProviderClient(this);
        tts = new TextToSpeech(this, this);
        requestRequiredPermissions();
    }

    private void requestRequiredPermissions() {
        List<String> missing = new ArrayList<>();
        addIfMissing(missing, Manifest.permission.ACCESS_FINE_LOCATION);
        addIfMissing(missing, Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addIfMissing(missing, Manifest.permission.BLUETOOTH_SCAN);
            addIfMissing(missing, Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(missing, Manifest.permission.POST_NOTIFICATIONS);
        }
        if (missing.isEmpty()) {
            permissionsHandled = true;
            startSensorServiceIfAllowed();
            initializeNavigationSdk();
            maybeStartStartupFlow();
        } else requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
    }

    private void addIfMissing(List<String> list, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) list.add(permission);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERMISSION_REQUEST) {
            permissionsHandled = true;
            startSensorServiceIfAllowed();
            if (hasLocationPermission()) {
                initializeNavigationSdk();
                maybeStartStartupFlow();
            }
            else status.setText("Precise location permission is required for navigation.");
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasMicrophonePermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void initializeNavigationSdk() {
        status.setText("Initializing Google Navigation…");
        NavigationApi.getNavigator(this, new NavigationApi.NavigatorListener() {
            @Override
            public void onNavigatorReady(@NonNull Navigator readyNavigator) {
                if (shuttingDown || isFinishing() || isDestroyed()) return;
                navigator = readyNavigator;
                navigator.addArrivalListener(arrivalListener);
                walkingOptions = new RoutingOptions();
                walkingOptions.travelMode(RoutingOptions.TravelMode.WALKING);
                if (navigationFragment != null) {
                    navigationFragment.getMapAsync(
                            map -> map.followMyLocation(CameraPerspective.TILTED));
                }
                maybeStartStartupFlow();
            }

            @Override
            public void onError(@NavigationApi.ErrorCode int errorCode) {
                navigationSdkUnavailable = true;
                String message = "In-app navigation unavailable (" + errorCode
                        + "). Walking directions will open in Google Maps.";
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                maybeStartStartupFlow();
            }
        });
    }

    @Override
    public void onInit(int result) {
        if (result == TextToSpeech.SUCCESS) {
            ttsReady = true;
            tts.setLanguage(Locale.getDefault());
            tts.setSpeechRate(0.92f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) {
                    runOnUiThread(() -> handleFinishedSpeech(id));
                }
                @Override public void onError(String id) {
                    runOnUiThread(() -> handleFinishedSpeech(id));
                }
            });
        }
        maybeStartStartupFlow();
    }

    private void maybeStartStartupFlow() {
        if (!permissionsHandled || !hasLocationPermission() || startupStarted || shuttingDown) return;
        startupStarted = true;
        startLocationSession();
    }

    private void startLocationSession() {
        if (shuttingDown) return;
        commandListeningAllowed = false;
        stopCommandListener();
        voiceButton.setText("Voice command / Stop everything");
        startSensorServiceIfAllowed();
        // Do not make the first voice prompt wait up to 20 seconds for GPS and
        // reverse geocoding. The Navigation SDK obtains current location itself.
        status.setText("Ready. Say or type your destination.");
        voiceStatus.setText("Listening for your destination after the prompt");
        speak("Where do you want to go?", SAY_DESTINATION);
    }

    private void navigateTypedDestination() {
        String typed = destinationInput.getText().toString().trim();
        if (typed.isEmpty()) {
            destinationInput.setError("Enter a destination");
            return;
        }
        commandListeningAllowed = false;
        stopCommandListener();
        if (tts != null) tts.stop();
        destinationName = typed;
        destinationHasCoordinates = false;
        status.setText("Finding " + typed + "…");
        geocoderExecutor.execute(() -> {
            Address foundAddress = null;
            try {
                List<Address> matches = new Geocoder(this, Locale.getDefault())
                        .getFromLocationName(typed, 1);
                if (matches != null && !matches.isEmpty()) foundAddress = matches.get(0);
            } catch (IOException | IllegalArgumentException ignored) { }
            Address match = foundAddress;
            runOnUiThread(() -> {
                if (shuttingDown) return;
                if (match != null) {
                    destinationLatitude = match.getLatitude();
                    destinationLongitude = match.getLongitude();
                    destinationHasCoordinates = true;
                }
                startSdkGuidance();
            });
        });
    }

    private void startSensorServiceIfAllowed() {
        boolean allowed = hasLocationPermission() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED));
        if (allowed) ContextCompat.startForegroundService(
                this, new Intent(this, SensorService.class));
        else sensorStatus.setText("ESP32: allow precise location and Nearby devices");
    }

    private void findCurrentLocationAndAskDestination() {
        if (!hasLocationPermission()) return;
        status.setText("Getting a fresh high-accuracy location…");
        CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(0).setDurationMillis(20000).build();
        locationClient.getCurrentLocation(request, new CancellationTokenSource().getToken())
                .addOnSuccessListener(location -> {
                    if (shuttingDown) return;
                    if (location == null) speak(
                            "I could not get a precise location. Where do you want to go?",
                            SAY_DESTINATION);
                    else announceCurrentLocation(location);
                })
                .addOnFailureListener(error -> {
                    if (!shuttingDown) speak(
                            "I could not get a precise location. Where do you want to go?",
                            SAY_DESTINATION);
                });
    }

    private void announceCurrentLocation(Location location) {
        geocoderExecutor.execute(() -> {
            String address = null;
            try {
                List<Address> found = new Geocoder(this, Locale.getDefault())
                        .getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                if (found != null && !found.isEmpty()) address = usefulAddress(found.get(0));
            } catch (IOException ignored) { }
            String spoken = address == null || address.isEmpty()
                    ? String.format(Locale.US, "latitude %.5f, longitude %.5f",
                    location.getLatitude(), location.getLongitude()) : address;
            runOnUiThread(() -> {
                if (shuttingDown) return;
                status.setText("Current location: " + spoken + "\nAccuracy: approximately "
                        + Math.round(location.getAccuracy()) + " metres");
                speak("Your current location is " + spoken + ". Where do you want to go?",
                        SAY_DESTINATION);
            });
        });
    }

    private String usefulAddress(Address address) {
        if (address.getAddressLine(0) != null) return address.getAddressLine(0);
        return address.getFeatureName() == null ? "" : address.getFeatureName();
    }

    private void handleFinishedSpeech(String id) {
        if (SAY_DESTINATION.equals(id)) startDestinationRecognition();
        else if (CONFIRM_DESTINATION.equals(id)) startDestinationConfirmation();
        else if (ASK_NEW_SESSION.equals(id)) startNewSessionAnswerRecognition();
    }

    private Intent speechIntent(String prompt, int maxResults) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults);
        return intent;
    }

    private void startDestinationRecognition() {
        stopCommandListener();
        startSpeechActivity(speechIntent("Say the destination name and area", 5),
                DESTINATION_REQUEST);
    }

    private void startDestinationConfirmation() {
        startSpeechActivity(speechIntent("Say yes or no", 3), DESTINATION_CONFIRM_REQUEST);
    }

    private void startNewSessionAnswerRecognition() {
        startSpeechActivity(speechIntent("Say yes or no", 3), NEW_SESSION_REQUEST);
    }

    private void startManualCommandRecognition() {
        commandListeningAllowed = false;
        stopCommandListener();
        startSpeechActivity(speechIntent("Say end session, stop everything, or cancel", 5),
                MANUAL_COMMAND_REQUEST);
    }

    private void startSpeechActivity(Intent intent, int requestCode) {
        try {
            voiceStatus.setText("Listening…");
            startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException error) {
            voiceStatus.setText("Voice recognition unavailable. Type a destination instead.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        ArrayList<String> results = data == null ? null
                : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (results != null && containsStopEverythingCommand(results)) {
            stopEverythingAndClose();
            return;
        }
        voiceStatus.setText("Voice ready");
        if (requestCode == DESTINATION_REQUEST) {
            if (resultCode != Activity.RESULT_OK || results == null || results.isEmpty())
                retryDestination("I did not hear a destination.");
            else validateDestinationCandidates(results);
        } else if (requestCode == DESTINATION_CONFIRM_REQUEST) {
            handleDestinationConfirmation(resultCode, results);
        } else if (requestCode == NEW_SESSION_REQUEST) {
            handleNewSessionAnswer(resultCode, results);
        } else if (requestCode == MANUAL_COMMAND_REQUEST) {
            if (containsStopEverythingCommand(results)) stopEverythingAndClose();
            else if (containsStartLocationCommand(results)) startLocationSession();
            else if (containsEndCommand(results)) endCurrentSessionAndAsk();
            else {
                commandListeningAllowed = true;
                scheduleCommandListener();
            }
        }
    }

    private void validateDestinationCandidates(List<String> candidates) {
        status.setText("Checking destination: " + candidates.get(0));
        destinationInput.setText(candidates.get(0));
        geocoderExecutor.execute(() -> {
            Address match = null;
            String heard = null;
            for (String candidate : candidates) {
                try {
                    List<Address> found = new Geocoder(this, Locale.getDefault())
                            .getFromLocationName(candidate, 1);
                    if (found != null && !found.isEmpty()) {
                        match = found.get(0);
                        heard = candidate;
                        break;
                    }
                } catch (IOException ignored) { break; }
            }
            Address finalMatch = match;
            String finalHeard = heard;
            runOnUiThread(() -> {
                if (shuttingDown) return;
                if (finalMatch == null) {
                    destinationHasCoordinates = false;
                    destinationName = candidates.get(0);
                    status.setText("Destination: " + destinationName);
                    speak("I heard " + destinationName + ". Is that correct? Say yes or no.",
                            CONFIRM_DESTINATION);
                    return;
                }
                destinationHasCoordinates = true;
                destinationLatitude = finalMatch.getLatitude();
                destinationLongitude = finalMatch.getLongitude();
                destinationName = usefulAddress(finalMatch);
                if (destinationName.isEmpty()) destinationName = finalHeard;
                status.setText("Destination: " + destinationName);
                speak("I found " + destinationName + ". Is that correct? Say yes or no.",
                        CONFIRM_DESTINATION);
            });
        });
    }

    private void handleDestinationConfirmation(int resultCode, List<String> results) {
        if (resultCode != Activity.RESULT_OK || results == null || results.isEmpty()) {
            speak("Please say yes or no. Is " + destinationName + " correct?",
                    CONFIRM_DESTINATION);
            return;
        }
        String answer = results.get(0).toLowerCase(Locale.ROOT);
        if (isYes(answer)) {
            status.setText("Calculating a walking route to " + destinationName + "…");
            startSdkGuidance();
        } else if (isNo(answer)) {
            retryDestination("That destination was rejected.");
        } else {
            speak("Please say yes or no. Is " + destinationName + " correct?",
                    CONFIRM_DESTINATION);
        }
    }

    private void retryDestination(String reason) {
        status.setText(reason);
        speak(reason + " Please say the destination name and area again.", SAY_DESTINATION);
    }

    private void startSdkGuidance() {
        if (shuttingDown) return;
        if (navigationSdkUnavailable || navigator == null || walkingOptions == null
                || !destinationHasCoordinates) {
            startMapsWalkingGuidance();
            return;
        }
        Waypoint waypoint = Waypoint.builder()
                .setLatLng(destinationLatitude, destinationLongitude)
                .setTitle(destinationName).build();
        ListenableResultFuture<Navigator.RouteStatus> route =
                navigator.setDestination(waypoint, walkingOptions);
        route.setOnResultListener(code -> runOnUiThread(() -> {
            if (shuttingDown) return;
            if (code == Navigator.RouteStatus.OK) {
                AudioGuidanceSettings audio = AudioGuidanceSettings.builder()
                        .setGuidanceMode(AudioGuidanceSettings.GuidanceMode.VOICE_ALERTS_AND_GUIDANCE)
                        .setVolumeLevel(AudioGuidanceSettings.VolumeLevel.NORMAL).build();
                navigator.setAudioGuidanceSettings(audio);
                navigator.startGuidance();
                navigationActive = true;
                commandListeningAllowed = true;
                status.setText("Walking guidance active: " + destinationName
                        + "\nSay 'end session' or 'stop everything'.");
                voiceButton.setText("Voice command / Stop everything");
                scheduleCommandListener();
            } else {
                startMapsWalkingGuidance();
            }
        }));
    }

    private void startMapsWalkingGuidance() {
        String destination = destinationHasCoordinates
                ? String.format(Locale.US, "%.6f,%.6f", destinationLatitude,
                        destinationLongitude)
                : destinationName;
        Uri uri = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
                .appendQueryParameter("api", "1")
                .appendQueryParameter("destination", destination)
                .appendQueryParameter("travelmode", "walking")
                .appendQueryParameter("dir_action", "navigate")
                .build();
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.setPackage("com.google.android.apps.maps");
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            intent.setPackage(null);
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException noMapsApp) {
                status.setText("No maps app can open walking directions.");
                return;
            }
        }
        navigationActive = true;
        commandListeningAllowed = true;
        status.setText("Walking directions opened: " + destinationName);
        scheduleCommandListener();
    }

    private void createCommandRecognizerIfNeeded() {
        if (commandRecognizer != null || !SpeechRecognizer.isRecognitionAvailable(this)) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            commandRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
        } else commandRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        commandRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { commandRecognizerRunning = true; }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onError(int error) {
                commandRecognizerRunning = false;
                scheduleCommandListener();
            }
            @Override public void onResults(Bundle bundle) {
                commandRecognizerRunning = false;
                ArrayList<String> results = bundle.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                if (containsStopEverythingCommand(results)) stopEverythingAndClose();
                else if (containsStartLocationCommand(results)) startLocationSession();
                else if (containsEndCommand(results)) endCurrentSessionAndAsk();
                else scheduleCommandListener();
            }
            @Override public void onPartialResults(Bundle partialResults) {
                ArrayList<String> results = partialResults.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                if (containsStopEverythingCommand(results)) stopEverythingAndClose();
                else if (containsStartLocationCommand(results)) startLocationSession();
            }
            @Override public void onEvent(int eventType, Bundle params) { }
        });
    }

    private void scheduleCommandListener() {
        handler.removeCallbacks(startCommandListening);
        if (!shuttingDown && commandListeningAllowed && hasWindowFocus())
            handler.postDelayed(startCommandListening, 2500);
    }

    private void startCommandListener() {
        if (shuttingDown || !commandListeningAllowed
                || !hasWindowFocus() || !hasMicrophonePermission() || commandRecognizerRunning) return;
        createCommandRecognizerIfNeeded();
        if (commandRecognizer == null) return;
        Intent intent = speechIntent("Say start location or stop everything", 5);
        commandRecognizer.startListening(intent);
        commandRecognizerRunning = true;
        voiceStatus.setText("Listening for Start location / Stop everything");
    }

    private void stopCommandListener() {
        if (commandRecognizer != null && commandRecognizerRunning) commandRecognizer.cancel();
        commandRecognizerRunning = false;
    }

    private boolean containsEndCommand(List<String> results) {
        if (results == null) return false;
        for (String result : results) {
            String text = result.toLowerCase(Locale.ROOT);
            if (text.contains("end session") || text.contains("end the session")
                    || text.contains("close session") || text.contains("close the session"))
                return true;
        }
        return false;
    }

    private boolean containsStartLocationCommand(List<String> results) {
        return containsPhrase(results, "start location", "start navigation",
                "begin location", "begin navigation");
    }

    private boolean containsStopEverythingCommand(List<String> results) {
        return containsPhrase(results, "stop everything", "stop all", "close app",
                "close the app", "turn off app", "turn off the app");
    }

    private boolean containsPhrase(List<String> results, String... phrases) {
        if (results == null) return false;
        for (String result : results) {
            String text = result.toLowerCase(Locale.ROOT).trim();
            for (String phrase : phrases) {
                if (text.contains(phrase)) return true;
            }
        }
        return false;
    }

    private void stopGuidance() {
        commandListeningAllowed = false;
        stopCommandListener();
        navigationActive = false;
        if (navigator != null) {
            navigator.stopGuidance();
            navigator.clearDestinations();
        }
        status.setText("Navigation session ended.");
    }

    private void endCurrentSessionAndAsk() {
        stopGuidance();
        speak("The current session has ended. Do you want to set a new destination? "
                + "Say yes or no.", ASK_NEW_SESSION);
    }

    private void handleNewSessionAnswer(int resultCode, List<String> results) {
        if (resultCode != Activity.RESULT_OK || results == null || results.isEmpty()) {
            speak("Please say yes or no. Do you want to set a new destination?", ASK_NEW_SESSION);
            return;
        }
        String answer = results.get(0).toLowerCase(Locale.ROOT);
        if (isYes(answer)) {
            startLocationSession();
        } else if (isNo(answer)) {
            stopService(new Intent(this, SensorService.class));
            status.setText("Session ended. Press the button to start again.");
            voiceButton.setText("Start a new destination");
            commandListeningAllowed = true;
            scheduleCommandListener();
        } else speak("Please say yes or no. Do you want to set a new destination?",
                ASK_NEW_SESSION);
    }

    private boolean isYes(String answer) {
        return answer.contains("yes") || answer.contains("correct")
                || answer.contains("okay") || answer.equals("ok");
    }

    private boolean isNo(String answer) {
        return answer.contains("no") || answer.contains("wrong");
    }

    private void speak(String text, String utteranceId) {
        if (shuttingDown) return;
        commandListeningAllowed = false;
        stopCommandListener();
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        else {
            status.setText(text);
            handler.post(() -> handleFinishedSpeech(utteranceId));
        }
    }

    private void stopEverythingAndClose() {
        if (shuttingDown) return;
        shuttingDown = true;
        commandListeningAllowed = false;
        navigationActive = false;
        handler.removeCallbacksAndMessages(null);
        stopCommandListener();
        if (navigator != null) {
            if (navigator.isGuidanceRunning()) navigator.stopGuidance();
            navigator.clearDestinations();
        }
        stopService(new Intent(this, SensorService.class));
        if (tts != null) tts.stop();
        finishAndRemoveTask();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) stopCommandListener();
        else if (commandListeningAllowed) scheduleCommandListener();
    }

    @Override
    protected void onDestroy() {
        shuttingDown = true;
        if (sensorReceiverRegistered) {
            unregisterReceiver(sensorStatusReceiver);
            sensorReceiverRegistered = false;
        }
        handler.removeCallbacksAndMessages(null);
        stopCommandListener();
        if (commandRecognizer != null) commandRecognizer.destroy();
        if (navigator != null) {
            navigator.removeArrivalListener(arrivalListener);
            if (navigator.isGuidanceRunning()) navigator.stopGuidance();
            navigator.clearDestinations();
            navigator.cleanup();
        }
        geocoderExecutor.shutdownNow();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (isFinishing() && !isChangingConfigurations()) {
            stopService(new Intent(this, SensorService.class));
        }
        super.onDestroy();
    }
}
