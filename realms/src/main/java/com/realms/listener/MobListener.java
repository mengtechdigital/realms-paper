package com.realms.listener;

import com.realms.data.ClaimKey;
import com.realms.data.RealmsStore;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Phantom;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityBreakDoorEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;

/**
 * Per-claim mob spawning + griefing flags.
 *
 *   hostile-spawn — natural / chunk-gen hostile mobs allowed?
 *   passive-spawn — natural / chunk-gen passive mobs allowed?
 *   mob-griefing  — endermen / zombies / ravagers / silverfish modifying
 *                   blocks. Intentionally narrower than vanilla
 *                   mobGriefing gamerule — explosions are handled by
 *                   ExplosionListener, not here.
 *
 * Spawn-reason filter: only NATURAL and CHUNK_GEN are gated. Spawn eggs,
 * spawners, command summons, breeding, and player-built golems all pass
 * through — those are intentional player actions.
 */
public final class MobListener implements Listener {

    private final RealmsStore store;

    public MobListener(RealmsStore store) {
        this.store = store;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        SpawnReason reason = event.getSpawnReason();
        if (reason != SpawnReason.NATURAL && reason != SpawnReason.CHUNK_GEN) return;
        Long ownerId = store.claimOwner(ClaimKey.of(event.getLocation()));
        if (ownerId == null) return;     // wilderness — vanilla
        Entity ent = event.getEntity();
        if (isHostile(ent)) {
            if (!store.getFlag(ownerId, "hostile-spawn", true)) event.setCancelled(true);
        } else {
            if (!store.getFlag(ownerId, "passive-spawn", true)) event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMobChangeBlock(EntityChangeBlockEvent event) {
        Entity ent = event.getEntity();
        // Players modify blocks via build events, not this. Skip villagers
        // (farming behavior) and falling blocks (gravity), let them through.
        if (!isGrieferEntity(ent)) return;
        Long ownerId = store.claimOwner(ClaimKey.of(event.getBlock().getLocation()));
        if (ownerId == null) return;
        if (!store.getFlag(ownerId, "mob-griefing", false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onZombieBreakDoor(EntityBreakDoorEvent event) {
        Long ownerId = store.claimOwner(ClaimKey.of(event.getBlock().getLocation()));
        if (ownerId == null) return;
        if (!store.getFlag(ownerId, "mob-griefing", false)) {
            event.setCancelled(true);
        }
    }

    private static boolean isHostile(Entity ent) {
        if (ent instanceof Monster) return true;     // covers most hostiles
        if (ent instanceof Slime) return true;       // includes magma cubes
        if (ent instanceof Phantom) return true;
        EntityType t = ent.getType();
        // Warden and Breeze (1.21) don't implement the Monster interface
        // in Bukkit; without explicit listing they'd silently fall under
        // passive-spawn and bypass the hostile-spawn=false guarantee.
        return t == EntityType.GHAST
                || t == EntityType.HOGLIN
                || t == EntityType.SHULKER
                || t == EntityType.WARDEN
                || t == EntityType.BREEZE;
    }

    /** Mobs that modify the world via EntityChangeBlockEvent. */
    private static boolean isGrieferEntity(Entity ent) {
        EntityType t = ent.getType();
        return t == EntityType.ENDERMAN
                || t == EntityType.SILVERFISH
                || t == EntityType.RAVAGER
                || t == EntityType.WITHER
                || t == EntityType.SHEEP    // eats grass
                || t == EntityType.RABBIT;  // eats carrots
    }
}
