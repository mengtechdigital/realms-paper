package com.realms.manager;

import com.realms.RealmsConfig;
import com.realms.data.ClaimKey;
import com.realms.data.Realm;
import com.realms.data.RealmsStore;
import com.realms.data.Resident;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Land claim engine. /realm claim N centers an N×N square on the player's
 * chunk. The whole area is validated atomically — if any cell fails, no
 * mutation occurs.
 *
 * Validation pipeline:
 *   1. Player must be a managing member (mayor / assistant).
 *   2. N must be odd and ≤ max-claim-diameter (config).
 *   3. Every chunk in the area must be wilderness.
 *   4. Total cost must fit in (capacity - currentCost).
 *   5. If realm has any claims, at least one chunk must touch existing land.
 *   6. If N ≥ confirm-from-diameter, a confirm token must be armed.
 */
public final class ClaimManager {

    private final RealmsConfig config;
    private final RealmsStore store;
    private final ConfirmStore confirms;
    private final PowerCalc power;

    public ClaimManager(RealmsConfig config, RealmsStore store,
                        ConfirmStore confirms, PowerCalc power) {
        this.config = config;
        this.store = store;
        this.confirms = confirms;
        this.power = power;
    }

    public Result claim(Player player, int diameter, boolean confirm) {
        Resident me = store.getResident(player.getUniqueId());
        if (me == null) return Result.fail("errors.not-in-realm");
        if (!me.role().canManage()) return Result.fail("errors.not-mayor-or-assistant");
        Realm realm = store.getRealm(me.realmId());
        if (realm == null) return Result.fail("errors.not-in-realm");

        if (diameter < 1) diameter = 1;
        if (diameter % 2 == 0) return Result.fail("errors.diameter-not-odd");
        int max = config.maxClaimDiameter();
        if (diameter > max) {
            return Result.fail("errors.diameter-too-large", Map.of("n", String.valueOf(max)));
        }

        ClaimKey center = ClaimKey.of(player.getLocation());
        int r = (diameter - 1) / 2;
        List<ClaimKey> area = new ArrayList<>(diameter * diameter);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                area.add(center.offset(dx, dz));
            }
        }

        // Wilderness check — bail on first hit and name the conflicting realm.
        for (ClaimKey k : area) {
            Long owner = store.claimOwner(k);
            if (owner != null) {
                Realm other = store.getRealm(owner);
                return Result.fail("errors.not-wilderness",
                        Map.of("realm", other == null ? "?" : other.name(),
                                "x", String.valueOf(k.chunkX()), "z", String.valueOf(k.chunkZ())));
            }
        }

        long cost = power.claimCost(area.size());
        long currentCost = power.currentClaimCost(realm);
        long capacity = power.compute(realm);
        long spare = capacity - currentCost;
        if (cost > spare) {
            return Result.fail("errors.insufficient-power", Map.of(
                    "power", String.valueOf(spare),
                    "n", String.valueOf(cost)
            ));
        }

        // Adjacency: skip when realm has no claims yet (only happens via
        // post-disband regrowth or admin shenanigans — /realm create stakes
        // its own first chunk so the typical first-claim case has 1 claim
        // already).
        Set<ClaimKey> existing = new java.util.HashSet<>(store.claimsOf(realm.id()));
        if (!existing.isEmpty() && !touchesAny(area, existing)) {
            return Result.fail("errors.not-adjacent");
        }

        // Confirmation gate for big batches. Token is keyed by (diameter,
        // world, chunkX, chunkZ) so walking to a different chunk and
        // confirming there cannot accidentally claim the wrong area.
        String confirmKey = "claim:" + diameter + ":" + center.world() + ":"
                + center.chunkX() + ":" + center.chunkZ();
        if (diameter >= config.confirmFromDiameter()) {
            if (!confirm) {
                confirms.arm(player.getUniqueId(), confirmKey);
                return Result.fail("errors.confirm-required", Map.of(
                        "n", String.valueOf(area.size()),
                        "cost", String.valueOf(cost),
                        "diameter", String.valueOf(diameter)
                ));
            }
            if (!confirms.consume(player.getUniqueId(), confirmKey)) {
                return Result.fail("errors.confirm-expired");
            }
        }

        long now = Instant.now().toEpochMilli();
        store.addClaims(realm.id(), area, now);
        power.recompute(realm);

        return Result.ok("info.claim-success", Map.of(
                "n", String.valueOf(area.size()),
                "power", String.valueOf(currentCost + cost),
                "capacity", String.valueOf(capacity)
        ));
    }

    public Result unclaim(Player player) {
        Resident me = store.getResident(player.getUniqueId());
        if (me == null) return Result.fail("errors.not-in-realm");
        if (!me.role().canManage()) return Result.fail("errors.not-mayor-or-assistant");
        Realm realm = store.getRealm(me.realmId());
        if (realm == null) return Result.fail("errors.not-in-realm");

        ClaimKey here = ClaimKey.of(player.getLocation());
        Long owner = store.claimOwner(here);
        if (owner == null) return Result.fail("errors.chunk-not-yours");
        // Explicit unboxed comparison — no NPE risk if the null-guard above
        // is ever reordered, and clearer than relying on auto-unboxing of
        // a Long against a primitive.
        if (owner.longValue() != realm.id()) return Result.fail("errors.chunk-not-yours");

        // If this chunk hosts the realm's home, clear it — surprise-clearing
        // is better than leaving an orphaned home location pointing into
        // wilderness (and silently failing /realm home). Use Math.floor
        // (matches Bukkit's getBlockX) so negative coordinates land in the
        // correct chunk: -0.5 → block -1 → chunk -1, NOT chunk 0.
        boolean clearedHome = false;
        if (realm.hasHome()
                && realm.homeWorld().equals(here.world())
                && (((int) Math.floor(realm.homeX())) >> 4) == here.chunkX()
                && (((int) Math.floor(realm.homeZ())) >> 4) == here.chunkZ()) {
            store.updateRealm(realm.withHome(null));
            clearedHome = true;
        }

        // Deduct power-ledger value before drop — cached_power will recompute.
        Map<Material, Integer> wiped = store.ledgerCounts(here);
        store.removeClaim(here); // clears claim row + ledger rows for this chunk

        Realm refreshed = store.getRealm(realm.id());
        if (refreshed != null) power.recompute(refreshed);

        Map<String, String> ph = new java.util.HashMap<>();
        ph.put("x", String.valueOf(here.chunkX()));
        ph.put("z", String.valueOf(here.chunkZ()));
        ph.put("wiped", String.valueOf(wiped.size()));
        ph.put("home-cleared", clearedHome ? "1" : "0");
        return Result.ok("info.unclaim-success", ph);
    }

    /** True iff any chunk in `area` is 4-neighbor adjacent to any chunk in `existing`. */
    private static boolean touchesAny(List<ClaimKey> area, Set<ClaimKey> existing) {
        for (ClaimKey k : area) {
            if (existing.contains(k.offset(1, 0))) return true;
            if (existing.contains(k.offset(-1, 0))) return true;
            if (existing.contains(k.offset(0, 1))) return true;
            if (existing.contains(k.offset(0, -1))) return true;
        }
        return false;
    }
}
