package com.realms.data;

/**
 * Per-player display preferences. {@link #defaults()} returns the values used
 * when a player has no row yet — so we don't insert a row until they actually
 * change something.
 */
public record DisplayPrefs(boolean titleOn, BarMode barMode, boolean soundOn) {

    public enum BarMode { ACTION, BOSS, OFF }

    public static DisplayPrefs defaults() {
        return new DisplayPrefs(true, BarMode.ACTION, false);
    }

    public DisplayPrefs withTitle(boolean v) { return new DisplayPrefs(v, barMode, soundOn); }
    public DisplayPrefs withBarMode(BarMode m) { return new DisplayPrefs(titleOn, m, soundOn); }
    public DisplayPrefs withSound(boolean v) { return new DisplayPrefs(titleOn, barMode, v); }
}
