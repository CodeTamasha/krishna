package com.krishna.assistant;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ═══════════════════════════════════════════════════════════
 * MEMORY MANAGER — SMART MEMORY SYSTEM (v1.1.0 — FAST REWRITE)
 *
 * PURANA SAMSSYA (yahi "sab slow" ki wajah thi):
 *   Har command par network pe jaata tha:
 *     - buildContext → session GET
 *     - afterExchange → profile GET + PUT, addMessage PUT×2,
 *       touch GET+PUT, increment GET+PUT, prune DELETEs...
 *   Matlab: AI reply bolne se PEHLE ~8-10 HTTP calls block karte the.
 *
 * AB KA TARIIKA:
 *   A. SHORT-TERM  → sirf last 6-7 messages + compressed summary
 *   B. WARM-UP     → service start hote hi SAARA context background me
 *                    load ho jaata hai (profile, facts, summary,
 *                    last notification, session) — local cache me
 *   C. buildContext → 100% LOCAL, ZERO network → AI call turant
 *   D. afterExchange→ local cache turant update + Firebase persist
 *                    BACKGROUND thread me (voice ko block NAHI karta)
 *   E. COMPRESSION → har 50 messages ke baad Mercury se summary
 *   F. LONG-TERM   → facts (naam, pasand, number) — hamesha system prompt me
 *
 * Total context 2000 tokens se kam rehta hai.
 * ═══════════════════════════════════════════════════════════
 */
public class MemoryManager {

    private static final String TAG = "KrishnaMemory";

    private static final int SESSION_LIMIT = 50;         // iske baad compress
    private static final int KEEP_AFTER_COMPRESS = 10;   // compress ke baad last 10 bridge
    private static final int CONTEXT_MESSAGES = 7;       // AI ko last 7 messages milte hain

    private final Context ctx;
    private final FirebaseHelper fb = FirebaseHelper.get();
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Object cacheLock = new Object();

    // ═══════════════ LOCAL CACHE (AI context network ke bina banta hai) ═══════════════
    private volatile UserProfile profile = new UserProfile();
    private volatile List<String> facts = new ArrayList<>();
    private volatile String summary = "";
    private volatile String[] lastNotif;
    private final List<ChatMessage> session = new ArrayList<>(); // cacheLock se guard
    private volatile boolean warmed = false;

    public MemoryManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /**
     * Service start hote hi call karo — background me Firebase se
     * poora context load karo. Jab tak yeh complete nahi hota,
     * buildContext cached (empty/default) data use karta hai —
     * pehli command bhi instant hoti hai.
     */
    public void warmUp(final String deviceId) {
        bg.submit(() -> {
            try {
                final UserProfile p = fb.getProfile(deviceId);
                final List<String> f = fb.getFacts(deviceId);
                final String s = fb.getShortTermSummary(deviceId);
                final String[] ln = fb.getLastNotification(deviceId);
                List<ChatMessage> msgs = fb.getSessionMessages(deviceId);
                synchronized (cacheLock) {
                    profile = (p == null) ? new UserProfile() : p;
                    facts = (f == null) ? new ArrayList<>() : f;
                    summary = (s == null) ? "" : s;
                    lastNotif = ln;
                    session.clear();
                    if (msgs != null) session.addAll(msgs);
                }
                warmed = true;
                Log.i(TAG, "Context warm: " + session.size() + " msgs, "
                        + facts.size() + " facts, summary=" + (summary.isEmpty() ? "none" : "yes"));
            } catch (Exception e) {
                Log.e(TAG, "warmup fail", e);
            }
        });
    }

    public boolean isWarmed() {
        return warmed;
    }

    // ═══════════════ BASE SYSTEM PROMPT (assets se) ═══════════════

    private String loadBasePrompt() {
        try {
            InputStream is = ctx.getAssets().open("krishna_system_prompt.txt");
            try {
                // ⚡ PURANA BUG: single is.read(buf) guarantee nahi deta tha
                // ki poori file padhi hogi — prompt bada hote hi adhoora
                // load hota. Ab loop tak ki poora stream read ho jaye.
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                return bos.toString("UTF-8");
            } finally {
                try { is.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            return "You are Krishna, a personal AI voice assistant inside an Android phone. "
                    + "You speak in natural Hinglish. Be helpful and concise. You call the user Boss.";
        }
    }

    /**
     * Poora system prompt = base prompt + user profile + facts
     * + summary + last notification + current date-time
     * (100% LOCAL — cache se)
     */
    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append(loadBasePrompt()).append("\n\nRUNTIME CONTEXT:\n");
        try {
            sb.append("User's name: ").append(userName()).append("\n");
            if (profile.preferences != null && !profile.preferences.isEmpty()) {
                sb.append("User preferences: ").append(profile.preferences).append("\n");
            }
            List<String> f = facts;
            if (f != null && !f.isEmpty()) {
                sb.append("Important facts about user:\n");
                for (String ft : f) {
                    sb.append("- ").append(ft).append("\n");
                }
            }
            String s = summary;
            if (s != null && !s.trim().isEmpty()) {
                sb.append("Previous conversation summary: ").append(s.trim()).append("\n");
            }
            String[] ln = lastNotif;
            if (ln != null) {
                sb.append("Last received notification: ").append(ln[0])
                        .append(" par ").append(ln[1])
                        .append(" ka message: ").append(ln[2]).append("\n");
            }
            sb.append("Current date-time: ").append(DeviceUtils.nowIso()).append("\n");
        } catch (Exception e) {
            Log.e(TAG, "context build fail", e);
        }
        return sb.toString();
    }

    private String userName() {
        String n = profile == null ? null : profile.name;
        return (n == null || n.isEmpty()) ? "Boss" : n;
    }

    /**
     * AI ke liye poora context banao — ZERO NETWORK:
     * [system prompt] + [last 7 messages] + [current user message]
     */
    public List<ChatMessage> buildContext(String deviceId, String currentMessage) {
        List<ChatMessage> out = new ArrayList<>();
        out.add(new ChatMessage("system", buildSystemPrompt()));

        List<ChatMessage> snap;
        synchronized (cacheLock) {
            int from = Math.max(0, session.size() - CONTEXT_MESSAGES);
            snap = new ArrayList<>(session.subList(from, session.size()));
        }
        for (ChatMessage m : snap) {
            out.add(new ChatMessage("assistant".equals(m.role) ? "assistant" : "user", m.text));
        }
        out.add(new ChatMessage("user", currentMessage));
        return out;
    }

    // ═══════════════ BAAT KE BAAD (local sync + Firebase async) ═══════════════

    /**
     * ⭐ AB YEH INSTANT HAI:
     *  1. Local cache turant update (next command ka context ready)
     *  2. Facts extract (local regex — milliseconds)
     *  3. Firebase persist BACKGROUND me — voice/next command ko block NAHI karta
     */
    public void afterExchange(final String deviceId, String userText, String aiReply, String action) {
        // 1) Local cache — turant
        String now = DeviceUtils.nowIso();
        synchronized (cacheLock) {
            session.add(new ChatMessage("user", userText, now, null));
            session.add(new ChatMessage("assistant", aiReply, now, action));
            if (session.size() > SESSION_LIMIT) {
                session.subList(0, session.size() - SESSION_LIMIT).clear();
            }
        }

        // 2) Facts extract (local — fast)
        final List<String> newFacts = new ArrayList<>();
        final boolean[] nameChanged = { false };
        try {
            extractFacts(userText, newFacts, nameChanged);
        } catch (Exception e) {
            Log.e(TAG, "fact extraction fail", e);
        }

        // 3) Firebase persist — BACKGROUND (voice ko block nahi karega)
        final boolean firstSeen = (profile == null || profile.firstSeen == null);
        bg.submit(() -> {
            try {
                fb.addMessage(deviceId, "user", userText, null);
                fb.addMessage(deviceId, "assistant", aiReply, action);
                for (String f : newFacts) {
                    fb.addFact(deviceId, f);
                }
                if (firstSeen || nameChanged[0]) {
                    fb.saveProfile(deviceId, profile);
                }
                fb.touchLastActive(deviceId);
                fb.incrementCommands(deviceId);
                maybeCompress(deviceId);
            } catch (Exception e) {
                Log.e(TAG, "persist fail", e);
            }
        });
    }

    /**
     * 50+ messages → last 10 rakh ke baaki ki summary banao (Mercury se),
     * summary save karo, purane messages delete karo (bridge: last 10)
     * (yeh background thread par chalta hai)
     */
    private void maybeCompress(String deviceId) {
        List<ChatMessage> snap;
        synchronized (cacheLock) {
            snap = new ArrayList<>(session);
        }
        if (snap.size() < SESSION_LIMIT) return;

        Log.i(TAG, "Compressing session: " + snap.size() + " messages");
        List<ChatMessage> old = new ArrayList<>(snap.subList(0, snap.size() - KEEP_AFTER_COMPRESS));
        String s = ApiHelper.compressConversation(old);
        if (s != null && !s.trim().isEmpty()) {
            String existing = summary;
            summary = ((existing == null || existing.trim().isEmpty()) ? "" : existing.trim() + " ")
                    + s.trim();
            fb.saveShortTermSummary(deviceId, summary);
        }
        fb.deleteMessagesExceptLast(deviceId, KEEP_AFTER_COMPRESS);
        synchronized (cacheLock) {
            while (session.size() > KEEP_AFTER_COMPRESS) {
                session.remove(0);
            }
        }
        Log.i(TAG, "Compression done — " + session.size() + " msgs baaki");
    }

    // ═══════════════ FACT EXTRACTION (user ki baat se) ═══════════════

    private void extractFacts(String text, List<String> outNewFacts, boolean[] nameChanged) {
        if (text == null || text.trim().isEmpty()) return;
        try {
            // "Mera naam X hai" / "my name is X"
            Matcher m1 = Pattern.compile(
                    "(?:mera|my)\\s+(?:naam|name)\\s+(?:hai|is|hoon|ho)?\\s*([a-z][a-z ]{1,25})",
                    Pattern.CASE_INSENSITIVE).matcher(text);
            if (m1.find()) {
                String name = m1.group(1).trim().split("\\s+")[0];
                if (name.length() >= 2) {
                    addLocalFact(outNewFacts, "User ka naam " + name + " hai");
                    if (profile.name == null || !profile.name.equalsIgnoreCase(name)) {
                        profile.name = name;
                        nameChanged[0] = true;
                    }
                    Log.i(TAG, "Name fact: " + name);
                }
            }
            // "Mujhe X pasand hai"
            Matcher m2 = Pattern.compile("mujhe\\s+([a-z0-9 ]{2,30})\\s+pasand\\s+hai",
                    Pattern.CASE_INSENSITIVE).matcher(text);
            if (m2.find()) {
                addLocalFact(outNewFacts, "User ko " + m2.group(1).trim() + " pasand hai");
            }
            // "Mera number X hai"
            Matcher m3 = Pattern.compile("(?:mera|my)\\s+(?:number|phone|mobile)\\s+(?:hai|is)?\\s*([0-9+\\- ]{7,15})",
                    Pattern.CASE_INSENSITIVE).matcher(text);
            if (m3.find()) {
                addLocalFact(outNewFacts, "User ka contact number " + m3.group(1).trim() + " hai");
            }
            // "Main X hoon"
            Matcher m4 = Pattern.compile("main\\s+([a-z][a-z]{1,15})\\s+(?:hoon|haan|hun)\\s*$",
                    Pattern.CASE_INSENSITIVE).matcher(text);
            if (m4.find()) {
                String name = m4.group(1).trim();
                addLocalFact(outNewFacts, "User ka naam " + name + " hai");
                if (profile.name == null || !profile.name.equalsIgnoreCase(name)) {
                    profile.name = name;
                    nameChanged[0] = true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "fact extraction fail", e);
        }
    }

    /** Local facts list me duplicate-check ke saath add karo */
    private void addLocalFact(List<String> outNewFacts, String fact) {
        synchronized (cacheLock) {
            for (String existing : facts) {
                if (existing.equalsIgnoreCase(fact)) return;
            }
            facts = new ArrayList<>(facts);
            facts.add(fact);
        }
        outNewFacts.add(fact);
    }
}
