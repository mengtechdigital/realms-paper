package com.realms;

import com.realms.data.NameCache;
import com.realms.data.RealmsStore;
import com.realms.data.SqliteRealmsStore;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.logging.Level;

public final class RealmsPlugin extends JavaPlugin {

    private RealmsConfig config;
    private SqliteRealmsStore store;
    private NameCache nameCache;

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

        getLogger().info("Realms enabled (phase 2 — data layer).");
    }

    @Override
    public void onDisable() {
        if (store != null) store.close();
    }

    public RealmsConfig getRealmsConfig() { return config; }
    public RealmsStore getStore() { return store; }
    public NameCache getNameCache() { return nameCache; }
}
