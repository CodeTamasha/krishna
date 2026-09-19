package com.krishna.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

/**
 * DEVICE UTILS — device ID, OPPO detect, notification channels,
 * battery optimization, OPPO auto-start, phone number check
 */
public class DeviceUtils {

    public static final String CHANNEL_MAIN = "krishna_main";

    /** Har device ka stable unique ID (Android ID + package name ka hash) */
    public static String getDeviceId(Context ctx) {
        try {
            String id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (id == null || id.isEmpty()) id = "unknown";
            String base = id + "@" + ctx.getPackageName();
            return Integer.toHexString(base.hashCode());
        } catch (Exception e) {
            return "device_" + Math.abs((int) (System.currentTimeMillis() % 1000000));
        }
    }

    /** OPPO / Realme / ColorOS detect karo (special handling ke liye) */
    public static boolean isOppo(Context ctx) {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        String d = Build.DISPLAY == null ? "" : Build.DISPLAY.toLowerCase();
        return m.contains("oppo") || m.contains("realme") || d.contains("coloros") || d.contains("realme");
    }

    /** Notification channel banao (har service apne onCreate me call karti hai) */
    public static void ensureChannels(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm == null) return;
                NotificationChannel main = new NotificationChannel(
                        CHANNEL_MAIN, "Krishna Services", NotificationManager.IMPORTANCE_HIGH);
                main.setDescription("Krishna ki background services ke liye");
                main.setShowBadge(false);
                nm.createNotificationChannel(main);
            }
        } catch (Exception ignored) {}
    }

    /**
     * ⭐ IMPORTANT NOTIFICATION — jab kuch ghalat ho (jaise OPPO ne
     * accessibility service band kar di) to user ko turant pata chale,
     * warna wo sochega Krishna kharaab hai.
     * POST_NOTIFICATIONS permission na ho to silently fail (log me rahega).
     */
    public static void notifyImportant(Context ctx, String title, String text) {
        try {
            ensureChannels(ctx);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification n = new NotificationCompat.Builder(ctx, CHANNEL_MAIN)
                    .setSmallIcon(R.drawable.ic_mic)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build();
            nm.notify(900 + (int) (System.currentTimeMillis() % 1000), n);
        } catch (Exception ignored) {}
    }

    /**
     * Battery optimization EXEMPT karo — OPPO/ColorOS me sabse zaroori step.
     * Bina iske background service kill ho jati hai.
     */
    public static void requestBatteryExemption(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            if (!pm.isIgnoringBatteryOptimizations(ctx.getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + ctx.getPackageName()));
                ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS);
                ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {}
        }
    }

    /**
     * OPPO Auto-Start settings kholo.
     * Path: Settings > App Management > Auto-Start > Krishna > ON
     */
    public static void openOppoAutoStart(Context ctx) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"));
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (Exception ignored) {}
        try {
            // Realme fallback
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    "com.heytap.permissionmanager",
                    "com.heytap.permissionmanager.startup.StartupActivity"));
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (Exception ignored) {}
        try {
            // General fallback: app ki details settings
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + ctx.getPackageName()));
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {}
    }

    /** Kya yeh string phone number jaisi hai (10-15 digits)? */
    public static boolean isPhoneNumber(String s) {
        if (s == null) return false;
        String d = s.replaceAll("[^0-9+]", "");
        return d.length() >= 10 && d.length() <= 15 && d.matches("[+]?[0-9]+");
    }

    /**
     * Abhi ka time, DEVICE ke apne timezone me (purane code me "IST" hardcode
     * tha — phone kisi aur timezone me ho to AI ko galat time context milta tha).
     */
    public static String nowIso() {
        try {
            return java.time.ZonedDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm zzz", java.util.Locale.US));
        } catch (Exception e) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    /** Package name se app ka friendly label lao */
    public static String friendlyAppName(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
