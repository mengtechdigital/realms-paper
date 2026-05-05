package com.realms.manager;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pending ally proposals — keyed by (proposer-realm-id, target-realm-id).
 * If the target later runs /realm ally proposer, the alliance is
 * consummated immediately. Proposals expire after a fixed TTL.
 *
 * In-memory only; restarts wipe pending proposals (consistent with
 * marriage-paper's ProposalStore).
 */
public final class AllyProposalStore {

    private static final long PROPOSAL_TTL_SECONDS = 120;

    private final Map<Long, Entry> byProposer = new ConcurrentHashMap<>();

    public record Entry(long proposerRealmId, long targetRealmId, long expiresAtMillis) {
        public boolean expired(long nowMillis) { return nowMillis >= expiresAtMillis; }
    }

    public void put(long proposerRealmId, long targetRealmId) {
        byProposer.put(proposerRealmId, new Entry(proposerRealmId, targetRealmId,
                Instant.now().toEpochMilli() + PROPOSAL_TTL_SECONDS * 1000L));
    }

    /** True iff `targetRealmId` previously proposed to `myRealmId`. */
    public boolean isMutualWith(long targetRealmId, long myRealmId) {
        Entry e = byProposer.get(targetRealmId);
        if (e == null) return false;
        if (e.expired(Instant.now().toEpochMilli())) {
            byProposer.remove(targetRealmId, e);
            return false;
        }
        return e.targetRealmId() == myRealmId;
    }

    public void clear(long proposerRealmId) { byProposer.remove(proposerRealmId); }

    public void clearForRealm(long realmId) {
        byProposer.entrySet().removeIf(e ->
                e.getValue().proposerRealmId() == realmId
                || e.getValue().targetRealmId() == realmId);
    }

    public void purgeExpired() {
        long now = Instant.now().toEpochMilli();
        byProposer.entrySet().removeIf(e -> e.getValue().expired(now));
    }
}
