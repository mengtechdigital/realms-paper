package com.realms.display;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Drops per-player display state on disconnect: the boss-bar handle held by
 * the territory display task and the show-claims active set. Without this,
 * boss bar references leak forever and show-claims slots are permanently
 * occupied (eventually filling the seeClaimsMaxUsers cap with ghosts).
 */
public final class DisplayQuitListener implements Listener {

    private final TerritoryDisplayTask territory;
    private final ShowClaimManager showClaim;

    public DisplayQuitListener(TerritoryDisplayTask territory, ShowClaimManager showClaim) {
        this.territory = territory;
        this.showClaim = showClaim;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        territory.onPlayerQuit(event.getPlayer().getUniqueId());
        showClaim.abort(event.getPlayer().getUniqueId());
    }
}
