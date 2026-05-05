package com.realms.manager;

import com.realms.RealmsConfig;
import com.realms.data.ClaimKey;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /realm home — warmup-then-teleport with cancel-on-move and cancel-on-damage.
 * /realm sethome — mayor only, must stand inside own claim.
 *
 * Cooldown is per-player. Active warmups are tracked in-memory and cleared
 * on quit / disconnect.
 */
public final class HomeManager implements Listener {

    public record Warmup(long readyAtMillis, Location startedAt) {}

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

    public Result home(Player player) {
        Resident me = store.getResident(player.getUniqueId());
        if (me == null) return Result.fail("errors.not-in-realm");
        Realm realm = store.getRealm(me.realmId());
        if (realm == null) return Result.fail("errors.not-in-realm");
        Location target = realm.homeLocation();
        if (target == null) return Result.fail("errors.no-home");

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
            Location freshTarget = freshRealm == null ? null : freshRealm.homeLocation();
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

    private void doTeleport(Player player, Location target) {
        player.teleport(target);
        cooldowns.put(player.getUniqueId(),
                Instant.now().toEpochMilli() + config.homeCooldownSeconds() * 1000L);
    }

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
