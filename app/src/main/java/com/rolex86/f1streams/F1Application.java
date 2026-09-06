package com.rolex86.f1streams;

import android.app.Application;
import android.content.Intent;
import android.os.Build;

public class F1Application extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Intent service = new Intent(this, ProxyKeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (Exception ignored) {
            // Playback still works on devices that do not require process keep-alive.
        }
    }
}
