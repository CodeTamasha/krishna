package com.krishna.assistant;

/**
 * Ek chat message — role (user/assistant/system), text, time, action (agar koi action mila ho)
 * Firebase + Mercury AI dono me is structure me jaata hai.
 */
public class ChatMessage {
    public String role;
    public String text;
    public String time;
    public String action;

    public ChatMessage() {}

    public ChatMessage(String role, String text) {
        this.role = role;
        this.text = text;
        this.time = "";
    }

    public ChatMessage(String role, String text, String time, String action) {
        this.role = role;
        this.text = text;
        this.time = time;
        this.action = action;
    }
}
