package dev.aura.punishments.gui;

import dev.aura.punishments.config.Offense;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Records.Case;
import dev.aura.punishments.model.Records.Custody;
import dev.aura.punishments.model.Simple.CustodyStatus;
import dev.aura.punishments.service.CaseService.Detail;
import dev.aura.punishments.service.JudicialService;
import dev.aura.punishments.util.Durations;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The judge's sentencing screen: type, duration (with recommendations), time-served credit, reasoning. */
final class SentenceMenu extends Menu {
    private static final PunishType[] TYPES = {PunishType.WARN, PunishType.MUTE, PunishType.BAN, PunishType.IPBAN, PunishType.JAIL};

    private final Detail d;
    private final Optional<Offense> offense;
    private PunishType type;
    private long duration;
    private boolean credit = true;
    private String reasoning = "";

    private SentenceMenu(Gui gui, Player viewer, Menu parent, Detail d) {
        super(gui, viewer, parent);
        this.d = d;
        this.offense = gui.core().offenses.get(d.c().offenseKey());
        offense.ifPresent(o -> {
            var rec = o.recommend(d.priorFindings());
            if (rec != null) {
                type = rec.type();
                duration = rec.duration();
            }
        });
    }

    static void open(Gui gui, Player p, Menu parent, Detail d) {
        new SentenceMenu(gui, p, parent, d).open();
    }

    @Override
    protected Component title() {
        return Items.t("<dark_purple>Sentence: <white><v>", "v", d.c().ref());
    }

    private boolean timed() {
        return type != null && type.canBeTimed();
    }

    private boolean durationAllowed(long ms) {
        if (type == null) return false;
        var actor = gui.actor(viewer);
        if (!gui.core().auth.checkDuration(actor, type, ms, false).isOk()) return false;
        if (type == PunishType.JAIL && ms == Durations.PERMANENT) return false;
        return offense.map(o -> !o.exceedsCap(ms)).orElse(true);
    }

    private long creditEstimate() {
        Custody cu = d.custody();
        if (!credit || !timed() || duration == Durations.PERMANENT || cu == null || cu.status() != CustodyStatus.ACTIVE || cu.startedAt() == null) return 0;
        var ts = gui.settings().judicial.timeServed();
        if (!ts.enabled() || !ts.appliesTo().contains(type)) return 0;
        long served = Math.max(0, gui.core().clock.now() - cu.startedAt());
        return Math.min(duration, (long) (served * ts.rate()));
    }

    @Override
    protected void render() {
        navigation();
        Case c = d.c();
        List<Component> head = new ArrayList<>();
        head.add(Items.t("<gray>Accused: <white><v>", "v", c.accusedName()));
        head.add(Items.t("<gray>Offense: <white><v>", "v", offense.map(Offense::display).orElse(c.offenseKey())));
        head.add(Items.t("<gray>Prior guilty findings: <white><v>", "v", String.valueOf(d.priorFindings())));
        Offense.Recommendation rec = offense.map(o -> o.recommend(d.priorFindings())).orElse(null);
        head.add(Items.t(rec == null ? "<gray>No recommendation" : "<gold>★ Recommended: <white><v>", "v", rec == null ? "" : rec.describe()));
        head.add(Items.t("<dark_gray>The judge keeps the final decision."));
        set(4, Items.item(Material.NETHERITE_AXE, Items.t("<white><b>Impose sentence"), head));

        for (int i = 0; i < TYPES.length; i++) {
            PunishType t = TYPES[i];
            boolean ok = offense.map(o -> o.allows(t)).orElse(true) && (t != PunishType.JAIL || gui.core().jail.available());
            boolean sel = t == type;
            String star = rec != null && rec.type() == t ? " <gold>★" : "";
            if (ok) {
                set(10 + i, Items.item(Views.punishIcon(t), Items.t((sel ? "<green><b>" : "<white>") + t.display() + star),
                        List.of(Items.t(sel ? "<green>Selected" : "<gray>Click to select"))), (p, k) -> {
                    type = t;
                    if (!t.canBeTimed()) duration = 0;
                    else if (duration == 0 || !durationAllowed(duration)) duration = firstPreset();
                    refresh();
                });
            } else {
                set(10 + i, Items.item(Material.GRAY_DYE, Items.t("<dark_gray><st>" + t.display()),
                        List.of(Items.t(t == PunishType.JAIL && !gui.core().jail.available() ? "<red>JailReloaded unavailable" : "<red>Not allowed for this offense"))));
            }
        }
        if (timed()) {
            List<Long> presets = new ArrayList<>(offense.map(o -> o.presets().getOrDefault(type, List.of())).orElse(List.of()));
            if (rec != null && rec.type() == type && !presets.contains(rec.duration())) presets.add(rec.duration());
            Pickers.durationRow(this, 18, presets.size() > 9 ? presets.subList(0, 9) : presets, duration, this::durationAllowed, ms -> {
                duration = ms;
                refresh();
            });
            set(28, Items.item(Material.COMPASS, Items.t("<yellow><b>Custom duration"), List.of(Items.t("<gray>Selected: <white><v>", "v", duration == 0 ? "none" : Views.duration(duration)))),
                    (p, k) -> Pickers.customDuration(gui, p, type != PunishType.JAIL, ms -> {
                        if (!durationAllowed(ms)) {
                            gui.messages().sendError(p, "That duration is above your authority or the offense's cap.");
                        } else duration = ms;
                        open();
                    }, this::open));
            long cr = creditEstimate();
            set(30, Items.item(credit ? Material.LIME_DYE : Material.GRAY_DYE, Items.t(credit ? "<green><b>Time-served credit: ON" : "<gray><b>Time-served credit: OFF"),
                    List.of(Items.t("<gray>Pretrial custody: <white><v>", "v", d.custody() == null ? "none" : "yes"),
                            Items.t("<gray>Credit now: <white><v>", "v", cr > 0 ? Durations.format(cr) : "0"), Items.t("<yellow>Click to toggle"))), (p, k) -> {
                credit = !credit;
                refresh();
            });
        }
        set(32, Items.item(Material.WRITABLE_BOOK, Items.t("<gold><b>Reasoning"), reasoning.isBlank()
                ? List.of(Items.t("<red>Required - click to enter"))
                : NotesWrap.wrap(reasoning, 40).stream().map(s -> Items.t("<white><v>", "v", s)).toList()), (p, k) ->
                gui.prompts().ask(p, "Enter the sentencing reasoning.", gui.settings().cases.minReason(), gui.settings().cases.maxReason(), text -> {
                    reasoning = text;
                    open();
                }, this::open));

        boolean ready = type != null && (!timed() || duration != 0) && reasoning.length() >= gui.settings().cases.minReason();
        set(40, Items.item(ready ? Material.LIME_CONCRETE : Material.RED_CONCRETE, Items.t(ready ? "<green><b>IMPOSE SENTENCE" : "<red><b>Incomplete"),
                List.of(Items.t("<gray>Choose a sentence, duration (if timed)"), Items.t("<gray>and enter the reasoning."))), (p, k) -> {
            if (!ready) {
                gui.messages().sendError(p, "Choose the sentence, duration and enter the reasoning first.");
                return;
            }
            List<String> lines = new ArrayList<>();
            lines.add("Accused: " + c.accusedName());
            lines.add("Sentence: " + type.display() + (timed() ? " " + Views.duration(duration) : ""));
            if (creditEstimate() > 0) lines.add("Time-served credit: " + Durations.format(creditEstimate()));
            lines.add("Reasoning: " + reasoning);
            if (d.custody() != null) lines.add(type == PunishType.JAIL ? "The existing JailReloaded record becomes the sentence." : "Pretrial custody is released first.");
            new ConfirmMenu(gui, p, this, "Sentence " + c.ref(), lines, null, () ->
                    gui.call(p, gui.judicial.sentence(gui.actor(p), c.id(), c.version(), new JudicialService.SentenceSpec(type, timed() ? duration : 0, reasoning, credit)), r -> {
                        gui.success(p, "Sentence imposed: " + type.display() + (timed() ? " " + Views.duration(duration) : "")
                                + (r.creditedMs() > 0 ? " (credit " + Durations.format(r.creditedMs()) + ")" : "") + ".");
                        CaseMenu.open(gui, p, parent == null ? null : parent.parent, c.id());
                    }, this::open)).open();
        });
    }

    private long firstPreset() {
        return offense.map(o -> o.presets().getOrDefault(type, List.of())).orElse(List.of()).stream().filter(this::durationAllowed).findFirst().orElse(0L);
    }
}
