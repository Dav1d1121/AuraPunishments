package dev.aura.punishments.gui;

import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.service.AdminService.Counts;
import dev.aura.punishments.service.CaseService.Filter;
import dev.aura.punishments.service.Perms;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** /punishments: the general staff dashboard. */
final class StaffDashboard extends Menu {
    private final Counts n;

    private StaffDashboard(Gui gui, Player viewer, Counts n) {
        super(gui, viewer, null);
        this.n = n;
    }

    static void open(Gui gui, Player p) {
        gui.call(p, gui.admin2().counts(gui.actor(p)), c -> new StaffDashboard(gui, p, c).open());
    }

    @Override
    protected Component title() {
        return Items.t("<gradient:#c084fc:#60a5fa>Punishments dashboard</gradient>");
    }

    private long c(CaseStatus... s) {
        long t = 0;
        for (CaseStatus x : s) t += n.byStatus().getOrDefault(x, 0L);
        return t;
    }

    @Override
    protected void render() {
        fill(0, 53);
        set(49, Items.item(Material.BARRIER, "<red>Close"), (p, t) -> p.closeInventory());
        set(4, Items.item(Material.NETHER_STAR, Items.t("<white><b>AuraPunishments"), List.of(
                Items.t("<gray>Open cases: <white><v>", "v", String.valueOf(c(CaseStatus.OPEN, CaseStatus.UNDER_INVESTIGATION, CaseStatus.UNDER_REVIEW))),
                Items.t("<gray>Prisoners in custody: <white><v>", "v", String.valueOf(n.liveCustody())),
                Items.t("<gray>Active punishments (bans/mutes): <white><v>", "v", String.valueOf(n.cachedPunishments())))));

        action(10, gui.can(viewer, Perms.PUNISH) && gui.can(viewer, Perms.PUNISH_OTHERS), Material.PLAYER_HEAD, "<green><b>Find a player",
                List.of("<gray>Open a player's punishment screen", "<gray>(same as /punish <player>)"), (p, t) ->
                        gui.prompts().ask(p, "Type the player's name.", 1, 32, name -> gui.resolve(p, name, tg -> TargetMenu.open(gui, p, this, tg)), this::open));
        boolean view = gui.can(viewer, Perms.CASE_VIEW_ALL) || gui.can(viewer, Perms.CASE_VIEW_OWN);
        action(11, view, Material.BOOK, "<aqua><b>My cases", List.of("<gray>Cases you prosecute or judge"), (p, t) ->
                CaseListMenu.open(gui, p, this, "My cases", pg -> gui.cases.list(gui.actor(p), Filter.MINE, pg, 28), null));
        action(12, gui.can(viewer, Perms.CASE_VIEW_ALL), Material.WRITABLE_BOOK, "<aqua><b>Open cases", List.of("<gray>Open: <white>" + c(CaseStatus.OPEN, CaseStatus.UNDER_INVESTIGATION, CaseStatus.UNDER_REVIEW)), (p, t) ->
                CaseListMenu.open(gui, p, this, "Open cases", pg -> gui.cases.list(gui.actor(p), Filter.OPEN, pg, 28), null));
        action(13, gui.can(viewer, Perms.CASE_VIEW_ALL), Material.ENCHANTED_BOOK, "<aqua><b>All cases", List.of("<gray>Every case, newest first"), (p, t) ->
                CaseListMenu.open(gui, p, this, "All cases", pg -> gui.cases.list(gui.actor(p), Filter.ALL, pg, 28), null));
        action(14, gui.can(viewer, Perms.JUDICIAL_VIEW), Material.ANVIL, "<light_purple><b>Judicial dashboard",
                List.of("<gray>Trials, verdicts, sentencing, custody"), (p, t) -> JudicialDashboard.open(gui, p, this));
        action(15, gui.can(viewer, Perms.ADMIN) || gui.can(viewer, Perms.AUDIT_VIEW), Material.COMMAND_BLOCK, "<gold><b>Administration",
                List.of("<gray>Diagnostics, audit log, reload"), (p, t) -> AdminMenu.open(gui, p, this));
    }
}
