package com.realms.data;

/**
 * Zone classification for a realm.
 *
 *   NORMAL    — player-owned realm (default; everything in phases 1–9).
 *   SAFEZONE  — admin-defined immune area. No PvP, no outsider build,
 *               no overclaim, all explosions cancelled. No residents,
 *               no power economy.
 *   WARZONE   — admin-defined PvP arena. PvP forced ON regardless of
 *               flag, but no outsider build, no claim/unclaim by players,
 *               no overclaim. All explosions cancelled (no terrain damage).
 *
 * Admin zones are always claimed by a sentinel realm row that has no
 * residents and a non-mutable {@code zoneType}. The creator's UUID is
 * stored in {@code founder} for audit but doesn't grant ownership.
 */
public enum ZoneType {
    NORMAL,
    SAFEZONE,
    WARZONE;

    public boolean isAdminZone() { return this != NORMAL; }
    public boolean forcesPvpOff() { return this == SAFEZONE; }
    public boolean forcesPvpOn() { return this == WARZONE; }
    public boolean cancelsExplosions() { return isAdminZone(); }

    public static ZoneType parse(String s) {
        if (s == null) return NORMAL;
        try { return ZoneType.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return NORMAL; }
    }
}
