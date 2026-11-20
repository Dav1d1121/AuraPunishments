package dev.aura.punishments.gui;

import dev.aura.punishments.service.CaseService.Filter;
import dev.aura.punishments.service.PlayerResolver.Target;
import org.bukkit.entity.Player;

/** Public entry points into the GUI for commands and other packages. */
public final class Menus {
    private Menus() {}

    public static void openTarget(Gui gui, Player p, Target t) {
        TargetMenu.open(gui, p, null, t);
    }

    public static void openDashboard(Gui gui, Player p) {
        StaffDashboard.open(gui, p);
    }

    public static void openJudicial(Gui gui, Player p) {
        JudicialDashboard.open(gui, p, null);
    }

    public static void openCase(Gui gui, Player p, long id) {
        CaseMenu.open(gui, p, null, id);
    }

    public static void openCases(Gui gui, Player p, Filter f) {
        CaseListMenu.open(gui, p, null, f == Filter.ALL ? "All cases" : "Cases: " + f.name().toLowerCase().replace('_', ' '),
                pg -> gui.cases.list(gui.actor(p), f, pg, 28), null);
    }

    public static void openHistory(Gui gui, Player p, Target t) {
        HistoryMenu.open(gui, p, null, t);
    }

    public static void openAdmin(Gui gui, Player p) {
        AdminMenu.open(gui, p, null);
    }
}
