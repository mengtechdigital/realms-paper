package com.realms.command;

import com.realms.RealmsConfig;
import com.realms.RealmsPlugin;
import com.realms.data.ClaimKey;
import com.realms.data.NameCache;
import com.realms.data.Realm;
import com.realms.data.DisplayPrefs;
import com.realms.data.RealmsStore;
import com.realms.data.Resident;
import com.realms.data.Role;
import com.realms.display.DisplayPrefsManager;
import com.realms.display.Palette;
import com.realms.display.PowerBlocksGui;
import com.realms.display.ShowClaimManager;
import com.realms.manager.AdminBypass;
import com.realms.manager.AdminZoneManager;
import com.realms.manager.ClaimManager;
import com.realms.manager.DiplomacyManager;
import com.realms.manager.HomeManager;
import com.realms.manager.OverclaimManager;
import com.realms.manager.PowerCalc;
import com.realms.manager.RealmManager;
import com.realms.manager.RealmTitles;
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
            "create", "disband", "rename", "claim", "unclaim",
            "invite", "join", "leave", "kick",
            "promote", "demote", "transfer",
            "title",
            "sethome", "home", "spawn", "delhome", "homes",
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

    /** Roles that can be customised via /realm title. */
    private static final List<String> TITLE_ROLES =
            Arrays.asList("mayor", "assistant", "resident");

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
    private final HomeManager home;
    private final DisplayPrefsManager displayPrefs;
    private final ShowClaimManager showClaim;
    private final Palette palette;
    private final AdminZoneManager adminZones;
    private final PowerBlocksGui powerBlocksGui;

    /** Member-toggleable flags. peaceful is admin-only and lives elsewhere. */
    private static final List<String> MEMBER_FLAGS = Arrays.asList(
            "hostile-spawn", "passive-spawn", "mob-griefing", "pvp");

    public RealmsCommand(RealmsPlugin plugin, RealmsConfig config, RealmsStore store,
                         NameCache nameCache, RealmManager realms, ClaimManager claims,
                         PowerCalc power, DiplomacyManager diplomacy, OverclaimManager overclaim,
                         AdminBypass adminBypass, HomeManager home,
                         DisplayPrefsManager displayPrefs, ShowClaimManager showClaim,
                         Palette palette, AdminZoneManager adminZones,
                         PowerBlocksGui powerBlocksGui) {
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
        this.home = home;
        this.displayPrefs = displayPrefs;
        this.showClaim = showClaim;
        this.palette = palette;
        this.adminZones = adminZones;
        this.powerBlocksGui = powerBlocksGui;
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
            case "power" -> {
                // /realm power blocks: GUI for players, chat list for console.
                if (args.length >= 2) {
                    String s = args[1].toLowerCase(Locale.ROOT);
                    if (s.equals("blocks") || s.equals("values") || s.equals("table")) {
                        if (sender instanceof Player p) powerBlocksGui.open(p);
                        else runPowerBlocks(sender);
                        return true;
                    }
                }
            }
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("errors.console-only", "&cOnly players can run that."));
            return true;
        }

        switch (sub) {
            case "create" -> runCreate(player, args);
            case "disband" -> runDisband(player, args);
            case "rename" -> runRename(player, args);
            case "claim" -> runClaim(player, args);
            case "unclaim" -> runUnclaim(player);
            case "invite" -> runInvite(player, args);
            case "join" -> runJoin(player, args);
            case "leave" -> runLeave(player);
            case "kick" -> runKick(player, args);
            case "promote" -> runPromote(player, args);
            case "demote" -> runDemote(player, args);
            case "transfer" -> runTransfer(player, args);
            case "title" -> runTitle(player, args);
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
            case "sethome" -> runSetHome(player, args);
            case "home", "spawn" -> runHome(player, args);
            case "delhome" -> runDelHome(player, args);
            case "homes" -> runHomes(player);
            case "map" -> runMap(player);
            case "display" -> runDisplay(player, args);
            case "togglebar" -> runToggleBar(player);
            case "showclaim", "sc", "visualize" -> runShowClaim(player, args);
            case "chat" -> player.sendMessage(Text.colorize(
                    "&7Realm chat lands in phase 9. Use &e/rc &7there."));
            default -> player.sendMessage(msg("errors.unknown-subcommand",
                    "&cUnknown subcommand. Try &e/realm help&c."));
        }
        return true;
    }

    // ---- Home subcommands ------------------------------------------------

    private void runSetHome(Player player, String[] args) {
        if (args.length >= 2) {
            deliver(player, home.setHome(player, args[1]));
            return;
        }
        deliver(player, home.setHome(player));
    }

    private void runHome(Player player, String[] args) {
        if (args.length >= 2) {
            deliver(player, home.home(player, args[1]));
            return;
        }
        deliver(player, home.home(player));
    }

    private void runDelHome(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Text.colorize("&7Usage: /realm delhome <name>"));
            return;
        }
        deliver(player, home.deleteHome(player, args[1]));
    }

    private void runHomes(Player player) {
        Resident me = store.getResident(player.getUniqueId());
        if (me == null) { deliver(player, Result.fail("errors.not-in-realm")); return; }
        Realm realm = store.getRealm(me.realmId());
        if (realm == null) { deliver(player, Result.fail("errors.not-in-realm")); return; }

        java.util.Map<String, org.bukkit.Location> homes = home.listHomes(player);
        int max = home.maxNamedSlots(realm);
        int namedUsed = store.namedHomeCount(realm.id());
        boolean disabled = config.homePowerPerSlot() <= 0;
        // Distinguish "feature off" from "no slots earned yet" — otherwise a
        // realm with stale named homes from a previous config sees "n/0",
        // which reads like a violation.
        String header = disabled
                ? "&6" + realm.name() + " &7— homes &8(named homes disabled by server)"
                : "&6" + realm.name() + " &7— homes (" + namedUsed + "/" + max + " named slots used)";
        StringBuilder sb = new StringBuilder(Text.colorize(header));
        if (homes.isEmpty()) {
            sb.append(Text.colorize("\n  &7No homes set. Mayor: &e/realm sethome [name]&7."));
        } else {
            // Default first, then named alphabetically.
            org.bukkit.Location def = homes.get("default");
            if (def != null) {
                sb.append(Text.colorize("\n  &7- &edefault &8→ &f"
                        + (def.getWorld() == null ? "?" : def.getWorld().getName())
                        + " (" + def.getBlockX() + "," + def.getBlockY() + "," + def.getBlockZ() + ")"));
            }
            homes.entrySet().stream()
                    .filter(e -> !"default".equals(e.getKey()))
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(e -> {
                        org.bukkit.Location l = e.getValue();
                        sb.append(Text.colorize("\n  &7- &b" + e.getKey() + " &8→ &f"
                                + (l.getWorld() == null ? "?" : l.getWorld().getName())
                                + " (" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ() + ")"));
                    });
        }
        if (!disabled && namedUsed < max) {
            sb.append(Text.colorize("\n  &8(" + (max - namedUsed) + " more named slot(s) available)"));
        }
        player.sendMessage(sb.toString());
    }

    // ---- Rename / title --------------------------------------------------

    private void runRename(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Text.colorize("&7Usage: /realm rename <new-name>"));
            return;
        }
        deliver(player, realms.rename(player, args[1]));
    }

    private void runTitle(Player player, String[] args) {
        Resident me = store.getResident(player.getUniqueId());
        if (me == null) { deliver(player, Result.fail("errors.not-in-realm")); return; }
        Realm realm = store.getRealm(me.realmId());
        if (realm == null) { deliver(player, Result.fail("errors.not-in-realm")); return; }

        if (args.length < 2) {
            // List current titles + the default for any role without an override.
            StringBuilder sb = new StringBuilder(Text.colorize(
                    "&6" + realm.name() + " &7— role titles"));
            for (Role role : Role.values()) {
                String override = store.titleFor(realm.id(), role);
                String def = com.realms.manager.RealmTitles.defaultLabel(role);
                if (override == null) {
                    sb.append(Text.colorize("\n  &7- &e"
                            + role.name().toLowerCase(Locale.ROOT)
                            + " &8→ &f" + def + " &8(default)"));
                } else {
                    sb.append(Text.colorize("\n  &7- &e"
                            + role.name().toLowerCase(Locale.ROOT)
                            + " &8→ &b" + override
                            + " &8(was " + def + ")"));
                }
            }
            sb.append(Text.colorize(
                    "\n  &7Mayor: &e/realm title <role> <title|reset>&7."));
            player.sendMessage(sb.toString());
            return;
        }
        String roleArg = args[1].toLowerCase(Locale.ROOT);
        Role role;
        try { role = Role.valueOf(roleArg.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) {
            deliver(player, Result.fail("errors.title-invalid-role"));
            return;
        }
        if (args.length < 3) {
            player.sendMessage(Text.colorize(
                    "&7Usage: /realm title " + roleArg + " <title|reset>"));
            return;
        }
        // Join args[2..] so multi-word titles like "Land Holder" work.
        String title = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        deliver(player, realms.setTitle(player, role, title));
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
        // Same for any named homes that lived in the unclaimed chunk.
        String namedCleared = r.placeholders().get("named-cleared");
        if (r.ok() && namedCleared != null && !"0".equals(namedCleared)) {
            player.sendMessage(Text.render(
                    config.message("info.unclaim-cleared-named-homes",
                            "&e{n} named realm home(s) were in that chunk and have been cleared."),
                    Map.of("n", namedCleared)));
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
        Palette.Relation rel = palette.relationFor(player, owner);
        String tag = "";
        if (realm.isAdminZone()) {
            tag = " &7[" + realm.zoneType().name().toLowerCase(Locale.ROOT) + "]";
        } else if (realm.peaceful()) {
            tag = " &6[Peaceful]";
        }
        player.sendMessage(Text.colorize(
                palette.code(rel) + realm.name() + "&7 — chunk ("
                        + k.chunkX() + "," + k.chunkZ() + ") in " + k.world() + tag));
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
        long realmId = realm.id();
        members.stream()
                .sorted((a, b) -> Integer.compare(a.role().ordinal(), b.role().ordinal()))
                .forEach(r -> sb.append(Text.colorize("  &7- "
                        + roleColor(r.role())
                        + RealmTitles.label(store, realmId, r.role())
                        + " &f" + nameCache.getOrLookup(r.uuid(), "?") + "\n")));
        player.sendMessage(sb.toString().stripTrailing());
    }

    private void runList(CommandSender sender) {
        var all = new ArrayList<>(store.allRealms());
        if (all.isEmpty()) {
            sender.sendMessage(Text.colorize("&7No realms exist yet."));
            return;
        }
        // Sort: player realms first by power desc, admin zones last by name.
        all.sort((a, b) -> {
            if (a.isAdminZone() != b.isAdminZone()) return a.isAdminZone() ? 1 : -1;
            if (a.isAdminZone()) return a.name().compareToIgnoreCase(b.name());
            return Long.compare(b.cachedPower(), a.cachedPower());
        });
        StringBuilder sb = new StringBuilder(Text.colorize("&6Realms &7(" + all.size() + ")\n"));
        for (Realm r : all) {
            if (r.isAdminZone()) {
                String c = r.zoneType() == com.realms.data.ZoneType.SAFEZONE
                        ? config.colorSafezone() : config.colorWarzone();
                sb.append(Text.colorize("  &7- " + c + r.name()
                        + " &7[" + r.zoneType().name().toLowerCase(Locale.ROOT)
                        + ", " + store.claimCount(r.id()) + " chunks]\n"));
            } else {
                sb.append(Text.colorize("  &7- &6" + r.name()
                        + " &7[" + store.residentCount(r.id()) + " members, "
                        + store.claimCount(r.id()) + " chunks, " + r.cachedPower() + " power]"
                        + (r.peaceful() ? " &6[Peaceful]" : "") + "\n"));
            }
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
        if (realm.isAdminZone()) {
            String c = realm.zoneType() == com.realms.data.ZoneType.SAFEZONE
                    ? config.colorSafezone() : config.colorWarzone();
            sender.sendMessage(Text.colorize(
                    c + realm.name() + " &7— admin zone (&e"
                            + realm.zoneType().name().toLowerCase(Locale.ROOT) + "&7)" +
                    "\n  &7Chunks: &f" + store.claimCount(realm.id()) +
                    "\n  &7No residents, no power, no overclaim."
            ));
            return;
        }
        sender.sendMessage(Text.colorize(
                "&6" + realm.name() + " &7— founded by " + nameCache.getOrLookup(realm.founder(), "?") +
                "\n  &7Members: &f" + store.residentCount(realm.id()) +
                "\n  &7Chunks:  &f" + store.claimCount(realm.id()) +
                "\n  &7Power:   &f" + realm.cachedPower() +
                (realm.peaceful() ? "\n  &6[Peaceful]" : "") +
                (realm.hasHome() ? "\n  &7Home:    &f" + realm.homeWorld() +
                        " (" + (int) (double) realm.homeX() + "," + (int) (double) realm.homeY() + ","
                        + (int) (double) realm.homeZ() + ")" : "")
        ));
    }

    private void runPowerBlocks(CommandSender sender) {
        Map<org.bukkit.Material, Long> values = config.powerValues();
        if (values.isEmpty()) {
            sender.sendMessage(Text.colorize("&7No power blocks configured."));
            return;
        }
        StringBuilder sb = new StringBuilder(Text.colorize(
                "&6Power blocks &7— place these in your claims to grow realm power"));
        values.entrySet().stream()
                .sorted((a, b) -> {
                    int byValue = Long.compare(b.getValue(), a.getValue());
                    if (byValue != 0) return byValue;
                    return a.getKey().name().compareTo(b.getKey().name());
                })
                .forEach(e -> sb.append(Text.colorize(
                        "\n  &7- &e" + e.getKey().name().toLowerCase(Locale.ROOT)
                                + " &8→ &b+" + e.getValue() + " &7power")));
        sb.append(Text.colorize(
                "\n  &8(broken / TNT-mined → power back out; raid drain works the same way)"));
        sender.sendMessage(sb.toString());
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
        // Admin zones live outside the player power economy; excluding them
        // here keeps /realm top a leaderboard of player realms only.
        for (Realm r : store.allRealms()) {
            if (r.isAdminZone()) continue;
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
            case "zone" -> runAdminZone(player, args);
            default -> player.sendMessage(Text.colorize(
                    "&7Usage: /realm admin <peaceful|bypass|zone>"));
        }
    }

    private void runAdminZone(Player player, String[] args) {
        // args: [admin, zone, <op>, ...]
        if (args.length < 3) {
            player.sendMessage(Text.colorize(
                    "&7Usage: /realm admin zone <create|claim|unclaim|delete|list> ..."));
            return;
        }
        String op = args[2].toLowerCase(Locale.ROOT);
        switch (op) {
            case "create" -> {
                if (args.length < 5) {
                    player.sendMessage(Text.colorize(
                            "&7Usage: /realm admin zone create <name> <safezone|warzone>"));
                    return;
                }
                deliver(player, adminZones.create(player, args[3], args[4]));
            }
            case "claim" -> {
                if (args.length < 4) {
                    player.sendMessage(Text.colorize(
                            "&7Usage: /realm admin zone claim <name> [diameter]"));
                    return;
                }
                int diameter = 1;
                if (args.length >= 5) {
                    try { diameter = Integer.parseInt(args[4]); }
                    catch (NumberFormatException e) {
                        player.sendMessage(Text.colorize(
                                "&7Usage: /realm admin zone claim <name> [diameter]"));
                        return;
                    }
                }
                deliver(player, adminZones.claim(player, args[3], diameter));
            }
            case "unclaim" -> deliver(player, adminZones.unclaim(player));
            case "delete" -> {
                if (args.length < 4) {
                    player.sendMessage(Text.colorize(
                            "&7Usage: /realm admin zone delete <name> [confirm]"));
                    return;
                }
                boolean confirm = args.length >= 5 && args[4].equalsIgnoreCase("confirm");
                deliver(player, adminZones.delete(player, args[3], confirm));
            }
            case "list" -> {
                java.util.List<Realm> zones = adminZones.list();
                if (zones.isEmpty()) {
                    player.sendMessage(Text.colorize("&7No admin zones defined."));
                    return;
                }
                StringBuilder sb = new StringBuilder(Text.colorize("&6Admin zones\n"));
                for (Realm z : zones) {
                    String c = palette.code(z.zoneType() == com.realms.data.ZoneType.SAFEZONE
                            ? Palette.Relation.SAFEZONE : Palette.Relation.WARZONE);
                    sb.append(Text.colorize("  " + c + z.name()
                            + " &7[" + z.zoneType().name().toLowerCase(Locale.ROOT)
                            + ", " + store.claimCount(z.id()) + " chunks]\n"));
                }
                player.sendMessage(sb.toString().stripTrailing());
            }
            default -> player.sendMessage(Text.colorize(
                    "&7Usage: /realm admin zone <create|claim|unclaim|delete|list> ..."));
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

    private void runMap(Player player) {
        // 11×11 ASCII grid centered on player's chunk. North is up. The
        // player's chunk is shown as a large highlighted square.
        ClaimKey center = ClaimKey.of(player.getLocation());
        int radius = 5;
        StringBuilder sb = new StringBuilder(Text.colorize(
                "&6Realm map &7— centered on (" + center.chunkX() + ", " + center.chunkZ() + ")\n"));
        for (int dz = -radius; dz <= radius; dz++) {
            StringBuilder row = new StringBuilder();
            for (int dx = -radius; dx <= radius; dx++) {
                ClaimKey k = center.offset(dx, dz);
                Long ownerId = store.claimOwner(k);
                Palette.Relation rel = palette.relationFor(player, ownerId);
                // Wilderness uses '-' rather than '·' (middle dot) — the
                // unicode dot renders ~4px wide in Minecraft's default font
                // while letters are 6px, so wilderness rows compressed
                // visually next to claimed-cell rows.
                String glyph = (dx == 0 && dz == 0) ? "+" : ownerId == null ? "-" : glyphFor(rel);
                row.append(palette.code(rel)).append(glyph);
            }
            sb.append("  ").append(Text.colorize(row.toString())).append('\n');
        }
        sb.append(Text.colorize("&8  - wilderness   "
                + palette.code(Palette.Relation.OWN) + "O&8 own   "
                + palette.code(Palette.Relation.ALLY) + "A&8 ally   "
                + palette.code(Palette.Relation.ENEMY) + "E&8 enemy   "
                + palette.code(Palette.Relation.NEUTRAL) + "N&8 neutral   "
                + palette.code(Palette.Relation.PEACEFUL) + "P&8 peaceful   "
                + palette.code(Palette.Relation.SAFEZONE) + "S&8 safe   "
                + palette.code(Palette.Relation.WARZONE) + "W&8 war"));
        player.sendMessage(sb.toString());
    }

    private static String glyphFor(Palette.Relation rel) {
        return switch (rel) {
            case OWN        -> "O";
            case ALLY       -> "A";
            case ENEMY      -> "E";
            case NEUTRAL    -> "N";
            case PEACEFUL   -> "P";
            case SAFEZONE   -> "S";
            case WARZONE    -> "W";
            case WILDERNESS -> "-";
        };
    }

    private void runDisplay(Player player, String[] args) {
        DisplayPrefs current = displayPrefs.of(player);
        if (args.length < 2) {
            player.sendMessage(Text.colorize(
                    "&6Display preferences\n" +
                    "  &7Title:  &f" + (current.titleOn() ? "on" : "off") + "\n" +
                    "  &7Bar:    &f" + current.barMode().name().toLowerCase(Locale.ROOT) + "\n" +
                    "  &7Sound:  &f" + (current.soundOn() ? "on" : "off") + "\n" +
                    "  &7Usage: &e/realm display <title|bar|sound> <on|off|action|boss>"));
            return;
        }
        String which = args[1].toLowerCase(Locale.ROOT);
        if (args.length < 3) {
            player.sendMessage(Text.colorize("&7Usage: /realm display " + which + " <on|off|action|boss>"));
            return;
        }
        String value = args[2].toLowerCase(Locale.ROOT);
        switch (which) {
            case "title" -> {
                displayPrefs.setTitle(player, isOn(value));
                player.sendMessage(Text.colorize("&aTitle: &e" + (isOn(value) ? "on" : "off")));
            }
            case "sound" -> {
                displayPrefs.setSound(player, isOn(value));
                player.sendMessage(Text.colorize("&aSound: &e" + (isOn(value) ? "on" : "off")));
            }
            case "bar" -> {
                DisplayPrefs.BarMode mode = switch (value) {
                    case "action" -> DisplayPrefs.BarMode.ACTION;
                    case "boss"   -> DisplayPrefs.BarMode.BOSS;
                    case "off"    -> DisplayPrefs.BarMode.OFF;
                    default -> null;
                };
                if (mode == null) {
                    player.sendMessage(Text.colorize("&cUnknown bar mode. Use action|boss|off."));
                    return;
                }
                displayPrefs.setBar(player, mode);
                player.sendMessage(Text.colorize("&aBar: &e" + mode.name().toLowerCase(Locale.ROOT)));
            }
            default -> player.sendMessage(Text.colorize(
                    "&cUnknown setting. Use: title, bar, sound."));
        }
    }

    private void runToggleBar(Player player) {
        DisplayPrefs cur = displayPrefs.of(player);
        DisplayPrefs.BarMode next = switch (cur.barMode()) {
            case ACTION -> DisplayPrefs.BarMode.OFF;
            case OFF    -> DisplayPrefs.BarMode.BOSS;
            case BOSS   -> DisplayPrefs.BarMode.ACTION;
        };
        displayPrefs.setBar(player, next);
        player.sendMessage(Text.colorize("&aTerritory bar: &e"
                + next.name().toLowerCase(Locale.ROOT)));
    }

    private void runShowClaim(Player player, String[] args) {
        ShowClaimManager.Mode mode;
        if (args.length < 2) {
            mode = parseMode(config.seeClaimsDefaultMode());
        } else {
            String v = args[1].toLowerCase(Locale.ROOT);
            mode = switch (v) {
                case "off"    -> ShowClaimManager.Mode.OFF;
                case "line"   -> ShowClaimManager.Mode.LINE;
                case "wall"   -> ShowClaimManager.Mode.WALL;
                case "corner" -> ShowClaimManager.Mode.CORNER;
                default -> null;
            };
            if (mode == null) {
                player.sendMessage(Text.colorize(
                        "&cUnknown mode. Use: line, wall, corner, off."));
                return;
            }
        }
        deliver(player, showClaim.toggle(player, mode));
    }

    private static ShowClaimManager.Mode parseMode(String s) {
        try { return ShowClaimManager.Mode.valueOf(s.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return ShowClaimManager.Mode.LINE; }
    }

    private static boolean isOn(String s) {
        return s.equalsIgnoreCase("on") || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes");
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
        sender.sendMessage(Text.colorize("  &e/realm disband &7— wipe your realm (mayor, confirm)"));
        sender.sendMessage(Text.colorize("  &e/realm claim [N] &7— claim N×N (odd, ≤ "
                + config.maxClaimDiameter() + ")"));
        sender.sendMessage(Text.colorize("  &e/realm unclaim &7— release current chunk"));
        sender.sendMessage(Text.colorize("  &e/realm overclaim &7— take a weakened enemy chunk"));
        sender.sendMessage(Text.colorize("  &e/realm invite|join|leave|kick &7— membership"));
        sender.sendMessage(Text.colorize("  &e/realm promote|demote|transfer &7— role management (mayor)"));
        sender.sendMessage(Text.colorize("  &e/realm sethome [name] &7— set default or named home (mayor)"));
        sender.sendMessage(Text.colorize("  &e/realm home [name] &7— teleport to default or named home"));
        sender.sendMessage(Text.colorize("  &e/realm delhome <name> &7— delete a named home (mayor)"));
        sender.sendMessage(Text.colorize("  &e/realm homes &7— list realm homes"));
        sender.sendMessage(Text.colorize("  &e/realm rename <name> &7— rename your realm (mayor)"));
        sender.sendMessage(Text.colorize("  &e/realm title <role> <text|reset> &7— customise role label (mayor)"));
        sender.sendMessage(Text.colorize("  &e/realm info|here|who|list|power|map &7— info"));
        sender.sendMessage(Text.colorize("  &e/realm power blocks &7— list valuable blocks that grow realm power"));
        sender.sendMessage(Text.colorize("  &e/realm top [power|members|chunks|age] &7— leaderboard"));
        sender.sendMessage(Text.colorize("  &e/realm ally|enemy|neutral &7— diplomacy"));
        sender.sendMessage(Text.colorize("  &e/realm allies|enemies|relations &7— list relations"));
        sender.sendMessage(Text.colorize("  &e/realm flag &7— per-realm flags (mayor/assistant)"));
        sender.sendMessage(Text.colorize("  &e/realm display|togglebar|showclaim &7— display preferences"));
        sender.sendMessage(Text.colorize("  &e/rc <message> &7— realm-only chat"));
        if (sender.hasPermission("realms.admin")) {
            sender.sendMessage(Text.colorize("  &c/realm admin peaceful|bypass|zone &7— admin tools"));
            sender.sendMessage(Text.colorize("  &c/realm reload &7— reload configs"));
        }
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
                case "power" -> List.of("blocks");
                case "flag" -> MEMBER_FLAGS;
                case "admin" -> List.of("peaceful", "bypass", "zone");
                case "display" -> List.of("title", "bar", "sound");
                case "showclaim", "sc", "visualize" -> List.of("line", "wall", "corner", "off");
                case "title" -> TITLE_ROLES;
                case "home", "spawn", "delhome" -> homeNamesFor(sender);
                default -> Collections.emptyList();
            };
        }
        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("title")) return titleSuggestionsFor(sender, args[1]);
        }
        return Collections.emptyList();
    }

    /**
     * Suggest "reset" plus the role's current label (override or default) so
     * a single TAB at position 3 of /realm title gives the player either a
     * clear-back-to-default option or their current title to edit.
     */
    private List<String> titleSuggestionsFor(CommandSender sender, String roleArg) {
        Role role;
        try { role = Role.valueOf(roleArg.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return List.of("reset"); }
        if (!(sender instanceof Player p)) return List.of("reset");
        Resident me = store.getResident(p.getUniqueId());
        if (me == null) return List.of("reset");
        String current = RealmTitles.label(store, me.realmId(), role);
        // Suggest "reset" first (the more common operation) and the current
        // label as a starting point for an edit. Single-token labels work
        // verbatim through TAB; multi-word labels still need manual entry,
        // since Bukkit splits args on whitespace.
        if (current == null || current.isBlank() || current.contains(" ")) {
            return List.of("reset");
        }
        return List.of("reset", current);
    }

    /**
     * Tab-suggest the home names available to {@code sender}'s realm —
     * "default" plus any named homes. Returns empty when the sender isn't
     * in a realm.
     */
    private List<String> homeNamesFor(CommandSender sender) {
        if (!(sender instanceof Player p)) return Collections.emptyList();
        Resident me = store.getResident(p.getUniqueId());
        if (me == null) return Collections.emptyList();
        Realm realm = store.getRealm(me.realmId());
        if (realm == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        if (realm.hasHome()) out.add("default");
        out.addAll(store.namedHomes(realm.id()).keySet());
        Collections.sort(out);
        return out;
    }
}
