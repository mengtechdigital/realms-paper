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
 * Applies the realm chat / tab prefix and suffix using either the chat-event
 * setFormat path (default — composes with admin LP meta already in the
 * format), the LuckPerms transient-node path (for chat plugins that template
 * both weights), or both. Modes come from {@code prefix-mode} and
 * {@code suffix-mode} in config — set either to {@code off} to disable that side.
 *
 * Tab nameplate: setPlayerListName always runs as a best-effort prefix prepend
 * + suffix append. Chat plugins that override the tab list (TAB / Featherboard)
 * ignore us and should read %realms_tab_prefix% / %realms_tab_suffix%.
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

    /** Recompute and re-apply the player's tab nameplate and (if enabled) LP transient meta. */
    public void refresh(Player player) {
        String tabPrefix = computeTabPrefix(player);
        String tabSuffix = computeTabSuffix(player);
        String head = tabPrefix == null ? "" : Text.colorize(tabPrefix);
        String tail = tabSuffix == null ? "" : Text.colorize(tabSuffix);
        player.setPlayerListName(head + player.getName() + tail);

        if (luckPerms != null) {
            if (lpModeEnabled(config.prefixMode())) {
                String chat = computeChatPrefix(player);
                if (chat == null) luckPerms.clearPrefix(player);
                else luckPerms.applyPrefix(player, Text.colorize(chat));
            }
            if (lpModeEnabled(config.suffixMode())) {
                String chat = computeChatSuffix(player);
                if (chat == null) luckPerms.clearSuffix(player);
                else luckPerms.applySuffix(player, Text.colorize(chat));
            }
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
        if (luckPerms != null) luckPerms.clearAll(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        String prefix = chatEventModeEnabled(config.prefixMode()) ? computeChatPrefix(event.getPlayer()) : null;
        String suffix = chatEventModeEnabled(config.suffixMode()) ? computeChatSuffix(event.getPlayer()) : null;
        if (prefix == null && suffix == null) return;
        String head = prefix == null ? "" : Text.colorize(prefix);
        String tail = suffix == null ? "" : Text.colorize(suffix);
        // Prepend prefix / append suffix onto the existing format so chat-plugin
        // / admin LP meta already in the format string still surfaces.
        event.setFormat(head + event.getFormat() + tail);
    }

    private static boolean chatEventModeEnabled(String mode) {
        String m = mode.toLowerCase(Locale.ROOT);
        return m.equals("chat-event") || m.equals("both");
    }

    private static boolean lpModeEnabled(String mode) {
        String m = mode.toLowerCase(Locale.ROOT);
        return m.equals("luckperms-meta") || m.equals("both");
    }

    private String computeTabPrefix(Player player) {
        return renderForPlayer(player, config.tabPrefixFormat(), config.prefixMode());
    }

    private String computeTabSuffix(Player player) {
        return renderForPlayer(player, config.tabSuffixFormat(), config.suffixMode());
    }

    private String computeChatPrefix(Player player) {
        return renderForPlayer(player, config.chatPrefixFormat(), config.prefixMode());
    }

    private String computeChatSuffix(Player player) {
        return renderForPlayer(player, config.chatSuffixFormat(), config.suffixMode());
    }

    /** Render a format string for the player's realm/role, or null if disabled / not in a realm. */
    private String renderForPlayer(Player player, String format, String mode) {
        if (mode.toLowerCase(Locale.ROOT).equals("off")) return null;
        Resident me = store.getResident(player.getUniqueId());
        if (me == null) return null;
        Realm realm = store.getRealm(me.realmId());
        if (realm == null) return null;
        return Text.render(format, Map.of(
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
