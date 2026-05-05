package com.realms.display;

import com.realms.RealmsConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only chest inventory listing every power-block material with its
 * value in lore. Sorted by value descending so the highest-yield blocks
 * sit in the top-left.
 *
 * Click protection lives in {@link GuiClickListener}, which cancels every
 * InventoryClickEvent against an inventory with this class's holder.
 * Players can't pull items out — the GUI is purely informational.
 */
public final class PowerBlocksGui {

    /** Marker holder so the click listener can identify our GUI. */
    public static final class Holder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
        void attach(Inventory i) { this.inv = i; }
    }

    private final RealmsConfig config;

    public PowerBlocksGui(RealmsConfig config) {
        this.config = config;
    }

    public void open(Player viewer) {
        Map<Material, Long> values = config.powerValues();
        List<Map.Entry<Material, Long>> sorted = new ArrayList<>(values.entrySet());
        sorted.sort((a, b) -> {
            int byValue = Long.compare(b.getValue(), a.getValue());
            if (byValue != 0) return byValue;
            return a.getKey().name().compareTo(b.getKey().name());
        });

        // Round inventory size up to the nearest 9-row, capped at the
        // chest 54-slot maximum. Empty if the table is empty.
        int rows = Math.max(1, Math.min(6, (sorted.size() + 8) / 9));
        int size = rows * 9;

        Holder holder = new Holder();
        Component title = Component.text("Power Blocks", NamedTextColor.GOLD);
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.attach(inv);

        int slot = 0;
        for (Map.Entry<Material, Long> entry : sorted) {
            if (slot >= size) break;
            inv.setItem(slot++, render(entry.getKey(), entry.getValue()));
        }
        viewer.openInventory(inv);
    }

    private static ItemStack render(Material material, long value) {
        // Some power-block materials (BEACON, SHULKER_BOX) might be
        // unstackable / have weird rendering. ItemStack(Material) is the
        // safest path; ItemMeta carries the display name and lore.
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(Component.text(pretty(material), NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("+" + value + " power", NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Place inside your claim to grow realm power.",
                        NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Broken / TNT-mined → power back out.",
                        NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    /** "DIAMOND_BLOCK" → "Diamond Block" */
    private static String pretty(Material m) {
        StringBuilder out = new StringBuilder(m.name().length());
        boolean cap = true;
        for (char c : m.name().toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '_') { out.append(' '); cap = true; }
            else if (cap) { out.append(Character.toUpperCase(c)); cap = false; }
            else { out.append(c); }
        }
        return out.toString();
    }

    /** Hint for callers when there's nothing to render — they can fall back to chat. */
    public boolean hasContent() {
        return !config.powerValues().isEmpty();
    }

    /** Convenience: handles the "no power blocks configured" case. */
    public static boolean isPowerBlocksGui(Inventory inv) {
        return inv != null && inv.getHolder() instanceof Holder;
    }

    @SuppressWarnings("unused")
    private static InventoryType unusedHint() { return InventoryType.CHEST; }
}
