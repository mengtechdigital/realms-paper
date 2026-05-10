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
import org.bukkit.event.block.BlockFromToEvent;
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

    /**
     * Dragon eggs teleport when clicked instead of dropping as an item.
     * Vanilla fires {@link BlockFromToEvent} for the move; we mirror the
     * power ledger from the source chunk to the destination chunk so that
     * breaking the egg later actually decrements the correct ledger row.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        Block fromBlock = event.getBlock();
        if (fromBlock.getType() != Material.DRAGON_EGG) return;
        if (!isTracked(Material.DRAGON_EGG)) return;

        ClaimKey fromKey = ClaimKey.of(fromBlock.getLocation());
        ClaimKey toKey = ClaimKey.of(event.getToBlock().getLocation());
        if (fromKey.equals(toKey)) return; // same chunk — net zero, skip

        Long fromRealmId = store.claimOwner(fromKey);
        Long toRealmId = store.claimOwner(toKey);

        if (fromRealmId != null) {
            Realm fromRealm = store.getRealm(fromRealmId);
            if (fromRealm != null && !fromRealm.isAdminZone()) {
                store.deltaPower(fromKey, Material.DRAGON_EGG, -1);
                recompute(fromRealmId);
            }
        }

        if (toRealmId != null) {
            Realm toRealm = store.getRealm(toRealmId);
            if (toRealm != null && !toRealm.isAdminZone()) {
                store.deltaPower(toKey, Material.DRAGON_EGG, +1);
                if (!toRealmId.equals(fromRealmId)) {
                    recompute(toRealmId);
                }
            }
        }
    }

    private boolean isTracked(Material type) {
        return type != null && config.powerValues().containsKey(type);
    }

    private void recompute(long realmId) {
        Realm realm = store.getRealm(realmId);
        if (realm != null) power.recompute(realm);
    }
}
