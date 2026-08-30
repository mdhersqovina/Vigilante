package com.example.kiosk;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(
            Context context,
            Intent intent) {

        if (Intent.ACTION_BOOT_COMPLETED.equals(
                intent.getAction())) {

            Intent kioskIntent =
                    new Intent(
                            context,
                            MainActivity.class
                    );

            kioskIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_CLEAR_TOP |
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
            );

            context.startActivity(kioskIntent);
        }
    }
}