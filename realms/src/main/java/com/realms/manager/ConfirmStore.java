package com.realms.manager;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks pending "confirm" tokens for destructive or large operations
 * (disband, claim N≥5). Token is keyed by (player, action-key) so a
 * disband-confirm pending doesn't accidentally accept a claim-confirm.
 */
public final class ConfirmStore {

    private final Map<String, Long> pending = new ConcurrentHashMap<>();
    private final long ttlSeconds;

    public ConfirmStore(long ttlSeconds) {
        this.ttlSeconds = Math.max(5, ttlSeconds);
    }

    private static String key(UUID player, String action) {
        return player + "|" + action;
    }

    public void arm(UUID player, String action) {
        pending.put(key(player, action), Instant.now().toEpochMilli() + ttlSeconds * 1000L);
    }

    /** Returns true and consumes the token if armed; false otherwise. */
    public boolean consume(UUID player, String action) {
        String k = key(player, action);
        Long until = pending.remove(k);
        if (until == null) return false;
        return Instant.now().toEpochMilli() < until;
    }

    public boolean armed(UUID player, String action) {
        Long until = pending.get(key(player, action));
        if (until == null) return false;
        if (Instant.now().toEpochMilli() >= until) {
            pending.remove(key(player, action));
            return false;
        }
        return true;
    }

    public void purgeExpired() {
        long now = Instant.now().toEpochMilli();
        pending.entrySet().removeIf(e -> e.getValue() <= now);
    }
}
