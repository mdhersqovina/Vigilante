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

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.Source;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Kiosk_MainActivity";

    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;
    private FirebaseFirestore db;
    private String deviceId;
    private ListenerRegistration stationListener;

    private final EditText[] pinInputs = new EditText[6];
    private TextView statusText;
    private TextView tvStationName;
    private TextView tvHourlyRate;
    private TextView tvStationStatusValue;
    private TextView tvPrepaidDuration;
    private TextView tvOnlineStatus;
    private View vOnlineDot;
    private View unlockButton;

    private boolean isVerifying = false;
    private boolean isUnlockedByServer = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Force Firestore to NOT store data locally
        db = FirebaseFirestore.getInstance();
        FirebaseFirestoreSettings settings = new FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(false)
                .build();
        db.setFirestoreSettings(settings);

        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        Log.i(TAG, "Device Identity: " + deviceId);

        // 2. Check for registration and session
        checkRegistrationAndSession();

        setContentView(R.layout.activity_main);

        // Ensure background service is running
        ContextCompat.startForegroundService(this, new Intent(this, KioskService.class));

        initViews();
        setupPinNavigation();
        setupConnectivityMonitor();

        devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MyDeviceAdminReceiver.class);

        configureKiosk();
        listenForRemoteCommands();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!isUnlockedByServer) {
                    Toast.makeText(MainActivity.this, R.string.device_is_locked, Toast.LENGTH_SHORT).show();
                } else {
                    finish();
                }
            }
        });
    }

    private void checkRegistrationAndSession() {
        Log.i(TAG, "Checking server for registration: " + deviceId);
        db.collection("stations").document(deviceId).get(Source.SERVER).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                DocumentSnapshot doc = task.getResult();
                if (doc != null && doc.exists()) {
                    Log.i(TAG, "Station registered. Checking session...");
                    processActiveSession(doc);
                } else {
                    Log.i(TAG, "Station NOT registered. Redirecting to Registration flow.");
                    startActivity(new Intent(this, RegistrationActivity.class));
                    finish();
                }
            } else {
                Log.e(TAG, "Firestore Server connection failed!", task.getException());
                // In a kiosk, we might want to stay here and show a "Check Connection" message
                if (statusText != null) statusText.setText("Connection Failed - Check Internet");
            }
        });
    }

    private void processActiveSession(DocumentSnapshot doc) {
        if ("Active".equalsIgnoreCase(doc.getString("status"))) {
            Long startLong = doc.getLong("startTime");
            long startTime = startLong != null ? startLong : 0;
            long dur = parseDuration(doc);
            long now = System.currentTimeMillis();

            if (now < (startTime + (dur * 60000))) {
                Log.i(TAG, "Verified ACTIVE session. Unlocking.");
                isUnlockedByServer = true;
                launchAndroidHome();
                finish();
                return;
            }
        }
        isUnlockedByServer = false;
        // Lock UI will be shown since we didn't finish()
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        
        if (intent.getBooleanExtra("FORCE_LOCK", false)) {
            isUnlockedByServer = false;
            configureKiosk();
        } else {
            checkRegistrationAndSession();
        }
    }

    private void initViews() {
        pinInputs[0] = findViewById(R.id.pin_1);
        pinInputs[1] = findViewById(R.id.pin_2);
        pinInputs[2] = findViewById(R.id.pin_3);
        pinInputs[3] = findViewById(R.id.pin_4);
        pinInputs[4] = findViewById(R.id.pin_5);
        pinInputs[5] = findViewById(R.id.pin_6);
        unlockButton = findViewById(R.id.unlockButton);
        statusText = findViewById(R.id.statusText);
        tvStationName = findViewById(R.id.tv_station_name);
        tvHourlyRate = findViewById(R.id.tv_hourly_rate);
        tvStationStatusValue = findViewById(R.id.tv_station_status_value);
        tvPrepaidDuration = findViewById(R.id.tv_prepaid_duration);
        tvOnlineStatus = findViewById(R.id.tv_online_status);
        vOnlineDot = findViewById(R.id.v_online_dot);
        
        if (tvStationStatusValue != null) {
            tvStationStatusValue.setText("Syncing...");
        }
        
        if (unlockButton != null) unlockButton.setOnClickListener(v -> submitCode());
        setupStyledFooter();
    }

    private void submitCode() {
        if (isVerifying) return;
        StringBuilder sb = new StringBuilder();
        for (EditText et : pinInputs) if (et != null) sb.append(et.getText().toString().trim());
        String enteredCode = sb.toString();

        if (enteredCode.length() == 6) {
            verifyUnlockCode(enteredCode);
        } else {
            Toast.makeText(this, "Enter 6-digit code", Toast.LENGTH_SHORT).show();
        }
    }

    private void verifyUnlockCode(String enteredCode) {
        isVerifying = true;
        statusText.setText(R.string.verifying_code_status);
        
        db.collection("unlock_codes")
                .whereEqualTo("code", enteredCode)
                .whereEqualTo("status", "PENDING")
                .get(Source.SERVER)
                .addOnSuccessListener(snapshots -> {
                    if (snapshots != null && !snapshots.isEmpty()) {
                        for (QueryDocumentSnapshot doc : snapshots) {
                            String target = doc.getString("deviceId");
                            if (target == null) target = doc.getString("stationId");
                            if (deviceId.equals(target)) {
                                fetchDurationAndUnlock(doc);
                            } else {
                                isVerifying = false;
                                statusText.setText(R.string.wrong_station_message);
                                clearPin();
                            }
                            return;
                        }
                    } else {
                        isVerifying = false;
                        statusText.setText(R.string.invalid_code_message);
                        clearPin();
                    }
                }).addOnFailureListener(e -> {
                    isVerifying = false;
                    statusText.setText(R.string.network_error_message);
                });
    }

    private void fetchDurationAndUnlock(QueryDocumentSnapshot codeDoc) {
        db.collection("stations").document(deviceId).get(Source.SERVER).addOnCompleteListener(task -> {
            long duration = -1L;
            if (task.isSuccessful() && task.getResult() != null) {
                duration = parseDuration(task.getResult());
            }
            if (duration <= 0) {
                duration = parseDuration(codeDoc);
            }
            if (duration <= 0) {
                duration = 30L;
            }
            performUnlock(codeDoc.getId(), duration, codeDoc.getString("player"));
        });
    }

    private long parseDuration(DocumentSnapshot doc) {
        String[] fields = {"durationMinutes", "prepaidDuration", "duration", "Duration"};
        for (String f : fields) {
            Object v = doc.get(f);
            if (v == null) continue;
            if (v instanceof Number) return ((Number) v).longValue();
            if (v instanceof String) {
                String s = (String) v;
                try { return Long.parseLong(s); } catch (Exception ignored) {}
                if (s.contains(":")) {
                    String[] p = s.split(":");
                    if (p.length >= 2) {
                        try { return Long.parseLong(p[0]) * 60 + Long.parseLong(p[1]); } catch (Exception ignored) {}
                    }
                }
            }
        }
        return -1L;
    }

    private void performUnlock(String codeId, long mins, String player) {
        long durationMs = mins * 60L * 1000L;
        long startTime = System.currentTimeMillis();
        long expiry = startTime + durationMs;

        Intent serviceIntent = new Intent(this, KioskService.class);
        serviceIntent.setAction("START_TIMER");
        serviceIntent.putExtra("DURATION_MS", durationMs);
        serviceIntent.putExtra("EXPIRY_TIME", expiry);
        ContextCompat.startForegroundService(this, serviceIntent);

        db.collection("unlock_codes").document(codeId).update("status", "ACTIVE");
        Map<String, Object> up = new HashMap<>();
        up.put("status", "Active");
        up.put("startTime", startTime);
        up.put("prepaidDuration", mins);
        up.put("player", player);
        db.collection("stations").document(deviceId).update(up);

        if (isInLockTaskMode()) {
            try { stopLockTask(); } catch (Exception ignored) {}
        }

        launchAndroidHome();
        isVerifying = false;
        finish();
    }

    private void launchAndroidHome() {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(homeIntent);
        } catch (Exception ignored) {}
    }

    private void listenForRemoteCommands() {
        if (stationListener != null) stationListener.remove();
        stationListener = db.collection("stations").document(deviceId)
                .addSnapshotListener((snapshot, e) -> {
                    if (e != null) return;
                    if (snapshot == null || !snapshot.exists()) {
                        // If record was deleted remotely, go back to registration
                        startActivity(new Intent(this, RegistrationActivity.class));
                        finish();
                        return;
                    }
                    String status = snapshot.getString("status");
                    String name = snapshot.getString("name");
                    Double rate = snapshot.getDouble("hourlyRate");
                    if (name != null && tvStationName != null) tvStationName.setText(name);
                    if (rate != null && tvHourlyRate != null) tvHourlyRate.setText(String.format(Locale.getDefault(), "TZS %,.0f", rate));
                    if (status != null && tvStationStatusValue != null) {
                        tvStationStatusValue.setText(status);
                        boolean availableOrActive = "Available".equalsIgnoreCase(status) || "Active".equalsIgnoreCase(status);
                        tvStationStatusValue.setTextColor(ContextCompat.getColor(this, availableOrActive ? R.color.green_primary : R.color.red_primary));
                        
                        if ("Available".equalsIgnoreCase(status)) {
                            isUnlockedByServer = false;
                            configureKiosk();
                        }
                    }
                });
    }

    private void configureKiosk() {
        if (devicePolicyManager != null && devicePolicyManager.isDeviceOwnerApp(getPackageName())) {
            devicePolicyManager.setLockTaskPackages(adminComponent, new String[]{getPackageName()});
            devicePolicyManager.setKeyguardDisabled(adminComponent, true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) devicePolicyManager.setLockTaskFeatures(adminComponent, 0);

            if (!isUnlockedByServer) {
                try { startLockTask(); } catch (Exception ignored) {}
            }
        }
    }

    private void clearPin() { for (EditText et : pinInputs) if (et != null) et.setText(""); if (pinInputs[0] != null) pinInputs[0].requestFocus(); }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isUnlockedByServer) return super.dispatchKeyEvent(event);
        int c = event.getKeyCode();
        if ((c >= KeyEvent.KEYCODE_0 && c <= KeyEvent.KEYCODE_9) || 
            (c >= KeyEvent.KEYCODE_DPAD_UP && c <= KeyEvent.KEYCODE_DPAD_CENTER) || 
            c == KeyEvent.KEYCODE_ENTER || c == KeyEvent.KEYCODE_DEL) {
            return super.dispatchKeyEvent(event);
        }
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkRegistrationAndSession();
        configureKiosk();
        updateClock();
    }

    private void updateClock() {
        TextView tv = findViewById(R.id.tv_clock);
        if (tv != null) tv.setText(new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date()));
    }

    private void setupPinNavigation() {
        for (int i = 0; i < 6; i++) {
            final int index = i;
            if (pinInputs[index] == null) continue;
            pinInputs[index].addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (s.length() == 1) { if (index < 5 && pinInputs[index+1] != null) pinInputs[index+1].requestFocus(); else if (index == 5) submitCode(); }
                }
                public void afterTextChanged(Editable s) {}
            });
            pinInputs[index].setOnKeyListener((v, k, e) -> {
                if (k == KeyEvent.KEYCODE_DEL && e.getAction() == KeyEvent.ACTION_DOWN && pinInputs[index].getText().length() == 0 && index > 0) {
                    if (pinInputs[index-1] != null) { pinInputs[index-1].requestFocus(); pinInputs[index-1].setText(""); }
                    return true;
                }
                return false;
            });
        }
    }

    private void setupConnectivityMonitor() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            cm.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                new ConnectivityManager.NetworkCallback() {
                    public void onAvailable(@NonNull Network n) { runOnUiThread(() -> {
                        updateConn(true);
                        checkRegistrationAndSession();
                    }); }
                    public void onLost(@NonNull Network n) { runOnUiThread(() -> updateConn(false)); }
                });
        }
    }

    private void updateConn(boolean on) {
        runOnUiThread(() -> {
            if (tvOnlineStatus != null) { tvOnlineStatus.setText(on ? R.string.online : R.string.offline); tvOnlineStatus.setTextColor(ContextCompat.getColor(this, on ? R.color.green_primary : R.color.red_primary)); }
            if (vOnlineDot != null) vOnlineDot.setBackgroundResource(on ? R.drawable.bg_circle_green : R.drawable.bg_circle_red);
        });
    }

    private void setupStyledFooter() {
        TextView f = findViewById(R.id.tvSecuredBy);
        if (f != null) f.setText(Html.fromHtml(getString(R.string.secured_by_vigilant), 0));
    }

    private boolean isInLockTaskMode() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        return am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    }

    @Override
    protected void onDestroy() {
        if (stationListener != null) { stationListener.remove(); stationListener = null; }
        super.onDestroy();
    }
}
