package com.krishna.assistant;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════
 * ACTION EXECUTOR — AI ka action JSON phone par chalaao
 *
 * Yeh pipeline thread par chalta hai (blocking OK — sleeps allowed).
 * Return: true = kaam ho gaya, false = nahi hua.
 *
 * v1.1.0 FIXES:
 *  - ⭐ open_app ab ACCESSIBILITY SERVICE KE BINAA chalta hai
 *    (AppLauncher) — "koi app open nahi ho rahi" fix.
 *  - ⭐ call_contact ab Pehle Contacts database se direct number
 *    milata hai (ContactResolver) — UI-clicking sirf fallback.
 *  - "explained" flag: agar action khud bataya ki kyun fail hua
 *    (jaise "app nahi mili"), pipeline apni generic apology nahi bolta.
 *
 * SAARE actions error-safe hain — koi bhi action crash nahi karta.
 * ═══════════════════════════════════════════════════════════
 */
public class ActionExecutor {

    private static final String TAG = "KrishnaAction";

    private final Context ctx;
    private final KrishnaAccessibilityService acc;
    private final AudioPlaybackService audio;
    private volatile boolean explained = false; // action ne khud user ko bataya?

    public ActionExecutor(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.acc = KrishnaAccessibilityService.get();
        this.audio = AudioPlaybackService.get();
    }

    /** Action ne khud user ko fail ka reason boliya? (pipeline generic apology skip kare) */
    public boolean wasExplained() {
        return explained;
    }

    /**
     * Accessibility service connected NAHI hai?
     * WhatsApp/YouTube/UI controls ke liye yeh chahiye (OPPO reinstall ke
     * baad aksar band kar deta hai). User ko BOLA hua clear message dete hain.
     * (App-open aur calls ab iske bina bhi chalte hain)
     */
    private boolean accMissing() {
        if (acc != null) return false;
        Log.w(TAG, "⚠️ Accessibility service CONNECTED nahi hai! Phone Settings → Accessibility → 'Krishna' ON karo.");
        explained = true;
        if (audio != null) {
            audio.speakBlocking("Boss, meri Accessibility service abhi off hai. Phone ke Settings me "
                    + "Accessibility chalo aur Krishna ko on karo, phir dobara try karo.");
        }
        return true;
    }

    private String str(ParsedResponse p, String key) {
        Map<String, String> params = p.params;
        String v = params.get(key);
        return v == null ? "" : v.trim();
    }

    /** Kya text me Devanagari hai? (Phone English script use karta hai) */
    private static boolean containsDevanagari(String s) {
        if (s == null) return false;
        return s.codePoints().anyMatch(cp -> cp >= 0x0900 && cp <= 0x097F);
    }

    public boolean execute(ParsedResponse p) {
        String action = p.action == null ? "" : p.action;
        if (BuildConfig.DEBUG) Log.i(TAG, "⚡ Execute: " + action + " params=" + p.params);
        try {
            switch (action) {
                case "open_app":
                    return doOpenApp(str(p, "app"));
                case "send_message":
                    return doSendMessage(p);
                case "type_text":
                    if (accMissing()) return false;
                    return acc.typeText(str(p, "text"));
                case "press_button":
                    if (accMissing()) return false;
                    return acc.clickByContentDesc(str(p, "name"));
                case "go_home":
                    if (accMissing()) return false;
                    acc.pressHome();
                    return true;
                case "go_back":
                    if (accMissing()) return false;
                    acc.pressBack();
                    return true;
                case "go_recent":
                    if (accMissing()) return false;
                    acc.pressRecent();
                    return true;
                case "search_youtube":
                    return doSearchYouTube(str(p, "query"));
                case "video_pause":
                case "video_play":
                    if (accMissing()) return false;
                    acc.togglePlayPause();
                    return true;
                case "set_volume":
                    return doVolume(str(p, "level"));
                case "set_brightness":
                    return doBrightness(str(p, "level"));
                case "toggle_wifi":
                    return doWifi(str(p, "state"));
                case "toggle_bluetooth":
                    return doBt(str(p, "state"));
                case "toggle_flashlight":
                    if (accMissing()) return false;
                    boolean torchOk = acc.toggleFlashlight(str(p, "state"));
                    if (!torchOk) {
                        explained = true;
                        if (audio != null) audio.speakBlocking("Boss, torch on nahi ho paya. Camera app se manually kar lo.");
                    }
                    return torchOk;
                case "take_screenshot":
                    if (accMissing()) return false;
                    boolean shotOk = acc.takeScreenshot();
                    if (!shotOk && Build.VERSION.SDK_INT < 29) {
                        explained = true;
                        if (audio != null) audio.speakBlocking("Boss, screenshot sirf Android 10+ par hi leta hoon.");
                    }
                    return shotOk;
                case "lock_screen":
                    if (accMissing()) return false;
                    acc.lockScreen();
                    return true;
                case "call_contact":
                    return doCall(str(p, "contact"));
                case "read_notifications":
                    return doReadNotifications();
                default:
                    Log.w(TAG, "Unknown action: " + action);
                    return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "execute fail: " + action, e);
            return false;
        }
    }

    // ═══════════════ APP OPEN (⭐ accessibility ke bina) ═══════════════

    private boolean doOpenApp(String name) {
        if (name.isEmpty()) return false;
        String pkg = AppLauncher.resolveApp(ctx, name);
        if (pkg == null) {
            Log.w(TAG, "App not found: " + name);
            explained = true;
            if (audio != null) audio.speakBlocking("Boss, \"" + name + "\" app mujhe device par nahi mili.");
            return false;
        }
        boolean ok = AppLauncher.openApp(ctx, pkg);
        if (ok) {
            try {
                FirebaseHelper.get().trackAppOpened(DeviceUtils.getDeviceId(ctx), name);
            } catch (Exception ignored) {}
        }
        return ok;
    }

    // ═══════════════ WHATSAPP MESSAGE ═══════════════

    private boolean doSendMessage(ParsedResponse p) {
        String contact = str(p, "contact");
        String message = str(p, "message");
        if (contact.isEmpty() || message.isEmpty()) return false;
        if (accMissing()) return false;

        // DEVANAGARI GUARD — phone English script use karta hai.
        // (System prompt me bhi AI ko mana kiya gaya hai)
        if (containsDevanagari(contact) || containsDevanagari(message)) {
            Log.w(TAG, "Devanagari detected — skip (phone English script me hai)");
            explained = true;
            if (audio != null) audio.speakBlocking("Boss, phone me message English letters me hi type karta hoon. "
                    + "Waise bolo, main waise hi bhej dunga.");
            return false;
        }

        boolean ok = acc.sendWhatsAppMessage(contact, message);
        if (ok) {
            try {
                FirebaseHelper.get().incrementMessagesSent(DeviceUtils.getDeviceId(ctx));
            } catch (Exception ignored) {}
        }
        return ok;
    }

    // ═══════════════ YOUTUBE ═══════════════

    private boolean doSearchYouTube(String query) {
        if (query.isEmpty()) return false;
        if (accMissing()) return false;
        return acc.searchYouTube(query);
    }

    // ═══════════════ CALL (⭐ direct number / contacts, UI fallback) ═══════════════

    private boolean doCall(String nameOrNumber) {
        if (nameOrNumber.isEmpty()) return false;
        String digits = nameOrNumber.replaceAll("[^0-9+]", "");
        // 1) Number diya hai → seedha call (accessibility ki zaroorat NAHI)
        if (DeviceUtils.isPhoneNumber(digits)) {
            return ContactResolver.callNumber(ctx, digits);
        }
        // 2) Naam hai → Contacts database se best match number (fast + reliable)
        String num = ContactResolver.findNumberByName(ctx, nameOrNumber);
        if (num != null) {
            return ContactResolver.callNumber(ctx, num);
        }
        // 3) Contacts me nahi mila → dialer UI me search karo (accessibility chahiye)
        if (accMissing()) return false;
        boolean ok = acc.callContact(nameOrNumber);
        if (!ok) {
            explained = true;
            if (audio != null) audio.speakBlocking("Boss, \"" + nameOrNumber + "\" contact mujhe nahi mila.");
        }
        return ok;
    }

    // ═══════════════ VOLUME ═══════════════

    private boolean doVolume(String level) {
        if (level.isEmpty()) return false;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        try {
            switch (level.toLowerCase()) {
                case "up":
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0);
                    break;
                case "down":
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0);
                    break;
                case "mute":
                    am.setStreamMute(AudioManager.STREAM_MUSIC, true);
                    break;
                case "unmute":
                    am.setStreamMute(AudioManager.STREAM_MUSIC, false);
                    break;
                default:
                    return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "volume fail", e);
            return false;
        }
    }

    // ═══════════════ BRIGHTNESS (WRITE_SETTINGS chahiye) ═══════════════

    private boolean doBrightness(String level) {
        if (!Settings.System.canWrite(ctx)) {
            try {
                ctx.startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + ctx.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {}
            explained = true;
            if (audio != null) audio.speakBlocking("Boss, brightness badalne ke liye WRITE_SETTINGS permission chahiye. Settings khol di hain.");
            return false;
        }
        try {
            float cur = Settings.System.getFloat(ctx.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS);
            if (Float.isNaN(cur)) cur = 0.5f;
            float next = "down".equalsIgnoreCase(level) ? cur - 0.2f : cur + 0.2f;
            next = Math.max(0.05f, Math.min(1f, next));
            Settings.System.putFloat(ctx.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, next);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "brightness fail", e);
            return false;
        }
    }

    // ═══════════════ WIFI ═══════════════

    private boolean doWifi(String state) {
        // Note: Android 10+ par third-party apps direct WiFi toggle nahi
        // kar sakte (system restriction) — isliye fail ho to settings kholte hain
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return false;
            boolean on = "on".equalsIgnoreCase(state);
            if (wm.isWifiEnabled() == on) return true;
            boolean ok = wm.setWifiEnabled(on);
            KrishnaAccessibilityService.sleep(800);
            if (wm.isWifiEnabled() == on) return ok;
            try {
                ctx.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {}
            explained = true;
            if (audio != null) audio.speakBlocking("Boss, WiFi settings khol di hain — wahan se "
                    + (on ? "on" : "off") + " kar lo (Android iska direct control nahi deta).");
            return false;
        } catch (Exception e) {
            try {
                ctx.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {}
            return false;
        }
    }

    // ═══════════════ BLUETOOTH ═══════════════

    private boolean doBt(String state) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return false;
            boolean on = "on".equalsIgnoreCase(state);
            if (adapter.isEnabled() == on) return true;
            if (Build.VERSION.SDK_INT >= 31) {
                if (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
            if (on) {
                return adapter.enable();
            } else {
                return adapter.disable();
            }
        } catch (Exception e) {
            try {
                ctx.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {}
            return false;
        }
    }

    // ═══════════════ NOTIFICATIONS PADHO ═══════════════

    private boolean doReadNotifications() {
        try {
            String[] last = FirebaseHelper.get().getLastNotification(DeviceUtils.getDeviceId(ctx));
            if (audio == null) return false;
            if (last != null) {
                audio.speakBlocking("Boss, " + last[0] + " par " + last[1] + " ka message tha: " + last[2]);
            } else {
                audio.speakBlocking("Boss, abhi koi naya message nahi aaya.");
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "read notifications fail", e);
            return false;
        }
    }
}
