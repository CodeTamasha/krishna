package com.krishna.assistant;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════
 * KRISHNA ACCESSIBILITY SERVICE — PHONE KA "HAATH"
 *
 * Yahi service phone control karti hai:
 *  - Apps kholna (40+ apps + fuzzy matcher)
 *  - Home / Back / Recent / Lock / Screenshot
 *  - WhatsApp: search → RESULT ME ENTER → chat box me type → SEND
 *  - YouTube: search → submit → PEHLA RESULT → play
 *  - Calls: number ya contact name dono
 *  - Flashlight / volume / brightness
 *
 * ZAROORI NOTE: Saare UI operations background thread se call hote hain
 * (ActionExecutor pipeline thread par) — yeh allowed hai.
 * ═══════════════════════════════════════════════════════════
 */
public class KrishnaAccessibilityService extends AccessibilityService {

    private static final String TAG = "KrishnaAcc";
    private static volatile KrishnaAccessibilityService instance;

    public static KrishnaAccessibilityService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "✅ Accessibility service connected — Krishna ke haath active");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Events ignore — hum sirf on-demand control karte hain
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    // ═══════════════ BASIC HELPERS ═══════════════

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }

    private int getScreenWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int getScreenHeight() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    // ═══════════════ APP RESOLVE + OPEN ═══════════════

    /**
     * "whatsapp" → package name. Pehle Constants ke map me dekho,
     * phir installed apps ke label se fuzzy match karo.
     */
    public String resolveApp(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        String n = name.toLowerCase().trim();
        String direct = Constants.APP_PACKAGES.get(n);
        if (direct != null) return direct;

        // Fuzzy: installed apps ke labels se match
        String best = null;
        int bestScore = 0;
        try {
            List<android.content.pm.PackageInfo> pkgs = getPackageManager().getInstalledPackages(0);
            for (android.content.pm.PackageInfo pi : pkgs) {
                try {
                    if (pi.applicationInfo == null) continue;
                    String label = getPackageManager()
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
     * (Log WhatsApp Business use karte hain aur uska package alag hota hai —
     *  isliye "No launch intent for com.whatsapp" aa rha tha.)
     */
    private String resolvePackage(String pkg) {
        try {
            if (getPackageManager().getLaunchIntentForPackage(pkg) != null) return pkg;
            String alt = null;
            if ("com.whatsapp".equals(pkg)) alt = "com.whatsapp.w4b";
            else if ("com.whatsapp.w4b".equals(pkg)) alt = "com.whatsapp";
            if (alt != null && getPackageManager().getLaunchIntentForPackage(alt) != null) {
                Log.i(TAG, "Package resolve: " + pkg + " → " + alt);
                return alt;
            }
        } catch (Exception ignored) {}
        return pkg;
    }

    public boolean openApp(String pkg) {
        if (pkg == null) return false;
        pkg = resolvePackage(pkg);
        try {
            Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
            if (li == null) {
                Log.w(TAG, "No launch intent for " + pkg);
                return false;
            }
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(li);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "openApp fail: " + pkg, e);
            return false;
        }
    }

    // ═══════════════ NAVIGATION ═══════════════

    public void pressHome() {
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public void pressBack() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public void pressRecent() {
        performGlobalAction(GLOBAL_ACTION_RECENTS);
    }

    public void lockScreen() {
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    /** Screenshot — Android 10+ (API 29) par hi public API hai */
    public boolean takeScreenshot() {
        if (Build.VERSION.SDK_INT >= 29) {
            return performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
        }
        return false;
    }

    // ═══════════════ CLICK / GESTURE ═══════════════

    /** Content description ya view id se button dhundo aur click karo */
    public boolean clickByContentDesc(String desc) {
        if (desc == null || desc.isEmpty()) return false;
        AccessibilityNodeInfo n = findByContentDesc(desc, false);
        if (n == null) n = findByViewIdContains(desc, false);
        return clickNode(n) != null;
    }

    /** Node click karo — ACTION_CLICK, fail ho to dobara try */
    private AccessibilityNodeInfo clickNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        try {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return node;
            sleep(300);
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return node;
        } catch (Exception e) {
            Log.e(TAG, "clickNode fail", e);
        }
        return null;
    }

    // ═══════════════ NODE FIND HELPERS (tree walk) ═══════════════

    private AccessibilityNodeInfo root() {
        try {
            return getRootInActiveWindow();
        } catch (Exception e) {
            return null;
        }
    }

    /** Content description me keyword dhoondho */
    private AccessibilityNodeInfo findByContentDesc(String key, boolean needClickable) {
        return findInTreeByDesc(root(), key, needClickable);
    }

    private AccessibilityNodeInfo findInTreeByDesc(AccessibilityNodeInfo node, String key, boolean needClickable) {
        if (node == null) return null;
        try {
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.toString().toLowerCase().contains(key.toLowerCase())) {
                if (!needClickable || node.isClickable()) return node;
            }
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo c = node.getChild(i);
                if (c != null) {
                    AccessibilityNodeInfo r = findInTreeByDesc(c, key, needClickable);
                    if (r != null) return r;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** View ID me keyword dhoondho (jaise com.whatsapp:id/send) */
    private AccessibilityNodeInfo findByViewIdContains(String key, boolean needClickable) {
        return findInTreeById(root(), key, needClickable);
    }

    private AccessibilityNodeInfo findInTreeById(AccessibilityNodeInfo node, String key, boolean needClickable) {
        if (node == null) return null;
        try {
            String viewId = node.getViewIdResourceName();
            if (viewId != null && viewId.toLowerCase().contains(key.toLowerCase())) {
                if (!needClickable || node.isClickable()) return node;
            }
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo c = node.getChild(i);
                if (c != null) {
                    AccessibilityNodeInfo r = findInTreeById(c, key, needClickable);
                    if (r != null) return r;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Screen pe focused editable field (jaha keyboard type kar raha hai) */
    private AccessibilityNodeInfo findFocusedEditable() {
        AccessibilityNodeInfo r = root();
        if (r == null) return null;
        try {
            AccessibilityNodeInfo focused = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null && focused.isEditable()) return focused;
        } catch (Exception ignored) {}
        return null;
    }

    /** Screen ke BOTTOM me editable box (WhatsApp chat input waghera) */
    private AccessibilityNodeInfo findBottomEditable() {
        AccessibilityNodeInfo r = root();
        if (r == null) return null;
        AccessibilityNodeInfo best = null;
        int bestTop = -1;
        int h = getScreenHeight();
        List<AccessibilityNodeInfo> edits = collectAllEditables(r);
        for (AccessibilityNodeInfo e : edits) {
            try {
                Rect rect = new Rect();
                e.getBoundsInScreen(rect);
                if (rect.top > 0.60f * h && rect.top > bestTop) {
                    bestTop = rect.top;
                    best = e;
                }
            } catch (Exception ignored) {}
        }
        return best;
    }

    private List<AccessibilityNodeInfo> collectAllEditables(AccessibilityNodeInfo node) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        collectEditablesRec(node, out, 0);
        return out;
    }

    private void collectEditablesRec(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || out.size() > 15 || depth > 25) return;
        try {
            if (node.isEditable()) out.add(node);
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo c = node.getChild(i);
                if (c != null) collectEditablesRec(c, out, depth + 1);
            }
        } catch (Exception ignored) {}
    }

    // ═══════════════ TYPING (2 strategies) ═══════════════

    /**
     * Text type karo focused field me:
     * 1. Focused editable node → ACTION_SET_TEXT
     * 2. Pehla visible editable node → ACTION_SET_TEXT
     */
    public boolean typeText(String text) {
        if (text == null || text.isEmpty()) return false;
        // Strategy 1: focused node
        AccessibilityNodeInfo focused = findFocusedEditable();
        if (focused != null && setNodeText(focused, text)) {
            sleep(300);
            return true;
        }
        // Strategy 2: first editable
        List<AccessibilityNodeInfo> edits = collectAllEditables(root());
        if (!edits.isEmpty()) {
            for (AccessibilityNodeInfo e : edits) {
                if (e.isVisibleToUser() && setNodeText(e, text)) {
                    sleep(300);
                    return true;
                }
            }
        }
        Log.w(TAG, "typeText: no editable found");
        return false;
    }

    private boolean setNodeText(AccessibilityNodeInfo node, String text) {
        try {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } catch (Exception e) {
            return false;
        }
    }

    /** WhatsApp chat input (bottom box) me type karo */
    public boolean typeTextInBottomBox(String text) {
        AccessibilityNodeInfo input = findBottomEditable();
        if (input != null && setNodeText(input, text)) {
            sleep(300);
            return true;
        }
        return typeText(text);
    }

    // ═══════════════════════════════════════════════════════════
    // WHATSAPP MESSAGE FLOW (PURANA BUG FIX HUA)
    //
    // Purani galti thi: search box me contact search hoke USI SEARCH BOX
    // me message type ho jata tha.
    //
    // Ab ka flow:
    //  1. WhatsApp khol (2.5s wait)
    //  2. Search icon click (3 attempts)
    //  3. Contact NAME type (Latin script — Devanagari KABHI nahi)
    //  4. ⭐ PEHLA SEARCH RESULT CLICK KARO — ab chat khul jayegi
    //  5. Chat ka BOTTOM INPUT BOX me message type karo
    //  6. 1.8s wait (WhatsApp ko processing time)
    //  7. Send button (3 strategies × 3 retries)
    // ═══════════════════════════════════════════════════════════

    public boolean sendWhatsAppMessage(String contact, String message) {
        Log.i(TAG, "📲 sendWhatsAppMessage: " + contact + " → " + message);
        if (contact == null || contact.trim().isEmpty()) return false;
        if (message == null || message.trim().isEmpty()) return false;

        // Step 1: WhatsApp kholo
        if (!openApp("com.whatsapp")) {
            Log.w(TAG, "WhatsApp open fail");
            return false;
        }
        sleep(2500);

        // Step 2: Search icon (3 attempts × 1s)
        AccessibilityNodeInfo search = null;
        for (int i = 0; i < 3 && search == null; i++) {
            search = findByContentDesc("search", true);
            if (search == null) search = findByViewIdContains("search", true);
            if (search == null) sleep(1000);
        }
        if (search != null) {
            clickNode(search);
            sleep(1500);
        }

        // Step 3: Contact name type karo (English/Hinglish me)
        if (!typeText(contact.trim())) {
            Log.w(TAG, "contact type fail");
            return false;
        }
        sleep(2000); // results load hone do

        // Step 4: ⭐ PEHLA RESULT PAR CLICK — chat me ghusna (KEY FIX)
        AccessibilityNodeInfo result = findFirstSearchResult(contact.trim());
        if (result == null) result = findFirstResultByPosition();
        if (result == null) {
            pressBack();
            Log.w(TAG, "No search result for: " + contact);
            return false;
        }
        clickNode(result);
        sleep(2200); // chat khulne do

        // Step 5: Chat ka BOTTOM input box me message type
        if (!typeTextInBottomBox(message.trim())) {
            Log.w(TAG, "message type fail");
            return false;
        }

        // Step 6: WhatsApp ko processing time (spec: 1.5-2s)
        sleep(1800);

        // Step 7: Send button click (multi-strategy)
        boolean sent = clickWhatsAppSendButton();
        Log.i(TAG, "Send result: " + sent);
        return sent;
    }

    /**
     * Search results me se PEHLA (upar wala) contact dhoondho —
     * naam text me ho, clickable ho, search bar ke neeche ho, editable NAHI ho.
     */
    private AccessibilityNodeInfo findFirstSearchResult(String name) {
        List<AccessibilityNodeInfo> cands = new ArrayList<>();
        collectNameCandidates(root(), name, 0.14f, 0.85f, cands);
        if (cands.isEmpty()) return null;
        cands.sort((a, b) -> {
            try {
                Rect ra = new Rect();
                a.getBoundsInScreen(ra);
                Rect rb = new Rect();
                b.getBoundsInScreen(rb);
                return Integer.compare(ra.top, rb.top);
            } catch (Exception e) {
                return 0;
            }
        });
        return cands.get(0);
    }

    private void collectNameCandidates(AccessibilityNodeInfo node, String name,
                                        float minFrac, float maxFrac, List<AccessibilityNodeInfo> out) {
        if (node == null || out.size() > 20) return;
        try {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            int h = getScreenHeight();
            CharSequence t = node.getText();
            if (node.isClickable() && !node.isEditable() && t != null) {
                String txt = t.toString().trim();
                if (!txt.isEmpty() && txt.length() < 120
                        && txt.toLowerCase().contains(name.toLowerCase())
                        && r.top > minFrac * h && r.top < maxFrac * h
                        && r.height() > 40) {
                    out.add(node);
                }
            }
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo c = node.getChild(i);
                if (c != null) collectNameCandidates(c, name, minFrac, maxFrac, out);
            }
        } catch (Exception e) {
            Log.w(TAG, "collectNameCandidates fail", e);
        }
    }

    /** Naam match nahi mila to position se pehla result (text wala, list area me) */
    private AccessibilityNodeInfo findFirstResultByPosition() {
        List<AccessibilityNodeInfo> list = new ArrayList<>();
        collectPositionCandidates(root(), 0.16f, 0.80f, list);
        AccessibilityNodeInfo best = null;
        int bestTop = Integer.MAX_VALUE;
        int w = getScreenWidth();
        for (AccessibilityNodeInfo c : list) {
            try {
                Rect r = new Rect();
                c.getBoundsInScreen(r);
                if (r.top < bestTop && r.width() > w * 0.5f) {
                    bestTop = r.top;
                    best = c;
                }
            } catch (Exception ignored) {}
        }
        return best;
    }

    /** List area me clickable, text wale nodes collect karo */
    private void collectPositionCandidates(AccessibilityNodeInfo node,
                                           float minFrac, float maxFrac, List<AccessibilityNodeInfo> out) {
        if (node == null || out.size() > 30) return;
        try {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            int h = getScreenHeight();
            CharSequence t = node.getText();
            CharSequence d = node.getContentDescription();
            String vid = node.getViewIdResourceName();
            boolean hasText = (t != null && t.toString().trim().length() >= 2)
                    || (d != null && d.toString().trim().length() >= 2);
            boolean notSearchBar = vid == null || !vid.toLowerCase().contains("search");
            if (node.isClickable() && !node.isEditable() && hasText && notSearchBar
                    && r.top > minFrac * h && r.top < maxFrac * h && r.height() > 60) {
                out.add(node);
            }
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo c = node.getChild(i);
                if (c != null) collectPositionCandidates(c, minFrac, maxFrac, out);
            }
        } catch (Exception ignored) {}
    }

    /**
     * ⭐ WHATSAPP SEND BUTTON — 3 STRATEGIES × 3 RETRIES (1s gap)
     *  1. Content description "Send"
     *  2. View ID me "send" (com.whatsapp:id/send, send_btn waghera)
     *  3. Bottom-right me small circular clickable button (green send ka position)
     * WhatsApp ke updates se UI badalta hai — isliye multiple strategies.
     */
    public boolean clickWhatsAppSendButton() {
        for (int attempt = 0; attempt < 3; attempt++) {
            AccessibilityNodeInfo r = root();
            if (r == null) {
                sleep(1000);
                continue;
            }
            AccessibilityNodeInfo send = null;
            // Strategy 1: content description
            send = findByContentDesc("send", true);
            // Strategy 2: view id
            if (send == null) send = findByViewIdContains("send", true);
            // Strategy 3: position (bottom-right circular button)
            if (send == null) send = findSendButtonByPosition();

            if (send != null) {
                if (clickNode(send) != null) return true;
            }
            Log.w(TAG, "send button not found — attempt " + (attempt + 1));
            sleep(1000);
        }
        return false;
    }

    /** Bottom-right corner me send button jaisa small circular clickable */
    private AccessibilityNodeInfo findSendButtonByPosition() {
        List<AccessibilityNodeInfo> list = new ArrayList<>();
        collectSendCandidates(root(), list);
        int w = getScreenWidth();
        int h = getScreenHeight();
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo c : list) {
            try {
                Rect r = new Rect();
                c.getBoundsInScreen(r);
                int size = Math.min(r.width(), r.height());
                int cx = r.centerX();
                int cy = r.centerY();
                boolean bottomRight = cy > h * 0.75f && cx > w * 0.55f;
                if (!bottomRight) continue;
                if (size < 200 && size > 30) {
                    int score = r.bottom * 10 + r.right;
                    if (score > bestScore) {
                        bestScore = score;
                        best = c;
                    }
                }
            } catch (Exception ignored) {}
        }
        return best;
    }

    private void collectSendCandidates(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null || out.size() > 40) return;
        try {
            if (node.isClickable()) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                int h = getScreenHeight();
                String cls = node.getClassName() == null ? "" : node.getClassName().toString();
                boolean buttonLike = cls.contains("Button") || cls.contains("ImageView")
                        || cls.contains("FrameLayout") || cls.contains("LinearLayout");
                if (r.top > h * 0.7f && buttonLike) {
                    out.add(node);
                }
            }
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo c = node.getChild(i);
                if (c != null) collectSendCandidates(c, out);
            }
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════
    // YOUTUBE FLOW (PURANA "app not found" + "play nahi hota" FIX)
    //
    //  1. com.google.android.youtube khol (fuzzy fallback ke saath)
    //  2. Search icon click
    //  3. Query type
    //  4. Submit: search button click YA pehli suggestion
    //  5. ⭐ PEHLA VIDEO RESULT CLICK → video khud chalegi
    // ═══════════════════════════════════════════════════════════

    public boolean searchYouTube(String query) {
        Log.i(TAG, "▶️ searchYouTube: " + query);
        if (query == null || query.trim().isEmpty()) return false;

        // Step 1: YouTube kholo (correct package + fuzzy fallback)
        String ytPkg = "com.google.android.youtube";
        if (!openApp(ytPkg)) {
            String alt = resolveApp("youtube");
            if (alt == null || !openApp(alt)) {
                Log.w(TAG, "YouTube app not found on device");
                return false;
            }
        }
        sleep(3500);

        // Step 2: Search icon
        AccessibilityNodeInfo search = findByContentDesc("search", true);
        if (search == null) search = findByViewIdContains("search", true);
        if (search != null) {
            clickNode(search);
            sleep(1500);
        }

        // Step 3: Query type
        if (!typeText(query.trim())) {
            Log.w(TAG, "youtube type fail");
            return false;
        }
        sleep(1000);

        // Step 4: Submit (search button ya pehli suggestion)
        AccessibilityNodeInfo submit = findByContentDesc("search", true);
        boolean submitted = false;
        if (submit != null && submit != search) {
            submitted = clickNode(submit) != null;
        }
        if (!submitted) {
            List<AccessibilityNodeInfo> sugg = new ArrayList<>();
            collectPositionCandidates(root(), 0.08f, 0.6f, sugg);
            AccessibilityNodeInfo pick = null;
            for (AccessibilityNodeInfo c : sugg) {
                try {
                    CharSequence t = c.getText();
                    if (t != null && t.toString().toLowerCase().contains(query.toLowerCase())) {
                        pick = c;
                        break;
                    }
                } catch (Exception ignored) {}
            }
            if (pick == null && !sugg.isEmpty()) pick = sugg.get(0);
            if (pick != null) submitted = clickNode(pick) != null;
        }
        if (!submitted) {
            Log.w(TAG, "youtube submit fail");
            return false;
        }
        sleep(3000);

        // Step 5: Pehla video result click karo → play
        AccessibilityNodeInfo video = findFirstVideoResult();
        if (video == null) {
            Log.w(TAG, "no video result found");
            return false;
        }
        clickNode(video);
        sleep(2500);
        return true;
    }

    /** Search results ka pehla video item (full-width clickable, upar wala) */
    private AccessibilityNodeInfo findFirstVideoResult() {
        List<AccessibilityNodeInfo> list = new ArrayList<>();
        collectPositionCandidates(root(), 0.12f, 0.75f, list);
        AccessibilityNodeInfo best = null;
        int bestTop = Integer.MAX_VALUE;
        int w = getScreenWidth();
        for (AccessibilityNodeInfo c : list) {
            try {
                Rect r = new Rect();
                c.getBoundsInScreen(r);
                if (r.width() > w * 0.5f && r.top < bestTop) {
                    bestTop = r.top;
                    best = c;
                }
            } catch (Exception ignored) {}
        }
        return best;
    }

    /** YouTube video pause/resume — Play/Pause button dhoondh ke click karo */
    public void togglePlayPause() {
        AccessibilityNodeInfo btn = findByContentDesc("play", true);
        if (btn == null) btn = findByContentDesc("pause", true);
        if (btn == null) btn = findByContentDesc("replay", true);
        if (btn != null) {
            clickNode(btn);
            return;
        }
        btn = findByViewIdContains("play", true);
        if (btn == null) btn = findByViewIdContains("pause", true);
        if (btn != null) clickNode(btn);
    }

    // ═══════════════════════════════════════════════════════════
    // CALLS — number ya contact name dono
    // ═══════════════════════════════════════════════════════════

    /** Seedha number se call (ACTION_CALL, fail ho to ACTION_DIAL) */
    public boolean callByNumber(String number) {
        if (number == null || number.trim().isEmpty()) return false;
        String clean = number.trim();
        try {
            Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + clean));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "ACTION_CALL fail — DIAL try");
            try {
                Intent d = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + clean));
                d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(d);
                return true;
            } catch (Exception e2) {
                Log.e(TAG, "call fail", e2);
                return false;
            }
        }
    }

    /**
     * Contact NAME se call:
     * Dialer kholo → People tab → naam search → pehla result → green CALL button
     */
    public boolean callContact(String name) {
        Log.i(TAG, "📞 callContact: " + name);
        if (name == null || name.trim().isEmpty()) return false;

        // Dialer package dhoondho
        String dialerPkg = null;
        try {
            List<ResolveInfo> list = getPackageManager()
                    .queryIntentActivities(new Intent(Intent.ACTION_DIAL), 0);
            for (ResolveInfo ri : list) {
                if (ri.activityInfo != null) {
                    dialerPkg = ri.activityInfo.packageName;
                    break;
                }
            }
        } catch (Exception ignored) {}
        if (dialerPkg == null) dialerPkg = "com.google.android.dialer";

        if (!openApp(dialerPkg)) return false;
        sleep(2500);

        // "People" tab
        AccessibilityNodeInfo people = findByContentDesc("people", true);
        if (people == null) people = findByContentDesc("people", false);
        if (people != null) {
            clickNode(people);
            sleep(1500);
        }

        // Naam search karo
        if (!typeText(name.trim())) return false;
        sleep(2000);

        // Pehla result click
        AccessibilityNodeInfo result = findFirstSearchResult(name.trim());
        if (result == null) result = findFirstResultByPosition();
        if (result == null) {
            Log.w(TAG, "contact not found: " + name);
            return false;
        }
        clickNode(result);
        sleep(2000);

        // Green CALL button (content-desc "Call" ya view id "call")
        AccessibilityNodeInfo callBtn = findByContentDesc("call", true);
        if (callBtn == null) callBtn = findByViewIdContains("call", true);
        if (callBtn != null && clickNode(callBtn) != null) return true;

        // Fallback: bottom-center ka green circular button
        AccessibilityNodeInfo green = findBottomCenterButton();
        return green != null && clickNode(green) != null;
    }

    /** Bottom-center me chhota clickable (green call button) */
    private AccessibilityNodeInfo findBottomCenterButton() {
        List<AccessibilityNodeInfo> cands = new ArrayList<>();
        collectCallButtonCandidates(root(), cands);
        int w = getScreenWidth();
        int h = getScreenHeight();
        AccessibilityNodeInfo best = null;
        int bestTop = -1;
        for (AccessibilityNodeInfo c : cands) {
            try {
                Rect r = new Rect();
                c.getBoundsInScreen(r);
                int cx = r.centerX();
                if (cx > 0.35f * w && cx < 0.65f * w && r.top > 0.75f * h && r.top > bestTop) {
                    bestTop = r.top;
                    best = c;
                }
            } catch (Exception ignored) {}
        }
        return best;
    }

    private void collectCallButtonCandidates(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null || out.size() > 40) return;
        try {
            if (node.isClickable()) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                int h = getScreenHeight();
                int size = Math.min(r.width(), r.height());
                if (r.top > h * 0.7f && size > 40 && size < 220) {
                    out.add(node);
                }
            }
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo c = node.getChild(i);
                if (c != null) collectCallButtonCandidates(c, out);
            }
        } catch (Exception ignored) {}
    }

    // ═══════════════ FLASHLIGHT ═══════════════

    public void toggleFlashlight(String state) {
        boolean on = "on".equalsIgnoreCase(state);
        // Strategy 1: Camera2 torch API (CAMERA permission chahiye)
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cm != null) {
                String[] ids = cm.getCameraIdList();
                if (ids != null && ids.length > 0) {
                    cm.setTorchMode(ids[0], on);
                    return;
                }
            }
        } catch (Exception ignored) {}
        // Strategy 2: ColorOS/OPPO hidden setting
        try {
            Settings.Global.putInt(getContentResolver(), "torch", on ? 1 : 0);
        } catch (Exception ignored) {}
    }
}
