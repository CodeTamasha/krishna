package com.krishna.assistant;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ═══════════════════════════════════════════════════════════
 * VOICE LISTENER SERVICE — KRISHNA KA DIMAG (live mode)
 *
 * GEMINI-JAIS LIVE FLOW:
 *
 *  [IDLE/WAKE]   → "Hey Krishna" sunte hi WAKE
 *  [LISTENING]   → command suno
 *  [PROCESSING]  → Mercury AI se reply + action
 *  [SPEAKING]    → TTS bolo (default Android TTS = INSTANT)
 *  [LISTENING]   → AUTOMATICALLY wapas sunne lago
 *  ... yeh LOOP tab tak chalta hai jab tak user na bole:
 *      "chup ho ja" / "band kar" / "so ja" / "bye"
 *
 * v1.1.0 FIXES:
 *  - MIC PERMISSION GUARD: Android 12+ par mic permission ke bina
 *    mic-type FGS startForeground SecurityException throw karta tha
 *    → AB APP CRASH NAHI KAREGI (check + try-catch dono)
 *  - FAST PIPELINE: reply bolna ab memory-save ke PEHLE hota hai;
 *    saara Firebase save background me (AudioPlaybackService queue)
 *  - ACCESSIBILITY WATCHDOG: OPPO accessibility band kare to
 *    notification + voice warning (warna user ko pata hi nahi chalta
 *    ki WhatsApp/YouTube kyun fail ho rahe hain)
 *  - MEMORY WARM-UP: service start hote hi context background me load
 *  - DETERMINISTIC STOP/MUTE: fixed 3s/4.5s delays ki jagah TTS
 *    complete hone par hi agla state (echo + timing bugs fix)
 *
 * ECHO/LOOP FIX (puri purani problem isliye thi):
 *  - Krishna BOLTAA WAQT mic POORA BAND (koi recognizer active nahi)
 *  - TTS khatam → 800ms grace → phir mic on
 *
 * APP BAND NA HO (ColorOS fix):
 *  - Foreground service + persistent notification
 *  - Processing ke time WakeLock (CPU so nahi sakta)
 *  - Har jagah try-catch — koi error service ko mat marto
 *  - Recognizer fail ho to auto-restart
 *
 * NOTE: RecognitionListener reflection (Proxy) se attach hota hai —
 * isliye ye code purane/naye saare SDK versions par compile hota hai.
 * ═══════════════════════════════════════════════════════════
 */
public class VoiceListenerService extends Service {

    private static final String TAG = "KrishnaVoice";

    private static volatile VoiceListenerService instance;
    private static volatile boolean pendingActivate = false;

    public static VoiceListenerService get() {
        return instance;
    }

    public static void setPendingActivate(boolean v) {
        pendingActivate = v;
    }

    /**
     * Service start karo (safe — killed ho to phir se).
     * ⚡ Android 12+ par mic-type foreground service MIC PERMISSION ke
     * bina start nahi kar sakte (startForeground SecurityException deta
     * hai → app crash). Isliye pehle permission check.
     */
    public static void startServiceSafe(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 31
                && app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "startServiceSafe: mic permission missing — start nahi kiya");
            return;
        }
        Intent i = new Intent(app, VoiceListenerService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(i);
            } else {
                app.startService(i);
            }
        } catch (Exception e) {
            try {
                app.startService(i);
            } catch (Exception e2) {
                Log.e(TAG, "service start fail", e2);
            }
        }
    }

    public static int getState() {
        return instance == null ? Constants.STATE_IDLE : instance.state;
    }

    // ═══════════════ STATE ═══════════════
    private volatile int state = Constants.STATE_IDLE;
    private volatile boolean liveMode = false;

    private SpeechRecognizer wakeRec = null;
    private SpeechRecognizer cmdRec = null;

    private final ExecutorService pipeline = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private MemoryManager memory; // onCreate() me init hoti hai — constructor me NAHI
    private PowerManager.WakeLock wakeLock;
    private ToneGenerator tone;
    private int wakeFailCount = 0;
    private int listenFailCount = 0; // listening mode ke consecutive fails (backoff ke liye)
    private long lastAccWarnAt = 0;

    public VoiceListenerService() {
        // ⚠️ YAHAN KOI CONTEXT KA KAAM NAHI — Android service ka constructor
        // usse pehle chalta hai jab context attach hota hai.
    }

    public boolean isLive() {
        return liveMode;
    }

    /**
     * ⭐ ACCESSIBILITY WATCHDOG — har 60s check:
     * OPPO/ColorOS accessibility service chupke se band kar deta hai.
     * Purane code me user ko pata bhi nahi chalta tha ("kuch accessibility
     * wala nahi ho raha"). Ab notification + (live mode me) voice warning.
     */
    private final Runnable accWatchdog = new Runnable() {
        @Override
        public void run() {
            try {
                if (KrishnaAccessibilityService.get() == null
                        && KrishnaAccessibilityService.wasEverConnected()
                        && System.currentTimeMillis() - lastAccWarnAt > 10 * 60 * 1000L) {
                    lastAccWarnAt = System.currentTimeMillis();
                    DeviceUtils.notifyImportant(VoiceListenerService.this,
                            "⚠️ Krishna ke haath OFF ho gaye",
                            "Phone ne Krishna ki Accessibility service band kar di hai. "
                                    + "WhatsApp message, YouTube aur UI controls ke liye "
                                    + "Settings → Accessibility → Krishna dobara ON karo. "
                                    + "(Apps kholna aur calls iske bina bhi chalte hain.)");
                    AudioPlaybackService a = AudioPlaybackService.get();
                    if (a != null && liveMode && !Constants.isMuted()) {
                        a.speak("Boss, ek important baat — meri accessibility service band ho gayi hai. "
                                + "Settings me Accessibility khol ke Krishna on kar lo, "
                                + "warna WhatsApp aur YouTube ke kaam nahi honge.");
                    }
                }
            } catch (Exception ignored) {}
            main.postDelayed(this, 60_000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Constants.init(getApplicationContext());
        // Ab context ready hai — MemoryManager yahi banate hain
        memory = new MemoryManager(getApplicationContext());
        DeviceUtils.ensureChannels(this);

        // ⚡ CONTEXT WARM-UP — background me Firebase se saara context load,
        // taaki pehli command bhi instant ho (AI se pehle koi network block nahi)
        final String warmDeviceId = DeviceUtils.getDeviceId(this);
        pipeline.submit(() -> memory.warmUp(warmDeviceId));

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "krishna:wakelock");
                wakeLock.setReferenceCounted(false);
            }
        } catch (Exception ignored) {}
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
        } catch (Exception ignored) {}
        // Audio service pehle se chalu kar do — TTS init ka time bachta hai,
        // aur reply ke waqt sab ready ho chuka hota hai
        try {
            Intent ai = new Intent(this, AudioPlaybackService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(ai);
            } else {
                startService(ai);
            }
        } catch (Exception ignored) {}
        main.postDelayed(accWatchdog, 30_000);
        Log.i(TAG, "VoiceListenerService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // ⚡ CRASH GUARD: Android 12+ par mic permission missing ho to
        // startForeground SecurityException throw karta hai. Purane code me
        // yeh try-catch me nahi tha → app crash. Ab safe.
        try {
            startForeground(100, buildNotification());
        } catch (Exception e) {
            Log.e(TAG, "startForeground fail — service stop", e);
            try {
                stopSelf();
            } catch (Exception ignored) {}
            return START_NOT_STICKY;
        }
        if (intent != null && "STOP".equals(intent.getStringExtra("ACTION"))) {
            stopConversationLoop();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (state == Constants.STATE_IDLE) {
            beginWake();
        }
        if (pendingActivate) {
            pendingActivate = false;
            main.postDelayed(this::manualActivate, 800);
        }
        return START_STICKY;
    }

    // ═══════════════ PUBLIC CONTROLS ═══════════════

    /** FAB tap / manual start — seedha command mode me (wake word ki zaroorat nahi) */
    public void manualActivate() {
        if (state == Constants.STATE_PROCESSING || state == Constants.STATE_SPEAKING) return;
        stopRecognizers();
        liveMode = true;
        playChime();
        main.postDelayed(this::beginCommandListening, 700);
    }

    /**
     * Live mode band karo — service chalta rehta hai,
     * aur 1.5s ke baad WAKE mode me wapas ( "Hey Krishna" phir se kaam karega )
     */
    public void stopEverything() {
        liveMode = false;
        stopConversationLoop();
        main.postDelayed(this::beginWake, 1500);
    }

    private void stopConversationLoop() {
        stopRecognizers();
        state = Constants.STATE_IDLE;
    }

    private void stopRecognizers() {
        try {
            if (cmdRec != null) {
                cmdRec.cancel();
                cmdRec.destroy();
            }
        } catch (Exception ignored) {}
        cmdRec = null;
        try {
            if (wakeRec != null) {
                wakeRec.cancel();
                wakeRec.destroy();
            }
        } catch (Exception ignored) {}
        wakeRec = null;
    }

    // ═══════════════ WAKE MODE: "Hey Krishna" ka wait ═══════════════

    private void beginWake() {
        if (liveMode) {
            beginCommandListening();
            return;
        }
        state = Constants.STATE_WAKE;
        stopRecognizers();
        try {
            wakeRec = SpeechRecognizer.createSpeechRecognizer(this);
            if (wakeRec == null) {
                main.postDelayed(this::beginWake, 2000);
                return;
            }
            if (!attachSttListener(wakeRec)) {
                // Listener attach nahi hua — sunna bekar hai, destroy + retry karo
                try { wakeRec.destroy(); } catch (Exception ignored) {}
                wakeRec = null;
                main.postDelayed(this::beginWake, 2000);
                return;
            }
            wakeRec.startListening(buildSpeechIntent());
            wakeFailCount = 0;
        } catch (Exception e) {
            Log.e(TAG, "wake recognizer start fail", e);
            main.postDelayed(this::beginWake, 2000);
        }
    }

    // ═══════════════ COMMAND MODE: user ki baat suno ═══════════════

    private void beginCommandListening() {
        if (!liveMode) {
            beginWake();
            return;
        }
        state = Constants.STATE_LISTENING;
        stopRecognizers();
        try {
            cmdRec = SpeechRecognizer.createSpeechRecognizer(this);
            if (cmdRec == null) {
                main.postDelayed(this::beginCommandListening, 2000);
                return;
            }
            if (!attachSttListener(cmdRec)) {
                // Listener attach nahi hua — sunna bekar hai, destroy + retry karo
                try { cmdRec.destroy(); } catch (Exception ignored) {}
                cmdRec = null;
                main.postDelayed(this::beginCommandListening, 2000);
                return;
            }
            cmdRec.startListening(buildSpeechIntent());
        } catch (Exception e) {
            Log.e(TAG, "command recognizer start fail", e);
            main.postDelayed(this::beginCommandListening, 2000);
        }
    }

    private Intent buildSpeechIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Constants.SPEECH_LANGUAGE);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        return i;
    }

    private String joinResults(android.os.Bundle results) {
        List<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) return "";
        return matches.get(0) == null ? "" : matches.get(0).trim();
    }

    // ═══════════════ STT LISTENER (reflection se attach) ═══════════════
    // IDE ke SDK me SpeechRecognizer.RecognitionListener (nested class) missing
    // ho sakti hai — isliye hum source me nested type ka NAAM use nahi karte.
    // Real phone par yeh class hamesha exist karti hai, isliye runtime 100% safe.

    private boolean attachSttListener(final SpeechRecognizer rec) {
        try {
            // TARIKA 1 (reliable): SpeechRecognizer.class ke public methods me se
            // "setRecognitionListener" dhundho — uska parameter type hi wahi
            // RecognitionListener interface hai.
            Method setter = null;
            for (Method m : SpeechRecognizer.class.getMethods()) {
                if ("setRecognitionListener".equals(m.getName())) {
                    setter = m;
                    break;
                }
            }
            Class<?> listenerIfc = (setter != null && setter.getParameterTypes().length > 0)
                    ? setter.getParameterTypes()[0] : null;
            // TARIKA 2 (fallback): seedha naam se
            if (listenerIfc == null) {
                try {
                    listenerIfc = Class.forName("android.speech.SpeechRecognizer$RecognitionListener");
                    setter = SpeechRecognizer.class.getMethod("setRecognitionListener", listenerIfc);
                } catch (Throwable ignored) {}
            }
            if (listenerIfc == null || setter == null) {
                Log.e(TAG, "attachSttListener: interface nahi mila");
                return false;
            }
            // Proxy — har callback hamare public methods par route hota hai
            ClassLoader cl = SpeechRecognizer.class.getClassLoader();
            if (cl == null) cl = getClass().getClassLoader();
            Object proxy = Proxy.newProxyInstance(
                    cl,
                    new Class<?>[]{ listenerIfc },
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object p, Method method, Object[] args) {
                            String name = method.getName();
                            try {
                                if ("onReadyForSpeech".equals(name)) {
                                    onReadyForSpeech(args != null && args.length > 0 ? (android.os.Bundle) args[0] : null);
                                } else if ("onBeginningOfSpeech".equals(name)) {
                                    onBeginningOfSpeech();
                                } else if ("onRmsChanged".equals(name)) {
                                    onRmsChanged(args != null && args.length > 0 ? ((Number) args[0]).floatValue() : 0f);
                                } else if ("onBufferReceived".equals(name)) {
                                    onBufferReceived(args != null && args.length > 0 ? (byte[]) args[0] : null);
                                } else if ("onEndOfSpeech".equals(name)) {
                                    onEndOfSpeech();
                                } else if ("onPartialResults".equals(name)) {
                                    onPartialResults(args != null && args.length > 0 ? (android.os.Bundle) args[0] : null);
                                } else if ("onResults".equals(name)) {
                                    onResults(args != null && args.length > 0 ? (android.os.Bundle) args[0] : null);
                                } else if ("onError".equals(name)) {
                                    onError(args != null && args.length > 0 ? ((Number) args[0]).intValue() : -1);
                                } else if ("hashCode".equals(name)) {
                                    return System.identityHashCode(p);
                                } else if ("equals".equals(name)) {
                                    return p == args[0];
                                } else if ("toString".equals(name)) {
                                    return "KrishnaSttListener";
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "stt callback " + name + " fail", e);
                            }
                            return null;
                        }
                    });
            // Attach karo
            setter.invoke(rec, proxy);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "attachSttListener fail", e);
            return false;
        }
    }

    // ═══════════════ SPEECH RECOGNIZER CALLBACKS ═══════════════

    public void onReadyForSpeech(android.os.Bundle params) {}

    public void onBeginningOfSpeech() {}

    public void onRmsChanged(float rmsdB) {}

    public void onBufferReceived(byte[] buffer) {}

    public void onEndOfSpeech() {}

    public void onPartialResults(android.os.Bundle partialResults) {
        try {
            if (state == Constants.STATE_WAKE) {
                String text = joinResults(partialResults);
                if (isWakeWord(text)) {
                    onWakeDetected();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "partial results fail", e);
        }
    }

    public void onResults(android.os.Bundle results) {
        try {
            listenFailCount = 0; // recognizer theek se chala — backoff reset
            String text = joinResults(results);
            if (BuildConfig.DEBUG) Log.i(TAG, "Recognizer result: \"" + text + "\" state=" + state);
            if (state == Constants.STATE_WAKE) {
                if (isWakeWord(text)) {
                    onWakeDetected();
                } else {
                    // Kuch aur bola — phir se wait karo
                    main.postDelayed(this::beginWake, 400);
                }
            } else if (state == Constants.STATE_LISTENING) {
                handleCommand(text);
            }
        } catch (Exception e) {
            Log.e(TAG, "onResults fail", e);
            continueConversation();
        }
    }

    public void onError(int errorCode) {
        Log.w(TAG, "Recognizer error: " + errorCode);
        // Cancel ke baad ka delayed error ho to ignore
        if (state == Constants.STATE_PROCESSING || state == Constants.STATE_SPEAKING
                || state == Constants.STATE_STOPPING) return;
        if (state == Constants.STATE_WAKE) {
            wakeFailCount++;
            long delay = wakeFailCount > 20 ? 30000 : 1500;
            Log.i(TAG, "wake mic retry in " + delay + "ms (fail #" + wakeFailCount + ")");
            main.postDelayed(this::beginWake, delay);
        } else if (state == Constants.STATE_LISTENING) {
            listenFailCount++;
            // Error 11 = service disconnected (OPPO STT service ko maar deta hai)
            // — thoda lamba wait karo taaki service recover ho jaye
            long base = (errorCode == 11) ? 3000 : 1500;
            long delay = Math.min(base * (1 + Math.min(listenFailCount, 5)), 15000);
            Log.i(TAG, "listen mic retry in " + delay + "ms (fail #" + listenFailCount + ")");
            main.postDelayed(this::beginCommandListening, delay);
        }
    }

    // ═══════════════ WAKE DETECTED → LIVE MODE ON ═══════════════

    private void onWakeDetected() {
        Log.i(TAG, "⚡ WAKE WORD DETECTED — live mode ON");
        stopRecognizers();
        playChime();
        liveMode = true;
        main.postDelayed(this::beginCommandListening, 650);
    }

    /** Chhota "haan Boss?" beep (ToneGenerator se — koi audio file nahi chahiye) */
    private void playChime() {
        try {
            if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP, 160);
            main.postDelayed(() -> {
                try {
                    if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_ACK, 200);
                } catch (Exception ignored) {}
            }, 220);
        } catch (Exception ignored) {}
    }

    // ═══════════════ COMMAND PROCESSING ═══════════════

    private void handleCommand(String text) {
        stopRecognizers();
        if (text == null || text.trim().isEmpty()) {
            continueConversation();
            return;
        }
        String t = text.trim();
        if (BuildConfig.DEBUG) Log.i(TAG, "🎤 Command: " + t);

        // STOP phrases → live mode band
        // ⚡ DETERMINISTIC: goodbye BOL ke khatam hone par hi wake mode (purana
        //    fixed 4500ms delay TTS se fast/slow dono case me galat hota tha)
        if (isStopCommand(t)) {
            state = Constants.STATE_STOPPING;
            liveMode = false;
            final String goodbye = "Theek hai Boss, main so raha hoon. Jab zaroorat ho to 'Hey Krishna' bolna.";
            pipeline.submit(() -> {
                speakNow(goodbye);
                main.post(this::beginWake);
            });
            return;
        }
        // MUTE
        if (isMuteOnCommand(t)) {
            Constants.setMuted(true);
            main.postDelayed(this::beginCommandListening, 500);
            return;
        }
        if (isMuteOffCommand(t)) {
            Constants.setMuted(false);
            state = Constants.STATE_SPEAKING;
            pipeline.submit(() -> {
                speakNow("Mute hata diya Boss, ab main bolunga.");
                continueConversation();
            });
            return;
        }

        // Normal command → AI pipeline
        state = Constants.STATE_PROCESSING;
        pipeline.submit(() -> runPipeline(t));
    }

    /** Koi line bolo — audio service auto-start + TTS ready wait (bg thread se) */
    private void speakNow(String text) {
        try {
            AudioPlaybackService a = AudioPlaybackService.startSafe(this);
            if (a != null) {
                a.waitTtsReady(2000);
                a.speakBlocking(text);
            }
        } catch (Exception e) {
            Log.e(TAG, "speakNow fail", e);
        }
    }

    /**
     * ⚡ FAST PIPELINE (v1.1.0):
     *   1. buildContext — 100% LOCAL (warm cache) — zero network
     *   2. Mercury AI call (yahi sabse lamba step hai)
     *   3. Action execute
     *   4. ⭐ REPLY Bolo — memory-save SE PEHLE (purane code me yahan
     *      8-10 Firebase calls block karte the → "sab slow" lagta tha)
     *   5. Memory save — BACKGROUND me (voice ko block nahi karta)
     */
    private void runPipeline(String userText) {
        if (BuildConfig.DEBUG) Log.i(TAG, "🔄 Pipeline start: " + userText);
        // Audio service guaranteed chalu karo — warna reply sunai nahi dega
        final AudioPlaybackService audio = AudioPlaybackService.startSafe(this);
        final String deviceId = DeviceUtils.getDeviceId(this);
        try {
            acquireWakeLock();

            // 1️⃣ CONTEXT (local — instant)
            List<ChatMessage> ctx = memory.buildContext(deviceId, userText);

            // 2️⃣ MERCURY AI se reply lao
            String reply = ApiHelper.callMercuryAI(ctx);
            if (reply == null) {
                speakVia(audio, "Mujhe thodi dikkat ho rahi hai Boss, ek baar phir try karo.");
                continueConversation();
                return;
            }

            // 3️⃣ Response parse karo (text + action)
            final ParsedResponse parsed = CommandParser.parse(reply);

            // 4️⃣ Action execute karo (agar mila)
            boolean actionOk = true;
            boolean explained = false;
            if (parsed.isAction) {
                try {
                    ActionExecutor executor = new ActionExecutor(this);
                    actionOk = executor.execute(parsed);
                    explained = executor.wasExplained();
                } catch (Exception e) {
                    Log.e(TAG, "action execute fail", e);
                    actionOk = false;
                }
            }

            // 5️⃣ ⭐ REPLY Bolo (abhi — memory save pehle nahi block karega)
            state = Constants.STATE_SPEAKING;
            speakVia(audio, parsed.spokenText);
            if (!actionOk && !explained) {
                speakVia(audio, "Boss, ek kaam nahi ho paya. Phir try karo ya manually kar lo.");
            }

            // 6️⃣ MEMORY SAVE — background me (facts + compression)
            try {
                memory.afterExchange(deviceId, userText, reply,
                        parsed.isAction ? parsed.action : null);
            } catch (Exception e) {
                Log.e(TAG, "memory save fail", e);
            }

            continueConversation();
        } catch (Exception e) {
            Log.e(TAG, "pipeline fail", e);
            speakVia(audio, "Kuch gadbad ho gayi Boss, phir try karo.");
            continueConversation();
        } finally {
            releaseWakeLock();
        }
    }

    private void speakVia(AudioPlaybackService audio, String text) {
        if (audio != null) {
            audio.speakBlocking(text);
        }
    }

    /**
     * 💡 LIVE LOOP ka magic: baat khatam → AUTOMATICALLY phir se suno.
     * Icon dabane ki zaroorat NAHI. Jab tak "chup ho ja" na bole, loop chalta hai.
     */
    private void continueConversation() {
        main.post(() -> {
            if (liveMode) {
                // 800ms grace — taaki TTS ki echo mic me na jaye
                main.postDelayed(this::beginCommandListening, 800);
            } else {
                beginWake();
            }
        });
    }

    // ═══════════════ WAKE/STOP/MUTE WORD CHECKS ═══════════════

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase()
                .replaceAll("[^a-z0-9\\u0900-\\u097F ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isWakeWord(String text) {
        String t = normalize(text);
        if (t.isEmpty()) return false;
        for (String w : Constants.WAKE_WORDS) {
            if (t.contains(w)) return true;
        }
        return false;
    }

    private boolean isStopCommand(String text) {
        String t = normalize(text);
        if (t.isEmpty()) return false;
        for (String p : Constants.STOP_PHRASES) {
            if (t.contains(p)) return true;
        }
        return false;
    }

    private boolean isMuteOnCommand(String text) {
        String t = normalize(text);
        for (String p : Constants.MUTE_PHRASES) {
            if (t.contains(p)) return true;
        }
        return false;
    }

    private boolean isMuteOffCommand(String text) {
        String t = normalize(text);
        for (String p : Constants.UNMUTE_PHRASES) {
            if (t.contains(p)) return true;
        }
        return false;
    }

    // ═══════════════ WAKE LOCK (ColorOS CPU ko mat sulaao) ═══════════════

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(90_000);
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
    }

    // ═══════════════ NOTIFICATION + LIFECYCLE ═══════════════

    private Notification buildNotification() {
        PendingIntent stopPi = PendingIntent.getService(this, 0,
                new Intent(this, VoiceListenerService.class).putExtra("ACTION", "STOP"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, DeviceUtils.CHANNEL_MAIN)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(getString(R.string.notif_active_title))
                .setContentText("Listening for commands")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(new NotificationCompat.Action.Builder(0, getString(R.string.notif_stop), stopPi).build())
                .build();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "VoiceListenerService onDestroy");
        main.removeCallbacks(accWatchdog);
        stopRecognizers();
        liveMode = false;
        state = Constants.STATE_IDLE;
        pipeline.shutdownNow();
        try {
            if (tone != null) tone.release();
        } catch (Exception ignored) {}
        tone = null;
        releaseWakeLock();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
