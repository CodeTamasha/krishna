package com.krishna.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * BOOT RECEIVER — phone restart hone ke baad Krishna khud start ho jata hai
 * (sirf agar user ne pehle se "Krishna Start" dabaya tha)
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
        try {
            Log.i(TAG, "Boot detected — Krishna start ho raha hai");
            context.startForegroundService(new Intent(context, VoiceListenerService.class));
            context.startForegroundService(new Intent(context, FloatingButtonService.class));
            context.startForegroundService(new Intent(context, AudioPlaybackService.class));
        } catch (Exception e) {
            Log.e(TAG, "boot start fail", e);
        }
    }
}
