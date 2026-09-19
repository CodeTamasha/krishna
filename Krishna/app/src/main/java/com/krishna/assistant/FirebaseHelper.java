package com.krishna.assistant;

import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * ═══════════════════════════════════════════════════════════
 * FIREBASE HELPER — saara memory/data Firebase Realtime DB me
 * (REST API se — Admin SDK ki zaroorat nahi, public rules + secret kaafi hai)
 *
 * Structure:
 *   krishna/users/{device_id}/
 *     ├── profile/            (name, preferences, first_seen, last_active)
 *     ├── memory/short_term/  (summary, last_updated)
 *     ├── memory/long_term/facts/  (0: {fact, added}, 1: ...)
 *     ├── memory/last_notification/ (app, sender, text, time)
 *     ├── conversations/current_session/messages/ (msg_xxx: {role, text, time, action})
 *     ├── conversations/history/{session_id}/
 *     ├── notifications/
 *     └── stats/
 *
 * NOTE: Yeh methods BLOCKING hain — hamesha background thread se call karna.
 * ═══════════════════════════════════════════════════════════
 */
public class FirebaseHelper {

    private static final String TAG = "KrishnaFirebase";
    private static final FirebaseHelper INSTANCE = new FirebaseHelper();

    // Message keys ke liye sequence — "msg_" + millis AKAHI HAI kyunki
    // user + assistant message back-to-back save hote hain aur same millisecond
    // me dono ka key same ho jata tha → doosra PUT pehle wale ko overwrite
    // kar deta tha (message data loss). Ab har key unique hai.
    private static final java.util.concurrent.atomic.AtomicLong MSG_SEQ = new java.util.concurrent.atomic.AtomicLong();

    public static FirebaseHelper get() {
        return INSTANCE;
    }

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    public boolean isConfigured() {
        return Constants.FIREBASE_DB_URL != null && !Constants.FIREBASE_DB_URL.trim().isEmpty();
    }

    // ═══════════════ URL building ═══════════════

    private String base() {
        if (!isConfigured()) return null;
        String url = Constants.FIREBASE_DB_URL.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private String authParam() {
        String s = Constants.FIREBASE_SECRET;
        if (s == null || s.isEmpty()) return "";
        try {
            return "?auth=" + URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private String urlFor(String deviceId, String path) {
        String b = base();
        if (b == null) return null;
        return b + "/krishna/users/" + deviceId + "/" + path + ".json" + authParam();
    }

    // ═══════════════ Raw HTTP (GET / PUT / POST / DELETE) ═══════════════

    private String httpGet(String url) {
        if (url == null) return null;
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return null;
            String body = resp.body().string();
            if (body == null || body.trim().equals("null")) return null;
            return body;
        } catch (Exception e) {
            Log.e(TAG, "GET fail", e);
            return null;
        }
    }

    private String httpPut(String url, String json) {
        if (url == null) return null;
        RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));
        Request req = new Request.Builder().url(url).put(rb).build();
        try (Response resp = client.newCall(req).execute()) {
            return resp.isSuccessful() ? "ok" : "fail:" + resp.code();
        } catch (Exception e) {
            Log.e(TAG, "PUT fail", e);
            return null;
        }
    }

    private String httpPost(String url, String json) {
        if (url == null) return null;
        RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));
        Request req = new Request.Builder().url(url).post(rb).build();
        try (Response resp = client.newCall(req).execute()) {
            if (resp.isSuccessful() && resp.body() != null) {
                String body = resp.body().string();
                try {
                    JsonObject o = JsonParser.parseString(body).getAsJsonObject();
                    if (o.has("name")) return o.get("name").getAsString();
                } catch (Exception ignored) {}
                return "auto";
            }
            return "fail";
        } catch (Exception e) {
            Log.e(TAG, "POST fail", e);
            return null;
        }
    }

    private void httpDelete(String url) {
        if (url == null) return;
        Request req = new Request.Builder().url(url).delete().build();
        try (Response resp = client.newCall(req).execute()) {
            Log.d(TAG, "DELETE " + resp.code());
        } catch (Exception e) {
            Log.e(TAG, "DELETE fail", e);
        }
    }

    // ═══════════════ PROFILE ═══════════════

    /** User ka profile lao. Naya user ho to default "Boss" profile milta hai. */
    public UserProfile getProfile(String deviceId) {
        UserProfile p = new UserProfile();
        String json = httpGet(urlFor(deviceId, "profile"));
        if (json == null) {
            p.name = "Boss";
            p.preferences = new HashMap<>();
            return p;
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (o.has("name") && o.get("name").isJsonPrimitive()) p.name = o.get("name").getAsString();
            if (o.has("first_seen") && o.get("first_seen").isJsonPrimitive()) p.firstSeen = o.get("first_seen").getAsString();
            if (o.has("last_active") && o.get("last_active").isJsonPrimitive()) p.lastActive = o.get("last_active").getAsString();
            p.preferences = new HashMap<>();
            if (o.has("preferences") && o.get("preferences").isJsonObject()) {
                JsonObject pref = o.getAsJsonObject("preferences");
                for (Map.Entry<String, JsonElement> e : pref.entrySet()) {
                    p.preferences.put(e.getKey(), e.getValue().isJsonNull() ? "" : e.getValue().getAsString());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "profile parse fail", e);
        }
        return p;
    }

    public void saveProfile(String deviceId, UserProfile p) {
        if (!isConfigured()) return;
        JsonObject o = new JsonObject();
        o.addProperty("name", p.name == null ? "Boss" : p.name);
        o.addProperty("first_seen", p.firstSeen != null ? p.firstSeen : DeviceUtils.nowIso());
        o.addProperty("last_active", DeviceUtils.nowIso());
        JsonObject pref = new JsonObject();
        if (p.preferences != null) {
            for (Map.Entry<String, String> e : p.preferences.entrySet()) {
                pref.addProperty(e.getKey(), e.getValue());
            }
        }
        o.add("preferences", pref);
        httpPut(urlFor(deviceId, "profile"), o.toString());
    }

    /**
     * Har interaction ke baad last_active update karo.
     * (Purane code me yeh poora profile GET + PUT karta tha — har command par
     * 2 extra network calls. Ab sirf last_active leaf par 1 hi PUT hota hai.)
     */
    public void touchLastActive(String deviceId) {
        if (!isConfigured()) return;
        httpPut(urlFor(deviceId, "profile/last_active"), "\"" + DeviceUtils.nowIso() + "\"");
    }

    // ═══════════════ SHORT-TERM MEMORY (compressed summary) ═══════════════

    public String getShortTermSummary(String deviceId) {
        String json = httpGet(urlFor(deviceId, "memory/short_term/summary"));
        if (json == null) return null;
        try {
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonPrimitive() ? e.getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void saveShortTermSummary(String deviceId, String summary) {
        if (!isConfigured()) return;
        JsonObject o = new JsonObject();
        o.addProperty("summary", summary == null ? "" : summary);
        o.addProperty("last_updated", DeviceUtils.nowIso());
        httpPut(urlFor(deviceId, "memory/short_term"), o.toString());
    }

    // ═══════════════ LONG-TERM FACTS ═══════════════

    /** User ke saare permanent facts lao ("User ka naam Rahul hai" waghera) */
    public List<String> getFacts(String deviceId) {
        List<String> out = new ArrayList<>();
        String json = httpGet(urlFor(deviceId, "memory/long_term/facts"));
        if (json == null) return out;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                try {
                    if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("fact")) {
                        out.add(e.getValue().getAsJsonObject().get("fact").getAsString());
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "facts parse fail", e);
        }
        return out;
    }

    /** Naya fact save karo (duplicate nahi banega) */
    public void addFact(String deviceId, String fact) {
        if (fact == null || fact.trim().isEmpty()) return;
        if (!isConfigured()) return;
        List<String> existing = getFacts(deviceId);
        for (String f : existing) {
            if (f.equalsIgnoreCase(fact.trim())) return; // pehle se hai
        }
        JsonObject o = new JsonObject();
        o.addProperty("fact", fact.trim());
        o.addProperty("added", DeviceUtils.nowIso());
        httpPost(urlFor(deviceId, "memory/long_term/facts"), o.toString());
    }

    // ═══════════════ CONVERSATION SESSION ═══════════════

    /** Current session ke saare messages lao (time ke order me) */
    public List<ChatMessage> getSessionMessages(String deviceId) {
        List<ChatMessage> out = new ArrayList<>();
        String json = httpGet(urlFor(deviceId, "conversations/current_session/messages"));
        if (json == null) return out;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                try {
                    if (!e.getValue().isJsonObject()) continue;
                    JsonObject m = e.getValue().getAsJsonObject();
                    ChatMessage cm = new ChatMessage();
                    cm.role = m.has("role") ? m.get("role").getAsString() : "user";
                    cm.text = m.has("text") ? m.get("text").getAsString() : "";
                    cm.time = m.has("time") ? m.get("time").getAsString() : DeviceUtils.nowIso();
                    cm.action = (m.has("action") && !m.get("action").isJsonNull()) ? m.get("action").getAsString() : null;
                    out.add(cm);
                } catch (Exception ignored) {}
            }
            out.sort(Comparator.comparing((ChatMessage c) -> c.time == null ? "" : c.time));
        } catch (Exception e) {
            Log.e(TAG, "session parse fail", e);
        }
        return out;
    }

    /** Ek message session me add karo */
    public void addMessage(String deviceId, String role, String text, String action) {
        if (!isConfigured()) return;
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("text", text);
        o.addProperty("time", DeviceUtils.nowIso());
        if (action != null) o.addProperty("action", action);
        String key = "msg_" + System.currentTimeMillis() + "_" + MSG_SEQ.incrementAndGet();
        httpPut(urlFor(deviceId, "conversations/current_session/messages/" + key), o.toString());
    }

    /** Pura session archive karo (history me) aur current session saaf karo */
    public void archiveSession(String deviceId, String summary) {
        if (!isConfigured()) return;
        String json = httpGet(urlFor(deviceId, "conversations/current_session/messages"));
        String sessionId = "sess_" + System.currentTimeMillis();
        JsonObject o = new JsonObject();
        try {
            o.add("messages", json != null ? JsonParser.parseString(json) : new JsonObject());
        } catch (Exception e) {
            o.add("messages", new JsonObject());
        }
        o.addProperty("summary", summary == null ? "" : summary);
        o.addProperty("start", DeviceUtils.nowIso());
        o.addProperty("end", DeviceUtils.nowIso());
        httpPut(urlFor(deviceId, "conversations/history/" + sessionId), o.toString());
        httpDelete(urlFor(deviceId, "conversations/current_session/messages"));
    }

    /**
     * Session ko limit rakho — sirf last 'keep' messages rehne dena,
     * purane keys DELETE karo. (Memory spec: max 50, compression ke baad 10)
     */
    public void deleteMessagesExceptLast(String deviceId, int keep) {
        if (!isConfigured()) return;
        String json = httpGet(urlFor(deviceId, "conversations/current_session/messages"));
        if (json == null) return;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(o.entrySet());
            entries.sort(Comparator.comparing(e -> {
                try {
                    if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("time")) {
                        return e.getValue().getAsJsonObject().get("time").getAsString();
                    }
                } catch (Exception ignored) {}
                return "";
            }));
            int removeCount = entries.size() - keep;
            for (int i = 0; i < removeCount; i++) {
                httpDelete(urlFor(deviceId, "conversations/current_session/messages/" + entries.get(i).getKey()));
            }
        } catch (Exception e) {
            Log.e(TAG, "prune fail", e);
        }
    }

    // ═══════════════ NOTIFICATIONS ═══════════════

    /** Aayi notification save karo + "last notification" update karo */
    public void saveNotification(String deviceId, String app, String sender, String text) {
        if (!isConfigured()) return;
        JsonObject o = new JsonObject();
        o.addProperty("app", app);
        o.addProperty("sender", sender);
        o.addProperty("text", text);
        o.addProperty("time", DeviceUtils.nowIso());
        httpPost(urlFor(deviceId, "notifications"), o.toString());
        setLastNotification(deviceId, app, sender, text);
    }

    public void setLastNotification(String deviceId, String app, String sender, String text) {
        if (!isConfigured()) return;
        JsonObject o = new JsonObject();
        o.addProperty("app", app);
        o.addProperty("sender", sender);
        o.addProperty("text", text);
        o.addProperty("time", DeviceUtils.nowIso());
        httpPut(urlFor(deviceId, "memory/last_notification"), o.toString());
    }

    /** Last aayi notification lao → {app, sender, text} ya null */
    public String[] getLastNotification(String deviceId) {
        String json = httpGet(urlFor(deviceId, "memory/last_notification"));
        if (json == null) return null;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            return new String[] {
                    o.has("app") ? o.get("app").getAsString() : "",
                    o.has("sender") ? o.get("sender").getAsString() : "",
                    o.has("text") ? o.get("text").getAsString() : ""
            };
        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════ STATS ═══════════════

    public void incrementCommands(String deviceId) {
        if (!isConfigured()) return;
        int n = readInt(urlFor(deviceId, "stats/total_commands")) + 1;
        httpPut(urlFor(deviceId, "stats/total_commands"), String.valueOf(n));
    }

    public void incrementMessagesSent(String deviceId) {
        if (!isConfigured()) return;
        int n = readInt(urlFor(deviceId, "stats/total_messages_sent")) + 1;
        httpPut(urlFor(deviceId, "stats/total_messages_sent"), String.valueOf(n));
    }

    public void trackAppOpened(String deviceId, String appName) {
        if (!isConfigured()) return;
        String safe = appName == null ? "unknown" : appName.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        int n = readInt(urlFor(deviceId, "stats/apps_opened/" + safe)) + 1;
        httpPut(urlFor(deviceId, "stats/apps_opened/" + safe), String.valueOf(n));
    }

    private int readInt(String url) {
        String j = httpGet(url);
        if (j == null) return 0;
        try {
            return Integer.parseInt(j.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Stats dialog ke liye readable lines */
    public List<String> getStats(String deviceId) {
        List<String> lines = new ArrayList<>();
        if (!isConfigured()) {
            lines.add("Firebase set nahi hai.");
            lines.add("Constants.java me FIREBASE_DB_URL daalo.");
            return lines;
        }
        lines.add("Total commands: " + readInt(urlFor(deviceId, "stats/total_commands")));
        lines.add("Messages sent: " + readInt(urlFor(deviceId, "stats/total_messages_sent")));
        lines.add("Long-term facts: " + getFacts(deviceId).size());
        String apps = httpGet(urlFor(deviceId, "stats/apps_opened"));
        if (apps != null) {
            try {
                JsonObject o = JsonParser.parseString(apps).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                    try {
                        lines.add("  • " + e.getKey() + ": " + e.getValue().getAsInt() + " baar");
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        return lines;
    }
}
