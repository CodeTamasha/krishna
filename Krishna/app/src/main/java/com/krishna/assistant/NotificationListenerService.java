package com.krishna.assistant;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ═══════════════════════════════════════════════════════════
 * NOTIFICATION LISTENER — messages aate hi TURANT bolo
 *
 * TRACKED: WhatsApp, WhatsApp Business, SMS/Messages, Telegram,
 *          Phone/Call
 *
 * v1.1.0 FIXES:
 *  - ⭐ INSTANT VOICE: purane code me agar AudioPlaybackService nahi
 *    chal rahi thi (service killed / Krishna off), message CHUPCHAAP
 *    drop ho jata tha. Ab audio service nahi hai to khud START karo
 *    (startSafe) + wait + phir bolo. Message aate hi awaaz.
 *  - Saara kaam background thread par — system ke binder thread ko
 *    block nahi karte.
 *  - Apni app ki notifications ignore (loop se bachav).
 *
 * QUEUE LOGIC:
 *  - Krishna IDLE hai → turant bolo
 *  - Krishna baat/kaam kar raha hai → uski line khatam hote hi yeh
 *    bolo (single queue order sambhal leti hai)
 *
 * "kaunsa message aaya" → last notification wapas padhi jati hai
 * "iska reply kar"      → AI context me last notification hoti hai
 * ═══════════════════════════════════════════════════════════
 */
public class NotificationListenerService extends android.service.notification.NotificationListenerService {

    private static final String TAG = "KrishnaNotif";

    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    @Override
    public void onNotificationPosted(android.service.notification.StatusBarNotification sbn) {
        try {
            String pkg = sbn.getPackageName();
            if (pkg == null || !Constants.TRACKED_APPS.contains(pkg)) return;
            // Apni hi notifications mat bolo (infinite loop se bachav)
            if (pkg.equals(getPackageName())) return;

            Notification n = sbn.getNotification();
            if (n == null || n.extras == null) return;
            Bundle extras = n.extras;

            String title = extras.getString(Notification.EXTRA_TITLE, "");
            CharSequence textSeq = extras.getCharSequence(Notification.EXTRA_TEXT);
            CharSequence subSeq = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
            String text = textSeq == null ? "" : textSeq.toString();
            String sub = subSeq == null ? "" : subSeq.toString();
            if (text.isEmpty()) text = sub;

            if (title.isEmpty() && text.isEmpty()) return;

            String appName = friendlyName(pkg);
            String sender;
            if (!title.isEmpty()) {
                sender = title;
            } else if (text.length() > 30) {
                sender = text.substring(0, 30) + "…";
            } else {
                sender = text;
            }

            final String sentence;
            if ("Call".equals(appName)) {
                sentence = "Boss, " + (title.isEmpty() ? "ek" : title) + " ki call aa rahi hai.";
            } else {
                sentence = "Boss, " + appName + " par " + sender + " ka message aaya hai: " + text;
            }
            if (BuildConfig.DEBUG) Log.i(TAG, "📩 " + sentence);

            final String fApp = appName;
            final String fSender = sender;
            final String fText = text;

            // ⭐ SAARA KAAM BACKGROUND ME — aur voice GUARANTEED:
            // audio service nahi hai to startSafe se khud start + wait
            bg.submit(() -> {
                // 1) Firebase me save karo (agar configured hai — memory ke liye)
                try {
                    String deviceId = DeviceUtils.getDeviceId(NotificationListenerService.this);
                    FirebaseHelper.get().saveNotification(deviceId, fApp, fSender, fText);
                } catch (Exception e) {
                    Log.e(TAG, "save notification fail", e);
                }
                // 2) TURANT bolo
                try {
                    AudioPlaybackService audio = AudioPlaybackService.get();
                    if (audio == null) {
                        audio = AudioPlaybackService.startSafe(NotificationListenerService.this);
                    }
                    if (audio != null) {
                        audio.speak(sentence);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "speak notification fail", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "onNotificationPosted fail", e);
        }
    }

    @Override
    public void onNotificationRemoved(android.service.notification.StatusBarNotification sbn) {
        // Ignore
    }

    private String friendlyName(String pkg) {
        switch (pkg) {
            case "com.whatsapp":
            case "com.whatsapp.w4b":
                return "WhatsApp";
            case "org.telegram.messenger":
                return "Telegram";
            case "com.google.android.apps.messaging":
                return "Messages";
            case "com.android.mms":
                return "SMS";
            case "com.android.dialer":
            case "com.google.android.dialer":
                return "Call";
            default:
                return pkg;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }
}
