package com.realms.display;

import com.realms.RealmsConfig;
import com.realms.data.ClaimKey;
import com.realms.data.NameCache;
import com.realms.data.Realm;
import com.realms.data.RealmsStore;
import com.realms.data.Resident;
import com.realms.data.Role;
import com.realms.manager.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.Location;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fires the chunk-cross title (and optional sound). Throttled to actual
 * ownership changes — same-chunk moves and same-owner crossings produce
 * nothing, which is most of every minute.
 */
public final class BorderTitleListener implements Listener {

    private final RealmsConfig config;
    private final RealmsStore store;
    private final NameCache names;
    private final DisplayPrefsManager prefs;
    private final Palette palette;

    /** Last claim-owner-id we showed for each player. null = wilderness. */
    private final Map<UUID, Long> lastOwner = new ConcurrentHashMap<>();

    public BorderTitleListener(RealmsConfig config, RealmsStore store, NameCache names,
                               DisplayPrefsManager prefs, Palette palette) {
        this.config = config;
        this.store = store;
        this.names = names;
        this.prefs = prefs;
        this.palette = palette;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        // Coord-based chunk filter — calling getChunk() forces a chunk load
        // and relies on Chunk.equals semantics that vary across Paper builds.
        // The shift-by-4 form (block coord >> 4) gives the chunk index for
        // both positive and negative coords (vs. integer division which
        // truncates toward zero and breaks at -1).
        if (sameChunk(event.getFrom(), event.getTo())) return;
        check(event.getPlayer(), ClaimKey.of(event.getTo()));
    }

    /**
     * PlayerMoveEvent doesn't fire for teleports — /spawn, /tp, ender pearls,
     * /realm home all jump the player without a move event in between. The
     * title would otherwise stay stuck on the previous chunk's owner.
     */
    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;
        if (sameChunk(event.getFrom(), event.getTo())) return;
        check(event.getPlayer(), ClaimKey.of(event.getTo()));
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        check(event.getPlayer(), ClaimKey.of(event.getPlayer().getLocation()));
    }

    private static boolean sameChunk(Location a, Location b) {
        if (a == null || b == null) return false;
        if (a.getWorld() != b.getWorld()) return false;
        return (a.getBlockX() >> 4) == (b.getBlockX() >> 4)
                && (a.getBlockZ() >> 4) == (b.getBlockZ() >> 4);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Seed lastOwner so the first chunk-cross fires correctly. Don't
        // show a title on join — too noisy.
        Player p = event.getPlayer();
        recordOwner(p.getUniqueId(), store.claimOwner(ClaimKey.of(p.getLocation())));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastOwner.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Public so {@link TerritoryDisplayTask} can call it as a 1Hz fallback —
     * any chunk-crossing path the event listeners miss (vehicle movement,
     * spectator flight, plugin-suppressed events, weird teleport edge cases)
     * will still fire the title within a tick of the next poll.
     */
    public void checkPlayer(Player p) {
        if (p == null || !p.isOnline()) return;
        check(p, ClaimKey.of(p.getLocation()));
    }

    private void check(Player p, ClaimKey here) {
        Long newOwner = store.claimOwner(here);
        Long oldOwner = lastOwner.get(p.getUniqueId());
        if (java.util.Objects.equals(newOwner, oldOwner)) return;
        recordOwner(p.getUniqueId(), newOwner);
        if (!config.borderTitleEnabled()) return;
        if (!prefs.of(p).titleOn()) return;
        showTitle(p, newOwner);
        playSound(p, newOwner);
    }

    /**
     * Update lastOwner without ever putting null into the map —
     * ConcurrentHashMap rejects null values with NPE. We map "wilderness"
     * (no owner) to "no entry"; lastOwner.get returns null in either case,
     * which is exactly what every reader expects.
     */
    private void recordOwner(UUID uuid, Long owner) {
        if (owner == null) lastOwner.remove(uuid);
        else lastOwner.put(uuid, owner);
    }

    private void showTitle(Player p, Long ownerId) {
        Palette.Relation rel = palette.relationFor(p, ownerId);
        Realm realm = ownerId == null ? null : store.getRealm(ownerId);
        String mainRaw = realm == null
                ? "&7Entering " + config.message("info.display-title-wilderness", "&7Wilderness")
                : "&7Entering " + palette.code(rel) + realm.name();
        String subRaw = "";
        if (config.borderTitleSubtitle() && realm != null) {
            if (realm.isAdminZone()) {
                subRaw = "&8[" + realm.zoneType().name().toLowerCase(java.util.Locale.ROOT)
                        + "]&7 · " + store.claimCount(realm.id()) + " chunks";
            } else if (realm.peaceful()) {
                subRaw = config.message("info.display-subtitle-peaceful", "&6[Peaceful]");
            } else if (palette.isWeakened(realm)) {
                subRaw = config.message("info.display-subtitle-weakened", "&c[Weakened]");
            } else {
                // Look up the current mayor from residents — realm.founder()
                // is intentionally never updated on /realm transfer, so it
                // can name a player who is no longer a member (or whose
                // UUID has dropped out of the live name cache, producing
                // the "Mayor: ?" bug). The name lookup falls back to
                // Bukkit's offline-player cache for stale UUIDs.
                String mayorName = currentMayorName(realm);
                subRaw = Text.render(config.message("info.display-subtitle-mayor",
                        "&7Mayor: {player} · {chunks} chunks"),
                        Map.of(
                                "player", mayorName,
                                "chunks", String.valueOf(store.claimCount(realm.id()))
                        ));
            }
        }
        sendLegacyTitle(p, Text.colorize(mainRaw), Text.colorize(subRaw));
    }

    /**
     * Walk residents to find the current mayor. Falls back to the founder
     * UUID and finally to "?" — but the founder fallback is also routed
     * through {@link NameCache#getOrLookup} so a stale uncached founder
     * still resolves via Bukkit's offline-player cache.
     */
    private String currentMayorName(Realm realm) {
        for (Resident r : store.residentsOf(realm.id())) {
            if (r.role() == Role.MAYOR) {
                return names.getOrLookup(r.uuid(), "?");
            }
        }
        return names.getOrLookup(realm.founder(), "?");
    }

    /**
     * Player#sendTitle is deprecated but reliable across Paper builds.
     * The Adventure showTitle path rendered only the first title per
     * session in some configurations — legacy fires every call.
     */
    @SuppressWarnings("deprecation")
    private void sendLegacyTitle(Player p, String main, String sub) {
        p.sendTitle(main, sub,
                config.borderTitleFadeIn(),
                config.borderTitleStay(),
                config.borderTitleFadeOut());
    }

    private void playSound(Player p, Long ownerId) {
        if (!prefs.of(p).soundOn()) return;
        String key = ownerId == null ? config.soundEnterWilderness() : config.soundEnterRealm();
        if (key == null || key.isEmpty()) return;
        try {
            Sound s = Sound.valueOf(key.toUpperCase(java.util.Locale.ROOT)
                    .replace('.', '_').replace(':', '_'));
            p.playSound(p.getLocation(), s, (float) config.soundVolume(), (float) config.soundPitch());
        } catch (IllegalArgumentException e) {
            // Unknown sound key → fall through to namespaced key path so admins
            // can use vanilla sound names verbatim.
            p.playSound(p.getLocation(), key, (float) config.soundVolume(), (float) config.soundPitch());
        }
    }

}
