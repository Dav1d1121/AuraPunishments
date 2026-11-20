package dev.aura.punishments.gui;

import dev.aura.punishments.model.PunishType;
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
    private final List<EvidenceValidator.Input> evidence = new ArrayList<>();

    private QuickPunishMenu(Gui gui, Player viewer, Menu parent, Target target, PunishType type) {
        super(gui, viewer, parent);
        this.target = target;
        this.type = type;
    }

    static void open(Gui gui, Player p, Menu parent, Target t, PunishType type) {
        new QuickPunishMenu(gui, p, parent, t, type).open();
    }

    @Override
    protected Component title() {
        return Items.t("<dark_purple><t>: <white><v>", "t", type.display(), "v", target.name());
    }

    private boolean allowed(long ms) {
        return gui.core().auth.checkDuration(gui.actor(viewer), type, ms, false).isOk();
    }

    @Override
    protected void render() {
        navigation();
        boolean needsEvidence = gui.settings().quick.requireEvidence().contains(type);
        set(4, Items.head(target.uuid(), target.name(), Items.t("<white><b><v>", "v", target.name()),
                List.of(Items.t("<gray>Action: <white><v>", "v", type.display()))));
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
        if (needsEvidence || !evidence.isEmpty()) {
            List<Component> ev = new ArrayList<>();
            ev.add(Items.t(needsEvidence ? "<red>Required for " + type.display() + "s" : "<gray>Optional"));
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
            boolean heavy = type == PunishType.BAN || type == PunishType.IPBAN || (type.canBeTimed() && duration == Durations.PERMANENT);
            new ConfirmMenu(gui, p, this, type.display() + " " + target.name(), lines, heavy ? target.name() : null, () ->
                    gui.call(p, gui.punishments.issueQuick(gui.actor(p), new PunishmentService.Request(target, type, type.canBeTimed() ? duration : 0, r, List.copyOf(evidence))), pun -> {
                        gui.success(p, type.display() + " issued to " + target.name() + " (#" + pun.id() + ")"
                                + (type == PunishType.WARN && !pun.delivered() ? " - will be shown at next login." : "."));
                        if (parent != null) parent.open();
                        else p.closeInventory();
                    }, this::open)).open();
        });
    }
}
