package com.krishna.assistant;

import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * ═══════════════════════════════════════════════════════════
 * API HELPER — Mercury AI (brain) + Fish Audio (voice)
 * Saare network calls background me (OkHttp) — main thread par Nahi.
 * ═══════════════════════════════════════════════════════════
 */
public class ApiHelper {

    private static final String TAG = "KrishnaApi";

    // Mercury client — 180 second timeout (spec ke mutabik)
    private static final OkHttpClient MERCURY_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .build();

    // Fish Audio client — 90 second timeout (spec ke mutabik)
    private static final OkHttpClient FISH_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build();

    private static final MediaType JSON = MediaType.parse("application/json");

    // ═══════════════════════════════════════════════════════
    // 1. MERCURY AI — conversation ke messages bhejo, AI reply lao
    //    Retry: 3 attempts, wait 2^attempt seconds (1s, 2s, 4s)
    //    Final failure par null return → caller fallback line bolti hai
    // ═══════════════════════════════════════════════════════
    public static String callMercuryAI(List<ChatMessage> messages) {
        if (Constants.MERCURY_API_KEY == null || Constants.MERCURY_API_KEY.isEmpty()) {
            Log.w(TAG, "MERCURY_API_KEY set nahi hai — Constants.java me daalo");
            return null;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("model", Constants.MERCURY_MODEL);
                body.addProperty("reasoning_effort", "low");
                body.addProperty("max_tokens", Constants.MERCURY_MAX_TOKENS);
                body.addProperty("temperature", Constants.MERCURY_TEMPERATURE);
                JsonArray arr = new JsonArray();
                for (ChatMessage m : messages) {
                    JsonObject mm = new JsonObject();
                    mm.addProperty("role", m.role == null ? "user" : m.role);
                    mm.addProperty("content", m.text == null ? "" : m.text);
                    arr.add(mm);
                }
                body.add("messages", arr);

                Request req = new Request.Builder()
                        .url("https://api.inceptionlabs.ai/v1/chat/completions")
                        .addHeader("Authorization", "Bearer " + Constants.MERCURY_API_KEY)
                        .addHeader("Content-Type", "application/json")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();

                Log.i(TAG, "Mercury call attempt " + (attempt + 1));
                try (Response resp = MERCURY_CLIENT.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        String responseBody = resp.body().string();
                        try {
                            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
                            JsonArray choices = root.getAsJsonArray("choices");
                            if (choices != null && choices.size() > 0) {
                                String content = choices.get(0).getAsJsonObject()
                                        .getAsJsonObject("message").get("content").getAsString();
                                if (content != null && !content.trim().isEmpty()) {
                                    return content.trim();
                                }
                            }
                        } catch (Exception pe) {
                            Log.e(TAG, "mercury response parse fail", pe);
                        }
                    } else {
                        Log.e(TAG, "Mercury HTTP error: " + (resp == null ? "null" : resp.code()));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "mercury attempt " + (attempt + 1) + " fail", e);
            }
            sleepSeconds(1L << attempt); // 1, 2, 4 second
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════
    // 2. FISH AUDIO TTS — text se MP3 lao
    //    Retry: 3 attempts, wait 2*attempt seconds (2s, 4s)
    //    Response < 1000 bytes → retry. Final failure → null
    //    (caller Android TTS fallback use karega)
    // ═══════════════════════════════════════════════════════
    public static byte[] callFishAudioTTS(String text) {
        if (Constants.FISH_API_KEY == null || Constants.FISH_API_KEY.isEmpty()) {
            Log.w(TAG, "FISH_API_KEY set nahi hai — Constants.java me daalo");
            return null;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("text", text);
                body.addProperty("reference_id", Constants.FISH_REFERENCE_ID);
                body.addProperty("format", "mp3");

                Request req = new Request.Builder()
                        .url("https://api.fish.audio/v1/tts")
                        .addHeader("Authorization", "Bearer " + Constants.FISH_API_KEY)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("model", Constants.FISH_MODEL)
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();

                try (Response resp = FISH_CLIENT.newCall(req).execute()) {
                    if (resp.code() == 200 && resp.body() != null) {
                        byte[] data = resp.body().bytes();
                        if (data.length >= 1000) {
                            return data;
                        }
                        Log.w(TAG, "Fish response chhota hai: " + data.length + " bytes — retry");
                    } else {
                        Log.e(TAG, "Fish HTTP error: " + resp.code());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "fish attempt " + (attempt + 1) + " fail", e);
            }
            sleepSeconds(2L * (attempt + 1)); // 2, 4 second
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════
    // 3. MEMORY COMPRESSION — purani baatein chhoti summary me badlo
    //    (har 50 messages ke baad MemoryManager call karta hai)
    // ═══════════════════════════════════════════════════════
    public static String compressConversation(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return null;
        List<ChatMessage> list = new ArrayList<>();
        list.add(new ChatMessage("system",
                "Summarize this conversation in 3 short Hinglish sentences, focusing on user preferences, important facts and pending tasks. Output ONLY the summary text, nothing else."));
        list.addAll(messages);
        return callMercuryAI(list);
    }

    private static void sleepSeconds(long s) {
        try {
            Thread.sleep(s * 1000);
        } catch (InterruptedException ignored) {}
    }
}
