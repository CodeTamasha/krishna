package com.krishna.assistant;

import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ═══════════════════════════════════════════════════════════
 * COMMAND PARSER — AI response se JSON action extract karta hai
 *
 * AI ka format:
 *   "Sure Boss! WhatsApp khol raha hoon.
 *    {\"action\": \"open_app\", \"app\": \"whatsapp\"}"
 *
 * Naya (fix6):
 *   - AI kabhi galti se 2 JSON blocks de deta hai (open_app +
 *     send_message). Pehle sirf pehla block hata jata tha — baaki
 *     JSON TTS me spoken text ki tarah BOLA jata tha. Ab SAARE
 *     JSON blocks spoken text se hatate hain.
 *   - Agar blocks me send_message hai to usko PRIORITY milti hai
 *     (kyunki send_message khud WhatsApp bhi khol deta hai).
 *
 * Sirf flat JSON support (nested objects nahi) — kyunki Mercury
 * flat response deta hai. Agar koi nested JSON aaye to wo
 * spoken text me hi reh jayega (safe fallback).
 * ═══════════════════════════════════════════════════════════
 */
public class CommandParser {

    private static final String TAG = "KrishnaParser";

    public static class ParsedResponse {
        public String spokenText;              // jo TTS se bolna hai
        public String action;                  // action name (null agar koi nahi)
        public final java.util.HashMap<String, String> params = new java.util.HashMap<>();
        public boolean isAction = false;
    }

    /**
     * AI response parse karo.
     * Saare flat JSON action blocks dhundh-te hain:
     *  1. Spoken text me se SAB hata do (JSON kabhi spoken nahi hona chahiye)
     *  2. Ek action choose karo: agar koi bhi block send_message hai to wo,
     *     warna pehla valid block.
     */
    public static ParsedResponse parse(String aiResponse) {
        ParsedResponse out = new ParsedResponse();
        if (aiResponse == null || aiResponse.trim().isEmpty()) {
            out.spokenText = "Haan Boss?";
            return out;
        }

        // Code fences hatao (kabhi-kabhi AI ```json ... ``` me deta hai)
        String cleaned = aiResponse.trim()
                .replaceAll("(?s)```json\\s*", " ")
                .replaceAll("(?s)```\\s*", " ")
                .trim();

        // SAARE flat JSON blocks { ... "action" ... } dhundho
        Matcher m = Pattern.compile("\\{[^{}]*\"action\"[^{}]*\\}").matcher(cleaned);
        StringBuilder spoken = new StringBuilder();
        JsonObject chosen = null;     // execute karne wala action
        JsonObject firstFound = null; // pehla valid block (fallback)
        int blocks = 0;
        while (m.find()) {
            m.appendReplacement(spoken, " ");
            try {
                JsonObject obj = JsonParser.parseString(m.group(0)).getAsJsonObject();
                if (obj.has("action") && obj.get("action").isJsonPrimitive()) {
                    blocks++;
                    if (firstFound == null) firstFound = obj;
                    // send_message priority — kyunki wo khud WhatsApp khol deta hai
                    if ("send_message".equalsIgnoreCase(obj.get("action").getAsString().trim())) {
                        chosen = obj;
                    }
                }
            } catch (Exception ignored) {
                // JSON invalid hai — sirf spoken text se hat gaya, itna kaafi
            }
        }
        m.appendTail(spoken);
        if (blocks > 1) {
            Log.w(TAG, "AI ne " + blocks + " JSON blocks diye — sirf ek execute hoga: "
                    + (chosen != null ? chosen.get("action").getAsString() : firstFound.get("action").getAsString()));
        }

        if (chosen == null) chosen = firstFound;

        if (chosen != null) {
            try {
                out.action = chosen.get("action").getAsString().trim().toLowerCase();
                for (Map.Entry<String, JsonElement> e : chosen.entrySet()) {
                    if ("action".equals(e.getKey())) continue;
                    try {
                        if (e.getValue().isJsonNull()) {
                            out.params.put(e.getKey(), "");
                        } else {
                            out.params.put(e.getKey(), e.getValue().getAsString());
                        }
                    } catch (Exception nested) {
                        // nested value ho to raw string rakh do
                        out.params.put(e.getKey(), e.getValue().toString());
                    }
                }
                out.isAction = true;
                out.spokenText = spoken.toString().replaceAll("\\s+", " ").trim();
                Log.i(TAG, "Action found: " + out.action + " params=" + out.params);
            } catch (Exception e) {
                Log.e(TAG, "action JSON parse fail", e);
            }
        }

        if (!out.isAction) {
            // Koi valid action nahi mila — poori text hi spoken hai
            out.spokenText = cleaned;
        }
        if (out.spokenText == null || out.spokenText.trim().isEmpty()) {
            out.spokenText = "Ho gaya Boss.";
        }
        return out;
    }
}
