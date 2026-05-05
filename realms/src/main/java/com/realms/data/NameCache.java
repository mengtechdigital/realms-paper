package com.realms.data;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last-known names for offline players, populated on join. Used by chat /
 * tab / message templates to render references to offline residents without
 * blocking on Bukkit.getOfflinePlayer (which can hit the user-cache file).
 */
public final class NameCache {

    private final Map<UUID, String> nameByUuid = new ConcurrentHashMap<>();

    public void remember(Player player) {
        nameByUuid.put(player.getUniqueId(), player.getName());
    }

    public void remember(UUID uuid, String name) {
        if (uuid == null || name == null) return;
        nameByUuid.put(uuid, name);
    }

    public String get(UUID uuid) {
        if (uuid == null) return null;
        return nameByUuid.get(uuid);
    }

    public String getOr(UUID uuid, String fallback) {
        String name = get(uuid);
        return name == null ? fallback : name;
    }
}
