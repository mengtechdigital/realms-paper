package com.realms.listener;

import com.realms.RealmsConfig;
import com.realms.RealmsConfig.ExplosionPolicy;
import com.realms.data.ClaimKey;
import com.realms.data.Realm;
import com.realms.data.RealmsStore;
import com.realms.manager.PowerCalc;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Explosion-vs-claim policy enforcement and the raid power drain.
 *
 * Per-source policies come from config (tnt: vanilla, creeper: cancel, ...).
 * Peaceful realms force CANCEL for ALL sources, regardless of policy. For
 * sources whose policy resolves to VANILLA in a non-peaceful claim, blocks
 * that match the power-ledger value table get a deltaPower(-1) before
 * they actually break — that's the Factions-style raid drain.
 *
 * Wilderness chunks are unaffected — the listener returns the explosion
 * intact for those.
 */
public final class ExplosionListener implements Listener {

    private final RealmsConfig config;
    private final RealmsStore store;
    private final PowerCalc power;

    public ExplosionListener(RealmsConfig config, RealmsStore store, PowerCalc power) {
        this.config = config;
        this.store = store;
        this.power = power;
    }

    /**
     * HIGH priority on purpose: a protection plugin running later at NORMAL
     * could cancel the entire explosion AFTER we've decremented the ledger,
     * producing a phantom power drain on raids that never actually break
     * blocks. Running at HIGH lets other plugins veto first; we only debit
     * power for explosions that will actually survive to the break step.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        ExplosionPolicy policy = policyForSource(event.getEntity());
        Set<Long> affectedRealms = new HashSet<>();
        applyPolicy(event.blockList().iterator(), policy, affectedRealms);
        recomputeAll(affectedRealms);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        // Block-source explosions in 1.21: bed in nether/end, respawn anchor in
        // overworld/end. Pick policy by source material.
        Material src = event.getBlock().getType();
        ExplosionPolicy policy;
        if (src == Material.RESPAWN_ANCHOR) policy = config.explosionRespawnAnchor();
        else if (Material.RED_BED.equals(src)
                || Material.BLACK_BED.equals(src) || Material.WHITE_BED.equals(src)
                || Material.YELLOW_BED.equals(src) || Material.BLUE_BED.equals(src)
                || Material.LIGHT_BLUE_BED.equals(src) || Material.LIME_BED.equals(src)
                || Material.PINK_BED.equals(src) || Material.GREEN_BED.equals(src)
                || Material.GRAY_BED.equals(src) || Material.CYAN_BED.equals(src)
                || Material.MAGENTA_BED.equals(src) || Material.ORANGE_BED.equals(src)
                || Material.PURPLE_BED.equals(src) || Material.LIGHT_GRAY_BED.equals(src)
                || Material.BROWN_BED.equals(src)) policy = config.explosionBed();
        else policy = ExplosionPolicy.VANILLA; // unknown block-source: leave alone
        Set<Long> affectedRealms = new HashSet<>();
        applyPolicy(event.blockList().iterator(), policy, affectedRealms);
        recomputeAll(affectedRealms);
    }

    /**
     * Walk the block list — drop blocks inside peaceful claims or when
     * policy=CANCEL; for blocks that survive (will actually break) and
     * are tracked materials, decrement the power ledger.
     */
    private void applyPolicy(Iterator<Block> blocks, ExplosionPolicy policy, Set<Long> affectedRealms) {
        while (blocks.hasNext()) {
            Block b = blocks.next();
            ClaimKey k = ClaimKey.of(b.getLocation());
            Long ownerId = store.claimOwner(k);
            if (ownerId == null) continue;       // wilderness — vanilla
            Realm realm = store.getRealm(ownerId);
            if (realm == null) continue;         // orphaned claim row, ignore

            boolean cancel = realm.peaceful() || policy == ExplosionPolicy.CANCEL;
            if (cancel) {
                blocks.remove();
                continue;
            }
            // Block will actually break — count it against the realm's
            // power ledger if it's a tracked material.
            Material type = b.getType();
            if (config.powerValues().containsKey(type)) {
                store.deltaPower(k, type, -1);
                affectedRealms.add(ownerId);
            }
        }
    }

    private void recomputeAll(Set<Long> realmIds) {
        for (Long id : realmIds) {
            Realm r = store.getRealm(id);
            if (r != null) power.recompute(r);
        }
    }

    private ExplosionPolicy policyForSource(Entity src) {
        if (src instanceof TNTPrimed) return config.explosionTnt();
        if (src instanceof Creeper) return config.explosionCreeper();
        if (src instanceof EnderCrystal) return config.explosionEndCrystal();
        if (src instanceof Wither || src instanceof WitherSkull) return config.explosionWither();
        // LargeFireball is specifically the ghast projectile. The general
        // Fireball superclass also matches blaze SmallFireball, dragon
        // fireballs, and wind charges — those should not be governed by
        // the ghast policy key.
        if (src instanceof LargeFireball) return config.explosionGhast();
        // Unknown source — be conservative inside claims. Going VANILLA here
        // means an unrecognised explosion still drops blocks; CANCEL would
        // silently break unrelated mechanics, which is worse.
        return ExplosionPolicy.VANILLA;
    }
}
