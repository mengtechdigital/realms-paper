package com.realms.integration;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PrefixNode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Bridges realm prefixes into LuckPerms via a transient PrefixNode.
 *
 * Crucial difference from marriage-paper: realm prefixes use a LOW weight
 * (default 100, configurable) so admin / staff prefixes at typical weights
 * 500+ stay on top in %luckperms_prefix%. Servers that want both visible
 * can wire {@code %luckperms_prefix_500%} (admin) + {@code %luckperms_prefix_100%}
 * (realm) in their chat-plugin templates.
 *
 * Returns {@code null} from {@link #attempt(Plugin, int)} when LuckPerms is
 * not present or its API isn't ready — callers fall back to the chat-event
 * setFormat path.
 */
public final class LuckPermsHook {

    private final LuckPerms api;
    private final int weight;
    /** Last node we applied per player, for clean removal on refresh. */
    private final Map<UUID, Node> applied = new ConcurrentHashMap<>();

    private LuckPermsHook(LuckPerms api, int weight) {
        this.api = api;
        this.weight = weight;
    }

    public static LuckPermsHook attempt(Plugin plugin, int weight) {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) return null;
        try {
            LuckPerms api = LuckPermsProvider.get();
            plugin.getLogger().info(
                    "LuckPerms detected — realm prefixes will be applied as transient meta nodes (weight "
                    + weight + ").");
            return new LuckPermsHook(api, weight);
        } catch (IllegalStateException e) {
            plugin.getLogger().log(Level.WARNING,
                    "LuckPerms is installed but its API isn't ready yet — falling back to chat-event prefix.", e);
            return null;
        }
    }

    public void apply(Player player, String coloredPrefix) {
        UUID id = player.getUniqueId();
        User user = api.getUserManager().getUser(id);
        if (user != null) { applyTo(user, id, coloredPrefix); return; }
        api.getUserManager().loadUser(id).thenAccept(loaded -> {
            // Player may have left during the async load — bail rather than
            // pinning a transient node onto an orphaned User.
            if (Bukkit.getPlayer(id) == null) return;
            applyTo(loaded, id, coloredPrefix);
        });
    }

    public void clear(Player player) {
        UUID id = player.getUniqueId();
        User user = api.getUserManager().getUser(id);
        if (user == null) { applied.remove(id); return; }
        applyTo(user, id, null);
    }

    /**
     * Per-key locked add/remove so concurrent refreshes (main-thread realm
     * change + async loadUser callback for the same player) can't leak a
     * stale node.
     */
    private void applyTo(User user, UUID id, String coloredPrefix) {
        applied.compute(id, (k, prev) -> {
            if (prev != null) user.transientData().remove(prev);
            if (coloredPrefix == null || coloredPrefix.isEmpty()) return null;
            Node node = PrefixNode.builder(coloredPrefix, weight).build();
            user.transientData().add(node);
            return node;
        });
    }
}
