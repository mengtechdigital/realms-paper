package com.realms.listener;

import com.realms.RealmsConfig;
import com.realms.manager.AdminBypass;
import com.realms.manager.ClaimAccess;
import com.realms.manager.Text;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Outsider-protection event handlers. Coarse — members may do anything,
 * outsiders may not modify the chunk in any meaningful way. Diplomacy in
 * phase 6 will relax this for allies (door/button only, not containers).
 *
 * Throttle policy: we send a chat message when an action is denied so the
 * player understands. Bukkit doesn't dedupe these for us, so a player
 * spamming click on a chest will see the message every click. That's
 * acceptable — the goal is clarity, not server protection.
 */
public final class ProtectionListener implements Listener {

    private static final long DENY_MESSAGE_COOLDOWN_MS = 2000L;

    private final RealmsConfig config;
    private final ClaimAccess access;
    private final AdminBypass bypassRef;
    /** Last time we sent a deny message to each player — keeps clicks from spamming chat. */
    private final Map<UUID, Long> lastDeny = new ConcurrentHashMap<>();

    public ProtectionListener(RealmsConfig config, ClaimAccess access, AdminBypass bypass) {
        this.config = config;
        this.access = access;
        this.bypassRef = bypass;
    }

    // ---- Block break / place ---------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!access.canBuild(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            denyMessage(event.getPlayer(), "build");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!access.canBuild(event.getPlayer(), event.getBlockPlaced().getLocation())) {
            event.setCancelled(true);
            denyMessage(event.getPlayer(), "build");
        }
    }

    // ---- Right-click interactions ---------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                && event.getAction() != Action.PHYSICAL) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        Material type = clicked.getType();
        // PHYSICAL = pressure plate / tripwire trigger via player walk.
        // Plates are ally-permissive: walking on a plate isn't griefing.
        if (event.getAction() == Action.PHYSICAL) {
            if (!ProtectedMaterials.isPressurePlate(type)) return;
            if (!access.canUseAsAlly(event.getPlayer(), clicked.getLocation())) {
                event.setCancelled(true);
                event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
                // No message on PHYSICAL — would spam on every step.
            }
            return;
        }
        if (!ProtectedMaterials.isProtectedInteraction(type)) return;
        // Containers + workstations: members only. Doors/gates/buttons/levers/
        // beds/plates: members + allies (so allies can navigate your town).
        boolean ok;
        if (ProtectedMaterials.isContainer(type) || ProtectedMaterials.isWorkstation(type)) {
            ok = access.canOpenContainer(event.getPlayer(), clicked.getLocation());
        } else {
            ok = access.canUseAsAlly(event.getPlayer(), clicked.getLocation());
        }
        if (!ok) {
            event.setCancelled(true);
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            denyMessage(event.getPlayer(), "interact");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        // Don't lock outsiders out of their own tamed mobs that wandered into
        // a claim — that's friction without an anti-grief upside.
        if (target instanceof Tameable t && t.isTamed()) {
            AnimalTamer owner = t.getOwner();
            if (owner != null && owner.getUniqueId().equals(event.getPlayer().getUniqueId())) {
                return;
            }
        }
        // Entity interaction = item frames, armor stands, villagers — treated
        // as ally-permissive when ally-interact is on (default), so allies
        // can loot frames / trade with villagers in your land. Outsiders
        // still blocked entirely.
        boolean ok = access.allyInteractEnabled()
                ? access.canUseAsAlly(event.getPlayer(), target.getLocation())
                : access.canBuild(event.getPlayer(), target.getLocation());
        if (!ok) {
            event.setCancelled(true);
            denyMessage(event.getPlayer(), "interact");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        // Armor stands hold gear — same ally-permissive policy as item frames.
        boolean ok = access.allyInteractEnabled()
                ? access.canUseAsAlly(event.getPlayer(), event.getRightClicked().getLocation())
                : access.canBuild(event.getPlayer(), event.getRightClicked().getLocation());
        if (!ok) {
            event.setCancelled(true);
            denyMessage(event.getPlayer(), "interact");
        }
    }

    /**
     * Boats and minecarts placed on water/rails inside a claim go through
     * EntityPlaceEvent, not BlockPlaceEvent — so an outsider could otherwise
     * place vehicles in a claim and use them to push members or obstruct
     * water builds.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player p = event.getPlayer();
        if (p == null) return;
        if (!access.canBuild(p, event.getEntity().getLocation())) {
            event.setCancelled(true);
            denyMessage(p, "build");
        }
    }

    // ---- Buckets / fluid manipulation ------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!access.canBuild(event.getPlayer(), event.getBlockClicked().getLocation())) {
            event.setCancelled(true);
            denyMessage(event.getPlayer(), "build");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!access.canBuild(event.getPlayer(), event.getBlockClicked().getLocation())) {
            event.setCancelled(true);
            denyMessage(event.getPlayer(), "build");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (event.getPlayer() == null) return; // lava drips, lightning — handled elsewhere
        if (!access.canBuild(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            denyMessage(event.getPlayer(), "build");
        }
    }

    // ---- Hanging entities (paintings / item frames) ---------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Player attacker = resolvePlayer(event.getRemover());
        if (attacker == null) return;
        if (!access.canBuild(attacker, event.getEntity().getLocation())) {
            event.setCancelled(true);
            denyMessage(attacker, "build");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() == null) return;
        if (!access.canBuild(event.getPlayer(), event.getEntity().getLocation())) {
            event.setCancelled(true);
            denyMessage(event.getPlayer(), "build");
        }
    }

    // ---- PvP filter -------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolvePlayer(event.getDamager());
        if (attacker == null) return;
        if (!access.canPvp(attacker, victim)) {
            event.setCancelled(true);
            // No deny-spam message on PvP — players already know what they're
            // doing; spamming chat on every arrow tick would be miserable.
        }
    }

    /**
     * Splash potions don't fire EntityDamageByEntityEvent on harm effects —
     * they go through PotionSplashEvent → applyPotionEffects. Strip protected
     * victims from the affected set so the potion still works on attackers /
     * non-protected players.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        ThrownPotion potion = event.getPotion();
        Player attacker = sourceAsPlayer(potion.getShooter());
        if (attacker == null) return;
        event.getAffectedEntities().removeIf(e -> {
            if (!(e instanceof Player p)) return false;
            if (p.getUniqueId().equals(attacker.getUniqueId())) return false;
            return !access.canPvp(attacker, p);
        });
    }

    // ---- Helpers ----------------------------------------------------------

    /**
     * Walk projectiles / area-effect clouds / TNT back to the original Player
     * source. Returns null if the damage didn't originate from a player.
     */
    private static Player resolvePlayer(Entity damager) {
        if (damager == null) return null;
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj) return sourceAsPlayer(proj.getShooter());
        if (damager instanceof AreaEffectCloud cloud) return sourceAsPlayer(cloud.getSource());
        if (damager instanceof TNTPrimed tnt) {
            Entity src = tnt.getSource();
            if (src instanceof Player p) return p;
        }
        return null;
    }

    private static Player sourceAsPlayer(ProjectileSource src) {
        return src instanceof Player p ? p : null;
    }

    /**
     * Drop the player's transient state on disconnect. Without this, the
     * AdminBypass set retains entries indefinitely (a re-logged admin keeps
     * silent bypass) and the deny-message throttle map slowly grows.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        java.util.UUID id = event.getPlayer().getUniqueId();
        bypassRef.clear(id);
        lastDeny.remove(id);
    }

    private void denyMessage(Player player, String kind) {
        // Throttle: clicking a chest 5 times per second shouldn't spam chat.
        long now = System.currentTimeMillis();
        Long last = lastDeny.get(player.getUniqueId());
        if (last != null && now - last < DENY_MESSAGE_COOLDOWN_MS) return;
        lastDeny.put(player.getUniqueId(), now);
        String fallback = kind.equals("build")
                ? "&cYou can't build here."
                : "&cYou can't interact here.";
        player.sendMessage(Text.colorize(
                config.message("errors.protection-deny-" + kind, fallback)));
    }
}
