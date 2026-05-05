package com.realms.integration;

import com.realms.RealmsConfig;
import com.realms.data.Realm;
import com.realms.data.RealmsStore;
import com.realms.data.Resident;
import com.realms.manager.Text;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.Map;

/**
 * Exposes realm membership state to PlaceholderAPI consumers (TAB, scoreboards,
 * chat plugins). Uses the same chat/tab format strings that PrefixUpdater reads
 * from config so the on-screen text stays consistent across surfaces.
 *
 * Placeholders:
 *   %realms_prefix%      → chat-prefix-format with {role}/{realm} substituted
 *   %realms_tab_prefix%  → tab-prefix-format with {realm} substituted
 *   %realms_suffix%      → chat-suffix-format with {role}/{realm} substituted
 *   %realms_tab_suffix%  → tab-suffix-format with {realm} substituted
 *   %realms_realm%       → realm name (empty if wilderness)
 *   %realms_role%        → Mayor / Assistant / Resident (empty if wilderness)
 */
public final class RealmsPapiExpansion extends PlaceholderExpansion {

    private final RealmsConfig config;
    private final RealmsStore store;

    public RealmsPapiExpansion(RealmsConfig config, RealmsStore store) {
        this.config = config;
        this.store = store;
    }

    @Override public String getIdentifier() { return "realms"; }
    @Override public String getAuthor()     { return "Meng Tech LLC"; }
    @Override public String getVersion()    { return "1.0.0"; }
    @Override public boolean persist()      { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) return "";
        Resident me = store.getResident(player.getUniqueId());
        if (me == null) return "";
        Realm realm = store.getRealm(me.realmId());
        if (realm == null) return "";

        Map<String, String> subs = Map.of(
                "realm", realm.name(),
                "role",  roleLabel(me));

        return switch (params.toLowerCase()) {
            case "prefix"     -> Text.colorize(Text.render(config.chatPrefixFormat(), subs));
            case "tab_prefix" -> Text.colorize(Text.render(config.tabPrefixFormat(), subs));
            case "suffix"     -> Text.colorize(Text.render(config.chatSuffixFormat(), subs));
            case "tab_suffix" -> Text.colorize(Text.render(config.tabSuffixFormat(), subs));
            case "realm"      -> realm.name();
            case "role"       -> roleLabel(me);
            default           -> null;
        };
    }

    private static String roleLabel(Resident r) {
        return switch (r.role()) {
            case MAYOR -> "Mayor";
            case ASSISTANT -> "Assistant";
            case RESIDENT -> "Resident";
        };
    }
}
