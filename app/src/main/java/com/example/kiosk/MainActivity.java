package com.example.kiosk;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;

import android.app.ActivityManager;
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
import com.google.firebase.firestore.DocumentSnapshot;
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

    private TextView tvStationName;
    private TextView tvHourlyRate;
    private TextView tvStationStatusValue;
    private TextView tvWelcomeTitle;
    private TextView tvPrepaidDuration;

    private static final String PREFS_NAME = "KioskPrefs";
    private static final String KEY_UNLOCK_EXPIRY = "unlock_expiry";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable relockRunnable = this::relockDevice;
    
    private final Runnable clockRunnable = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 10000); 
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

        tvStationName = findViewById(R.id.tv_station_name);
        tvHourlyRate = findViewById(R.id.tv_hourly_rate);
        tvStationStatusValue = findViewById(R.id.tv_station_status_value);
        tvWelcomeTitle = findViewById(R.id.tv_welcome_title);
        tvPrepaidDuration = findViewById(R.id.tv_prepaid_duration);

        setupPinNavigation();
        setupStyledFooter();
        setupConnectivityMonitor();

        devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MyDeviceAdminReceiver.class);

        configureKiosk();
        registerStation();
        listenForRemoteCommands();

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
                station.put("hourlyRate", 1000.0);
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
                        String name = snapshot.getString("name");
                        Double rate = snapshot.getDouble("hourlyRate");

                        if (name != null && tvStationName != null) {
                            tvStationName.setText(name);
                        }
                        if (rate != null && tvHourlyRate != null) {
                            tvHourlyRate.setText(String.format(Locale.getDefault(), "TZS %,.0f", rate));
                        }
                        if (remoteStatus != null && tvStationStatusValue != null) {
                            tvStationStatusValue.setText(remoteStatus);
                            if ("Available".equalsIgnoreCase(remoteStatus) || "Active".equalsIgnoreCase(remoteStatus)) {
                                tvStationStatusValue.setTextColor(ContextCompat.getColor(this, R.color.green_primary));
                            } else {
                                tvStationStatusValue.setTextColor(ContextCompat.getColor(this, R.color.red_primary));
                            }
                        }

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

        db.collection("unlock_codes")
                .whereEqualTo("code", enteredCode)
                .whereEqualTo("status", "PENDING")
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (!queryDocumentSnapshots.isEmpty()) {
                        for (QueryDocumentSnapshot document : queryDocumentSnapshots) {
                            String targetId = document.getString("deviceId");
                            if (targetId == null) targetId = document.getString("stationId");
                            
                            if (deviceId.equals(targetId)) {
                                fetchDurationAndProcessUnlock(document);
                            } else {
                                setVerifyingState(false);
                                String errorMsg = "Code belongs to another station";
                                statusText.setText(errorMsg);
                                clearPin();
                                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
                            }
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

    private void fetchDurationAndProcessUnlock(QueryDocumentSnapshot codeDoc) {
        // As a backup, fetch the latest station document to ensure we have the correct duration
        db.collection("stations").document(deviceId).get().addOnCompleteListener(task -> {
            long durationMinutes = 30L; // Default
            String player = codeDoc.getString("player");

            if (task.isSuccessful() && task.getResult() != null) {
                DocumentSnapshot stationDoc = task.getResult();
                // Try to find duration in station doc first (per user's snippet)
                durationMinutes = parseDurationFromDoc(stationDoc);
                
                // If not found in station, try code doc
                if (durationMinutes == 30L) {
                    durationMinutes = parseDurationFromDoc(codeDoc);
                }
            } else {
                // Fallback to code doc only
                durationMinutes = parseDurationFromDoc(codeDoc);
            }

            Log.d(TAG, "FINAL DURATION SELECTED: " + durationMinutes + " minutes");
            processUnlock(codeDoc, durationMinutes, player);
        });
    }

    private long parseDurationFromDoc(DocumentSnapshot doc) {
        String[] fields = {"prepaidDuration", "duration", "Duration"};
        for (String field : fields) {
            Object val = doc.get(field);
            if (val == null) continue;

            if (val instanceof Number) return ((Number) val).longValue();
            
            if (val instanceof String) {
                String s = (String) val;
                try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
                if (s.contains(":")) {
                    try {
                        String[] parts = s.split(":");
                        if (parts.length >= 2) return (long) Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
                    } catch (Exception ignored) {}
                }
            }
        }
        return 30L;
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

    private void processUnlock(QueryDocumentSnapshot codeDoc, long durationMinutes, String player) {
        long durationMs = durationMinutes * 60 * 1000;

        if (tvPrepaidDuration != null) {
            tvPrepaidDuration.setText(String.format(Locale.getDefault(), "%d Min", durationMinutes));
        }

        long startTime = System.currentTimeMillis();
        long expiryTime = startTime + durationMs;
        
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putLong(KEY_UNLOCK_EXPIRY, expiryTime)
                .apply();

        statusText.setText(R.string.code_accepted);
        Toast.makeText(this, R.string.device_unlocked, Toast.LENGTH_SHORT).show();
        
        if (tvWelcomeTitle != null) {
            tvWelcomeTitle.setText(R.string.welcome_title);
        }

        if (isInLockTaskMode()) {
            stopLockTask();
        }
        moveTaskToBack(true);
        
        handler.removeCallbacks(relockRunnable);
        handler.postDelayed(relockRunnable, durationMs);
        setVerifyingState(false);

        db.collection("unlock_codes").document(codeDoc.getId()).update("status", "ACTIVE");
        
        Map<String, Object> update = new HashMap<>();
        update.put("status", "Active");
        update.put("startTime", startTime);
        update.put("prepaidDuration", durationMinutes);
        update.put("player", player);
        db.collection("stations").document(deviceId).update(update);
    }

    private boolean isInLockTaskMode() {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        return activityManager.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
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
                        if (index < 5) pinInputs[index + 1].requestFocus();
                        else submitCode();
                    }
                }
                @Override
                public void afterTextChanged(Editable s) {}
            });
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
            public void onAvailable(@NonNull Network network) { runOnUiThread(() -> updateConnectivityUI(true)); }
            @Override
            public void onLost(@NonNull Network network) { runOnUiThread(() -> updateConnectivityUI(false)); }
        };
        Network activeNetwork = connectivityManager.getActiveNetwork();
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(activeNetwork);
        updateConnectivityUI(caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
    }

    private void updateConnectivityUI(boolean isOnline) {
        if (tvOnlineStatus != null) {
            tvOnlineStatus.setText(isOnline ? R.string.online : R.string.offline);
            tvOnlineStatus.setTextColor(ContextCompat.getColor(this, isOnline ? R.color.green_primary : R.color.red_primary));
        }
        if (vOnlineDot != null) vOnlineDot.setBackgroundResource(isOnline ? R.drawable.bg_circle_green : R.drawable.bg_circle_red);
    }

    private void configureKiosk() {
        if (devicePolicyManager != null && devicePolicyManager.isDeviceOwnerApp(getPackageName())) {
            devicePolicyManager.setLockTaskPackages(adminComponent, new String[]{getPackageName()});
            devicePolicyManager.setKeyguardDisabled(adminComponent, true);
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT);
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET);
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER);
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                devicePolicyManager.setLockTaskFeatures(adminComponent, 0); 
            }

            try {
                startLockTask();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start lock task", e);
            }
        }
    }

    private void relockDevice() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(KEY_UNLOCK_EXPIRY).apply();
        clearPin();
        handler.removeCallbacks(relockRunnable);
        db.collection("stations").document(deviceId).update("status", "Available");
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
        if (devicePolicyManager != null && devicePolicyManager.isDeviceOwnerApp(getPackageName())) {
            try { startLockTask(); } catch (Exception ignored) {}
        }
    }

    private void clearPin() {
        for (EditText et : pinInputs) et.setText("");
        pinInputs[0].requestFocus();
    }

    private boolean isCurrentlyUnlocked() {
        long expiry = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getLong(KEY_UNLOCK_EXPIRY, 0);
        return System.currentTimeMillis() < expiry;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!isCurrentlyUnlocked()) {
            int keyCode = event.getKeyCode();
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9 ||
                keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9 ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DEL) {
                return super.dispatchKeyEvent(event);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!isCurrentlyUnlocked()) {
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9 ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DEL) {
                return super.onKeyDown(keyCode, event);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && !isCurrentlyUnlocked()) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(clockRunnable);
        handler.post(clockRunnable);

        if (connectivityManager != null && networkCallback != null) {
            NetworkRequest networkRequest = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
        }

        long expiry = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getLong(KEY_UNLOCK_EXPIRY, 0);
        long timeLeft = expiry - System.currentTimeMillis();

        if (timeLeft > 0) {
            if (isInLockTaskMode()) stopLockTask();
            handler.removeCallbacks(relockRunnable);
            handler.postDelayed(relockRunnable, timeLeft);
        } else {
            if (devicePolicyManager != null && devicePolicyManager.isDeviceOwnerApp(getPackageName())) {
                try { startLockTask(); } catch (Exception ignored) {}
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockRunnable);
        if (connectivityManager != null && networkCallback != null) connectivityManager.unregisterNetworkCallback(networkCallback);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(relockRunnable);
        if (stationListener != null) stationListener.remove();
    }
}
