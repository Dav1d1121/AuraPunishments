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

import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import dev.aura.punishments.config.Messages;
import dev.aura.punishments.config.Settings;
import dev.aura.punishments.model.Records.Notification;
import dev.aura.punishments.service.NotificationService;
import dev.aura.punishments.service.ReviewService;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;

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

    /** The proxy's own plugin-message channel ("BungeeCord" is the name Velocity and BungeeCord both answer to). */
    public static final String PROXY_CHANNEL = "BungeeCord";

    @Override
    public boolean transfer(UUID uuid, String proxyServer) {
        Player pl = Bukkit.getPlayer(uuid);
        if (pl == null || proxyServer == null || proxyServer.isBlank()) return false;
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(proxyServer);
            pl.sendPluginMessage(plugin, PROXY_CHANNEL, bytes.toByteArray());
            return true;
        } catch (java.io.IOException | RuntimeException e) {
            core.log.warning("Could not ask the proxy to move " + pl.getName() + " to '" + proxyServer + "': " + e.getMessage());
            return false;
        }
    }

    @Override
    public void hint(UUID uuid, String messageKey) {
        Player pl = Bukkit.getPlayer(uuid);
        if (pl != null) pl.sendActionBar(core.messages.get(messageKey));
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

    /** Set by the plugin: true while the player is answering a chat prompt (their title is then not replaced). */
    private volatile java.util.function.Predicate<UUID> prompting = u -> false;

    public void promptCheck(java.util.function.Predicate<UUID> p) {
        this.prompting = p == null ? u -> false : p;
    }

    @Override
    public boolean deliverNotification(UUID uuid, Notification n, boolean late) {
        Player pl = Bukkit.getPlayer(uuid);
        if (pl == null || !pl.isOnline()) return false;
        Map<String, String> data = NotificationService.payload(n);
        if (NotificationService.WARNING.equals(n.kind())) {
            if (!presentWarning(pl, data, late)) return false;
        } else if (ReviewService.STAFF.equals(n.kind())) {
            String key = "staffnote." + data.getOrDefault("event", "generic");
            pl.sendMessage(core.messages.get(core.messages.has(key) ? key : "staffnote.generic", "ref", data.getOrDefault("ref", ""),
                    "player", data.getOrDefault("player", ""), "detail", data.getOrDefault("detail", ""), "by", data.getOrDefault("by", "")));
        } else {
            presentDefendant(pl, data, late);
        }
        return true;
    }

    /** Reason shortened for a title line: control characters removed, never split inside a surrogate pair. */
    public static String shorten(String reason, int max) {
        String t = reason == null ? "" : reason.replaceAll("[\\p{Cntrl}\\n\\r]+", " ").trim();
        if (t.codePointCount(0, t.length()) <= max) return t;
        int end = t.offsetByCodePoints(0, Math.max(1, max - 1));
        return t.substring(0, end).stripTrailing() + "\u2026";
    }

    /** @return false when nothing at all could be shown (so the warning stays queued instead of being lost). */
    private boolean presentWarning(Player pl, Map<String, String> d, boolean late) {
        boolean shown = false;
        Settings.Warnings w = core.settings.warnings;
        String reason = d.getOrDefault("reason", "");
        String count = d.getOrDefault("count", "1");
        if (w.chat()) {
            if (late) pl.sendMessage(core.messages.get("warning.delivered-later"));
            for (Component c : core.messages.lines("warning.notice", "reason", reason, "count", count)) pl.sendMessage(c);
            shown = true;
        }
        try {
            if (w.title().enabled() && !(w.deferTitleWhilePrompting() && prompting.test(pl.getUniqueId()))) {
                Settings.WarnTitle t = w.title();
                MiniMessage mm = Messages.mini();
                Component sub = t.showReason()
                        ? mm.deserialize(t.subtitleWithReason(), Placeholder.unparsed("reason", shorten(reason, t.reasonMaxChars())))
                        : mm.deserialize(t.subtitle());
                pl.showTitle(Title.title(mm.deserialize(t.title()), sub, Title.Times.times(
                        Duration.ofMillis(t.fadeIn() * 50L), Duration.ofMillis(t.stay() * 50L), Duration.ofMillis(t.fadeOut() * 50L))));
                shown = true;
            }
            if (w.sound().enabled()) {
                Settings.WarnSound so = w.sound();
                pl.playSound(Sound.sound(Key.key(so.sound()), Sound.Source.valueOf(so.source().toUpperCase(java.util.Locale.ROOT)), so.volume(), so.pitch()));
            }
            if (w.actionBar()) {
                pl.sendActionBar(Messages.mini().deserialize(w.actionBarText()));
                shown = true;
            }
        } catch (RuntimeException e) { // a bad sound key or MiniMessage typo must never lose the warning itself
            core.log.warning("Warning presentation problem (the chat warning was still delivered): " + e.getMessage());
        }
        if (!shown) { // chat disabled and title deferred: show the plain chat line rather than lose the warning
            for (Component c : core.messages.lines("warning.notice", "reason", reason, "count", count)) pl.sendMessage(c);
        }
        return true;
    }

    private void presentDefendant(Player pl, Map<String, String> d, boolean late) {
        String event = d.getOrDefault("event", "STATUS").toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        String key = "defendant." + event;
        String[] kv = {"ref", d.getOrDefault("ref", ""), "offense", d.getOrDefault("offense", ""), "reason", d.getOrDefault("reason", ""),
                "detail", d.getOrDefault("detail", ""), "instructions", d.getOrDefault("instructions", ""), "status", d.getOrDefault("status", "")};
        if (late) pl.sendMessage(core.messages.get("defendant.delivered-later"));
        for (Component c : core.messages.lines(key, kv)) pl.sendMessage(c);
        if (core.settings.defendant.sound()) {
            try {
                pl.playSound(Sound.sound(Key.key("block.note_block.chime"), Sound.Source.MASTER, 0.6f, 1.0f));
            } catch (RuntimeException ignored) {
                // cosmetic
            }
        }
    }
}
