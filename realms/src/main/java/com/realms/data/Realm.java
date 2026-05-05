package com.realms.data;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * Immutable snapshot of a realm row. Mutations produce new instances via with*.
 * cachedPower is the materialized power score (base + member bonus + ledger
 * sum) — recomputed on writes by PowerManager and persisted here for fast
 * leaderboard queries.
 *
 * For admin zones (zoneType != NORMAL), cachedPower is meaningless and
 * residents/flags/relations are not used.
 */
public record Realm(
        long id,
        String name,
        UUID founder,
        boolean peaceful,
        long foundedMillis,
        long cachedPower,
        ZoneType zoneType,
        String homeWorld,
        Double homeX,
        Double homeY,
        Double homeZ,
        Float homeYaw,
        Float homePitch
) {

    public Realm {
        if (zoneType == null) zoneType = ZoneType.NORMAL;
    }

    public boolean hasHome() {
        return homeWorld != null && homeX != null && homeY != null && homeZ != null;
    }

    public boolean isAdminZone() { return zoneType.isAdminZone(); }

    /** Resolve the home location, or null if not set or world is unloaded. */
    public Location homeLocation() {
        if (!hasHome()) return null;
        World world = Bukkit.getWorld(homeWorld);
        if (world == null) return null;
        float yaw = homeYaw == null ? 0f : homeYaw;
        float pitch = homePitch == null ? 0f : homePitch;
        return new Location(world, homeX, homeY, homeZ, yaw, pitch);
    }

    public Realm withName(String newName) {
        return new Realm(id, newName, founder, peaceful, foundedMillis, cachedPower,
                zoneType, homeWorld, homeX, homeY, homeZ, homeYaw, homePitch);
    }

    public Realm withFounder(UUID newFounder) {
        return new Realm(id, name, newFounder, peaceful, foundedMillis, cachedPower,
                zoneType, homeWorld, homeX, homeY, homeZ, homeYaw, homePitch);
    }

    public Realm withPeaceful(boolean newPeaceful) {
        return new Realm(id, name, founder, newPeaceful, foundedMillis, cachedPower,
                zoneType, homeWorld, homeX, homeY, homeZ, homeYaw, homePitch);
    }

    public Realm withCachedPower(long newPower) {
        return new Realm(id, name, founder, peaceful, foundedMillis, newPower,
                zoneType, homeWorld, homeX, homeY, homeZ, homeYaw, homePitch);
    }

    public Realm withHome(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return new Realm(id, name, founder, peaceful, foundedMillis, cachedPower,
                    zoneType, null, null, null, null, null, null);
        }
        return new Realm(id, name, founder, peaceful, foundedMillis, cachedPower,
                zoneType, loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch());
    }
}
