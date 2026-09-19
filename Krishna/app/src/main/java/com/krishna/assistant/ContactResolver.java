package com.krishna.assistant;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * ═══════════════════════════════════════════════════════════
 * CONTACT RESOLVER — naam se number + seedha call
 *
 * ⭐ Accessibility service ke BINAA kaam karta hai:
 *  1. "Rupesh ko call kar" → Contacts database se best match number
 *  2. ACTION_CALL se direct call (DIAL fallback)
 *
 * Purana dialer UI-clicking ab sirf fallback hai (jab number
 * contacts me na mile, jaise naya number).
 *
 * READ_CONTACTS permission chahiye — wizard me request hoti hai.
 * Permission na ho to null/false return (fallback use hoga).
 * ═══════════════════════════════════════════════════════════
 */
public class ContactResolver {

    private static final String TAG = "KrishnaContact";

    /**
     * Naam se best matching phone number dhoondho.
     * Score: exact name match > starts-with > contains,
     * + is number par jitni baar call hui thi (TIMES_USED).
     */
    public static String findNumberByName(Context ctx, String name) {
        if (ctx == null || name == null || name.trim().isEmpty()) return null;
        Context app = ctx.getApplicationContext();
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CONTACTS missing — contact search skip");
            return null;
        }
        String n = name.trim().toLowerCase();
        String best = null;
        int bestScore = -1;
        Cursor c = null;
        try {
            c = app.getContentResolver().query(
                    ContactsContract.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.Phone.NUMBER,
                            ContactsContract.Phone.DISPLAY_NAME,
                            ContactsContract.Phone.TIMES_USED
                    },
                    ContactsContract.Phone.DISPLAY_NAME + " LIKE ?",
                    new String[]{"%" + name.trim() + "%"},
                    null);
            if (c == null) return null;
            int iNum = c.getColumnIndex(ContactsContract.Phone.NUMBER);
            int iName = c.getColumnIndex(ContactsContract.Phone.DISPLAY_NAME);
            int iUsed = c.getColumnIndex(ContactsContract.Phone.TIMES_USED);
            while (c.moveToNext()) {
                String num = iNum >= 0 ? c.getString(iNum) : null;
                String disp = iName >= 0 ? c.getString(iName) : null;
                if (num == null || num.trim().isEmpty()) continue;
                int score = 0;
                if (disp != null) {
                    String d = disp.trim().toLowerCase();
                    if (d.equals(n)) score = 100;
                    else if (d.startsWith(n)) score = 80;
                    else if (d.contains(n)) score = 60;
                }
                int used = 0;
                try {
                    if (iUsed >= 0) used = c.getInt(iUsed);
                } catch (Exception ignored) {}
                score += Math.min(used, 20);
                if (score > bestScore) {
                    bestScore = score;
                    best = num.trim();
                }
            }
            if (best != null) Log.i(TAG, "Contact match: " + name + " → " + best);
        } catch (Exception e) {
            Log.e(TAG, "contact search fail", e);
        } finally {
            if (c != null) {
                try { c.close(); } catch (Exception ignored) {}
            }
        }
        return best;
    }

    /** Seedha number par call — ACTION_CALL, fail ho to ACTION_DIAL */
    public static boolean callNumber(Context ctx, String number) {
        if (number == null || number.trim().isEmpty()) return false;
        Context app = ctx.getApplicationContext();
        String clean = number.trim();
        try {
            Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + clean));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "ACTION_CALL fail (CALL_PHONE permission?) — DIAL try");
            try {
                Intent d = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + clean));
                d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(d);
                return true;
            } catch (Exception e2) {
                Log.e(TAG, "call fail", e2);
                return false;
            }
        }
    }
}
