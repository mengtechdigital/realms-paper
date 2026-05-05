package com.realms.manager;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory pending invite store. Invites expire after a fixed window (60s
 * by default). Non-persistent — rebooting clears all pending invites,
 * which is the right thing.
 */
public final class InviteStore {

    /** invitee uuid -> (realm id, expires-at-millis) */
    private final Map<UUID, Entry> invites = new ConcurrentHashMap<>();

    public record Entry(long realmId, long expiresAtMillis) {
        public boolean expired(long nowMillis) { return nowMillis >= expiresAtMillis; }
    }

    public void put(UUID invitee, long realmId, long ttlSeconds) {
        invites.put(invitee, new Entry(realmId, Instant.now().toEpochMilli() + ttlSeconds * 1000L));
    }

    /** Returns the realm id if an invite is active for the player, else null. */
    public Long active(UUID invitee, long realmId) {
        Entry e = invites.get(invitee);
        if (e == null) return null;
        if (e.expired(Instant.now().toEpochMilli())) {
            invites.remove(invitee, e);
            return null;
        }
        return e.realmId() == realmId ? e.realmId() : null;
    }

    public Long activeAny(UUID invitee) {
        Entry e = invites.get(invitee);
        if (e == null) return null;
        if (e.expired(Instant.now().toEpochMilli())) {
            invites.remove(invitee, e);
            return null;
        }
        return e.realmId();
    }

    public void clear(UUID invitee) { invites.remove(invitee); }

    /** Drop every pending invite that points at the given realm. Used on disband. */
    public void clearForRealm(long realmId) {
        invites.entrySet().removeIf(e -> e.getValue().realmId() == realmId);
    }

    public void purgeExpired() {
        long now = Instant.now().toEpochMilli();
        invites.entrySet().removeIf(e -> e.getValue().expired(now));
    }
}
