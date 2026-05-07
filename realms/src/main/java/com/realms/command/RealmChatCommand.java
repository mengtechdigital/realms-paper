package com.realms.command;

import com.realms.RealmsConfig;
import com.realms.data.Realm;
import com.realms.data.RealmsStore;
import com.realms.data.Resident;
import com.realms.manager.DiplomacyManager;
import com.realms.manager.RealmTitles;
import com.realms.manager.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Members-only realm chat. Optionally also reaches allied realms when the
 * realm-chat.allies-overhear flag is on.
 */
public final class RealmChatCommand implements CommandExecutor {

    private final RealmsConfig config;
    private final RealmsStore store;
    private final DiplomacyManager diplomacy;

    public RealmChatCommand(RealmsConfig config, RealmsStore store, DiplomacyManager diplomacy) {
        this.config = config;
        this.store = store;
        this.diplomacy = diplomacy;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!config.realmChatEnabled()) {
            sender.sendMessage(Text.colorize("&cRealm chat is disabled."));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.colorize("&cOnly players can use realm chat."));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(Text.colorize("&7Usage: /rc <message>"));
            return true;
        }
        Resident me = store.getResident(player.getUniqueId());
        if (me == null) {
            player.sendMessage(Text.colorize("&cYou are not in a realm."));
            return true;
        }
        Realm realm = store.getRealm(me.realmId());
        if (realm == null) {
            player.sendMessage(Text.colorize("&cYou are not in a realm."));
            return true;
        }
        // Strip color codes from the player-supplied message body so they
        // can't inject &c / &l / &r etc. into the rendered chat output.
        // Translate-then-strip removes both &-codes and any pre-translated
        // §-codes; plain '&' (e.g. "Tom & Jerry") survives untouched.
        String rawMessage = String.join(" ", args);
        String safeMessage = ChatColor.stripColor(
                ChatColor.translateAlternateColorCodes('&', rawMessage));
        String formatted = Text.render(config.realmChatFormat(), Map.of(
                "role",    RealmTitles.label(store, realm.id(), me.role()),
                "name",    player.getName(),
                "realm",   realm.name(),
                "message", safeMessage
        ));

        Set<UUID> recipients = new HashSet<>();
        for (Resident r : store.residentsOf(realm.id())) recipients.add(r.uuid());
        if (config.realmChatAlliesOverhear()) {
            for (Realm ally : diplomacy.allies(realm.id())) {
                for (Resident r : store.residentsOf(ally.id())) recipients.add(r.uuid());
            }
        }
        for (UUID id : recipients) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(formatted);
        }
        return true;
    }
}
