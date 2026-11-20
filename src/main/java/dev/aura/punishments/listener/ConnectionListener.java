package dev.aura.punishments.listener;

import dev.aura.punishments.integration.BukkitEnforcement;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.service.Actors;
import dev.aura.punishments.service.Core;
import dev.aura.punishments.service.PlayerResolver;
import dev.aura.punishments.service.PunishmentService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Optional;

/** Ban enforcement at login (memory lookup, no I/O), profile tracking and delivery of missed warnings. */
public final class ConnectionListener implements Listener {
    private final Core core;
    private final PunishmentService punishments;
    private final PlayerResolver resolver;
    private final BukkitEnforcement enforcement;

    public ConnectionListener(Core core, PunishmentService punishments, PlayerResolver resolver, BukkitEnforcement enforcement) {
        this.core = core;
        this.punishments = punishments;
        this.resolver = resolver;
        this.enforcement = enforcement;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        String ip = e.getAddress() == null ? null : e.getAddress().getHostAddress();
        Optional<Punishment> ban = punishments.banFor(e.getUniqueId(), ip);
        if (ban.isPresent()) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, enforcement.banScreen(ban.get()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        var p = e.getPlayer();
        String ip = p.getAddress() == null || p.getAddress().getAddress() == null ? null : p.getAddress().getAddress().getHostAddress();
        resolver.recordJoin(p.getUniqueId(), p.getName(), ip, Actors.levelOf(p));
        punishments.deliverPending(p.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // Remember the staff level so offline protection works without a permission backend.
        resolver.recordLevel(e.getPlayer().getUniqueId(), Actors.levelOf(e.getPlayer()));
    }
}
