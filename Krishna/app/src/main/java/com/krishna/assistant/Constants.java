package com.krishna.assistant;

import android.content.Context;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════
 * KRISHNA — SAARE CONSTANTS + API KEYS
 * ═══════════════════════════════════════════════════════════
 * 🔑 SIRS YEH 4 LINES EDIT KARNI HAIN (keys daalne ke liye):
 *   1. MERCURY_API_KEY   → Inception Labs ki key
 *   2. FISH_API_KEY      → Fish Audio ki key (sirf TTS_ENGINE="fish" me chahiye)
 *   3. FIREBASE_DB_URL   → Firebase database URL (https://xxx-default-rtdb.firebaseio.com)
 *   4. FIREBASE_SECRET   → Firebase secret (rules public hain to KHALI chhod do)
 *
 * ⚡ SPEED: TTS_ENGINE = "android" (default) — instant + offline voice.
 *          "fish" — Fish Audio premium voice (thoda late — network se MP3 aata hai).
 * ═══════════════════════════════════════════════════════════
 */
public class Constants {

    // ═══════════════════ 🔑 API KEYS YAHAN DAALO ═══════════════════
    public static final String MERCURY_API_KEY = "";   // ← Mercury AI key yahan paste karo
    public static final String FISH_API_KEY = "";      // ← Fish Audio TTS key (sirf "fish" engine ke liye)
    public static final String FIREBASE_DB_URL = "";   // ← Firebase database URL yahan paste karo
    public static final String FIREBASE_SECRET = "";   // ← Firebase secret (public rules ho to khali chhodo)

    // ═══════════════ TTS ENGINE (SPEED ka main control) ═══════════════
    // "android" = INSTANT + OFFLINE (default — JARVIS jaisi speed ke liye)
    // "fish"    = Fish Audio premium voice (MP3 network se aata hai, 2-10s lag sakti hai)
    public static final String TTS_ENGINE = "android";

    // ═══════════════ MERCURY AI (The Brain) ═══════════════
    public static final String MERCURY_MODEL = "mercury-2";
    public static final int MERCURY_MAX_TOKENS = 800;      // voice reply chhota hota hai — 800 kaafi (latency kam)
    public static final double MERCURY_TEMPERATURE = 0.5;  // JSON action ke liye stable output

    // ═══════════════ FISH AUDIO TTS (premium voice option) ═══════════════
    public static final String FISH_MODEL = "s2.1-pro-free";
    public static final String FISH_REFERENCE_ID = "bcfb8b1e89984ac8ba6896bced34f7d0";

    // ═══════════════ SPEECH RECOGNITION ═══════════════
    // Hinglish ke liye "en-IN" best hai. Pure Hindi ke liye "hi-IN" try karo.
    public static final String SPEECH_LANGUAGE = "en-IN";

    // ═══════════════ APP STATES ═══════════════
    public static final int STATE_IDLE = 0;
    public static final int STATE_WAKE = 1;          // "Hey Krishna" wait kar raha hai
    public static final int STATE_LISTENING = 2;     // command sun raha hai
    public static final int STATE_PROCESSING = 3;    // AI/kaam chal raha hai
    public static final int STATE_SPEAKING = 4;      // TTS play ho raha hai
    public static final int STATE_STOPPING = 5;      // live mode band ho raha hai

    // ═══════════════ WAKE WORDS ═══════════════
    // SIRF Latin letters — kyunki STT (en-IN) results Latin me deta hai,
    // Devanagari entries kabhi match nahi hoti thi (purana bug).
    public static final String[] WAKE_WORDS = {
            "krishna", "krisna", "krishan", "krishaan",
            "crisna", "krisan", "khishna", "crishna"
    };

    // ═══════════════ LIVE MODE BAND KARNE KE PHRASES ═══════════════
    public static final String[] STOP_PHRASES = {
            "chup ho ja", "chup ho jao", "chup kar", "chup kar do", "chup",
            "band kar", "band karo", "band ho jao",
            "so ja", "so jao", "bye", "goodnight", "good night",
            "stop karo", "stop kar", "exit"
    };

    // ═══════════════ MUTE / UNMUTE PHRASES ═══════════════
    public static final String[] MUTE_PHRASES = {
            "mute kar", "mute karo", "mute", "silence", "kuch mat bolo", "mujhe mat bolo"
    };
    public static final String[] UNMUTE_PHRASES = {
            "unmute", "mute hata", "mute hatao", "wapas bolo", "bolna shuru"
    };

    // ═══════════════ NOTIFICATIONS PADHNE KE TRACKED APPS ═══════════════
    // (Instagram hata diya — story/like notifications spam the aur turant
    //  "message aaya" wali voice ko ghatiyaa kar dete the)
    public static final List<String> TRACKED_APPS = Arrays.asList(
            "com.whatsapp", "com.whatsapp.w4b",
            "com.google.android.apps.messaging", "com.android.mms",
            "org.telegram.messenger",
            "com.android.dialer", "com.google.android.dialer"
    );

    // ═══════════════ APP NAME → PACKAGE NAME MAP ═══════════════
    // Yahan se app khulte hain. Naya app add karna ho to yahan entry daal do.
    // (Zyada apps ke liye fuzzy matcher bhi hai — installed apps ka label match karta hai)
    public static final Map<String, String> APP_PACKAGES = new HashMap<>();
    static {
        APP_PACKAGES.put("whatsapp", "com.whatsapp");
        APP_PACKAGES.put("youtube", "com.google.android.youtube");
        APP_PACKAGES.put("yt", "com.google.android.youtube");
        APP_PACKAGES.put("instagram", "com.instagram.android");
        APP_PACKAGES.put("chrome", "com.android.chrome");
        APP_PACKAGES.put("settings", "com.android.settings");
        APP_PACKAGES.put("camera", "com.android.camera");
        APP_PACKAGES.put("calculator", "com.google.android.calculator");
        APP_PACKAGES.put("calc", "com.google.android.calculator");
        APP_PACKAGES.put("maps", "com.google.android.apps.maps");
        APP_PACKAGES.put("phone", "com.google.android.dialer");
        APP_PACKAGES.put("dialer", "com.google.android.dialer");
        APP_PACKAGES.put("contacts", "com.google.android.contacts");
        APP_PACKAGES.put("messages", "com.google.android.apps.messaging");
        APP_PACKAGES.put("sms", "com.android.mms");
        APP_PACKAGES.put("gmail", "com.google.android.gm");
        APP_PACKAGES.put("facebook", "com.facebook.katana");
        APP_PACKAGES.put("twitter", "com.twitter.android");
        APP_PACKAGES.put("x", "com.twitter.android");
        APP_PACKAGES.put("amazon", "com.amazon.mShop.android.shopping");
        APP_PACKAGES.put("flipkart", "com.flipkart.android");
        APP_PACKAGES.put("uber", "com.uber.client");
        APP_PACKAGES.put("ola", "com.olacabs.customer");
        APP_PACKAGES.put("swiggy", "in.swiggy");
        APP_PACKAGES.put("zomato", "zomato");
        APP_PACKAGES.put("spotify", "com.spotify.music");
        APP_PACKAGES.put("netflix", "com.netflix.mediaclient");
        APP_PACKAGES.put("prime video", "com.amazon.avod.thirdpartyclient");
        APP_PACKAGES.put("primevideo", "com.amazon.avod.thirdpartyclient");
        APP_PACKAGES.put("hotstar", "in.startv.hotstar");
        APP_PACKAGES.put("jio cinema", "com.jiocinema.android");
        APP_PACKAGES.put("gallery", "com.google.android.apps.photos");
        APP_PACKAGES.put("photos", "com.google.android.apps.photos");
        APP_PACKAGES.put("files", "com.google.android.documentsui");
        APP_PACKAGES.put("playstore", "com.android.vending");
        APP_PACKAGES.put("play store", "com.android.vending");
        APP_PACKAGES.put("truecaller", "com.truecaller");
        APP_PACKAGES.put("linkedin", "com.linkedin.android");
        APP_PACKAGES.put("reddit", "com.reddit.frontpage");
        APP_PACKAGES.put("sharechat", "com.sharechat");
        APP_PACKAGES.put("pinterest", "com.pinterest");
        APP_PACKAGES.put("ebay", "com.ebay.mobile");
        APP_PACKAGES.put("olx", "com.olxindia");
        APP_PACKAGES.put("paytm", "net.one97.paytm");
        APP_PACKAGES.put("gpay", "com.google.android.apps.nbu.paisa");
        APP_PACKAGES.put("google pay", "com.google.android.apps.nbu.paisa");
        APP_PACKAGES.put("phonepe", "in.phonepe.phonepe");
        APP_PACKAGES.put("moovit", "com.transitapp.moovit");
        APP_PACKAGES.put("weather", "com.google.android.apps.weather");
    }

    // ═══════════════ MUTE STATE (ab SharedPreferences me persist hota hai) ═══════════════
    private static volatile boolean muted = false;
    private static Context appCtx = null;

    /** Kisi bhi service/activity ke onCreate me call karo (idempotent).
     *  App restart ke baad bhi mute state yaad rahega. */
    public static void init(Context ctx) {
        appCtx = ctx.getApplicationContext();
        try {
            muted = appCtx.getSharedPreferences("krishna_prefs", Context.MODE_PRIVATE)
                    .getBoolean("krishna_muted", false);
        } catch (Exception ignored) {}
    }

    public static void setMuted(boolean v) {
        muted = v;
        try {
            if (appCtx != null) {
                appCtx.getSharedPreferences("krishna_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("krishna_muted", v).apply();
            }
        } catch (Exception ignored) {}
    }

    public static boolean isMuted() {
        return muted;
    }
}
