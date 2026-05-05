package com.realms.manager;

import com.realms.RealmsConfig;
import com.realms.data.Realm;
import com.realms.data.RealmsStore;

/**
 * Stateless power-calc helpers. Materialized as a class so {@link RealmsConfig}
 * lookups don't get scattered across managers.
 *
 *   power     = base + members*per-member-bonus + ledger
 *   capacity  = power
 *   cost      = chunks_claimed * cost-per-chunk
 *   spare     = power - cost          (positive: still has room; negative: weakened)
 */
public final class PowerCalc {

    private final RealmsConfig config;
    private final RealmsStore store;

    public PowerCalc(RealmsConfig config, RealmsStore store) {
        this.config = config;
        this.store = store;
    }

    public long basePower() { return config.basePower(); }

    public long memberPower(int residentCount) {
        return Math.max(0, residentCount) * config.perMemberPower();
    }

    public long ledgerPower(long realmId) {
        return store.ledgerPower(realmId, config.powerValues());
    }

    /** Compute the realm's full power score (base + members + ledger). */
    public long compute(Realm realm) {
        if (realm == null) return 0L;
        // Admin zones live outside the player power economy. Returning
        // Long.MAX_VALUE means claim_cost can never exceed capacity, so
        // they never appear weakened in any code path that asks.
        if (realm.isAdminZone()) return Long.MAX_VALUE;
        return config.basePower()
                + memberPower(store.residentCount(realm.id()))
                + ledgerPower(realm.id());
    }

    public long claimCost(int chunkCount) {
        return Math.max(0, chunkCount) * config.costPerChunk();
    }

    public long currentClaimCost(Realm realm) {
        return claimCost(store.claimCount(realm.id()));
    }

    /**
     * Refresh cached_power on the realm row from the live formula. Always
     * writes — the skip-if-equal optimisation risked leaving cached_power
     * at zero on createRealm if base+memberPower(1)+ledger happened to
     * equal the seeded zero (e.g., when a server tunes basePower=0).
     */
    public void recompute(Realm realm) {
        if (realm == null) return;
        // Admin zones don't have a meaningful cached_power. Skip the write
        // so we don't pollute /realm top with sentinel values.
        if (realm.isAdminZone()) return;
        long fresh = compute(realm);
        store.updateRealm(realm.withCachedPower(fresh));
    }
}
