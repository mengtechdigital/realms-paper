package com.realms.display;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Cancels every click and drag against any read-only Realms GUI. Currently
 * just the {@link PowerBlocksGui} but kept generic so future GUIs (relations
 * picker, flag toggles, admin zone manager) can reuse the same gate by
 * tagging their inventory holder.
 */
public final class GuiClickListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (PowerBlocksGui.isPowerBlocksGui(event.getInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (PowerBlocksGui.isPowerBlocksGui(event.getInventory())) {
            event.setCancelled(true);
        }
    }
}
