package com.realms;

import com.realms.command.RealmChatCommand;
import com.realms.command.RealmsCommand;
import com.realms.data.NameCache;
import com.realms.data.RealmsStore;
import com.realms.data.SqliteRealmsStore;
import com.realms.integration.LuckPermsHook;
import com.realms.display.BorderTitleListener;
import com.realms.display.DisplayPrefsManager;
import com.realms.display.DisplayQuitListener;
import com.realms.display.GuiClickListener;
import com.realms.display.Palette;
import com.realms.display.PowerBlocksGui;
import com.realms.display.ShowClaimManager;
import com.realms.display.TerritoryDisplayTask;
import com.realms.listener.ExplosionListener;
import com.realms.listener.MobListener;
import com.realms.listener.OverclaimQuitListener;
import com.realms.listener.PowerLedgerListener;
import com.realms.listener.PrefixUpdater;
import com.realms.listener.ProtectionListener;
import com.realms.manager.AdminBypass;
import com.realms.manager.AdminZoneManager;
import com.realms.manager.AllyProposalStore;
import com.realms.manager.ClaimAccess;
import com.realms.manager.ClaimManager;
import com.realms.manager.ConfirmStore;
import com.realms.manager.DiplomacyManager;
import com.realms.manager.HomeManager;
import com.realms.manager.InviteStore;
import com.realms.manager.OverclaimManager;
import com.realms.manager.PowerCalc;
import com.realms.manager.RealmManager;
import com.realms.manager.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.logging.Level;

public final class RealmsPlugin extends JavaPlugin {

    private RealmsConfig config;
    private SqliteRealmsStore store;
    private NameCache nameCache;
    private InviteStore invites;
    private ConfirmStore confirms;
    private RealmManager realmManager;
    private ClaimManager claimManager;
    private PowerCalc powerCalc;
    private AdminBypass adminBypass;
    private ClaimAccess claimAccess;
    private AllyProposalStore allyProposals;
    private DiplomacyManager diplomacyManager;
    private OverclaimManager overclaimManager;
    private HomeManager homeManager;
    private DisplayPrefsManager displayPrefs;
    private Palette palette;
    private ShowClaimManager showClaimManager;
    private TerritoryDisplayTask territoryTask;
    private LuckPermsHook luckPermsHook;
    private PrefixUpdater prefixUpdater;
    private AdminZoneManager adminZoneManager;

    @Override
    public void onEnable() {
        this.config = new RealmsConfig();
        this.config.load(this);

        File dbFile = new File(getDataFolder(), config.databaseFile());
        this.store = new SqliteRealmsStore(this, dbFile);
        try {
            this.store.open();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to open realms database — disabling plugin", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.nameCache = new NameCache();
        for (Player p : getServer().getOnlinePlayers()) nameCache.remember(p);

        this.invites = new InviteStore();
        this.confirms = new ConfirmStore(config.confirmExpirySeconds());
        this.allyProposals = new AllyProposalStore();
        this.powerCalc = new PowerCalc(config, store);
        this.adminBypass = new AdminBypass();
        this.realmManager = new RealmManager(config, store, nameCache, invites, confirms,
                powerCalc, allyProposals);
        this.claimManager = new ClaimManager(config, store, confirms, powerCalc);
        this.diplomacyManager = new DiplomacyManager(config, store, allyProposals);
        this.claimAccess = new ClaimAccess(config, store, adminBypass, diplomacyManager);
        this.overclaimManager = new OverclaimManager(config, store, powerCalc, diplomacyManager,
                this::broadcastOverclaim);
        // Resolve the Diplomacy ↔ Overclaim cycle: setNeutral aborts attempts.
        diplomacyManager.setOverclaimManager(overclaimManager);
        this.homeManager = new HomeManager(this, config, store);
        this.displayPrefs = new DisplayPrefsManager(store);
        this.palette = new Palette(config, store, diplomacyManager, powerCalc);
        this.showClaimManager = new ShowClaimManager(config, store, palette);
        this.territoryTask = new TerritoryDisplayTask(config, store, displayPrefs, palette);
        this.adminZoneManager = new AdminZoneManager(config, store, confirms);
        PowerBlocksGui powerBlocksGui = new PowerBlocksGui(config);

        // Listeners
        getServer().getPluginManager().registerEvents(
                new ProtectionListener(config, claimAccess, adminBypass), this);
        getServer().getPluginManager().registerEvents(
                new PowerLedgerListener(config, store, powerCalc), this);
        getServer().getPluginManager().registerEvents(
                new OverclaimQuitListener(overclaimManager), this);
        getServer().getPluginManager().registerEvents(
                new ExplosionListener(config, store, powerCalc), this);
        getServer().getPluginManager().registerEvents(
                new MobListener(store), this);
        getServer().getPluginManager().registerEvents(homeManager, this);
        BorderTitleListener borderTitle =
                new BorderTitleListener(config, store, nameCache, displayPrefs, palette);
        getServer().getPluginManager().registerEvents(borderTitle, this);
        // Wire the 1Hz fallback so the territory task also re-checks each
        // online player's chunk owner — catches any crossing the event
        // listeners miss (vehicle, spectator flight, plugin-suppressed events).
        territoryTask.setBorderTitle(borderTitle);
        getServer().getPluginManager().registerEvents(
                new DisplayQuitListener(territoryTask, showClaimManager), this);
        getServer().getPluginManager().registerEvents(new GuiClickListener(), this);
        this.luckPermsHook = LuckPermsHook.attempt(this, config.luckPermsPrefixWeight());
        this.prefixUpdater = new PrefixUpdater(config, store, luckPermsHook);
        getServer().getPluginManager().registerEvents(prefixUpdater, this);
        // Wire the manager → prefix-updater hook so role / realm transitions
        // update chat & tab prefixes without needing a relog.
        realmManager.setPrefixRefresh(prefixUpdater::refresh);
        // Apply prefixes to anyone already online (e.g. /reload mid-session).
        for (Player p : getServer().getOnlinePlayers()) prefixUpdater.refresh(p);

        // Periodic janitor + overclaim tick.
        getServer().getScheduler().runTaskTimer(this, () -> {
            invites.purgeExpired();
            confirms.purgeExpired();
            allyProposals.purgeExpired();
            store.purgeExpiredCooldowns(System.currentTimeMillis());
        }, 20L * 30L, 20L * 30L);
        getServer().getScheduler().runTaskTimer(this, overclaimManager::tick, 20L, 20L);
        territoryTask.runTaskTimer(this,
                config.actionBarRefreshTicks(), config.actionBarRefreshTicks());
        showClaimManager.runTaskTimer(this,
                config.seeClaimsRefreshTicks(), config.seeClaimsRefreshTicks());

        // Command
        RealmsCommand cmd = new RealmsCommand(this, config, store, nameCache,
                realmManager, claimManager, powerCalc, diplomacyManager, overclaimManager,
                adminBypass, homeManager, displayPrefs, showClaimManager, palette,
                adminZoneManager, powerBlocksGui);
        PluginCommand pc = getCommand("realm");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }
        PluginCommand rc = getCommand("realmchat");
        if (rc != null) rc.setExecutor(new RealmChatCommand(config, store, diplomacyManager));

        getLogger().info("Realms enabled.");
    }

    /** Hooks the prefix updater so managers can refresh after realm changes. */
    public PrefixUpdater getPrefixUpdater() { return prefixUpdater; }

    @Override
    public void onDisable() {
        if (store != null) store.close();
    }

    private void broadcastOverclaim(String aggressor, String victim, int loot) {
        String tpl = config.message("broadcasts.realm-overclaimed",
                "&c{aggressor}&7 has overclaimed land from &c{victim}&7.");
        Bukkit.broadcastMessage(Text.render(tpl, java.util.Map.of(
                "aggressor", aggressor,
                "victim", victim,
                "loot", String.valueOf(loot)
        )));
    }

    public RealmsConfig getRealmsConfig() { return config; }
    public RealmsStore getStore() { return store; }
    public NameCache getNameCache() { return nameCache; }
    public RealmManager getRealmManager() { return realmManager; }
    public ClaimManager getClaimManager() { return claimManager; }
    public PowerCalc getPowerCalc() { return powerCalc; }
    public InviteStore getInvites() { return invites; }
    public ConfirmStore getConfirms() { return confirms; }
    public AdminBypass getAdminBypass() { return adminBypass; }
    public ClaimAccess getClaimAccess() { return claimAccess; }
    public DiplomacyManager getDiplomacy() { return diplomacyManager; }
    public OverclaimManager getOverclaim() { return overclaimManager; }
}
