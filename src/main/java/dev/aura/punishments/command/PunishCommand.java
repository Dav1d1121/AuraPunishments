package dev.aura.punishments.command;

import dev.aura.punishments.gui.Gui;
import dev.aura.punishments.gui.Menus;
import dev.aura.punishments.service.Perms;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /punish &lt;player&gt; : opens that player's disciplinary GUI directly. */
public final class PunishCommand implements CommandExecutor, TabCompleter {
    private final Gui gui;

    public PunishCommand(Gui gui) {
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            gui.messages().send(sender, "command.players-only");
            return true;
        }
        if (!p.hasPermission(Perms.PUNISH)) {
            gui.messages().send(p, "command.no-permission");
            return true;
        }
        if (args.length != 1) {
            gui.messages().send(p, "command.usage-punish");
            return true;
        }
        if (!p.hasPermission(Perms.PUNISH_OTHERS)) {
            gui.messages().sendError(p, "You do not have permission to manage other players' disciplinary records.");
            return true;
        }
        gui.resolve(p, args[0], t -> Menus.openTarget(gui, p, t));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission(Perms.PUNISH) || !sender.hasPermission(Perms.PUNISH_OTHERS)) return List.of();
        return PlayerNames.suggest(gui, sender, args[0]);
    }
}
