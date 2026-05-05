package com.realms;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Realms — Towny-flavoured towns + Factions-flavoured land claiming.
 *
 * Plugin entry point. Subsystems are wired here but most logic lives in
 * data/, manager/, listener/, command/, task/, integration/, display/.
 *
 * Phase 1: scaffold only. Real wiring lands in subsequent phases.
 */
public final class RealmsPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("Realms enabling — phase 1 scaffold (no wiring yet).");
    }

    @Override
    public void onDisable() {
        getLogger().info("Realms disabling.");
    }
}
