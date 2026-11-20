package dev.aura.punishments.command;

import dev.aura.punishments.gui.Gui;
import dev.aura.punishments.gui.Menus;
import dev.aura.punishments.service.Perms;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /punishments : the general staff dashboard. */
public final class PunishmentsCommand implements CommandExecutor {
    private final Gui gui;

    public PunishmentsCommand(Gui gui) {
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            gui.messages().send(sender, "command.players-only");
            return true;
        }
        if (!p.hasPermission(Perms.DASHBOARD)) {
            gui.messages().send(p, "command.no-permission");
            return true;
        }
        Menus.openDashboard(gui, p);
        return true;
    }
}
