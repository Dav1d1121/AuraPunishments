package dev.aura.punishments.gui;

import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.service.EvidenceValidator;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.service.PunishmentService;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Routine punishments (warn, kick, mute, ban, IP ban) within the staff member's authority. */
final class QuickPunishMenu extends Menu {
    private static final long[] PRESETS = {15 * Durations.MINUTE, 30 * Durations.MINUTE, Durations.HOUR, 3 * Durations.HOUR, 6 * Durations.HOUR,
            12 * Durations.HOUR, Durations.DAY, 3 * Durations.DAY, 7 * Durations.DAY, 14 * Durations.DAY, 30 * Durations.DAY, 90 * Durations.DAY,
            180 * Durations.DAY, Durations.PERMANENT};

    private final Target target;
    private final PunishType type;
    private long duration;
    private String reason = "";
    private String note = "";
    private final List<EvidenceValidator.Input> evidence = new ArrayList<>();
    /** Case workflow: the case this punishment is issued from (null for a plain quick punishment). */
    private final Case linked;
    private final long caseEvidence;
    private final Runnable afterIssue;

    private QuickPunishMenu(Gui gui, Player viewer, Menu parent, Target target, PunishType type, Case linked, long caseEvidence, Runnable afterIssue) {
        super(gui, viewer, parent);
        this.target = target;
        this.type = type;
        this.linked = linked;
        this.caseEvidence = caseEvidence;
        this.afterIssue = afterIssue;
    }

    static void open(Gui gui, Player p, Menu parent, Target t, PunishType type) {
        new QuickPunishMenu(gui, p, parent, t, type, null, 0, null).open();
    }

    /** Punish from a case (case workflow): evidence and notes go on the case, the case stays open. */
    static void openForCase(Gui gui, Player p, Menu parent, Target t, PunishType type, Case c, long activeCaseEvidence, Runnable afterIssue) {
        new QuickPunishMenu(gui, p, parent, t, type, c, activeCaseEvidence, afterIssue).open();
    }

    @Override
    protected Component title() {
        return linked == null ? Items.t("<dark_purple><t>: <white><v>", "t", type.display(), "v", target.name())
                : Items.t("<dark_purple><t> <gray>from <white><c>", "t", type.display(), "c", linked.ref());
    }

    private boolean allowed(long ms) {
        return gui.core().auth.checkDuration(gui.actor(viewer), type, ms, false).isOk();
    }

    @Override
    protected void render() {
        navigation();
        // Evidence already on the case counts toward the requirement when punishing from a case.
        boolean needsEvidence = gui.settings().quick.requireEvidence().contains(type) && (linked == null || caseEvidence == 0);
        // With the judicial system off and no case, evidence and notes are attached to the punishment itself.
        boolean recordsOnPunishment = !gui.core().mode.enabled() && linked == null;
        List<Component> headLore = new ArrayList<>();
        headLore.add(Items.t("<gray>Action: <white><v>", "v", type.display()));
        if (linked != null) {
            headLore.add(Items.t("<gray>From case: <white><v>", "v", linked.ref()));
            headLore.add(Items.t("<gray>Evidence already on the case: <white><v>", "v", String.valueOf(caseEvidence)));
        }
        set(4, Items.head(target.uuid(), target.name(), Items.t("<white><b><v>", "v", target.name()), headLore));
        if (type.canBeTimed()) {
            List<Long> shown = new ArrayList<>();
            for (long d : PRESETS) shown.add(d);
            Pickers.durationRow(this, 18, shown.subList(0, 9), duration, this::allowed, ms -> {
                duration = ms;
                refresh();
            });
            Pickers.durationRow(this, 27, shown.subList(9, shown.size()), duration, this::allowed, ms -> {
                duration = ms;
                refresh();
            });
            long max = gui.core().auth.maxDuration(gui.actor(viewer), type);
            set(10, Items.item(Material.COMPASS, Items.t("<yellow><b>Custom duration"), List.of(Items.t("<gray>Selected: <white><v>", "v", duration == 0 ? "none" : Views.duration(duration)),
                    Items.t("<gray>Your limit: <white><v>", "v", viewer.hasPermission(Perms.punishPermanent(type)) ? "permanent" : (max == 0 ? "none" : Durations.format(max))))),
                    (p, k) -> Pickers.customDuration(gui, p, true, ms -> {
                        if (!allowed(ms)) gui.messages().sendError(p, "That duration is above your limit.");
                        else duration = ms;
                        open();
                    }, this::open));
        }
        set(12, Items.item(Material.WRITABLE_BOOK, Items.t("<gold><b>Reason" + (type == PunishType.KICK && !gui.settings().quick.requireReason() ? " <gray>(optional)" : "")),
                reason.isBlank() ? List.of(Items.t("<red>Required - click to enter")) : NotesWrap.wrap(reason, 40).stream().map(s -> Items.t("<white><v>", "v", s)).toList()),
                (p, k) -> gui.prompts().ask(p, "Type the reason shown to the player and recorded in history.", gui.settings().cases.minReason(),
                        gui.settings().cases.maxReason(), text -> {
                            reason = text;
                            open();
                        }, this::open));
        if (needsEvidence || !evidence.isEmpty() || ((recordsOnPunishment || linked != null) && type != PunishType.KICK)) {
            List<Component> ev = new ArrayList<>();
            ev.add(Items.t(needsEvidence ? "<red>Required for " + type.display() + "s" : "<gray>Optional"));
            if (linked != null) ev.add(Items.t("<gray>New items are added to " + linked.ref()));
            for (EvidenceValidator.Input in : evidence) ev.add(Items.t("<dark_gray>- <white><t>: <aqua><v>", "t", in.type().name().toLowerCase(), "v", Text.truncate(in.reference(), 32)));
            ev.add(Items.t("<yellow>Click to add  <dark_gray>| <yellow>Shift+Click: remove last"));
            set(14, Items.item(Material.SPYGLASS, Items.t("<gold><b>Evidence"), ev), (p, k) -> {
                if (k.isShiftClick()) {
                    if (!evidence.isEmpty()) evidence.remove(evidence.size() - 1);
                    refresh();
                    return;
                }
                Pickers.addEvidence(gui, p, this, in -> {
                    evidence.add(in);
                    open();
                });
            });
        }
        if ((recordsOnPunishment || linked != null) && viewer.hasPermission(Perms.NOTE_ADD)) {
            set(16, Items.item(Material.OAK_SIGN, Items.t("<gold><b>Staff note <gray>(optional)"),
                    note.isBlank() ? List.of(Items.t("<gray>A private note for other staff"), Items.t("<yellow>Click to enter"))
                            : NotesWrap.wrap(note, 40).stream().map(s -> Items.t("<white><v>", "v", s)).toList()),
                    (p, k) -> gui.prompts().ask(p, "Type a staff note (not shown to the player).", 3, gui.settings().cases.maxNote(), text -> {
                        note = text;
                        open();
                    }, this::open));
        }
        boolean ready = (!type.canBeTimed() || duration != 0) && (reason.length() >= gui.settings().cases.minReason() || (type == PunishType.KICK && !gui.settings().quick.requireReason()))
                && (!needsEvidence || !evidence.isEmpty());
        set(40, Items.item(ready ? Material.LIME_CONCRETE : Material.RED_CONCRETE, Items.t(ready ? "<green><b>CONTINUE" : "<red><b>Incomplete"),
                List.of(Items.t("<gray>You will be asked to confirm."))), (p, k) -> {
            if (!ready) {
                gui.messages().sendError(p, "Choose a duration, enter a reason" + (needsEvidence ? " and add evidence" : "") + " first.");
                return;
            }
            String r = reason.isBlank() ? "No reason given" : reason;
            List<String> lines = new ArrayList<>();
            lines.add("Player: " + target.name());
            lines.add("Action: " + type.display() + (type.canBeTimed() ? " " + Views.duration(duration) : ""));
            lines.add("Reason: " + r);
            if (!evidence.isEmpty()) lines.add("Evidence items: " + evidence.size());
            if (!note.isBlank()) lines.add("Staff note attached");
            if (type == PunishType.JAIL) lines.add("The player is jailed through JailReloaded and released automatically when the time is up.");
            if (linked != null) lines.add("Issued from case " + linked.ref() + " - the case stays open for notes and appeals.");
            if (recordsOnPunishment || linked != null) lines.add("This punishment takes effect immediately and is final.");
            boolean heavy = gui.settings().quick.typeNameToConfirm()
                    && (type == PunishType.BAN || type == PunishType.IPBAN || (type.canBeTimed() && duration == Durations.PERMANENT));
            new ConfirmMenu(gui, p, this, type.display() + " " + target.name(), lines, heavy ? target.name() : null, () ->
                    gui.call(p, issue(p, r), pun -> {
                        gui.success(p, type.display() + " issued to " + target.name() + " (#" + pun.id() + ")"
                                + (type == PunishType.WARN && !pun.delivered() ? " - will be shown at next login." : "."));
                        if (afterIssue != null) afterIssue.run();
                        else if (parent != null) parent.open();
                        else p.closeInventory();
                    }, this::open)).open();
        });
    }

    private java.util.concurrent.CompletableFuture<dev.aura.punishments.util.Result<dev.aura.punishments.model.Records.Punishment>> issue(Player p, String r) {
        var req = new PunishmentService.Request(target, type, type.canBeTimed() ? duration : 0, r, List.copyOf(evidence), note.isBlank() ? null : note);
        return linked == null ? gui.punishments.issueQuick(gui.actor(p), req) : gui.punishments.issueForCase(gui.actor(p), linked.id(), req);
    }
}
