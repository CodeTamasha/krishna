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
 * NOTIFICATION LISTENER — messages aate hi padho
 *
 * TRACKED: WhatsApp, WhatsApp Business, SMS/Messages, Telegram,
 *          Instagram, Phone/Call
 *
 * QUEUE LOGIC (user requirement):
 *  - Krishna IDLE hai → turant bolo
 *  - Krishna baat/kaam kar raha hai → queue me rakho,
 *    USKA KAAM KATME KE BAAD automatically padha jayega
 *  (AudioPlaybackService ki single queue yeh order guarantee karti hai)
 *
 * "kaunsa message aaya" → last notification wapas padhi jati hai
 * "iska reply kar"      → AI context me last notification hota hai,
 *                         wahi contact par reply bhej deta hai
 * ═══════════════════════════════════════════════════════════
 */
public class NotificationListenerService extends android.service.notification.NotificationListenerService {

    private static final String TAG = "KrishnaNotif";

    private final ExecutorService firebaseExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onNotificationPosted(android.service.notification.StatusBarNotification sbn) {
        try {
            String pkg = sbn.getPackageName();
            if (pkg == null || !Constants.TRACKED_APPS.contains(pkg)) return;

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

            String sentence;
            if (appName.equals("Call")) {
                sentence = "Boss, " + (title.isEmpty() ? "ek" : title) + " ki call aa rahi hai.";
            } else {
                sentence = "Boss, " + appName + " par " + sender + " ka message aaya hai: " + text;
            }
            Log.i(TAG, "📩 " + sentence);

            final String fApp = appName;
            final String fSender = sender;
            final String fText = text;

            // Firebase me save karo (background thread)
            firebaseExecutor.submit(() -> {
                try {
                    String deviceId = DeviceUtils.getDeviceId(NotificationListenerService.this);
                    FirebaseHelper.get().saveNotification(deviceId, fApp, fSender, fText);
                } catch (Exception e) {
                    Log.e(TAG, "save notification fail", e);
                }
            });

            // Bolo — queue khud order sambhal legi:
            // Krishna bol raha hai to pehle wo khatam, phir yeh message
            AudioPlaybackService audio = AudioPlaybackService.get();
            if (audio != null) {
                audio.speak(sentence);
            }
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
            case "com.instagram.android":
                return "Instagram";
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
        firebaseExecutor.shutdownNow();
        super.onDestroy();
    }
}
