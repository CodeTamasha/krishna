package com.krishna.assistant;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

/**
 * BOOT RECEIVER — phone restart hone ke baad Krishna khud start ho jata hai
 * (sirf agar user ne pehle se "Krishna Start" dabaya tha)
 *
 * fix7 (v1.1.0): Android 12+ par mic-type foreground service (VoiceListenerService)
 * mic permission ke bina start karna CRASH karta tha (SecurityException).
 * Isliye pehle mic permission check — na ho to sirf floating button +
 * audio service start hote hain, voice service tab start hoga jab
 * user mic permission de dega.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "KrishnaBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences sp = context.getSharedPreferences("krishna_prefs", Context.MODE_PRIVATE);
        if (!sp.getBoolean("krishna_enabled", false)) {
            Log.i(TAG, "Krishna disabled — boot start skip");
            return;
        }
        boolean micOk = Build.VERSION.SDK_INT < 31
                || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED;
        try {
            if (micOk) {
                Log.i(TAG, "Boot detected — Krishna start ho raha hai");
                context.startForegroundService(new Intent(context, VoiceListenerService.class));
            } else {
                Log.i(TAG, "Boot: mic permission missing — voice service skip (baaki services start)");
            }
            context.startForegroundService(new Intent(context, FloatingButtonService.class));
            context.startForegroundService(new Intent(context, AudioPlaybackService.class));
        } catch (Exception e) {
            Log.e(TAG, "boot start fail", e);
        }
    }
}
