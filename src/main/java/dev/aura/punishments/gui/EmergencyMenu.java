package dev.aura.punishments.gui;

import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.service.DirectService;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.util.Durations;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Emergency protective action: short, auto-expiring, reviewed by senior staff. */
final class EmergencyMenu extends Menu {
    private static final long[] PRESETS = {Durations.HOUR, 3 * Durations.HOUR, 6 * Durations.HOUR, 12 * Durations.HOUR, 24 * Durations.HOUR};

    private final Target target;
    private PunishType type;
    private long duration;
    private String reason = "";

    private EmergencyMenu(Gui gui, Player viewer, Menu parent, Target target) {
        super(gui, viewer, parent);
        this.target = target;
        this.duration = gui.settings().emergency.defaultDuration();
    }

    static void open(Gui gui, Player p, Menu parent, Target t) {
        new EmergencyMenu(gui, p, parent, t).open();
    }

    @Override
    protected Component title() {
        return Items.t("<dark_red>EMERGENCY: <white><v>", "v", target.name());
    }

    @Override
    protected void render() {
        navigation();
        var s = gui.settings();
        set(4, Items.head(target.uuid(), target.name(), Items.t("<white><b><v>", "v", target.name()), List.of(
                Items.t("<red>Emergency protective action"), Items.t("<gray>Max duration: <white><v>", "v", Durations.format(s.emergency.maxDuration())),
                Items.t("<gray>Senior review due within: <white><v>", "v", Durations.format(s.emergency.reviewDeadline())),
                Items.t("<gray>Expires automatically unless replaced."))));
        int slot = 10;
        for (PunishType t : s.emergency.allowedTypes()) {
            boolean sel = t == type;
            set(slot++, Items.item(Views.punishIcon(t), Items.t((sel ? "<green><b>" : "<white>") + t.display()), List.of(Items.t(sel ? "<green>Selected" : "<gray>Click to select"))), (p, k) -> {
                type = t;
                refresh();
            });
        }
        List<Long> list = new ArrayList<>();
        for (long d : PRESETS) if (d <= s.emergency.maxDuration()) list.add(d);
        Pickers.durationRow(this, 18, list, duration, d -> d <= s.emergency.maxDuration(), ms -> {
            duration = ms;
            refresh();
        });
        set(30, Items.item(Material.WRITABLE_BOOK, Items.t("<gold><b>Describe the urgent threat"), reason.isBlank() ? List.of(Items.t("<red>Required"))
                : NotesWrap.wrap(reason, 40).stream().map(x -> Items.t("<white><v>", "v", x)).toList()), (p, k) ->
                gui.prompts().ask(p, "Describe the urgent threat (recorded and sent to senior staff).", Math.max(15, s.cases.minReason()), s.cases.maxReason(), text -> {
                    reason = text;
                    open();
                }, this::open));
        boolean ready = type != null && duration > 0 && reason.length() >= Math.max(15, s.cases.minReason());
        set(40, Items.item(ready ? Material.LIME_CONCRETE : Material.RED_CONCRETE, Items.t(ready ? "<red><b>APPLY EMERGENCY ACTION" : "<red><b>Incomplete"), List.of()), (p, k) -> {
            if (!ready) {
                gui.messages().sendError(p, "Choose the action and duration and describe the threat first.");
                return;
            }
            new ConfirmMenu(gui, p, this, "EMERGENCY " + type.display() + " " + target.name(), List.of("Duration: " + Views.duration(duration),
                    "Reason: " + reason, "Senior staff are notified immediately.", "It expires on its own unless replaced."), null, () ->
                    gui.call(p, gui.direct.emergency(gui.actor(p), new DirectService.EmergencyRequest(target, type, duration, reason)), pun -> {
                        gui.success(p, "Emergency " + type.display().toLowerCase() + " applied to " + target.name() + " (case AP-" + String.format("%04d", pun.caseId()) + ").");
                        if (parent != null) parent.open();
                        else p.closeInventory();
                    }, this::open)).open();
        });
    }
}
