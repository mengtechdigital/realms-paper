package com.realms.data;

import org.bukkit.Material;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence interface for the Realms plugin. Read methods are O(1)
 * against the in-memory hot maps. Writes are durable — queued to the async
 * writer thread and flushed on close.
 *
 * Threading: hot-map mutations happen on the main thread. Reads are
 * thread-safe at the per-call level (ConcurrentHashMap), but composing
 * multiple reads (e.g. {@code getResident} → {@code getRealm}) without a
 * lock can observe an in-progress {@link #deleteRealm} cascade. Async
 * callers (e.g. {@code AsyncPlayerChatEvent} handlers) should always
 * null-guard each step and accept that a torn read returns null, not
 * partial state — the rest of the plugin is built around that contract.
 */
public interface RealmsStore {

    // -- Realms --------------------------------------------------------------

    /** Insert a new realm and return its persistent record (with assigned id). */
    Realm createRealm(String name, UUID founder, boolean peaceful, long foundedMillis);

    void updateRealm(Realm realm);

    /** Delete a realm and cascade its residents/claims/flags/relations/ledger. */
    void deleteRealm(long realmId);

    Realm getRealm(long realmId);

    Realm getRealmByName(String name);

    Collection<Realm> allRealms();

    // -- Residents -----------------------------------------------------------

    void upsertResident(Resident resident);

    void removeResident(UUID uuid);

    Resident getResident(UUID uuid);

    /** Members of a realm. */
    List<Resident> residentsOf(long realmId);

    int residentCount(long realmId);

    // -- Claims --------------------------------------------------------------

    /** O(1) lookup. Returns realm id, or null when chunk is wilderness. */
    Long claimOwner(ClaimKey key);

    /** Atomic batch claim: set every key to realmId. Caller pre-validates. */
    void addClaims(long realmId, Collection<ClaimKey> keys, long claimedMillis);

    void removeClaim(ClaimKey key);

    /** All claims belonging to a realm. */
    Collection<ClaimKey> claimsOf(long realmId);

    int claimCount(long realmId);

    /**
     * Move a single claim from one realm to another (overclaim). Power-ledger
     * rows for that chunk transfer with it.
     */
    void transferClaim(ClaimKey key, long fromRealmId, long toRealmId, long millis);

    // -- Power ledger --------------------------------------------------------

    /** Apply a delta to (chunk, material). Negative deltas allowed. Floors at 0. */
    void deltaPower(ClaimKey key, Material material, int delta);

    /** Sum all materials in a chunk → power score (using value table). */
    long ledgerPower(ClaimKey key, Map<Material, Long> valueTable);

    /** Aggregate ledger value across an entire realm's claims. */
    long ledgerPower(long realmId, Map<Material, Long> valueTable);

    /** Drop ledger rows for a single chunk (used on unclaim). */
    void clearLedger(ClaimKey key);

    /** Sum all materials in a chunk before transfer / unclaim, for delta math. */
    Map<Material, Integer> ledgerCounts(ClaimKey key);

    // -- Flags ---------------------------------------------------------------

    void setFlag(long realmId, String flag, boolean value);

    Map<String, Boolean> flagsOf(long realmId);

    /**
     * Direct read of a single flag — avoids the defensive-copy allocation in
     * {@link #flagsOf} on hot paths (PvP damage tick, every interact). Returns
     * {@code defaultValue} when the realm has never set the flag.
     */
    boolean getFlag(long realmId, String flag, boolean defaultValue);

    // -- Relations -----------------------------------------------------------

    /** Insert or replace a directed relation. */
    void putRelation(Relation relation);

    void removeRelation(long realmA, long realmB);

    /** All relations declared BY realmA (outbound). */
    Collection<Relation> relationsFrom(long realmA);

    /** All relations declared TOWARDS realmB (inbound). */
    Collection<Relation> relationsTo(long realmB);

    // -- Relation cooldowns --------------------------------------------------

    void putCooldown(long realmA, long realmB, RelationKind kind, long untilMillis);

    /** millis epoch when cooldown expires, or 0 if no active cooldown. */
    long getCooldown(long realmA, long realmB, RelationKind kind);

    void purgeExpiredCooldowns(long nowMillis);

    // -- Display prefs -------------------------------------------------------

    DisplayPrefs getDisplayPrefs(UUID uuid);

    void putDisplayPrefs(UUID uuid, DisplayPrefs prefs);
}
