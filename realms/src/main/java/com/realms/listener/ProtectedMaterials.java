package com.realms.listener;

import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.EnumSet;
import java.util.Set;

/**
 * Material classifier for the protection listener. We deliberately do NOT
 * try to lock down every right-click — that breaks too many vanilla flows
 * (eating, throwing snowballs, riding boats). Instead we list the classes
 * of blocks an outsider should never be able to interact with inside a
 * claim: containers, doors, switches, beds, crafting/utility stations.
 *
 * Anything not in these sets falls through to vanilla behaviour.
 */
public final class ProtectedMaterials {
    private ProtectedMaterials() {}

    /** Containers — chests, barrels, shulker boxes, hoppers, droppers, etc. */
    private static final Set<Material> CONTAINERS = EnumSet.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.ENDER_CHEST,
            Material.BARREL, Material.HOPPER, Material.DROPPER, Material.DISPENSER,
            Material.BREWING_STAND, Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER,
            Material.JUKEBOX, Material.LECTERN, Material.CHISELED_BOOKSHELF,
            Material.DECORATED_POT,
            Material.CRAFTER,                  // 1.21 auto-crafter — has an inventory
            Material.BEACON, Material.CAULDRON, Material.WATER_CAULDRON,
            Material.LAVA_CAULDRON, Material.POWDER_SNOW_CAULDRON
    );

    /** Switches, buttons, plates, levers — manipulating these triggers redstone. */
    private static final Set<Material> REDSTONE_INPUT = EnumSet.of(
            Material.LEVER,
            Material.STONE_BUTTON, Material.OAK_BUTTON, Material.SPRUCE_BUTTON,
            Material.BIRCH_BUTTON, Material.JUNGLE_BUTTON, Material.ACACIA_BUTTON,
            Material.DARK_OAK_BUTTON, Material.MANGROVE_BUTTON, Material.CHERRY_BUTTON,
            Material.BAMBOO_BUTTON, Material.CRIMSON_BUTTON, Material.WARPED_BUTTON,
            Material.POLISHED_BLACKSTONE_BUTTON,
            Material.REPEATER, Material.COMPARATOR, Material.DAYLIGHT_DETECTOR,
            Material.NOTE_BLOCK, Material.BELL,
            Material.RESPAWN_ANCHOR
    );

    /** Crafting / utility stations a member would expect to gatekeep. */
    private static final Set<Material> WORKSTATIONS = EnumSet.of(
            Material.CRAFTING_TABLE, Material.SMITHING_TABLE, Material.LOOM,
            Material.STONECUTTER, Material.GRINDSTONE, Material.CARTOGRAPHY_TABLE,
            Material.FLETCHING_TABLE, Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
            Material.ENCHANTING_TABLE, Material.COMPOSTER
    );

    public static boolean isContainer(Material m) {
        if (CONTAINERS.contains(m)) return true;
        return Tag.SHULKER_BOXES.isTagged(m);
    }

    public static boolean isDoorOrGate(Material m) {
        return Tag.DOORS.isTagged(m) || Tag.TRAPDOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m);
    }

    public static boolean isPressurePlate(Material m) {
        return Tag.PRESSURE_PLATES.isTagged(m);
    }

    public static boolean isBed(Material m) {
        return Tag.BEDS.isTagged(m);
    }

    public static boolean isSwitch(Material m) {
        if (REDSTONE_INPUT.contains(m)) return true;
        return Tag.BUTTONS.isTagged(m);
    }

    public static boolean isWorkstation(Material m) {
        return WORKSTATIONS.contains(m);
    }

    /** Anything we'd block an outsider from right-clicking inside a claim. */
    public static boolean isProtectedInteraction(Material m) {
        if (m == null || m == Material.AIR) return false;
        return isContainer(m)
                || isDoorOrGate(m)
                || isPressurePlate(m)
                || isBed(m)
                || isSwitch(m)
                || isWorkstation(m);
    }
}
