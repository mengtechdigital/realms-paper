package com.realms.manager;

import com.realms.RealmsConfig;
import com.realms.data.ClaimKey;
import com.realms.data.Realm;
import com.realms.data.RealmsStore;
import com.realms.data.Resident;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Access-control queries against claims. Pure logic — no side effects.
 * Listeners ask "can this player do X here?" and get yes/no.
 *
 * Relation semantics:
 *   canBuild  — only members of the chunk's owning realm.
 *   canUseAsAlly — members OR allies (door/button/plate/bed access).
 *   canPvp    — wilderness vanilla; otherwise:
 *                 same realm: peaceful or pvp-flag-off → block;
 *                 ally relation: always blocked (peace);
 *                 enemy relation: always allowed UNLESS victim is in a
 *                                 peaceful realm (peaceful overrides);
 *                 neutral: chunk owner's pvp flag (peaceful blocks).
 */
public final class ClaimAccess {

    private final RealmsConfig config;
    private final RealmsStore store;
    private final AdminBypass bypass;
    private final DiplomacyManager diplomacy;

    public ClaimAccess(RealmsConfig config, RealmsStore store,
                       AdminBypass bypass, DiplomacyManager diplomacy) {
        this.config = config;
        this.store = store;
        this.bypass = bypass;
        this.diplomacy = diplomacy;
    }

    /** Modify-the-world authority — members only; admin zones lock everyone out. */
    public boolean canBuild(Player player, Location at) {
        if (player == null || at == null || at.getWorld() == null) return true;
        if (bypass.is(player.getUniqueId())) return true;
        Long ownerId = store.claimOwner(ClaimKey.of(at));
        if (ownerId == null) return true;
        Realm realm = store.getRealm(ownerId);
        // Admin zones (safezone / warzone) — no player can build, only ops
        // with bypass on (already short-circuited above).
        if (realm != null && realm.isAdminZone()) return false;
        return isMemberOf(player.getUniqueId(), ownerId);
    }

    /** Members and allies. Used by door/button/lever/plate/bed interactions. */
    public boolean canUseAsAlly(Player player, Location at) {
        if (player == null || at == null || at.getWorld() == null) return true;
        if (bypass.is(player.getUniqueId())) return true;
        Long ownerId = store.claimOwner(ClaimKey.of(at));
        if (ownerId == null) return true;
        Realm realm = store.getRealm(ownerId);
        if (realm != null && realm.isAdminZone()) return false;
        if (isMemberOf(player.getUniqueId(), ownerId)) return true;
        Resident me = store.getResident(player.getUniqueId());
        if (me == null) return false;
        return diplomacy.areAllies(me.realmId(), ownerId);
    }

    /**
     * Container / workstation interaction — same authority as build for now;
     * outsiders and allies are both blocked. Phase split exists so later
     * tweaks (e.g. ally chest sharing as an opt-in flag) have a hook.
     */
    public boolean canOpenContainer(Player player, Location at) {
        return canBuild(player, at);
    }

    public boolean canPvp(Player attacker, Player victim) {
        if (attacker == null || victim == null) return true;
        if (attacker.equals(victim)) return true;

        // Chunk owner takes precedence for admin zones — safezone always
        // blocks PvP, warzone always allows it, regardless of attacker /
        // victim realm membership.
        Long chunkOwnerId = store.claimOwner(ClaimKey.of(victim.getLocation()));
        if (chunkOwnerId != null) {
            Realm chunkRealm = store.getRealm(chunkOwnerId);
            if (chunkRealm != null && chunkRealm.isAdminZone()) {
                if (chunkRealm.zoneType().forcesPvpOff()) return false;
                if (chunkRealm.zoneType().forcesPvpOn())  return true;
            }
        }

        Resident attackerRes = store.getResident(attacker.getUniqueId());
        Resident victimRes   = store.getResident(victim.getUniqueId());
        Long attackerRealm = attackerRes == null ? null : attackerRes.realmId();
        Long victimRealm   = victimRes == null ? null : victimRes.realmId();

        // Peaceful-realm members are immune anywhere; that overrides enemy
        // declarations (the design's "peaceful absorbs enemy declarations"
        // rule).
        if (victimRealm != null) {
            Realm vr = store.getRealm(victimRealm);
            if (vr != null && vr.peaceful()) return false;
        }

        // Same realm — peaceful covered above; pvp flag drives.
        if (attackerRealm != null && attackerRealm.equals(victimRealm)) {
            return store.getFlag(attackerRealm, "pvp", true);
        }

        // Cross-realm relations.
        if (attackerRealm != null && victimRealm != null) {
            if (diplomacy.areAllies(attackerRealm, victimRealm)) return false;
            if (diplomacy.areEnemies(attackerRealm, victimRealm)) return true;
        }

        // Neutral / one side has no realm — chunk owner's pvp flag rules.
        Long ownerId = store.claimOwner(ClaimKey.of(victim.getLocation()));
        if (ownerId == null) return true; // wilderness
        Realm chunkRealm = store.getRealm(ownerId);
        if (chunkRealm == null) return true;
        if (chunkRealm.peaceful()) return false;
        return store.getFlag(ownerId, "pvp", true);
    }

    private boolean isMemberOf(UUID player, long realmId) {
        Resident r = store.getResident(player);
        return r != null && r.realmId() == realmId;
    }
}
