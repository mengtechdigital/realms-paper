package com.realms.manager;

import com.realms.RealmsConfig;
import com.realms.data.ClaimKey;
import com.realms.data.Realm;
import com.realms.data.RealmsStore;
import com.realms.data.Resident;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Overclaim flow:
 *
 *   1. Aggressor stands inside an enemy chunk and runs /realm overclaim.
 *   2. We validate: in a realm, raid enabled, target is enemy, target not
 *      peaceful, chunk is weakened (claim_cost > capacity).
 *   3. We arm an OverclaimAttempt for the player. A 1Hz task tracks
 *      whether they're still in the same chunk; if they leave, the
 *      attempt is dropped. When elapsed ≥ grace-seconds, the chunk
 *      transfers to the aggressor's realm and both realms are recomputed.
 *   4. Success message goes out to both sides; broadcast goes to the
 *      whole server through {@link Broadcast}.
 *
 * Field naming: an attempt is the aggressor stealing FROM the victim.
 * {@code victimRealmId} owns the chunk being targeted; {@code aggressorRealmId}
 * is the player's realm. Don't conflate the two — naming was deliberately
 * verbose to avoid the trap.
 */
public final class OverclaimManager {

    /**
     * Structured broadcast callback. Avoids the fragile string-encoded
     * "key:val::val::val" protocol that breaks if a realm name ever
     * contains a colon (the name regex currently forbids it, but a future
     * relaxation would silently corrupt broadcasts).
     */
    public interface Broadcast {
        void overclaimed(String aggressorRealmName, String victimRealmName, int lootedSlots);
    }

    public record Attempt(
            UUID player,
            ClaimKey chunk,
            long victimRealmId,
            long aggressorRealmId,
            long startedMillis,
            long lastProgressMillis
    ) {}

    private final RealmsConfig config;
    private final RealmsStore store;
    private final PowerCalc power;
    private final DiplomacyManager diplomacy;
    private final Broadcast broadcast;

    private final Map<UUID, Attempt> attempts = new ConcurrentHashMap<>();

    public OverclaimManager(RealmsConfig config, RealmsStore store, PowerCalc power,
                            DiplomacyManager diplomacy, Broadcast broadcast) {
        this.config = config;
        this.store = store;
        this.power = power;
        this.diplomacy = diplomacy;
        this.broadcast = broadcast;
    }

    /** Start (or restart) an overclaim attempt for the player. */
    public Result start(Player player) {
        if (!config.raidEnabled()) return Result.fail("errors.raid-disabled");
        Resident me = store.getResident(player.getUniqueId());
        if (me == null) return Result.fail("errors.not-in-realm");
        Realm myRealm = store.getRealm(me.realmId());
        if (myRealm == null) return Result.fail("errors.not-in-realm");

        ClaimKey here = ClaimKey.of(player.getLocation());
        Long ownerId = store.claimOwner(here);
        // Long == long auto-boxes — identity comparison only works for cached
        // ids (-128..127). Use longValue so ids above 127 also resolve.
        if (ownerId == null || ownerId.longValue() == myRealm.id()) {
            return Result.fail("errors.chunk-not-overclaimable");
        }
        Realm victim = store.getRealm(ownerId);
        if (victim == null) return Result.fail("errors.chunk-not-overclaimable");
        if (victim.peaceful()) return Result.fail("errors.target-peaceful");
        if (!diplomacy.areEnemies(myRealm.id(), victim.id())) {
            return Result.fail("errors.not-enemy");
        }
        if (!isWeakened(victim)) return Result.fail("errors.not-weakened");

        long now = Instant.now().toEpochMilli();
        attempts.put(player.getUniqueId(),
                new Attempt(player.getUniqueId(), here, victim.id(), myRealm.id(), now, now));
        long grace = config.weakenedGraceSeconds();
        return Result.ok("info.overclaim-progress", Map.of("n", String.valueOf(grace)));
    }

    /**
     * Periodic tick — call once per second from a Bukkit task. Validates
     * each attempt's prerequisites, completes it if grace elapsed, drops
     * it on any inconsistency.
     */
    public void tick() {
        if (attempts.isEmpty()) return;
        long now = Instant.now().toEpochMilli();
        long graceMs = config.weakenedGraceSeconds() * 1000L;
        for (Entry<UUID, Attempt> entry : new HashMap<>(attempts).entrySet()) {
            UUID id = entry.getKey();
            Attempt a = entry.getValue();
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) { attempts.remove(id); continue; }
            ClaimKey current = ClaimKey.of(p.getLocation());
            if (!current.equals(a.chunk())) {
                attempts.remove(id);
                p.sendMessage(Text.colorize("&cOverclaim aborted — you left the target chunk."));
                continue;
            }
            // Re-validate target each tick — diplomacy or peacefulness can
            // flip while standing.
            Realm victim = store.getRealm(a.victimRealmId());
            Realm aggressor = store.getRealm(a.aggressorRealmId());
            if (victim == null || aggressor == null
                    || victim.peaceful()
                    || !diplomacy.areEnemies(aggressor.id(), victim.id())
                    || !isWeakened(victim)
                    || !equalsLong(store.claimOwner(current), victim.id())) {
                attempts.remove(id);
                p.sendMessage(Text.colorize("&cOverclaim aborted — target is no longer eligible."));
                continue;
            }
            long elapsed = now - a.startedMillis();
            if (elapsed >= graceMs) {
                completeOverclaim(p, a, current, victim, aggressor, now);
                continue;
            }
            // Progress ping every 30s so the player knows it's working.
            if (now - a.lastProgressMillis() >= 30_000L) {
                long remainSecs = (graceMs - elapsed) / 1000L;
                p.sendMessage(Text.colorize(
                        "&eOverclaiming &6" + victim.name() + "&e... &c"
                                + remainSecs + "s &eremaining."));
                attempts.put(id, new Attempt(a.player(), a.chunk(), a.victimRealmId(),
                        a.aggressorRealmId(), a.startedMillis(), now));
            }
        }
    }

    private void completeOverclaim(Player p, Attempt a, ClaimKey current,
                                   Realm victim, Realm aggressor, long now) {
        // Loot count for the broadcast — captured before mutation.
        int wipedSlots = store.ledgerCounts(current).size();

        // Home-orphan fix: if the victim's home was inside this chunk, clear
        // it before the chunk changes hands. Otherwise /realm home would
        // teleport victim members straight into aggressor territory — a real
        // griefing vector. Math.floor matches Bukkit's getBlockX semantics
        // (negative coordinates would otherwise mis-shift).
        if (victim.hasHome()
                && victim.homeWorld() != null
                && victim.homeWorld().equals(current.world())
                && (((int) Math.floor(victim.homeX())) >> 4) == current.chunkX()
                && (((int) Math.floor(victim.homeZ())) >> 4) == current.chunkZ()) {
            store.updateRealm(victim.withHome(null));
            // Notify victim members who happen to be online — they can't see
            // the broadcast distinct from the aggressor's.
            for (Resident r : store.residentsOf(victim.id())) {
                Player vp = Bukkit.getPlayer(r.uuid());
                if (vp != null) {
                    vp.sendMessage(Text.colorize(
                            "&cYour realm home was overclaimed and has been cleared. "
                                    + "Use &6/realm sethome&c to set a new one."));
                }
            }
        }

        store.transferClaim(current, a.victimRealmId(), a.aggressorRealmId(), now);
        // Re-fetch — withHome update may have replaced the in-memory snapshot.
        Realm victimFresh = store.getRealm(a.victimRealmId());
        Realm aggressorFresh = store.getRealm(a.aggressorRealmId());
        if (victimFresh != null) power.recompute(victimFresh);
        if (aggressorFresh != null) power.recompute(aggressorFresh);
        attempts.remove(a.player());
        p.sendMessage(Text.colorize("&aChunk overclaimed from &6" + victim.name() + "&a."));
        broadcast.overclaimed(aggressor.name(), victim.name(), wipedSlots);
    }

    /**
     * Cancel any in-flight attempts that involve either of the two realms.
     * Called from {@link DiplomacyManager} when a relation drops, so a
     * mayor can't rug-pull their own attacking team mid-raid (or, in
     * reverse, abort an attempt that has become irrelevant after a peace).
     */
    public int abortForRealmPair(long realmA, long realmB) {
        int aborted = 0;
        for (Entry<UUID, Attempt> e : new HashMap<>(attempts).entrySet()) {
            Attempt a = e.getValue();
            boolean involved = (a.victimRealmId() == realmA && a.aggressorRealmId() == realmB)
                    || (a.victimRealmId() == realmB && a.aggressorRealmId() == realmA);
            if (involved) {
                attempts.remove(e.getKey());
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null) {
                    p.sendMessage(Text.colorize(
                            "&cOverclaim aborted — relations changed."));
                }
                aborted++;
            }
        }
        return aborted;
    }

    /** Drop any attempt for this player (used on quit). */
    public void abort(UUID player) { attempts.remove(player); }

    public boolean isAttempting(UUID player) { return attempts.containsKey(player); }

    private boolean isWeakened(Realm realm) {
        long capacity = power.compute(realm);
        long cost = power.currentClaimCost(realm);
        return cost > capacity;
    }

    private static boolean equalsLong(Long boxed, long primitive) {
        return boxed != null && boxed.longValue() == primitive;
    }
}
