package com.realms.integration;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.SuffixNode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Bridges realm prefixes/suffixes into LuckPerms via transient meta nodes.
 *
 * Realm meta uses a LOW weight (configurable, default 100) so admin / staff
 * meta at typical weights 500+ stays on top in %luckperms_prefix% /
 * %luckperms_suffix%. Servers that want both visible can compose them via
 * %luckperms_prefixes% / %luckperms_suffixes% or the realms-specific
 * placeholders %realms_prefix% / %realms_suffix%.
 *
 * Returns {@code null} from {@link #attempt(Plugin, int, int)} when LuckPerms
 * is not present or its API isn't ready — callers fall back to the chat-event
 * setFormat path.
 */
public final class LuckPermsHook {

    private final LuckPerms api;
    private final int prefixWeight;
    private final int suffixWeight;
    /** Last prefix/suffix nodes we applied per player, for clean removal on refresh. */
    private final Map<UUID, Node> appliedPrefix = new ConcurrentHashMap<>();
    private final Map<UUID, Node> appliedSuffix = new ConcurrentHashMap<>();

    private LuckPermsHook(LuckPerms api, int prefixWeight, int suffixWeight) {
        this.api = api;
        this.prefixWeight = prefixWeight;
        this.suffixWeight = suffixWeight;
    }

    public static LuckPermsHook attempt(Plugin plugin, int prefixWeight, int suffixWeight) {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) return null;
        try {
            LuckPerms api = LuckPermsProvider.get();
            plugin.getLogger().info(
                    "LuckPerms detected — realm meta will be applied as transient nodes (prefix weight "
                    + prefixWeight + ", suffix weight " + suffixWeight + ").");
            return new LuckPermsHook(api, prefixWeight, suffixWeight);
        } catch (IllegalStateException e) {
            plugin.getLogger().log(Level.WARNING,
                    "LuckPerms is installed but its API isn't ready yet — falling back to chat-event prefix.", e);
            return null;
        }
    }

    public void applyPrefix(Player player, String coloredPrefix) {
        applyMeta(player, coloredPrefix, true);
    }

    public void clearPrefix(Player player) {
        applyMeta(player, null, true);
    }

    public void applySuffix(Player player, String coloredSuffix) {
        applyMeta(player, coloredSuffix, false);
    }

    public void clearSuffix(Player player) {
        applyMeta(player, null, false);
    }

    /** Clear both — used on quit. */
    public void clearAll(Player player) {
        clearPrefix(player);
        clearSuffix(player);
    }

    private void applyMeta(Player player, String colored, boolean isPrefix) {
        UUID id = player.getUniqueId();
        User user = api.getUserManager().getUser(id);
        if (user != null) { applyTo(user, id, colored, isPrefix); return; }
        api.getUserManager().loadUser(id).thenAccept(loaded -> {
            // Player may have left during the async load — bail rather than
            // pinning a transient node onto an orphaned User.
            if (Bukkit.getPlayer(id) == null) return;
            applyTo(loaded, id, colored, isPrefix);
        });
    }

    /**
     * Per-key locked add/remove so concurrent refreshes (main-thread realm
     * change + async loadUser callback for the same player) can't leak a
     * stale node.
     */
    private void applyTo(User user, UUID id, String colored, boolean isPrefix) {
        Map<UUID, Node> map = isPrefix ? appliedPrefix : appliedSuffix;
        int weight = isPrefix ? prefixWeight : suffixWeight;
        map.compute(id, (k, prev) -> {
            if (prev != null) user.transientData().remove(prev);
            if (colored == null || colored.isEmpty()) return null;
            Node node = isPrefix
                    ? PrefixNode.builder(colored, weight).build()
                    : SuffixNode.builder(colored, weight).build();
            user.transientData().add(node);
            return node;
        });
    }
}
