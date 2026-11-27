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
        boolean judicialShown = gui.core().mode.judicialVisible();
        boolean judicialOn = gui.core().mode.enabled();
        List<Component> head = new java.util.ArrayList<>();
        if (judicialShown) {
            head.add(Items.t("<gray>Open cases: <white><v>", "v", String.valueOf(c(CaseStatus.OPEN, CaseStatus.UNDER_INVESTIGATION, CaseStatus.UNDER_REVIEW))));
            head.add(Items.t("<gray>Prisoners in custody: <white><v>", "v", String.valueOf(n.liveCustody())));
        }
        head.add(Items.t("<gray>Open appeals: <white><v>", "v", String.valueOf(n.pendingAppeals())));
        head.add(Items.t("<gray>Active punishments (bans/mutes): <white><v>", "v", String.valueOf(n.cachedPunishments())));
        set(4, Items.item(Material.NETHER_STAR, Items.t("<white><b>AuraPunishments"), head));
        if (!judicialOn && judicialShown && (gui.can(viewer, Perms.ADMIN) || gui.can(viewer, Perms.JUDICIAL_VIEW))) {
            List<Component> w = new java.util.ArrayList<>();
            w.add(Items.t("<gray>The judicial system is switched off, but work is still open."));
            w.add(Items.t("<gray>Nothing is released or resolved automatically:"));
            for (String l : gui.core().mode.report().lines()) w.add(Items.t("<yellow>- <v>", "v", l));
            set(22, Items.item(Material.ORANGE_BANNER, Items.t("<gold><b>Judicial wind-down"), w));
        }

        action(10, gui.can(viewer, Perms.PUNISH) && gui.can(viewer, Perms.PUNISH_OTHERS), Material.PLAYER_HEAD, "<green><b>Find a player",
                List.of("<gray>Open a player's punishment screen", "<gray>(same as /punish <player>)"), (p, t) ->
                        gui.prompts().ask(p, "Type the player's name.", 1, 32, name -> gui.resolve(p, name, tg -> TargetMenu.open(gui, p, this, tg)), this::open));
        boolean view = gui.can(viewer, Perms.CASE_VIEW_ALL) || gui.can(viewer, Perms.CASE_VIEW_OWN);
        if (judicialShown) {
            action(11, view, Material.BOOK, "<aqua><b>My cases", List.of("<gray>Cases you prosecute or judge"), (p, t) ->
                    CaseListMenu.open(gui, p, this, "My cases", pg -> gui.cases.list(gui.actor(p), Filter.MINE, pg, 28), null));
            action(12, gui.can(viewer, Perms.CASE_VIEW_ALL), Material.WRITABLE_BOOK, "<aqua><b>Open cases", List.of("<gray>Open: <white>" + c(CaseStatus.OPEN, CaseStatus.UNDER_INVESTIGATION, CaseStatus.UNDER_REVIEW)), (p, t) ->
                    CaseListMenu.open(gui, p, this, "Open cases", pg -> gui.cases.list(gui.actor(p), Filter.OPEN, pg, 28), null));
            action(14, gui.can(viewer, Perms.JUDICIAL_VIEW), Material.ANVIL, "<light_purple><b>Judicial dashboard",
                    List.of("<gray>Trials, verdicts, sentencing, custody"), (p, t) -> JudicialDashboard.open(gui, p, this));
        }
        // historical case records stay reachable even when the judicial system is off
        action(13, gui.can(viewer, Perms.CASE_VIEW_ALL), Material.ENCHANTED_BOOK, judicialShown ? "<aqua><b>All cases" : "<aqua><b>Case history (read-only)",
                List.of(judicialShown ? "<gray>Every case, newest first" : "<gray>Cases from before the judicial system was switched off"), (p, t) ->
                CaseListMenu.open(gui, p, this, "All cases", pg -> gui.cases.list(gui.actor(p), Filter.ALL, pg, 28), null));
        if (gui.appeals != null) {
            action(17, gui.can(viewer, Perms.APPEAL_DECIDE) || gui.can(viewer, Perms.APPEAL_FILE) || gui.can(viewer, Perms.APPEAL_TICKET) || gui.can(viewer, Perms.JUDICIAL_OVERTURN),
                    Material.LECTERN, "<light_purple><b>Appeals", List.of("<gray>Open appeals against punishments: <white>" + n.pendingAppeals(), "<gray>(handled through Discord Ticket Tool)"),
                    (p, t) -> AppealListMenu.open(gui, p, this));
        }
        action(16, gui.can(viewer, Perms.REVIEW_VIEW), n.overdueReviews() > 0 ? Material.RED_CONCRETE : Material.BLUE_CONCRETE, "<aqua><b>Owner review queue",
                List.of("<gray>Awaiting a decision: <white>" + n.pendingReviews(), n.overdueReviews() > 0 ? "<red>OVERDUE: " + n.overdueReviews() : "<green>None overdue"), (p, t) ->
                        ReviewQueueMenu.open(gui, p, this, false));
        action(19, gui.can(viewer, Perms.PUNISH), Material.NAME_TAG, "<aqua><b>My punishments under review",
                List.of("<gray>Quick punishments you issued and", "<gray>their review status / evidence requests"), (p, t) -> ReviewQueueMenu.open(gui, p, this, true));
        action(20, (judicialShown && gui.can(viewer, Perms.JUDICIAL_VIEW)) || gui.can(viewer, Perms.REVIEW_VIEW) || gui.can(viewer, Perms.EMERGENCY_REVIEW), Material.HOPPER, "<light_purple><b>Work queue",
                List.of("<gray>Everything waiting for a decision,", "<gray>most urgent first"), (p, t) -> WorkQueueMenu.open(gui, p, this));
        action(15, gui.can(viewer, Perms.ADMIN) || gui.can(viewer, Perms.AUDIT_VIEW), Material.COMMAND_BLOCK, "<gold><b>Administration",
                List.of("<gray>Diagnostics, audit log, reload"), (p, t) -> AdminMenu.open(gui, p, this));
    }
}
