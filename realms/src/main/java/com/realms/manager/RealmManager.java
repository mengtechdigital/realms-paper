package com.realms.manager;

import com.realms.RealmsConfig;
import com.realms.data.ClaimKey;
import com.realms.data.NameCache;
import com.realms.data.Realm;
import com.realms.data.RealmsStore;
import com.realms.data.Resident;
import com.realms.data.Role;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Realm lifecycle + membership operations. All public methods return a
 * {@link Result} that the command layer translates into a chat message.
 *
 * Validation order: identity (player exists / is in realm) → permission
 * (mayor/assistant) → semantic (name valid, target reachable) → state
 * mutation. Failures never half-mutate.
 */
public final class RealmManager {

    private static final Pattern NAME_RE = Pattern.compile("[A-Za-z0-9_-]+");
    private static final long INVITE_TTL_SECONDS = 60;

    private final RealmsConfig config;
    private final RealmsStore store;
    private final NameCache nameCache;
    private final InviteStore invites;
    private final ConfirmStore confirms;
    private final PowerCalc power;

    public RealmManager(RealmsConfig config, RealmsStore store, NameCache nameCache,
                        InviteStore invites, ConfirmStore confirms, PowerCalc power) {
        this.config = config;
        this.store = store;
        this.nameCache = nameCache;
        this.invites = invites;
        this.confirms = confirms;
        this.power = power;
    }

    // ---- Realm lifecycle --------------------------------------------------

    /**
     * Found a new realm at the player's current chunk. The player becomes
     * Mayor and the chunk becomes the realm's first claim + spawn.
     */
    public Result createRealm(Player founder, String rawName) {
        if (store.getResident(founder.getUniqueId()) != null) {
            return Result.fail("errors.already-in-realm",
                    Map.of("realm", realmNameFor(founder.getUniqueId())));
        }
        String name = rawName == null ? "" : rawName.trim();
        Result nameCheck = validateName(name);
        if (!nameCheck.ok()) return nameCheck;
        if (store.getRealmByName(name) != null) {
            return Result.fail("errors.name-taken", Map.of("realm", name));
        }
        ClaimKey here = ClaimKey.of(founder.getLocation());
        Long owner = store.claimOwner(here);
        if (owner != null) {
            Realm other = store.getRealm(owner);
            return Result.fail("errors.not-wilderness",
                    Map.of("realm", other == null ? "?" : other.name()));
        }

        long now = Instant.now().toEpochMilli();
        Realm realm = store.createRealm(name, founder.getUniqueId(),
                config.peacefulDefault(), now);
        store.upsertResident(new Resident(founder.getUniqueId(), realm.id(), Role.MAYOR, now));
        // Seed default flags (peaceful is its own column, not in flags table).
        store.setFlag(realm.id(), "hostile-spawn", config.flagDefault("hostile-spawn", true));
        store.setFlag(realm.id(), "passive-spawn", config.flagDefault("passive-spawn", true));
        store.setFlag(realm.id(), "mob-griefing",  config.flagDefault("mob-griefing",  false));
        // pvp default: forced false on peaceful realms, else config default.
        boolean pvp = !realm.peaceful() && config.flagDefault("pvp", true);
        store.setFlag(realm.id(), "pvp", pvp);
        // Stake first chunk + set home to player's location.
        store.addClaims(realm.id(), java.util.List.of(here), now);
        Realm withHome = realm.withHome(founder.getLocation());
        store.updateRealm(withHome);
        power.recompute(withHome);

        nameCache.remember(founder);
        return Result.ok("info.realm-created", Map.of(
                "realm", name,
                "player", founder.getName()
        ));
    }

    /** Disband requires a 'confirm' arg the second time it's called within the TTL. */
    public Result disband(Player mayor, boolean confirm) {
        Resident me = store.getResident(mayor.getUniqueId());
        if (me == null) return Result.fail("errors.not-in-realm");
        if (me.role() != Role.MAYOR) return Result.fail("errors.not-mayor");
        if (!confirm) {
            confirms.arm(mayor.getUniqueId(), "disband");
            return Result.fail("errors.confirm-required-disband");
        }
        if (!confirms.consume(mayor.getUniqueId(), "disband")) {
            return Result.fail("errors.confirm-expired");
        }
        Realm r = store.getRealm(me.realmId());
        long realmId = me.realmId();
        store.deleteRealm(realmId);
        // Drop any pending invites pointing at the now-deleted realm so
        // /realm join falls through cleanly. (Join would fail anyway via
        // realm-not-found, but this keeps the in-memory map tidy.)
        invites.clearForRealm(realmId);
        return Result.ok("info.realm-disbanded",
                Map.of("realm", r == null ? "?" : r.name()));
    }

    // ---- Membership -------------------------------------------------------

    public Result invite(Player actor, String targetName) {
        Resident me = store.getResident(actor.getUniqueId());
        if (me == null) return Result.fail("errors.not-in-realm");
        if (!me.role().canManage()) return Result.fail("errors.not-mayor-or-assistant");
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) return Result.fail("errors.player-not-found", Map.of("player", targetName));
        if (store.getResident(target.getUniqueId()) != null) {
            return Result.fail("errors.target-already-in-realm", Map.of("player", target.getName()));
        }
        Realm realm = store.getRealm(me.realmId());
        invites.put(target.getUniqueId(), realm.id(), INVITE_TTL_SECONDS);
        nameCache.remember(target);
        target.sendMessage(Text.render(
                config.message("info.invite-received", "&e{player}&a invited you to &6{realm}&a."),
                Map.of("player", actor.getName(), "realm", realm.name())));
        return Result.ok("info.invited",
                Map.of("player", target.getName(), "realm", realm.name()));
    }

    public Result join(Player joiner, String realmName) {
        if (store.getResident(joiner.getUniqueId()) != null) {
            return Result.fail("errors.already-in-realm",
                    Map.of("realm", realmNameFor(joiner.getUniqueId())));
        }
        Realm realm = store.getRealmByName(realmName);
        if (realm == null) return Result.fail("errors.realm-not-found", Map.of("realm", realmName));
        Long active = invites.active(joiner.getUniqueId(), realm.id());
        if (active == null) {
            return Result.fail("errors.no-pending-invite", Map.of("realm", realmName));
        }
        long now = Instant.now().toEpochMilli();
        store.upsertResident(new Resident(joiner.getUniqueId(), realm.id(), Role.RESIDENT, now));
        invites.clear(joiner.getUniqueId());
        nameCache.remember(joiner);
        // Member count change → power capacity changes.
        power.recompute(realm);
        return Result.ok("info.joined", Map.of("realm", realm.name()));
    }

    public Result leave(Player leaver) {
        Resident me = store.getResident(leaver.getUniqueId());
        if (me == null) return Result.fail("errors.not-in-realm");
        if (me.role() == Role.MAYOR) {
            return Result.fail("errors.mayor-cannot-leave");
        }
        Realm realm = store.getRealm(me.realmId());
        store.removeResident(leaver.getUniqueId());
        if (realm != null) power.recompute(realm);
        return Result.ok("info.left");
    }

    public Result kick(Player actor, String targetName) {
        Resident me = store.getResident(actor.getUniqueId());
        if (me == null) return Result.fail("errors.not-in-realm");
        if (!me.role().canManage()) return Result.fail("errors.not-mayor-or-assistant");
        UUID targetId = resolveUuid(targetName);
        if (targetId == null) return Result.fail("errors.player-not-found", Map.of("player", targetName));
        Resident target = store.getResident(targetId);
        if (target == null || target.realmId() != me.realmId()) {
            return Result.fail("errors.player-not-found", Map.of("player", targetName));
        }
        if (target.uuid().equals(actor.getUniqueId())) {
            return Result.fail("errors.cannot-target-self");
        }
        if (target.role() == Role.MAYOR) {
            return Result.fail("errors.cannot-target-mayor");
        }
        // Assistants cannot kick other assistants — only the mayor can.
        if (target.role() == Role.ASSISTANT && me.role() != Role.MAYOR) {
            return Result.fail("errors.not-mayor");
        }
        store.removeResident(targetId);
        Realm realm = store.getRealm(me.realmId());
        if (realm != null) power.recompute(realm);
        Player onlineTarget = Bukkit.getPlayer(targetId);
        if (onlineTarget != null) {
            onlineTarget.sendMessage(Text.render(
                    config.message("info.kicked-target", "&cYou were kicked from &6{realm}&c."),
                    Map.of("realm", realm == null ? "?" : realm.name())));
        }
        return Result.ok("info.kicked",
                Map.of("player", nameCache.getOr(targetId, targetName)));
    }

    public Result promote(Player mayor, String targetName) {
        return adjustRole(mayor, targetName, Role.ASSISTANT, "info.promoted");
    }

    public Result demote(Player mayor, String targetName) {
        return adjustRole(mayor, targetName, Role.RESIDENT, "info.demoted");
    }

    private Result adjustRole(Player mayor, String targetName, Role newRole, String successKey) {
        Resident me = store.getResident(mayor.getUniqueId());
        if (me == null) return Result.fail("errors.not-in-realm");
        if (me.role() != Role.MAYOR) return Result.fail("errors.not-mayor");
        UUID targetId = resolveUuid(targetName);
        if (targetId == null) return Result.fail("errors.player-not-found", Map.of("player", targetName));
        if (targetId.equals(mayor.getUniqueId())) return Result.fail("errors.cannot-target-self");
        Resident target = store.getResident(targetId);
        if (target == null || target.realmId() != me.realmId()) {
            return Result.fail("errors.player-not-found", Map.of("player", targetName));
        }
        if (target.role() == Role.MAYOR) return Result.fail("errors.cannot-target-mayor");
        store.upsertResident(target.withRole(newRole));
        return Result.ok(successKey, Map.of(
                "player", nameCache.getOr(targetId, targetName),
                "role", newRole.name().toLowerCase(Locale.ROOT)
        ));
    }

    public Result transferMayorship(Player mayor, String targetName) {
        Resident me = store.getResident(mayor.getUniqueId());
        if (me == null) return Result.fail("errors.not-in-realm");
        if (me.role() != Role.MAYOR) return Result.fail("errors.not-mayor");
        UUID targetId = resolveUuid(targetName);
        if (targetId == null) return Result.fail("errors.player-not-found", Map.of("player", targetName));
        if (targetId.equals(mayor.getUniqueId())) return Result.fail("errors.cannot-target-self");
        Resident target = store.getResident(targetId);
        if (target == null || target.realmId() != me.realmId()) {
            return Result.fail("errors.player-not-found", Map.of("player", targetName));
        }
        // Demote current mayor → assistant; promote target → mayor.
        // The founder field is historical and intentionally NOT updated —
        // the mayor of record is whichever resident has Role.MAYOR.
        store.upsertResident(me.withRole(Role.ASSISTANT));
        store.upsertResident(target.withRole(Role.MAYOR));
        Realm realm = store.getRealm(me.realmId());
        return Result.ok("info.transferred", Map.of(
                "player", nameCache.getOr(targetId, targetName),
                "realm", realm == null ? "?" : realm.name()
        ));
    }

    // ---- Helpers ----------------------------------------------------------

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

    private String realmNameFor(UUID uuid) {
        Resident r = store.getResident(uuid);
        if (r == null) return "?";
        Realm realm = store.getRealm(r.realmId());
        return realm == null ? "?" : realm.name();
    }

    /**
     * Resolve a name to UUID. Order:
     *   1. online player (O(1), no I/O),
     *   2. NameCache reverse lookup (O(1), no I/O — populated on every join),
     *   3. Bukkit.getOfflinePlayer (filesystem read, last resort).
     *
     * Returns null if completely unknown.
     */
    private UUID resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        UUID cached = nameCache.uuidFor(name);
        if (cached != null) return cached;
        // Cache miss — fall back to disk. Acceptable on a /command path
        // because it only fires for players who have never logged in since
        // server start AND aren't in the cache (rare).
        @SuppressWarnings("deprecation")
        var off = Bukkit.getOfflinePlayer(name);
        if (off == null || !off.hasPlayedBefore()) return null;
        nameCache.remember(off.getUniqueId(), name);
        return off.getUniqueId();
    }
}
