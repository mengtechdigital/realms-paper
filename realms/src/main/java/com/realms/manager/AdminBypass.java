package com.realms.manager;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player admin bypass: when ON, claim protection treats the player as a
 * silent member of every realm. Toggled via /realm admin bypass (phase 9).
 *
 * Non-persistent on purpose — bypass should re-arm on every login so admins
 * who forget to switch it off don't accidentally bulldoze someone's town.
 */
public final class AdminBypass {

    private final Set<UUID> bypassing = ConcurrentHashMap.newKeySet();

    public boolean toggle(UUID uuid) {
        if (bypassing.add(uuid)) return true;
        bypassing.remove(uuid);
        return false;
    }

    public boolean is(UUID uuid) { return bypassing.contains(uuid); }

    public void clear(UUID uuid) { bypassing.remove(uuid); }
}
