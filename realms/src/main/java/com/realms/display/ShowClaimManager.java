package com.realms.display;

import com.realms.RealmsConfig;
import com.realms.data.ClaimKey;
import com.realms.data.RealmsStore;
import com.realms.manager.Result;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player toggle + 1Hz particle render of nearby claim borders.
 * Particles are clientside (single-player target), so other players never
 * see another player's outline.
 *
 * Modes:
 *   line   — single particle line at player Y, one particle per block
 *            along ownership-boundary chunk edges.
 *   wall   — vertical wall from playerY-Δ to playerY+Δ (config), every
 *            sample-spacing blocks vertically.
 *   corner — vertical pillars only at chunk corners that flank a boundary.
 */
public final class ShowClaimManager extends BukkitRunnable {

    public enum Mode { LINE, WALL, CORNER, OFF }

    private final RealmsConfig config;
    private final RealmsStore store;
    private final Palette palette;

    private final Map<UUID, Mode> active = new ConcurrentHashMap<>();

    public ShowClaimManager(RealmsConfig config, RealmsStore store, Palette palette) {
        this.config = config;
        this.store = store;
        this.palette = palette;
    }

    public Result toggle(Player p, Mode requested) {
        if (!config.seeClaimsEnabled()) {
            return Result.fail("errors.showclaim-disabled");
        }
        UUID id = p.getUniqueId();
        if (requested == Mode.OFF) {
            active.remove(id);
            return Result.ok("info.showclaim-off");
        }
        int max = config.seeClaimsMaxUsers();
        if (max > 0 && !active.containsKey(id) && active.size() >= max) {
            return Result.fail("info.showclaim-busy");
        }
        active.put(id, requested);
        return Result.ok("info.showclaim-on", Map.of("mode", requested.name().toLowerCase()));
    }

    public void abort(UUID id) { active.remove(id); }

    @Override
    public void run() {
        if (active.isEmpty()) return;
        // TPS guard — pause renders if the server is suffering. Bukkit's
        // server.getTPS() returns recent TPS averages on Paper.
        double[] tps = Bukkit.getServer().getTPS();
        if (tps != null && tps.length > 0 && tps[0] < config.seeClaimsTpsThreshold()) {
            return;
        }
        for (Map.Entry<UUID, Mode> e : active.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline()) continue;
            renderFor(p, e.getValue());
        }
    }

    private void renderFor(Player viewer, Mode mode) {
        int radius = config.seeClaimsRadius();
        Location origin = viewer.getLocation();
        ClaimKey center = ClaimKey.of(origin);
        // Iterate every chunk in the area and only draw the south + east
        // edges — that covers every boundary edge in the radius without
        // double-rendering.
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ClaimKey here = center.offset(dx, dz);
                Long hereOwner = store.claimOwner(here);
                // East edge: between (here) and (here +x).
                ClaimKey east = here.offset(1, 0);
                Long eastOwner = store.claimOwner(east);
                if (!eq(hereOwner, eastOwner)) {
                    Long colored = pickOwner(hereOwner, eastOwner);
                    Color rgb = palette.rgb(palette.relationFor(viewer, colored));
                    drawEdge(viewer, mode, here.world(),
                            (here.chunkX() + 1) * 16, here.chunkZ() * 16,
                            (here.chunkX() + 1) * 16, here.chunkZ() * 16 + 16,
                            origin.getY(), rgb);
                }
                // South edge (positive Z): between (here) and (here +z).
                ClaimKey south = here.offset(0, 1);
                Long southOwner = store.claimOwner(south);
                if (!eq(hereOwner, southOwner)) {
                    Long colored = pickOwner(hereOwner, southOwner);
                    Color rgb = palette.rgb(palette.relationFor(viewer, colored));
                    drawEdge(viewer, mode, here.world(),
                            here.chunkX() * 16,      (here.chunkZ() + 1) * 16,
                            here.chunkX() * 16 + 16, (here.chunkZ() + 1) * 16,
                            origin.getY(), rgb);
                }
            }
        }
    }

    /**
     * Draw a particle line/wall/corner along the line segment from
     * (x1, z1) to (x2, z2) at the given y. Either x or z varies; the
     * other stays constant.
     */
    private void drawEdge(Player viewer, Mode mode, String world,
                          int x1, int z1, int x2, int z2, double y, Color rgb) {
        var bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) return;
        Particle.DustOptions dust = new Particle.DustOptions(rgb, 1.0f);
        int spacing = config.seeClaimsSampleSpacing();
        if (mode == Mode.CORNER) {
            spawnPillar(viewer, bukkitWorld, x1, y, z1, dust);
            spawnPillar(viewer, bukkitWorld, x2, y, z2, dust);
            return;
        }
        if (x1 == x2) {
            // edge runs along Z axis
            for (int z = Math.min(z1, z2); z < Math.max(z1, z2); z += spacing) {
                drawAt(viewer, bukkitWorld, x1, y, z, mode, dust);
            }
        } else {
            // edge runs along X axis
            for (int x = Math.min(x1, x2); x < Math.max(x1, x2); x += spacing) {
                drawAt(viewer, bukkitWorld, x, y, z1, mode, dust);
            }
        }
    }

    private void drawAt(Player viewer, org.bukkit.World w, double x, double y, double z,
                        Mode mode, Particle.DustOptions dust) {
        if (mode == Mode.LINE) {
            viewer.spawnParticle(Particle.DUST,
                    new Location(w, x, y + config.seeClaimsLineYOffset(), z),
                    1, 0, 0, 0, 0, dust);
        } else if (mode == Mode.WALL) {
            int yMin = config.seeClaimsWallYMin();
            int yMax = config.seeClaimsWallYMax();
            for (int dy = yMin; dy <= yMax; dy += 2) {
                viewer.spawnParticle(Particle.DUST,
                        new Location(w, x, y + dy, z), 1, 0, 0, 0, 0, dust);
            }
        }
    }

    private void spawnPillar(Player viewer, org.bukkit.World w, double x, double y, double z,
                             Particle.DustOptions dust) {
        int yMin = config.seeClaimsWallYMin();
        int yMax = config.seeClaimsWallYMax();
        for (int dy = yMin; dy <= yMax; dy += 2) {
            viewer.spawnParticle(Particle.DUST,
                    new Location(w, x, y + dy, z), 1, 0, 0, 0, 0, dust);
        }
    }

    /** Choose which side's owner colors the line — prefer the owned side. */
    private static Long pickOwner(Long a, Long b) {
        if (a != null) return a;
        return b;
    }

    private static boolean eq(Long a, Long b) {
        return java.util.Objects.equals(a, b);
    }
}
