package dev.aura.punishments.command;

import dev.aura.punishments.gui.Gui;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Name suggestions for staff who may manage other players. Vanished players are not suggested. */
final class PlayerNames {
    private PlayerNames() {}

    static List<String> suggest(Gui gui, CommandSender sender, String prefix) {
        Set<String> out = new LinkedHashSet<>();
        String p = prefix.toLowerCase(Locale.ROOT);
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (sender instanceof Player s && !s.canSee(o)) continue;
            if (o.getName().toLowerCase(Locale.ROOT).startsWith(p)) out.add(o.getName());
        }
        out.addAll(gui.resolver.knownNames(prefix, 25));
        return out.stream().limit(30).toList();
    }
}
