package dev.aura.punishments.gui;

import dev.aura.punishments.model.CaseStatus;
import dev.aura.punishments.service.AdminService.Counts;
import dev.aura.punishments.service.CaseService.Filter;
import dev.aura.punishments.service.Perms;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** The judge's overview: pending trials, verdicts, sentencing, custody, appeals, emergency reviews. */
final class JudicialDashboard extends Menu {
    private final Counts n;

    private JudicialDashboard(Gui gui, Player viewer, Menu parent, Counts n) {
        super(gui, viewer, parent);
        this.n = n;
    }

    static void open(Gui gui, Player p, Menu parent) {
        gui.call(p, gui.admin2().counts(gui.actor(p)), c -> new JudicialDashboard(gui, p, parent, c).open());
    }

    @Override
    protected Component title() {
        return Items.t("<dark_purple>Judicial dashboard");
    }

    private long c(CaseStatus... s) {
        long t = 0;
        for (CaseStatus x : s) t += n.byStatus().getOrDefault(x, 0L);
        return t;
    }

    @Override
    protected void render() {
        navigation();
        set(4, Items.item(Material.ANVIL, Items.t("<white><b>Judicial dashboard"), List.of(Items.t("<gray>Hearings are held in Discord."),
                Items.t("<gray>Record outcomes here."))));
        action(10, true, Material.CLOCK, "<gold><b>Pending trials", List.of("<gray>Awaiting trial / in trial: <white>" + c(CaseStatus.AWAITING_TRIAL, CaseStatus.IN_TRIAL)), (p, t) ->
                CaseListMenu.open(gui, p, this, "Pending trials", pg -> gui.cases.list(gui.actor(p), Filter.PENDING_TRIAL, pg, 28), null));
        action(11, true, Material.BELL, "<gold><b>Awaiting verdict", List.of("<gray>In trial / awaiting verdict: <white>" + c(CaseStatus.IN_TRIAL, CaseStatus.AWAITING_VERDICT)), (p, t) ->
                CaseListMenu.open(gui, p, this, "Awaiting verdict", pg -> gui.cases.list(gui.actor(p), Filter.AWAITING_VERDICT, pg, 28), null));
        action(12, true, Material.NETHERITE_AXE, "<red><b>Awaiting sentencing", List.of("<gray>Guilty, not yet sentenced: <white>" + c(CaseStatus.GUILTY, CaseStatus.AWAITING_SENTENCING)), (p, t) ->
                CaseListMenu.open(gui, p, this, "Awaiting sentencing", pg -> gui.cases.list(gui.actor(p), Filter.AWAITING_SENTENCE, pg, 28), null));
        action(13, gui.can(viewer, Perms.CUSTODY_VIEW) || gui.can(viewer, Perms.JUDICIAL_VIEW), Material.IRON_BARS, "<gold><b>Custody",
                List.of("<gray>Prisoners held: <white>" + n.liveCustody(), n.overdueCustody() > 0 ? "<red>OVERDUE reviews: " + n.overdueCustody() : "<green>No overdue reviews"), (p, t) ->
                        CustodyListMenu.open(gui, p, this));
        action(14, true, Material.WRITABLE_BOOK, "<aqua><b>Appeals", List.of("<gray>Pending: <white>" + n.pendingAppeals()), (p, t) ->
                CaseListMenu.open(gui, p, this, "Appeals", pg -> gui.cases.list(gui.actor(p), Filter.APPEALS, pg, 28), null));
        action(15, gui.can(viewer, Perms.EMERGENCY_REVIEW), Material.REDSTONE_TORCH, "<red><b>Emergency reviews",
                List.of("<gray>Awaiting review: <white>" + n.unreviewedEmergencies()), (p, t) -> EmergencyReviewList.open(gui, p, this));
        action(16, true, Material.ENCHANTED_BOOK, "<aqua><b>All cases", List.of("<gray>Browse everything"), (p, t) ->
                CaseListMenu.open(gui, p, this, "All cases", pg -> gui.cases.list(gui.actor(p), Filter.ALL, pg, 28), null));
    }
}
