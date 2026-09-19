package com.krishna.assistant;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.util.Log;

import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════
 * APP LAUNCHER — koi bhi app dhoondho aur kholo
 *
 * ⭐ YEH KAM ACCESSIBILITY SERVICE KE BINAA BHI KARTA HAI.
 * Purane code me app-open ko accessibility par depend karna padta tha —
 * OPPO accessibility service chupke se band kar deta tha, isliye
 * "koi app open nahi ho rahi" thi. Ab app-open hamesha kaam karta hai.
 *
 * Android 11+ (package visibility) par bhi kaam karta hai
 * kyunki manifest me QUERY_ALL_PACKAGES + <queries> hai.
 * ═══════════════════════════════════════════════════════════
 */
public class AppLauncher {

    private static final String TAG = "KrishnaApp";

    /**
     * "whatsapp" → package name. Pehle Constants ke map me dekho,
     * phir installed apps ke label se fuzzy match karo.
     */
    public static String resolveApp(Context ctx, String name) {
        if (name == null || name.trim().isEmpty()) return null;
        Context app = ctx.getApplicationContext();
        String n = name.toLowerCase().trim();
        String direct = Constants.APP_PACKAGES.get(n);
        if (direct != null) return direct;

        // Fuzzy: installed apps ke labels se match
        String best = null;
        int bestScore = 0;
        try {
            List<PackageInfo> pkgs = app.getPackageManager().getInstalledPackages(0);
            for (PackageInfo pi : pkgs) {
                try {
                    if (pi.applicationInfo == null) continue;
                    String label = app.getPackageManager()
                            .getApplicationLabel(pi.applicationInfo).toString().toLowerCase();
                    String pkgName = pi.packageName == null ? "" : pi.packageName.toLowerCase();
                    int score = 0;
                    if (label.equals(n)) score = 100;
                    else if (label.contains(n)) score = 80;
                    else if (n.contains(label) && label.length() > 2) score = 60;
                    if (pkgName.contains(n)) score = Math.max(score, 40);
                    if (score > bestScore) {
                        bestScore = score;
                        best = pi.packageName;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "fuzzy resolve fail", e);
        }
        return best;
    }

    /**
     * PACKAGE RESOLVE — WhatsApp ke 2 versions hote hain:
     *  - Regular:  com.whatsapp
     *  - Business: com.whatsapp.w4b
     * Agar preferred package installed nahi hai to doosra try karo.
     */
    public static String resolvePackage(Context ctx, String pkg) {
        try {
            Context app = ctx.getApplicationContext();
            if (app.getPackageManager().getLaunchIntentForPackage(pkg) != null) return pkg;
            String alt = null;
            if ("com.whatsapp".equals(pkg)) alt = "com.whatsapp.w4b";
            else if ("com.whatsapp.w4b".equals(pkg)) alt = "com.whatsapp";
            if (alt != null && app.getPackageManager().getLaunchIntentForPackage(alt) != null) {
                Log.i(TAG, "Package resolve: " + pkg + " → " + alt);
                return alt;
            }
        } catch (Exception ignored) {}
        return pkg;
    }

    /** App kholo (accessibility service ki zaroorat NAHI) */
    public static boolean openApp(Context ctx, String pkg) {
        if (pkg == null) return false;
        Context app = ctx.getApplicationContext();
        pkg = resolvePackage(app, pkg);
        try {
            Intent li = app.getPackageManager().getLaunchIntentForPackage(pkg);
            if (li == null) {
                Log.w(TAG, "No launch intent for " + pkg);
                return false;
            }
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            app.startActivity(li);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "openApp fail: " + pkg, e);
            return false;
        }
    }
}
