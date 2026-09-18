package com.example.kiosk;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Source;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class KioskService extends Service {
    private static final String TAG = "KioskService";
    private static final int RELOCK_REQUEST_CODE = 9999;
    private static final String CHANNEL_ID = "KioskServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    private FirebaseFirestore db;
    private String deviceId;
    private ListenerRegistration stationListener;

    @Override
    public void onCreate() {
        super.onCreate();
        
        // 1. Force Firestore to NOT store data locally
        db = FirebaseFirestore.getInstance();
        FirebaseFirestoreSettings settings = new FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(false)
                .build();
        db.setFirestoreSettings(settings);
        
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification("Kiosk Protection Active"));

        listenForRemoteCommands();
        // Force a server-side check on startup
        checkServerAndResumeTimer();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID, "Kiosk Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }

    private Notification createNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Vigilante Guard")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "START_TIMER".equals(intent.getAction())) {
            long durationMs = intent.getLongExtra("DURATION_MS", 0);
            long expiryTime = intent.getLongExtra("EXPIRY_TIME", System.currentTimeMillis() + durationMs);
            scheduleRelock(expiryTime);
        } else {
            checkServerAndResumeTimer();
        }
        return START_STICKY;
    }

    private void checkServerAndResumeTimer() {
        db.collection("stations").document(deviceId).get(Source.SERVER).addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                DocumentSnapshot doc = task.getResult();
                if (doc.exists() && "Active".equalsIgnoreCase(doc.getString("status"))) {
                    Long startLong = doc.getLong("startTime");
                    long startTime = startLong != null ? startLong : 0;
                    long durationMins = parseDuration(doc);
                    long expiry = startTime + (durationMins * 60 * 1000);
                    long now = System.currentTimeMillis();

                    if (now < expiry) {
                        Log.i(TAG, "Resuming timer from Server. Expiry in: " + ((expiry - now) / 1000) + "s");
                        scheduleRelock(expiry);
                        return;
                    }
                }
            }
            triggerRelock(); 
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
        return 30L;
    }

    private void scheduleRelock(long expiryTime) {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent lockIntent = new Intent(this, MainActivity.class);
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
                          Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | 
                          Intent.FLAG_ACTIVITY_CLEAR_TOP);
        lockIntent.putExtra("FORCE_LOCK", true);

        PendingIntent pi = PendingIntent.getActivity(this, RELOCK_REQUEST_CODE, lockIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, expiryTime, pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, expiryTime, pi);
        }
    }

    private void listenForRemoteCommands() {
        if (stationListener != null) stationListener.remove();
        stationListener = db.collection("stations").document(deviceId)
                .addSnapshotListener((snapshot, e) -> {
                    if (e != null) return;
                    if (snapshot == null || !snapshot.exists()) {
                        // Document deleted -> Trigger relock which will redirect to registration in MainActivity
                        triggerRelock();
                        return;
                    }
                    String status = snapshot.getString("status");
                    if ("Locked".equalsIgnoreCase(status) || "Shutdown".equalsIgnoreCase(status) || "Available".equalsIgnoreCase(status)) {
                        triggerRelock();
                    }
                });
    }

    private void triggerRelock() {
        Log.w(TAG, "TRIGGER RELOCK executing.");
        
        Intent lockIntent = new Intent(this, MainActivity.class);
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
                          Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | 
                          Intent.FLAG_ACTIVITY_CLEAR_TOP);
        lockIntent.putExtra("FORCE_LOCK", true);
        startActivity(lockIntent);
    }

    @Override
    public void onDestroy() {
        if (stationListener != null) stationListener.remove();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
