package com.krishna.assistant;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI response parse karne ke baad ka result:
 *  - isAction=true  → action JSON mila (ActionExecutor execute karega)
 *  - isAction=false → sirf text hai (seedha TTS par jayega)
 */
public class ParsedResponse {
    public boolean isAction = false;
    public String action = null;
    public Map<String, String> params = new LinkedHashMap<>();
    public String spokenText = "";
}
