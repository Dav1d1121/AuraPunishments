package dev.aura.punishments.gui;

import dev.aura.punishments.model.Records.Punishment;
import dev.aura.punishments.model.Records.Review;
import dev.aura.punishments.model.Records.ReviewEvent;
import dev.aura.punishments.model.Simple.PunishStatus;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.ReviewService.Detail;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * One owner review: the punishment as issued, its evidence status and history, and every reviewer action. Each action
 * is re-checked by {@link dev.aura.punishments.service.ReviewService} when it runs, and uses the review's version so two
 * reviewers can never silently overwrite each other.
 */
final class ReviewMenu extends Menu {
    private Detail d;

    private ReviewMenu(Gui gui, Player viewer, Menu parent, Detail d) {
        super(gui, viewer, parent);
        this.d = d;
    }

    static void open(Gui gui, Player p, Menu parent, long reviewId) {
        gui.call(p, gui.reviews.detail(gui.actor(p), reviewId), det -> new ReviewMenu(gui, p, parent, det).open());
    }

    private void reload() {
        gui.call(viewer, gui.reviews.detail(gui.actor(viewer), d.review().id()), det -> {
            d = det;
            open();
        });
    }

    @Override
    protected Component title() {
        return Items.t("<aqua>Review <v> <gray>- <v2>", "v", d.review().ref(), "v2", d.review().targetName());
    }

    private Review r() {
        return d.review();
    }

    private boolean own() {
        return viewer.getUniqueId().equals(r().issuerUuid());
    }

    @Override
    protected void render() {
        navigation();
        Review r = r();
        Punishment p = d.punishment();
        long now = gui.core().clock.now();
        boolean open = r.status().open() && !r.migrated();
        List<Component> head = new ArrayList<>();
        head.add(Items.t("<gray>Status: <white><v>", "v", r.status().display()));
        head.add(Items.t("<gray>Type: <white><v>", "v", p == null ? r.ptype().display() : p.classification()));
        head.add(Items.t("<gray>Punishment: <white><v>", "v", r.ptype().display() + (r.durationMs() == null ? " (permanent)" : " (" + Durations.format(r.durationMs()) + ")")));
        if (p != null) head.add(Items.t("<gray>Now: <white><v>", "v", p.status().name().toLowerCase() + (p.modifiedAt() != null ? " (modified)" : "")));
        head.add(Items.t("<gray>Issued by: <white><v> <gray>on <v2>", "v", r.issuerName(), "v2", Gui.time(r.issuedAt())));
        head.add(Items.t("<gray>Reason: <white><v>", "v", Text.truncate(r.reason(), 80)));
        if (open && r.evidenceDue() != null) head.add(Items.t(!d.evidenceAttached() && now > r.evidenceDue() ? "<red>Evidence overdue since <v>" : "<gray>Evidence due: <white><v>", "v", Gui.time(r.evidenceDue())));
        if (open && r.reviewDue() != null) head.add(Items.t(now > r.reviewDue() ? "<red>Review overdue since <v>" : "<gray>Review due: <white><v>", "v", Gui.time(r.reviewDue())));
        head.add(Items.t(d.evidenceAttached() ? "<green>Evidence attached" : "<yellow>No evidence attached yet"));
        if (r.reviewerName() != null) head.add(Items.t("<gray>Reviewer: <white><v>", "v", r.reviewerName()));
        if (r.decisionNote() != null) head.add(Items.t("<gray>Decision: <white><v>", "v", Text.truncate(r.decisionNote(), 80)));
        if (r.migrated()) head.add(Items.t("<gray>Imported from 1.0.0 - not reviewable."));
        set(4, Items.item(Material.WRITABLE_BOOK, Items.t("<white><b><v>", "v", r.ref()), head));

        // history
        List<Component> hist = new ArrayList<>();
        List<ReviewEvent> ev = d.events();
        for (int i = Math.max(0, ev.size() - 8); i < ev.size(); i++) {
            ReviewEvent e = ev.get(i);
            hist.add(Items.t("<gray><t> <white><a>: <gray><d>", "t", Gui.time(e.ts()), "a", e.actorName() == null ? "-" : e.actorName(), "d", Text.truncate(e.type() + (e.detail() == null ? "" : " " + e.detail()), 50)));
        }
        if (hist.isEmpty()) hist.add(Items.t("<gray>No history."));
        set(13, Items.item(Material.CLOCK, Items.t("<yellow><b>History"), hist));

        boolean review = gui.can(viewer, Perms.REVIEW_REVIEW);
        // navigation to related records
        if (r.caseId() != null && gui.core().mode.casesVisible()) set(11, Items.item(Material.BOOK, "<aqua><b>Open case", "<gray>Evidence, timeline and notes"), (pl, t) -> CaseMenu.open(gui, pl, this, r.caseId()));
        set(15, Items.item(Material.PLAYER_HEAD, "<green><b>Player profile", "<gray>Open the punishment screen"), (pl, t) ->
                gui.resolve(pl, r.targetName(), tg -> TargetMenu.open(gui, pl, this, tg)));

        action(19, open && review && r.status() == dev.aura.punishments.model.Simple.ReviewStatus.PENDING_REVIEW, Material.SPYGLASS, "<aqua><b>Start review", List.of("<gray>Mark this review as being handled by you"), (pl, t) ->
                gui.call(pl, gui.reviews.take(gui.actor(pl), r.id(), r.version()), x -> reload()));
        action(20, review, Material.OAK_SIGN, "<gray><b>Private note", List.of("<gray>Visible only to reviewers with", "<gray>review.evidence.sensitive"), (pl, t) ->
                gui.prompts().ask(pl, "Type a private reviewer note.", 3, 1000, text -> gui.call(pl, gui.reviews.addNote(gui.actor(pl), r.id(), text), x -> {
                    gui.success(pl, "Note saved.");
                    reload();
                }), this::open));
        action(21, review && !r.migrated(), Material.BELL, "<yellow><b>Request explanation", List.of("<gray>Asks the issuing staff member", "<gray>to explain (they are notified)"), (pl, t) ->
                gui.prompts().ask(pl, "What do you want the issuer to explain?", 5, 600, text -> gui.call(pl, gui.reviews.requestExplanation(gui.actor(pl), r.id(), text), x -> {
                    gui.success(pl, "The issuer has been asked.");
                    reload();
                }), this::open));
        action(22, (own() || review) && !r.migrated() && r.status() != dev.aura.punishments.model.Simple.ReviewStatus.REVERSED, Material.ENDER_EYE, "<green><b>Add evidence",
                List.of("<gray>Supporting evidence for this punishment", own() ? "<gray>(you issued it)" : "<gray>(as a reviewer)"), (pl, t) ->
                        Pickers.addEvidence(gui, pl, this, in -> gui.call(pl, gui.reviews.submitEvidence(gui.actor(pl), r.id(), in), x -> {
                            gui.success(pl, "Evidence added.");
                            reload();
                        })));
        action(23, open && gui.can(viewer, Perms.REVIEW_DEADLINES), Material.CLOCK, "<gold><b>Change deadlines", List.of("<gray>Set the evidence / review deadline", "<gray>from now"), (pl, t) -> deadlines(pl));

        boolean decide = open;
        action(28, decide && review, Material.LIME_CONCRETE, "<green><b>Uphold", List.of("<gray>The punishment stands as issued"), (pl, t) -> note(pl, "Why is the punishment upheld?", n ->
                gui.call(pl, gui.reviews.uphold(gui.actor(pl), r.id(), r.version(), n), x -> done(pl, "Review upheld."))));
        action(29, decide && gui.can(viewer, Perms.REVIEW_MODIFY) && p != null && p.status() == PunishStatus.ACTIVE, Material.CYAN_CONCRETE, "<aqua><b>Modify",
                List.of("<gray>Change the duration and/or the reason"), (pl, t) -> modify(pl));
        action(30, decide && gui.can(viewer, Perms.REVIEW_REVERSE), Material.RED_CONCRETE, "<red><b>Reverse", List.of("<gray>Revokes the punishment"), (pl, t) -> note(pl, "Why is the punishment reversed?", n ->
                new ConfirmMenu(gui, pl, this, "Reverse " + r.ref(), List.of("The " + r.ptype().display().toLowerCase() + " on " + r.targetName() + " will be lifted."), null, () ->
                        gui.call(pl, gui.reviews.reverse(gui.actor(pl), r.id(), r.version(), n), x -> done(pl, "Punishment reversed."))).open()));
        action(31, decide && review, Material.ORANGE_CONCRETE, "<gold><b>Further investigation", List.of("<gray>Keep it open: more information needed"), (pl, t) -> note(pl, "What needs investigating?", n ->
                gui.call(pl, gui.reviews.furtherInvestigation(gui.actor(pl), r.id(), r.version(), n), x -> done(pl, "Marked for further investigation."))));
        action(32, decide && review && gui.can(viewer, Perms.CASE_CREATE) && gui.core().mode.casesAvailable(), Material.ANVIL,
                gui.core().mode.casework() ? "<light_purple><b>Open a case to investigate" : "<light_purple><b>Refer to a judicial case",
                gui.core().mode.casework() ? List.of("<gray>Opens a case for further investigation", "<gray>(the punishment stays in force)")
                        : List.of("<gray>Opens a standard case for a full hearing", "<gray>(the punishment stays in force)"), (pl, t) ->
                note(pl, "Why does this need a full case?", n -> gui.call(pl, gui.reviews.refer(gui.actor(pl), r.id(), r.version(), n), caseId -> {
                    gui.success(pl, "Case AP-" + String.format("%04d", caseId) + " opened.");
                    CaseMenu.open(gui, pl, this, caseId);
                })));
    }

    private void done(Player pl, String msg) {
        gui.success(pl, msg);
        reload();
    }

    private void note(Player pl, String question, java.util.function.Consumer<String> then) {
        gui.prompts().ask(pl, question + " (recorded with your decision)", 5, 1000, then, this::open);
    }

    private void deadlines(Player pl) {
        Pickers.customDuration(gui, pl, false, ev -> Pickers.customDuration(gui, pl, false, rv -> {
            long now = gui.core().clock.now();
            gui.call(pl, gui.reviews.setDeadlines(gui.actor(pl), r().id(), now + ev, now + rv), x -> done(pl, "Deadlines updated."));
        }, this::open), this::open);
        gui.messages().sendInfo(pl, "First enter the EVIDENCE deadline (from now), then the REVIEW deadline.");
    }

    private void modify(Player pl) {
        Pickers.customDuration(gui, pl, true, dur -> note(pl, "Why is it being modified?", n -> gui.call(pl,
                gui.reviews.modify(gui.actor(pl), r().id(), r().version(), dur, null, n), x -> done(pl, "Punishment modified."))), this::open);
        gui.messages().sendInfo(pl, "Enter the NEW total duration, counted from when the punishment was issued.");
    }
}
