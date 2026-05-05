package com.realms.display;

import com.realms.data.DisplayPrefs;
import com.realms.data.RealmsStore;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Convenience facade over {@link RealmsStore#getDisplayPrefs} /
 * {@link RealmsStore#putDisplayPrefs}. The store's getDisplayPrefs already
 * returns defaults when no row exists, so we only persist on explicit
 * change — keeps display_prefs row count proportional to players who
 * actually customised something.
 */
public final class DisplayPrefsManager {

    private final RealmsStore store;

    public DisplayPrefsManager(RealmsStore store) {
        this.store = store;
    }

    public DisplayPrefs of(Player p) {
        return store.getDisplayPrefs(p.getUniqueId());
    }

    public DisplayPrefs of(UUID id) {
        return store.getDisplayPrefs(id);
    }

    public void setTitle(Player p, boolean on) {
        store.putDisplayPrefs(p.getUniqueId(), of(p).withTitle(on));
    }

    public void setBar(Player p, DisplayPrefs.BarMode mode) {
        store.putDisplayPrefs(p.getUniqueId(), of(p).withBarMode(mode));
    }

    public void setSound(Player p, boolean on) {
        store.putDisplayPrefs(p.getUniqueId(), of(p).withSound(on));
    }
}
