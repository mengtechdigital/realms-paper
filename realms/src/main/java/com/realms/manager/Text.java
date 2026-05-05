package com.realms.manager;

import org.bukkit.ChatColor;

import java.util.Map;

/**
 * Tiny helpers for color codes and message-template rendering. Mirrors
 * marriage-paper's Text class so the two plugins read alike.
 */
public final class Text {
    private Text() {}

    public static String colorize(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    /**
     * Substitute {keys} in template using the placeholders map. Unknown keys
     * are left intact so misspellings are visible in chat.
     */
    public static String render(String template, Map<String, String> placeholders) {
        if (template == null) return "";
        String out = template;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue();
            out = out.replace("{" + e.getKey() + "}", v);
        }
        return colorize(out);
    }

    public static String stripColor(String s) {
        return s == null ? "" : ChatColor.stripColor(colorize(s));
    }
}
