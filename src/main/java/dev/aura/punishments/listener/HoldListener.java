package dev.aura.punishments.listener;

import dev.aura.punishments.service.Core;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Locale;

/**
 * The network jail's hold: a player with a live jail punishment who is not (yet) inside JailReloaded cannot move, build, fight, chat or run
 * commands on this backend. It closes the gap between "jailed" and "taken to the jail" - a slow transfer, a login on the wrong backend, a jail
 * backend that is down - so none of those is a way out.
 *
 * <p>Teleports are NOT blocked: JailReloaded and the proxy transfer must be able to move the player.
 */
public final class HoldListener implements Listener {
    private final Core core;

    public HoldListener(Core core) {
        this.core = core;
    }

    private boolean held(Player p) {
        return core.netJail != null && core.netJail.mustHold(p.getUniqueId());
    }

    private void block(Player p, Cancellable e) {
        if (!held(p)) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        var from = e.getFrom();
        var to = e.getTo();
        if (to == null || (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ())) return; // turning the head is fine
        if (held(e.getPlayer())) e.setTo(from.clone().setDirection(to.getDirection()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        block(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!held(e.getPlayer())) return;
        String label = e.getMessage().substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        for (String ok : core.settings.network.jail().holdCommands()) if (ok.equalsIgnoreCase(label)) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(core.messages.get("command.jail-hold-blocked"));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        block(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        block(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        block(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        block(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        block(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) block(p, e);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        block(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        block(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p) block(p, e);
    }
}
