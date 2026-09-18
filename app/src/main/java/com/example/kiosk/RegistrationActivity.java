package com.example.kiosk;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class RegistrationActivity extends AppCompatActivity {

    private static final String TAG = "RegistrationActivity";

    private EditText etLoungeId, etRegCode;
    private Button btnRegister;
    private TextView tvRegStatus, tvDeviceId;
    private FirebaseFirestore db;
    private String deviceId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_registration);

        db = FirebaseFirestore.getInstance();
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        etLoungeId = findViewById(R.id.et_lounge_id);
        etRegCode = findViewById(R.id.et_reg_code);
        btnRegister = findViewById(R.id.btn_register);
        tvRegStatus = findViewById(R.id.tv_reg_status);
        tvDeviceId = findViewById(R.id.tv_device_id);

        tvDeviceId.setText("Device ID: " + deviceId);

        btnRegister.setOnClickListener(v -> attemptRegistration());
    }

    private void attemptRegistration() {
        String loungeId = etLoungeId.getText().toString().trim();
        String regCode = etRegCode.getText().toString().trim();

        if (loungeId.isEmpty()) {
            showError(getString(R.string.invalid_lounge_id));
            return;
        }

        if (regCode.length() != 6) {
            showError(getString(R.string.invalid_reg_code));
            return;
        }

        tvRegStatus.setVisibility(View.VISIBLE);
        tvRegStatus.setText("Verifying...");
        btnRegister.setEnabled(false);

        // 1. Fetch Lounge
        db.collection("lounges").document(loungeId).get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists()) {
                        verifyLoungeData(documentSnapshot, regCode, loungeId);
                    } else {
                        showError(getString(R.string.lounge_not_found));
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error fetching lounge", e);
                    showError("Connection Error: " + e.getMessage());
                });
    }

    private void verifyLoungeData(DocumentSnapshot loungeDoc, String enteredCode, String loungeId) {
        // 2. Verify Code
        String serverCode = loungeDoc.getString("registrationCode");
        if (serverCode == null || !serverCode.equals(enteredCode)) {
            showError(getString(R.string.code_mismatch));
            return;
        }

        // 3. Check Expiry
        Long expiry = loungeDoc.getLong("regCodeExpiry");
        if (expiry == null || expiry < System.currentTimeMillis()) {
            showError(getString(R.string.code_expired));
            return;
        }

        // 4. Verification Successful -> Create Station
        createStationRecord(loungeId, loungeDoc.getDouble("hourlyRate"));
    }

    private void createStationRecord(String loungeId, Double hourlyRate) {
        tvRegStatus.setText("Creating station...");

        Map<String, Object> stationData = new HashMap<>();
        stationData.put("loungeId", loungeId);
        stationData.put("name", "New Station");
        stationData.put("status", "Pending Setup");
        stationData.put("hourlyRate", hourlyRate != null ? hourlyRate : 1000.0);
        stationData.put("deviceId", deviceId);

        db.collection("stations").document(deviceId)
                .set(stationData)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, R.string.registration_success, Toast.LENGTH_LONG).show();
                    // Go to MainActivity
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error creating station", e);
                    showError("Registration failed: " + e.getMessage());
                });
    }

    private void showError(String message) {
        tvRegStatus.setVisibility(View.VISIBLE);
        tvRegStatus.setText(message);
        btnRegister.setEnabled(true);
    }
}
