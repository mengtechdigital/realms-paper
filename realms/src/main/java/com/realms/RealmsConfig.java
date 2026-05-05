package com.realms;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Immutable snapshot of {@code config.yml} + {@code messages.yml} +
 * {@code power-blocks.yml}. Reload swaps the snapshot atomically; live
 * listeners holding the old reference keep working until the next read,
 * so a /reload mid-tick can't tear state.
 */
public final class RealmsConfig {

    public enum ExplosionPolicy { VANILLA, CANCEL }

    private FileConfiguration config;
    private FileConfiguration messages;
    private FileConfiguration powerBlocks;
    private Map<Material, Long> powerValues = Collections.emptyMap();
    private Set<String> reservedNames = Collections.emptySet();

    public void load(RealmsPlugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        this.messages = loadOrCopy(plugin, "messages.yml");
        this.powerBlocks = loadOrCopy(plugin, "power-blocks.yml");

        Map<Material, Long> pv = new HashMap<>();
        var section = powerBlocks.getConfigurationSection("power-blocks");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m == null) {
                    plugin.getLogger().warning("Unknown material in power-blocks.yml: " + key);
                    continue;
                }
                pv.put(m, section.getLong(key));
            }
        }
        this.powerValues = Collections.unmodifiableMap(pv);

        Set<String> reserved = new HashSet<>();
        List<String> reservedList = config.getStringList("realm-name.reserved");
        for (String s : reservedList) reserved.add(s.toLowerCase(Locale.ROOT));
        this.reservedNames = Collections.unmodifiableSet(reserved);
    }

    private static FileConfiguration loadOrCopy(RealmsPlugin plugin, String name) {
        File f = new File(plugin.getDataFolder(), name);
        if (!f.exists()) {
            try (InputStream in = plugin.getResource(name)) {
                if (in == null) {
                    plugin.getLogger().severe("Bundled resource missing: " + name);
                    return new YamlConfiguration();
                }
                java.nio.file.Files.createDirectories(plugin.getDataFolder().toPath());
                java.nio.file.Files.copy(in, f.toPath());
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to copy default " + name, e);
            }
        }
        return YamlConfiguration.loadConfiguration(f);
    }

    public FileConfiguration raw() { return config; }
    public FileConfiguration messages() { return messages; }
    public Map<Material, Long> powerValues() { return powerValues; }
    public Set<String> reservedNames() { return reservedNames; }

    // Accessors with sensible defaults -------------------------------------

    public String databaseFile() { return config.getString("database-file", "realms.db"); }

    public int realmNameMin() { return config.getInt("realm-name.min-length", 3); }
    public int realmNameMax() { return config.getInt("realm-name.max-length", 16); }

    public long basePower() { return config.getLong("power.base", 30); }
    public long perMemberPower() { return config.getLong("power.per-member-bonus", 20); }
    public long costPerChunk() { return Math.max(1L, config.getLong("power.cost-per-chunk", 1)); }

    public int maxClaimDiameter() { return Math.max(1, config.getInt("claim.max-diameter", 7)); }
    public int confirmFromDiameter() { return config.getInt("claim.confirm-required-from-diameter", 5); }
    public int confirmExpirySeconds() { return config.getInt("claim.confirm-expiry-seconds", 30); }

    public boolean raidEnabled() { return config.getBoolean("raid.enabled", true); }
    public boolean peacefulDefault() { return config.getBoolean("raid.peaceful-default", false); }
    public long weakenedGraceSeconds() { return config.getLong("raid.weakened-grace-seconds", 600); }
    public long enemyRedeclareCooldownSeconds() { return config.getLong("raid.enemy-redeclare-cooldown-seconds", 3600); }

    public ExplosionPolicy explosionTnt() { return policy("explosions.tnt", ExplosionPolicy.VANILLA); }
    public ExplosionPolicy explosionCreeper() { return policy("explosions.creeper", ExplosionPolicy.CANCEL); }
    public ExplosionPolicy explosionEndCrystal() { return policy("explosions.end-crystal", ExplosionPolicy.CANCEL); }
    public ExplosionPolicy explosionRespawnAnchor() { return policy("explosions.respawn-anchor", ExplosionPolicy.CANCEL); }
    public ExplosionPolicy explosionBed() { return policy("explosions.bed-in-nether-end", ExplosionPolicy.CANCEL); }
    public ExplosionPolicy explosionWither() { return policy("explosions.wither", ExplosionPolicy.CANCEL); }
    public ExplosionPolicy explosionGhast() { return policy("explosions.ghast-fireball", ExplosionPolicy.CANCEL); }

    private ExplosionPolicy policy(String path, ExplosionPolicy fallback) {
        String v = config.getString(path, fallback.name().toLowerCase(Locale.ROOT));
        try { return ExplosionPolicy.valueOf(v.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return fallback; }
    }

    public boolean flagDefault(String name, boolean fallback) {
        return config.getBoolean("flag-defaults." + name, fallback);
    }

    public boolean allySpawnAccess() { return config.getBoolean("ally-spawn-access", false); }

    public String prefixMode() { return config.getString("prefix-mode", "chat-event"); }
    public int luckPermsPrefixWeight() { return config.getInt("prefix-weight-luckperms", 100); }
    public String chatPrefixFormat() { return config.getString("chat-prefix-format", "&7[&6{role} of {realm}&7] &r"); }
    public String tabPrefixFormat() { return config.getString("tab-prefix-format", "&6[{realm}] &r"); }

    public boolean realmChatEnabled() { return config.getBoolean("realm-chat.enabled", true); }
    public String realmChatFormat() { return config.getString("realm-chat.format",
            "&7[&aRealm&7] &6{role} {name}: &f{message}"); }
    public boolean realmChatAlliesOverhear() { return config.getBoolean("realm-chat.allies-overhear", false); }

    // Display ---------------------------------------------------------------

    public boolean borderTitleEnabled() { return config.getBoolean("display.border-title.enabled", true); }
    public int borderTitleFadeIn()  { return config.getInt("display.border-title.fade-in-ticks", 5); }
    public int borderTitleStay()    { return config.getInt("display.border-title.stay-ticks", 30); }
    public int borderTitleFadeOut() { return config.getInt("display.border-title.fade-out-ticks", 10); }
    public boolean borderTitleSubtitle() { return config.getBoolean("display.border-title.show-subtitle", true); }

    public boolean actionBarEnabledDefault() { return config.getBoolean("display.action-bar.enabled-default", true); }
    public int actionBarRefreshTicks() { return Math.max(5, config.getInt("display.action-bar.refresh-ticks", 20)); }
    public boolean actionBarPeacefulTag() { return config.getBoolean("display.action-bar.show-peaceful-tag", true); }
    public boolean actionBarWeakenedTag() { return config.getBoolean("display.action-bar.show-weakened-tag", true); }

    public boolean bossBarEnabledDefault() { return config.getBoolean("display.boss-bar.enabled-default", false); }
    public boolean bossBarShowFill() { return config.getBoolean("display.boss-bar.show-power-fill", true); }

    public boolean soundEnabledDefault() { return config.getBoolean("display.sound.enabled-default", false); }
    public String soundEnterRealm() { return config.getString("display.sound.enter-realm", "block.note_block.bell"); }
    public String soundEnterWilderness() { return config.getString("display.sound.enter-wilderness", "block.note_block.harp"); }
    public double soundVolume() { return config.getDouble("display.sound.volume", 0.5); }
    public double soundPitch()  { return config.getDouble("display.sound.pitch", 1.0); }

    public boolean seeClaimsEnabled() { return config.getBoolean("display.see-claims.enabled", true); }
    public String seeClaimsDefaultMode() { return config.getString("display.see-claims.default-mode", "line"); }
    public int seeClaimsRadius() { return Math.max(1, config.getInt("display.see-claims.radius", 5)); }
    public int seeClaimsRefreshTicks() { return Math.max(10, config.getInt("display.see-claims.refresh-ticks", 20)); }
    public int seeClaimsMaxUsers() { return config.getInt("display.see-claims.max-concurrent-users", 50); }
    public double seeClaimsTpsThreshold() { return config.getDouble("display.see-claims.tps-threshold", 18.0); }
    public int seeClaimsLineYOffset() { return config.getInt("display.see-claims.line-y-offset", 0); }
    public int seeClaimsWallYMin() { return config.getInt("display.see-claims.wall-y-min-offset", -4); }
    public int seeClaimsWallYMax() { return config.getInt("display.see-claims.wall-y-max-offset", 8); }
    public int seeClaimsSampleSpacing() { return Math.max(1, config.getInt("display.see-claims.sample-spacing", 1)); }
    public boolean seeClaimsPersistToggle() { return config.getBoolean("display.see-claims.persist-toggle", false); }

    // Colors ----------------------------------------------------------------

    public String colorWilderness() { return config.getString("colors.wilderness", "&7"); }
    public String colorOwn() { return config.getString("colors.own", "&a"); }
    public String colorAlly() { return config.getString("colors.ally", "&b"); }
    public String colorEnemy() { return config.getString("colors.enemy", "&c"); }
    public String colorNeutral() { return config.getString("colors.neutral", "&e"); }
    public String colorPeaceful() { return config.getString("colors.peaceful", "&6"); }

    // Home ------------------------------------------------------------------

    public int homeWarmupSeconds() { return config.getInt("home.warmup-seconds", 3); }
    public int homeCooldownSeconds() { return config.getInt("home.cooldown-seconds", 60); }
    public boolean homeCancelOnMove() { return config.getBoolean("home.cancel-on-move", true); }
    public boolean homeCancelOnDamage() { return config.getBoolean("home.cancel-on-damage", true); }

    // Messages --------------------------------------------------------------

    public String message(String path, String fallback) {
        String v = messages.getString(path);
        return v == null ? fallback : v;
    }
}
