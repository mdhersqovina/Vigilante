package com.example.kiosk;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;

    private final EditText[] pinInputs = new EditText[6];
    private TextView statusText;
    private TextView tvSecuredBy;
    
    private TextView tvClock;
    private TextView tvOnlineStatus;
    private View vOnlineDot;
    private View unlockButton;

    private static final String PREFS_NAME = "KioskPrefs";
    private static final String KEY_UNLOCK_EXPIRY = "unlock_expiry";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable relockRunnable = this::relockDevice;
    
    private final Runnable clockRunnable = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 10000); // update every 10 seconds
        }
    };

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private FirebaseFirestore db;
    private String deviceId;
    private ListenerRegistration stationListener;
    private boolean isVerifying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        // Initialize Firebase
        db = FirebaseFirestore.getInstance();
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        pinInputs[0] = findViewById(R.id.pin_1);
        pinInputs[1] = findViewById(R.id.pin_2);
        pinInputs[2] = findViewById(R.id.pin_3);
        pinInputs[3] = findViewById(R.id.pin_4);
        pinInputs[4] = findViewById(R.id.pin_5);
        pinInputs[5] = findViewById(R.id.pin_6);

        unlockButton = findViewById(R.id.unlockButton);
        statusText = findViewById(R.id.statusText);
        tvSecuredBy = findViewById(R.id.tvSecuredBy);
        
        tvClock = findViewById(R.id.tv_clock);
        tvOnlineStatus = findViewById(R.id.tv_online_status);
        vOnlineDot = findViewById(R.id.v_online_dot);

        setupPinNavigation();
        setupStyledFooter();
        setupConnectivityMonitor();

        devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MyDeviceAdminReceiver.class);

        configureKiosk();
        registerStation();
        listenForRemoteCommands();

        // Prevent back button
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!isCurrentlyUnlocked()) {
                    Toast.makeText(MainActivity.this, R.string.device_is_locked, Toast.LENGTH_SHORT).show();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });

        if (unlockButton != null) {
            unlockButton.setOnClickListener(v -> submitCode());
        }
    }

    private void submitCode() {
        if (isVerifying) return;

        StringBuilder sb = new StringBuilder();
        for (EditText et : pinInputs) {
            sb.append(et.getText().toString().trim());
        }
        String enteredCode = sb.toString();
        if (enteredCode.length() == 6) {
            verifyUnlockCode(enteredCode);
        } else {
            Toast.makeText(this, "Please enter 6-digit code", Toast.LENGTH_SHORT).show();
        }
    }

    private void registerStation() {
        DocumentReference docRef = db.collection("stations").document(deviceId);
        docRef.get().addOnSuccessListener(documentSnapshot -> {
            if (!documentSnapshot.exists()) {
                Map<String, Object> station = new HashMap<>();
                station.put("deviceId", deviceId);
                station.put("status", "Pending Setup");
                station.put("name", "New TV Device");
                docRef.set(station);
            }
        });
    }

    private void listenForRemoteCommands() {
        stationListener = db.collection("stations").document(deviceId)
                .addSnapshotListener((snapshot, e) -> {
                    if (e != null) {
                        Log.w(TAG, "Listen failed.", e);
                        return;
                    }

                    if (snapshot != null && snapshot.exists()) {
                        String remoteStatus = snapshot.getString("status");
                        if ("Locked".equalsIgnoreCase(remoteStatus) || "Shutdown".equalsIgnoreCase(remoteStatus)) {
                            if (isCurrentlyUnlocked()) {
                                Log.d(TAG, "Remote Kill Signal received");
                                relockDevice();
                            }
                        }
                    }
                });
    }

    private void verifyUnlockCode(String enteredCode) {
        setVerifyingState(true);
        statusText.setText("Verifying code...");

        // Filter by code, status AND deviceId to ensure Station A's code doesn't work on Station B
        db.collection("unlock_codes")
                .whereEqualTo("code", enteredCode)
                .whereEqualTo("status", "PENDING")
                .whereEqualTo("deviceId", deviceId)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (!queryDocumentSnapshots.isEmpty()) {
                        for (QueryDocumentSnapshot document : queryDocumentSnapshots) {
                            processUnlock(document);
                            return;
                        }
                    } else {
                        setVerifyingState(false);
                        statusText.setText(R.string.invalid_code_message);
                        clearPin();
                        Toast.makeText(this, R.string.invalid_code_toast, Toast.LENGTH_SHORT).show();
                    }
                })
                .addOnFailureListener(e -> {
                    setVerifyingState(false);
                    statusText.setText("Network error. Try again.");
                    Toast.makeText(this, "Connection Error", Toast.LENGTH_SHORT).show();
                });
    }

    private void setVerifyingState(boolean verifying) {
        this.isVerifying = verifying;
        if (unlockButton != null) {
            unlockButton.setEnabled(!verifying);
            unlockButton.setAlpha(verifying ? 0.5f : 1.0f);
        }
        for (EditText et : pinInputs) {
            et.setEnabled(!verifying);
        }
    }

    private void processUnlock(QueryDocumentSnapshot document) {
        String docId = document.getId();
        Long durationMinutes = document.getLong("duration");
        String player = document.getString("player");

        if (durationMinutes == null) durationMinutes = 30L;
        long durationMs = durationMinutes * 60 * 1000;

        // Trigger local unlock immediately for instant feel
        long startTime = System.currentTimeMillis();
        long expiryTime = startTime + durationMs;
        
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putLong(KEY_UNLOCK_EXPIRY, expiryTime)
                .apply();

        statusText.setText(R.string.code_accepted);
        Toast.makeText(this, R.string.device_unlocked, Toast.LENGTH_SHORT).show();
        
        stopLockTask();
        moveTaskToBack(true);
        handler.postDelayed(relockRunnable, durationMs);
        setVerifyingState(false);

        // Perform Firestore updates in background
        db.collection("unlock_codes").document(docId).update("status", "ACTIVE");
        
        Map<String, Object> update = new HashMap<>();
        update.put("status", "Active");
        update.put("startTime", startTime);
        update.put("prepaidDuration", durationMinutes);
        update.put("player", player);
        db.collection("stations").document(deviceId).update(update);
    }

    private void setupStyledFooter() {
        if (tvSecuredBy != null) {
            String styledText = getString(R.string.secured_by_vigilant);
            tvSecuredBy.setText(Html.fromHtml(styledText, Html.FROM_HTML_MODE_LEGACY));
        }
    }

    private void setupPinNavigation() {
        for (int i = 0; i < 6; i++) {
            final int index = i;
            pinInputs[index].addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (s.length() == 1) {
                        if (index < 5) {
                            pinInputs[index + 1].requestFocus();
                        } else {
                            // Automatically submit when the last digit is entered
                            submitCode();
                        }
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });

            // Handle backspace to move to previous box
            pinInputs[index].setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_DEL && event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (pinInputs[index].getText().length() == 0 && index > 0) {
                        pinInputs[index - 1].requestFocus();
                        pinInputs[index - 1].setText("");
                        return true;
                    }
                }
                return false;
            });
        }
    }

    private void updateClock() {
        if (tvClock != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a", Locale.getDefault());
            tvClock.setText(sdf.format(new Date()));
        }
    }

    private void setupConnectivityMonitor() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                runOnUiThread(() -> updateConnectivityUI(true));
            }

            @Override
            public void onLost(@NonNull Network network) {
                runOnUiThread(() -> updateConnectivityUI(false));
            }
        };

        // Check initial state
        Network activeNetwork = connectivityManager.getActiveNetwork();
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(activeNetwork);
        boolean isOnline = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        updateConnectivityUI(isOnline);
    }

    private void updateConnectivityUI(boolean isOnline) {
        if (tvOnlineStatus != null) {
            tvOnlineStatus.setText(isOnline ? R.string.online : R.string.offline);
            tvOnlineStatus.setTextColor(ContextCompat.getColor(this, 
                    isOnline ? R.color.green_primary : R.color.red_primary));
        }
        if (vOnlineDot != null) {
            vOnlineDot.setBackgroundResource(isOnline ? R.drawable.bg_circle_green : R.drawable.bg_circle_red);
        }
    }

    private void configureKiosk() {
        if (devicePolicyManager != null && devicePolicyManager.isDeviceOwnerApp(getPackageName())) {
            devicePolicyManager.setLockTaskPackages(adminComponent, new String[]{getPackageName()});
            
            // Disable keyguard (system lock screen)
            devicePolicyManager.setKeyguardDisabled(adminComponent, true);

            // Add restrictions to block settings access and other system menus
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT);
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET);
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER);
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
            
            // API 28+ features to explicitly block home/recents if supported
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                devicePolicyManager.setLockTaskFeatures(adminComponent, 0); 
            }

            try {
                startLockTask();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start lock task", e);
            }
            statusText.setText(R.string.tv_locked);
        } else {
            statusText.setText(R.string.device_owner_not_configured);
        }
    }

    private void relockDevice() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(KEY_UNLOCK_EXPIRY)
                .apply();

        clearPin();
        statusText.setText(R.string.tv_locked);
        
        handler.removeCallbacks(relockRunnable);

        // Update station status to Time Up in Firestore
        db.collection("stations").document(deviceId).update("status", "Time Up");

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);

        if (devicePolicyManager != null && devicePolicyManager.isDeviceOwnerApp(getPackageName())) {
            try {
                startLockTask();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start lock task in relock", e);
            }
        }
    }

    private void clearPin() {
        for (EditText et : pinInputs) {
            et.setText("");
        }
        pinInputs[0].requestFocus();
    }

    private boolean isCurrentlyUnlocked() {
        long expiry = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getLong(KEY_UNLOCK_EXPIRY, 0);
        return System.currentTimeMillis() < expiry;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!isCurrentlyUnlocked()) {
            int keyCode = event.getKeyCode();
            // Expanded list of keys to block when locked
            if (keyCode == KeyEvent.KEYCODE_BACK ||
                keyCode == KeyEvent.KEYCODE_ESCAPE ||
                keyCode == KeyEvent.KEYCODE_MENU ||
                keyCode == KeyEvent.KEYCODE_SETTINGS ||
                keyCode == KeyEvent.KEYCODE_SEARCH ||
                keyCode == KeyEvent.KEYCODE_TV_INPUT ||
                keyCode == KeyEvent.KEYCODE_GUIDE ||
                keyCode == KeyEvent.KEYCODE_DVR ||
                keyCode == KeyEvent.KEYCODE_HOME) {

                if (event.getAction() == KeyEvent.ACTION_UP) {
                    Toast.makeText(this, R.string.device_is_locked, Toast.LENGTH_SHORT).show();
                }
                return true; // Consume event
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        handler.removeCallbacks(clockRunnable);
        handler.post(clockRunnable);

        if (connectivityManager != null && networkCallback != null) {
            NetworkRequest networkRequest = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
        }

        long expiry = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getLong(KEY_UNLOCK_EXPIRY, 0);
        long timeLeft = expiry - System.currentTimeMillis();

        if (timeLeft > 0) {
            handler.removeCallbacks(relockRunnable);
            handler.postDelayed(relockRunnable, timeLeft);
            statusText.setText(R.string.unlocked);
        } else {
            if (devicePolicyManager != null && devicePolicyManager.isDeviceOwnerApp(getPackageName())) {
                try {
                    startLockTask();
                    statusText.setText(R.string.tv_locked);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start lock task in onResume", e);
                }
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockRunnable);
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(relockRunnable);
        if (stationListener != null) {
            stationListener.remove();
        }
    }
}
