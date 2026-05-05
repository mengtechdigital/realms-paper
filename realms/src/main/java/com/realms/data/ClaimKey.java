package com.realms.data;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;

/**
 * (world-name, chunkX, chunkZ) tuple used as the primary key for claims and
 * power-ledger rows. World names are stable across saves (UUIDs are not).
 */
public record ClaimKey(String world, int chunkX, int chunkZ) {

    public ClaimKey {
        Objects.requireNonNull(world, "world");
    }

    public static ClaimKey of(Chunk chunk) {
        return new ClaimKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
    }

    public static ClaimKey of(Location loc) {
        World w = loc.getWorld();
        if (w == null) throw new IllegalArgumentException("location has no world");
        return new ClaimKey(w.getName(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    public ClaimKey offset(int dx, int dz) {
        return new ClaimKey(world, chunkX + dx, chunkZ + dz);
    }
}
