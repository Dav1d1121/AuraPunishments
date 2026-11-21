package dev.aura.punishments.integration;

import dev.aura.punishments.gui.Gui;
import dev.aura.punishments.service.Core;
import dev.aura.punishments.service.DomainEvent;
import dev.aura.punishments.service.Perms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Routes domain events to in-game staff alerts, the console and Discord. */
public final class BukkitNotifier implements DomainEvent.Sink {
    private static final Pattern DISCORD_TS = Pattern.compile("<t:(\\d+)(?::[A-Za-z])?>");

    private final Plugin plugin;
    private final Core core;
    private final DiscordService discord;

    public BukkitNotifier(Plugin plugin, Core core, DiscordService discord) {
        this.plugin = plugin;
        this.core = core;
        this.discord = discord;
    }

    @Override
    public void emit(DomainEvent e) {
        discord.send(e);
        Bukkit.getScheduler().runTask(plugin, () -> inGame(e));
    }

    private static String humanize(String s) {
        if (s == null) return "";
        Matcher m = DISCORD_TS.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(Gui.time(Long.parseLong(m.group(1)) * 1000)));
        m.appendTail(sb);
        return sb.toString();
    }

    private void inGame(DomainEvent e) {
        if (core.messages == null) return;
        String ref = e.caseId() == null ? "-" : "AP-" + String.format("%04d", e.caseId());
        Component msg = core.messages.get("alert." + e.type().discordKey, "ref", ref, "player", e.player(), "actor", e.actor(),
                "detail", humanize(e.summary()), "offense", e.fields().getOrDefault("Offense", ""),
                "deadline", humanize(e.fields().getOrDefault("Review deadline", "")));
        plugin.getLogger().info(PlainTextComponentSerializer.plainText().serialize(msg));
        if (!core.settings.notify(e.type().notifyKey)) return;
        if (e.type().category.equals(DomainEvent.Cat.OWNER_REVIEW) && !core.settings.review.notifyInGame()) return;
        String node = Perms.notify(e.type().notifyKey);
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean emergency = e.type() == DomainEvent.Type.EMERGENCY && p.hasPermission(Perms.EMERGENCY_REVIEW);
            if (p.hasPermission(node) || emergency) p.sendMessage(msg);
        }
    }
}
