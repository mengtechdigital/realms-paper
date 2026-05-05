package com.realms.listener;

import com.realms.RealmsConfig;
import com.realms.data.ClaimKey;
import com.realms.data.Realm;
import com.realms.data.RealmsStore;
import com.realms.manager.PowerCalc;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Maintains the power-ledger when valuable blocks are placed or broken
 * inside a claim. Only player-driven place/break events count — naturally
 * generated terrain (ore in stone) doesn't fire BlockPlaceEvent and so
 * never enters the ledger.
 *
 * Listens at MONITOR priority and ignores cancelled events: the
 * ProtectionListener at LOW priority cancels outsider actions before we
 * see them, so by the time we run, the action is committed and any
 * outsider modifications are already filtered out.
 *
 * Explosion-driven block loss is handled by ExplosionListener in phase 7,
 * which decrements the same ledger before the blocks actually break.
 */
public final class PowerLedgerListener implements Listener {

    private final RealmsConfig config;
    private final RealmsStore store;
    private final PowerCalc power;

    public PowerLedgerListener(RealmsConfig config, RealmsStore store, PowerCalc power) {
        this.config = config;
        this.store = store;
        this.power = power;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block b = event.getBlockPlaced();
        Material type = b.getType();
        if (!isTracked(type)) return;
        ClaimKey key = ClaimKey.of(b.getLocation());
        Long realmId = store.claimOwner(key);
        if (realmId == null) return;     // wilderness — no ledger
        // Admin zones don't track power; players can't normally build there
        // anyway, but ops with bypass might. Skip the ledger so we don't
        // accumulate phantom power on a synthetic realm.
        Realm realm = store.getRealm(realmId);
        if (realm != null && realm.isAdminZone()) return;
        store.deltaPower(key, type, +1);
        recompute(realmId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block b = event.getBlock();
        Material type = b.getType();
        if (!isTracked(type)) return;
        ClaimKey key = ClaimKey.of(b.getLocation());
        Long realmId = store.claimOwner(key);
        if (realmId == null) return;
        Realm realm = store.getRealm(realmId);
        if (realm != null && realm.isAdminZone()) return;
        store.deltaPower(key, type, -1);
        recompute(realmId);
    }

    private boolean isTracked(Material type) {
        return type != null && config.powerValues().containsKey(type);
    }

    private void recompute(long realmId) {
        Realm realm = store.getRealm(realmId);
        if (realm != null) power.recompute(realm);
    }
}
