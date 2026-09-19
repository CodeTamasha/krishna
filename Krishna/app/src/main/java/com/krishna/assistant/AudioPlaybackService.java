package com.krishna.assistant;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedList;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ═══════════════════════════════════════════════════════════
 * AUDIO PLAYBACK SERVICE — Fish Audio TTS + Android TTS fallback
 *
 * QUEUE SYSTEM:
 *  - Krishna bolte waqt naya message aaye to WO QUEUE me jaata hai
 *  - Jaise pehla khatam, waise agla — ek baar me do awaazein NAHI
 *  - Notifications bhi isi queue me jaate hain (order me)
 *
 * ECHO PREVENTION:
 *  - Yeh service tabhi bolti hai jab mic band hai
 *  - VoiceListenerService playback khatam hone ke BAAD hi sunne lagta hai
 *
 * Fish Audio fail ho → Android ka built-in TTS (robotic but chalega)
 * Volume: phone ki MEDIA volume follow karti hai (STREAM_MUSIC)
 * ═══════════════════════════════════════════════════════════
 */
public class AudioPlaybackService extends Service {

    private static final String TAG = "KrishnaAudio";
    private static volatile AudioPlaybackService instance;

    public static AudioPlaybackService get() {
        return instance;
    }

    private static class QueueItem {
        String text;
        CountDownLatch latch; // null = background item (notification), set = wait karna hai

        QueueItem(String t, CountDownLatch l) {
            text = t;
            latch = l;
        }
    }

    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();
    private final Object queueLock = new Object();
    private final LinkedList<QueueItem> queue = new LinkedList<>();

    private TextToSpeech androidTts;
    private volatile boolean ttsReady = false;
    private volatile MediaPlayer activePlayer;
    private volatile boolean draining = false;

    /**
     * QUEUE SUSPENSION — jab pipeline (kaam) chal raha hai:
     *  - notifications (latch==null) RUK JATE HAIN
     *  - replies (latch set) hamesha play hote hain
     * Isse kaam khatam hone ke BAAD hi pending messages padhe jaate hain.
     */
    private volatile boolean queueSuspended = false;

    public void suspendQueue() {
        queueSuspended = true;
    }

    public void resumeQueue() {
        queueSuspended = false;
        scheduleDrain(); // abhi bhi kuch pending notifications hain to ab bolo
    }

    public boolean hasPendingNotifications() {
        synchronized (queueLock) {
            for (QueueItem q : queue) {
                if (q.latch == null) return true;
            }
            return false;
        }
    }

    /**
     * Android TTS ready hone ka wait (sirf BACKGROUND thread se call karna).
     * Fish Audio ke liye zaroori nahi — par Fish fail hokar TTS fallback use
     * hone wala ho to TTS init complete hona chahiye, warna pehli line
     * chup-chaap drop ho jati hai.
     */
    public boolean waitTtsReady(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!ttsReady && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                return ttsReady;
            }
        }
        return ttsReady;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        DeviceUtils.ensureChannels(this);
        // Android TTS fallback ready karo (simple 2-arg constructor — purane SDK me bhi hai)
        try {
            androidTts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    ttsReady = (status == TextToSpeech.SUCCESS);
                }
            });
            if (androidTts != null) {
                androidTts.setLanguage(Locale.US);
            }
        } catch (Exception e) {
            Log.e(TAG, "Android TTS init fail", e);
        }
        Log.i(TAG, "AudioPlaybackService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        if (intent != null && "STOP".equals(intent.getStringExtra("ACTION"))) {
            stopPlayback();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(300, buildNotification());
        return START_STICKY;
    }

    // ═══════════════ PUBLIC API ═══════════════

    /** Normal queue add karo (notification waghera) — wait nahi karta */
    public boolean speak(String text) {
        if (text == null || text.trim().isEmpty() || Constants.isMuted()) return false;
        synchronized (queueLock) {
            queue.add(new QueueItem(text.trim(), null));
        }
        scheduleDrain();
        return true;
    }

    /**
     * BOLO AUR WAIT KARO — is specific line khatam hone tak block karta hai.
     * VoiceListenerService isse use karta hai taaki TTS ke BAAD hi mic on ho.
     */
    public boolean speakBlocking(String text) {
        if (text == null || text.trim().isEmpty() || Constants.isMuted()) return true;
        CountDownLatch latch = new CountDownLatch(1);
        synchronized (queueLock) {
            queue.add(new QueueItem(text.trim(), latch));
        }
        scheduleDrain();
        try {
            return latch.await(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return false;
        }
    }

    /**
     * Queue me pehle se rakha hua background item uthao (notification waghera).
     * Pipeline isse use karta hai: "pehle mera kaam khatam, phir notifications padho"
     */
    public String takeNextPending() {
        synchronized (queueLock) {
            for (QueueItem q : queue) {
                if (q.latch == null) {
                    queue.remove(q);
                    return q.text;
                }
            }
            return null;
        }
    }

    /** Kya abhi kuch bol raha hai / queue me kuch hai? */
    public boolean isBusy() {
        synchronized (queueLock) {
            return !queue.isEmpty();
        }
    }

    // ═══════════════ QUEUE DRAIN (ek single thread par — MediaPlayer thread-safe) ═══════════════

    private void scheduleDrain() {
        if (draining) return;
        draining = true;
        audioExecutor.submit(() -> {
            try {
                while (true) {
                    QueueItem item = pickNextPlayable();
                    if (item == null) return;
                    playOne(item.text);
                    if (item.latch != null) item.latch.countDown();
                    // Do lines ke beech thoda gap (natural pause)
                    try {
                        Thread.sleep(350);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            } finally {
                draining = false;
            }
        });
    }

    /**
     * Queue se agla PLAYABLE item uthao:
     *  - Latch wala (AI reply) hamesha playable
     *  - Notification (latch==null) tabhi jab queue suspended NAHI ho
     *  Kuch playable nahi (sab suspended notifications) → null (task khatam)
     */
    private QueueItem pickNextPlayable() {
        synchronized (queueLock) {
            if (queue.isEmpty()) return null;
            for (QueueItem q : queue) {
                if (q.latch != null || !queueSuspended) {
                    queue.remove(q);
                    return q;
                }
            }
            return null;
        }
    }

    private void playOne(String text) {
        Log.i(TAG, "Playing: " + (text.length() > 80 ? text.substring(0, 80) + "…" : text));
        if (playWithFish(text)) return;
        Log.w(TAG, "Fish fail — Android TTS fallback");
        playWithAndroidTts(text);
    }

    // ═══════════════ FISH AUDIO (primary voice) ═══════════════

    private boolean playWithFish(String text) {
        byte[] data = ApiHelper.callFishAudioTTS(text);
        if (data == null || data.length < 1000) return false;
        MediaPlayer mp = null;
        try {
            File f = new File(getCacheDir(), "krishna_tts.mp3");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(data);
            fos.close();

            mp = new MediaPlayer();
            mp.setDataSource(f.getAbsolutePath());
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC); // phone ki media volume
            final CountDownLatch done = new CountDownLatch(1);
            mp.setOnCompletionListener(m -> done.countDown());
            mp.setOnErrorListener((m, what, extra) -> {
                done.countDown();
                return true;
            });
            mp.prepare();
            mp.start();
            activePlayer = mp;
            done.await(90, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "MediaPlayer fail", e);
            return false;
        } finally {
            activePlayer = null;
            if (mp != null) {
                try {
                    mp.release();
                } catch (Exception ignored) {}
            }
        }
    }

    // ═══════════════ ANDROID TTS (fallback — Fish fail ho to) ═══════════════

    private void playWithAndroidTts(String text) {
        try {
            // TTS ready hone ka thoda intezaar karo
            int wait = 0;
            while (androidTts == null && wait < 10) {
                Thread.sleep(300);
                wait++;
            }
            if (androidTts == null) return;

            final CountDownLatch done = new CountDownLatch(1);
            // setUtteranceProgressListener — REFLACTION se set karte hain.
            // Kyu: kuch (incomplete/partial) android.jar me TextToSpeech par yeh method
            // missing hota hai to javac "cannot find symbol" deta hai.
            // Asli phone par method hamesha hota hai, to runtime me sab normal chalega.
            final UtteranceProgressListener upl = new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {}
                @Override
                public void onDone(String utteranceId) {
                    done.countDown();
                }
                @Override
                public void onError(String utteranceId) {
                    done.countDown();
                }
            };
            try {
                java.lang.reflect.Method m = android.speech.tts.TextToSpeech.class
                        .getMethod("setUtteranceProgressListener", UtteranceProgressListener.class);
                m.invoke(androidTts, upl);
            } catch (Throwable ignored) {
                // Kisi bhi wajah se set na ho to done.await(60s) hi safety net hai
            }
            androidTts.speak(text, TextToSpeech.QUEUE_ADD, null, "krishna_" + System.currentTimeMillis());
            done.await(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.e(TAG, "AndroidTTS fail", e);
        }
    }

    // ═══════════════ LIFECYCLE ═══════════════

    private void stopPlayback() {
        synchronized (queueLock) {
            queue.clear();
        }
        MediaPlayer mp = activePlayer;
        if (mp != null) {
            try {
                mp.stop();
                mp.release();
            } catch (Exception ignored) {}
        }
        if (androidTts != null) {
            try {
                androidTts.stop();
            } catch (Exception ignored) {}
        }
    }

    private Notification buildNotification() {
        PendingIntent stopPi = PendingIntent.getService(this, 0,
                new Intent(this, AudioPlaybackService.class).putExtra("ACTION", "STOP"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, DeviceUtils.CHANNEL_MAIN)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(getString(R.string.notif_active_title))
                .setContentText("Voice ready 🎙️")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(new NotificationCompat.Action(0, getString(R.string.notif_stop), stopPi))
                .build();
    }

    @Override
    public void onDestroy() {
        stopPlayback();
        if (androidTts != null) {
            try {
                androidTts.shutdown();
            } catch (Exception ignored) {}
            androidTts = null;
        }
        audioExecutor.shutdownNow();
        if (instance == this) instance = null;
        super.onDestroy();
        Log.i(TAG, "AudioPlaybackService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
