package com.realms.data;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Immutable snapshot of an additional realm home (beyond the default home
 * stored on the {@link Realm} row). The {@code name} is the canonical lookup
 * key — always lowercase, validated by the manager layer before persistence.
 *
 * Resolves to {@code null} if the world is unloaded — callers should treat
 * that the same as a missing home rather than NPE.
 */
public record NamedHome(
        String name,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {

    public Location location() {
        World w = Bukkit.getWorld(world);
        if (w == null) return null;
        return new Location(w, x, y, z, yaw, pitch);
    }

    public static NamedHome from(String name, Location loc) {
        World w = loc.getWorld();
        return new NamedHome(name,
                w == null ? "" : w.getName(),
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch());
    }
}
