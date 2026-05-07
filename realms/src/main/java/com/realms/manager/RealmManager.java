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
    private final AllyProposalStore allyProposals;
    /** Set after construction — phase 9 prefix updater wiring. */
    private java.util.function.Consumer<Player> prefixRefresh = p -> {};

    public RealmManager(RealmsConfig config, RealmsStore store, NameCache nameCache,
                        InviteStore invites, ConfirmStore confirms, PowerCalc power,
                        AllyProposalStore allyProposals) {
        this.config = config;
        this.store = store;
        this.nameCache = nameCache;
        this.invites = invites;
        this.confirms = confirms;
        this.power = power;
        this.allyProposals = allyProposals;
    }

    /** Phase-9 hook so role / realm changes refresh chat & tab prefixes. */
    public void setPrefixRefresh(java.util.function.Consumer<Player> refresh) {
        this.prefixRefresh = refresh == null ? p -> {} : refresh;
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
                config.peacefulDefault(), com.realms.data.ZoneType.NORMAL, now);
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
        prefixRefresh.accept(founder);
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
        // Snapshot residents so we can refresh their prefixes after delete.
        java.util.List<UUID> formerMembers = new java.util.ArrayList<>();
        for (Resident res : store.residentsOf(realmId)) formerMembers.add(res.uuid());
        store.deleteRealm(realmId);
        invites.clearForRealm(realmId);
        allyProposals.clearForRealm(realmId);
        for (UUID id : formerMembers) {
            Player online = Bukkit.getPlayer(id);
            if (online != null) prefixRefresh.accept(online);
        }
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
        prefixRefresh.accept(joiner);
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
        prefixRefresh.accept(leaver);
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
            prefixRefresh.accept(onlineTarget);
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
        Player onlineTarget = Bukkit.getPlayer(targetId);
        if (onlineTarget != null) prefixRefresh.accept(onlineTarget);
        return Result.ok(successKey, Map.of(
                "player", nameCache.getOr(targetId, targetName),
                "role", newRole.name().toLowerCase(Locale.ROOT)
        ));
    }

    /**
     * Rename the realm. Mayor only. Validates the new name with the same
     * rules used at creation, and refuses if the name is already taken
     * (case-insensitive). Renaming is in-place — claim, member, and
     * relation rows continue to point at the realm's stable id.
     */
    public Result rename(Player actor, String rawName) {
        Resident me = store.getResident(actor.getUniqueId());
        if (me == null) return Result.fail("errors.not-in-realm");
        if (me.role() != Role.MAYOR) return Result.fail("errors.not-mayor");
        Realm realm = store.getRealm(me.realmId());
        if (realm == null) return Result.fail("errors.not-in-realm");
        String name = rawName == null ? "" : rawName.trim();
        Result nameCheck = validateName(name);
        if (!nameCheck.ok()) return nameCheck;
        // Allow same-name "rename" (case difference) so mayors can fix
        // capitalization. Any other realm holding the new key is a conflict.
        Realm clash = store.getRealmByName(name);
        if (clash != null && clash.id() != realm.id()) {
            return Result.fail("errors.name-taken", Map.of("realm", name));
        }
        if (realm.name().equals(name)) {
            return Result.fail("errors.rename-no-change", Map.of("realm", name));
        }
        String oldName = realm.name();
        store.updateRealm(realm.withName(name));
        // Refresh chat/tab prefixes for everyone in the realm so the new
        // name surfaces immediately without requiring a relog.
        for (Resident r : store.residentsOf(realm.id())) {
            Player online = Bukkit.getPlayer(r.uuid());
            if (online != null) prefixRefresh.accept(online);
        }
        return Result.ok("info.realm-renamed", Map.of(
                "old", oldName,
                "realm", name
        ));
    }

    /**
     * Override the role title for a realm (e.g. "Lord" instead of "Mayor").
     * Mayor only. {@code reset} (case-insensitive) clears the override back
     * to the plugin default.
     */
    public Result setTitle(Player actor, Role role, String rawTitle) {
        Resident me = store.getResident(actor.getUniqueId());
        if (me == null) return Result.fail("errors.not-in-realm");
        if (me.role() != Role.MAYOR) return Result.fail("errors.not-mayor");
        if (role == null) return Result.fail("errors.title-invalid-role");

        if (rawTitle == null || rawTitle.isBlank()
                || rawTitle.equalsIgnoreCase("reset")
                || rawTitle.equalsIgnoreCase("default")) {
            store.clearTitle(me.realmId(), role);
            // Reapply prefixes so {role} substitution updates live.
            refreshRealmPrefixes(me.realmId());
            return Result.ok("info.title-cleared", Map.of(
                    "role", role.name().toLowerCase(Locale.ROOT)
            ));
        }
        Result check = validateTitle(rawTitle);
        if (!check.ok()) return check;
        // Strip color codes — chat injection vector. The title appears in
        // chat prefix, where embedded &-codes could spoof admin messages.
        String safe = Text.stripColor(rawTitle.trim());
        if (safe.isBlank()) return Result.fail("errors.title-invalid");
        store.setTitle(me.realmId(), role, safe);
        refreshRealmPrefixes(me.realmId());
        return Result.ok("info.title-set", Map.of(
                "role", role.name().toLowerCase(Locale.ROOT),
                "title", safe
        ));
    }

    private void refreshRealmPrefixes(long realmId) {
        for (Resident r : store.residentsOf(realmId)) {
            Player online = Bukkit.getPlayer(r.uuid());
            if (online != null) prefixRefresh.accept(online);
        }
    }

    private Result validateTitle(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.length() < 2 || t.length() > 20) return Result.fail("errors.title-invalid");
        // Letters, digits, space, dash, underscore, apostrophe — keeps
        // multi-word titles like "Land Holder" working without opening
        // the door to control characters or markup.
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (Character.isLetterOrDigit(ch)) continue;
            if (ch == ' ' || ch == '-' || ch == '_' || ch == '\'') continue;
            return Result.fail("errors.title-invalid");
        }
        return Result.ok("");
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
        prefixRefresh.accept(mayor);
        Player onlineTarget = Bukkit.getPlayer(targetId);
        if (onlineTarget != null) prefixRefresh.accept(onlineTarget);
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
