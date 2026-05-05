package com.realms;

import com.realms.command.RealmsCommand;
import com.realms.data.NameCache;
import com.realms.data.RealmsStore;
import com.realms.data.SqliteRealmsStore;
import com.realms.listener.PowerLedgerListener;
import com.realms.listener.ProtectionListener;
import com.realms.manager.AdminBypass;
import com.realms.manager.ClaimAccess;
import com.realms.manager.ClaimManager;
import com.realms.manager.ConfirmStore;
import com.realms.manager.InviteStore;
import com.realms.manager.PowerCalc;
import com.realms.manager.RealmManager;
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
        this.powerCalc = new PowerCalc(config, store);
        this.realmManager = new RealmManager(config, store, nameCache, invites, confirms, powerCalc);
        this.claimManager = new ClaimManager(config, store, confirms, powerCalc);
        this.adminBypass = new AdminBypass();
        this.claimAccess = new ClaimAccess(config, store, adminBypass);

        // Listeners
        getServer().getPluginManager().registerEvents(
                new ProtectionListener(config, claimAccess, adminBypass), this);
        getServer().getPluginManager().registerEvents(
                new PowerLedgerListener(config, store, powerCalc), this);

        // Periodic janitor: invites expire on access too, but a sweep keeps
        // the map small on idle servers. Confirm tokens follow the same logic.
        getServer().getScheduler().runTaskTimer(this, () -> {
            invites.purgeExpired();
            confirms.purgeExpired();
            store.purgeExpiredCooldowns(System.currentTimeMillis());
        }, 20L * 30L, 20L * 30L);

        // Command
        RealmsCommand cmd = new RealmsCommand(this, config, store, nameCache,
                realmManager, claimManager, powerCalc);
        PluginCommand pc = getCommand("realm");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }
        // /realmchat will be wired in phase 9 (realm chat).

        getLogger().info("Realms enabled (phase 3 — realm + claim management).");
    }

    @Override
    public void onDisable() {
        if (store != null) store.close();
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
}
