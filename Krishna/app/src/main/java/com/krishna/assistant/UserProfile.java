package com.krishna.assistant;

import java.util.HashMap;
import java.util.Map;

/**
 * User ka profile — naam, preferences, first_seen, last_active
 * Firebase: krishna/users/{device_id}/profile
 */
public class UserProfile {
    public String name = "Boss";
    public Map<String, String> preferences = new HashMap<>();
    public String firstSeen;
    public String lastActive;

    public UserProfile() {}
}
