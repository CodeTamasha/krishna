package com.krishna.assistant;

import android.content.Context;
import android.util.Log;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ═══════════════════════════════════════════════════════════
 * MEMORY MANAGER — SMART MEMORY SYSTEM
 *
 * Mercury AI ka context limited hai, isliye:
 *  A. SHORT-TERM  → sirf last 6-7 messages + compressed summary
 *  B. COMPRESSION → har 50 messages ke baad Mercury se summary, purane delete
 *  C. LONG-TERM   → facts (naam, pasand, number) — hamesha system prompt me
 *
 * Total context 2000 tokens se kam rehta hai.
 * ═══════════════════════════════════════════════════════════
 */
public class MemoryManager {

    private static final String TAG = "KrishnaMemory";

    private final Context ctx;
    private final FirebaseHelper fb = FirebaseHelper.get();

    public MemoryManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // ═══════════════ BASE SYSTEM PROMPT (assets se) ═══════════════

    private String loadBasePrompt() {
        try {
            InputStream is = ctx.getAssets().open("krishna_system_prompt.txt");
            byte[] buf = new byte[16384];
            int n = is.read(buf);
            is.close();
            return new String(buf, 0, Math.max(n, 0), "UTF-8");
        } catch (Exception e) {
            return "You are Krishna, a personal AI voice assistant inside an Android phone. You speak in natural Hinglish. Be helpful and concise. You call the user Boss.";
        }
    }

    /**
     * Poora system prompt = base prompt + user profile + facts
     * + summary + last notification + current date-time
     */
    public String buildSystemPrompt(String deviceId) {
        StringBuilder sb = new StringBuilder();
        sb.append(loadBasePrompt()).append("\n\nRUNTIME CONTEXT:\n");
        try {
            UserProfile profile = fb.getProfile(deviceId);
            sb.append("User's name: ").append(profile.name == null || profile.name.isEmpty() ? "Boss" : profile.name).append("\n");
            if (profile.preferences != null && !profile.preferences.isEmpty()) {
                sb.append("User preferences: ").append(profile.preferences).append("\n");
            }
            List<String> facts = fb.getFacts(deviceId);
            if (!facts.isEmpty()) {
                sb.append("Important facts about user:\n");
                for (String f : facts) {
                    sb.append("- ").append(f).append("\n");
                }
            }
            String summary = fb.getShortTermSummary(deviceId);
            if (summary != null && !summary.trim().isEmpty()) {
                sb.append("Previous conversation summary: ").append(summary.trim()).append("\n");
            }
            String[] lastNotif = fb.getLastNotification(deviceId);
            if (lastNotif != null) {
                sb.append("Last received notification: ").append(lastNotif[0])
                        .append(" par ").append(lastNotif[1])
                        .append(" ka message: ").append(lastNotif[2]).append("\n");
            }
            sb.append("Current date-time: ")
                    .append(new SimpleDateFormat("dd-MM-yyyy, HH:mm 'IST'", Locale.US).format(new Date()))
                    .append("\n");
        } catch (Exception e) {
            Log.e(TAG, "context build fail", e);
        }
        return sb.toString();
    }

    /**
     * AI ke liye poora context banao:
     * [system prompt] + [last 7 messages] + [current user message]
     */
    public List<ChatMessage> buildContext(String deviceId, String currentMessage) {
        List<ChatMessage> ctxList = new ArrayList<>();
        ctxList.add(new ChatMessage("system", buildSystemPrompt(deviceId)));

        List<ChatMessage> recent = fb.getSessionMessages(deviceId);
        if (recent.size() > 7) {
            recent = new ArrayList<>(recent.subList(recent.size() - 7, recent.size()));
        }
        for (ChatMessage m : recent) {
            if ("assistant".equals(m.role)) {
                ctxList.add(new ChatMessage("assistant", m.text));
            } else {
                ctxList.add(new ChatMessage("user", m.text));
            }
        }
        ctxList.add(new ChatMessage("user", currentMessage));
        return ctxList;
    }

    // ═══════════════ BAAT KE BAAD (save + facts + compression) ═══════════════

    public void afterExchange(String deviceId, String userText, String aiReply, String action) {
        try {
            // Pehli baar ho to profile create karo
            UserProfile profile = fb.getProfile(deviceId);
            if (profile.firstSeen == null) {
                fb.saveProfile(deviceId, profile);
            }

            // Dono messages save karo
            fb.addMessage(deviceId, "user", userText, null);
            fb.addMessage(deviceId, "assistant", aiReply, action);

            // User ki baat se facts nikaalo (naam, pasand, number)
            extractAndSaveFacts(deviceId, userText, profile);

            fb.touchLastActive(deviceId);
            fb.incrementCommands(deviceId);

            // Session bada ho raha hai to compress karo
            List<ChatMessage> all = fb.getSessionMessages(deviceId);
            if (all.size() >= 50) {
                compressSession(deviceId, all);
            } else if (all.size() > 50) {
                fb.deleteMessagesExceptLast(deviceId, 50);
            }
        } catch (Exception e) {
            Log.e(TAG, "afterExchange fail", e);
        }
    }

    /**
     * 50+ messages → last 10 rakh ke baaki ki summary banao (Mercury se),
     * summary save karo, purane messages delete karo (bridge: last 10)
     */
    private void compressSession(String deviceId, List<ChatMessage> all) {
        int keep = 10;
        if (all.size() <= keep) return;
        Log.i(TAG, "Compressing session: " + all.size() + " messages");
        List<ChatMessage> old = new ArrayList<>(all.subList(0, all.size() - keep));
        String summary = ApiHelper.compressConversation(old);
        if (summary != null && !summary.trim().isEmpty()) {
            String existing = fb.getShortTermSummary(deviceId);
            String combined = (existing == null || existing.isEmpty())
                    ? summary.trim()
                    : existing + " " + summary.trim();
            fb.saveShortTermSummary(deviceId, combined);
        }
        fb.deleteMessagesExceptLast(deviceId, keep);
    }

    // ═══════════════ FACT EXTRACTION (user ki baat se) ═══════════════

    private void extractAndSaveFacts(String deviceId, String text, UserProfile profile) {
        if (text == null || text.trim().isEmpty()) return;
        try {
            // "Mera naam X hai" / "my name is X"
            Matcher m1 = Pattern.compile(
                    "(?:mera|my)\\s+(?:naam|name)\\s+(?:hai|is|hoon|ho)?\\s*([a-z][a-z ]{1,25})",
                    Pattern.CASE_INSENSITIVE).matcher(text);
            if (m1.find()) {
                String name = m1.group(1).trim().split("\\s+")[0];
                if (name.length() >= 2) {
                    fb.addFact(deviceId, "User ka naam " + name + " hai");
                    profile.name = name;
                    fb.saveProfile(deviceId, profile);
                    Log.i(TAG, "Name fact: " + name);
                }
            }
            // "Mujhe X pasand hai"
            Matcher m2 = Pattern.compile("mujhe\\s+([a-z0-9 ]{2,30})\\s+pasand\\s+hai",
                    Pattern.CASE_INSENSITIVE).matcher(text);
            if (m2.find()) {
                fb.addFact(deviceId, "User ko " + m2.group(1).trim() + " pasand hai");
            }
            // "Mera number X hai"
            Matcher m3 = Pattern.compile("(?:mera|my)\\s+(?:number|phone|mobile)\\s+(?:hai|is)?\\s*([0-9+\\- ]{7,15})",
                    Pattern.CASE_INSENSITIVE).matcher(text);
            if (m3.find()) {
                fb.addFact(deviceId, "User ka contact number " + m3.group(1).trim() + " hai");
            }
            // "Main X hoon"
            Matcher m4 = Pattern.compile("main\\s+([a-z][a-z]{1,15})\\s+(?:hoon|haan|hun)\\s*$",
                    Pattern.CASE_INSENSITIVE).matcher(text);
            if (m4.find()) {
                String name = m4.group(1).trim();
                fb.addFact(deviceId, "User ka naam " + name + " hai");
                profile.name = name;
                fb.saveProfile(deviceId, profile);
            }
        } catch (Exception e) {
            Log.e(TAG, "fact extraction fail", e);
        }
    }
}
