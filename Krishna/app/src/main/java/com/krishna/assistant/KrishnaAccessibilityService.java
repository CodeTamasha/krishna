package com.krishna.assistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityWindowInfo;
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
 * v1.1.0 FIXES:
 *  - ⭐ ACCESSIBILITY HEALTH: OPPO accessibility chupke se band karta
 *    hai — ab service band hote hi user ko NOTIFICATION milta hai
 *    (warna "kuch accessibility wala nahi ho raha" par pata hi nahi
 *    chalta tha ki service OFF hai)
 *  - WHATSAPP FLOW: fixed sleeps ki jagah POLLING — search result
 *    aane tak wait, chat input box aane tak wait (fast phone par
 *    fast, slow phone par nahi fail hota)
 *  - Agar phone par PEHLE SE WhatsApp khula hai to dobara open +
 *    2.5s wait skip (turant search)
 *  - Call button "people" + "contacts" dono desc se dhoondhta hai
 *    (Google Dialer me "Contacts" hota hai, Samsung me "People")
 *
 * ZAROORI NOTE: Saare UI operations background thread se call hote hain
 * (ActionExecutor pipeline thread par) — yeh allowed hai.
 * ═══════════════════════════════════════════════════════════
 */
public class KrishnaAccessibilityService extends AccessibilityService {

    private static final String TAG = "KrishnaAcc";
    private static volatile KrishnaAccessibilityService instance;
    private static volatile boolean wasEverConnected = false;

    public static KrishnaAccessibilityService get() {
        return instance;
    }

    /** Service kabhi connect thi? (watchdog sirf tab warn karega jab
     *  pehle se ON thi aur ab OFF ho gayi — kabhi ON nahi hui to nahi) */
    public static boolean wasEverConnected() {
        return wasEverConnected;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wasEverConnected = true;
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
        if (instance == this) {
            instance = null;
            // ⭐ USER KO PATA CHALNA CHAHIYE — OPPO aksar yeh service
            // chupke se band kar deta hai. Bina is notification ke user
            // sochta "Krishna toot gaya hai".
            try {
                DeviceUtils.notifyImportant(this,
                        "⚠️ Krishna ki Accessibility service OFF",
                        "Phone ne Krishna ki Accessibility service band kar di hai. "
                                + "WhatsApp message / YouTube / UI controls ke liye "
                                + "Settings → Accessibility → Krishna dobara ON karo.");
            } catch (Exception ignored) {}
        }
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

    // ═══════════════ APP RESOLVE + OPEN (AppLauncher par delegate) ═══════════════

    public String resolveApp(String name) {
        return AppLauncher.resolveApp(this, name);
    }

    public boolean openApp(String pkg) {
        return AppLauncher.openApp(this, pkg);
    }

    /**
     * Screen par sabse upar ka window ka package —
     * (WhatsApp already khula ho to dobara open + wait skip hota hai)
     */
    private String topWindowPackage() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null || ws.isEmpty()) return null;
            AccessibilityWindowInfo top = ws.get(0);
            for (AccessibilityWindowInfo w : ws) {
                if (w.isFocused()) {
                    top = w;
                    break;
                }
            }
            AccessibilityNodeInfo root = top.getRoot();
            if (root != null && root.getPackageName() != null) return root.getPackageName();
            return top.getPackageName();
        } catch (Exception e) {
            return null;
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

    /** Screen ke TOP me editable box (search box waghera) */
    private AccessibilityNodeInfo findTopEditable() {
        AccessibilityNodeInfo r = root();
        if (r == null) return null;
        AccessibilityNodeInfo best = null;
        int bestTop = Integer.MAX_VALUE;
        List<AccessibilityNodeInfo> edits = collectAllEditables(r);
        for (AccessibilityNodeInfo e : edits) {
            try {
                if (!e.isVisibleToUser()) continue;
                Rect rect = new Rect();
                e.getBoundsInScreen(rect);
                if (rect.top < bestTop) {
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

    // ═══════════════ TYPING (3 strategies) ═══════════════

    /**
     * Text type karo:
     * 1. Focused editable node → ACTION_SET_TEXT
     * 2. Pehla visible editable node → ACTION_SET_TEXT
     * 3. ⭐ TOP me wala editable (search box) → ACTION_SET_TEXT
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
        // Strategy 3: topmost editable (search box)
        AccessibilityNodeInfo top = findTopEditable();
        if (top != null && setNodeText(top, text)) {
            sleep(300);
            return true;
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

    /** Search box (top me wala editable) me type karo */
    public boolean typeTextInTopBox(String text) {
        AccessibilityNodeInfo top = findTopEditable();
        if (top != null && setNodeText(top, text)) {
            sleep(300);
            return true;
        }
        return typeText(text);
    }

    // ═══════════════════════════════════════════════════════════
    // WHATSAPP MESSAGE FLOW (v1.1.0 — POLLING WAITS)
    //
    //  1. WhatsApp khol (already khula ho to SKIP — turant)
    //  2. Search icon click (3 attempts)
    //  3. Contact NAME type (Latin script — Devanagari KABHI nahi)
    //  4. ⭐ PEHLA SEARCH RESULT PAR CLICK — poll: result aane tak wait
    //  5. Chat ka BOTTOM INPUT BOX me message type — poll: box aane tak wait
    //  6. WhatsApp ko processing time (1.5s)
    //  7. Send button (3 strategies × 3 retries)
    //
    // Purane code me fixed sleeps (2s/2.2s) the — fast phone par zaya,
    // slow phone par kam (UI abhi ready nahi hota → fail).
    // Ab POLLING: phone ki speed ke hisaab se.
    // ═══════════════════════════════════════════════════════════

    public boolean sendWhatsAppMessage(String contact, String message) {
        if (contact == null || contact.trim().isEmpty()) return false;
        if (message == null || message.trim().isEmpty()) return false;
        final String c = contact.trim();
        final String m = message.trim();
        Log.i(TAG, "📲 sendWhatsAppMessage: " + c + " → " + m);

        // Step 1: WhatsApp kholo — par agar PEHLE SE khula hai to skip
        String topPkg = topWindowPackage();
        if (!"com.whatsapp".equals(topPkg) && !"com.whatsapp.w4b".equals(topPkg)) {
            if (!openApp("com.whatsapp")) {
                Log.w(TAG, "WhatsApp open fail");
                return false;
            }
            sleep(2500);
        } else {
            Log.i(TAG, "WhatsApp already open — turant search");
        }

        // Step 2: Search icon (3 attempts × 1s)
        AccessibilityNodeInfo search = null;
        for (int i = 0; i < 3 && search == null; i++) {
            search = findByContentDesc("search", true);
            if (search == null) search = findByViewIdContains("search", true);
            if (search == null) sleep(1000);
        }
        if (search != null) {
            clickNode(search);
            sleep(1200);
        }

        // Step 3: Contact name type karo (English/Hinglish me)
        if (!typeText(c)) {
            if (!typeTextInTopBox(c)) {
                Log.w(TAG, "contact type fail");
                return false;
            }
        }

        // Step 4: ⭐ PEHLA RESULT PAR CLICK — POLL (max 4s: 10 × 400ms)
        AccessibilityNodeInfo result = waitForNode(10, 400, new NodeFinder() {
            @Override
            public AccessibilityNodeInfo find() {
                AccessibilityNodeInfo r = findFirstSearchResult(c);
                return (r != null) ? r : findFirstResultByPosition();
            }
        });
        if (result == null) {
            pressBack();
            Log.w(TAG, "No search result for: " + c);
            return false;
        }
        clickNode(result);

        // Step 5: Chat khule + BOTTOM input box aaye ka wait — POLL (max 4s)
        boolean typed = false;
        for (int i = 0; i < 10 && !typed; i++) {
            typed = typeTextInBottomBox(m);
            if (!typed) sleep(400);
        }
        if (!typed) {
            Log.w(TAG, "message type fail");
            return false;
        }

        // Step 6: WhatsApp ko processing time (message render ho jaye)
        sleep(1500);

        // Step 7: Send button click (multi-strategy)
        boolean sent = clickWhatsAppSendButton();
        Log.i(TAG, "Send result: " + sent);
        return sent;
    }

    /** Node dhundhne ka small interface (polling ke liye) */
    private interface NodeFinder {
        AccessibilityNodeInfo find();
    }

    /** attempts × gapMs tak node aane ka wait karo (null aaye to null) */
    private AccessibilityNodeInfo waitForNode(int attempts, long gapMs, NodeFinder finder) {
        for (int i = 0; i < attempts; i++) {
            AccessibilityNodeInfo r = null;
            try {
                r = finder.find();
            } catch (Exception ignored) {}
            if (r != null) return r;
            sleep(gapMs);
        }
        return null;
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
    // (Number + contact-name calls ab ActionExecutor me ContactResolver
    //  se direct hote hain — yeh UI flow sirf FALLBACK hai)
    // ═══════════════════════════════════════════════════════════

    /** Seedha number se call (ACTION_CALL, fail ho to ACTION_DIAL) */
    public boolean callByNumber(String number) {
        return ContactResolver.callNumber(this, number);
    }

    /**
     * Contact NAME se call (UI fallback):
     * Dialer kholo → People/Contacts tab → naam search → pehla result → CALL button
     */
    public boolean callContact(String name) {
        Log.i(TAG, "📞 callContact (UI fallback): " + name);
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

        // "People" tab — ⭐ Google Dialer me "Contacts" hota hai,
        // Samsung me "People" — dono try karte hain
        AccessibilityNodeInfo people = findByContentDesc("people", true);
        if (people == null) people = findByContentDesc("people", false);
        if (people == null) people = findByContentDesc("contacts", true);
        if (people == null) people = findByContentDesc("contacts", false);
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

    /**
     * Flashlight — true = kaam ho gaya.
     * (Purane code me Settings.Global "torch" hack tha — uske liye
     * WRITE_SECURE_SETTINGS chahiye jo kabhi nahi milti, isliye hata diya)
     */
    public boolean toggleFlashlight(String state) {
        boolean on = "on".equalsIgnoreCase(state);
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cm != null) {
                String[] ids = cm.getCameraIdList();
                if (ids != null && ids.length > 0) {
                    cm.setTorchMode(ids[0], on);
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
