package com.realms.manager;

import com.realms.RealmsConfig;
import com.realms.data.ClaimKey;
import com.realms.data.NamedHome;
import com.realms.data.Realm;
import com.realms.data.RealmsStore;
import com.realms.data.Resident;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * /realm home — warmup-then-teleport with cancel-on-move and cancel-on-damage.
 * /realm sethome — mayor only, must stand inside own claim.
 *
 * Default home (no name) lives on the {@link Realm} row. Each additional
 * named home unlocks at every {@code home.power-per-slot} threshold. Homes
 * stay valid below threshold once set — power loss never deletes a home,
 * but it does block creating new ones until power recovers.
 *
 * Cooldown is per-player. Active warmups are tracked in-memory and cleared
 * on quit / disconnect.
 */
public final class HomeManager implements Listener {

    public record Warmup(long readyAtMillis, Location startedAt) {}

    /** Reserved keywords that must not be used as a named-home key. */
    private static final java.util.Set<String> RESERVED_NAMES =
            java.util.Set.of("default", "spawn", "main", "list");

    private static final Pattern NAME_RE = Pattern.compile("[A-Za-z0-9_-]+");

    private final Plugin plugin;
    private final RealmsConfig config;
    private final RealmsStore store;
    private final Map<UUID, Warmup> warming = new ConcurrentHashMap<>();
    /** uuid → epoch-millis when cooldown expires */
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public HomeManager(Plugin plugin, RealmsConfig config, RealmsStore store) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
    }

    // ---- Set / delete --------------------------------------------------------

    /** Set the default (unnamed) home — mayor only, must stand on own claim. */
    public Result setHome(Player player) {
        Resident me = store.getResident(player.getUniqueId());
        if (me == null) return Result.fail("errors.not-in-realm");
        if (me.role() != com.realms.data.Role.MAYOR) return Result.fail("errors.not-mayor");
        Realm realm = store.getRealm(me.realmId());
        if (realm == null) return Result.fail("errors.not-in-realm");
        Long owner = store.claimOwner(ClaimKey.of(player.getLocation()));
        // Long != long auto-boxes the primitive into a NEW Long and identity-
        // compares. Long cache is [-128, 127], so realms with id ≥ 128 would
        // silently fail this check for their own mayors. Use longValue.
        if (owner == null || owner.longValue() != realm.id()) {
            return Result.fail("errors.chunk-not-yours");
        }
        store.updateRealm(realm.withHome(player.getLocation()));
        return Result.ok("info.sethome-success");
    }

    /**
     * Set an additional named home. Mayor only, on own claim, name validated,
     * and capped by realm power: max named slots = floor(power / per-slot).
     * Replacing an existing name doesn't consume a fresh slot — the slot
     * cap only gates new names.
     */
    public Result setHome(Player player, String rawName) {
        Resident me = store.getResident(player.getUniqueId());
        if (me == null) return Result.fail("errors.not-in-realm");
        if (me.role() != com.realms.data.Role.MAYOR) return Result.fail("errors.not-mayor");
        Realm realm = store.getRealm(me.realmId());
        if (realm == null) return Result.fail("errors.not-in-realm");
        Long owner = store.claimOwner(ClaimKey.of(player.getLocation()));
        if (owner == null || owner.longValue() != realm.id()) {
            return Result.fail("errors.chunk-not-yours");
        }
        // Server admin disabled the multi-home feature outright. Distinct
        // from "0 slots earned" because power growth never recovers from it.
        if (config.homePowerPerSlot() <= 0) {
            return Result.fail("errors.home-disabled");
        }

        Result nameCheck = validateName(rawName);
        if (!nameCheck.ok()) return nameCheck;
        String key = rawName.trim().toLowerCase(Locale.ROOT);

        boolean replacing = store.getNamedHome(realm.id(), key) != null;
        int existing = store.namedHomeCount(realm.id());
        int allowed  = maxNamedSlots(realm);
        // Replacing an existing name keeps the slot — only check the cap on a
        // brand-new slot.
        if (!replacing && existing >= allowed) {
            return Result.fail("errors.home-cap-reached", Map.of(
                    "n", String.valueOf(existing),
                    "max", String.valueOf(allowed),
                    "needed", String.valueOf(neededPowerForSlot(existing + 1))
            ));
        }
        store.putNamedHome(realm.id(), NamedHome.from(key, player.getLocation()));
        return Result.ok("info.sethome-named-success", Map.of(
                "name", key,
                "n", String.valueOf(existing + (replacing ? 0 : 1)),
                "max", String.valueOf(allowed)
        ));
    }

    /** Remove a named home. Mayor only. No-op error if not present. */
    public Result deleteHome(Player player, String rawName) {
        Resident me = store.getResident(player.getUniqueId());
        if (me == null) return Result.fail("errors.not-in-realm");
        if (me.role() != com.realms.data.Role.MAYOR) return Result.fail("errors.not-mayor");
        Realm realm = store.getRealm(me.realmId());
        if (realm == null) return Result.fail("errors.not-in-realm");

        if (rawName == null || rawName.isBlank()) {
            return Result.fail("errors.home-name-required");
        }
        String key = rawName.trim().toLowerCase(Locale.ROOT);
        if (store.getNamedHome(realm.id(), key) == null) {
            return Result.fail("errors.home-not-found", Map.of("name", key));
        }
        store.removeNamedHome(realm.id(), key);
        return Result.ok("info.delhome-success", Map.of("name", key));
    }

    // ---- Read ----------------------------------------------------------------

    /** Snapshot of all homes (default + named) for the player's realm. */
    public Map<String, Location> listHomes(Player player) {
        Resident me = store.getResident(player.getUniqueId());
        if (me == null) return Map.of();
        Realm realm = store.getRealm(me.realmId());
        if (realm == null) return Map.of();
        Map<String, Location> out = new HashMap<>();
        Location def = realm.homeLocation();
        if (def != null) out.put("default", def);
        for (Map.Entry<String, NamedHome> e : store.namedHomes(realm.id()).entrySet()) {
            Location loc = e.getValue().location();
            if (loc != null) out.put(e.getKey(), loc);
        }
        return out;
    }

    public int maxNamedSlots(Realm realm) {
        int per = config.homePowerPerSlot();
        if (per <= 0) return 0;
        long power = Math.max(0L, realm == null ? 0L : realm.cachedPower());
        long slots = power / per;
        // Hard upper bound — keeps the integer well-behaved if a server
        // tunes per-slot to 1 and a realm racks up tens of thousands of power.
        return (int) Math.min(slots, 64L);
    }

    /** The smallest power threshold that grants slot {@code targetSlot} (1-indexed). */
    public long neededPowerForSlot(int targetSlot) {
        int per = config.homePowerPerSlot();
        if (per <= 0) return Long.MAX_VALUE;
        return (long) Math.max(1, targetSlot) * per;
    }

    // ---- Teleport ------------------------------------------------------------

    /** Teleport to the default (unnamed) home. */
    public Result home(Player player) {
        return teleport(player, null);
    }

    /** Teleport to a specific named home. {@code null} or blank → default. */
    public Result home(Player player, String rawName) {
        if (rawName == null || rawName.isBlank()
                || rawName.equalsIgnoreCase("default")) {
            return teleport(player, null);
        }
        return teleport(player, rawName.trim().toLowerCase(Locale.ROOT));
    }

    private Result teleport(Player player, String namedKey) {
        Resident me = store.getResident(player.getUniqueId());
        if (me == null) return Result.fail("errors.not-in-realm");
        Realm realm = store.getRealm(me.realmId());
        if (realm == null) return Result.fail("errors.not-in-realm");

        Location target = resolveHome(realm, namedKey);
        if (target == null) {
            return namedKey == null
                    ? Result.fail("errors.no-home")
                    : Result.fail("errors.home-not-found", Map.of("name", namedKey));
        }

        long now = Instant.now().toEpochMilli();
        Long cdEnd = cooldowns.get(player.getUniqueId());
        if (cdEnd != null && cdEnd > now) {
            long secs = (cdEnd - now) / 1000L;
            return Result.fail("errors.home-cooldown", Map.of("n", String.valueOf(secs)));
        }

        long warmupSecs = Math.max(0L, config.homeWarmupSeconds());
        if (warmupSecs == 0) {
            doTeleport(player, target);
            return Result.ok("info.home-teleporting");
        }
        warming.put(player.getUniqueId(),
                new Warmup(now + warmupSecs * 1000L, player.getLocation()));
        final String capturedKey = namedKey;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Warmup w = warming.remove(player.getUniqueId());
            if (w == null) return;          // cancelled
            if (!player.isOnline()) return;
            // Re-fetch the home in case it was cleared mid-warmup (admin
            // wiped, realm disbanded, chunk overclaimed and cleared, etc.).
            // Without this we'd use the stale snapshot captured 3s ago.
            Resident fresh = store.getResident(player.getUniqueId());
            if (fresh == null) return;
            Realm freshRealm = store.getRealm(fresh.realmId());
            Location freshTarget = freshRealm == null ? null : resolveHome(freshRealm, capturedKey);
            if (freshTarget == null) {
                player.sendMessage(Text.colorize(
                        "&cTeleport cancelled — realm home was removed during warmup."));
                return;
            }
            doTeleport(player, freshTarget);
        }, warmupSecs * 20L);
        return Result.ok("info.home-warming",
                Map.of("n", String.valueOf(warmupSecs)));
    }

    private Location resolveHome(Realm realm, String namedKey) {
        if (namedKey == null) return realm.homeLocation();
        NamedHome h = store.getNamedHome(realm.id(), namedKey);
        return h == null ? null : h.location();
    }

    private void doTeleport(Player player, Location target) {
        player.teleport(target);
        cooldowns.put(player.getUniqueId(),
                Instant.now().toEpochMilli() + config.homeCooldownSeconds() * 1000L);
    }

    // ---- Validation ----------------------------------------------------------

    private Result validateName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) return Result.fail("errors.home-name-required");
        if (name.length() < config.homeNameMin() || name.length() > config.homeNameMax()) {
            return Result.fail("errors.home-name-invalid");
        }
        if (!NAME_RE.matcher(name).matches()) return Result.fail("errors.home-name-invalid");
        if (RESERVED_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
            return Result.fail("errors.home-name-reserved", Map.of("name", name));
        }
        return Result.ok("");
    }

    // ---- Listeners -----------------------------------------------------------

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!config.homeCancelOnMove()) return;
        Warmup w = warming.get(event.getPlayer().getUniqueId());
        if (w == null) return;
        Location now = event.getPlayer().getLocation();
        if (now.getBlockX() != w.startedAt().getBlockX()
                || now.getBlockY() != w.startedAt().getBlockY()
                || now.getBlockZ() != w.startedAt().getBlockZ()) {
            warming.remove(event.getPlayer().getUniqueId());
            event.getPlayer().sendMessage(Text.colorize(
                    "&cTeleport cancelled — you moved."));
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!config.homeCancelOnDamage()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        if (warming.remove(p.getUniqueId()) != null) {
            p.sendMessage(Text.colorize("&cTeleport cancelled — you took damage."));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        warming.remove(event.getPlayer().getUniqueId());
        // Do NOT clear cooldowns — they should survive disconnect to stop
        // log-out-then-back-in cooldown skip.
    }
}
