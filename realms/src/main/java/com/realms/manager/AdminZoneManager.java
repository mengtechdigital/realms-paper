package com.realms.manager;

import com.realms.RealmsConfig;
import com.realms.data.ClaimKey;
import com.realms.data.Realm;
import com.realms.data.RealmsStore;
import com.realms.data.ZoneType;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Admin-only safezone / warzone management. Admin zones live in the same
 * {@code realms} table as player realms but with {@code zone_type} set to
 * SAFEZONE or WARZONE — they share the protection / display / claim
 * machinery for free.
 *
 * Admin zones differ from normal realms in that they:
 *   - have no residents (no /realm join, no roles)
 *   - have no power economy (no ledger, no overclaim)
 *   - claim chunks without adjacency requirement
 *   - block all outsider build / interact regardless of any flag
 *   - cancel all explosions inside their claims
 *   - force PvP off (SAFEZONE) or on (WARZONE) regardless of any flag
 */
public final class AdminZoneManager {

    private static final Pattern NAME_RE = Pattern.compile("[A-Za-z0-9_-]+");

    private final RealmsConfig config;
    private final RealmsStore store;
    private final ConfirmStore confirms;

    public AdminZoneManager(RealmsConfig config, RealmsStore store, ConfirmStore confirms) {
        this.config = config;
        this.store = store;
        this.confirms = confirms;
    }

    public Result create(Player op, String rawName, String typeArg) {
        String name = rawName == null ? "" : rawName.trim();
        Result nameCheck = validateName(name);
        if (!nameCheck.ok()) return nameCheck;
        if (store.getRealmByName(name) != null) {
            return Result.fail("errors.name-taken", Map.of("realm", name));
        }
        ZoneType type = ZoneType.parse(typeArg);
        if (!type.isAdminZone()) return Result.fail("errors.invalid-zone-type",
                Map.of("type", typeArg == null ? "?" : typeArg));
        long now = Instant.now().toEpochMilli();
        Realm realm = store.createRealm(name, op.getUniqueId(),
                /* peaceful */ false, type, now);
        return Result.ok("info.zone-created",
                Map.of("realm", name, "type", type.name().toLowerCase(Locale.ROOT)));
    }

    public Result claim(Player op, String zoneName, int diameter) {
        Realm zone = store.getRealmByName(zoneName);
        if (zone == null) return Result.fail("errors.realm-not-found",
                Map.of("realm", zoneName));
        if (!zone.isAdminZone()) return Result.fail("errors.not-admin-zone",
                Map.of("realm", zoneName));

        if (diameter < 1) diameter = 1;
        if (diameter % 2 == 0) return Result.fail("errors.diameter-not-odd");
        int max = config.maxClaimDiameterAdmin();
        if (diameter > max) {
            return Result.fail("errors.diameter-too-large", Map.of("n", String.valueOf(max)));
        }

        String worldName = op.getWorld().getName();
        if (!config.isClaimAllowed(worldName)) {
            return Result.fail("errors.claim-disabled-world", Map.of("world", worldName));
        }

        ClaimKey center = ClaimKey.of(op.getLocation());
        int r = (diameter - 1) / 2;

        // Walk the N×N area:
        //   - if a cell is wilderness: queue for claim
        //   - if a cell is already this zone: skip (idempotent extension)
        //   - if a cell is owned by anything else: abort (atomic — no
        //     partial mutation; admin re-runs after resolving the conflict)
        java.util.List<ClaimKey> toClaim = new java.util.ArrayList<>();
        int alreadyOwned = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ClaimKey k = center.offset(dx, dz);
                Long owner = store.claimOwner(k);
                if (owner == null) {
                    toClaim.add(k);
                } else if (owner.longValue() == zone.id()) {
                    alreadyOwned++;
                } else {
                    Realm other = store.getRealm(owner);
                    return Result.fail("errors.not-wilderness", Map.of(
                            "realm", other == null ? "?" : other.name(),
                            "x", String.valueOf(k.chunkX()),
                            "z", String.valueOf(k.chunkZ())
                    ));
                }
            }
        }
        if (toClaim.isEmpty()) {
            return Result.fail("errors.chunk-already-yours");
        }

        store.addClaims(zone.id(), toClaim, Instant.now().toEpochMilli());
        return Result.ok("info.zone-claim-success", Map.of(
                "realm", zone.name(),
                "x", String.valueOf(center.chunkX()),
                "z", String.valueOf(center.chunkZ()),
                "n", String.valueOf(toClaim.size()),
                "skipped", String.valueOf(alreadyOwned)
        ));
    }

    public Result unclaim(Player op) {
        ClaimKey here = ClaimKey.of(op.getLocation());
        Long owner = store.claimOwner(here);
        if (owner == null) return Result.fail("errors.chunk-not-yours");
        Realm zone = store.getRealm(owner);
        if (zone == null || !zone.isAdminZone()) {
            return Result.fail("errors.not-admin-zone",
                    Map.of("realm", zone == null ? "?" : zone.name()));
        }
        store.removeClaim(here);
        return Result.ok("info.zone-unclaim-success", Map.of(
                "realm", zone.name(),
                "x", String.valueOf(here.chunkX()),
                "z", String.valueOf(here.chunkZ())
        ));
    }

    public Result delete(Player op, String zoneName, boolean confirm) {
        Realm zone = store.getRealmByName(zoneName);
        if (zone == null) return Result.fail("errors.realm-not-found",
                Map.of("realm", zoneName));
        if (!zone.isAdminZone()) return Result.fail("errors.not-admin-zone",
                Map.of("realm", zoneName));
        // Confirm gate scoped to (op, zone-name) so a stale token from a
        // different zone can't be reused. Mirrors /realm disband behaviour
        // — admin zones can hold hundreds of chunks; a typo shouldn't wipe
        // them silently.
        String key = "zone-delete:" + zoneName.toLowerCase(Locale.ROOT);
        if (!confirm) {
            confirms.arm(op.getUniqueId(), key);
            return Result.fail("errors.confirm-required-zone-delete",
                    Map.of("realm", zoneName,
                            "chunks", String.valueOf(store.claimCount(zone.id()))));
        }
        if (!confirms.consume(op.getUniqueId(), key)) {
            return Result.fail("errors.confirm-expired");
        }
        store.deleteRealm(zone.id());
        return Result.ok("info.zone-deleted", Map.of("realm", zoneName));
    }

    public java.util.List<Realm> list() {
        java.util.List<Realm> out = new java.util.ArrayList<>();
        for (Realm r : store.allRealms()) if (r.isAdminZone()) out.add(r);
        out.sort(java.util.Comparator.comparing(Realm::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private Result validateName(String name) {
        if (name.length() < config.realmNameMin() || name.length() > config.realmNameMax()) {
            return Result.fail("errors.invalid-name");
        }
        if (!NAME_RE.matcher(name).matches()) return Result.fail("errors.invalid-name");
        if (config.reservedNames().contains(name.toLowerCase(Locale.ROOT))) {
            return Result.fail("errors.reserved-name");
        }
        return Result.ok("");
    }
}
