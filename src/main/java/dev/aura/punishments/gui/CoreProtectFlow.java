package dev.aura.punishments.gui;

import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.service.CoreProtectPort;
import dev.aura.punishments.service.CoreProtectPort.Kind;
import dev.aura.punishments.util.Durations;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Read-only CoreProtect lookup for a case: choose a kind, enter a time window, preview, optionally attach as evidence. */
final class CoreProtectFlow {
    private CoreProtectFlow() {}

    static void start(Gui gui, Player p, Menu parent, Case c) {
        new ChoiceMenu(gui, p, parent, "CoreProtect lookup (read-only)", Items.item(Material.COMPASS, "<green>" + c.accusedName(), "<gray>Nothing is rolled back."), List.of(
                choice(Material.GRASS_BLOCK, "<green>Block activity", "Places/breaks near where you stand", () -> ask(gui, p, parent, c, Kind.BLOCKS)),
                choice(Material.WRITABLE_BOOK, "<aqua>Chat", "Chat messages", () -> ask(gui, p, parent, c, Kind.CHAT)),
                choice(Material.COMMAND_BLOCK, "<gold>Commands", "Commands typed", () -> ask(gui, p, parent, c, Kind.COMMANDS)),
                choice(Material.CLOCK, "<yellow>Sessions", "Logins / logouts", () -> ask(gui, p, parent, c, Kind.SESSIONS)))).open();
    }

    private static ChoiceMenu.Choice choice(Material m, String name, String lore, Runnable run) {
        return new ChoiceMenu.Choice(m, name, List.of("<gray>" + lore), true, run);
    }

    private static void ask(Gui gui, Player p, Menu parent, Case c, Kind kind) {
        var cfg = gui.settings().coreProtect;
        String q = "How far back? e.g. 2h or 1d (max " + Durations.format(cfg.maxTime()) + ")" + (kind == Kind.BLOCKS ? ", optionally followed by a radius, e.g. '2h 20' (max " + cfg.maxRadius() + ")." : ".");
        gui.prompts().ask(p, q, 1, 40, text -> {
            String[] parts = text.trim().split("\\s+");
            long ms = Durations.parse(parts[0]);
            if (ms <= 0) {
                gui.messages().sendError(p, "That is not a valid time window.");
                parent.open();
                return;
            }
            int radius = 20;
            if (parts.length > 1) {
                try {
                    radius = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    gui.messages().sendError(p, "The radius must be a number.");
                    parent.open();
                    return;
                }
            }
            Location l = p.getLocation();
            CoreProtectPort.Query query = new CoreProtectPort.Query(c.accusedName(), kind, ms / 1000, l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), radius);
            gui.call(p, gui.coreProtect.lookup(gui.actor(p), query), res -> {
                gui.messages().sendInfo(p, "CoreProtect " + kind.name().toLowerCase() + ": " + res.total() + " result(s)" + (res.truncated() ? " (first " + res.lines().size() + " shown)" : "") + ".");
                for (String line : res.lines()) p.sendMessage(Items.t("<gray> - <white><v>", "v", line));
                new ConfirmMenu(gui, p, parent, "Attach as evidence?", List.of("Stores this snapshot on " + c.ref() + " as CoreProtect evidence.",
                        "It does not prove anything by itself and rolls nothing back."), null, () ->
                        gui.call(p, gui.coreProtect.attach(gui.actor(p), c.id(), query, res), ev -> {
                            gui.success(p, "Snapshot attached as evidence #" + ev.id() + ".");
                            parent.open();
                        })).open();
            }, parent::open);
        }, parent::open);
    }
}
