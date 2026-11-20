package dev.aura.punishments.integration;

import dev.aura.punishments.gui.Gui;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.service.Core;
import dev.aura.punishments.service.Enforcement;
import dev.aura.punishments.util.Durations;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/** Applies punishments to connected players: kick/ban screens and notices. Main thread only. */
public final class BukkitEnforcement implements Enforcement {
    private final Plugin plugin;
    private final Core core;

    public BukkitEnforcement(Plugin plugin, Core core) {
        this.plugin = plugin;
        this.core = core;
    }

    public static String expiry(Punishment p, long now) {
        if (p.expiresAt() == null) return "never (permanent)";
        return Gui.time(p.expiresAt()) + " (" + Durations.format(Math.max(0, p.expiresAt() - now)) + " left)";
    }

    public Component banScreen(Punishment p) {
        String key = p.type() == PunishType.IPBAN ? "screen.ipban" : p.type() == PunishType.KICK ? "screen.kick" : "screen.ban";
        return core.messages.screen(key, "reason", p.reason(), "expires", expiry(p, core.clock.now()), "id", String.valueOf(p.id()));
    }

    @Override
    public boolean isOnline(UUID uuid) {
        return Bukkit.getPlayer(uuid) != null;
    }

    @Override
    public void disconnect(UUID uuid, Punishment p) {
        Player pl = Bukkit.getPlayer(uuid);
        if (pl == null) return;
        Component screen = banScreen(p);
        long delay = core.settings.kickDelayTicks;
        // A prisoner being released needs a moment to finish JailReloaded's release before the connection drops.
        if (delay == 0 && core.jail.available() && core.jail.find(uuid).isPresent()) delay = 40;
        if (delay <= 0) {
            pl.kick(screen);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player again = Bukkit.getPlayer(uuid);
                if (again != null) again.kick(screen);
            }, delay);
        }
    }

    @Override
    public void noticeMute(UUID uuid, Punishment p) {
        Player pl = Bukkit.getPlayer(uuid);
        if (pl == null) return;
        for (Component c : core.messages.lines("mute.notice", "reason", p.reason(), "expires", expiry(p, core.clock.now()))) pl.sendMessage(c);
    }

    @Override
    public void noticeUnmute(UUID uuid) {
        Player pl = Bukkit.getPlayer(uuid);
        if (pl != null) pl.sendMessage(core.messages.get("mute.expired"));
    }

    @Override
    public boolean deliverWarning(UUID uuid, Punishment p, boolean late) {
        Player pl = Bukkit.getPlayer(uuid);
        if (pl == null) return false;
        if (late) pl.sendMessage(core.messages.get("warning.delivered-later"));
        for (Component c : core.messages.lines("warning.notice", "reason", p.reason())) pl.sendMessage(c);
        return true;
    }
}
