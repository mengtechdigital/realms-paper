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
 *   canBuild       — only members of the chunk's owning realm.
 *   canUseAsAlly   — members OR allies (door / button / lever / plate / bed).
 *   canOpenContainer — members; also allies when {@code ally-interact} is on.
 *   canPvp         — wilderness vanilla; otherwise:
 *                      peaceful-claim chunk: always blocked (everyone is
 *                          immune inside that realm's land — peaceful is
 *                          a sanctuary, not a personal aura);
 *                      admin zone chunk: forced on/off by zone type;
 *                      same realm: pvp-flag drives;
 *                      ally relation: always blocked (peace);
 *                      enemy relation: always allowed;
 *                      neutral: chunk owner's pvp flag.
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
     * Container / workstation interaction. Members always allowed; allies
     * allowed when {@code ally-interact} is on (default true). Outsiders
     * always blocked.
     */
    public boolean canOpenContainer(Player player, Location at) {
        if (config.allyInteract()) return canUseAsAlly(player, at);
        return canBuild(player, at);
    }

    /** Whether allies should be treated like members for entity interactions. */
    public boolean allyInteractEnabled() { return config.allyInteract(); }

    public boolean canPvp(Player attacker, Player victim) {
        if (attacker == null || victim == null) return true;
        if (attacker.equals(victim)) return true;

        // Chunk-level overrides take precedence over relation rules.
        // Peaceful realms are now claim-scoped: everyone inside their land
        // is immune, but peaceful members fighting in arenas / wilderness /
        // contested ground take damage normally. This unblocks peaceful
        // players from PvP events while keeping their towns sanctuary.
        Long chunkOwnerId = store.claimOwner(ClaimKey.of(victim.getLocation()));
        if (chunkOwnerId != null) {
            Realm chunkRealm = store.getRealm(chunkOwnerId);
            if (chunkRealm != null) {
                if (chunkRealm.isAdminZone()) {
                    if (chunkRealm.zoneType().forcesPvpOff()) return false;
                    if (chunkRealm.zoneType().forcesPvpOn())  return true;
                }
                if (chunkRealm.peaceful()) return false;
            }
        }

        Resident attackerRes = store.getResident(attacker.getUniqueId());
        Resident victimRes   = store.getResident(victim.getUniqueId());
        Long attackerRealm = attackerRes == null ? null : attackerRes.realmId();
        Long victimRealm   = victimRes == null ? null : victimRes.realmId();

        // Same realm — pvp flag drives.
        if (attackerRealm != null && attackerRealm.equals(victimRealm)) {
            return store.getFlag(attackerRealm, "pvp", true);
        }

        // Cross-realm relations.
        if (attackerRealm != null && victimRealm != null) {
            if (diplomacy.areAllies(attackerRealm, victimRealm)) return false;
            if (diplomacy.areEnemies(attackerRealm, victimRealm)) return true;
        }

        // Neutral / one side has no realm — chunk owner's pvp flag rules.
        if (chunkOwnerId == null) return true;
        return store.getFlag(chunkOwnerId, "pvp", true);
    }

    private boolean isMemberOf(UUID player, long realmId) {
        Resident r = store.getResident(player);
        return r != null && r.realmId() == realmId;
    }
}
