package dev.aura.punishments.listener;

import dev.aura.punishments.gui.PromptManagerAccess;
import dev.aura.punishments.integration.BukkitEnforcement;
import dev.aura.punishments.model.Records.Custody;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.service.Core;
import dev.aura.punishments.service.CustodyService;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PunishmentService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Command-level enforcement:
 * <ol>
 *   <li>a running chat prompt is cancelled when the player runs a command;</li>
 *   <li>muted players cannot use private-message, emote or chat-channel commands (EssentialsX, TownyChat);</li>
 *   <li>legacy punishment commands (/ban, /mute, /kick, ...) are redirected to AuraPunishments;</li>
 *   <li>JailReloaded commands that would release or alter a prisoner held for an open case are refused unless
 *       the sender holds aurapunishments.custody.override.</li>
 * </ol>
 * Command aliases are resolved through the server's command map, so every alias of a blocked command is covered.
 */
public final class CommandGuardListener implements Listener {
    private final Core core;
    private final PunishmentService punishments;
    private final CustodyService custody;
    private final PromptManagerAccess prompts;

    public CommandGuardListener(Core core, PunishmentService punishments, CustodyService custody, PromptManagerAccess prompts) {
        this.core = core;
        this.punishments = punishments;
        this.custody = custody;
        this.prompts = prompts;
    }

    /** All names a typed label may refer to: itself, without namespace, the real command name and its aliases. */
    static Set<String> names(String label) {
        Set<String> out = new HashSet<>();
        String l = label.toLowerCase(Locale.ROOT);
        out.add(l);
        String bare = l.contains(":") ? l.substring(l.indexOf(':') + 1) : l;
        out.add(bare);
        try {
            Command c = Bukkit.getCommandMap().getCommand(l);
            if (c == null) c = Bukkit.getCommandMap().getCommand(bare);
            if (c != null) {
                out.add(c.getName().toLowerCase(Locale.ROOT));
                for (String a : c.getAliases()) out.add(a.toLowerCase(Locale.ROOT));
            }
        } catch (RuntimeException ignored) {
            // command map not available: fall back to the typed label
        }
        return out;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        String msg = e.getMessage().startsWith("/") ? e.getMessage().substring(1) : e.getMessage();
        String[] parts = msg.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return;
        String label = parts[0].toLowerCase(Locale.ROOT);
        String bare = label.contains(":") ? label.substring(label.indexOf(':') + 1) : label;

        // 1. leave a pending chat prompt (the command itself is allowed to run)
        if (prompts.has(p) && !bare.equals("ap") && !bare.equals("aurapunishments")) prompts.cancel(p, true);

        Set<String> names = names(label);

        // 2. mute enforcement
        Optional<Punishment> mute = punishments.muteFor(p.getUniqueId());
        if (mute.isPresent() && !p.hasPermission("aurapunishments.mute.bypass")) {
            var s = core.settings.mute;
            boolean allowed = names.stream().anyMatch(s.allowedCommands()::contains);
            boolean blocked = names.stream().anyMatch(s.blockedCommands()::contains);
            if (!blocked && parts.length > 1) {
                String sub = bare + " " + parts[1].toLowerCase(Locale.ROOT);
                for (String n : names) if (s.blockedSubcommands().contains(n + " " + parts[1].toLowerCase(Locale.ROOT)) || s.blockedSubcommands().contains(sub)) blocked = true;
            }
            if (blocked && !allowed) {
                e.setCancelled(true);
                p.sendMessage(core.messages.get("mute.blocked", "expires", BukkitEnforcement.expiry(mute.get(), core.clock.now())));
                return;
            }
        }

        // 3. legacy punishment commands
        if (core.settings.legacy.block() && !p.hasPermission(Perms.LEGACY_BYPASS)) {
            if (names.stream().anyMatch(core.settings.legacy.blocked()::contains)) {
                e.setCancelled(true);
                p.sendMessage(core.messages.get("command.legacy-blocked", "command", bare));
                return;
            }
        }

        // 4. JailReloaded custody guard
        if (core.settings.custody.guardJailCommands() && !p.hasPermission(Perms.CUSTODY_OVERRIDE)) {
            Set<String> guarded = core.settings.custody.guardedJailCommands();
            if (names.stream().anyMatch(guarded::contains) && parts.length > 1) {
                List<String> targets = guardedTargets(bare, parts);
                for (String t : targets) {
                    Optional<Custody> held = custody.heldByName(t);
                    if (held.isPresent()) {
                        e.setCancelled(true);
                        p.sendMessage(core.messages.get("command.custody-guard", "ref", CustodyService.tag(held.get().caseId())));
                        return;
                    }
                }
            }
        }
    }

    /** Which argument names a prisoner for this JailReloaded command form. */
    private static List<String> guardedTargets(String bare, String[] parts) {
        if (bare.equals("jail") || bare.equals("jailreloaded:jail")) {
            if (parts.length > 2) {
                String sub = parts[1].toLowerCase(Locale.ROOT);
                if (Set.of("time", "transfer", "clear", "clearforce", "unjail", "unjailforce", "mute", "stop", "tele", "telein", "teleout").contains(sub)) {
                    return List.of(parts[2]);
                }
            }
            return List.of(parts[1]); // /jail <player> ... would re-jail a held prisoner
        }
        return List.of(parts[1]);
    }
}
