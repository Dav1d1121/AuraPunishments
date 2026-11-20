package dev.aura.punishments.gui;

import dev.aura.punishments.config.Offense;
import dev.aura.punishments.model.PunishType;
import dev.aura.punishments.model.Simple.Severity;
import dev.aura.punishments.service.DirectService;
import dev.aura.punishments.service.EvidenceValidator;
import dev.aura.punishments.service.Perms;
import dev.aura.punishments.service.PlayerResolver.Target;
import dev.aura.punishments.util.Durations;
import dev.aura.punishments.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Direct punishment for MAJOR offenses. Deliberately the harder path: eligible offense, evidence,
 * written description, written justification for bypassing the hearing, and a confirmation.
 */
final class DirectMenu extends Menu {
    private final Target target;
    private Offense offense;
    private Severity severity = Severity.MAJOR;
    private PunishType type;
    private long duration;
    private String description = "";
    private String justification = "";
    private final List<EvidenceValidator.Input> evidence = new ArrayList<>();

    private DirectMenu(Gui gui, Player viewer, Menu parent, Target target) {
        super(gui, viewer, parent);
        this.target = target;
    }

    static void open(Gui gui, Player p, Menu parent, Target t) {
        new DirectMenu(gui, p, parent, t).open();
    }

    @Override
    protected Component title() {
        return Items.t("<dark_red>Direct punishment: <white><v>", "v", target.name());
    }

    private boolean durationAllowed(long ms) {
        if (type == null) return false;
        if (!gui.core().auth.checkDuration(gui.actor(viewer), type, ms, true).isOk()) return false;
        if (offense != null) {
            if (ms == Durations.PERMANENT) return offense.maxDuration() == Durations.PERMANENT;
            return !offense.exceedsCap(ms);
        }
        return true;
    }

    @Override
    protected void render() {
        navigation();
        var s = gui.settings();
        int needEv = offense == null ? s.direct.minEvidence() : Math.max(s.direct.minEvidence(), offense.minEvidenceDirect());
        set(4, Items.head(target.uuid(), target.name(), Items.t("<white><b><v>", "v", target.name()),
                List.of(Items.t("<red>Direct punishment - major offenses only"), Items.t("<gray>The hearing is bypassed, so evidence and a"),
                        Items.t("<gray>written justification are mandatory."))));

        // offense
        set(10, Items.item(offense == null ? Material.BOOKSHELF : Items.material(offense.icon(), Material.PAPER), Items.t("<gold><b>1. Major offense"),
                List.of(Items.t(offense == null ? "<red>Required" : "<white><v>", "v", offense == null ? "" : offense.display()), Items.t("<yellow>Click to choose"))),
                (p, k) -> new OffenseSelectMenu(gui, p, this, Offense::directEligible, "Eligible for direct punishment", o -> {
                    offense = o;
                    severity = o.severity().ordinal() >= s.direct.minSeverity().ordinal() ? o.severity() : s.direct.minSeverity();
                    if (type != null && !o.allows(type)) type = null;
                    duration = 0;
                    open();
                }).open());
        // severity
        set(11, Items.item(Views.severityIcon(severity), Items.t("<gold><b>2. Severity"),
                List.of(Items.t("<white><v>", "v", severity.name()), Items.t("<gray>Minimum: <white><v>", "v", s.direct.minSeverity().name()), Items.t("<yellow>Click to cycle"))), (p, k) -> {
            Severity next = Severity.values()[(severity.ordinal() + 1) % Severity.values().length];
            severity = next.ordinal() < s.direct.minSeverity().ordinal() ? s.direct.minSeverity() : next;
            refresh();
        });
        // type
        int slot = 12;
        for (PunishType t : DirectService.DIRECT_TYPES) {
            boolean ok = viewer.hasPermission(Perms.direct(t)) && (offense == null || offense.allows(t));
            boolean sel = t == type;
            if (ok) {
                set(slot, Items.item(Views.punishIcon(t), Items.t((sel ? "<green><b>" : "<white>") + t.display()), List.of(Items.t(sel ? "<green>Selected" : "<gray>Click to select"))), (p, k) -> {
                    type = t;
                    duration = 0;
                    refresh();
                });
            } else {
                set(slot, Items.item(Material.GRAY_DYE, Items.t("<dark_gray><st>" + t.display()), List.of(Items.t("<red>Not permitted"))));
            }
            slot++;
        }
        // duration
        if (type != null) {
            List<Long> presets = new ArrayList<>(offense == null ? List.of() : offense.presets().getOrDefault(type, List.of()));
            Pickers.durationRow(this, 18, presets.size() > 9 ? presets.subList(0, 9) : presets, duration, this::durationAllowed, ms -> {
                duration = ms;
                refresh();
            });
            set(27, Items.item(Material.COMPASS, Items.t("<yellow><b>Custom duration"), List.of(Items.t("<gray>Selected: <white><v>", "v", duration == 0 ? "none" : Views.duration(duration)))),
                    (p, k) -> Pickers.customDuration(gui, p, true, ms -> {
                        if (!durationAllowed(ms)) gui.messages().sendError(p, "That duration is above your authority or this offense's cap.");
                        else duration = ms;
                        open();
                    }, this::open));
        }
        // description + justification
        set(29, Items.item(Material.WRITABLE_BOOK, Items.t("<gold><b>3. Detailed description"), textLore(description, "<red>Required (min " + s.direct.minDescription() + " chars)")), (p, k) ->
                gui.prompts().ask(p, "Describe in detail what the player did.", s.direct.minDescription(), s.cases.maxDescription(), text -> {
                    description = text;
                    open();
                }, this::open));
        set(30, Items.item(Material.BOOK, Items.t("<gold><b>4. Why skip the hearing?"), textLore(justification, "<red>Required (min " + s.direct.minJustification() + " chars)")), (p, k) ->
                gui.prompts().ask(p, "Explain why bypassing the ordinary judicial process is justified.", s.direct.minJustification(), s.cases.maxDescription(), text -> {
                    justification = text;
                    open();
                }, this::open));
        List<Component> ev = new ArrayList<>();
        ev.add(Items.t("<gray>Items: <white><n><gray> / required: <white><r>", "n", String.valueOf(evidence.size()), "r", String.valueOf(needEv)));
        for (EvidenceValidator.Input in : evidence) ev.add(Items.t("<dark_gray>- <white><t>: <aqua><v>", "t", in.type().name().toLowerCase(), "v", Text.truncate(in.reference(), 32)));
        ev.add(Items.t("<yellow>Click to add  <dark_gray>| <yellow>Shift+Click: remove last"));
        set(31, Items.item(Material.SPYGLASS, Items.t("<gold><b>5. Evidence"), ev), (p, k) -> {
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

        boolean ready = offense != null && type != null && duration != 0 && description.length() >= s.direct.minDescription()
                && justification.length() >= s.direct.minJustification() && evidence.size() >= needEv;
        set(40, Items.item(ready ? Material.LIME_CONCRETE : Material.RED_CONCRETE, Items.t(ready ? "<green><b>CONTINUE TO CONFIRMATION" : "<red><b>Incomplete"),
                List.of(Items.t("<gray>Every requirement must be met."))), (p, k) -> {
            if (!ready) {
                gui.messages().sendError(p, "Choose the offense and punishment, add the description, justification and the required evidence first.");
                return;
            }
            List<String> lines = new ArrayList<>();
            lines.add("Player: " + target.name());
            lines.add("Offense: " + offense.display() + " (" + severity + ")");
            lines.add("Punishment: " + type.display() + " " + Views.duration(duration));
            lines.add("Evidence items: " + evidence.size());
            lines.add("Justification: " + Text.truncate(justification, 60));
            lines.add("The hearing is bypassed. This is audited.");
            String typed = s.direct.typeNameToConfirm().contains(type) ? target.name() : null;
            new ConfirmMenu(gui, p, this, "DIRECT " + type.display() + " " + target.name(), lines, typed, () ->
                    gui.call(p, gui.direct.direct(gui.actor(p), new DirectService.DirectRequest(target, offense.key(), severity, description, justification,
                            List.copyOf(evidence), type, duration, null)), pun -> {
                        gui.success(p, "Direct " + type.display().toLowerCase() + " issued to " + target.name() + " (case AP-" + String.format("%04d", pun.caseId()) + ").");
                        if (parent != null) parent.open();
                        else p.closeInventory();
                    }, this::open)).open();
        });
    }

    private static List<Component> textLore(String text, String emptyHint) {
        List<Component> l = new ArrayList<>();
        if (text.isBlank()) l.add(Items.t(emptyHint));
        else for (String s : NotesWrap.wrap(text, 40)) l.add(Items.t("<white><v>", "v", s));
        l.add(Items.t("<yellow>Click to edit"));
        return l;
    }
}
