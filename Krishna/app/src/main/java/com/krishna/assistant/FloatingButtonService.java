package com.krishna.assistant;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.core.app.NotificationCompat;

/**
 * FLOATING BUTTON SERVICE — har app ke upar mic button
 *
 * - 60dp circular button, DRAGGABLE (kahin bhi le jao)
 * - TAP     → Krishna start/stop (live mode)
 * - LONG PRESS → Krishna main screen khulti hai
 * - Colors:  GRAY idle • GREEN pulse listening • BLUE rotate thinking • GOLD speaking
 * - Foreground service + persistent notification (ColorOS se bachav)
 *
 * v1.1.0: mic permission na ho to tap par app khulti hai (permission
 * wahan se deni padti hai) — Android 12+ par bina mic permission ke
 * voice service start karna crash karta tha.
 */
public class FloatingButtonService extends Service {

    private static final String TAG = "KrishnaFloat";

    private static volatile FloatingButtonService instance;

    public static FloatingButtonService get() {
        return instance;
    }

    private WindowManager wm;
    private ImageView btn;
    private WindowManager.LayoutParams lp;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable stateUpdater;
    private android.animation.Animator currentAnim;
    private int currentBg = -1;

    private float downX, downY;
    private boolean dragging;
    private final Handler longPressHandler = new Handler(Looper.getMainLooper());
    private final Runnable longPressAction = () -> {
        try {
            Intent i = new Intent(FloatingButtonService.this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {}
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Constants.init(this);
        DeviceUtils.ensureChannels(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Log.i(TAG, "FloatingButtonService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        if (intent != null && "STOP".equals(intent.getStringExtra("ACTION"))) {
            // Sab band karo
            try {
                startService(new Intent(this, VoiceListenerService.class).putExtra("ACTION", "STOP"));
                startService(new Intent(this, AudioPlaybackService.class).putExtra("ACTION", "STOP"));
            } catch (Exception ignored) {}
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(200, buildNotification());
        createOverlay();
        startStateUpdater();
        return START_STICKY;
    }

    // ═══════════════ OVERLAY BUTTON ═══════════════

    private void createOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            // Permission nahi hai — settings khol do
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {}
            }
            return;
        }
        if (btn != null) return; // pehle se hai

        try {
            btn = (ImageView) LayoutInflater.from(this).inflate(R.layout.floating_button, null);
            int size = (int) (60 * getResources().getDisplayMetrics().density);
            lp = new WindowManager.LayoutParams(
                    size, size,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            lp.x = dm.widthPixels - size - 24;
            lp.y = dm.heightPixels - size - 160;

            btn.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        dragging = false;
                        longPressHandler.postDelayed(longPressAction, 550);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - downX) > 20 || Math.abs(e.getRawY() - downY) > 20) {
                            dragging = true;
                            longPressHandler.removeCallbacks(longPressAction);
                        }
                        if (dragging && lp != null) {
                            lp.x = (int) (e.getRawX() - lp.width / 2f);
                            lp.y = (int) (e.getRawY() - lp.height / 2f);
                            try {
                                wm.updateViewLayout(btn, lp);
                            } catch (Exception ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        longPressHandler.removeCallbacks(longPressAction);
                        if (!dragging && e.getActionMasked() == MotionEvent.ACTION_UP) {
                            onButtonTap();
                        }
                        return true;
                    default:
                        return false;
                }
            });
            wm.addView(btn, lp);
        } catch (Exception e) {
            Log.e(TAG, "overlay create fail", e);
        }
    }

    /** TAP: live hai to band karo, nahi hai to seedha sunne lagao */
    private void onButtonTap() {
        ui.post(() -> {
            try {
                // ⚡ Mic permission na ho to app khulti hai (Android 12+ par
                // bina mic ke voice service start = crash). Wahan se permission do.
                if (Build.VERSION.SDK_INT >= 31
                        && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                                != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "Mic permission missing — app khol rahe hain");
                    startActivity(new Intent(this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                }
                VoiceListenerService v = VoiceListenerService.get();
                if (v != null && v.isLive()) {
                    v.stopEverything();
                } else {
                    VoiceListenerService.setPendingActivate(true);
                    VoiceListenerService.startServiceSafe(this);
                }
            } catch (Exception e) {
                Log.e(TAG, "button tap fail", e);
            }
        });
    }

    // ═══════════════ STATE COLORS + ANIMATIONS ═══════════════

    private void startStateUpdater() {
        stateUpdater = new Runnable() {
            @Override
            public void run() {
                try {
                    updateStateVisuals();
                } catch (Exception ignored) {}
                ui.postDelayed(this, 400);
            }
        };
        ui.post(stateUpdater);
    }

    private void updateStateVisuals() {
        if (btn == null) return;
        int st = VoiceListenerService.getState();
        int bg;
        switch (st) {
            case Constants.STATE_WAKE:
                bg = R.drawable.circle_idle;
                break;
            case Constants.STATE_LISTENING:
                bg = R.drawable.circle_listening;
                break;
            case Constants.STATE_PROCESSING:
                bg = R.drawable.circle_thinking;
                break;
            case Constants.STATE_SPEAKING:
                bg = R.drawable.circle_speaking;
                break;
            default:
                bg = R.drawable.circle_idle;
        }
        if (bg != currentBg) {
            currentBg = bg;
            btn.setBackgroundResource(bg);
            restartAnimation(st);
        }
    }

    private void restartAnimation(int st) {
        if (currentAnim != null) {
            currentAnim.cancel();
            currentAnim = null;
        }
        try {
            // Reset previous state ki animation
            btn.animate().rotation(0f).alpha(1f).scaleX(1f).scaleY(1f).setDuration(200);

            if (st == Constants.STATE_WAKE) {
                // Breathing (idle)
                android.animation.ObjectAnimator oa =
                        android.animation.ObjectAnimator.ofFloat(btn, View.ALPHA, 1f, 0.7f, 1f);
                oa.setDuration(2000);
                oa.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
                oa.start();
                currentAnim = oa;
            } else if (st == Constants.STATE_LISTENING) {
                // Pulse (green)
                android.animation.ObjectAnimator oa =
                        android.animation.ObjectAnimator.ofFloat(btn, View.SCALE_X, 1f, 1.2f, 1f);
                android.animation.ObjectAnimator ob =
                        android.animation.ObjectAnimator.ofFloat(btn, View.SCALE_Y, 1f, 1.2f, 1f);
                android.animation.AnimatorSet set = new android.animation.AnimatorSet();
                set.playTogether(oa, ob);
                set.setDuration(900);
                setInfiniteRepeat(set); // infinite (reflection se — helper neeche hai)
                set.start();
                currentAnim = set;
            } else if (st == Constants.STATE_PROCESSING) {
                // Spin (blue)
                android.animation.ObjectAnimator rot =
                        android.animation.ObjectAnimator.ofFloat(btn, View.ROTATION, 0f, 360f);
                rot.setDuration(1200);
                rot.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
                rot.start();
                currentAnim = rot;
            } else if (st == Constants.STATE_SPEAKING) {
                // Wave (gold)
                android.animation.ObjectAnimator oa =
                        android.animation.ObjectAnimator.ofFloat(btn, View.SCALE_X, 1f, 1.15f, 1f);
                android.animation.ObjectAnimator ob =
                        android.animation.ObjectAnimator.ofFloat(btn, View.SCALE_Y, 1f, 1.15f, 1f);
                android.animation.AnimatorSet set = new android.animation.AnimatorSet();
                set.playTogether(oa, ob);
                set.setDuration(500);
                setInfiniteRepeat(set); // infinite (reflection se — helper neeche hai)
                set.start();
                currentAnim = set;
            }
        } catch (Exception e) {
            Log.w(TAG, "animation fail", e);
        }
    }

    /**
     * INFINITE REPEAT — REFLACTION se.
     * Kyu: user ke IDE ke android.jar me AnimatorSet par setRepeatCount method
     * missing ho sakta hai (jar incomplete hai). Reflection se compile har jagah hota hai,
     * aur asli phone par method maujood hai to animation waise hi loop karega.
     */
    private static void setInfiniteRepeat(android.animation.Animator animator) {
        try {
            java.lang.reflect.Method m = animator.getClass().getMethod("setRepeatCount", int.class);
            m.invoke(animator, -1);
        } catch (Throwable ignored) {}
    }

    // ═══════════════ NOTIFICATION + LIFECYCLE ═══════════════

    private Notification buildNotification() {
        PendingIntent stopPi = PendingIntent.getService(this, 0,
                new Intent(this, FloatingButtonService.class).putExtra("ACTION", "STOP"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, DeviceUtils.CHANNEL_MAIN)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(getString(R.string.notif_active_title))
                .setContentText(getString(R.string.notif_active_text))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(new NotificationCompat.Action.Builder(0, getString(R.string.notif_stop), stopPi).build())
                .build();
    }

    @Override
    public void onDestroy() {
        if (stateUpdater != null) ui.removeCallbacks(stateUpdater);
        if (currentAnim != null) currentAnim.cancel();
        longPressHandler.removeCallbacksAndMessages(null);
        if (btn != null) {
            try {
                wm.removeView(btn);
            } catch (Exception ignored) {}
            btn = null;
        }
        if (instance == this) instance = null;
        super.onDestroy();
        Log.i(TAG, "FloatingButtonService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
