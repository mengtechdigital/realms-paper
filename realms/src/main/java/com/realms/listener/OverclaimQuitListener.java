package com.realms.listener;

import com.realms.manager.OverclaimManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Drops any in-flight overclaim attempt when its owner disconnects. The
 * grace tracker also bails out on its own when it sees the player offline,
 * but this gives the abort an immediate, deterministic moment instead of
 * waiting for the next tick.
 */
public final class OverclaimQuitListener implements Listener {

    private final OverclaimManager overclaim;

    public OverclaimQuitListener(OverclaimManager overclaim) {
        this.overclaim = overclaim;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        overclaim.abort(event.getPlayer().getUniqueId());
    }
}
