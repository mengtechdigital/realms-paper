package com.realms.display;

import com.realms.RealmsConfig;
import com.realms.data.ClaimKey;
import com.realms.data.NameCache;
import com.realms.data.Realm;
import com.realms.data.RealmsStore;
import com.realms.manager.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.Title.Times;
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

import java.time.Duration;
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
        lastOwner.put(p.getUniqueId(), store.claimOwner(ClaimKey.of(p.getLocation())));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastOwner.remove(event.getPlayer().getUniqueId());
    }

    private void check(Player p, ClaimKey here) {
        Long newOwner = store.claimOwner(here);
        Long oldOwner = lastOwner.get(p.getUniqueId());
        if (java.util.Objects.equals(newOwner, oldOwner)) return;
        lastOwner.put(p.getUniqueId(), newOwner);
        if (!config.borderTitleEnabled()) return;
        if (!prefs.of(p).titleOn()) return;
        showTitle(p, newOwner);
        playSound(p, newOwner);
    }

    private void showTitle(Player p, Long ownerId) {
        Palette.Relation rel = palette.relationFor(p, ownerId);
        Realm realm = ownerId == null ? null : store.getRealm(ownerId);
        String prefix = palette.code(rel);
        String mainPart = realm == null
                ? config.message("info.display-title-wilderness", "&7Wilderness")
                : prefix + realm.name();
        String subRaw = "";
        if (config.borderTitleSubtitle()) {
            if (realm != null) {
                if (realm.isAdminZone()) {
                    // Admin zones don't have a mayor / chunk economy in any
                    // meaningful sense — show the zone type and chunk count.
                    subRaw = "[" + realm.zoneType().name().toLowerCase(java.util.Locale.ROOT)
                            + "] " + store.claimCount(realm.id()) + " chunks";
                } else if (realm.peaceful()) {
                    subRaw = config.message("info.display-subtitle-peaceful", "[Peaceful]");
                } else if (palette.isWeakened(realm)) {
                    subRaw = config.message("info.display-subtitle-weakened", "[Weakened]");
                } else {
                    subRaw = Text.render(config.message("info.display-subtitle-mayor",
                            "Mayor: {player} · {chunks} chunks"),
                            Map.of(
                                    "player", names.getOr(realm.founder(), "?"),
                                    "chunks", String.valueOf(store.claimCount(realm.id()))
                            ));
                }
            }
        }
        Component main = Component.text(Text.stripColor("Entering ")).color(NamedTextColor.GRAY)
                .append(legacyComponent(mainPart));
        Component sub = subRaw.isEmpty() ? Component.empty() : legacyComponent(subRaw);
        Times times = Times.times(
                Duration.ofMillis(config.borderTitleFadeIn()  * 50L),
                Duration.ofMillis(config.borderTitleStay()    * 50L),
                Duration.ofMillis(config.borderTitleFadeOut() * 50L));
        p.showTitle(Title.title(main, sub, times));
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

    /** Re-use Bukkit's legacy color codes via Adventure's legacy serializer. */
    private static Component legacyComponent(String legacy) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize(legacy);
    }
}
