package com.realms.manager;

import com.realms.RealmsConfig;
import com.realms.data.Realm;
import com.realms.data.RealmsStore;
import com.realms.data.Relation;
import com.realms.data.RelationKind;
import com.realms.data.Resident;
import com.realms.data.Role;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Diplomacy state machine: ally (bilateral), enemy (unilateral), neutral
 * (default — absence of relation rows).
 *
 * Symmetry decisions:
 *   - Ally is bilateral. Two rows are written together once both sides
 *     confirm; either side dropping it removes both.
 *   - Enemy is unilateral. {@link AllyProposalStore} is used for ally
 *     handshake; enemy declaration takes effect immediately.
 *   - {@link #areEnemies} is symmetric — if either side declared, they
 *     count as at-war for PvP / overclaim. This matches Factions UX.
 *   - {@link #areAllies} is bilateral by definition.
 */
public final class DiplomacyManager {

    private final RealmsConfig config;
    private final RealmsStore store;
    private final AllyProposalStore proposals;
    /** Set after construction to avoid the Diplomacy ↔ Overclaim circular dep. */
    private OverclaimManager overclaimRef;

    public DiplomacyManager(RealmsConfig config, RealmsStore store, AllyProposalStore proposals) {
        this.config = config;
        this.store = store;
        this.proposals = proposals;
    }

    /** Wire after both managers exist. Safe to call once during plugin enable. */
    public void setOverclaimManager(OverclaimManager overclaim) {
        this.overclaimRef = overclaim;
    }

    // ---- Public predicate API (used by ClaimAccess + ClaimManager) -------

    /** True iff there's an ALLY row in either direction (we always write both). */
    public boolean areAllies(long a, long b) {
        if (a == b) return false;
        Relation r = relationOf(a, b);
        return r != null && r.kind() == RelationKind.ALLY;
    }

    /** True iff EITHER side has declared ENEMY. Symmetric for raid purposes. */
    public boolean areEnemies(long a, long b) {
        if (a == b) return false;
        Relation ab = relationOf(a, b);
        if (ab != null && ab.kind() == RelationKind.ENEMY) return true;
        Relation ba = relationOf(b, a);
        return ba != null && ba.kind() == RelationKind.ENEMY;
    }

    private Relation relationOf(long a, long b) {
        for (Relation r : store.relationsFrom(a)) {
            if (r.realmB() == b) return r;
        }
        return null;
    }

    // ---- Ally flow --------------------------------------------------------

    public Result requestAlly(Player actor, String targetName) {
        Resident me = actor(actor);
        if (me == null) return Result.fail("errors.not-in-realm");
        if (!me.role().canManage()) return Result.fail("errors.not-mayor-or-assistant");
        Realm target = store.getRealmByName(targetName);
        if (target == null) return Result.fail("errors.realm-not-found", Map.of("realm", targetName));
        if (target.id() == me.realmId()) return Result.fail("errors.cannot-target-self");

        long my = me.realmId();
        // Already allied?
        if (areAllies(my, target.id())) {
            return Result.fail("errors.already-allies", Map.of("realm", target.name()));
        }
        // Mutual? Other side already proposed → finalize alliance.
        if (proposals.isMutualWith(target.id(), my)) {
            long now = Instant.now().toEpochMilli();
            // Drop any leftover ENEMY rows so ally/enemy can't both exist.
            store.removeRelation(my, target.id());
            store.removeRelation(target.id(), my);
            store.putRelation(new Relation(my, target.id(), RelationKind.ALLY, now));
            store.putRelation(new Relation(target.id(), my, RelationKind.ALLY, now));
            proposals.clear(target.id());
            proposals.clear(my);
            return Result.ok("info.ally-confirmed", Map.of("realm", target.name()));
        }
        // First-mover — record proposal, notify target side.
        proposals.put(my, target.id());
        return Result.ok("info.ally-requested", Map.of("realm", target.name()));
    }

    public Result declareEnemy(Player actor, String targetName) {
        if (!config.raidEnabled()) return Result.fail("errors.raid-disabled");
        Resident me = actor(actor);
        if (me == null) return Result.fail("errors.not-in-realm");
        if (!me.role().canManage()) return Result.fail("errors.not-mayor-or-assistant");
        Realm target = store.getRealmByName(targetName);
        if (target == null) return Result.fail("errors.realm-not-found", Map.of("realm", targetName));
        if (target.id() == me.realmId()) return Result.fail("errors.cannot-target-self");

        long my = me.realmId();
        long now = Instant.now().toEpochMilli();
        // Cooldown — declaring → neutral → re-declaring spam.
        long cooldownUntil = store.getCooldown(my, target.id(), RelationKind.ENEMY);
        if (cooldownUntil > now) {
            long secs = (cooldownUntil - now) / 1000L;
            return Result.fail("errors.enemy-cooldown", Map.of("n", String.valueOf(secs)));
        }
        // Replace any existing relation (ally → enemy is a hostile breakup;
        // neutral → enemy is fresh war). Drop any pending ally proposal too.
        proposals.clear(my);
        // If we were allied, removing the relation in BOTH directions cleans
        // up the bilateral pair before we write the new enemy row.
        Relation existing = relationOf(my, target.id());
        if (existing != null && existing.kind() == RelationKind.ALLY) {
            store.removeRelation(my, target.id());
            store.removeRelation(target.id(), my);
        }
        store.putRelation(new Relation(my, target.id(), RelationKind.ENEMY, now));
        return Result.ok("info.enemy-declared", Map.of("realm", target.name()));
    }

    public Result setNeutral(Player actor, String targetName) {
        Resident me = actor(actor);
        if (me == null) return Result.fail("errors.not-in-realm");
        if (!me.role().canManage()) return Result.fail("errors.not-mayor-or-assistant");
        Realm target = store.getRealmByName(targetName);
        if (target == null) return Result.fail("errors.realm-not-found", Map.of("realm", targetName));

        long my = me.realmId();
        Relation existing = relationOf(my, target.id());
        Relation reverse  = relationOf(target.id(), my);
        if (existing == null && reverse == null) {
            return Result.fail("errors.already-neutral", Map.of("realm", target.name()));
        }
        if (existing != null && existing.kind() == RelationKind.ENEMY) {
            // Unilateral pull-out from war. Arm the redeclare cooldown so
            // the actor can't immediately swing back to enemy.
            store.removeRelation(my, target.id());
            long until = Instant.now().toEpochMilli()
                    + config.enemyRedeclareCooldownSeconds() * 1000L;
            store.putCooldown(my, target.id(), RelationKind.ENEMY, until);
            // Abort any overclaim attempts in flight between these realms.
            // Without this, a mayor calling /neutral mid-raid leaves the
            // attempt in limbo for up to one tick before the periodic
            // re-validation drops it — and gives no signal to the player.
            if (overclaimRef != null) overclaimRef.abortForRealmPair(my, target.id());
        }
        if (existing != null && existing.kind() == RelationKind.ALLY) {
            // Alliance dissolution is unilateral by convention; remove BOTH
            // rows so the other side stops believing they're allied too.
            store.removeRelation(my, target.id());
            store.removeRelation(target.id(), my);
        }
        // If they had declared on us (and we hadn't on them), our /neutral
        // doesn't unilaterally erase their declaration — only we control
        // our own outbound rows.
        return Result.ok("info.neutral-set", Map.of("realm", target.name()));
    }

    // ---- Listings ---------------------------------------------------------

    /** Enemies = realms either we declared on, or who declared on us. */
    public List<Realm> enemies(long realmId) {
        Set<Long> ids = new HashSet<>();
        for (Relation r : store.relationsFrom(realmId)) {
            if (r.kind() == RelationKind.ENEMY) ids.add(r.realmB());
        }
        for (Relation r : store.relationsTo(realmId)) {
            if (r.kind() == RelationKind.ENEMY) ids.add(r.realmA());
        }
        List<Realm> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Realm rl = store.getRealm(id);
            if (rl != null) out.add(rl);
        }
        return out;
    }

    /** Allies = realms with ALLY rows in BOTH directions (bilateral). */
    public List<Realm> allies(long realmId) {
        List<Realm> out = new ArrayList<>();
        Collection<Relation> outbound = store.relationsFrom(realmId);
        for (Relation r : outbound) {
            if (r.kind() != RelationKind.ALLY) continue;
            // Confirm bilateral: the other side has a matching ALLY row to us.
            Relation back = relationOf(r.realmB(), realmId);
            if (back == null || back.kind() != RelationKind.ALLY) continue;
            Realm rl = store.getRealm(r.realmB());
            if (rl != null) out.add(rl);
        }
        return out;
    }

    private Resident actor(Player p) {
        return store.getResident(p.getUniqueId());
    }
}
