package com.realms.display;

import com.realms.RealmsConfig;
import com.realms.data.ClaimKey;
import com.realms.data.DisplayPrefs;
import com.realms.data.Realm;
import com.realms.data.RealmsStore;
import com.realms.manager.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1Hz refresh that pushes the current chunk owner into each player's
 * action bar (default) or boss bar (opt-in). Reads claimOwner directly
 * from the in-memory hot map — O(1) per player per tick.
 */
public final class TerritoryDisplayTask extends BukkitRunnable {

    private final RealmsConfig config;
    private final RealmsStore store;
    private final DisplayPrefsManager prefs;
    private final Palette palette;

    /** Live boss bar handles per-player so we can update text + remove cleanly. */
    private final Map<UUID, BossBar> liveBars = new ConcurrentHashMap<>();

    public TerritoryDisplayTask(RealmsConfig config, RealmsStore store,
                                DisplayPrefsManager prefs, Palette palette) {
        this.config = config;
        this.store = store;
        this.prefs = prefs;
        this.palette = palette;
    }

    @Override
    public void run() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            DisplayPrefs pref = prefs.of(p);
            switch (pref.barMode()) {
                case ACTION -> renderActionBar(p);
                case BOSS   -> renderBossBar(p);
                case OFF    -> hideBossBar(p);
            }
        }
    }

    public void onPlayerQuit(UUID id) { hideBossBar(id); }

    private void renderActionBar(Player p) {
        // If we previously showed a boss bar for this player, drop it.
        hideBossBar(p);
        Long ownerId = store.claimOwner(ClaimKey.of(p.getLocation()));
        Palette.Relation rel = palette.relationFor(p, ownerId);
        String text = composeLine(p, ownerId, rel);
        p.sendActionBar(legacy(text));
    }

    private void renderBossBar(Player p) {
        Long ownerId = store.claimOwner(ClaimKey.of(p.getLocation()));
        Palette.Relation rel = palette.relationFor(p, ownerId);
        Realm realm = ownerId == null ? null : store.getRealm(ownerId);
        String text = composeLine(p, ownerId, rel);
        // Fill = claim_cost / capacity. A near-empty bar means the realm is
        // close to weakened (good raid signal). Wilderness gets full bar.
        // Admin zones don't have a meaningful capacity (cachedPower=0), so
        // they always show full instead of underflowing to empty.
        float fill = 1.0f;
        if (realm != null && config.bossBarShowFill() && !realm.isAdminZone()) {
            long capacity = Math.max(1L, realm.cachedPower());
            long cost = (long) store.claimCount(realm.id()) * Math.max(1L, config.costPerChunk());
            fill = (float) Math.max(0.0, Math.min(1.0, 1.0 - (double) cost / capacity));
        }
        BossBar.Color color = bossColor(rel);
        BossBar existing = liveBars.get(p.getUniqueId());
        if (existing == null) {
            existing = BossBar.bossBar(legacy(text), Math.max(0f, Math.min(1f, fill)),
                    color, BossBar.Overlay.PROGRESS);
            liveBars.put(p.getUniqueId(), existing);
        } else {
            existing.name(legacy(text));
            existing.progress(Math.max(0f, Math.min(1f, fill)));
            existing.color(color);
        }
        // Always (re)show: Adventure dedupes if the player already sees this
        // bar, but ensures a returning player after reconnect still sees it
        // even when the cached handle survived through the quit listener.
        p.showBossBar(existing);
    }

    private void hideBossBar(Player p) { hideBossBar(p.getUniqueId()); }
    private void hideBossBar(UUID id) {
        BossBar bar = liveBars.remove(id);
        if (bar == null) return;
        Player p = Bukkit.getPlayer(id);
        if (p != null) p.hideBossBar(bar);
    }

    private String composeLine(Player viewer, Long ownerId, Palette.Relation rel) {
        if (ownerId == null) {
            return palette.code(Palette.Relation.WILDERNESS) + "Wilderness";
        }
        Realm realm = store.getRealm(ownerId);
        if (realm == null) return palette.code(Palette.Relation.WILDERNESS) + "?";
        StringBuilder tag = new StringBuilder();
        if (realm.isAdminZone()) {
            tag.append(" [").append(realm.zoneType().name().toLowerCase(java.util.Locale.ROOT)).append("]");
        } else {
            if (realm.peaceful() && config.actionBarPeacefulTag()) tag.append(" [Peaceful]");
            if (config.actionBarWeakenedTag() && palette.isWeakened(realm)) tag.append(" [Weakened]");
        }
        String relLabel = switch (rel) {
            case ALLY    -> " (Ally)";
            case ENEMY   -> " (Enemy)";
            default      -> "";
        };
        return Text.colorize(palette.code(rel) + realm.name() + relLabel + tag);
    }

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    private static BossBar.Color bossColor(Palette.Relation rel) {
        return switch (rel) {
            case OWN        -> BossBar.Color.GREEN;
            case ALLY       -> BossBar.Color.BLUE;
            case ENEMY      -> BossBar.Color.RED;
            case NEUTRAL    -> BossBar.Color.YELLOW;
            case PEACEFUL   -> BossBar.Color.YELLOW;
            case SAFEZONE   -> BossBar.Color.GREEN;
            case WARZONE    -> BossBar.Color.RED;
            case WILDERNESS -> BossBar.Color.WHITE;
        };
    }
}
