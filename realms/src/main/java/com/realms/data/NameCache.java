package com.realms.data;

import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bi-directional last-known names for offline players, populated on join.
 * Both directions are maintained so callers can resolve UUID ↔ name without
 * blocking on Bukkit.getOfflinePlayer (which can hit usercache.json on the
 * main thread).
 *
 * Name keys are lowercased for case-insensitive lookup. Display names go
 * through {@link #get(UUID)} which preserves the original case.
 */
public final class NameCache {

    private final Map<UUID, String> nameByUuid = new ConcurrentHashMap<>();
    private final Map<String, UUID> uuidByLowerName = new ConcurrentHashMap<>();

    public void remember(Player player) {
        remember(player.getUniqueId(), player.getName());
    }

    public void remember(UUID uuid, String name) {
        if (uuid == null || name == null) return;
        String prevName = nameByUuid.put(uuid, name);
        // If this player previously had a different name, drop the old reverse
        // entry so a stale name → UUID can't outlive the rename.
        if (prevName != null && !prevName.equalsIgnoreCase(name)) {
            uuidByLowerName.remove(prevName.toLowerCase(Locale.ROOT), uuid);
        }
        uuidByLowerName.put(name.toLowerCase(Locale.ROOT), uuid);
    }

    public String get(UUID uuid) {
        return uuid == null ? null : nameByUuid.get(uuid);
    }

    public String getOr(UUID uuid, String fallback) {
        String name = get(uuid);
        return name == null ? fallback : name;
    }

    /** Resolve a name (case-insensitive) to UUID, or null if unknown. */
    public UUID uuidFor(String name) {
        return name == null ? null : uuidByLowerName.get(name.toLowerCase(Locale.ROOT));
    }
}
