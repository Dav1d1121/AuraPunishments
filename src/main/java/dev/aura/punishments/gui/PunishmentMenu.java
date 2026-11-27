package dev.aura.punishments.gui;

import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Appeal;
import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Simple.AppealStatus;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.service.JailService;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PunishmentRecords.Detail;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything about ONE punishment, whatever created it: its details, the evidence and staff notes attached to it, any
 * appeals (with their Discord Ticket Tool reference), the owner review, the case it came from, and the actions staff may
 * take (revoke, release from jail, decide an appeal). This is where evidence is added after the fact when the judicial
 * system is off, and where appeals are recorded and decided.
 */
final class PunishmentMenu extends Menu {
    private final long id;
    private Detail d;

    private PunishmentMenu(Gui gui, Player viewer, Menu parent, long id, Detail d) {
        super(gui, viewer, parent);
        this.id = id;
        this.d = d;
    }

    static void open(Gui gui, Player p, Menu parent, long punishmentId) {
        gui.call(p, gui.records.detail(gui.actor(p), punishmentId), d -> new PunishmentMenu(gui, p, parent, punishmentId, d).open());
    }

    private void reload() {
        gui.call(viewer, gui.records.detail(gui.actor(viewer), id), nd -> {
            this.d = nd;
            open();
        });
    }

    @Override
    protected Component title() {
        return Items.t("<dark_purple>Punishment #<v> <gray>(<t>)", "v", String.valueOf(id), "t", d.punishment().type().display());
    }

    private boolean can(String node) {
        return viewer.hasPermission(node);
    }

    private boolean mayDecide() {
        return can(Perms.APPEAL_DECIDE) || (gui.core().mode.enabled() && can(Perms.JUDICIAL_OVERTURN));
    }

    private Appeal openAppeal() {
        return d.appeals().stream().filter(a -> a.status().open()).findFirst().orElse(null);
    }

    @Override
    protected void render() {
        navigation();
        Punishment p = d.punishment();
        long now = gui.core().clock.now();
        List<Component> detail = new ArrayList<>(Views.punishmentItem(p, can(Perms.SENSITIVE_IP), now).getItemMeta().lore());
        if (p.type() == PunishType.JAIL && p.jailState() != null) {
            detail.add(Items.t("<gray>Jail: <white><v>", "v", JailService.describe(p)));
            if (p.jailError() != null) detail.add(Items.t("<red><v>", "v", Text.truncate(p.jailError(), 60)));
        }
        if (p.modifiedAt() != null) {
            detail.add(Items.t("<yellow>Modified by <v> on <t>", "v", String.valueOf(p.modifiedBy()), "t", Gui.time(p.modifiedAt())));
        }
        set(4, Items.item(Views.punishIcon(p.type()), Items.t("<white><b>#<id> <t>", "id", String.valueOf(p.id()), "t", p.type().display()), detail));

        // ---- evidence
        long active = d.evidence().stream().filter(e -> !e.voided()).count();
        action(10, !d.evidenceHidden() || d.mayAddEvidence(), Material.SPYGLASS, "<yellow><b>Evidence <gray>(" + (d.evidenceHidden() ? "hidden" : active) + ")",
                List.of("<gray>Screenshots, logs, links and reports", d.mayAddEvidence() ? "<gray>Click to view or add evidence" : "<gray>Click to view"),
                (pl, t) -> EvidenceMenu.openForPunishment(gui, pl, this, id));
        // ---- notes
        action(11, !d.notesHidden() || d.mayAddNote(), Material.OAK_SIGN, "<yellow><b>Staff notes <gray>(" + (d.notesHidden() ? "hidden" : d.notes().stream().filter(n -> !n.voided()).count()) + ")",
                List.of("<gray>Notes about this punishment"), (pl, t) -> NotesMenu.openForPunishment(gui, pl, this, id));

        // ---- appeals
        Appeal open = openAppeal();
        List<String> ap = new ArrayList<>();
        if (d.appeals().isEmpty()) ap.add("<gray>No appeals");
        for (Appeal a : d.appeals()) {
            ap.add("<gray>#" + a.id() + " by <white>" + a.filedByName() + " <gray>- <white>" + a.status().name().toLowerCase()
                    + (a.ticket() == null ? "" : " <dark_gray>[ticket " + a.ticket() + "]"));
            if (a.decidedByName() != null) ap.add("<dark_gray>  decided by " + a.decidedByName() + ": " + Text.truncate(String.valueOf(a.decisionReason()), 36));
            else if (a.assignedName() != null) ap.add("<dark_gray>  reviewer: " + a.assignedName());
        }
        boolean viaCase = gui.appeals.viaCase(p);
        boolean mayFile = can(Perms.APPEAL_FILE) && open == null && !viaCase && (p.status() == PunishStatus.ACTIVE || p.status() == PunishStatus.COMPLETED);
        if (mayFile) ap.add("<yellow>Click to record an appeal");
        if (viaCase) ap.add("<dark_gray>Appeals for this punishment are recorded on its case");
        action(13, !d.appeals().isEmpty() || mayFile, Material.WRITABLE_BOOK, "<light_purple><b>Appeals", ap, (pl, t) -> {
            if (mayFile) fileAppeal(pl);
        });
        if (open != null && (can(Perms.APPEAL_TICKET) || mayDecide())) {
            action(22, true, Material.NAME_TAG, "<aqua><b>Appeal ticket", List.of("<gray>Ticket Tool ticket: <white>" + (open.ticket() == null ? "none" : open.ticket()),
                    "<gray>Click to set or change it"), (pl, t) -> gui.prompts().ask(pl, "Enter the Ticket Tool ticket number (for example 0042) or the ticket channel link.", 1, 200,
                    text -> gui.call(pl, gui.appeals.linkTicket(gui.actor(pl), open.id(), text), x -> {
                        gui.success(pl, "Ticket linked.");
                        reload();
                    }, this::open), this::open));
        }
        if (open != null && mayDecide()) {
            if (open.status() == AppealStatus.PENDING) {
                action(31, true, Material.LECTERN, "<light_purple><b>Take appeal", List.of("<gray>Mark yourself as the reviewer"), (pl, t) ->
                        gui.call(pl, gui.appeals.take(gui.actor(pl), open.id()), x -> {
                            gui.success(pl, "You are reviewing this appeal.");
                            reload();
                        }, this::open));
            }
            action(32, true, Material.ANVIL, "<light_purple><b>Decide appeal", List.of("<gray>Uphold, reduce or overturn", "<dark_gray>The punishment stays in force until you decide"),
                    (pl, t) -> decideMenu(open));
        }

        // ---- owner review, case
        if (d.review() != null && (can(Perms.REVIEW_VIEW) || d.issuer())) {
            action(15, true, Material.BOOK, "<aqua><b>Owner review <gray>(" + d.review().status().display() + ")", List.of("<gray>" + d.review().ref(), "<gray>Click to open"),
                    (pl, t) -> ReviewMenu.open(gui, pl, this, d.review().id()));
        }
        if (d.linkedCase() != null) {
            action(16, true, Material.ENCHANTED_BOOK, "<aqua><b>Case " + d.linkedCase().ref(), List.of("<gray>This punishment came from a case", "<gray>Click to open it"),
                    (pl, t) -> CaseMenu.open(gui, pl, this, d.linkedCase().id()));
        }

        // ---- emergency review (a case-free emergency action is reviewed here)
        if (p.source() == dev.aura.punishments.model.Simple.Source.EMERGENCY && !p.reviewed() && p.caseId() == null && can(Perms.EMERGENCY_REVIEW)) {
            action(33, true, Material.REDSTONE_TORCH, "<red><b>Review emergency action", List.of("<gray>Uphold it (it runs to its expiry) or revoke it",
                    p.reviewDeadline() != null ? "<gray>Review due: <white>" + Gui.time(p.reviewDeadline()) : "<gray>No deadline"), (pl, t) -> emergencyReview());
        }

        // ---- revoke / release
        boolean live = p.status() == PunishStatus.ACTIVE || p.status() == PunishStatus.COMPLETED;
        boolean own = d.issuer() && p.source().quickLike();
        boolean releaseRight = p.type() == PunishType.JAIL && (can(Perms.CUSTODY_RELEASE) || can(Perms.JUDICIAL_RELEASE));
        boolean mayRevoke = live && (can(Perms.REVOKE_ANY) || (own && can(Perms.REVOKE)) || releaseRight);
        boolean jailPending = p.type() == PunishType.JAIL && JailService.live(p.jailState()) && p.status() != PunishStatus.ACTIVE;
        if (mayRevoke) {
            boolean jail = p.type() == PunishType.JAIL;
            action(28, true, jail ? Material.IRON_DOOR : Material.ENDER_EYE, jail ? "<green><b>Release from jail" : "<green><b>Revoke punishment",
                    List.of(jail ? "<gray>Ends the sentence now and frees the player" : "<gray>Lift it now with a recorded reason"),
                    (pl, t) -> {
                        if (jail) Pickers.releaseJail(gui, pl, this, p, this::reload);
                        else Pickers.revoke(gui, pl, this, p, this::reload);
                    });
        } else if (jailPending) {
            action(28, can(Perms.REVOKE_ANY) || releaseRight, Material.IRON_DOOR, "<gold><b>Retry release",
                    List.of("<gray>The sentence was revoked but " + p.targetName() + " is not out of jail yet", "<gray>(JailReloaded unavailable or player offline)"),
                    (pl, t) -> gui.call(pl, gui.jails.unjail(gui.actor(pl), p.targetUuid(), "Retry of an accepted release"), x -> {
                        gui.success(pl, "Release requested.");
                        reload();
                    }, this::open));
        }
    }

    private void emergencyReview() {
        Punishment p = d.punishment();
        List<ChoiceMenu.Choice> ch = new ArrayList<>();
        for (var rd : new dev.aura.punishments.service.DirectService.ReviewDecision[]{dev.aura.punishments.service.DirectService.ReviewDecision.REVOKE_AND_DISMISS}) {
            ch.add(new ChoiceMenu.Choice(Material.RED_CONCRETE, "<red><b>Revoke", List.of("<gray>Lifts the action now"), true, () -> reviewWith(rd)));
        }
        ch.add(0, new ChoiceMenu.Choice(Material.LIME_CONCRETE, "<green><b>Uphold", List.of("<gray>The action stays and runs to its expiry"), true, () -> reviewWith(null)));
        new ChoiceMenu(gui, viewer, this, "Review emergency #" + p.id(), null, ch).open();
    }

    private void reviewWith(dev.aura.punishments.service.DirectService.ReviewDecision rd) {
        gui.prompts().ask(viewer, "Enter the review reasoning.", gui.settings().cases.minReason(), gui.settings().cases.maxReason(), why ->
                gui.call(viewer, gui.direct.reviewEmergencyAction(gui.actor(viewer), id, rd == null ? dev.aura.punishments.service.DirectService.ReviewDecision.UPHOLD : rd, why), x -> {
                    gui.success(viewer, "Emergency action reviewed.");
                    reload();
                }, this::open), this::open);
    }

    private void fileAppeal(Player pl) {
        int min = Math.max(gui.settings().cases.minReason(), gui.settings().appeals.minReason());
        gui.prompts().ask(pl, "State the grounds for the appeal (what the player says).", min, gui.settings().cases.maxDescription(), grounds ->
                gui.prompts().ask(pl, "Ticket Tool ticket number or link" + (gui.settings().appeals.requireTicket() ? "" : " (type - if there is none yet)") + ".", 1, 200, ticketText -> {
                    String ticket = ticketText.trim().equals("-") ? null : ticketText;
                    gui.call(pl, gui.appeals.file(gui.actor(pl), id, grounds, ticket), a -> {
                        gui.success(pl, "Appeal #" + a.id() + " recorded. The punishment stays in force until it is decided.");
                        reload();
                    }, this::open);
                }, this::open), this::open);
    }

    private void decideMenu(Appeal a) {
        Punishment p = d.punishment();
        List<ChoiceMenu.Choice> ch = new ArrayList<>();
        ch.add(new ChoiceMenu.Choice(Material.LIME_CONCRETE, "<white><b>UPHOLD", List.of("<gray>The punishment stands"), true, () ->
                decide(a, AppealStatus.UPHELD, null)));
        boolean reducible = p.type().canBeTimed() && p.status() == PunishStatus.ACTIVE;
        ch.add(new ChoiceMenu.Choice(Material.YELLOW_CONCRETE, "<white><b>REDUCE", List.of("<gray>Shorten the punishment", reducible ? "<gray>Next: enter the new total length" : "<red>Only an active timed punishment can be reduced"),
                reducible, () -> Pickers.customDuration(gui, viewer, false, ms -> decide(a, AppealStatus.MODIFIED, ms), this::open)));
        ch.add(new ChoiceMenu.Choice(Material.RED_CONCRETE, "<white><b>OVERTURN", List.of("<gray>Revokes the punishment now"), true, () ->
                decide(a, AppealStatus.OVERTURNED, null)));
        new ChoiceMenu(gui, viewer, this, "Appeal #" + a.id(), null, ch).open();
    }

    private void decide(Appeal a, AppealStatus decision, Long newDuration) {
        int min = Math.max(5, gui.settings().judicial.minVerdictReason());
        gui.prompts().ask(viewer, "Explain the decision (recorded with the appeal).", min, 1000, why -> {
            List<String> lines = new ArrayList<>();
            lines.add("Punishment #" + id + " (" + d.punishment().type().display() + ") on " + d.punishment().targetName());
            lines.add("Decision: " + decision.name().toLowerCase().replace("modified", "reduced"));
            if (newDuration != null) lines.add("New length: " + Durations.format(newDuration));
            lines.add("Reasoning: " + why);
            new ConfirmMenu(gui, viewer, this, "Appeal " + decision.name().toLowerCase().replace("modified", "reduced"), lines, null, () ->
                    gui.call(viewer, gui.appeals.decide(gui.actor(viewer), a.id(), decision, why, newDuration), x -> {
                        gui.success(viewer, "Appeal " + decision.name().toLowerCase().replace("modified", "reduced") + ".");
                        reload();
                    }, this::open)).open();
        }, this::open);
    }
}
