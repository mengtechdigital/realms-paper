package com.realms.command;

import com.realms.RealmsConfig;
import com.realms.RealmsPlugin;
import com.realms.data.ClaimKey;
import com.realms.data.NameCache;
import com.realms.data.Realm;
import com.realms.data.RealmsStore;
import com.realms.data.Resident;
import com.realms.data.Role;
import com.realms.manager.AdminBypass;
import com.realms.manager.ClaimManager;
import com.realms.manager.DiplomacyManager;
import com.realms.manager.OverclaimManager;
import com.realms.manager.PowerCalc;
import com.realms.manager.RealmManager;
import com.realms.manager.Result;
import com.realms.manager.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Single dispatcher for /realm. Subcommand parsing is plain switch-case;
 * each case delegates to a manager method or formats a query result.
 *
 * Subcommands are added incrementally per phase. Stubs at the end document
 * the phase that owns each one.
 */
public final class RealmsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "create", "disband", "claim", "unclaim",
            "invite", "join", "leave", "kick",
            "promote", "demote", "transfer",
            "sethome", "home", "spawn",
            "info", "here", "who", "list", "map", "power",
            "top", "leaderboard", "lb",
            "flag",
            "ally", "enemy", "neutral", "allies", "enemies", "relations",
            "overclaim",
            "display", "togglebar", "showclaim", "sc", "visualize",
            "chat",
            "reload",
            "admin",
            "help"
    );

    private final RealmsPlugin plugin;
    private final RealmsConfig config;
    private final RealmsStore store;
    private final NameCache nameCache;
    private final RealmManager realms;
    private final ClaimManager claims;
    private final PowerCalc power;
    private final DiplomacyManager diplomacy;
    private final OverclaimManager overclaim;
    private final AdminBypass adminBypass;

    /** Member-toggleable flags. peaceful is admin-only and lives elsewhere. */
    private static final List<String> MEMBER_FLAGS = Arrays.asList(
            "hostile-spawn", "passive-spawn", "mob-griefing", "pvp");

    public RealmsCommand(RealmsPlugin plugin, RealmsConfig config, RealmsStore store,
                         NameCache nameCache, RealmManager realms, ClaimManager claims,
                         PowerCalc power, DiplomacyManager diplomacy, OverclaimManager overclaim,
                         AdminBypass adminBypass) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.nameCache = nameCache;
        this.realms = realms;
        this.claims = claims;
        this.power = power;
        this.diplomacy = diplomacy;
        this.overclaim = overclaim;
        this.adminBypass = adminBypass;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { sendHelp(sender); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);

        // Console-allowed commands first.
        switch (sub) {
            case "help" -> { sendHelp(sender); return true; }
            case "list" -> { runList(sender); return true; }
            case "info" -> { runInfo(sender, args); return true; }
            case "top", "leaderboard", "lb" -> { runTop(sender, args); return true; }
            case "reload" -> { return runReload(sender); }
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("errors.console-only", "&cOnly players can run that."));
            return true;
        }

        switch (sub) {
            case "create" -> runCreate(player, args);
            case "disband" -> runDisband(player, args);
            case "claim" -> runClaim(player, args);
            case "unclaim" -> runUnclaim(player);
            case "invite" -> runInvite(player, args);
            case "join" -> runJoin(player, args);
            case "leave" -> runLeave(player);
            case "kick" -> runKick(player, args);
            case "promote" -> runPromote(player, args);
            case "demote" -> runDemote(player, args);
            case "transfer" -> runTransfer(player, args);
            case "here" -> runHere(player);
            case "who" -> runWho(player, args);
            case "power" -> runPower(player);
            case "ally" -> runAlly(player, args);
            case "enemy" -> runEnemy(player, args);
            case "neutral" -> runNeutral(player, args);
            case "allies" -> runAllies(player);
            case "enemies" -> runEnemies(player);
            case "relations" -> runRelations(player);
            case "overclaim" -> deliver(player, overclaim.start(player));
            case "flag" -> runFlag(player, args);
            case "admin" -> runAdmin(player, args);
            // Phase 8+: display / chat / home / map
            case "sethome", "home", "spawn",
                 "map",
                 "display", "togglebar", "showclaim", "sc", "visualize",
                 "chat"
                 -> player.sendMessage(Text.colorize("&7(Subcommand &e/" + label + " " + sub + "&7 lands in a later phase.)"));
            default -> player.sendMessage(msg("errors.unknown-subcommand",
                    "&cUnknown subcommand. Try &e/realm help&c."));
        }
        return true;
    }

    // ---- Subcommand handlers ---------------------------------------------

    private void runCreate(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(Text.colorize("&7Usage: /realm create <name>")); return; }
        deliver(player, realms.createRealm(player, args[1]));
    }

    private void runDisband(Player player, String[] args) {
        boolean confirm = args.length >= 2 && args[1].equalsIgnoreCase("confirm");
        deliver(player, realms.disband(player, confirm));
    }

    private void runClaim(Player player, String[] args) {
        int diameter = 1;
        boolean confirm = false;
        if (args.length >= 2) {
            try { diameter = Integer.parseInt(args[1]); }
            catch (NumberFormatException e) {
                player.sendMessage(Text.colorize("&7Usage: /realm claim [diameter] [confirm]"));
                return;
            }
        }
        if (args.length >= 3 && args[2].equalsIgnoreCase("confirm")) confirm = true;
        deliver(player, claims.claim(player, diameter, confirm));
    }

    private void runUnclaim(Player player) {
        Result r = claims.unclaim(player);
        deliver(player, r);
        // unclaim manager signals home-cleared via a placeholder so the user
        // knows their home was wiped out alongside the chunk.
        if (r.ok() && "1".equals(r.placeholders().get("home-cleared"))) {
            player.sendMessage(msg("info.unclaim-cleared-home",
                    "&eRealm home was in that chunk and has been cleared."));
        }
    }

    private void runInvite(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(Text.colorize("&7Usage: /realm invite <player>")); return; }
        deliver(player, realms.invite(player, args[1]));
    }

    private void runJoin(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(Text.colorize("&7Usage: /realm join <realm>")); return; }
        deliver(player, realms.join(player, args[1]));
    }

    private void runLeave(Player player) { deliver(player, realms.leave(player)); }

    private void runKick(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(Text.colorize("&7Usage: /realm kick <player>")); return; }
        deliver(player, realms.kick(player, args[1]));
    }

    private void runPromote(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(Text.colorize("&7Usage: /realm promote <player>")); return; }
        deliver(player, realms.promote(player, args[1]));
    }

    private void runDemote(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(Text.colorize("&7Usage: /realm demote <player>")); return; }
        deliver(player, realms.demote(player, args[1]));
    }

    private void runTransfer(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(Text.colorize("&7Usage: /realm transfer <player>")); return; }
        deliver(player, realms.transferMayorship(player, args[1]));
    }

    private void runHere(Player player) {
        ClaimKey k = ClaimKey.of(player.getLocation());
        Long owner = store.claimOwner(k);
        if (owner == null) {
            player.sendMessage(Text.colorize("&7Wilderness &8(" + k.world() + " " + k.chunkX() + "," + k.chunkZ() + ")"));
            return;
        }
        Realm realm = store.getRealm(owner);
        if (realm == null) {
            player.sendMessage(Text.colorize("&cClaim ownership orphaned (no realm row). Tell an op."));
            return;
        }
        player.sendMessage(Text.colorize(
                "&6" + realm.name() + "&7 — chunk (" + k.chunkX() + "," + k.chunkZ() + ") in " + k.world()
                        + (realm.peaceful() ? " &6[Peaceful]" : "")));
    }

    private void runWho(Player player, String[] args) {
        Realm realm;
        if (args.length >= 2) {
            realm = store.getRealmByName(args[1]);
            if (realm == null) {
                deliver(player, Result.fail("errors.realm-not-found", Map.of("realm", args[1])));
                return;
            }
        } else {
            Resident me = store.getResident(player.getUniqueId());
            if (me == null) { deliver(player, Result.fail("errors.not-in-realm")); return; }
            realm = store.getRealm(me.realmId());
        }
        if (realm == null) { deliver(player, Result.fail("errors.not-in-realm")); return; }
        List<Resident> members = store.residentsOf(realm.id());
        StringBuilder sb = new StringBuilder();
        sb.append(Text.colorize("&6" + realm.name() + "&7 (" + members.size() + " members)\n"));
        members.stream()
                .sorted((a, b) -> Integer.compare(a.role().ordinal(), b.role().ordinal()))
                .forEach(r -> sb.append(Text.colorize("  &7- "
                        + roleColor(r.role()) + r.role().name().toLowerCase(Locale.ROOT)
                        + " &f" + nameCache.getOr(r.uuid(), "?") + "\n")));
        player.sendMessage(sb.toString().stripTrailing());
    }

    private void runList(CommandSender sender) {
        var all = new ArrayList<>(store.allRealms());
        if (all.isEmpty()) {
            sender.sendMessage(Text.colorize("&7No realms exist yet."));
            return;
        }
        all.sort((a, b) -> Long.compare(b.cachedPower(), a.cachedPower()));
        StringBuilder sb = new StringBuilder(Text.colorize("&6Realms &7(" + all.size() + ")\n"));
        for (Realm r : all) {
            sb.append(Text.colorize("  &7- &6" + r.name()
                    + " &7[" + store.residentCount(r.id()) + " members, "
                    + store.claimCount(r.id()) + " chunks, " + r.cachedPower() + " power]"
                    + (r.peaceful() ? " &6[Peaceful]" : "") + "\n"));
        }
        sender.sendMessage(sb.toString().stripTrailing());
    }

    private void runInfo(CommandSender sender, String[] args) {
        Realm realm;
        if (args.length >= 2) {
            realm = store.getRealmByName(args[1]);
            if (realm == null) {
                sender.sendMessage(Text.colorize(Text.render(
                        config.message("errors.realm-not-found", "&cNo realm named &e{realm}&c."),
                        Map.of("realm", args[1]))));
                return;
            }
        } else if (sender instanceof Player p) {
            Resident me = store.getResident(p.getUniqueId());
            if (me == null) {
                sender.sendMessage(Text.colorize(config.message("errors.not-in-realm",
                        "&cYou are not part of any realm.")));
                return;
            }
            realm = store.getRealm(me.realmId());
        } else {
            sender.sendMessage(Text.colorize("&7Usage: /realm info <name>"));
            return;
        }
        if (realm == null) return;
        sender.sendMessage(Text.colorize(
                "&6" + realm.name() + " &7— founded by " + nameCache.getOr(realm.founder(), "?") +
                "\n  &7Members: &f" + store.residentCount(realm.id()) +
                "\n  &7Chunks:  &f" + store.claimCount(realm.id()) +
                "\n  &7Power:   &f" + realm.cachedPower() +
                (realm.peaceful() ? "\n  &6[Peaceful]" : "") +
                (realm.hasHome() ? "\n  &7Home:    &f" + realm.homeWorld() +
                        " (" + (int) (double) realm.homeX() + "," + (int) (double) realm.homeY() + ","
                        + (int) (double) realm.homeZ() + ")" : "")
        ));
    }

    private void runPower(Player player) {
        Resident me = store.getResident(player.getUniqueId());
        if (me == null) { deliver(player, Result.fail("errors.not-in-realm")); return; }
        Realm realm = store.getRealm(me.realmId());
        if (realm == null) { deliver(player, Result.fail("errors.not-in-realm")); return; }
        // Snapshot counts once — calling residentCount / claimCount twice in
        // the format string and the math could disagree if a member joins or
        // a claim is added between the two reads.
        int memberCount = store.residentCount(realm.id());
        int chunkCount  = store.claimCount(realm.id());
        long base = power.basePower();
        long memberBonus = power.memberPower(memberCount);
        long ledger = power.ledgerPower(realm.id());
        long total = base + memberBonus + ledger;
        long cost = power.claimCost(chunkCount);
        long spare = total - cost;
        String weakened = spare < 0 ? " &c[Weakened]" : "";
        player.sendMessage(Text.colorize(
                "&6" + realm.name() + " &7— power breakdown\n" +
                "  &7Base:        &f" + base + "\n" +
                "  &7Members:     &f" + memberBonus + " &8(" + memberCount + " × " + config.perMemberPower() + ")\n" +
                "  &7Power blocks:&f " + ledger + "\n" +
                "  &7---\n" +
                "  &7Capacity:    &a" + total + "\n" +
                "  &7Claimed:     &e" + cost + " &8(" + chunkCount + " chunks × " + config.costPerChunk() + ")\n" +
                "  &7Spare:       &b" + spare + weakened
        ));
    }

    private void runTop(CommandSender sender, String[] args) {
        String sortMode = "power";
        int page = 1;
        if (args.length >= 2) {
            String arg = args[1].toLowerCase(Locale.ROOT);
            if (arg.equals("power") || arg.equals("members") || arg.equals("chunks") || arg.equals("age")) {
                sortMode = arg;
                if (args.length >= 3) {
                    try { page = Math.max(1, Integer.parseInt(args[2])); } catch (NumberFormatException ignored) {}
                }
            } else {
                try { page = Math.max(1, Integer.parseInt(arg)); } catch (NumberFormatException ignored) {}
            }
        }

        // Snapshot member/chunk counts up front — TimSort requires a
        // consistent comparator across the whole sort. Live counts could
        // drift mid-sort if a player joins/claims, producing wrong order
        // and (in rare cases) IllegalArgumentException from TimSort.
        record TopRow(Realm realm, int members, int chunks) {}
        List<TopRow> rows = new ArrayList<>();
        for (Realm r : store.allRealms()) {
            rows.add(new TopRow(r, store.residentCount(r.id()), store.claimCount(r.id())));
        }
        java.util.Comparator<TopRow> cmp = switch (sortMode) {
            case "members" -> java.util.Comparator.comparingInt(TopRow::members).reversed();
            case "chunks"  -> java.util.Comparator.comparingInt(TopRow::chunks).reversed();
            case "age"     -> java.util.Comparator.comparingLong(t -> t.realm().foundedMillis());
            default        -> java.util.Comparator.<TopRow>comparingLong(t -> t.realm().cachedPower()).reversed();
        };
        rows.sort(cmp);

        int perPage = 10;
        int totalPages = Math.max(1, (rows.size() + perPage - 1) / perPage);
        if (page > totalPages) page = totalPages;
        int from = (page - 1) * perPage;
        int to = Math.min(from + perPage, rows.size());

        StringBuilder sb = new StringBuilder();
        sb.append(Text.colorize("&6Realms &7— top by &e" + sortMode
                + " &8(page " + page + "/" + totalPages + ")\n"));
        if (rows.isEmpty()) {
            sb.append(Text.colorize("  &7No realms exist yet."));
            sender.sendMessage(sb.toString());
            return;
        }
        for (int i = from; i < to; i++) {
            TopRow t = rows.get(i);
            Realm r = t.realm();
            sb.append(Text.colorize(String.format(
                    "  &7%2d. &6%s &7— %d power, %d chunks, %d members%s%n",
                    i + 1, r.name(), r.cachedPower(),
                    t.chunks(), t.members(),
                    r.peaceful() ? " &6[Peaceful]" : "")));
        }
        sender.sendMessage(sb.toString().stripTrailing());
    }

    private void runAlly(Player p, String[] args) {
        if (args.length < 2) { p.sendMessage(Text.colorize("&7Usage: /realm ally <realm>")); return; }
        deliver(p, diplomacy.requestAlly(p, args[1]));
    }
    private void runEnemy(Player p, String[] args) {
        if (args.length < 2) { p.sendMessage(Text.colorize("&7Usage: /realm enemy <realm>")); return; }
        deliver(p, diplomacy.declareEnemy(p, args[1]));
    }
    private void runNeutral(Player p, String[] args) {
        if (args.length < 2) { p.sendMessage(Text.colorize("&7Usage: /realm neutral <realm>")); return; }
        deliver(p, diplomacy.setNeutral(p, args[1]));
    }
    private void runAllies(Player p) {
        Resident me = store.getResident(p.getUniqueId());
        if (me == null) { deliver(p, Result.fail("errors.not-in-realm")); return; }
        var list = diplomacy.allies(me.realmId());
        if (list.isEmpty()) { p.sendMessage(Text.colorize("&7No allies.")); return; }
        StringBuilder sb = new StringBuilder(Text.colorize("&bAllies:\n"));
        for (Realm r : list) sb.append(Text.colorize("  &b- " + r.name() + "\n"));
        p.sendMessage(sb.toString().stripTrailing());
    }
    private void runEnemies(Player p) {
        Resident me = store.getResident(p.getUniqueId());
        if (me == null) { deliver(p, Result.fail("errors.not-in-realm")); return; }
        var list = diplomacy.enemies(me.realmId());
        if (list.isEmpty()) { p.sendMessage(Text.colorize("&7No enemies.")); return; }
        StringBuilder sb = new StringBuilder(Text.colorize("&cEnemies:\n"));
        for (Realm r : list) sb.append(Text.colorize("  &c- " + r.name()
                + (r.peaceful() ? " &6[Peaceful]" : "") + "\n"));
        p.sendMessage(sb.toString().stripTrailing());
    }
    private void runRelations(Player p) {
        Resident me = store.getResident(p.getUniqueId());
        if (me == null) { deliver(p, Result.fail("errors.not-in-realm")); return; }
        StringBuilder sb = new StringBuilder(Text.colorize("&6Relations\n"));
        var allies = diplomacy.allies(me.realmId());
        var enemies = diplomacy.enemies(me.realmId());
        if (allies.isEmpty() && enemies.isEmpty()) {
            sb.append(Text.colorize("  &7At peace with the world."));
        } else {
            for (Realm r : allies)  sb.append(Text.colorize("  &b- ALLY  &f" + r.name() + "\n"));
            for (Realm r : enemies) sb.append(Text.colorize("  &c- ENEMY &f" + r.name()
                    + (r.peaceful() ? " &6[Peaceful]" : "") + "\n"));
        }
        p.sendMessage(sb.toString().stripTrailing());
    }

    private void runFlag(Player player, String[] args) {
        Resident me = store.getResident(player.getUniqueId());
        if (me == null) { deliver(player, Result.fail("errors.not-in-realm")); return; }
        Realm realm = store.getRealm(me.realmId());
        if (realm == null) { deliver(player, Result.fail("errors.not-in-realm")); return; }

        if (args.length < 2) {
            // List current values for member-toggleable flags + show peaceful state.
            StringBuilder sb = new StringBuilder(Text.colorize(
                    "&6Flags for " + realm.name() + "\n"));
            for (String f : MEMBER_FLAGS) {
                boolean v = store.getFlag(realm.id(), f, config.flagDefault(f, defaultFor(f)));
                sb.append(Text.colorize("  &7- &e" + f + ": &f" + (v ? "on" : "off") + "\n"));
            }
            sb.append(Text.colorize("  &7- &epeaceful&7 (op-only): &f"
                    + (realm.peaceful() ? "on" : "off")));
            player.sendMessage(sb.toString());
            return;
        }
        if (!me.role().canManage()) {
            deliver(player, Result.fail("errors.not-mayor-or-assistant"));
            return;
        }
        String flag = args[1].toLowerCase(Locale.ROOT);
        if (flag.equals("peaceful")) {
            deliver(player, Result.fail("errors.flag-op-only"));
            return;
        }
        if (!MEMBER_FLAGS.contains(flag)) {
            deliver(player, Result.fail("errors.invalid-flag", Map.of(
                    "flag", flag, "list", String.join(", ", MEMBER_FLAGS))));
            return;
        }
        if (args.length < 3) {
            player.sendMessage(Text.colorize("&7Usage: /realm flag " + flag + " <on|off>"));
            return;
        }
        boolean on = args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true")
                || args[2].equalsIgnoreCase("yes");
        // Block pvp=on writes while peaceful. Peaceful overrides anyway in
        // ClaimAccess.canPvp, but persisting pvp=true would: (1) display a
        // misleading "pvp: on" in /realm flag, (2) outlive the peaceful
        // toggle if an admin later flips peaceful off, leaking state.
        if (flag.equals("pvp") && on && realm.peaceful()) {
            player.sendMessage(Text.colorize(
                    "&cCannot enable PvP while realm is peaceful. Ask an op to flip peaceful first."));
            return;
        }
        store.setFlag(realm.id(), flag, on);
        deliver(player, Result.ok("info.flag-set", Map.of(
                "flag", flag, "value", on ? "on" : "off")));
    }

    private void runAdmin(Player player, String[] args) {
        if (!player.hasPermission("realms.admin")) {
            deliver(player, Result.fail("errors.no-permission"));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Text.colorize(
                    "&7Usage: /realm admin <peaceful|bypass|delete|unclaim>"));
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "peaceful" -> runAdminPeaceful(player, args);
            case "bypass" -> runAdminBypass(player);
            default -> player.sendMessage(Text.colorize(
                    "&7Usage: /realm admin <peaceful|bypass|delete|unclaim>"));
        }
    }

    private void runAdminPeaceful(Player player, String[] args) {
        if (args.length < 4) {
            player.sendMessage(Text.colorize(
                    "&7Usage: /realm admin peaceful <realm> <on|off>"));
            return;
        }
        Realm realm = store.getRealmByName(args[2]);
        if (realm == null) {
            deliver(player, Result.fail("errors.realm-not-found", Map.of("realm", args[2])));
            return;
        }
        boolean on = args[3].equalsIgnoreCase("on") || args[3].equalsIgnoreCase("true");
        store.updateRealm(realm.withPeaceful(on));
        // Forced-off pvp on peaceful → make the visible flag match the de-facto state.
        if (on) store.setFlag(realm.id(), "pvp", false);
        player.sendMessage(Text.colorize("&aRealm &6" + realm.name()
                + "&a peaceful → &e" + (on ? "on" : "off")));
    }

    private void runAdminBypass(Player player) {
        boolean nowOn = adminBypass.toggle(player.getUniqueId());
        player.sendMessage(Text.colorize(nowOn
                ? "&aAdmin bypass &eON&a — claim protection treats you as a member of every realm."
                : "&aAdmin bypass &eOFF&a."));
    }

    /** Default for member-toggleable flags (only consulted if config didn't set one). */
    private static boolean defaultFor(String flag) {
        return switch (flag) {
            case "hostile-spawn", "passive-spawn", "pvp" -> true;
            case "mob-griefing" -> false;
            default -> false;
        };
    }

    private boolean runReload(CommandSender sender) {
        if (!sender.hasPermission("realms.admin")) {
            sender.sendMessage(msg("errors.no-permission", "&cYou don't have permission for that."));
            return true;
        }
        plugin.getRealmsConfig().load(plugin);
        sender.sendMessage(msg("info.reload-done", "&aRealms config + messages reloaded."));
        return true;
    }

    // ---- Helpers ----------------------------------------------------------

    private void deliver(CommandSender to, Result result) {
        String tpl = config.message(result.messageKey(),
                "&7" + result.messageKey() + " " + result.placeholders());
        to.sendMessage(Text.render(tpl, result.placeholders()));
    }

    private String msg(String key, String fallback) {
        return Text.colorize(config.message(key, fallback));
    }

    private String roleColor(Role role) {
        return switch (role) {
            case MAYOR -> "&6";
            case ASSISTANT -> "&e";
            case RESIDENT -> "&7";
        };
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Text.colorize("&6Realms &7— commands"));
        sender.sendMessage(Text.colorize("  &e/realm create <name> &7— found a realm here"));
        sender.sendMessage(Text.colorize("  &e/realm claim [N] &7— claim N×N (odd, ≤ "
                + config.maxClaimDiameter() + ")"));
        sender.sendMessage(Text.colorize("  &e/realm unclaim &7— release current chunk"));
        sender.sendMessage(Text.colorize("  &e/realm invite|join|leave|kick &7— membership"));
        sender.sendMessage(Text.colorize("  &e/realm info|here|who|list|power|map &7— info"));
        sender.sendMessage(Text.colorize("  &e/realm top [power|members|chunks|age] &7— leaderboard"));
        sender.sendMessage(Text.colorize("  &e/realm ally|enemy|neutral &7— diplomacy (later phase)"));
        sender.sendMessage(Text.colorize("  &e/realm flag|home|sethome &7— config (later phase)"));
        sender.sendMessage(Text.colorize("  &e/realm reload &7— reload configs (op)"));
    }

    // ---- Tab completion ---------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(prefix))
                    .sorted()
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            return switch (sub) {
                case "join", "info", "who", "ally", "enemy", "neutral" ->
                        store.allRealms().stream().map(Realm::name).sorted().toList();
                case "invite", "kick", "promote", "demote", "transfer" ->
                        Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList();
                case "claim" -> List.of("1", "3", "5", "7");
                case "top", "leaderboard", "lb" -> List.of("power", "members", "chunks", "age");
                case "flag" -> MEMBER_FLAGS;
                case "admin" -> List.of("peaceful", "bypass");
                default -> Collections.emptyList();
            };
        }
        return Collections.emptyList();
    }
}
