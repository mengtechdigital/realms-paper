package com.realms.manager;

import com.realms.RealmsConfig;
import com.realms.data.ClaimKey;
import com.realms.data.RealmsStore;
import com.realms.data.Resident;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Access-control queries against claims. Pure logic — no side effects.
 * Listeners ask "can this player do X here?" and get yes/no.
 *
 * Owner relations beyond same-realm/different-realm (ally / enemy) are
 * resolved by {@link com.realms.manager.DiplomacyManager} in phase 6;
 * for now, "different realm" is treated as outsider.
 */
public final class ClaimAccess {

    private final RealmsConfig config;
    private final RealmsStore store;
    private final AdminBypass bypass;

    public ClaimAccess(RealmsConfig config, RealmsStore store, AdminBypass bypass) {
        this.config = config;
        this.store = store;
        this.bypass = bypass;
    }

    /**
     * True iff {@code player} is allowed to modify blocks (build/break) at
     * the given location. Wilderness → always allowed.
     */
    public boolean canBuild(Player player, Location at) {
        if (player == null || at == null || at.getWorld() == null) return true;
        if (bypass.is(player.getUniqueId())) return true;
        Long ownerId = store.claimOwner(ClaimKey.of(at));
        if (ownerId == null) return true; // wilderness
        return isMemberOf(player.getUniqueId(), ownerId);
    }

    /**
     * True iff {@code player} can interact with a protected block (open
     * containers, doors, switches, beds, workstations) at this location.
     * Phase 4 treats containers/doors/etc. uniformly; phase 6 will allow
     * allies through doors but not containers.
     */
    public boolean canInteract(Player player, Location at) {
        // Same authority as build for phase 4 — both routed through realm
        // membership. The two stay separate methods so phase 6 can split
        // them (allies get door/button access but not chest access).
        return canBuild(player, at);
    }

    /**
     * True iff PvP damage between attacker and victim should resolve
     * normally; false means the listener should cancel it. Wilderness is
     * always vanilla. Same-realm members are gated on the realm's "pvp"
     * flag; peaceful realms force PvP off.
     *
     * Phase 6 layers on:
     *   - allies → always blocked
     *   - enemies → always allowed (overrides peaceful caveat: see design)
     */
    public boolean canPvp(Player attacker, Player victim) {
        if (attacker == null || victim == null) return true;
        if (attacker.equals(victim)) return true;
        Location at = victim.getLocation();
        Long ownerId = store.claimOwner(ClaimKey.of(at));
        if (ownerId == null) return true; // wilderness PvP per server defaults
        var realm = store.getRealm(ownerId);
        if (realm == null) return true;
        if (realm.peaceful()) return false;
        // Hot path — single-key direct read, no defensive copy.
        if (!store.getFlag(ownerId, "pvp", true)) return false;
        return true;
    }

    private boolean isMemberOf(UUID player, long realmId) {
        Resident r = store.getResident(player);
        return r != null && r.realmId() == realmId;
    }
}
