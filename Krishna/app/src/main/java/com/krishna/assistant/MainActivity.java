package com.krishna.assistant;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ═══════════════════════════════════════════════════════════
 * MAIN ACTIVITY — UI + PERMISSIONS + SETUP WIZARD
 *
 * Pehli baar chalu karne par 10-step wizard:
 *  1. Welcome
 *  2. Runtime permissions (mic, notifications, contacts, call, bt, camera...)
 *  3. Overlay permission (floating button)
 *  4. Accessibility service (phone control)
 *  5. Notification listener (messages padhna)
 *  6. Battery optimization band karo (OPPO)
 *  7. OPPO Auto-Start ON karo
 *  8. WRITE_SETTINGS (brightness)
 *  9. Recent apps me lock karo (manual)
 * 10. Done → Krishna start
 *
 * Saare permissions jo bhi features ke liye chahiye, yahan request hote hain.
 * ═══════════════════════════════════════════════════════════
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "KrishnaMain";
    private static final String PREFS = "krishna_prefs";

    private TextView tvStatus;
    private LinearLayout llChat;
    private ImageButton ibMic;
    private TextView btnSettings, btnStats, btnMute;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private volatile boolean polling = false;
    private int wizardStep = 0;

    // Runtime permissions ka launcher
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                StringBuilder sb = new StringBuilder("Permissions: ");
                for (Map.Entry<String, Boolean> e : result.entrySet()) {
                    String shortName = e.getKey().substring(e.getKey().lastIndexOf('.') + 1);
                    sb.append(shortName).append(e.getValue() ? "✓" : "✗").append(" ");
                }
                Toast.makeText(this, sb.toString(), Toast.LENGTH_LONG).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        DeviceUtils.ensureChannels(this);

        tvStatus = findViewById(R.id.tv_status);
        llChat = findViewById(R.id.ll_chat);
        ibMic = findViewById(R.id.ib_mic);
        btnSettings = findViewById(R.id.btn_settings);
        btnStats = findViewById(R.id.btn_stats);
        btnMute = findViewById(R.id.btn_mute);

        ibMic.setOnClickListener(v -> onMicTap());
        findViewById(R.id.btn_start).setOnClickListener(v -> startAll());
        findViewById(R.id.btn_stop).setOnClickListener(v -> stopAll());
        btnSettings.setOnClickListener(v -> {
            wizardStep = 0;
            showWizardStep();
        });
        btnStats.setOnClickListener(v -> showStats());
        btnMute.setOnClickListener(v -> {
            Constants.setMuted(!Constants.isMuted());
            Toast.makeText(this,
                    Constants.isMuted() ? "Krishna muted 🔇" : "Krishna on 🔊",
                    Toast.LENGTH_SHORT).show();
        });

        // Pehli baar → wizard
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("krishna_enabled", false)) {
            wizardStep = 0;
            showWizardStep();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        polling = true;
        pollStatus();
        loadChat();
    }

    @Override
    protected void onPause() {
        super.onPause();
        polling = false;
        uiHandler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bg.shutdownNow();
    }

    // ═══════════════ LIVE STATUS (polling) ═══════════════

    private void pollStatus() {
        if (!polling) return;
        int st = VoiceListenerService.getState();
        VoiceListenerService v = VoiceListenerService.get();
        String label;
        switch (st) {
            case Constants.STATE_LISTENING:
                label = "🎤 Sun raha hoon…";
                break;
            case Constants.STATE_PROCESSING:
                label = "🤔 Soch raha hoon…";
                break;
            case Constants.STATE_SPEAKING:
                label = "🗣️ Bol raha hoon…";
                break;
            case Constants.STATE_WAKE:
                label = (v != null && v.isLive()) ? "🎤 Sun raha hoon…" : "🎧 \"Hey Krishna\" bolo…";
                break;
            default:
                label = "😴 Ready hoon Boss…";
        }
        tvStatus.setText(label + (v != null && v.isLive() ? "  •  LIVE" : ""));

        int color = 0xFF555555;
        if (st == Constants.STATE_LISTENING) color = 0xFF00E676;
        else if (st == Constants.STATE_PROCESSING) color = 0xFF448AFF;
        else if (st == Constants.STATE_SPEAKING) color = 0xFFFFD700;
        ibMic.setColorFilter(color);

        uiHandler.postDelayed(this::pollStatus, 700);
    }

    // ═══════════════ MIC TAP (manual activate / stop) ═══════════════

    private void onMicTap() {
        VoiceListenerService v = VoiceListenerService.get();
        if (v == null) {
            VoiceListenerService.setPendingActivate(true);
            VoiceListenerService.startServiceSafe(this);
            Toast.makeText(this, "Krishna start ho raha hai…", Toast.LENGTH_SHORT).show();
        } else if (v.isLive()) {
            v.stopEverything();
            Toast.makeText(this, "Live band — 'Hey Krishna' se phir shuru", Toast.LENGTH_SHORT).show();
        } else {
            v.manualActivate();
            Toast.makeText(this, "Bolo Boss…", Toast.LENGTH_SHORT).show();
        }
    }

    // ═══════════════ START / STOP ALL SERVICES ═══════════════

    private void startAll() {
        if (Constants.MERCURY_API_KEY.isEmpty() || Constants.FISH_API_KEY.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("⚠️ API keys missing")
                    .setMessage("Constants.java me yeh keys daalni zaroori hai:\n\n"
                            + "• MERCURY_API_KEY\n"
                            + "• FISH_API_KEY\n\n"
                            + "(FIREBASE_DB_URL optional hai — memory ke liye)\n\n"
                            + "App abhi start karein?")
                    .setPositiveButton("Start karo", (d, w) -> doStartAll())
                    .setNegativeButton("Pehle keys daalunga", null)
                    .show();
        } else {
            doStartAll();
        }
    }

    private void doStartAll() {
        try {
            VoiceListenerService.startServiceSafe(this);
            startForegroundService(new Intent(this, FloatingButtonService.class));
            startForegroundService(new Intent(this, AudioPlaybackService.class));
        } catch (Exception e) {
            try {
                startService(new Intent(this, FloatingButtonService.class));
                startService(new Intent(this, AudioPlaybackService.class));
            } catch (Exception e2) {
                Toast.makeText(this, "Services start nahi ho payi", Toast.LENGTH_LONG).show();
            }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("krishna_enabled", true).apply();
        Toast.makeText(this, "Krishna live hai — 'Hey Krishna' bolo! 🕉️", Toast.LENGTH_LONG).show();
    }

    private void stopAll() {
        try {
            startService(new Intent(this, VoiceListenerService.class).putExtra("ACTION", "STOP"));
            startService(new Intent(this, FloatingButtonService.class).putExtra("ACTION", "STOP"));
            startService(new Intent(this, AudioPlaybackService.class).putExtra("ACTION", "STOP"));
        } catch (Exception ignored) {}
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("krishna_enabled", false).apply();
        Toast.makeText(this, "Krishna band ho gaya", Toast.LENGTH_SHORT).show();
    }

    // ═══════════════ SETUP WIZARD (10 steps) ═══════════════

    private void showWizardStep() {
        final int step = wizardStep;
        String msg = stepMessage(step);
        boolean done = stepDone(step);
        if (done) msg = "✓ " + msg;

        new AlertDialog.Builder(this)
                .setTitle("Step " + (step + 1) + " / 10")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton(step == 9 ? "🚀 Start Krishna" : "Aage ⏭", (d, w) -> {
                    if (step == 9) {
                        finishWizard();
                        return;
                    }
                    performStepAction(step);
                    wizardStep++;
                    showWizardStep();
                })
                .setNegativeButton(step > 0 ? "← Wapas" : "Cancel", (d, w) -> {
                    if (step > 0) {
                        wizardStep--;
                        showWizardStep();
                    }
                })
                .show();
    }

    private String stepMessage(int step) {
        switch (step) {
            case 0:
                return "Namaste Boss! 🙏\n\nKrishna aapka personal AI voice assistant hai — JARVIS jaisa.\n\n"
                        + "• 24/7 background me rahega\n"
                        + "• 'Hey Krishna' bolo → live mode\n"
                        + "• Phone control, WhatsApp, YouTube, calls\n"
                        + "• Messages aate hi padhega\n\n"
                        + "Ab permissions deta hai to shuru.";
            case 1:
                return "🎤 MICROPHONE + BASIC PERMISSIONS\n\n"
                        + "Mic (sunne ke liye), Notifications, Contacts, Call, "
                        + "Bluetooth, Location (WiFi toggle), Camera (flashlight).\n\n"
                        + "Saare features ke liye yeh zaroori hain. Allow karna.";
            case 2:
                return "🔲 FLOATING BUTTON (OVERLAY)\n\n"
                        + "Krishna ka mic button har app ke upar dikhega — "
                        + "WhatsApp me bhi, YouTube me bhi.\n\n"
                        + "Settings khol ke 'Krishna' ko ON karo.";
            case 3:
                return "🦾 ACCESSIBILITY SERVICE\n\n"
                        + "Yeh Krishna ke HAATH hain. Iske bina kuch nahi hoga:\n"
                        + "• Apps kholna\n• WhatsApp message bhejna\n"
                        + "• YouTube search\n• Calls\n\n"
                        + "Settings me jaake 'Krishna' ON karo.\n"
                        + "(Security warning aayega — CONTINUE dabana.)";
            case 4:
                return "🔔 NOTIFICATION LISTENER\n\n"
                        + "Taaki Krishna WhatsApp/SMS/Telegram ke messages "
                        + "aate hi padhe:\n\n"
                        + "\"Boss, WhatsApp par Rahul ka message aaya hai: Kal milte hain\"\n\n"
                        + "Settings me jaake 'Krishna' ko allow karo.";
            case 5:
                return "🔋 BATTERY OPTIMIZATION BAND KARO\n\n"
                        + "OPPO (ColorOS) background apps ko maar deta hai. "
                        + "Yeh step NAHI kiya to Krishna chala-jayega.\n\n"
                        + "'Don't optimize' / 'No restrictions' select karo.";
            case 6:
                return "📲 OPPO AUTO-START\n\n"
                        + "Phone restart ke baad Krishna khud shuru ho, "
                        + "isliye Auto-Start ON karna zaroori hai.\n\n"
                        + "Settings khol ke Krishna ko ON karo.";
            case 7:
                return "🔆 BRIGHTNESS PERMISSION\n\n"
                        + "\"Volume badha\" ya brightness ke liye WRITE_SETTINGS chahiye.\n\n"
                        + "Optional hai — skip kar sakte ho.";
            case 8:
                return "🔒 RECENT APPS ME LOCK KARO (ZAROORI)\n\n"
                        + "1. Recent apps button dabao\n"
                        + "2. Krishna ko LONG PRESS karo\n"
                        + "3. 'Lock' 🔒 icon dabao\n\n"
                        + "Yeh sirf manually hota hai — warna ColorOS "
                        + "Krishna ko recent se nikaal dega.";
            case 9:
                return "✅ SAB SET HO GAYA!\n\nKrishna ab start ho raha hai.\n\n"
                        + "USE KARNA:\n"
                        + "• 'Hey Krishna' bolo → wake\n"
                        + "• Phir seedha command bolo — live rahega\n"
                        + "• 'Chup ho ja' → live mode band\n\n"
                        + "Happy commanding, Boss! 🕉️";
            default:
                return "";
        }
    }

    private void performStepAction(int step) {
        switch (step) {
            case 1:
                requestRuntimePermissions();
                break;
            case 2:
                openOverlaySettings();
                break;
            case 3:
                openAccessibilitySettings();
                break;
            case 4:
                openNotificationSettings();
                break;
            case 5:
                DeviceUtils.requestBatteryExemption(this);
                break;
            case 6:
                DeviceUtils.openOppoAutoStart(this);
                break;
            case 7:
                openWriteSettings();
                break;
            case 8:
                new AlertDialog.Builder(this)
                        .setTitle("🔒 Krishna ko lock karo")
                        .setMessage("1. Recent apps dabao\n2. Krishna ko long press karo\n3. 🔒 Lock dabao\n\n"
                                + "Phone restart ke baad bhi Krishna chalta rahega.")
                        .setPositiveButton("Samajh gaya", null)
                        .show();
                break;
            default:
                break;
        }
    }

    private void finishWizard() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("wizard_done", true).apply();
        doStartAll();
    }

    // ═══════════════ PERMISSION CHECKS ═══════════════

    private boolean hasPerm(String perm) {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean notificationsGranted() {
        if (Build.VERSION.SDK_INT >= 33) {
            return NotificationManagerCompat.from(this).areNotificationsEnabled();
        }
        return true;
    }

    private boolean isAccessibilityOn() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String component = new android.content.ComponentName(this,
                    KrishnaAccessibilityService.class).flattenToString();
            return enabled != null && enabled.contains(component);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isListenerOn() {
        try {
            java.util.Set<String> enabled = NotificationManagerCompat
                    .getEnabledListenerPackages(this);
            return enabled != null && enabled.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isBatteryOk() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean stepDone(int step) {
        switch (step) {
            case 0:
                return true;
            case 1:
                return hasPerm(Manifest.permission.RECORD_AUDIO) && notificationsGranted();
            case 2:
                return Settings.canDrawOverlays(this);
            case 3:
                return isAccessibilityOn();
            case 4:
                return isListenerOn();
            case 5:
                return isBatteryOk();
            case 6:
                return false; // manual
            case 7:
                return Settings.System.canWrite(this);
            case 8:
                return false; // manual
            case 9:
                return true;
            default:
                return false;
        }
    }

    // ═══════════════ RUNTIME PERMISSIONS (saare features ke liye) ═══════════════

    private void requestRuntimePermissions() {
        List<String> pending = new ArrayList<>();
        addIfMissing(pending, Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) {
            addIfMissing(pending, Manifest.permission.POST_NOTIFICATIONS);
        }
        addIfMissing(pending, Manifest.permission.READ_CONTACTS);
        addIfMissing(pending, Manifest.permission.CALL_PHONE);
        addIfMissing(pending, Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 31) {
            addIfMissing(pending, Manifest.permission.BLUETOOTH_CONNECT);
        }
        addIfMissing(pending, Manifest.permission.ACCESS_FINE_LOCATION);
        addIfMissing(pending, Manifest.permission.CAMERA);
        if (pending.isEmpty()) {
            Toast.makeText(this, "Sab permissions mil gaye ✓", Toast.LENGTH_SHORT).show();
        } else {
            permissionLauncher.launch(pending.toArray(new String[0]));
        }
    }

    private void addIfMissing(List<String> list, String perm) {
        if (!hasPerm(perm)) list.add(perm);
    }

    // ═══════════════ SPECIAL SETTINGS SCREENS ═══════════════

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Exception e2) {
                toast("Settings > Apps > Special access > Display over other apps me Krishna ON karo");
            }
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
        }
    }

    private void openNotificationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            toast("Settings > Notifications > Notification access me Krishna allow karo");
        }
    }

    private void openWriteSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            toast("Settings > Apps > Special access > Modify system settings me Krishna ON karo");
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    // ═══════════════ CHAT HISTORY (last 5 messages) ═══════════════

    private void loadChat() {
        bg.submit(() -> {
            List<ChatMessage> msgs;
            try {
                msgs = FirebaseHelper.get().getSessionMessages(DeviceUtils.getDeviceId(this));
            } catch (Exception e) {
                msgs = new ArrayList<>();
            }
            final List<ChatMessage> fm = msgs;
            runOnUiThread(() -> {
                try {
                    llChat.removeAllViews();
                    if (fm.isEmpty()) {
                        TextView hint = new TextView(this);
                        hint.setText("💬 Baatein yahan dikhengi…\n\n\"Hey Krishna\" bolo ya mic dabao.");
                        hint.setTextColor(Color.parseColor("#888888"));
                        hint.setTextSize(13);
                        hint.setGravity(Gravity.CENTER);
                        llChat.addView(hint);
                        return;
                    }
                    int start = Math.max(0, fm.size() - 5);
                    for (int i = start; i < fm.size(); i++) {
                        ChatMessage m = fm.get(i);
                        boolean isUser = !"assistant".equals(m.role);
                        TextView tv = new TextView(this);
                        tv.setText((isUser ? "Tum: " : "Krishna: ") + (m.text == null ? "" : m.text));
                        tv.setTextColor(Color.WHITE);
                        tv.setTextSize(13);
                        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
                        tv.setBackgroundResource(isUser ? R.drawable.bg_chat_user : R.drawable.bg_chat_krishna);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                        lp.width = dp(290);
                        lp.setMargins(0, dp(4), 0, dp(4));
                        tv.setLayoutParams(lp);
                        llChat.addView(tv);
                    }
                } catch (Exception ignored) {}
            });
        });
    }

    // ═══════════════ STATS DIALOG ═══════════════

    private void showStats() {
        bg.submit(() -> {
            List<String> lines;
            try {
                lines = FirebaseHelper.get().getStats(DeviceUtils.getDeviceId(this));
            } catch (Exception e) {
                lines = new ArrayList<>();
                lines.add("Stats load nahi ho paye");
            }
            final List<String> fl = lines;
            runOnUiThread(() -> {
                StringBuilder sb = new StringBuilder();
                for (String l : fl) sb.append(l).append("\n");
                new AlertDialog.Builder(this)
                        .setTitle("📊 Krishna Stats")
                        .setMessage(sb.toString())
                        .setPositiveButton("OK", null)
                        .show();
            });
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
