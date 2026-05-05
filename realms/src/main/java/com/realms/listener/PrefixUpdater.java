package com.realms.listener;

import com.realms.RealmsConfig;
import com.realms.data.Realm;
import com.realms.data.RealmsStore;
import com.realms.data.Resident;
import com.realms.integration.LuckPermsHook;
import com.realms.manager.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Map;

/**
 * Applies the realm chat / tab prefix using either the chat-event setFormat
 * path (default — composes with admin LP prefix already in the format),
 * the LuckPerms transient-node path (for chat plugins that template both
 * weights), or both. Mode comes from {@code prefix-mode} in config.
 *
 * Tab nameplate: setPlayerListName always runs as a best-effort prepend.
 * Chat plugins that override the tab list (TAB / Featherboard) ignore us.
 */
public final class PrefixUpdater implements Listener {

    private final RealmsConfig config;
    private final RealmsStore store;
    /** Optional — null when LuckPerms isn't installed. */
    private final LuckPermsHook luckPerms;

    public PrefixUpdater(RealmsConfig config, RealmsStore store, LuckPermsHook luckPerms) {
        this.config = config;
        this.store = store;
        this.luckPerms = luckPerms;
    }

    /** Recompute and re-apply the player's tab prefix and (if enabled) LP transient prefix. */
    public void refresh(Player player) {
        String tab = computeTabPrefix(player);
        if (tab == null) {
            player.setPlayerListName(player.getName());
        } else {
            player.setPlayerListName(Text.colorize(tab) + player.getName());
        }
        if (luckPerms != null && lpModeEnabled()) {
            String chat = computeChatPrefix(player);
            if (chat == null) luckPerms.clear(player);
            else luckPerms.apply(player, Text.colorize(chat));
        }
    }

    /**
     * HIGHEST (not MONITOR) — refresh sets player list name + LuckPerms
     * meta, both of which are mutations. MONITOR is reserved for pure
     * observation; running side-effects there breaks the priority contract
     * for plugins that cancel join events at MONITOR.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (luckPerms != null) luckPerms.clear(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!chatEventModeEnabled()) return;
        String prefix = computeChatPrefix(event.getPlayer());
        if (prefix == null) return;
        // Prepend onto the existing format so chat-plugin / admin LP prefix
        // already in the format string still surfaces.
        event.setFormat(Text.colorize(prefix) + event.getFormat());
    }

    private boolean chatEventModeEnabled() {
        String mode = config.prefixMode().toLowerCase(Locale.ROOT);
        return mode.equals("chat-event") || mode.equals("both");
    }

    private boolean lpModeEnabled() {
        String mode = config.prefixMode().toLowerCase(Locale.ROOT);
        return mode.equals("luckperms-meta") || mode.equals("both");
    }

    private String computeTabPrefix(Player player) {
        Resident me = store.getResident(player.getUniqueId());
        if (me == null) return null;
        Realm realm = store.getRealm(me.realmId());
        if (realm == null) return null;
        return Text.render(config.tabPrefixFormat(), Map.of(
                "realm", realm.name(),
                "role",  roleLabel(me)
        ));
    }

    private String computeChatPrefix(Player player) {
        Resident me = store.getResident(player.getUniqueId());
        if (me == null) return null;
        Realm realm = store.getRealm(me.realmId());
        if (realm == null) return null;
        return Text.render(config.chatPrefixFormat(), Map.of(
                "realm", realm.name(),
                "role",  roleLabel(me)
        ));
    }

    private static String roleLabel(Resident r) {
        return switch (r.role()) {
            case MAYOR -> "Mayor";
            case ASSISTANT -> "Assistant";
            case RESIDENT -> "Resident";
        };
    }
}
