package dev.aura.punishments.listener;

import dev.aura.punishments.integration.BukkitEnforcement;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.service.Core;
import dev.aura.punishments.service.PunishmentService;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.Optional;

/**
 * Mutes public chat. Runs at LOWEST priority so the message is cancelled before TownyChat (or any other chat
 * plugin) renders and distributes it, which covers every TownyChat channel that is driven by chat events.
 * Channel and private-message COMMANDS are blocked by {@link CommandGuardListener}.
 */
public final class ChatListener implements Listener {
    private final Plugin plugin;
    private final Core core;
    private final PunishmentService punishments;

    public ChatListener(Plugin plugin, Core core, PunishmentService punishments) {
        this.plugin = plugin;
        this.core = core;
        this.punishments = punishments;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Optional<Punishment> mute = punishments.muteFor(e.getPlayer().getUniqueId());
        if (mute.isEmpty()) return;
        e.setCancelled(true);
        Punishment p = mute.get();
        var player = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(core.messages.get("mute.blocked", "expires", BukkitEnforcement.expiry(p, core.clock.now()))));
    }
}
